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
package de.cuioss.git.document.core;

import java.io.IOException;
import java.util.List;
import java.util.Objects;


import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.BatchRefUpdate;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.jspecify.annotations.Nullable;

/**
 * Moves a reference by compare-and-swap (GDR-CORE-5; checked against JGit 7.8.0).
 * <p>
 * An attempt reads the reference exactly ({@code Repository.exactRef(String)}), hands the tree of its commit to the
 * {@link TreeOperation}, writes the next commit through the {@link InCoreWriter} with the reference's commit as the
 * only parent (none for a reference that does not exist yet), and moves the reference through a {@link RefUpdate} that
 * expects the id it read ({@code setExpectedOldObjectId}, {@link ObjectId#zeroId()} for a reference that must not
 * exist), never forces ({@code setForceUpdate} is never called) and writes no reflog ({@code disableRefLog()}).
 * <p>
 * The window between reading the reference and moving it is closed by JGit, not by a check of this class: JGit 7.8.0
 * {@code RefUpdate.updateImpl} compares the expected old id with the reference's id while it holds the reference's
 * lock, and reports a mismatch, like a lock held by another writer, as {@link RefUpdate.Result#LOCK_FAILURE}. Such a
 * lost race starts the next attempt, which reads the reference again and runs the operation again on the tree the
 * concurrent writer left. The commit written by a lost attempt stays in the object database unreferenced.
 * <p>
 * A package-private tree-level variant runs the same loop with a finished tree instead of edits: its source receives
 * the reference's current tree and returns the tree of the next commit, and its update replaces a symbolic reference
 * instead of moving the reference it points at ({@code Repository.updateRef(refName, true)}).
 * <p>
 * For a symbolic reference replaced or deleted itself, which check decides depends on the backend: the file backend
 * (JGit 7.8.0 {@code RefDirectoryUpdate}) compares the expected id with the resolved id under the reference's lock as
 * for any other reference; the in-memory DFS backend ({@code DfsRefUpdate}) compares the expected id with no id at all
 * and always reports {@link RefUpdate.Result#LOCK_FAILURE}, so the updater then decides through one atomic
 * {@link BatchRefUpdate} that replaces or deletes the symbolic reference only while its base reference still points at
 * the expected id. Neither path drops the expected-id check.
 * <p>
 * {@link #compareAndDelete(Repository, String, String, int)} deletes a reference under the same lock-held comparison;
 * there the force flag is set, because JGit rejects a delete without it, and the expected id alone guards the delete.
 * <p>
 * A detail names the reference and the {@link RefUpdate.Result}, never content or an identity (GDR-NFR-3).
 *
 * @since 0.1
 */
public final class ReferenceUpdater {

    private static final CuiLogger LOGGER = new CuiLogger(ReferenceUpdater.class);

    /** The input field of the repository. */
    private static final String REPOSITORY = "repository";

    /** The input field of the reference name. */
    private static final String REF_NAME = "refName";

    private final InCoreWriter writer;

    /**
     * Creates the updater.
     *
     * @param writer the writer of the commits; stateless, so one may serve several updaters
     * @throws NullPointerException if {@code writer} is {@code null}
     */
    public ReferenceUpdater(InCoreWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * Writes the next commit of a reference and moves the reference to it, provided no other writer moved the
     * reference in between; a lost race is retried until the attempts are used up.
     * <p>
     * A symbolic {@code refName} is followed, as git follows one when it commits on {@code HEAD}: JGit 7.8.0
     * {@code Repository.updateRef(refName)} locks and moves the base reference, compares the expected id with that base
     * reference's id, and keeps the symbolic reference pointing at it. The named reference thus reads as the new
     * commit afterwards, which is what the result reports.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the reference was created ({@link RefUpdate.Result#NEW}) or moved
     * ({@link RefUpdate.Result#FAST_FORWARD}); the value is the full id of the new commit;</li>
     * <li>{@link GitOutcome#CONFLICT}: every attempt lost its race ({@link RefUpdate.Result#LOCK_FAILURE}); the reference
     * is left as the concurrent writers left it;</li>
     * <li>{@link GitOutcome#FAILED}: an I/O failure, including {@link RefUpdate.Result#IO_FAILURE}, which is neither
     * retried nor reported as a conflict;</li>
     * <li>{@link GitOutcome#REJECTED}: the update ended in any other {@link RefUpdate.Result}; the detail names it;</li>
     * <li>the result of the {@link InCoreWriter} when it did not write the commit, e.g. {@link GitOutcome#NOT_FOUND}
     * for an edit with an invalid path; the reference is not touched.</li>
     * </ul>
     *
     * @param repository the repository
     * @param refName    the full name of the reference, e.g. {@code refs/heads/main}
     * @param metadata   author, committer, message and signing of every commit written
     * @param operation  derives the edits from the reference's current tree; called once per attempt
     * @param attempts   the total number of tries, at least 1
     * @return the full id of the commit the reference now points at, or the outcome that prevented it
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    public GitResult<String> compareAndSwap(Repository repository, String refName, CommitMetadata metadata,
            TreeOperation operation, int attempts) {
        Objects.requireNonNull(repository, REPOSITORY);
        Objects.requireNonNull(refName, REF_NAME);
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(operation, "operation");
        requireAttempts(attempts);
        return swap(repository, refName, attempts, false, parent -> writer.write(repository, parentsOf(parent),
                metadata, operation.apply(parent == null ? null : parent.getTree().name())));
    }

    /**
     * Writes the next commit of a reference from a finished tree and moves the reference to it, provided no other
     * writer moved the reference in between; a lost race is retried until the attempts are used up (GDR-CORE-5;
     * checked against JGit 7.8.0).
     * <p>
     * The tree-level variant of {@link #compareAndSwap(Repository, String, CommitMetadata, TreeOperation, int)} for a
     * caller that builds the whole tree itself, such as the notes of GDR-CORE-10, whose {@code NoteMap} decides its own
     * fan-out: each attempt hands the tree of the reference's current commit to the {@link InCoreWriter.TreeSource}
     * through {@link InCoreWriter#writeTree(Repository, List, CommitMetadata, InCoreWriter.TreeSource)}, and the
     * commit is written with the returned tree as it is. After a lost race the source is called again with the tree the
     * concurrent writer left.
     * <p>
     * The reference itself is moved, never the reference a symbolic one points at: the update is created with
     * {@code Repository.updateRef(refName, true)}; with {@code detach} set JGit 7.8.0
     * {@code RefDirectory.newUpdate} and {@code DfsRefDatabase.newUpdate} mark the update as detaching a symbolic
     * reference, which replaces the symbolic reference by one that points at the new commit and leaves its former
     * target as it is. The expected id is the id the named reference resolves to. On the file backend JGit compares it
     * under the reference's lock as for {@code compareAndSwap}. On the in-memory DFS backend JGit 7.8.0
     * {@code DfsRefUpdate.tryLock(false)} drops the resolved id of a symbolic reference, so {@code RefUpdate} reports
     * {@link RefUpdate.Result#LOCK_FAILURE} for every expected id; the updater then replaces the symbolic reference
     * through one atomic {@link BatchRefUpdate} that also requires the base reference to still point at the expected id.
     * A writer that moved the base reference in between makes that batch fail, which is a lost race as for any other
     * reference.
     * <p>
     * The outcomes are those of {@code compareAndSwap}: {@link GitOutcome#OK} with the full id of the new commit,
     * {@link GitOutcome#CONFLICT} after a {@link RefUpdate.Result#LOCK_FAILURE} in every attempt,
     * {@link GitOutcome#FAILED} for an I/O failure, {@link RefUpdate.Result#IO_FAILURE} included,
     * {@link GitOutcome#REJECTED} for any other {@link RefUpdate.Result}, and the writer's result when it did not
     * write the commit.
     *
     * @param repository the repository
     * @param refName    the full name of the reference, e.g. {@code refs/notes/commits}
     * @param metadata   author, committer, message and signing of every commit written
     * @param source     derives the finished tree from the reference's current tree; called once per attempt
     * @param attempts   the total number of tries, at least 1
     * @return the full id of the commit the reference now points at, or the outcome that prevented it
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    GitResult<String> compareAndSwapTree(Repository repository, String refName, CommitMetadata metadata,
            InCoreWriter.TreeSource source, int attempts) {
        Objects.requireNonNull(repository, REPOSITORY);
        Objects.requireNonNull(refName, REF_NAME);
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(source, "source");
        requireAttempts(attempts);
        return swap(repository, refName, attempts, true,
                parent -> writer.writeTree(repository, parentsOf(parent), metadata, source));
    }

    /**
     * The retry loop of both compare-and-swap variants: reads the reference exactly, lets {@code next} write the
     * commit on the reference's commit, and moves the reference to it under the expected id.
     *
     * @param detach whether the update replaces a symbolic reference instead of moving its target
     */
    private static GitResult<String> swap(Repository repository, String refName, int attempts, boolean detach,
            NextCommit next) {
        try {
            for (int attempt = 1; attempt <= attempts; attempt++) {
                Ref current = repository.exactRef(refName);
                @Nullable ObjectId expected = current == null ? null : current.getObjectId();
                @Nullable RevCommit parent = expected == null ? null : parseCommit(repository, expected);
                GitResult<String> written = next.write(parent);
                if (!written.isOk()) {
                    return written;
                }
                ObjectId commit = ObjectId.fromString(Objects.requireNonNull(written.value()));
                RefUpdate.Result result = update(repository, refName, detach, expected, commit);
                switch (result) {
                    case NEW, FAST_FORWARD -> {
                        return GitResult.ok(commit.name());
                    }
                    case LOCK_FAILURE -> LOGGER.debug("Compare-and-swap of %s lost its race, attempt %s of %s",
                            refName, attempt, attempts);
                    case IO_FAILURE -> {
                        return GitResult.of(GitOutcome.FAILED, refName + ": " + result);
                    }
                    default -> {
                        return GitResult.of(GitOutcome.REJECTED, refName + ": " + result);
                    }
                }
            }
            return GitResult.of(GitOutcome.CONFLICT,
                    refName + ": " + RefUpdate.Result.LOCK_FAILURE + " on every attempt (" + attempts + ")");
        } catch (IOException e) {
            return JGitRepositoryOperations.failure(e);
        }
    }

    /**
     * Deletes a reference, provided it still points at the expected commit (GDR-CORE-5; checked against JGit 7.8.0).
     * <p>
     * An attempt deletes through a {@link RefUpdate} that expects the given id ({@code setExpectedOldObjectId}), sets
     * the force flag ({@code setForceUpdate(true)}: JGit 7.8.0 rejects a delete without it) and writes no reflog
     * ({@code disableRefLog()}). JGit compares the expected id with the reference's id while it holds the reference's
     * lock; a mismatch, like a lock held by another writer, ends the attempt in {@link RefUpdate.Result#LOCK_FAILURE}.
     * The updater then re-reads the reference ({@code Repository.exactRef(String)}) only to tell the two apart: a
     * reference still at the expected id is retried with that same expected id, so the lock-held comparison decides
     * again, and a reference that moved or is gone ends the call at once; the id it moved to is never adopted.
     * <p>
     * A symbolic reference is deleted itself, never the reference it points at: the update is created with
     * {@code Repository.updateRef(refName, true)}, because without {@code detach} JGit 7.8.0 resolves the symbolic
     * reference and deletes its base reference instead. The expected id is the id the symbolic reference resolves to.
     * On the file backend JGit compares it under the lock; on the in-memory DFS backend, where JGit 7.8.0
     * {@code DfsRefUpdate} compares it with no id and reports {@link RefUpdate.Result#LOCK_FAILURE}, the updater deletes
     * the symbolic reference through one atomic {@link BatchRefUpdate} that also requires its base reference to still
     * point at the expected id. A failed batch is the {@code LOCK_FAILURE} it replaces, decided by the re-read above.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the reference was deleted ({@link RefUpdate.Result#FORCED}); the value is the deleted
     * id, the expected id;</li>
     * <li>{@link GitOutcome#NOT_FOUND}: {@code expectedOldId} is no full commit id; the detail names the field. Nothing
     * is read or written;</li>
     * <li>{@link GitOutcome#CONFLICT}: the reference moved away from the expected id or is gone, or every attempt met a
     * held lock; the reference is left as the other writer left it;</li>
     * <li>{@link GitOutcome#FAILED}: an I/O failure, including {@link RefUpdate.Result#IO_FAILURE}, which is neither
     * retried nor reported as a conflict;</li>
     * <li>{@link GitOutcome#REJECTED}: the delete ended in any other {@link RefUpdate.Result}; the detail names it.</li>
     * </ul>
     *
     * @param repository    the repository
     * @param refName       the full name of the reference, e.g. {@code refs/heads/main}
     * @param expectedOldId the full id of the commit the reference must point at to be deleted
     * @param attempts      the total number of tries, at least 1
     * @return the deleted id, or the outcome that prevented the delete
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    public GitResult<String> compareAndDelete(Repository repository, String refName, String expectedOldId,
            int attempts) {
        Objects.requireNonNull(repository, REPOSITORY);
        Objects.requireNonNull(refName, REF_NAME);
        Objects.requireNonNull(expectedOldId, "expectedOldId");
        requireAttempts(attempts);
        if (!GitIdentity.isFullCommitId(expectedOldId)) {
            return GitResult.of(GitOutcome.NOT_FOUND, "expectedOldId: not a full commit id");
        }
        ObjectId expected = ObjectId.fromString(expectedOldId);
        try {
            for (int attempt = 1; attempt <= attempts; attempt++) {
                RefUpdate.Result result = delete(repository, refName, expected);
                switch (result) {
                    case FORCED -> {
                        return GitResult.ok(expected.name());
                    }
                    case LOCK_FAILURE -> {
                        Ref current = repository.exactRef(refName);
                        if (current == null || !expected.equals(current.getObjectId())) {
                            return GitResult.of(GitOutcome.CONFLICT,
                                    refName + ": " + result + ", the reference moved or is gone");
                        }
                        LOGGER.debug("Compare-and-delete of %s met a held lock, attempt %s of %s", refName, attempt,
                                attempts);
                    }
                    case IO_FAILURE -> {
                        return GitResult.of(GitOutcome.FAILED, refName + ": " + result);
                    }
                    default -> {
                        return GitResult.of(GitOutcome.REJECTED, refName + ": " + result);
                    }
                }
            }
            return GitResult.of(GitOutcome.CONFLICT,
                    refName + ": " + RefUpdate.Result.LOCK_FAILURE + " on every attempt (" + attempts + ")");
        } catch (IOException e) {
            return JGitRepositoryOperations.failure(e);
        }
    }

    /**
     * Deletes the named reference itself. The update is created without dereferencing ({@code updateRef(name, true)}):
     * JGit 7.8.0 {@code RefDirectory.newUpdate(name, false)} resolves a symbolic reference, and {@code RefUpdate.delete}
     * then deletes its leaf, the base reference, and leaves the symbolic one; with {@code detach} set it locks and
     * deletes the symbolic reference and leaves its target as it is. The expected id is still compared with the
     * resolved id of the named reference: under the lock on the file backend, through the guarded batch of
     * {@code guardedUnlink} on the in-memory DFS backend (JGit 7.8.0).
     */
    private static RefUpdate.Result delete(Repository repository, String refName, ObjectId expected)
            throws IOException {
        RefUpdate delete = repository.updateRef(refName, true);
        delete.setExpectedOldObjectId(expected);
        delete.setForceUpdate(true);
        delete.disableRefLog();
        RefUpdate.Result result = delete.delete();
        if (unresolvedUnderLock(delete, result)) {
            return guardedUnlink(repository, delete.getRef(), expected, ObjectId.zeroId())
                    ? RefUpdate.Result.FORCED : result;
        }
        return result;
    }

    private static RevCommit parseCommit(Repository repository, ObjectId id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(id);
        }
    }

    private static RefUpdate.Result update(Repository repository, String refName, boolean detach,
            @Nullable ObjectId expected, ObjectId commit) throws IOException {
        RefUpdate update = repository.updateRef(refName, detach);
        update.setExpectedOldObjectId(expected == null ? ObjectId.zeroId() : expected);
        update.setNewObjectId(commit);
        update.disableRefLog();
        RefUpdate.Result result = update.update();
        if (detach && expected != null && unresolvedUnderLock(update, result)) {
            return guardedUnlink(repository, update.getRef(), expected, commit) ? RefUpdate.Result.NEW : result;
        }
        return result;
    }

    /**
     * Whether a detaching update of a symbolic reference lost its race only because the backend compared the expected
     * id with no id at all. JGit 7.8.0 {@code DfsRefUpdate.tryLock(false)} sets the old id of a symbolic reference to
     * {@code null}, so {@code RefUpdate.updateImpl} compares the expected id with {@link ObjectId#zeroId()} and reports
     * {@link RefUpdate.Result#LOCK_FAILURE} whatever the reference points at; {@code RefDirectoryUpdate.tryLock} instead
     * re-reads the reference under its lock and keeps the resolved id, so there a {@code LOCK_FAILURE} with an old id
     * is a real one.
     */
    private static boolean unresolvedUnderLock(RefUpdate update, RefUpdate.Result result) {
        return result == RefUpdate.Result.LOCK_FAILURE && update.getRef().isSymbolic()
                && update.getOldObjectId() == null;
    }

    /**
     * Replaces or deletes a symbolic reference in one atomic {@link BatchRefUpdate} that also requires its base
     * reference to still point at the expected id: an unlink command that expects the symbolic reference's target
     * ({@code ReceiveCommand.unlink}) and a command on the base reference from the expected id to the same id, which
     * changes nothing and holds only when the base reference is still at the expected id. JGit 7.8.0
     * {@code ReftableBatchRefUpdate} compares every command under one lock before it writes any, so a writer that moved
     * the base reference makes the whole batch fail and nothing is written. A backend without atomic batches, and the
     * file backend, which refuses a symbolic command in an atomic batch ({@code PackedBatchRefUpdate}), write nothing
     * either: the batch fails and the caller keeps its lost race.
     *
     * @param newId the new id of the reference; {@link ObjectId#zeroId()} deletes it
     * @return whether both commands succeeded
     */
    private static boolean guardedUnlink(Repository repository, Ref symbolic, ObjectId expected, ObjectId newId)
            throws IOException {
        ReceiveCommand unlink = ReceiveCommand.unlink(symbolic.getTarget().getName(), newId, symbolic.getName());
        ReceiveCommand guard = new ReceiveCommand(expected, expected, symbolic.getLeaf().getName());
        BatchRefUpdate batch = repository.getRefDatabase().newBatchUpdate();
        batch.setAtomic(true);
        batch.setAllowNonFastForwards(true);
        batch.disableRefLog();
        batch.addCommand(unlink, guard);
        try (RevWalk walk = new RevWalk(repository)) {
            batch.execute(walk, NullProgressMonitor.INSTANCE);
        }
        return unlink.getResult() == ReceiveCommand.Result.OK && guard.getResult() == ReceiveCommand.Result.OK;
    }

    private static List<String> parentsOf(@Nullable RevCommit parent) {
        return parent == null ? List.of() : List.of(parent.name());
    }

    private static void requireAttempts(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
    }

    /** Writes the next commit of an attempt on the reference's current commit. */
    @FunctionalInterface
    private interface NextCommit {

        /**
         * Writes the commit.
         *
         * @param parent the reference's current commit; {@code null} when the reference does not exist
         * @return the writer's result
         */
        GitResult<String> write(@Nullable RevCommit parent);
    }
}
