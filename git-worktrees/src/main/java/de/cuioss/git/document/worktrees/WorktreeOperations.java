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

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import de.cuioss.git.document.core.RepositoryOperations.Identity;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The operations on the working trees of a repository: adding, listing and removing linked working trees (GDR-WT-1),
 * the commit from a working tree (GDR-WT-2) and the status of a working tree. One method per operation, typed inputs,
 * and results carrying the
 * closed {@link GitOutcome} of {@code git-core} (GDR-GEN-3).
 * <p>
 * Every repository an implementation opens is built on {@link RefusingFS}, so no operation starts a process through
 * the repository's file system; an operation that would run a hook ends {@link GitOutcome#REFUSED_BY_POLICY} with the
 * refused capability in the detail. The guarantee is conditional on the startup exception of {@code git-core}
 * (GDR-CORE-1): JGit reads the user and system configuration through its process-wide {@code SystemReader}, which the
 * operations do not replace, and the default reader runs {@code git} once per JVM to locate the system
 * configuration. No process starts only where the application installed {@link RefusingFS#systemReader} before any
 * JGit use. No result detail carries an author or committer name, an e-mail address or a commit message (GDR-NFR-3).
 *
 * @since 0.1
 */
public interface WorktreeOperations {

    /**
     * Creates a linked working tree in git's own layout, so {@code git worktree list} reads it. The start point and
     * the branch are classified by {@code GitIdentity}: an ambiguous name is refused with its candidates in the detail,
     * and an abbreviated or unknown start point is never resolved, also not for an existing branch. The target is
     * claimed exclusively and an existing branch is reserved until the new working tree is registered, so of two
     * concurrent additions for one target or one branch exactly one succeeds. A refused or failed addition removes only
     * what it created itself: the target directory or its contents, its administration directory and the branch it
     * created, the latter only while it still points at the start.
     *
     * @param input the input
     * @return {@link GitOutcome#OK} with the new working tree, {@link GitOutcome#ALREADY_EXISTS} if the path is not
     *         empty or claimed by another addition, or the branch to create exists,
     *         {@link GitOutcome#BRANCH_CHECKED_OUT} also for a branch another addition reserved,
     *         {@link GitOutcome#NOT_FOUND} for an unknown branch or an unknown or abbreviated start point,
     *         {@link GitOutcome#AMBIGUOUS}, {@link GitOutcome#IDENTITY_MISMATCH}, {@link GitOutcome#PATH_REFUSED} for a
     *         path outside the main working tree, for a root that is a linked working tree and for a path inside the
     *         git directory or inside another linked working tree, {@link GitOutcome#REFUSED_BY_POLICY} for a branch
     *         name that is no valid branch name and for a refused capability, {@link GitOutcome#NOT_A_REPOSITORY} or
     *         {@link GitOutcome#FAILED}
     */
    GitResult<Worktree> worktreeAdd(WorktreeAddInput input);

    /**
     * Lists the working trees of a repository: the main working tree first, then the linked working trees by name.
     *
     * @param root any working-tree root of the repository
     * @return {@link GitOutcome#OK} with the working trees, {@link GitOutcome#NOT_A_REPOSITORY} or
     *         {@link GitOutcome#FAILED}, also for a repository without a working tree
     */
    GitResult<List<Worktree>> worktreeList(Path root);

    /**
     * Removes a clean, unlocked linked working tree and its administration files; the removal is never forced. The
     * repository at the root must be the registered working tree, and a prunable working tree is removed by its
     * administration files only, so a directory that reuses its path is kept.
     * <p>
     * The caller guarantees exclusive use of the working tree from the call until it returns: the check that the
     * working tree is clean and its deletion cannot be made one atomic step, so a change written in between is deleted
     * with it.
     *
     * @param root     the main working-tree root
     * @param worktree the root of the linked working tree to remove
     * @return {@link GitOutcome#OK} with the removed root, {@link GitOutcome#WORKTREE_NOT_FOUND},
     *         {@link GitOutcome#WORKTREE_DIRTY}, {@link GitOutcome#WORKTREE_LOCKED},
     *         {@link GitOutcome#IDENTITY_MISMATCH} for another repository at the root, {@link GitOutcome#PATH_REFUSED}
     *         for the main working tree, {@link GitOutcome#REFUSED_BY_POLICY}, {@link GitOutcome#NOT_A_REPOSITORY} or
     *         {@link GitOutcome#FAILED}, also for a repository without a working tree
     */
    GitResult<Path> worktreeRemove(Path root, Path worktree);

    /**
     * Stages every change of a working tree (additions, modifications, deletions) and commits it without signing. The
     * root of a registered linked working tree inside it is not staged. A present {@code pre-commit},
     * {@code commit-msg} or {@code post-commit} hook ends the operation before anything is staged or committed. A
     * commit in a linked working tree writes its {@code HEAD} reflog entry to that working tree's own log, as git
     * does. A reflog entry that cannot be written does not fail a created commit: the result is
     * {@link GitOutcome#OK} with the commit, and the omission is logged with the commit id.
     *
     * @param input the input
     * @return {@link GitOutcome#OK} with the commit, {@link GitOutcome#NOTHING_TO_COMMIT},
     *         {@link GitOutcome#REFUSED_BY_POLICY} for a present hook, {@link GitOutcome#NOT_A_REPOSITORY} or
     *         {@link GitOutcome#FAILED}, also for a repository without a working tree
     */
    GitResult<CommitInfo> commit(CommitInput input);

    /**
     * Reports which paths of a working tree differ from its index or its {@code HEAD}, as three sets of
     * repository-relative paths with {@code /} separators; ignored files are not reported. A path may be in more
     * than one set: a file removed from the index but still on disk is both deleted and untracked.
     *
     * @param root the working-tree root, of the main or of a linked working tree
     * @return {@link GitOutcome#OK} with the status, {@link GitOutcome#REFUSED_BY_POLICY} for a capability the
     *         policy refuses, {@link GitOutcome#NOT_A_REPOSITORY} or {@link GitOutcome#FAILED}, also for a repository
     *         without a working tree
     */
    GitResult<WorktreeStatus> worktreeStatus(Path root);

    /**
     * The status of a working tree: the paths that differ from its index or its {@code HEAD}, by kind. Each set is
     * an unmodifiable copy sorted by path; the sets need not be disjoint.
     *
     * @param changed   the paths with content that differs: added to the index, changed in the index, modified on
     *                  disk or in conflict
     * @param untracked the paths on disk that are neither in the index nor ignored
     * @param deleted   the paths removed from the index or missing on disk
     * @since 0.1
     */
    record WorktreeStatus(Set<String> changed, Set<String> untracked, Set<String> deleted) {

        /**
         * Requires each set and stores an unmodifiable copy sorted by path.
         *
         * @param changed   the changed paths
         * @param untracked the untracked paths
         * @param deleted   the deleted paths
         * @throws NullPointerException if a set or one of its paths is {@code null}
         */
        public WorktreeStatus {
            changed = sortedCopy(changed, "changed");
            untracked = sortedCopy(untracked, "untracked");
            deleted = sortedCopy(deleted, "deleted");
        }

        private static Set<String> sortedCopy(Set<String> paths, String component) {
            Objects.requireNonNull(paths, component);
            return Collections.unmodifiableSortedSet(new TreeSet<>(paths));
        }
    }

    /**
     * A working tree of a repository.
     *
     * @param path     the working-tree root
     * @param name     the administrative name; {@code null} for the main working tree
     * @param branch   the checked-out branch; {@code null} when detached
     * @param head     the {@code HEAD} commit id; {@code null} when unborn or not recorded as an id
     * @param main     whether this is the main working tree
     * @param locked   whether the linked working tree is locked
     * @param prunable whether the directory of the linked working tree is gone
     * @since 0.1
     */
    @Builder
    record Worktree(Path path, @Nullable String name, @Nullable String branch, @Nullable String head, boolean main,
    boolean locked, boolean prunable) {

        /**
         * Requires the path.
         *
         * @param path     the working-tree root
         * @param name     the administrative name
         * @param branch   the branch
         * @param head     the head commit id
         * @param main     whether this is the main working tree
         * @param locked   whether the working tree is locked
         * @param prunable whether the working tree is prunable
         * @throws NullPointerException if {@code path} is {@code null}
         */
        public Worktree {
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * Input of {@link #worktreeAdd(WorktreeAddInput)}.
     *
     * @param root         the main working-tree root
     * @param path         the new working-tree root; must lie inside {@code root} and not exist or be empty
     * @param branch       the branch to check out; {@code null} for a detached working tree
     * @param createBranch whether to create {@code branch} at {@code startPoint}
     * @param startPoint   the start point: {@code HEAD}, a reference by its short or full name, or a full commit id;
     *                     for an existing branch it must be known, and only a full commit id is checked against the
     *                     branch
     * @since 0.1
     */
    @Builder
    record WorktreeAddInput(Path root, Path path, @Nullable String branch, boolean createBranch, String startPoint) {

        /**
         * Requires the roots and the start point.
         *
         * @param root         the main working-tree root
         * @param path         the new working-tree root
         * @param branch       the branch
         * @param createBranch whether to create the branch
         * @param startPoint   the start point
         * @throws NullPointerException if {@code root}, {@code path} or {@code startPoint} is {@code null}
         */
        public WorktreeAddInput {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(startPoint, "startPoint");
        }
    }

    /**
     * Input of {@link #commit(CommitInput)}.
     *
     * @param root      the working-tree root
     * @param message   the full commit message, held in memory and never written to a file
     * @param author    the author
     * @param committer the committer
     * @since 0.1
     */
    @Builder
    record CommitInput(Path root, String message, Identity author, Identity committer) {

        /**
         * Requires every value.
         *
         * @param root      the working-tree root
         * @param message   the message
         * @param author    the author
         * @param committer the committer
         * @throws NullPointerException if a value is {@code null}
         */
        public CommitInput {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(committer, "committer");
        }
    }
}
