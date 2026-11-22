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


import de.cuioss.git.document.core.TreeEdit;
import org.jspecify.annotations.Nullable;

/**
 * The optional per-attempt step of an explicit save (GDR-STORE-3): a caller-supplied function that adds edits to, or
 * refuses, every attempt of the save.
 * <p>
 * The store calls the step on every attempt of a save, the first and each re-application onto a new tip, after the
 * conflict check and before the save commit is written. The step sees the attempt through a read-only
 * {@link Attempt}: the tip the attempt commits on and the tree the draft's changes produce on it. It returns
 * {@link Edits}, which are written into the same single save commit, or a {@link Refusal}, which ends the save with
 * {@link StoreOutcome#REFUSED}: nothing is pushed, and draft and target branch stay unchanged. A step that throws a
 * {@link RuntimeException} ends the save with the passed-on {@link de.cuioss.git.document.core.GitOutcome#FAILED},
 * whose detail names the target and the class of the exception only, and is not retried. A draft without changes
 * writes no commit, so its save calls no step.
 * <p>
 * The step runs under the locks of the save; it must not call the writer it belongs to.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface SaveStep {

    /**
     * Decides about one attempt of a save.
     *
     * @param attempt the read-only view of the attempt, valid only during this call
     * @return {@link Edits} to add to the save commit, possibly none, or a {@link Refusal}; never {@code null}
     */
    Outcome apply(Attempt attempt);

    /**
     * The read-only view of one attempt of a save (GDR-STORE-3): the tip the attempt commits on and the tree the
     * draft's changes produce on that tip.
     * <p>
     * The view is the tip's tree overlaid with the draft's edits; it writes no object and gives the step no repository
     * and no credential. Every path is repository-relative, its segments separated by {@code /}, without a leading or
     * trailing {@code /}. Contents are copies: changing a returned array changes nothing the save writes.
     *
     * @since 0.1
     */
    interface Attempt {

        /**
         * The full id of the tip the attempt commits on, the only parent of the save commit.
         *
         * @return the full commit id, or {@code null} when the save commit is a root commit
         */
        @Nullable
        String tip();

        /**
         * The content of a file on the tip.
         *
         * @param path the repository-relative path
         * @return the content, or empty when the tip is absent or holds no file at the path
         * @throws NullPointerException if {@code path} is {@code null}
         */
        Optional<byte[]> tipContent(String path);

        /**
         * The content of a file in the tree the draft's changes produce on the tip.
         *
         * @param path the repository-relative path
         * @return the content, or empty when the produced tree holds no file at the path
         * @throws NullPointerException if {@code path} is {@code null}
         */
        Optional<byte[]> content(String path);

        /**
         * The paths of every file below a directory of the tree the draft's changes produce on the tip, at any depth.
         *
         * @param directory the repository-relative directory; the empty string for the whole tree
         * @return the file paths, sorted and distinct, possibly none
         * @throws NullPointerException if {@code directory} is {@code null}
         */
        List<String> paths(String directory);
    }

    /**
     * The decision of a step about one attempt.
     *
     * @since 0.1
     */
    sealed interface Outcome permits Edits, Refusal {
    }

    /**
     * Edits to add to the save commit of the attempt. They are applied after the draft's edits; an edit on a path the
     * draft also changed replaces the draft's edit of that path.
     *
     * @param edits the edits, possibly none
     * @since 0.1
     */
    record Edits(List<TreeEdit> edits) implements Outcome {

        /**
         * Copies the edits.
         *
         * @param edits the edits
         * @throws NullPointerException if {@code edits} or one of its elements is {@code null}
         */
        public Edits {
            edits = List.copyOf(Objects.requireNonNull(edits, "edits"));
        }
    }

    /**
     * The refusal of the attempt: the save ends with {@link StoreOutcome#REFUSED} and a fixed detail naming the target
     * only. A refusal carries no text of the caller, so nothing GDR-NFR-3 forbids can reach a detail or a log.
     *
     * @since 0.1
     */
    record Refusal() implements Outcome {
    }
}
