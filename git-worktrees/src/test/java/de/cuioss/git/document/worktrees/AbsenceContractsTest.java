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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Objects;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.git.document.worktrees.WorktreeOperations.WorktreeAddInput;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The absent case of the working-tree components as the operations produce it: a {@code null} component is an
 * absence the operation reports, next to a present value for the same component, so an operation that fills or drops
 * a component fails the test. The worktree part of the source {@code AbsenceContractsTest} of {@code pm-provider-git};
 * the {@code git-core} {@code AbsenceContractsTest} holds the repository part. Names are generated.
 */
@EnableGeneratorController
@DisplayName("Absence contracts of the working-tree components")
class AbsenceContractsTest {

    @TempDir
    Path temp;

    private final JGitWorktreeOperations operations = new JGitWorktreeOperations();

    @Test
    @DisplayName("a failed working-tree result read through GitResult carries no value but a detail")
    void failedResultWithoutValue(@LocalRepository Repository source) throws Exception {
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        var unknown = main.root().resolve(JGitWorktreeOperationsTest.name());

        var result = operations.worktreeRemove(main.root(), unknown);

        assertAll(
                () -> assertEquals(GitOutcome.WORKTREE_NOT_FOUND, result.outcome(), "the working tree is not found"),
                () -> assertFalse(result.isOk(), "the result is no success"),
                () -> assertNull(result.value(), "a failed result carries no working tree"),
                () -> assertEquals(unknown.toString(), result.detail(), "the detail names the path"));
    }

    @Test
    @DisplayName("the listed main working tree has no name, a linked one has its name")
    void mainWorktreeWithoutName(@LocalRepository Repository source) throws Exception {
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        var added = operations.worktreeAdd(WorktreeAddInput.builder().root(main.root())
                .path(main.root().resolve(JGitWorktreeOperationsTest.name()))
                .branch(JGitWorktreeOperationsTest.name()).createBranch(true).startPoint("HEAD").build());

        var listed = Objects.requireNonNull(operations.worktreeList(main.root()).value(), "the list");

        assertAll(
                () -> assertEquals(GitOutcome.OK, added.outcome(), "the linked working tree is added"),
                () -> assertTrue(listed.getFirst().main(), "the first entry is the main working tree"),
                () -> assertNull(listed.getFirst().name(), "the main working tree has no administrative name"),
                () -> assertNotNull(listed.get(1).name(), "the linked working tree has its administrative name"));
    }

    @Test
    @DisplayName("a detached addition has no branch, listed or returned, but a head")
    void detachedWithoutBranch(@LocalRepository Repository source) throws Exception {
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        var path = main.root().resolve(JGitWorktreeOperationsTest.name());

        var added = operations.worktreeAdd(WorktreeAddInput.builder().root(main.root()).path(path)
                .createBranch(false).startPoint(main.head().name()).build());
        var listed = Objects.requireNonNull(operations.worktreeList(main.root()).value(), "the list").get(1);

        var worktree = Objects.requireNonNull(added.value(), "the detached working tree is added");
        assertAll(
                () -> assertNull(worktree.branch(), "the returned detached working tree has no branch"),
                () -> assertNull(listed.branch(), "the listed detached working tree has no branch"),
                () -> assertEquals(main.head().name(), listed.head(), "the listed working tree has its head"),
                () -> assertEquals(main.branch(), Objects.requireNonNull(operations.worktreeList(main.root())
                        .value()).getFirst().branch(), "the main working tree has its branch"));
    }

    @Test
    @DisplayName("the main working tree of an unborn repository has no head but its branch")
    void unbornWithoutHead() throws Exception {
        var root = temp.resolve(JGitWorktreeOperationsTest.name());
        Git.init().setDirectory(root.toFile()).call().close();

        var listed = Objects.requireNonNull(operations.worktreeList(root).value(), "the list");

        var main = listed.getFirst();
        assertAll(
                () -> assertEquals(1, listed.size(), "only the main working tree is listed"),
                () -> assertTrue(main.main(), "the entry is the main working tree"),
                () -> assertNull(main.head(), "an unborn working tree has no head"),
                () -> assertNotNull(main.branch(), "the unborn branch is still named"));
    }
}
