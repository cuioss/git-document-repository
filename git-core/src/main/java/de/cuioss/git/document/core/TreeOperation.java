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


import org.jspecify.annotations.Nullable;

/**
 * The change a compare-and-swap update applies to a reference's tree (GDR-CORE-5): from the tree the reference
 * currently points at, it derives the edits of the next commit.
 * <p>
 * {@link ReferenceUpdater#compareAndSwap(org.eclipse.jgit.lib.Repository, String, CommitMetadata, TreeOperation, int)}
 * calls the operation once per attempt, each time with the tree of the commit it has just read; after a lost race it is
 * called again with the tree the concurrent writer left. The operation must therefore be free of side effects on the
 * repository and must derive its edits from the tree it is given, not from a tree it read earlier.
 * <p>
 * The operation is the caller's code and runs on the caller's contract: an exception it throws is mapped to no
 * outcome and propagates out of {@code compareAndSwap} unchanged, and so does the {@link NullPointerException} of a
 * {@code null} list or a {@code null} element it returns. The reference is not touched in either case; a commit
 * written by an earlier attempt stays in the object database unreferenced.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface TreeOperation {

    /**
     * Derives the edits of the next commit from the reference's current tree.
     *
     * @param currentTreeId the full id of the tree of the reference's commit; {@code null} when the reference does not
     *                      exist, so the commit becomes a root commit
     * @return the edits to apply; empty to keep the tree unchanged; never {@code null} and without {@code null}
     *         elements
     */
    List<TreeEdit> apply(@Nullable String currentTreeId);
}
