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
package de.cuioss.git.document.worktrees;

import static de.cuioss.git.document.worktrees.GitWorktreesLogMessages.INFO;
import static de.cuioss.git.document.worktrees.GitWorktreesLogMessages.WARN;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;


import de.cuioss.git.document.core.CapabilityRefusedException;
import de.cuioss.git.document.core.GitIdentity;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import de.cuioss.git.document.core.RepositoryOperations.Identity;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.NoWorkTreeException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.hooks.CommitMsgHook;
import org.eclipse.jgit.hooks.PostCommitHook;
import org.eclipse.jgit.hooks.PreCommitHook;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.util.FS;
import org.jspecify.annotations.Nullable;

/**
 * The working-tree operations on JGit (checked against JGit 7.8.0), in process and without a {@code git}
 * installation.
 * <p>
 * JGit has no command that adds or removes a linked working tree, so the administration files are read and written
 * natively in git's own layout ({@link LinkedWorktrees}). Every repository is opened with a {@link RefusingFS}, so no
 * operation starts a process through the repository's file system; an operation that would run a present hook or a
 * filter driver ends {@link GitOutcome#REFUSED_BY_POLICY} with the refused capability in the detail (GDR-WT-2). The
 * one process start outside it is JGit's process-wide {@code SystemReader}, which the operations leave untouched; it
 * is prevented only where the application installed {@link RefusingFS#systemReader} before any JGit use. Commits are
 * never signed.
 * <p>
 * The start point and the branch of a new working tree are classified by {@link GitIdentity}: an ambiguous name is
 * refused as {@link GitOutcome#AMBIGUOUS} with its candidates, an abbreviated or unknown one as
 * {@link GitOutcome#NOT_FOUND}, and only a supported identity, exactly one reference or a full commit id, is resolved.
 * <p>
 * Before an addition claims anything, it is guarded in this order: the root must be the main working tree, not a
 * linked one ({@link GitOutcome#PATH_REFUSED}); the branch, new or existing, must form a valid reference name
 * {@code refs/heads/<branch>} by JGit 7.8.0 {@link Repository#isValidRefName(String)}
 * ({@link GitOutcome#REFUSED_BY_POLICY}, the detail naming the field, never the string); the target must lie inside
 * the root, outside the git directory with its administration area and outside every registered linked working tree
 * ({@link GitOutcome#PATH_REFUSED}). Only then is the start point classified and the target claimed.
 * <p>
 * Details name the input field, a path, a reference of the repository or a commit id, and log messages a commit id,
 * never an author or committer name, an e-mail address, a commit message or the message of a failure (GDR-NFR-3).
 *
 * @since 0.1
 */
public final class JGitWorktreeOperations implements WorktreeOperations {

    private static final CuiLogger LOGGER = new CuiLogger(JGitWorktreeOperations.class);

    /** The name of the input argument, reported by the null checks. */
    private static final String INPUT_ARGUMENT = "input";

    /** The input field of the start point. */
    private static final String START_POINT = "startPoint";

    /** The input field of the branch. */
    private static final String BRANCH = "branch";

    /** The input field of the root. */
    private static final String ROOT = "root";

    /** The detail of a branch name that is no valid reference name; it never carries the caller's string. */
    private static final String INVALID_BRANCH = BRANCH + ": not a valid branch name";

    /** The detail suffix of an input field whose value names nothing in the repository. */
    private static final String NOT_FOUND_SUFFIX = ": not found";

    /** The reason of a refused abbreviated start point. */
    private static final String ABBREVIATED_COMMIT_ID = ": abbreviated commit id";

    /** The detail of a branch checked out, or being checked out, in another working tree. */
    private static final String CHECKED_OUT = BRANCH + ": checked out in another working tree";

    /** The refused capability of a present hook, as {@link RefusingFS} names it. */
    private static final String REPOSITORY_HOOK = "repository_hook";

    /** The hooks JGit 7.8.0 {@code CommitCommand} runs, the post-commit hook after the branch is updated. */
    private static final List<String> COMMIT_HOOKS =
            List.of(PreCommitHook.NAME, CommitMsgHook.NAME, PostCommitHook.NAME);

    private final FS fs;

    /** Creates the operations on a {@link RefusingFS}. */
    public JGitWorktreeOperations() {
        this(new RefusingFS());
    }

    /**
     * Creates the operations on the given file system; the test seam for failures no file-system condition provokes.
     *
     * @param fs the file system every repository is opened with
     */
    JGitWorktreeOperations(FS fs) {
        this.fs = Objects.requireNonNull(fs, "fs");
    }

    @Override
    public GitResult<Worktree> worktreeAdd(WorktreeAddInput input) {
        Objects.requireNonNull(input, INPUT_ARGUMENT);
        try (Repository main = openRepository(input.root())) {
            return addWorktree(main, input);
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.root().toString());
        } catch (IOException | JGitInternalException | IllegalArgumentException e) {
            return failure(e);
        }
    }

    /**
     * Adds the working tree after the guards, in this order: the root must be the main working tree, the branch
     * name must be valid, the target must lie inside the root and outside the git directory and every registered
     * linked working tree, and the start point must be supported. Nothing is claimed before every guard passed.
     */
    private GitResult<Worktree> addWorktree(Repository main, WorktreeAddInput input) throws IOException {
        Path commonDir = main.getCommonDirectory().toPath().toRealPath();
        if (!Files.isSameFile(main.getDirectory().toPath(), commonDir)) {
            return GitResult.of(GitOutcome.PATH_REFUSED, ROOT + ": not the main working tree");
        }
        String branch = input.branch();
        if (branch != null) {
            Optional<GitResult<Worktree>> invalid = refuseBranchName(branch);
            if (invalid.isPresent()) {
                return invalid.get();
            }
        }
        Path target = canonical(input.path());
        Optional<GitResult<Worktree>> refusedTarget = refuseTarget(input, commonDir, target);
        if (refusedTarget.isPresent()) {
            return refusedTarget.get();
        }
        var startLookup = GitIdentity.lookup(main, input.startPoint());
        Optional<GitResult<Worktree>> refused = refuseStartPoint(startLookup);
        if (refused.isPresent()) {
            return refused.get();
        }
        var attempt = new AddAttempt(main, commonDir, input, target);
        try {
            GitResult<Worktree> result = attempt.run(startLookup);
            if (!result.isOk()) {
                attempt.rollBack();
            }
            return result;
        }
        // every failure of the attempt, an unchecked one of JGit included, is rolled back and ends as a result
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (IOException | RuntimeException e) {
            try {
                attempt.rollBack();
            }
            // an unchecked failure of the rollback must not replace the failure it is rolling back
            // cui-rewrite:disable InvalidExceptionUsageRecipe
            catch (IOException | RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            return failure(e);
        }
    }

    /**
     * One addition of a linked working tree, with everything it claimed, so that a refused or failed addition undoes
     * exactly what it did: the target it claimed, the branch reservation it holds, the branch it created and the
     * administration directory it wrote, never anything another addition owns.
     */
    private final class AddAttempt {

        private final Repository main;
        private final Path commonDir;
        private final WorktreeAddInput input;
        private final Path target;

        /** Whether this attempt owns the target's {@code .git} file. */
        private boolean claimed;

        /** Whether this attempt created the target directory. */
        private boolean createdDirectory;

        private @Nullable Path reservation;
        private @Nullable String createdBranchRef;
        private @Nullable ObjectId createdBranchId;
        private @Nullable Path admin;

        AddAttempt(Repository main, Path commonDir, WorktreeAddInput input, Path target) {
            this.main = main;
            this.commonDir = commonDir;
            this.input = input;
            this.target = target;
        }

        GitResult<Worktree> run(GitIdentity.Lookup startLookup) throws IOException {
            if (!claimTarget()) {
                return GitResult.of(GitOutcome.ALREADY_EXISTS, target.toString());
            }
            String branch = input.branch();
            String branchRef = branch == null ? null : Constants.R_HEADS + branch;
            boolean existingBranch = branchRef != null && !input.createBranch();
            // every branch, new or existing, is reserved before it is checked, so of two additions for one branch
            // exactly one gets past the check (the atomic Files.createFile reservation in the common directory)
            if (branchRef != null) {
                reservation = LinkedWorktrees.reserveBranch(commonDir, branchRef).orElse(null);
                if (reservation == null) {
                    return GitResult.of(GitOutcome.BRANCH_CHECKED_OUT, CHECKED_OUT);
                }
            }
            StartPoint resolved = existingBranch
                    ? existingBranchStart(main, commonDir, Objects.requireNonNull(branch), branchRef)
                    : newStart(main, input, startLookup, branchRef);
            GitResult<Worktree> refusal = resolved.refusal();
            if (refusal != null) {
                return refusal;
            }
            ObjectId start = Objects.requireNonNull(resolved.id(), "id");
            if (existingBranch && GitIdentity.isFullCommitId(input.startPoint())
                    && !start.name().equalsIgnoreCase(input.startPoint())) {
                return GitResult.of(GitOutcome.IDENTITY_MISMATCH,
                        "the branch is at " + start.name() + ", not " + input.startPoint());
            }
            if (branchRef != null && !existingBranch) {
                Optional<GitResult<Worktree>> notCreated = createBranch(branchRef, start, startLookup);
                if (notCreated.isPresent()) {
                    return notCreated.get();
                }
            }
            Path created = LinkedWorktrees.create(commonDir, target, branchRef != null ? branchRef : start.name());
            admin = created;
            try (Repository linked = openRepository(target); RevWalk walk = new RevWalk(linked)) {
                RevCommit commit = walk.parseCommit(start);
                var checkout = new DirCacheCheckout(linked, linked.lockDirCache(), commit.getTree());
                checkout.setFailOnConflict(true);
                checkout.checkout();
            }
            LinkedWorktrees.unlock(created);
            // the reservation is held until the working tree is complete, so no other addition registers the branch
            // while a failed checkout is still being rolled back
            releaseReservation();
            LOGGER.info(INFO.WORKTREE_ADDED, start.name());
            return GitResult.ok(Worktree.builder()
                    .path(target)
                    .name(created.getFileName().toString())
                    .branch(branch)
                    .head(start.name())
                    .main(false)
                    .locked(false)
                    .prunable(false)
                    .build());
        }

        /**
         * Claims the target: a missing target directory is created with {@link Files#createDirectory}, so of two
         * concurrent claims exactly one creates it; in the directory the target's {@code .git} file is created with
         * {@link Files#createFile}, the narrowest claim of an existing empty directory, which also fails for every
         * claim but one. A directory that holds more than the claimed {@code .git} file was written by someone else,
         * so only the claimed file is removed again.
         */
        private boolean claimTarget() throws IOException {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(Objects.requireNonNull(target.getParent(), "parent"));
                try {
                    Files.createDirectory(target);
                } catch (FileAlreadyExistsException e) {
                    return false;
                }
                createdDirectory = true;
            }
            Path gitFile = target.resolve(Constants.DOT_GIT);
            try {
                Files.createFile(gitFile);
            } catch (FileAlreadyExistsException e) {
                createdDirectory = false;
                return false;
            }
            claimed = true;
            try (var entries = Files.list(target)) {
                if (entries.count() > 1) {
                    claimed = false;
                    createdDirectory = false;
                    Files.delete(gitFile);
                    return false;
                }
            }
            return true;
        }

        /**
         * Creates the new branch at the start, only if it does not exist. A concurrent creation of the same branch
         * wins the compare-and-swap from the zero id, and this attempt ends {@link GitOutcome#ALREADY_EXISTS}.
         */
        private Optional<GitResult<Worktree>> createBranch(String branchRef, ObjectId start,
                GitIdentity.Lookup startLookup) throws IOException {
            var update = main.updateRef(branchRef);
            update.setNewObjectId(start);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.setRefLogMessage("branch: Created from " + startLookup.target().orElseThrow(), false);
            Result result = update.update();
            if (result == Result.NEW) {
                createdBranchRef = branchRef;
                createdBranchId = start;
                return Optional.empty();
            }
            if (main.exactRef(branchRef) != null) {
                return Optional.of(GitResult.of(GitOutcome.ALREADY_EXISTS, BRANCH + ": already exists"));
            }
            return Optional.of(GitResult.of(GitOutcome.FAILED, "branch creation: " + result));
        }

        private void releaseReservation() throws IOException {
            Path held = reservation;
            if (held != null) {
                reservation = null;
                LinkedWorktrees.release(held);
            }
        }

        /** Releases the claimed target: the directory this attempt created, else only what it wrote into it. */
        private void releaseTarget() throws IOException {
            if (!claimed) {
                return;
            }
            claimed = false;
            if (createdDirectory) {
                LinkedWorktrees.deleteTree(target);
                return;
            }
            try (var entries = Files.list(target)) {
                for (Path entry : entries.toList()) {
                    LinkedWorktrees.deleteTree(entry);
                }
            }
        }

        /**
         * Deletes the branch this attempt created, only while it still points at the commit it was created at, so a
         * branch another writer advanced in the meantime is kept. The result of the deletion is read: a forced
         * update or no change is the deleted branch; after a lock failure or a rejection the branch is read again
         * (JGit 7.8.0 {@code RefUpdate.delete} returns {@code LOCK_FAILURE} both for a held reference lock and for an
         * expected id the branch is no longer at), and every other result, an I/O failure among them, is raised so the
         * rollback reports it.
         */
        private void deleteCreatedBranch() throws IOException {
            String branchRef = createdBranchRef;
            ObjectId id = createdBranchId;
            if (branchRef == null || id == null) {
                return;
            }
            createdBranchRef = null;
            RefUpdate delete = main.updateRef(branchRef);
            delete.setExpectedOldObjectId(id);
            delete.setForceUpdate(true);
            Result result = delete.delete();
            switch (result) {
                case FORCED, NO_CHANGE -> {
                    // deleted
                }
                case LOCK_FAILURE, REJECTED -> confirmNotLeft(branchRef, id, result);
                default -> throw new IOException("branch deletion: " + result);
            }
        }

        /**
         * Reads the branch again after a deletion that did not delete it: an absent branch was deleted, a branch at
         * another commit was advanced by another writer and is kept, and a branch still at the commit this attempt
         * created it at is left behind, which is raised so the rollback reports it rather than leaving it silently.
         */
        private void confirmNotLeft(String branchRef, ObjectId id, Result result) throws IOException {
            Ref current = main.exactRef(branchRef);
            if (current != null && id.equals(current.getObjectId())) {
                throw new IOException("branch deletion incomplete: " + result);
            }
        }

        /**
         * Undoes the attempt in reverse order; every step runs, the first failure is thrown with the others. The
         * created branch is deleted before the reservation is released, so no other addition registers the branch
         * while this attempt may still delete it. An incomplete rollback is logged without an argument.
         */
        void rollBack() throws IOException {
            IOException failure = null;
            Path written = admin;
            admin = null;
            if (written != null) {
                failure = attempt(failure, () -> LinkedWorktrees.deleteTree(written));
            }
            failure = attempt(failure, this::releaseTarget);
            failure = attempt(failure, this::deleteCreatedBranch);
            failure = attempt(failure, this::releaseReservation);
            if (failure != null) {
                LOGGER.warn(WARN.ROLLBACK_INCOMPLETE);
                throw failure;
            }
        }
    }

    /** A rollback step. */
    @FunctionalInterface
    private interface Step {

        void run() throws IOException;
    }

    private static @Nullable IOException attempt(@Nullable IOException failure, Step step) {
        try {
            step.run();
            return failure;
        } catch (IOException e) {
            if (failure == null) {
                return e;
            }
            failure.addSuppressed(e);
            return failure;
        }
    }

    /** The commit a new working tree starts at, or the refusal that ends the operation. */
    private record StartPoint(@Nullable ObjectId id, @Nullable GitResult<Worktree> refusal) {

        static StartPoint of(ObjectId id) {
            return new StartPoint(id, null);
        }

        static StartPoint refused(GitOutcome outcome, String detail) {
            return new StartPoint(null, GitResult.of(outcome, detail));
        }
    }

    /**
     * The start of a working tree on an existing branch: the branch is classified by {@link GitIdentity} by its full
     * name, so a second reference of that name is ambiguous, and it must not be checked out elsewhere. The caller
     * holds the branch's reservation, so no other addition registers the branch between this check and its own.
     */
    private static StartPoint existingBranchStart(Repository main, Path commonDir, String branch, String branchRef)
            throws IOException {
        var lookup = GitIdentity.lookup(main, branchRef);
        if (GitIdentity.isAmbiguous(lookup.resolution())) {
            return StartPoint.refused(GitOutcome.AMBIGUOUS, candidates(BRANCH, lookup));
        }
        Ref existing = lookup.resolution() == GitIdentity.Resolution.REF ? main.exactRef(branchRef) : null;
        if (existing == null || existing.getObjectId() == null) {
            return StartPoint.refused(GitOutcome.NOT_FOUND, BRANCH + NOT_FOUND_SUFFIX);
        }
        if (checkedOut(main, commonDir, branch)) {
            return StartPoint.refused(GitOutcome.BRANCH_CHECKED_OUT, CHECKED_OUT);
        }
        return StartPoint.of(existing.getObjectId());
    }

    /**
     * The start of a detached working tree or of one on a new branch: the supported identity is resolved, and the
     * new branch must not exist yet.
     */
    private static StartPoint newStart(Repository main, WorktreeAddInput input, GitIdentity.Lookup lookup,
            @Nullable String branchRef) throws IOException {
        Optional<ObjectId> resolved = resolveCommit(main, lookup);
        if (resolved.isEmpty()) {
            return StartPoint.refused(GitOutcome.NOT_FOUND, START_POINT + NOT_FOUND_SUFFIX);
        }
        ObjectId start = resolved.get();
        if (lookup.resolution() == GitIdentity.Resolution.FULL_COMMIT_ID
                && !start.name().equalsIgnoreCase(input.startPoint())) {
            return StartPoint.refused(GitOutcome.IDENTITY_MISMATCH,
                    "the pin " + input.startPoint() + " reached " + start.name());
        }
        if (branchRef != null && main.exactRef(branchRef) != null) {
            return StartPoint.refused(GitOutcome.ALREADY_EXISTS, BRANCH + ": already exists");
        }
        return StartPoint.of(start);
    }

    /**
     * Refuses a start point that is ambiguous, with its candidates, abbreviated, or no supported identity at all, so
     * an unknown start point is not found even where an existing branch would select its own tip. The detail names
     * the field and the reason; the candidates of an ambiguity are references of the repository.
     */
    private static <V> Optional<GitResult<V>> refuseStartPoint(GitIdentity.Lookup lookup) {
        if (GitIdentity.isAmbiguous(lookup.resolution())) {
            return Optional.of(GitResult.of(GitOutcome.AMBIGUOUS, candidates(START_POINT, lookup)));
        }
        if (lookup.resolution() == GitIdentity.Resolution.ABBREVIATED_COMMIT_ID) {
            return Optional.of(GitResult.of(GitOutcome.NOT_FOUND, START_POINT + ABBREVIATED_COMMIT_ID));
        }
        if (!lookup.isSupported()) {
            return Optional.of(GitResult.of(GitOutcome.NOT_FOUND, START_POINT + NOT_FOUND_SUFFIX));
        }
        return Optional.empty();
    }

    /**
     * Refuses a branch name whose full reference {@code refs/heads/<branch>} is no valid reference name by JGit 7.8.0
     * {@link Repository#isValidRefName(String)}, which rejects among others a {@code ..} component, a leading
     * {@code /} and the characters git forbids, so no caller string reaches a reference update or a file name of the
     * repository. The detail names the field only, never the string (GDR-NFR-3).
     */
    private static <V> Optional<GitResult<V>> refuseBranchName(String branch) {
        if (Repository.isValidRefName(Constants.R_HEADS + branch)) {
            return Optional.empty();
        }
        return Optional.of(GitResult.of(GitOutcome.REFUSED_BY_POLICY, INVALID_BRANCH));
    }

    /**
     * Refuses a target outside the root or equal to it, inside the git directory (which holds the administration
     * area of the linked working trees) or inside a registered linked working tree, and a target that is not empty
     * or is itself registered. The registered working trees are compared by their real paths.
     */
    private static Optional<GitResult<Worktree>> refuseTarget(WorktreeAddInput input, Path commonDir, Path target)
            throws IOException {
        Path root = input.root().toRealPath();
        if (!target.startsWith(root) || target.equals(root)) {
            return Optional.of(GitResult.of(GitOutcome.PATH_REFUSED, input.path() + " is not inside " + root));
        }
        if (target.startsWith(commonDir)) {
            return Optional.of(GitResult.of(GitOutcome.PATH_REFUSED, target + " is inside the git directory"));
        }
        for (Worktree linked : LinkedWorktrees.list(commonDir)) {
            Path linkedRoot = canonical(linked.path());
            if (target.equals(linkedRoot)) {
                return Optional.of(GitResult.of(GitOutcome.ALREADY_EXISTS, target.toString()));
            }
            if (target.startsWith(linkedRoot)) {
                return Optional.of(GitResult.of(GitOutcome.PATH_REFUSED,
                        target + " is inside another working tree"));
            }
        }
        if (Files.exists(target) && !isEmptyDirectory(target)) {
            return Optional.of(GitResult.of(GitOutcome.ALREADY_EXISTS, target.toString()));
        }
        return Optional.empty();
    }

    private static String candidates(String field, GitIdentity.Lookup lookup) {
        return field + " names " + String.join(", ", lookup.candidates());
    }

    /**
     * Resolves a supported identity to its commit: a reference by its exact full name, a full commit id as itself. A
     * full commit id whose object is absent is not found: JGit 7.8.0 {@code Repository.resolve("<id>^{commit}")}
     * throws {@link MissingObjectException} for it.
     */
    private static Optional<ObjectId> resolveCommit(Repository repository, GitIdentity.Lookup lookup)
            throws IOException {
        String target = lookup.target().orElseThrow();
        ObjectId selected;
        if (lookup.resolution() == GitIdentity.Resolution.REF) {
            Ref ref = repository.exactRef(target);
            if (ref == null || ref.getObjectId() == null) {
                return Optional.empty();
            }
            selected = ref.getObjectId();
        } else {
            selected = ObjectId.fromString(target);
        }
        try {
            return Optional.ofNullable(repository.resolve(selected.name() + "^{commit}"));
        } catch (MissingObjectException e) {
            return Optional.empty();
        }
    }

    private static boolean checkedOut(Repository main, Path commonDir, String branch) throws IOException {
        if (!main.isBare() && branch.equals(main.getBranch())) {
            return true;
        }
        return LinkedWorktrees.list(commonDir).stream().anyMatch(w -> branch.equals(w.branch()));
    }

    @Override
    public GitResult<List<Worktree>> worktreeList(Path root) {
        Objects.requireNonNull(root, "root");
        try (Repository repository = openRepository(root)) {
            Path commonDir = repository.getCommonDirectory().toPath().toRealPath();
            var worktrees = new ArrayList<Worktree>();
            try (Repository main = mainRepository(commonDir)) {
                Optional<ObjectId> head = Optional.ofNullable(main.resolve(Constants.HEAD));
                Ref headRef = main.exactRef(Constants.HEAD);
                Optional<String> branch = headRef != null && headRef.isSymbolic()
                        ? Optional.of(Repository.shortenRefName(headRef.getTarget().getName()))
                        : Optional.empty();
                worktrees.add(Worktree.builder()
                        .path(main.getWorkTree().toPath().toRealPath())
                        .name(null)
                        .branch(branch.orElse(null))
                        .head(head.map(ObjectId::name).orElse(null))
                        .main(true)
                        .locked(false)
                        .prunable(false)
                        .build());
            }
            worktrees.addAll(LinkedWorktrees.list(commonDir));
            return GitResult.ok(List.copyOf(worktrees));
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, root.toString());
        } catch (IOException | IllegalArgumentException | JGitInternalException | NoWorkTreeException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<Path> worktreeRemove(Path root, Path worktree) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(worktree, "worktree");
        GitResult<List<Worktree>> listed = worktreeList(root);
        if (!listed.isOk()) {
            return GitResult.of(listed.outcome(), Objects.requireNonNull(listed.detail(), "detail"));
        }
        try {
            Path target = worktree.toAbsolutePath().normalize();
            Path real = realPath(target);
            Worktree found = null;
            for (Worktree candidate : Objects.requireNonNull(listed.value(), "value")) {
                if (realPath(candidate.path()).equals(real) || candidate.path().equals(target)) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                return GitResult.of(GitOutcome.WORKTREE_NOT_FOUND, target.toString());
            }
            if (found.main()) {
                return GitResult.of(GitOutcome.PATH_REFUSED, "the main working tree cannot be removed");
            }
            if (found.locked()) {
                return GitResult.of(GitOutcome.WORKTREE_LOCKED, found.path().toString());
            }
            Path commonDir;
            try (Repository repository = openRepository(root)) {
                commonDir = repository.getCommonDirectory().toPath().toRealPath();
            }
            String name = Objects.requireNonNull(found.name(), "name");
            if (found.prunable()) {
                LinkedWorktrees.deleteAdmin(commonDir, name);
            } else {
                Path admin = commonDir.resolve(LinkedWorktrees.WORKTREES).resolve(name);
                // the real root, so a working tree registered through a symbolic link is deleted, not the link
                Path registered = realPath(found.path());
                try (Repository linked = openRepository(registered)) {
                    if (!Files.isSameFile(linked.getDirectory().toPath(), admin)) {
                        return GitResult.of(GitOutcome.WORKTREE_NOT_FOUND,
                                found.path() + " is not the registered working tree");
                    }
                    if (!Git.wrap(linked).status().call().isClean()) {
                        return GitResult.of(GitOutcome.WORKTREE_DIRTY, found.path().toString());
                    }
                }
                LinkedWorktrees.delete(commonDir, name, registered);
            }
            LOGGER.info(INFO.WORKTREE_REMOVED);
            return GitResult.ok(found.path());
        } catch (GitAPIException | IOException | JGitInternalException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<CommitInfo> commit(CommitInput input) {
        Objects.requireNonNull(input, INPUT_ARGUMENT);
        try (Repository repository = openRepository(input.root()); Git git = Git.wrap(repository)) {
            for (String hook : COMMIT_HOOKS) {
                if (repository.getFS().findHook(repository, hook) != null) {
                    return failure(new CapabilityRefusedException(REPOSITORY_HOOK, hook));
                }
            }
            Path commonDir = repository.getCommonDirectory().toPath().toRealPath();
            boolean linked = !Files.isSameFile(repository.getDirectory().toPath(), commonDir);
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            unstageLinkedRoots(repository, commonDir);
            ObjectId previous = repository.resolve(Constants.HEAD + "^{commit}");
            CommitCommand command = git.commit()
                    .setMessage(input.message())
                    .setAuthor(new PersonIdent(input.author().name(), input.author().email()))
                    .setCommitter(new PersonIdent(input.committer().name(), input.committer().email()))
                    .setSign(Boolean.FALSE)
                    .setAllowEmpty(false);
            if (linked) {
                command.setReflogComment(null);
            }
            RevCommit commit = command.call();
            if (linked) {
                writeLinkedReflogs(repository, commonDir, previous, commit);
            }
            return GitResult.ok(toInfo(commit));
        } catch (EmptyCommitException e) {
            return GitResult.of(GitOutcome.NOTHING_TO_COMMIT, "no changes");
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.root().toString());
        } catch (GitAPIException | IOException | JGitInternalException | NoWorkTreeException e) {
            return failure(e);
        }
    }

    /**
     * Writes the reflog entries of a created commit in a linked working tree. The commit and its branch are already
     * stored, so a reflog entry that cannot be written does not fail the commit: it is logged with the commit id only,
     * never the path or the message of the failure (GDR-NFR-3). An invalid {@code core.logAllRefUpdates} value is such
     * a case: JGit 7.8.0 {@code Config.getEnum} throws {@link IllegalArgumentException} for a value that names no
     * constant.
     */
    private static void writeLinkedReflogs(Repository linked, Path commonDir, @Nullable ObjectId previous,
            RevCommit commit) {
        try {
            appendLinkedReflogs(linked, commonDir, previous, commit);
        } catch (IOException | IllegalArgumentException e) {
            LOGGER.warn(WARN.REFLOG_NOT_WRITTEN, commit.name());
        }
    }

    /**
     * Removes from the index of a working tree the root of every registered linked working tree inside it: JGit 7.8.0
     * {@code FileTreeIterator} reports a directory holding a {@code .git} entry as a gitlink, which {@code AddCommand}
     * stages, so without this the main working tree would commit its linked working trees. The registered roots are
     * compared as real paths, so a root registered through a symbolic link is found as well.
     */
    private static void unstageLinkedRoots(Repository repository, Path commonDir) throws IOException {
        Path workTree = repository.getWorkTree().toPath().toRealPath();
        var roots = new ArrayList<String>();
        for (Worktree linked : LinkedWorktrees.list(commonDir)) {
            Path path = realPath(linked.path());
            if (path.startsWith(workTree) && !path.equals(workTree)) {
                roots.add(workTree.relativize(path).toString().replace(File.separatorChar, '/'));
            }
        }
        if (roots.isEmpty()) {
            return;
        }
        DirCache index = repository.lockDirCache();
        try {
            DirCacheEditor editor = index.editor();
            for (String root : roots) {
                DirCacheEntry entry = index.getEntry(root);
                if (entry != null && entry.getFileMode() == FileMode.GITLINK) {
                    editor.add(new DirCacheEditor.DeletePath(root));
                }
            }
            editor.commit();
        } finally {
            index.unlock();
        }
    }

    /**
     * Writes the reflog entries of a commit in a linked working tree where git writes them: JGit 7.8.0
     * {@code RefDirectory.logFor("HEAD")} resolves to the {@code logs/HEAD} of the common directory, the reflog of the
     * main working tree's {@code HEAD}, so the commit runs without a reflog and its {@code HEAD} entry goes to the
     * linked working tree's own {@code logs/HEAD}, its branch entry to the branch's log in the common directory. As in
     * JGit, a log is written when {@code core.logAllRefUpdates} asks for it or the log exists.
     */
    private static void appendLinkedReflogs(Repository linked, Path commonDir, @Nullable ObjectId previous,
            RevCommit commit) throws IOException {
        CoreConfig.LogRefUpdates mode = linked.getConfig().getEnum(ConfigConstants.CONFIG_CORE_SECTION, null,
                ConfigConstants.CONFIG_KEY_LOGALLREFUPDATES, CoreConfig.LogRefUpdates.TRUE);
        String prefix = previous == null ? "commit (initial): " : "commit: ";
        String entry = ObjectId.toString(previous) + " " + commit.name() + " "
                + commit.getCommitterIdent().toExternalString() + "\t"
                + (prefix + commit.getShortMessage()).replace("\r\n", " ").replace("\n", " ") + "\n";
        appendLog(linked.getDirectory().toPath().resolve(Constants.LOGS).resolve(Constants.HEAD), mode, entry);
        Ref head = linked.exactRef(Constants.HEAD);
        if (head != null && head.isSymbolic()) {
            appendLog(commonDir.resolve(Constants.LOGS).resolve(head.getTarget().getName()), mode, entry);
        }
    }

    private static void appendLog(Path log, CoreConfig.LogRefUpdates mode, String entry) throws IOException {
        if (mode == CoreConfig.LogRefUpdates.FALSE && !Files.isRegularFile(log)) {
            return;
        }
        Files.createDirectories(Objects.requireNonNull(log.getParent(), "parent"));
        Files.writeString(log, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The status is JGit's {@code StatusCommand}, mapped by the definitions of {@link Status} (checked against the
     * JGit 7.8.0 sources): {@link Status#getAdded() added} (in the index, not in {@code HEAD}),
     * {@link Status#getChanged() changed} (changed from {@code HEAD} to the index), {@link Status#getModified()
     * modified} (modified on disk relative to the index) and {@link Status#getConflicting() conflicting} are changed;
     * {@link Status#getRemoved() removed} (in {@code HEAD}, not in the index) and {@link Status#getMissing() missing}
     * (in the index, not on disk) are deleted; {@link Status#getUntracked() untracked} (on disk, neither in the index
     * nor ignored) is untracked. Ignored files are in none of these sets. A file removed from the index but present on
     * disk is both removed and untracked, so it is in deleted and in untracked.
     */
    @Override
    public GitResult<WorktreeStatus> worktreeStatus(Path root) {
        Objects.requireNonNull(root, "root");
        try (Repository repository = openRepository(root); Git git = Git.wrap(repository)) {
            Status status = git.status().call();
            var changed = new TreeSet<String>();
            changed.addAll(status.getAdded());
            changed.addAll(status.getChanged());
            changed.addAll(status.getModified());
            changed.addAll(status.getConflicting());
            var deleted = new TreeSet<String>();
            deleted.addAll(status.getRemoved());
            deleted.addAll(status.getMissing());
            return GitResult.ok(new WorktreeStatus(changed, status.getUntracked(), deleted));
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, root.toString());
        } catch (GitAPIException | IOException | JGitInternalException | NoWorkTreeException e) {
            return failure(e);
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The branch name is guarded before any path is derived from it, by the same check as an addition's branch, so
     * no caller string reaches a file name of the repository. The reservation file is resolved in the real common
     * directory and deleted with one {@link Files#delete}; a missing file is {@link GitOutcome#NOT_FOUND}. The detail
     * of a refusal names the field only, never the string (GDR-NFR-3), and the log message carries no argument.
     */
    @Override
    public GitResult<String> releaseBranchReservation(Path root, String branch) {
        Objects.requireNonNull(root, ROOT);
        Objects.requireNonNull(branch, BRANCH);
        try (Repository repository = openRepository(root)) {
            // a repository without a working tree has no addition to release: JGit 7.8.0 getWorkTree() throws
            // NoWorkTreeException for it, which ends FAILED
            repository.getWorkTree();
            Optional<GitResult<String>> invalid = refuseBranchName(branch);
            if (invalid.isPresent()) {
                return invalid.get();
            }
            Path commonDir = repository.getCommonDirectory().toPath().toRealPath();
            String branchRef = Constants.R_HEADS + branch;
            if (!LinkedWorktrees.releaseBranch(commonDir, branchRef)) {
                return GitResult.of(GitOutcome.NOT_FOUND, BRANCH + ": no reservation");
            }
            LOGGER.info(INFO.BRANCH_RESERVATION_RELEASED);
            return GitResult.ok(branchRef);
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, root.toString());
        } catch (IOException | JGitInternalException | IllegalArgumentException | NoWorkTreeException e) {
            return failure(e);
        }
    }

    /**
     * Opens the repository at {@code root}: the working tree containing a {@code .git} entry (a directory, or the file
     * of a linked working tree), or the repository found by walking up from {@code root}.
     */
    private Repository openRepository(Path root) throws IOException {
        var builder = new FileRepositoryBuilder().setFS(fs).setMustExist(true);
        if (Files.exists(root.resolve(Constants.DOT_GIT))) {
            builder.setWorkTree(root.toFile());
        } else {
            builder.findGitDir(root.toFile());
            if (builder.getGitDir() == null) {
                throw new RepositoryNotFoundException(root.toFile());
            }
        }
        return builder.build();
    }

    private Repository mainRepository(Path commonDir) throws IOException {
        return new FileRepositoryBuilder().setFS(fs).setMustExist(true).setGitDir(commonDir.toFile()).build();
    }

    private static CommitInfo toInfo(RevCommit commit) {
        var parents = Arrays.stream(commit.getParents()).map(RevCommit::name).toList();
        PersonIdent author = commit.getAuthorIdent();
        PersonIdent committer = commit.getCommitterIdent();
        return CommitInfo.builder()
                .id(commit.name())
                .parents(parents)
                .author(new Identity(author.getName(), author.getEmailAddress()))
                .authoredAt(author.getWhenAsInstant())
                .committer(new Identity(committer.getName(), committer.getEmailAddress()))
                .committedAt(committer.getWhenAsInstant())
                .message(commit.getFullMessage())
                .build();
    }

    /**
     * Resolves a path that may not exist yet: the nearest existing ancestor is resolved with
     * {@link Path#toRealPath}, the missing segments are appended.
     */
    private static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (!Files.exists(existing)) {
            existing = existing.getParent();
        }
        return existing.toRealPath().resolve(existing.relativize(absolute));
    }

    /** The real path of a path that exists, the normalized absolute path of one that does not. */
    private static Path realPath(Path path) throws IOException {
        return Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize();
    }

    private static boolean isEmptyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return false;
        }
        try (var entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        }
    }

    /**
     * Maps an unexpected failure: a refused capability anywhere in its cause chain is
     * {@link GitOutcome#REFUSED_BY_POLICY} with the capability as detail, every other failure
     * {@link GitOutcome#FAILED} with the failure class only, since the message of a failure may carry the caller's
     * input (GDR-NFR-3).
     */
    private static <V> GitResult<V> failure(Exception e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            if (current instanceof CapabilityRefusedException refusal) {
                LOGGER.warn(WARN.CAPABILITY_REFUSED, refusal.getReason());
                return GitResult.of(GitOutcome.REFUSED_BY_POLICY, refusal.getReason());
            }
        }
        return GitResult.of(GitOutcome.FAILED, e.getClass().getSimpleName());
    }
}
