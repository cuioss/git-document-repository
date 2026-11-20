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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;


import de.cuioss.git.document.core.GitIdentity;
import de.cuioss.git.document.core.GitOutcome;

/**
 * The result of an explicit save (GDR-STORE-3, GDR-STORE-4), which carries the conflicting paths as data.
 * <p>
 * A save result has the shape GDR-GEN-3 prescribes, an {@linkplain #outcome() outcome} and a {@linkplain #detail()
 * detail} for every non-success that carries nothing GDR-NFR-3 forbids, plus the data of its variant: the commit of
 * {@link Saved}, the conflicting paths of {@link Conflicting}. It composes that shape instead of extending
 * {@link StoreResult}, because a {@link StoreResult} that is no success carries no value, and a conflict that names its
 * paths would break that invariant; it is modelled on {@code MergeResult} of {@code git-core}. A caller branches with a
 * pattern-matching {@code switch} over the three variants; {@link #toResult()} gives the plain GDR-GEN-3 shape to a
 * caller that needs no more.
 *
 * @since 0.1
 */
public sealed interface SaveResult permits SaveResult.Saved, SaveResult.Conflicting, SaveResult.NotSaved {

    /**
     * The outcome of the save.
     *
     * @return {@link StoreOutcome#OK} for {@link Saved}, {@link StoreResult.PassedOn} of {@link GitOutcome#CONFLICT}
     *         for {@link Conflicting}, the recorded outcome for {@link NotSaved}; never {@code null}
     */
    StoreResult.Outcome outcome();

    /**
     * The detail of a non-success.
     *
     * @return the detail, present exactly when the save did not succeed
     */
    Optional<String> detail();

    /**
     * Converts the result into the plain GDR-GEN-3 shape.
     *
     * @return {@link StoreOutcome#OK} with the commit id for {@link Saved}; otherwise the outcome and the detail,
     *         without value, so the conflicting paths are not carried over
     */
    default StoreResult<String> toResult() {
        return switch (this) {
            case Saved(String commitId) -> StoreResult.ok(commitId);
            case Conflicting conflicting ->
                new StoreResult<>(conflicting.outcome(), null, conflicting.detail().orElseThrow());
            case NotSaved(StoreResult.Outcome outcome, String reason) -> new StoreResult<>(outcome, null, reason);
        };
    }

    /**
     * A save the remote accepted, or a draft without changes, which needs no commit.
     *
     * @param commitId the full id of the commit the target branch points at after the save: the new commit, or the
     *                 unchanged base for a draft without changes
     * @since 0.1
     */
    record Saved(String commitId) implements SaveResult {

        /**
         * Checks the commit id.
         *
         * @param commitId the full id of the commit
         * @throws NullPointerException     if {@code commitId} is {@code null}
         * @throws IllegalArgumentException if {@code commitId} is no full 40-digit object id
         */
        public Saved {
            Objects.requireNonNull(commitId, "commitId");
            if (!GitIdentity.isFullCommitId(commitId)) {
                throw new IllegalArgumentException("commitId: not a full object id");
            }
        }

        /**
         * The outcome of a save.
         *
         * @return {@link StoreOutcome#OK}
         */
        @Override
        public StoreResult.Outcome outcome() {
            return StoreOutcome.OK;
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
     * A save that conflicts on at least one path (GDR-STORE-4, GDR-STORE-5): nothing was pushed, and the draft
     * reference and the target branch are unchanged.
     * <p>
     * The paths travel as data only: the detail names their number and never a path, so a caller reads the paths from
     * {@link #paths()} and never parses a detail.
     *
     * @param paths the conflicting paths, repository-relative, sorted and distinct; never empty
     * @since 0.1
     */
    record Conflicting(List<String> paths) implements SaveResult {

        /**
         * Sorts the paths, removes duplicates and copies them, so a later change of the caller's list changes nothing.
         *
         * @param paths the conflicting paths, repository-relative
         * @throws NullPointerException     if {@code paths} or one of its elements is {@code null}
         * @throws IllegalArgumentException if {@code paths} is empty or a path is empty or absolute
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
         * The outcome of a conflicting save.
         *
         * @return {@link StoreResult.PassedOn} of {@link GitOutcome#CONFLICT}
         */
        @Override
        public StoreResult.Outcome outcome() {
            return new StoreResult.PassedOn(GitOutcome.CONFLICT);
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
     * Every other save that did not succeed: a malformed input, a missing draft, a rejection after the last attempt,
     * an unreachable remote, invalid credentials, a failure.
     *
     * @param outcome the outcome, never {@link StoreOutcome#OK}
     * @param reason  the detail of the non-success, not blank and free of anything GDR-NFR-3 forbids
     * @since 0.1
     */
    record NotSaved(StoreResult.Outcome outcome, String reason) implements SaveResult {

        /**
         * Checks the outcome and the reason.
         *
         * @param outcome the outcome
         * @param reason  the detail
         * @throws NullPointerException     if an argument is {@code null}
         * @throws IllegalArgumentException if {@code outcome} is {@link StoreOutcome#OK} or {@code reason} is blank
         */
        public NotSaved {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(reason, "reason");
            if (outcome == StoreOutcome.OK) {
                throw new IllegalArgumentException("a save that did not succeed is no OK");
            }
            if (reason.isBlank()) {
                throw new IllegalArgumentException("a save that did not succeed needs a detail");
            }
        }

        /**
         * Creates the non-success of a store result that did not succeed, keeping its outcome and its detail.
         *
         * @param result the non-success result
         * @return the non-success
         * @throws IllegalArgumentException if {@code result} is a success
         */
        static NotSaved of(StoreResult<?> result) {
            if (result.isOk()) {
                throw new IllegalArgumentException("a success is no NotSaved");
            }
            return new NotSaved(result.outcome(), Objects.requireNonNull(result.detail()));
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
