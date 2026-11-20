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
/**
 * Hardened Git repositories, remote policy, identity, results, transport, cache repository, in-core writing,
 * compare-and-swap, in-core merge (with its {@code MergeResult}), reading (a file of a cached commit, and with
 * {@code RepositoryReader} the snapshot of a commit, the history of a path and the paths changed between two commits)
 * and notes ({@code GitNotes}) on JGit, and in the package {@code de.cuioss.git.document.core.worktrees} linked
 * working trees, commit from a working tree and working-tree status.
 */
module de.cuioss.git.document.core {
    exports de.cuioss.git.document.core;
    exports de.cuioss.git.document.core.worktrees;

    requires transitive org.eclipse.jgit;
    requires de.cuioss.java.tools;
    requires static lombok;
    requires static org.jspecify;
}
