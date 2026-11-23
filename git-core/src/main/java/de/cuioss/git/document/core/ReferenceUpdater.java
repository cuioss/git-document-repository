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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


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
 * {@link BatchRefUpdate} that replaces or deletes the symbolic reference only while every link of its chain still
 * points where it did and its base reference still points at the expected id; a dangling symbolic reference, which
 * the backend would replace for the zero expected id even after its target was written, is replaced by that batch
 * before any {@link RefUpdate} runs, and only while its base reference is still absent; the file backend refuses that
 * batch, and the update falls through to the {@link RefUpdate}, which compares under the reference's lock. A batch
 * that fails on an I/O error is a failure, not a lost race. Neither path drops the expected-id check.
 * <p>
 * {@link #compareAndDelete(Repository, String, String, int)} deletes a reference under the same lock-held comparison;
 * there the force flag is set, because JGit rejects a delete without it, and the expected id alone guards the delete.
 * <p>
 * Every entry point checks the reference name before it reads or writes anything: a name that JGit 7.8.0
 * {@code Repository.isValidRefName} rejects (a component {@code ..}, a name of one component such as {@code HEAD}, a
 * name ending {@code .lock}, among others) is {@link GitOutcome#REFUSED_BY_POLICY} before any file is touched. A name
 * read as absent that overlaps an existing reference ({@code RefDatabase.isNameConflicting}: {@code refs/heads/a/b}
 * while {@code refs/heads/a} exists, or the reverse) is {@link GitOutcome#REJECTED} with the detail
 * {@code refName: name conflict}: a compare-and-swap decides it after it read the reference and before the operation
 * runs, a compare-and-delete in the re-read that follows its {@link RefUpdate.Result#LOCK_FAILURE}. Without that
 * decision JGit 7.8.0 {@code RefUpdate.updateImpl} answers a name conflict with {@code LOCK_FAILURE} (lines 714-718),
 * which the loop would retry as a lost race.
 * <p>
 * Code of the caller runs on the caller's contract: an exception the {@link TreeOperation} throws, and a
 * {@code null} list or element it returns, propagates unchanged; the reference is not touched.
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
     * A symbolic {@code refName} is followed, as git follows {@code HEAD} when it commits: JGit 7.8.0
     * {@code Repository.updateRef(refName)} locks and moves the base reference, compares the expected id with that base
     * reference's id, and keeps the symbolic reference pointing at it. The named reference thus reads as the new
     * commit afterwards, which is what the result reports.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the reference was created ({@link RefUpdate.Result#NEW}) or moved
     * ({@link RefUpdate.Result#FAST_FORWARD}); the value is the full id of the new commit;</li>
     * <li>{@link GitOutcome#REFUSED_BY_POLICY}: {@code refName} is no valid reference name (JGit 7.8.0
     * {@code Repository.isValidRefName}); detail {@code refName: not a valid reference name}. Nothing is read or
     * written;</li>
     * <li>{@link GitOutcome#CONFLICT}: every attempt lost its race ({@link RefUpdate.Result#LOCK_FAILURE}); the reference
     * is left as the concurrent writers left it;</li>
     * <li>{@link GitOutcome#FAILED}: an I/O failure, including {@link RefUpdate.Result#IO_FAILURE}, which is neither
     * retried nor reported as a conflict;</li>
     * <li>{@link GitOutcome#REJECTED}: the reference does not exist and its name conflicts with an existing one (detail
     * {@code refName: name conflict}, decided before the operation runs), the reference points at no commit, e.g. at
     * an annotated tag (the detail names the reference), or the update ended in any other {@link RefUpdate.Result}
     * (the detail names it);</li>
     * <li>the result of the {@link InCoreWriter} when it did not write the commit, e.g. {@link GitOutcome#NOT_FOUND}
     * for an edit with an invalid path; the reference is not touched.</li>
     * </ul>
     * <p>
     * An exception of the {@code operation}, or a {@code null} list or element it returns, propagates unchanged.
     *
     * @param repository the repository
     * @param refName    the full name of the reference, e.g. {@code refs/heads/main}
     * @param metadata   author, committer, message and signing of every commit written
     * @param operation  derives the edits from the reference's current tree; called once per attempt
     * @param attempts   the total number of tries, at least 1; {@link Integer#MAX_VALUE} included
     * @return the full id of the commit the reference now points at, or the outcome that prevented it
     * @throws NullPointerException     if an argument is {@code null}, or the operation returns {@code null} or a
     *                                  list with a {@code null} element
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
     * through one atomic {@link BatchRefUpdate} that also requires every link of the symbolic chain to still point where
     * it did and the base reference to still point at the expected id. A writer that moved one of them in between makes
     * that batch fail, which is a lost race as for any other reference. A symbolic reference that is dangling (no
     * expected id) is replaced by that batch before any {@link RefUpdate} runs, and only while its base reference is
     * still absent: the backend would otherwise match the zero expected id even after the base reference was written
     * and replace the alias without a conflict. A batch that fails on an I/O error ends the call in
     * {@link GitOutcome#FAILED}, without a retry.
     * <p>
     * The outcomes are those of {@code compareAndSwap}: {@link GitOutcome#OK} with the full id of the new commit,
     * {@link GitOutcome#REFUSED_BY_POLICY} for an invalid reference name, before anything is read,
     * {@link GitOutcome#CONFLICT} after a {@link RefUpdate.Result#LOCK_FAILURE} in every attempt,
     * {@link GitOutcome#FAILED} for an I/O failure, {@link RefUpdate.Result#IO_FAILURE} included,
     * {@link GitOutcome#REJECTED} for a name conflict ({@code refName: name conflict}), a reference at no commit or any
     * other {@link RefUpdate.Result}, and the writer's result when it did not write the commit.
     *
     * @param repository the repository
     * @param refName    the full name of the reference, e.g. {@code refs/notes/commits}
     * @param metadata   author, committer, message and signing of every commit written
     * @param source     derives the finished tree from the reference's current tree; called once per attempt
     * @param attempts   the total number of tries, at least 1; {@link Integer#MAX_VALUE} included
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
        if (!Repository.isValidRefName(refName)) {
            return invalidName();
        }
        try {
            for (int attempt = 0; attempt < attempts; attempt++) {
                GitResult<AttemptStart> start = attemptStart(repository, refName);
                if (!start.isOk()) {
                    return JGitRepositoryOperations.withoutValue(start);
                }
                AttemptStart from = Objects.requireNonNull(start.value());
                GitResult<String> written = next.write(from.parent());
                if (!written.isOk()) {
                    return written;
                }
                ObjectId commit = ObjectId.fromString(Objects.requireNonNull(written.value()));
                RefUpdate.Result result = update(repository, refName, detach, from.expected(), commit);
                Optional<GitResult<String>> settled = settled(refName, result, commit);
                if (settled.isPresent()) {
                    return settled.get();
                }
                LOGGER.debug("Compare-and-swap of %s lost its race, attempt %s of %s", refName, attempt + 1,
                        attempts);
            }
            return GitResult.of(GitOutcome.CONFLICT,
                    refName + ": " + RefUpdate.Result.LOCK_FAILURE + " on every attempt (" + attempts + ")");
        } catch (IOException e) {
            return JGitRepositoryOperations.failure(e);
        }
    }

    /**
     * What one compare-and-swap attempt starts from.
     *
     * @param expected the id the reference points at, {@code null} when it does not exist or has no id
     * @param parent   the commit at that id, the parent of the next commit; {@code null} without an id
     */
    private record AttemptStart(@Nullable ObjectId expected, @Nullable RevCommit parent) {
    }

    /**
     * Reads the reference exactly at the start of an attempt: a reference read as absent whose name conflicts with an
     * existing one is refused, and the commit it points at is parsed as it is.
     *
     * @return the start of the attempt, or {@link GitOutcome#REJECTED} for a name conflict or a reference that points
     *         at no commit
     * @throws IOException if the reference or its object cannot be read
     */
    private static GitResult<AttemptStart> attemptStart(Repository repository, String refName) throws IOException {
        Ref current = repository.exactRef(refName);
        if (current == null && isNameConflicting(repository, refName)) {
            return nameConflict();
        }
        @Nullable ObjectId expected = current == null ? null : current.getObjectId();
        if (expected == null) {
            return GitResult.ok(new AttemptStart(null, null));
        }
        Optional<RevCommit> commit = parseCommit(repository, expected);
        if (commit.isEmpty()) {
            return GitResult.of(GitOutcome.REJECTED, refName + ": points at no commit");
        }
        return GitResult.ok(new AttemptStart(expected, commit.get()));
    }

    /**
     * Maps the result of an attempt's update: {@code NEW} and {@code FAST_FORWARD} are the moved reference,
     * {@code IO_FAILURE} is {@link GitOutcome#FAILED}, any other result but {@code LOCK_FAILURE} is
     * {@link GitOutcome#REJECTED}, its detail naming the result.
     *
     * @return the result of the call, empty for a {@code LOCK_FAILURE}, a lost race that is retried
     */
    private static Optional<GitResult<String>> settled(String refName, RefUpdate.Result result, ObjectId commit) {
        return switch (result) {
            case NEW, FAST_FORWARD -> Optional.of(GitResult.ok(commit.name()));
            case LOCK_FAILURE -> Optional.empty();
            case IO_FAILURE -> Optional.of(GitResult.of(GitOutcome.FAILED, refName + ": " + result));
            default -> Optional.of(GitResult.of(GitOutcome.REJECTED, refName + ": " + result));
        };
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
     * the symbolic reference through one atomic {@link BatchRefUpdate} that also requires every link of its chain to
     * still point where it did and its base reference to still point at the expected id. A batch that lost a race is
     * the {@code LOCK_FAILURE} it replaces, decided by the re-read above; a batch that failed on an I/O error is an
     * {@link RefUpdate.Result#IO_FAILURE}.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the reference was deleted ({@link RefUpdate.Result#FORCED}); the value is the deleted
     * id, the expected id;</li>
     * <li>{@link GitOutcome#REFUSED_BY_POLICY}: {@code refName} is no valid reference name (JGit 7.8.0
     * {@code Repository.isValidRefName}); detail {@code refName: not a valid reference name}. Nothing is read or
     * written;</li>
     * <li>{@link GitOutcome#NOT_FOUND}: {@code expectedOldId} is no full commit id; the detail names the field. Nothing
     * is read or written;</li>
     * <li>{@link GitOutcome#CONFLICT}: the reference moved away from the expected id or is gone, or every attempt met a
     * held lock; the reference is left as the other writer left it;</li>
     * <li>{@link GitOutcome#FAILED}: an I/O failure, including {@link RefUpdate.Result#IO_FAILURE}, which is neither
     * retried nor reported as a conflict;</li>
     * <li>{@link GitOutcome#REJECTED}: the reference does not exist and its name conflicts with an existing one (detail
     * {@code refName: name conflict}, decided in the re-read after the {@code LOCK_FAILURE} JGit answers it with,
     * never retried), or the delete ended in any other
     * {@link RefUpdate.Result}; the detail names it.</li>
     * </ul>
     *
     * @param repository    the repository
     * @param refName       the full name of the reference, e.g. {@code refs/heads/main}
     * @param expectedOldId the full id of the commit the reference must point at to be deleted
     * @param attempts      the total number of tries, at least 1; {@link Integer#MAX_VALUE} included
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
        if (!Repository.isValidRefName(refName)) {
            return invalidName();
        }
        if (!GitIdentity.isFullCommitId(expectedOldId)) {
            return GitResult.of(GitOutcome.NOT_FOUND, "expectedOldId: not a full commit id");
        }
        ObjectId expected = ObjectId.fromString(expectedOldId);
        try {
            for (int attempt = 0; attempt < attempts; attempt++) {
                RefUpdate.Result result = delete(repository, refName, expected);
                switch (result) {
                    case FORCED -> {
                        return GitResult.ok(expected.name());
                    }
                    case LOCK_FAILURE -> {
                        Ref current = repository.exactRef(refName);
                        if (current == null && isNameConflicting(repository, refName)) {
                            return nameConflict();
                        }
                        if (current == null || !expected.equals(current.getObjectId())) {
                            return GitResult.of(GitOutcome.CONFLICT,
                                    refName + ": " + result + ", the reference moved or is gone");
                        }
                        LOGGER.debug("Compare-and-delete of %s met a held lock, attempt %s of %s", refName,
                                attempt + 1, attempts);
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
            return resultOf(guardedUnlink(repository, delete.getRef(), expected, ObjectId.zeroId()),
                    RefUpdate.Result.FORCED, result);
        }
        return result;
    }

    /**
     * Parses the object a reference points at as it is; empty when it is no commit. JGit 7.8.0
     * {@code RevWalk.parseCommit} peels an annotated tag ({@code peel(parseAny(id))}), so it would write the next commit
     * on the tagged commit and move the reference away from the tag; {@code parseAny} does not peel.
     */
    private static Optional<RevCommit> parseCommit(Repository repository, ObjectId id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseAny(id) instanceof RevCommit commit ? Optional.of(commit) : Optional.empty();
        }
    }

    /**
     * Whether the name of a reference read as absent overlaps an existing reference. JGit 7.8.0
     * {@code RefUpdate.updateImpl} answers such a name with {@link RefUpdate.Result#LOCK_FAILURE}, which the loop could
     * not tell from a lost race, so the caller asks before it runs the operation or after the delete it re-reads.
     */
    private static boolean isNameConflicting(Repository repository, String refName) throws IOException {
        return repository.getRefDatabase().isNameConflicting(refName);
    }

    /** The refusal of a reference name JGit 7.8.0 {@code Repository.isValidRefName} rejects; names the field only. */
    private static GitResult<String> invalidName() {
        return GitResult.of(GitOutcome.REFUSED_BY_POLICY, REF_NAME + ": not a valid reference name");
    }

    /** The result of a reference name that conflicts with an existing reference. */
    private static <V> GitResult<V> nameConflict() {
        return GitResult.of(GitOutcome.REJECTED, REF_NAME + ": name conflict");
    }

    /**
     * Creates or moves the reference to the new commit under the expected id.
     * <p>
     * For a dangling symbolic reference replaced itself, the guarded batch decides first; a backend that refuses the
     * batch, the file backend ({@code PackedBatchRefUpdate} refuses a symbolic command in an atomic batch, JGit
     * 7.8.0), falls through to {@code update.update()}, where {@code RefDirectoryUpdate} compares the zero expected id
     * with the id it reads under the reference's lock. That fall-through is kept on purpose: the file backend decides
     * there under its own lock, so no comparison is dropped.
     */
    private static RefUpdate.Result update(Repository repository, String refName, boolean detach,
            @Nullable ObjectId expected, ObjectId commit) throws IOException {
        RefUpdate update = repository.updateRef(refName, detach);
        update.setExpectedOldObjectId(expected == null ? ObjectId.zeroId() : expected);
        update.setNewObjectId(commit);
        update.disableRefLog();
        if (detach && expected == null && update.getRef().isSymbolic()) {
            // A dangling symbolic reference: the in-memory backend compares no id for it, so the zero expected id would
            // match even after another writer populated its target. The batch replaces it only while the target is
            // still absent; a backend that refuses the batch (the file backend) decides under its lock below.
            BatchOutcome batch = guardedUnlink(repository, update.getRef(), null, commit);
            if (batch != BatchOutcome.REFUSED) {
                return resultOf(batch, RefUpdate.Result.NEW, RefUpdate.Result.LOCK_FAILURE);
            }
        }
        RefUpdate.Result result = update.update();
        if (detach && expected != null && unresolvedUnderLock(update, result)) {
            return resultOf(guardedUnlink(repository, update.getRef(), expected, commit), RefUpdate.Result.NEW,
                    result);
        }
        return result;
    }

    /**
     * The result of an update or delete after its guarded batch: {@code applied} when the batch wrote, the I/O failure
     * a batch reports as one, and {@code lost} for a lost race or a refused batch, the result the batch replaces.
     */
    private static RefUpdate.Result resultOf(BatchOutcome batch, RefUpdate.Result applied, RefUpdate.Result lost) {
        return switch (batch) {
            case APPLIED -> applied;
            case IO_FAILED -> RefUpdate.Result.IO_FAILURE;
            case LOST_RACE, REFUSED -> lost;
        };
    }

    /**
     * Whether a detaching update of a symbolic reference ended in {@link RefUpdate.Result#LOCK_FAILURE} with no old id,
     * so that the guarded batch has to decide. JGit 7.8.0 {@code DfsRefUpdate.tryLock(false)} sets the old id of a
     * symbolic reference to {@code null}, so {@code RefUpdate.updateImpl} compares the expected id with
     * {@link ObjectId#zeroId()} and reports {@code LOCK_FAILURE} whatever the reference points at.
     * {@code RefDirectoryUpdate.tryLock} re-reads the reference under its lock and keeps the resolved id, so on the file
     * backend a {@code LOCK_FAILURE} after a comparison has an old id and is a real one; but when another writer holds
     * the lock, {@code RefDirectoryUpdate.tryLock} returns {@code false} before it calls {@code setOldObjectId}, so the
     * old id is unset there too. That case also reaches the batch, which the file backend refuses
     * ({@code PackedBatchRefUpdate}), and it is reported as the same {@code LOCK_FAILURE}, a lost race.
     */
    private static boolean unresolvedUnderLock(RefUpdate update, RefUpdate.Result result) {
        return result == RefUpdate.Result.LOCK_FAILURE && update.getRef().isSymbolic()
                && update.getOldObjectId() == null;
    }

    /** What an atomic guarded batch ended in; nothing is written unless it is {@link #APPLIED}. */
    enum BatchOutcome {

        /** Every command passed and the batch wrote. */
        APPLIED,

        /** A command's expected value did not hold: another writer changed a reference the batch guards. */
        LOST_RACE,

        /** The backend reported an I/O failure while it ran the batch. */
        IO_FAILED,

        /** The backend did not run the batch, e.g. the file backend, which refuses a symbolic command in an atomic batch. */
        REFUSED
    }

    /**
     * The message JGit 7.8.0 {@code ReftableBatchRefUpdate.execute} sets, with {@link ReceiveCommand.Result#LOCK_FAILURE},
     * on the first command when it catches an {@link IOException}. A mismatch of an expected value sets the same result
     * with no message, so the message is the only way to tell a storage failure from a lost race. Should a later JGit
     * change the text, an I/O failure is classified as {@link BatchOutcome#LOST_RACE}, as it was before.
     */
    private static final String REFTABLE_IO_ERROR = "io error";

    /**
     * Replaces or deletes a symbolic reference in one atomic {@link BatchRefUpdate} that also requires everything the
     * symbolic reference resolves through to still hold (checked against JGit 7.8.0): an unlink command that expects
     * the symbolic reference's target ({@code ReceiveCommand.unlink}), a no-op {@code ReceiveCommand.link} on each
     * further symbolic reference of the chain that holds only while it still points at the same target, and a command
     * on the base reference. For an {@code expected} id that command goes from the id to the same id, which changes
     * nothing and holds only while the base reference is still at it; for no expected id (a dangling symbolic
     * reference) it holds only while the base reference is still absent. JGit 7.8.0 {@code ReftableBatchRefUpdate}
     * compares a symbolic command with its immediate target name only, so without the commands on the chain's links a
     * writer that retargeted one of them while the base reference stayed at the expected id would go unseen. It also
     * compares every command under one lock before it writes any, so a writer that changed any of them makes the
     * whole batch fail and nothing is written. A backend without atomic batches, and the file backend, which refuses
     * a symbolic command in an atomic batch ({@code PackedBatchRefUpdate}), write nothing either.
     *
     * @param symbolic the symbolic reference as read, with its chain of targets
     * @param expected the id the symbolic reference must still resolve to; {@code null} when it must still be dangling
     * @param newId    the new id of the reference; {@link ObjectId#zeroId()} deletes it
     * @return how the batch ended
     */
    static BatchOutcome guardedUnlink(Repository repository, Ref symbolic, @Nullable ObjectId expected,
            ObjectId newId) throws IOException {
        ReceiveCommand unlink = ReceiveCommand.unlink(symbolic.getTarget().getName(), newId, symbolic.getName());
        List<ReceiveCommand> commands = new ArrayList<>();
        commands.add(unlink);
        Ref link = symbolic.getTarget();
        while (link.isSymbolic()) {
            String target = link.getTarget().getName();
            commands.add(ReceiveCommand.link(target, target, link.getName()));
            link = link.getTarget();
        }
        ObjectId base = expected == null ? ObjectId.zeroId() : expected;
        commands.add(new ReceiveCommand(base, base, link.getName()));
        BatchRefUpdate batch = repository.getRefDatabase().newBatchUpdate();
        batch.setAtomic(true);
        batch.setAllowNonFastForwards(true);
        batch.disableRefLog();
        batch.addCommand(commands);
        try (RevWalk walk = new RevWalk(repository)) {
            batch.execute(walk, NullProgressMonitor.INSTANCE);
        }
        return outcomeOf(commands);
    }

    private static BatchOutcome outcomeOf(List<ReceiveCommand> commands) {
        boolean applied = true;
        boolean lost = false;
        for (ReceiveCommand command : commands) {
            if (command.getResult() == ReceiveCommand.Result.LOCK_FAILURE) {
                if (REFTABLE_IO_ERROR.equals(command.getMessage())) {
                    return BatchOutcome.IO_FAILED;
                }
                lost = true;
            }
            applied &= command.getResult() == ReceiveCommand.Result.OK;
        }
        if (applied) {
            return BatchOutcome.APPLIED;
        }
        return lost ? BatchOutcome.LOST_RACE : BatchOutcome.REFUSED;
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
