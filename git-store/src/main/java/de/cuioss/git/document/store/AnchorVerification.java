/*
 * Copyright © 2026-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.git.document.store;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * The anchor verification of GDR-STORE-17 and GDR-STORE-18: checks the checkpoints, the release tags and the stored
 * proofs of one anchor provider in a repository, read only (checked against JGit 7.8.0).
 * <p>
 * {@link #verifyAnchors(Repository, AnchorProvider)} walks the checkpoint tags from {@code refs/tags/checkpoints/1}
 * upward and every release tag, and reports, as {@link Finding}s:
 * <ul>
 * <li>every missing checkpoint number below the highest one ({@link Kind#CHECKPOINT_MISSING}), one finding per gap;</li>
 * <li>every subject, the commit of a checkpoint or of a release tag, without a note in
 * {@code refs/notes/anchors/<provider>} ({@link Kind#NO_PROOF});</li>
 * <li>every stored proof the provider does not verify with {@link StoreOutcome#OK} ({@link Kind#PROOF_REJECTED}): the
 * proofs are verified in chain order, the order in which the notes commits of the notes reference added them along its
 * first parents, each with the proof before it as {@code previous}; a note that is no base64 is rejected as
 * {@link StoreOutcome#SIGNATURE_INVALID};</li>
 * <li>every reference a checkpoint lists in its file {@value Checkpoints#REFS_FILE} whose listed object id is no longer
 * reachable from the reference ({@link Kind#REFERENCE_UNREACHABLE}, also for a reference that no longer exists), and
 * every listed tag that no longer points at the listed object id ({@link Kind#TAG_RETARGETED});</li>
 * <li>the chain of the checkpoints (GDR-STORE-16): a checkpoint whose only parent is not the commit of the checkpoint
 * before it ({@link Kind#CHECKPOINT_CHAIN_BROKEN}), so a moved checkpoint tag or a history rewritten between two
 * checkpoints is reported; a checkpoint tag on the commit of a lower one ({@link Kind#CHECKPOINT_DUPLICATE}); and a
 * checkpoint that cannot be read as one, by its tag, its message, its tree or a line of its file
 * ({@link Kind#CHECKPOINT_UNREADABLE}).</li>
 * </ul>
 * A repository with a shallow boundary (JGit 7.8.0 {@code ObjectDatabase.getShallowCommits}) is
 * {@link StoreOutcome#INCOMPLETE}, never a success, because the reachability of a listed object cannot be decided
 * beyond it. A release-shaped tag on a blob or a tree is no subject, as the due check of the anchoring skips it. A
 * checkpoint or release tag that points at a missing object cannot be verified and ends the verification as a
 * failure, never as a clean report. Every note is read bounded by {@link ExplicitSave#BLOB_LIMIT}, as the file of
 * every checkpoint; a larger one ends the verification as a failure. The reachability of a reference that several
 * checkpoints list at the same object id is decided once. The verification changes no reference and writes no object.
 * A finished verification is logged once as {@code GIT_STORE-10} with the provider id and the counts only.
 * <p>
 * Every release tag is a subject: a repository anchored with {@code anchor.releases} set to {@code false} reports a
 * {@link Kind#NO_PROOF} for each release, which the caller of a CI run that knows the setting filters; the
 * verification reads no configuration.
 * <p>
 * The provider is pluggable code and its calls follow the observer rule of the store's callbacks: a
 * {@link RuntimeException} or an {@link Error} other than a {@link VirtualMachineError} of its {@code verify} rejects
 * that proof ({@link Kind#PROOF_REJECTED}) and the verification goes on; one of its {@code id} ends the verification
 * with the passed-on {@link GitOutcome#FAILED}; either is logged by its class name only, and a
 * {@link VirtualMachineError} propagates.
 *
 * @since 0.1
 */
public final class AnchorVerification {

    private static final CuiLogger LOGGER = new CuiLogger(AnchorVerification.class);

    /** The detail of a repository with a shallow boundary. */
    static final String SHALLOW = "anchors: history is shallow";

    private AnchorVerification() {
    }

    /**
     * What a finding of the anchor verification is about: the closed list of the verification.
     *
     * @since 0.1
     */
    public enum Kind {
        /** A checkpoint number below the highest one has no tag; the subject names the gap. */
        CHECKPOINT_MISSING,
        /** A checkpoint or release commit has no note under the notes reference of the provider. */
        NO_PROOF,
        /** The provider did not verify a stored proof; the outcome says why. */
        PROOF_REJECTED,
        /** A reference a checkpoint lists no longer reaches the listed object id, or no longer exists. */
        REFERENCE_UNREACHABLE,
        /** A tag a checkpoint lists no longer points at the listed object id, or no longer exists. */
        TAG_RETARGETED,
        /**
         * A checkpoint does not follow the one before it: checkpoint {@code n} does not have the commit of checkpoint
         * {@code n-1} as its only parent, or the first checkpoint has a parent; a branch rewritten between two
         * checkpoints, or a moved checkpoint tag, shows as this finding.
         */
        CHECKPOINT_CHAIN_BROKEN,
        /** A checkpoint tag points at the same commit as a checkpoint tag with a lower number. */
        CHECKPOINT_DUPLICATE,
        /**
         * A checkpoint cannot be read as one: its tag points at no commit, its message is not
         * {@code Checkpoint <n>}, its tree holds anything but the regular file {@value Checkpoints#REFS_FILE}, or a
         * line of that file is not of the form {@code <object id> SP <reference name>}.
         */
        CHECKPOINT_UNREADABLE
    }

    /**
     * One finding of the anchor verification.
     *
     * @param kind      what the finding is about
     * @param subject   the full commit id of the subject or of the checkpoint that lists the reference; for
     *                  {@link Kind#CHECKPOINT_MISSING} the missing tag, {@code refs/tags/checkpoints/<n>}, or the gap
     *                  {@code refs/tags/checkpoints/<n>..<m>}; for {@link Kind#CHECKPOINT_UNREADABLE} of a tag that
     *                  points at no commit the tag
     * @param reference the listed reference for {@link Kind#REFERENCE_UNREACHABLE} and {@link Kind#TAG_RETARGETED};
     *                  the checkpoint tag for {@link Kind#CHECKPOINT_CHAIN_BROKEN}, {@link Kind#CHECKPOINT_DUPLICATE}
     *                  and {@link Kind#CHECKPOINT_UNREADABLE}, or {@value Checkpoints#REFS_FILE} for a malformed line
     *                  of that file; {@code null} otherwise
     * @param outcome   the outcome of the provider for {@link Kind#PROOF_REJECTED}, {@code null} otherwise
     * @since 0.1
     */
    public record Finding(Kind kind, String subject, @Nullable String reference, @Nullable StoreOutcome outcome) {

        /**
         * Checks the components.
         *
         * @param kind      the kind
         * @param subject   the subject
         * @param reference the reference, or {@code null}
         * @param outcome   the outcome, or {@code null}
         * @throws NullPointerException if {@code kind} or {@code subject} is {@code null}
         */
        public Finding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(subject, "subject");
        }
    }

    /**
     * The report of an anchor verification.
     *
     * @param checkpoints the number of checkpoint tags
     * @param subjects    the number of subjects: checkpoint and release commits
     * @param proofs      the number of proofs verified in chain order
     * @param findings    the findings, empty when the anchoring is complete and every proof verifies
     * @since 0.1
     */
    public record Report(int checkpoints, int subjects, int proofs, List<Finding> findings) {

        /**
         * Copies the findings.
         *
         * @param checkpoints the number of checkpoint tags
         * @param subjects    the number of subjects
         * @param proofs      the number of proofs
         * @param findings    the findings
         * @throws NullPointerException if {@code findings} or a finding is {@code null}
         */
        public Report {
            findings = List.copyOf(findings);
        }

        /**
         * Tells whether the verification found nothing.
         *
         * @return whether there is no finding
         */
        public boolean clean() {
            return findings.isEmpty();
        }
    }

    /**
     * Verifies the checkpoints, the release tags and the stored proofs of a provider.
     *
     * @param repository the repository, read only
     * @param provider   the anchor provider whose notes reference {@code refs/notes/anchors/<id>} holds the proofs
     * @return {@link StoreOutcome#OK} with the report; {@link StoreOutcome#INCOMPLETE} with {@value #SHALLOW} for a
     *         repository with a shallow boundary; the passed-on {@link GitOutcome#FAILED} with
     *         {@code anchors: <simple class name of the exception>} when the repository cannot be read, a checkpoint or
     *         release tag included that points at a missing object, or a note or checkpoint file above
     *         {@link ExplicitSave#BLOB_LIMIT}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static StoreResult<Report> verifyAnchors(Repository repository, AnchorProvider provider) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(provider, "provider");
        try {
            if (!repository.getObjectDatabase().getShallowCommits().isEmpty()) {
                return StoreResult.of(StoreOutcome.INCOMPLETE, SHALLOW);
            }
            List<Finding> findings = new ArrayList<>();
            Map<Integer, ObjectId> checkpoints = new TreeMap<>();
            Set<ObjectId> subjects = new LinkedHashSet<>();
            try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
                List<ObjectId> releases = new ArrayList<>();
                for (Ref ref : repository.getRefDatabase().getRefsByPrefix(StoreReferences.TAGS)) {
                    OptionalInt number = StoreReferences.checkpointNumber(ref.getName());
                    if (number.isPresent()) {
                        checkpoints.put(number.getAsInt(), ref.getObjectId());
                    } else if (StoreReferences.isReleaseTag(ref.getName())) {
                        releases.add(ref.getObjectId());
                    }
                }
                missingCheckpoints(checkpoints, findings);
                Map<Integer, RevCommit> checkpointCommits = new TreeMap<>();
                for (Map.Entry<Integer, ObjectId> checkpoint : checkpoints.entrySet()) {
                    @Nullable RevCommit commit = checkpointCommit(walk, checkpoint.getKey(), checkpoint.getValue(),
                            findings);
                    if (commit != null) {
                        checkpointCommits.put(checkpoint.getKey(), commit);
                        subjects.add(commit.copy());
                    }
                }
                checkpointChain(repository, checkpointCommits, findings);
                for (ObjectId target : releases) {
                    releaseCommit(walk, target).ifPresent(commit -> subjects.add(commit.copy()));
                }
                String notesRef = StoreReferences.anchorNotes(provider.id());
                int proofs = verifyChain(repository, reader, walk, notesRef, provider, subjects, findings);
                Map<String, Boolean> reached = new HashMap<>();
                for (RevCommit checkpoint : checkpointCommits.values()) {
                    listedReferences(repository, walk, checkpoint, reached, findings);
                }
                LOGGER.info(GitStoreLogMessages.INFO.ANCHORS_VERIFIED, provider.id(), checkpoints.size(),
                        subjects.size(), proofs, findings.size());
                return StoreResult.ok(new Report(checkpoints.size(), subjects.size(), proofs, findings));
            }
        }
        // an unexpected failure reading the repository, or of the provider naming itself, ends the verification as a
        // result, never as an exception, naming the class of the failure only (GDR-STORE-17, GDR-NFR-3); the observer
        // rule of StoreEvents isolates every Error but a VirtualMachineError
        // operator-selected: AaIOFP33AtdQWIZKzKJz: isolation rule of StoreEvents.rethrowIfFatal decided by the operator
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (@SuppressWarnings("java:S1181") IOException | RuntimeException | Error e) {
            StoreEvents.rethrowIfFatal(e);
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.FAILED), null,
                    "anchors: " + e.getClass().getSimpleName());
        }
    }

    /** Adds one finding per gap in the checkpoint numbers from 1 to the highest. */
    private static void missingCheckpoints(Map<Integer, ObjectId> checkpoints, List<Finding> findings) {
        int expected = 1;
        for (int number : checkpoints.keySet()) {
            if (number > expected) {
                String gap = StoreReferences.checkpoint(expected) + (number - 1 > expected ? ".." + (number - 1) : "");
                findings.add(new Finding(Kind.CHECKPOINT_MISSING, gap, null, null));
            }
            expected = number + 1;
        }
    }

    /**
     * Verifies every proof of the notes reference in chain order and reports every subject without a note.
     *
     * @return the number of proofs verified
     */
    private static int verifyChain(Repository repository, ObjectReader reader, RevWalk walk, String notesRef,
            AnchorProvider provider, Set<ObjectId> subjects, List<Finding> findings) throws IOException {
        Ref ref = repository.exactRef(notesRef);
        @Nullable ObjectId tipId = ref == null ? null : ref.getObjectId();
        @Nullable RevCommit tip = tipId == null ? null : walk.parseCommit(tipId);
        byte[] previous = new byte[0];
        NoteMap before = NoteMap.newEmptyMap();
        int proofs = 0;
        for (RevCommit notesCommit : firstParents(walk, tip)) {
            NoteMap after = NoteMap.read(reader, notesCommit);
            for (Note note : added(before, after)) {
                previous = verifyProof(reader, provider, note, previous, findings);
                proofs++;
            }
            before = after;
        }
        NoteMap current = tip == null ? NoteMap.newEmptyMap() : NoteMap.read(reader, tip);
        for (ObjectId subject : subjects) {
            if (!current.contains(subject)) {
                findings.add(new Finding(Kind.NO_PROOF, subject.name(), null, null));
            }
        }
        return proofs;
    }

    /** The notes commits along the first parents of the tip, oldest first; none without a tip. */
    private static List<RevCommit> firstParents(RevWalk walk, @Nullable RevCommit tip) throws IOException {
        List<RevCommit> commits = new ArrayList<>();
        for (RevCommit commit = tip; commit != null;
             commit = commit.getParentCount() == 0 ? null : walk.parseCommit(commit.getParent(0))) {
            commits.add(commit);
        }
        Collections.reverse(commits);
        return commits;
    }

    /** The notes a notes commit added or changed against its first parent, by subject id. */
    private static List<Note> added(NoteMap before, NoteMap after) throws IOException {
        List<Note> added = new ArrayList<>();
        for (Note note : after) {
            if (!note.getData().equals(before.get(note))) {
                added.add(note);
            }
        }
        added.sort(Comparator.comparing(ObjectId::name));
        return added;
    }

    /**
     * Verifies one stored proof with the proof before it and reports a rejection; a note that is no base64 is
     * rejected as {@link StoreOutcome#SIGNATURE_INVALID}.
     *
     * @return the {@code previous} of the next proof: this proof, or the raw note when it is no base64
     */
    private static byte[] verifyProof(ObjectReader reader, AnchorProvider provider, Note note, byte[] previous,
            List<Finding> findings) throws IOException {
        byte[] raw = ExplicitSave.readBlob(reader, note.getData());
        byte[] proof = AnchorNotes.decode(raw);
        StoreOutcome outcome = proof == null ? StoreOutcome.SIGNATURE_INVALID
                : verify(provider, note.copy(), proof, previous);
        if (outcome != StoreOutcome.OK) {
            findings.add(new Finding(Kind.PROOF_REJECTED, note.name(), null, outcome));
        }
        return proof == null ? raw : proof;
    }

    /**
     * The outcome of the provider for one proof; a failure of the provider the observer rule of {@link StoreEvents}
     * isolates (a {@link RuntimeException} or an {@link Error} other than a {@link VirtualMachineError}) rejects the
     * proof, logged by its class name only.
     */
    private static StoreOutcome verify(AnchorProvider provider, ObjectId subject, byte[] proof, byte[] previous) {
        try {
            var verified = provider.verify(subject, proof.clone(), previous.clone());
            return verified.outcome() instanceof StoreOutcome outcome ? outcome : StoreOutcome.SIGNATURE_INVALID;
        }
        // a provider is pluggable code; its failure rejects the proof and never ends the verification; the observer
        // rule of StoreEvents isolates every Error but a VirtualMachineError
        // operator-selected: AaIOFP33AtdQWIZKzKJ0: isolation rule of StoreEvents.rethrowIfFatal decided by the operator
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (@SuppressWarnings("java:S1181") RuntimeException | Error e) {
            StoreEvents.rethrowIfFatal(e);
            LOGGER.debug("The anchor provider threw %s", e.getClass().getName());
            return StoreOutcome.SIGNATURE_INVALID;
        }
    }

    /**
     * The commit a checkpoint tag points at, an annotated tag peeled; a tag that points at no commit, or at no object
     * id, is reported as {@link Kind#CHECKPOINT_UNREADABLE} with the tag as its subject and yields {@code null}. A
     * missing object propagates and ends the verification as a failure.
     */
    private static @Nullable RevCommit checkpointCommit(RevWalk walk, int number, @Nullable ObjectId target,
            List<Finding> findings) throws IOException {
        String tag = StoreReferences.checkpoint(number);
        if (target == null) {
            findings.add(new Finding(Kind.CHECKPOINT_UNREADABLE, tag, tag, null));
            return null;
        }
        try {
            return walk.parseCommit(target);
        } catch (IncorrectObjectTypeException e) {
            findings.add(new Finding(Kind.CHECKPOINT_UNREADABLE, tag, tag, null));
            return null;
        }
    }

    /**
     * Checks the chain and the shape of the checkpoints (GDR-STORE-16): every tag points at another commit, the
     * checkpoint {@code n} has the commit of checkpoint {@code n-1} as its only parent (the first one none; across a
     * gap, already reported, the parent is not checked), its message is {@code Checkpoint <n>}, and its tree holds the
     * regular file {@value Checkpoints#REFS_FILE} and nothing else.
     */
    private static void checkpointChain(Repository repository, Map<Integer, RevCommit> checkpoints,
            List<Finding> findings) throws IOException {
        Map<ObjectId, Integer> targets = new HashMap<>();
        for (Map.Entry<Integer, RevCommit> checkpoint : checkpoints.entrySet()) {
            int number = checkpoint.getKey();
            RevCommit commit = checkpoint.getValue();
            String tag = StoreReferences.checkpoint(number);
            @Nullable Integer earlier = targets.putIfAbsent(commit.copy(), number);
            if (earlier != null) {
                findings.add(new Finding(Kind.CHECKPOINT_DUPLICATE, commit.name(), tag, null));
                continue;
            }
            boolean follows;
            if (number == 1) {
                follows = commit.getParentCount() == 0;
            } else {
                // a missing or unreadable predecessor is reported already; its successor's parent cannot be checked
                @Nullable RevCommit previous = checkpoints.get(number - 1);
                follows = previous == null
                        || commit.getParentCount() == 1 && commit.getParent(0).equals(previous);
            }
            if (!follows) {
                findings.add(new Finding(Kind.CHECKPOINT_CHAIN_BROKEN, commit.name(), tag, null));
            }
            if (!(Checkpoints.MESSAGE + number).equals(commit.getFullMessage().stripTrailing())
                    || !refsFileOnly(repository, commit)) {
                findings.add(new Finding(Kind.CHECKPOINT_UNREADABLE, commit.name(), tag, null));
            }
        }
    }

    /** Whether the tree of a checkpoint holds the regular file {@value Checkpoints#REFS_FILE} and nothing else. */
    private static boolean refsFileOnly(Repository repository, RevCommit commit) throws IOException {
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(commit.getTree());
            if (!walk.next() || !Checkpoints.REFS_FILE.equals(walk.getPathString())
                    || !FileMode.REGULAR_FILE.equals(walk.getRawMode(0))) {
                return false;
            }
            return !walk.next();
        }
    }

    /**
     * The commit a release tag points at, an annotated tag peeled; a release-shaped tag on a blob or a tree is no
     * subject, as the due check of the anchoring skips it. A missing object propagates and ends the verification as a
     * failure.
     */
    private static Optional<RevCommit> releaseCommit(RevWalk walk, @Nullable ObjectId target) throws IOException {
        try {
            return Optional.of(commit(walk, target));
        } catch (IncorrectObjectTypeException e) {
            return Optional.empty();
        }
    }

    /**
     * Reports every reference a checkpoint lists that no longer reaches, or for a tag points at, the listed id, and a
     * line of the file {@value Checkpoints#REFS_FILE} that is not of the form {@code <object id> SP <reference name>}
     * as {@link Kind#CHECKPOINT_UNREADABLE}. The reachability of a listed object id from a reference is decided once
     * per verification for each tip the reference is observed at, keyed by the listed id, the reference name and that
     * tip, so a reference that moves during the run is decided again at its new tip, and one that stays unchanged is
     * decided once however many checkpoints list it. Package-private for the test of that cache.
     */
    static void listedReferences(Repository repository, RevWalk walk, RevCommit checkpoint,
            Map<String, Boolean> reached, List<Finding> findings) throws IOException {
        String content = new String(Checkpoints.content(repository, checkpoint), UTF_8);
        boolean unreadable = false;
        for (String line : content.split("\n")) {
            if (!line.isEmpty() && !listedReference(repository, walk, checkpoint, line, reached, findings)) {
                unreadable = true;
            }
        }
        if (unreadable) {
            findings.add(new Finding(Kind.CHECKPOINT_UNREADABLE, checkpoint.name(), Checkpoints.REFS_FILE, null));
        }
    }

    /**
     * Checks one line of the file {@value Checkpoints#REFS_FILE} of a checkpoint: a listed tag that points elsewhere now
     * is {@link Kind#TAG_RETARGETED}, a listed branch or note that is gone or no longer reaches the listed id is
     * {@link Kind#REFERENCE_UNREACHABLE}.
     *
     * @return whether the line is of the form {@code <object id> SP <reference name>}
     */
    private static boolean listedReference(Repository repository, RevWalk walk, RevCommit checkpoint, String line,
            Map<String, Boolean> reached, List<Finding> findings) throws IOException {
        int space = line.indexOf(' ');
        if (space < 0 || !ObjectId.isId(line.substring(0, space)) || space == line.length() - 1) {
            return false;
        }
        ObjectId listed = ObjectId.fromString(line.substring(0, space));
        String name = line.substring(space + 1);
        Ref ref = repository.exactRef(name);
        @Nullable ObjectId now = ref == null ? null : ref.getObjectId();
        if (name.startsWith(StoreReferences.TAGS)) {
            if (!listed.equals(now)) {
                findings.add(new Finding(Kind.TAG_RETARGETED, checkpoint.name(), name, null));
            }
        } else if (now == null || !reached.computeIfAbsent(listed.name() + ' ' + name + ' ' + now.name(),
                pair -> reachesQuietly(walk, now, listed))) {
            findings.add(new Finding(Kind.REFERENCE_UNREACHABLE, checkpoint.name(), name, null));
        }
        return true;
    }

    /** {@link #reaches} for the cache of the listed pairs; an I/O failure propagates unchecked. */
    private static boolean reachesQuietly(RevWalk walk, ObjectId tip, ObjectId listed) {
        try {
            return reaches(walk, tip, listed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Whether the listed object is reachable from the tip of a reference. */
    private static boolean reaches(RevWalk walk, ObjectId tip, ObjectId listed) throws IOException {
        if (tip.equals(listed)) {
            return true;
        }
        try {
            walk.reset();
            return walk.isMergedInto(walk.parseCommit(listed), walk.parseCommit(tip));
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            return false;
        }
    }

    /**
     * The commit a checkpoint or release tag points at, an annotated tag peeled. A tag that points at no commit or at a
     * missing object cannot be verified, so its exception ends the verification as {@link GitOutcome#FAILED} and never
     * as a clean report.
     */
    private static RevCommit commit(RevWalk walk, @Nullable ObjectId target) throws IOException {
        if (target == null) {
            throw new MissingObjectException(ObjectId.zeroId(), "tag target");
        }
        // JGit 7.8.0 RevWalk.parseCommit peels annotated tags and throws IncorrectObjectTypeException for a blob or
        // tree, MissingObjectException for an absent object
        return walk.parseCommit(target);
    }
}
