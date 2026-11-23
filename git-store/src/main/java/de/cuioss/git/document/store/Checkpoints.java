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
import org.eclipse.jgit.lib.Constants;
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
 * that is the incomplete state reported ({@code GIT_STORE-112}) and repaired here.
 */
final class Checkpoints {

    /** The single file of a checkpoint tree. */
    static final String REFS_FILE = "refs";

    /** The message of a checkpoint commit before its number. */
    static final String MESSAGE = "Checkpoint ";

    /** The remote of every bare clone of the store. */
    private static final String ORIGIN = "origin";

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
     *
     * @param number the number of its tag; 0 when there is none
     * @param commit the checkpoint commit; {@code null} when there is none
     */
    record Latest(int number, @Nullable ObjectId commit) {
    }

    /**
     * Reads the checkpoint tag with the highest number.
     *
     * @return the newest checkpoint, number 0 without any
     * @throws IOException if the references cannot be read
     */
    Latest latest() throws IOException {
        int highest = 0;
        @Nullable ObjectId commit = null;
        for (Ref ref : clone.repository().getRefDatabase().getRefsByPrefix(StoreReferences.CHECKPOINTS)) {
            OptionalInt number = StoreReferences.checkpointNumber(ref.getName());
            if (number.isPresent() && number.getAsInt() > highest && ref.getObjectId() != null) {
                highest = number.getAsInt();
                commit = ref.getObjectId();
            }
        }
        return new Latest(highest, commit);
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
     * since the commit time of the newest checkpoint and the listed references differ from those it lists.
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
            return listed.length > 0;
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
     * Writes, tags and pushes the next checkpoint.
     *
     * @param latest the newest checkpoint, the parent of the new one
     * @param listed the content of {@value #REFS_FILE}
     * @return {@link StoreOutcome#OK} with the checkpoint commit, whose tag is on the remote;
     *         {@link StoreOutcome#INCOMPLETE} when the commit was written and kept but its tag was not pushed;
     *         {@link StoreOutcome#REFUSED} or {@link StoreOutcome#UNAVAILABLE} of the signing key source; or the
     *         passed-on Git outcome of the write
     * @throws IOException if a reference cannot be read or written
     */
    StoreResult<ObjectId> create(Latest latest, byte[] listed) throws IOException {
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
            return committing.signatures().<ObjectId>outcomeOf(written, signing, tag)
                    .orElseGet(() -> StoreResult.passOn(written));
        }
        ObjectId commit = ObjectId.fromString(Objects.requireNonNull(written.value()));
        setRef(retained(number), commit);
        LOGGER.info(GitStoreLogMessages.INFO.CHECKPOINT_CREATED, clone.alias(), number, commit.name());
        return push(number, commit);
    }

    /**
     * Pushes the tag of a checkpoint kept by an earlier due check, or drops it.
     * <p>
     * A kept checkpoint whose number follows the newest checkpoint on the remote and whose parent is that checkpoint
     * gets its tag pushed; one whose number is already taken on the remote, or that no longer follows it, is dropped,
     * because the remote moved on and a new checkpoint is made instead.
     *
     * @param latest the newest checkpoint as the last fetch mirrored it
     * @return the checkpoint commits whose tag this call pushed, oldest first
     * @throws IOException if a reference cannot be read or written
     */
    List<ObjectId> repair(Latest latest) throws IOException {
        List<ObjectId> pushed = new ArrayList<>();
        Repository repository = clone.repository();
        List<Ref> kept = new ArrayList<>(repository.getRefDatabase().getRefsByPrefix(
                StoreReferences.retainedCheckpoints()));
        kept.sort((first, second) -> Integer.compare(number(first), number(second)));
        int newest = latest.number();
        @Nullable ObjectId parent = latest.commit();
        for (Ref ref : kept) {
            int number = number(ref);
            @Nullable ObjectId commit = ref.getObjectId();
            if (number == newest + 1 && commit != null && follows(commit, parent)) {
                var result = push(number, commit);
                if (!result.isOk()) {
                    break;
                }
                pushed.add(commit);
                newest = number;
                parent = commit;
            } else {
                LOGGER.debug("A kept checkpoint no longer follows the newest checkpoint and is dropped");
                deleteRef(ref.getName());
            }
        }
        return pushed;
    }

    /** Tags the checkpoint locally, pushes the tag against the zero-id lease, and drops the kept reference on success. */
    private StoreResult<ObjectId> push(int number, ObjectId commit) throws IOException {
        String tag = StoreReferences.checkpoint(number);
        if (!createTag(tag, commit)) {
            return StoreResult.of(StoreOutcome.INCOMPLETE, tag + ": exists locally");
        }
        var pushed = operations.push(PushInput.builder().root(clone.gitDir()).remote(ORIGIN).localRef(commit.name())
                .remoteRef(tag).expectedRemoteHead(ObjectId.zeroId().name()).credentials(user.credentials()).build());
        if (!pushed.isOk()) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHOR_PUSH_INCOMPLETE, clone.alias(), pushed.outcome().name());
            // the local tag goes, so the clone lists only checkpoints the remote has; the commit stays kept
            deleteRef(tag);
            return StoreResult.of(StoreOutcome.INCOMPLETE, tag + ": not pushed (" + pushed.outcome().name() + ")");
        }
        deleteRef(retained(number));
        return StoreResult.ok(commit);
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
     * @return its content; empty when the tree holds no such file
     * @throws IOException if the tree cannot be read
     */
    static byte[] content(Repository repository, RevCommit commit) throws IOException {
        try (TreeWalk walk = TreeWalk.forPath(repository, REFS_FILE, commit.getTree())) {
            if (walk == null) {
                return new byte[0];
            }
            return repository.open(walk.getObjectId(0), Constants.OBJ_BLOB).getBytes(Integer.MAX_VALUE);
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

    private void setRef(String name, ObjectId target) throws IOException {
        RefUpdate update = clone.repository().updateRef(name);
        update.setForceUpdate(true);
        update.setNewObjectId(target);
        update.disableRefLog();
        RefUpdate.Result result = update.update();
        LOGGER.debug("The kept checkpoint reference was written: %s", result);
    }

    private void deleteRef(String name) throws IOException {
        if (clone.repository().exactRef(name) == null) {
            return;
        }
        RefUpdate update = clone.repository().updateRef(name);
        update.setForceUpdate(true);
        update.disableRefLog();
        RefUpdate.Result result = update.delete();
        LOGGER.debug("The kept anchoring reference was deleted: %s", result);
    }
}
