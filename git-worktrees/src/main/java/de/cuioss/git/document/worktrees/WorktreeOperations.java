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
import java.util.List;
import java.util.Objects;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import de.cuioss.git.document.core.RepositoryOperations.Identity;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The operations on the working trees of a repository: adding, listing and removing linked working trees (GDR-WT-1)
 * and the commit from a working tree (GDR-WT-2). One method per operation, typed inputs, and results carrying the
 * closed {@link GitOutcome} of {@code git-core} (GDR-GEN-3).
 * <p>
 * Every repository an implementation opens is built on {@link RefusingFS}, so no operation starts a process; an
 * operation that would run a hook ends {@link GitOutcome#REFUSED_BY_POLICY} with the refused capability in the detail.
 * No result detail carries an author or committer name, an e-mail address or a commit message (GDR-NFR-3).
 *
 * @since 0.1
 */
public interface WorktreeOperations {

    /**
     * Creates a linked working tree in git's own layout, so {@code git worktree list} reads it. The start point and
     * the branch are classified by {@code GitIdentity}: an ambiguous name is refused with its candidates in the detail,
     * and an abbreviated commit id is never resolved.
     *
     * @param input the input
     * @return {@link GitOutcome#OK} with the new working tree, {@link GitOutcome#ALREADY_EXISTS} if the path is not
     *         empty or the branch to create exists, {@link GitOutcome#BRANCH_CHECKED_OUT},
     *         {@link GitOutcome#NOT_FOUND} for an unknown branch or an unknown or abbreviated start point,
     *         {@link GitOutcome#AMBIGUOUS}, {@link GitOutcome#IDENTITY_MISMATCH}, {@link GitOutcome#PATH_REFUSED} for a
     *         path outside the main working tree, {@link GitOutcome#REFUSED_BY_POLICY},
     *         {@link GitOutcome#NOT_A_REPOSITORY} or {@link GitOutcome#FAILED}
     */
    GitResult<Worktree> worktreeAdd(WorktreeAddInput input);

    /**
     * Lists the working trees of a repository: the main working tree first, then the linked working trees by name.
     *
     * @param root any working-tree root of the repository
     * @return {@link GitOutcome#OK} with the working trees, {@link GitOutcome#NOT_A_REPOSITORY} or
     *         {@link GitOutcome#FAILED}
     */
    GitResult<List<Worktree>> worktreeList(Path root);

    /**
     * Removes a clean, unlocked linked working tree and its administration files; the removal is never forced.
     *
     * @param root     the main working-tree root
     * @param worktree the root of the linked working tree to remove
     * @return {@link GitOutcome#OK} with the removed root, {@link GitOutcome#WORKTREE_NOT_FOUND},
     *         {@link GitOutcome#WORKTREE_DIRTY}, {@link GitOutcome#WORKTREE_LOCKED}, {@link GitOutcome#PATH_REFUSED}
     *         for the main working tree, {@link GitOutcome#REFUSED_BY_POLICY}, {@link GitOutcome#NOT_A_REPOSITORY} or
     *         {@link GitOutcome#FAILED}
     */
    GitResult<Path> worktreeRemove(Path root, Path worktree);

    /**
     * Stages every change of a working tree (additions, modifications, deletions) and commits it without signing.
     *
     * @param input the input
     * @return {@link GitOutcome#OK} with the commit, {@link GitOutcome#NOTHING_TO_COMMIT},
     *         {@link GitOutcome#REFUSED_BY_POLICY} for a present hook, {@link GitOutcome#NOT_A_REPOSITORY} or
     *         {@link GitOutcome#FAILED}
     */
    GitResult<CommitInfo> commit(CommitInput input);

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
     *                     for an existing branch only a full commit id is checked against the branch
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
