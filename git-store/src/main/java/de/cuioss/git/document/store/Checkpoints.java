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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;


import de.cuioss.git.document.core.CommitMetadata;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * The checkpoints of one bare clone (GDR-STORE-16): a checkpoint is a commit whose tree holds the single file
 * {@value #REFS_FILE}, one line {@code <object id> SP <reference name> LF} per listed reference sorted by name in byte
 * order ({@link StoreReferences#listedByCheckpoint}: the branches, tags and notes, without the checkpoint tags and the
 * notes of anchor proofs), whose parent is the previous checkpoint (none for the first), whose author and committer is
 * the technical user and whose message is {@code Checkpoint <n>}. It is signed through {@link CommitSignatures} with
 * {@code CommitSigning(signer, null, null)} and carries no identity trailers (GDR-STORE-12, GDR-STORE-13).
 * <p>
 * The commit is reachable only through its tag {@code refs/tags/checkpoints/<n>}, {@code n} from 1, which is written
 * locally by an update that expects it absent and pushed against the zero-id lease, so a tag on the remote is never
 * moved or deleted. Every method runs under the transport lock of the clone, which the caller holds
 * ({@link RepositoryGuard}): the listed references are those the last fetch mirrored, and no pruning fetch of another
 * operation of the process runs between the write of the tag and its push. A checkpoint whose tag push failed is kept
 * under the local-only reference {@code refs/anchoring/checkpoints/<n>}, outside every mirror destination, its local
 * tag is deleted again, so the clone lists only the checkpoints the remote has, and its tag is pushed by the next due
 * check ({@link #repair}) instead of a new checkpoint being made; {@code git-core} pushes one reference per call, so
 * that is the incomplete state reported ({@code GIT_STORE-112}) and repaired here. A local tag that cannot be deleted
 * after a failed push is reported as {@link Standing#STRANDED}, so the due check discovers no subject from it. Every
 * reference write is checked: a tag is pushed only once its kept reference points at the commit.
 */
final class Checkpoints {

    /** The single file of a checkpoint tree. */
    static final String REFS_FILE = "refs";

    /** The message of a checkpoint commit before its number. */
    static final String MESSAGE = "Checkpoint ";

    private static final CuiLogger LOGGER = new CuiLogger(Checkpoints.class);

    private final ExplicitSave.Clone clone;
    private final RepositoryOperations operations;
    private final TechnicalUser user;
    private final ExplicitSave.Committing committing;
    private final InCoreWriter writer = new InCoreWriter();

    /**
     * Creates the checkpoints of a clone.
     *
     * @param clone      the bare clone
     * @param operations the repository operations of {@code git-core}, for the push of a tag
     * @param user       the technical user, author, committer and holder of the push credentials
     * @param committing the identities, the signing seam and the clock of the checkpoint commits
     * @throws NullPointerException if an argument is {@code null}
     */
    Checkpoints(ExplicitSave.Clone clone, RepositoryOperations operations, TechnicalUser user,
            ExplicitSave.Committing committing) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.user = Objects.requireNonNull(user, "user");
        this.committing = Objects.requireNonNull(committing, "committing");
    }

    /**
     * The newest checkpoint of the clone as the last fetch mirrored it.
     * <p>
     * A {@code null} commit means one of two things, told apart by the number: with number 0 there is no checkpoint
     * yet, and the next one is the first, without a parent; with a number above 0 the newest checkpoint tag points at no
     * commit (a blob or a tree), the state is {@linkplain #untargeted() untargeted}, and no checkpoint may follow it,
     * because only the first checkpoint has no parent.
     *
     * @param number the number of its tag; 0 when there is none
     * @param commit the checkpoint commit its tag points at, an annotated tag peeled; {@code null} when there is none
     *               (number 0) or the tag points at no commit (number above 0)
     */
    record Latest(int number, @Nullable ObjectId commit) {

        /**
         * Tells whether the newest checkpoint tag exists but points at no commit.
         *
         * @return whether a checkpoint tag exists and its commit is {@code null}
         */
        boolean untargeted() {
            return number > 0 && commit == null;
        }
    }

    /**
     * How the checkpoints of a clone stand after a repair or the creation of a checkpoint, and so how the due check
     * goes on.
     */
    enum Standing {

        /** Every checkpoint the clone made has its tag on the remote; a due checkpoint may be made. */
        CURRENT,

        /**
         * A kept checkpoint still waits for its tag push; no new checkpoint is made while it is eligible for repair,
         * since it would take its number.
         */
        BLOCKED,

        /**
         * The tag push of a checkpoint failed and its local tag could not be deleted again, so the clone lists a
         * checkpoint the remote lacks; the due check ends before the subjects are discovered.
         */
        STRANDED,

        /**
         * The newest checkpoint tag points at no commit ({@link Latest#untargeted()}), so no checkpoint can follow it;
         * no new checkpoint is made and the due check ends before the subjects are discovered.
         */
        UNTARGETED
    }

    /**
     * What a repair did.
     *
     * @param pushed   the checkpoint commits whose tag the repair pushed, oldest first
     * @param standing {@link Standing#CURRENT} when no kept checkpoint is left that waits for its tag push,
     *                 {@link Standing#BLOCKED} when the push of an eligible one failed, {@link Standing#STRANDED} when
     *                 its local tag also stayed
     */
    record Repair(List<ObjectId> pushed, Standing standing) {

        /**
         * Copies the pushed commits.
         *
         * @param pushed   the pushed commits
         * @param standing the standing
         * @throws NullPointerException if an argument is {@code null}
         */
        Repair {
            pushed = List.copyOf(pushed);
            Objects.requireNonNull(standing, "standing");
        }
    }

    /**
     * Reads the checkpoint tag with the highest number.
     *
     * @return the newest checkpoint, number 0 without any; its commit is {@code null} as {@link Latest} states
     * @throws IOException if the references or the tagged object cannot be read
     */
    Latest latest() throws IOException {
        int highest = 0;
        @Nullable ObjectId target = null;
        for (Ref ref : clone.repository().getRefDatabase().getRefsByPrefix(StoreReferences.CHECKPOINTS)) {
            OptionalInt number = StoreReferences.checkpointNumber(ref.getName());
            if (number.isPresent() && number.getAsInt() > highest && ref.getObjectId() != null) {
                highest = number.getAsInt();
                target = ref.getObjectId();
            }
        }
        return new Latest(highest, target == null ? null : peeled(target));
    }

    /** The commit a tag points at, an annotated tag peeled; {@code null} when it points at no commit. */
    private @Nullable ObjectId peeled(ObjectId target) throws IOException {
        try (RevWalk walk = new RevWalk(clone.repository())) {
            // JGit 7.8.0 RevWalk.parseCommit peels annotated tags until it reaches a commit
            return walk.parseCommit(target).copy();
        } catch (IncorrectObjectTypeException e) {
            LOGGER.debug("The newest checkpoint tag points at no commit");
            return null;
        }
    }

    /**
     * Builds the content of the file {@value #REFS_FILE} from the references of the clone.
     *
     * @param repository the clone
     * @return one line {@code <object id> SP <reference name> LF} per listed reference, sorted by name in byte order
     * @throws IOException if the references cannot be read
     */
    static byte[] refsFile(Repository repository) throws IOException {
        List<Ref> listed = new ArrayList<>();
        for (Ref ref : repository.getRefDatabase().getRefs()) {
            if (!ref.isSymbolic() && ref.getObjectId() != null && StoreReferences.listedByCheckpoint(ref.getName())) {
                listed.add(ref);
            }
        }
        listed.sort((first, second) -> Arrays.compareUnsigned(first.getName().getBytes(UTF_8),
                second.getName().getBytes(UTF_8)));
        var content = new StringBuilder();
        for (Ref ref : listed) {
            content.append(Objects.requireNonNull(ref.getObjectId()).name()).append(' ').append(ref.getName())
                    .append('\n');
        }
        return content.toString().getBytes(UTF_8);
    }

    /**
     * Tells whether a checkpoint is due: none exists yet and a listed reference exists, or the interval has passed
     * since the commit time of the newest checkpoint and the listed references differ from those it lists. An
     * {@linkplain Latest#untargeted() untargeted} newest checkpoint makes none due.
     *
     * @param latest   the newest checkpoint
     * @param listed   the content of {@value #REFS_FILE} for the references now
     * @param now      the current time
     * @param interval {@code anchor.checkpoint-interval}
     * @return whether a checkpoint is due
     * @throws IOException if the newest checkpoint cannot be read
     */
    boolean due(Latest latest, byte[] listed, Instant now, Duration interval) throws IOException {
        @Nullable ObjectId previous = latest.commit();
        if (previous == null) {
            return !latest.untargeted() && listed.length > 0;
        }
        try (RevWalk walk = new RevWalk(clone.repository())) {
            RevCommit commit = walk.parseCommit(previous);
            Instant time = commit.getCommitterIdent().getWhenAsInstant();
            if (now.isBefore(time.plus(interval))) {
                return false;
            }
            return !Arrays.equals(listed, content(clone.repository(), commit));
        }
    }

    /**
     * Writes, keeps, tags and pushes the next checkpoint; the tag is pushed only once the kept reference points at the
     * commit.
     *
     * @param latest the newest checkpoint, the parent of the new one; with number 0 the new one is the first and has no
     *               parent, an {@linkplain Latest#untargeted() untargeted} one makes none
     * @param listed the content of {@value #REFS_FILE}
     * @return {@link StoreOutcome#OK} with how the checkpoints stand after the push: {@link Standing#CURRENT} when its
     *         tag is on the remote, {@link Standing#BLOCKED} when the commit stays kept for the next due check,
     *         {@link Standing#STRANDED} when, in addition, its local tag stayed, {@link Standing#UNTARGETED} without any
     *         write for an untargeted newest checkpoint; {@link StoreOutcome#INCOMPLETE} when the commit was written but
     *         could not be kept, so nothing was pushed; {@link StoreOutcome#REFUSED} or {@link StoreOutcome#UNAVAILABLE}
     *         of the signing key source; or the passed-on Git outcome of the write
     * @throws IOException if a reference cannot be read or written
     */
    StoreResult<Standing> create(Latest latest, byte[] listed) throws IOException {
        if (latest.untargeted()) {
            return StoreResult.ok(Standing.UNTARGETED);
        }
        int number = latest.number() + 1;
        String tag = StoreReferences.checkpoint(number);
        var prepared = committing.signatures().prepare(tag);
        if (!prepared.isOk()) {
            return new StoreResult<>(prepared.outcome(), null, prepared.detail());
        }
        CommitSignatures.Prepared signing = Objects.requireNonNull(prepared.value());
        Instant now = committing.clock().instant();
        var identity = committing.identities().committer(now);
        @Nullable ObjectId parent = latest.commit();
        var written = writer.write(clone.repository(), parent == null ? List.of() : List.of(parent.name()),
                new CommitMetadata(identity, identity, MESSAGE + number, signing.signing()),
                List.of(new TreeEdit.Put(REFS_FILE, listed, TreeEdit.Mode.REGULAR_FILE)));
        if (!written.isOk()) {
            return committing.signatures().<Standing>outcomeOf(written, signing, tag)
                    .orElseGet(() -> StoreResult.passOn(written));
        }
        ObjectId commit = ObjectId.fromString(Objects.requireNonNull(written.value()));
        if (!setRef(retained(number), commit)) {
            return StoreResult.of(StoreOutcome.INCOMPLETE, tag + ": not kept");
        }
        LOGGER.info(GitStoreLogMessages.INFO.CHECKPOINT_CREATED, clone.alias(), number, commit.name());
        return StoreResult.ok(push(number, commit));
    }

    /**
     * Pushes the tag of a checkpoint kept by an earlier due check, or drops it.
     * <p>
     * A kept checkpoint whose number follows the newest checkpoint on the remote and whose parent is that checkpoint
     * gets its tag pushed; one whose number is already taken on the remote, or that no longer follows it, is dropped,
     * because the remote moved on and a new checkpoint is made instead. The first eligible one whose push fails ends
     * the repair: it stays kept, and no new checkpoint is made while it waits.
     *
     * @param latest the newest checkpoint as the last fetch mirrored it
     * @return the checkpoint commits whose tag this call pushed, oldest first, with how the checkpoints stand
     * @throws IOException if a reference cannot be read or written
     */
    Repair repair(Latest latest) throws IOException {
        List<ObjectId> pushed = new ArrayList<>();
        Repository repository = clone.repository();
        List<Ref> kept = new ArrayList<>(repository.getRefDatabase().getRefsByPrefix(
                StoreReferences.retainedCheckpoints()));
        kept.sort((first, second) -> Integer.compare(number(first), number(second)));
        int newest = latest.number();
        @Nullable ObjectId parent = latest.commit();
        Standing standing = Standing.CURRENT;
        for (int index = 0; index < kept.size() && standing == Standing.CURRENT; index++) {
            Ref ref = kept.get(index);
            int number = number(ref);
            @Nullable ObjectId commit = ref.getObjectId();
            if (number == newest + 1 && commit != null && follows(commit, parent)) {
                standing = push(number, commit);
                if (standing == Standing.CURRENT) {
                    pushed.add(commit);
                    newest = number;
                    parent = commit;
                }
            } else {
                LOGGER.debug("A kept checkpoint no longer follows the newest checkpoint and is dropped");
                deleteRef(ref.getName());
            }
        }
        return new Repair(pushed, standing);
    }

    /**
     * Tags the checkpoint locally, pushes the tag against the zero-id lease, and drops the kept reference on success;
     * after a failed push the local tag is deleted again, so the clone lists only checkpoints the remote has.
     */
    private Standing push(int number, ObjectId commit) throws IOException {
        String tag = StoreReferences.checkpoint(number);
        if (!createTag(tag, commit)) {
            return Standing.BLOCKED;
        }
        var pushed = operations.push(PushInput.builder().root(clone.gitDir()).remote(StoreReferences.ORIGIN).localRef(commit.name())
                .remoteRef(tag).expectedRemoteHead(ObjectId.zeroId().name()).credentials(user.credentials()).build());
        if (!pushed.isOk()) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHOR_PUSH_INCOMPLETE, clone.alias(), pushed.outcome().name());
            // the commit stays kept; a local tag that stays would make the clone list a checkpoint the remote lacks
            return deleteRef(tag) ? Standing.BLOCKED : Standing.STRANDED;
        }
        deleteRef(retained(number));
        return Standing.CURRENT;
    }

    /** Whether a commit's only parent is the given checkpoint, or it has none when there is no checkpoint. */
    private boolean follows(ObjectId commit, @Nullable ObjectId parent) throws IOException {
        try (RevWalk walk = new RevWalk(clone.repository())) {
            RevCommit parsed = walk.parseCommit(commit);
            if (parent == null) {
                return parsed.getParentCount() == 0;
            }
            return parsed.getParentCount() == 1 && parsed.getParent(0).equals(parent);
        }
    }

    /**
     * Reads the file {@value #REFS_FILE} of a checkpoint commit.
     *
     * @param repository the repository
     * @param commit     the checkpoint commit
     * @return its content, read bounded by {@link ExplicitSave#BLOB_LIMIT}; empty when the tree holds no such file
     * @throws IOException if the tree cannot be read, or {@link ExplicitSave.BlobTooLargeException} for a larger file
     */
    static byte[] content(Repository repository, RevCommit commit) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repository, REFS_FILE, commit.getTree())) {
            if (walk == null) {
                return new byte[0];
            }
            return ExplicitSave.readBlob(repository, walk.getObjectId(0));
        }
    }

    private static int number(Ref ref) {
        String name = ref.getName().substring(StoreReferences.retainedCheckpoints().length());
        return StoreReferences.checkpointNumber(StoreReferences.CHECKPOINTS + name).orElse(Integer.MAX_VALUE);
    }

    private static String retained(int number) {
        return StoreReferences.retainedCheckpoints() + number;
    }

    /**
     * Writes the local tag by an update that expects it absent, without a reflog; whether the tag now points at the
     * commit. A local tag that already points at it, left by a push that failed before any fetch pruned it, counts.
     */
    private boolean createTag(String tag, ObjectId commit) throws IOException {
        Ref existing = clone.repository().exactRef(tag);
        if (existing != null) {
            return commit.equals(existing.getObjectId());
        }
        RefUpdate update = clone.repository().updateRef(tag);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(commit);
        update.disableRefLog();
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW) {
            LOGGER.debug("The local checkpoint tag was not created: %s", result);
            return false;
        }
        return true;
    }

    /**
     * Writes the kept reference of a checkpoint; whether it points at the commit now. A reference that already points
     * at it counts.
     */
    private boolean setRef(String name, ObjectId target) throws IOException {
        RefUpdate update = clone.repository().updateRef(name);
        update.setForceUpdate(true);
        update.setNewObjectId(target);
        update.disableRefLog();
        RefUpdate.Result result = update.update();
        Ref written = clone.repository().exactRef(name);
        boolean kept = switch (result) {
            case NEW, FORCED, FAST_FORWARD, NO_CHANGE -> written != null && target.equals(written.getObjectId());
            default -> false;
        };
        if (!kept) {
            LOGGER.debug("The kept checkpoint reference was not written: %s", result);
        }
        return kept;
    }

    /** Deletes a local reference of the anchoring; whether it is gone now. An absent reference counts. */
    private boolean deleteRef(String name) throws IOException {
        if (clone.repository().exactRef(name) == null) {
            return true;
        }
        RefUpdate update = clone.repository().updateRef(name);
        update.setForceUpdate(true);
        update.disableRefLog();
        RefUpdate.Result result = update.delete();
        boolean deleted = clone.repository().exactRef(name) == null;
        if (!deleted) {
            LOGGER.debug("The local anchoring reference was not deleted: %s", result);
        }
        return deleted;
    }
}
