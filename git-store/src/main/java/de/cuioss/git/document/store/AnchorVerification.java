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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
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
 * every listed tag that no longer points at the listed object id ({@link Kind#TAG_RETARGETED}).</li>
 * </ul>
 * A repository with a shallow boundary (JGit 7.8.0 {@code ObjectDatabase.getShallowCommits}) is
 * {@link StoreOutcome#INCOMPLETE}, never a success, because the reachability of a listed object cannot be decided
 * beyond it. The verification changes no reference and writes no object. A finished verification is logged once as
 * {@code GIT_STORE-10} with the provider id and the counts only.
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
        TAG_RETARGETED
    }

    /**
     * One finding of the anchor verification.
     *
     * @param kind      what the finding is about
     * @param subject   the full commit id of the subject or of the checkpoint that lists the reference; for
     *                  {@link Kind#CHECKPOINT_MISSING} the missing tag, {@code refs/tags/checkpoints/<n>}, or the gap
     *                  {@code refs/tags/checkpoints/<n>..<m>}
     * @param reference the listed reference for {@link Kind#REFERENCE_UNREACHABLE} and {@link Kind#TAG_RETARGETED},
     *                  {@code null} otherwise
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
     *         {@code anchors: <simple class name of the exception>} when the repository cannot be read
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
                List<RevCommit> checkpointCommits = new ArrayList<>();
                for (ObjectId target : checkpoints.values()) {
                    commit(walk, target).ifPresent(commit -> {
                        checkpointCommits.add(commit);
                        subjects.add(commit.copy());
                    });
                }
                for (ObjectId target : releases) {
                    commit(walk, target).ifPresent(commit -> subjects.add(commit.copy()));
                }
                String notesRef = StoreReferences.anchorNotes(provider.id());
                int proofs = verifyChain(repository, reader, walk, notesRef, provider, subjects, findings);
                for (RevCommit checkpoint : checkpointCommits) {
                    listedReferences(repository, walk, checkpoint, findings);
                }
                LOGGER.info(GitStoreLogMessages.INFO.ANCHORS_VERIFIED, provider.id(), checkpoints.size(),
                        subjects.size(), proofs, findings.size());
                return StoreResult.ok(new Report(checkpoints.size(), subjects.size(), proofs, findings));
            }
        }
        // an unexpected failure reading the repository ends the verification as a result, never as an exception,
        // naming the class of the failure only (GDR-STORE-17, GDR-NFR-3)
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (IOException | RuntimeException e) {
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
        NoteMap current = NoteMap.newEmptyMap();
        List<RevCommit> notesCommits = new ArrayList<>();
        if (ref != null && ref.getObjectId() != null) {
            RevCommit tip = walk.parseCommit(ref.getObjectId());
            current = NoteMap.read(reader, tip);
            for (RevCommit commit = tip; commit != null;
                 commit = commit.getParentCount() == 0 ? null : walk.parseCommit(commit.getParent(0))) {
                notesCommits.add(commit);
            }
            Collections.reverse(notesCommits);
        }
        byte[] previous = new byte[0];
        NoteMap before = NoteMap.newEmptyMap();
        int proofs = 0;
        for (RevCommit notesCommit : notesCommits) {
            NoteMap after = NoteMap.read(reader, notesCommit);
            List<Note> added = new ArrayList<>();
            for (Note note : after) {
                if (!note.getData().equals(before.get(note))) {
                    added.add(note);
                }
            }
            added.sort(Comparator.comparing(ObjectId::name));
            for (Note note : added) {
                byte[] raw = reader.open(note.getData()).getBytes();
                byte[] proof = AnchorNotes.decode(raw);
                proofs++;
                StoreOutcome outcome = proof == null ? StoreOutcome.SIGNATURE_INVALID
                        : verify(provider, note.copy(), proof, previous);
                if (outcome != StoreOutcome.OK) {
                    findings.add(new Finding(Kind.PROOF_REJECTED, note.name(), null, outcome));
                }
                previous = proof == null ? raw : proof;
            }
            before = after;
        }
        for (ObjectId subject : subjects) {
            if (!current.contains(subject)) {
                findings.add(new Finding(Kind.NO_PROOF, subject.name(), null, null));
            }
        }
        return proofs;
    }

    /** The outcome of the provider for one proof; an exception of the provider rejects the proof. */
    private static StoreOutcome verify(AnchorProvider provider, ObjectId subject, byte[] proof, byte[] previous) {
        try {
            var verified = provider.verify(subject, proof.clone(), previous.clone());
            return verified.outcome() instanceof StoreOutcome outcome ? outcome : StoreOutcome.SIGNATURE_INVALID;
        }
        // a provider is pluggable code; its failure rejects the proof and never ends the verification
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (RuntimeException e) {
            LOGGER.debug("The anchor provider threw %s", e.getClass().getSimpleName());
            return StoreOutcome.SIGNATURE_INVALID;
        }
    }

    /** Reports every reference a checkpoint lists that no longer reaches, or for a tag points at, the listed id. */
    private static void listedReferences(Repository repository, RevWalk walk, RevCommit checkpoint,
            List<Finding> findings) throws IOException {
        String content = new String(Checkpoints.content(repository, checkpoint), UTF_8);
        for (String line : content.split("\n")) {
            int space = line.indexOf(' ');
            if (space < 0 || !ObjectId.isId(line.substring(0, space))) {
                continue;
            }
            ObjectId listed = ObjectId.fromString(line.substring(0, space));
            String name = line.substring(space + 1);
            Ref ref = repository.exactRef(name);
            @Nullable ObjectId now = ref == null ? null : ref.getObjectId();
            if (name.startsWith(StoreReferences.TAGS)) {
                if (!listed.equals(now)) {
                    findings.add(new Finding(Kind.TAG_RETARGETED, checkpoint.name(), name, null));
                }
            } else if (now == null || !reaches(walk, now, listed)) {
                findings.add(new Finding(Kind.REFERENCE_UNREACHABLE, checkpoint.name(), name, null));
            }
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

    /** The commit a tag points at, peeled; empty for a tag that points at no commit or at a missing object. */
    private static Optional<RevCommit> commit(RevWalk walk, ObjectId target) throws IOException {
        try {
            return Optional.of(walk.parseCommit(target));
        } catch (IncorrectObjectTypeException | MissingObjectException e) {
            return Optional.empty();
        }
    }
}
