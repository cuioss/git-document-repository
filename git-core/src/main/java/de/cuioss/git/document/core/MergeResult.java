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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The result of an in-core merge (GDR-CORE-6), which carries its content as data.
 * <p>
 * A merge result has the shape GDR-GEN-3 prescribes, an {@linkplain #outcome() outcome} from the closed
 * {@link GitOutcome} list and a {@linkplain #detail() detail} for every non-success that carries nothing GDR-NFR-3
 * forbids, plus the data of its variant: the merged tree of {@link Merged}, the conflicting paths of
 * {@link Conflicting}. It composes that shape instead of extending {@link GitResult}, because a {@link GitResult} that
 * is no success carries no value, and a conflict that names its paths would break that invariant. A caller branches
 * with a pattern-matching {@code switch} over the three variants; {@link #toResult()} gives the plain GDR-GEN-3 shape
 * to a caller that needs no more.
 *
 * @since 0.1
 */
public sealed interface MergeResult permits MergeResult.Merged, MergeResult.Conflicting, MergeResult.NotMerged {

    /**
     * The outcome of the merge.
     *
     * @return {@link GitOutcome#OK} for {@link Merged}, {@link GitOutcome#CONFLICT} for {@link Conflicting}, the
     *         recorded outcome for {@link NotMerged}; never {@code null}
     */
    GitOutcome outcome();

    /**
     * The detail of a non-success.
     *
     * @return the detail, present exactly when the merge did not succeed
     */
    Optional<String> detail();

    /**
     * Converts the result into the plain GDR-GEN-3 shape.
     *
     * @return {@link GitOutcome#OK} with the merged tree's id for {@link Merged}; otherwise the outcome and the detail,
     *         without value, so the conflicting paths are not carried over
     */
    default GitResult<String> toResult() {
        return switch (this) {
            case Merged(String treeId) -> GitResult.ok(treeId);
            case Conflicting conflicting -> GitResult.of(conflicting.outcome(), conflicting.detail().orElseThrow());
            case NotMerged(GitOutcome outcome, String reason) -> GitResult.of(outcome, reason);
        };
    }

    /**
     * A clean merge: the merged tree was written to the object database; no commit was written and no reference moved.
     *
     * @param treeId the full id of the merged tree
     * @since 0.1
     */
    record Merged(String treeId) implements MergeResult {

        /**
         * Checks the tree id.
         *
         * @param treeId the full id of the merged tree
         * @throws NullPointerException     if {@code treeId} is {@code null}
         * @throws IllegalArgumentException if {@code treeId} is no full 40-digit object id
         */
        public Merged {
            Objects.requireNonNull(treeId, "treeId");
            if (!GitIdentity.isFullCommitId(treeId)) {
                throw new IllegalArgumentException("treeId: not a full object id");
            }
        }

        /**
         * The outcome of a clean merge.
         *
         * @return {@link GitOutcome#OK}
         */
        @Override
        public GitOutcome outcome() {
            return GitOutcome.OK;
        }

        /**
         * A success carries no detail.
         *
         * @return an empty detail
         */
        @Override
        public Optional<String> detail() {
            return Optional.empty();
        }
    }

    /**
     * A merge that conflicts on at least one path; nothing of the merge is usable as a tree.
     * <p>
     * The paths travel as data only: the detail names their number and never a path, so a caller reads the paths from
     * {@link #paths()} and never parses a detail.
     *
     * @param paths the conflicting paths, repository-relative, sorted and distinct; never empty
     * @since 0.1
     */
    record Conflicting(List<String> paths) implements MergeResult {

        /**
         * Sorts the paths, removes duplicates and copies them, so a later change of the caller's list changes nothing.
         *
         * @param paths the conflicting paths, repository-relative
         * @throws NullPointerException     if {@code paths} or one of its elements is {@code null}
         * @throws IllegalArgumentException if {@code paths} is empty or a path is empty or absolute; a path of
         *                                  whitespace only is accepted, because a file of that name is valid in a Git
         *                                  repository (JGit 7.8.0 {@code ObjectChecker} rejects an empty name only,
         *                                  besides {@code .}, {@code ..} and {@code .git})
         */
        public Conflicting {
            Objects.requireNonNull(paths, "paths");
            var sorted = new TreeSet<String>();
            for (String path : paths) {
                Objects.requireNonNull(path, "paths");
                if (path.isEmpty() || path.startsWith("/")) {
                    throw new IllegalArgumentException("paths: not a repository-relative path");
                }
                sorted.add(path);
            }
            if (sorted.isEmpty()) {
                throw new IllegalArgumentException("paths: a conflict names at least one path");
            }
            paths = List.copyOf(sorted);
        }

        /**
         * The outcome of a conflicting merge.
         *
         * @return {@link GitOutcome#CONFLICT}
         */
        @Override
        public GitOutcome outcome() {
            return GitOutcome.CONFLICT;
        }

        /**
         * The detail of the conflict, naming the number of conflicting paths only.
         *
         * @return {@code "N conflicting paths"}
         */
        @Override
        public Optional<String> detail() {
            return Optional.of(paths.size() + " conflicting paths");
        }
    }

    /**
     * Every other merge that did not succeed: a commit that is not found, more than one merge base, a failure.
     *
     * @param outcome the outcome, never {@link GitOutcome#OK}
     * @param reason  the detail of the non-success, not blank and free of anything GDR-NFR-3 forbids
     * @since 0.1
     */
    record NotMerged(GitOutcome outcome, String reason) implements MergeResult {

        /**
         * Checks the outcome and the reason.
         *
         * @param outcome the outcome
         * @param reason  the detail
         * @throws NullPointerException     if an argument is {@code null}
         * @throws IllegalArgumentException if {@code outcome} is {@link GitOutcome#OK} or {@code reason} is blank
         */
        public NotMerged {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            if (outcome == GitOutcome.OK) {
                throw new IllegalArgumentException("a merge that did not succeed is no OK");
            }
            if (reason.isBlank()) {
                throw new IllegalArgumentException("a merge that did not succeed needs a detail");
            }
        }

        /**
         * The detail of the non-success.
         *
         * @return the reason
         */
        @Override
        public Optional<String> detail() {
            return Optional.of(reason);
        }
    }
}
