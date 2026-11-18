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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.git.document.core.CapabilityRefusedException;
import de.cuioss.git.document.core.GitIdentity;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import de.cuioss.git.document.core.RepositoryOperations.Identity;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.EmptyCommitException;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
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
 * operation starts a process; an operation that would run a present hook or a filter driver ends
 * {@link GitOutcome#REFUSED_BY_POLICY} with the refused capability in the detail (GDR-WT-2). Commits are never signed.
 * <p>
 * The start point and the branch of a new working tree are classified by {@link GitIdentity}: an ambiguous name is
 * refused as {@link GitOutcome#AMBIGUOUS} with its candidates, an abbreviated commit id as {@link GitOutcome#NOT_FOUND},
 * and only a supported identity, exactly one reference or a full commit id, is resolved. Details and log messages name
 * the input field, a path, a reference of the repository or a commit id, never an author or committer name, an e-mail
 * address, a commit message or the message of a failure (GDR-NFR-3).
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

    /** The detail suffix of an input field whose value names nothing in the repository. */
    private static final String NOT_FOUND_SUFFIX = ": not found";

    /** The reason of a refused abbreviated start point. */
    private static final String ABBREVIATED_COMMIT_ID = ": abbreviated commit id";

    private final FS fs;

    /** Creates the operations on a {@link RefusingFS}. */
    public JGitWorktreeOperations() {
        this.fs = new RefusingFS();
    }

    @Override
    public GitResult<Worktree> worktreeAdd(WorktreeAddInput input) {
        Objects.requireNonNull(input, INPUT_ARGUMENT);
        Path target;
        try {
            Path root = input.root().toRealPath();
            target = canonical(input.path());
            if (!target.startsWith(root) || target.equals(root)) {
                return GitResult.of(GitOutcome.PATH_REFUSED, input.path() + " is not inside " + root);
            }
            if (Files.exists(target) && !isEmptyDirectory(target)) {
                return GitResult.of(GitOutcome.ALREADY_EXISTS, target.toString());
            }
        } catch (IOException e) {
            return failure(e);
        }
        try (Repository main = openRepository(input.root())) {
            return addWorktree(main, input, target);
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.root().toString());
        } catch (IOException | JGitInternalException | IllegalArgumentException e) {
            return failure(e);
        }
    }

    private GitResult<Worktree> addWorktree(Repository main, WorktreeAddInput input, Path target) throws IOException {
        Path commonDir = main.getCommonDirectory().toPath().toRealPath();
        Optional<String> branchRef = Optional.ofNullable(input.branch()).map(b -> Constants.R_HEADS + b);
        var startLookup = GitIdentity.lookup(main, input.startPoint());
        Optional<GitResult<Worktree>> refused = refuseAmbiguousOrAbbreviated(startLookup, START_POINT);
        if (refused.isPresent()) {
            return refused.get();
        }
        boolean existingBranch = branchRef.isPresent() && !input.createBranch();
        var resolved = existingBranch
                ? existingBranchStart(main, commonDir, Objects.requireNonNull(input.branch()), branchRef.get())
                : newStart(main, input, startLookup, branchRef.orElse(null));
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
        Files.createDirectories(target);
        Path admin = LinkedWorktrees.create(commonDir, target, branchRef.orElse(start.name()));
        try (Repository linked = openRepository(target); RevWalk walk = new RevWalk(linked)) {
            RevCommit commit = walk.parseCommit(start);
            var checkout = new DirCacheCheckout(linked, linked.lockDirCache(), commit.getTree());
            checkout.setFailOnConflict(true);
            checkout.checkout();
            LinkedWorktrees.unlock(admin);
            String name = admin.getFileName().toString();
            LOGGER.info(INFO.WORKTREE_ADDED, target, name);
            return GitResult.ok(Worktree.builder()
                    .path(target)
                    .name(name)
                    .branch(input.branch())
                    .head(start.name())
                    .main(false)
                    .locked(false)
                    .prunable(false)
                    .build());
        } catch (IOException | JGitInternalException e) {
            LinkedWorktrees.delete(commonDir, admin.getFileName().toString(), target);
            throw e;
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
     * name, so a second reference of that name is ambiguous, and it must not be checked out elsewhere.
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
            return StartPoint.refused(GitOutcome.BRANCH_CHECKED_OUT, BRANCH + ": checked out in another working tree");
        }
        return StartPoint.of(existing.getObjectId());
    }

    /**
     * The start of a detached working tree or of one on a new branch: only a supported identity is resolved, and a
     * new branch is created at it.
     */
    private static StartPoint newStart(Repository main, WorktreeAddInput input, GitIdentity.Lookup lookup,
            @Nullable String branchRef) throws IOException {
        if (!lookup.isSupported()) {
            return StartPoint.refused(GitOutcome.NOT_FOUND, START_POINT + NOT_FOUND_SUFFIX);
        }
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
        if (branchRef != null) {
            if (main.exactRef(branchRef) != null) {
                return StartPoint.refused(GitOutcome.ALREADY_EXISTS, BRANCH + ": already exists");
            }
            var update = main.updateRef(branchRef);
            update.setNewObjectId(start);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            update.setRefLogMessage("branch: Created from " + lookup.target().orElseThrow(), false);
            Result result = update.update();
            if (result != Result.NEW) {
                return StartPoint.refused(GitOutcome.FAILED, "branch creation: " + result);
            }
        }
        return StartPoint.of(start);
    }

    /**
     * Refuses a start point that is ambiguous, with its candidates, or an abbreviated commit id. The detail names the
     * field and the reason; the candidates of an ambiguity are references of the repository.
     */
    private static <V> Optional<GitResult<V>> refuseAmbiguousOrAbbreviated(GitIdentity.Lookup lookup, String field) {
        if (GitIdentity.isAmbiguous(lookup.resolution())) {
            return Optional.of(GitResult.of(GitOutcome.AMBIGUOUS, candidates(field, lookup)));
        }
        if (lookup.resolution() == GitIdentity.Resolution.ABBREVIATED_COMMIT_ID) {
            return Optional.of(GitResult.of(GitOutcome.NOT_FOUND, field + ABBREVIATED_COMMIT_ID));
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
        } catch (IOException | IllegalArgumentException | JGitInternalException e) {
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
            Path real = Files.exists(target) ? target.toRealPath() : target;
            Optional<Worktree> match = Objects.requireNonNull(listed.value(), "value").stream()
                    .filter(w -> w.path().equals(real) || w.path().equals(target)).findFirst();
            if (match.isEmpty()) {
                return GitResult.of(GitOutcome.WORKTREE_NOT_FOUND, target.toString());
            }
            Worktree found = match.get();
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
            if (!found.prunable()) {
                try (Repository linked = openRepository(found.path())) {
                    if (!Git.wrap(linked).status().call().isClean()) {
                        return GitResult.of(GitOutcome.WORKTREE_DIRTY, found.path().toString());
                    }
                }
            }
            LinkedWorktrees.delete(commonDir, Objects.requireNonNull(found.name(), "name"), found.path());
            LOGGER.info(INFO.WORKTREE_REMOVED, found.path());
            return GitResult.ok(found.path());
        } catch (GitAPIException | IOException | JGitInternalException e) {
            return failure(e);
        }
    }

    @Override
    public GitResult<CommitInfo> commit(CommitInput input) {
        Objects.requireNonNull(input, INPUT_ARGUMENT);
        try (Repository repository = openRepository(input.root()); Git git = Git.wrap(repository)) {
            git.add().addFilepattern(".").call();
            git.add().addFilepattern(".").setUpdate(true).call();
            RevCommit commit = git.commit()
                    .setMessage(input.message())
                    .setAuthor(new PersonIdent(input.author().name(), input.author().email()))
                    .setCommitter(new PersonIdent(input.committer().name(), input.committer().email()))
                    .setSign(Boolean.FALSE)
                    .setAllowEmpty(false)
                    .call();
            return GitResult.ok(toInfo(commit));
        } catch (EmptyCommitException e) {
            return GitResult.of(GitOutcome.NOTHING_TO_COMMIT, "no changes");
        } catch (RepositoryNotFoundException e) {
            return GitResult.of(GitOutcome.NOT_A_REPOSITORY, input.root().toString());
        } catch (GitAPIException | IOException | JGitInternalException e) {
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
