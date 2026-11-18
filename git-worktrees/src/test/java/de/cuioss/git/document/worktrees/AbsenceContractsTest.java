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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.worktrees.WorktreeOperations.Worktree;
import de.cuioss.git.document.worktrees.WorktreeOperations.WorktreeAddInput;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The absent case of the working-tree components: a {@code null} component is an absence the accessor returns
 * unchanged. Every value is generated. The worktree part of the source {@code AbsenceContractsTest} of
 * {@code pm-provider-git}; the {@code git-core} {@code AbsenceContractsTest} holds the repository part.
 */
@EnableGeneratorController
@DisplayName("Absence contracts of the working-tree components")
class AbsenceContractsTest {

    private static String text() {
        return Generators.letterStrings(1, 20).next();
    }

    @Test
    @DisplayName("a failed working-tree result carries no value")
    void failedResultWithoutValue() {
        var result = GitResult.<Worktree>of(GitOutcome.WORKTREE_NOT_FOUND, text());

        assertNull(result.value(), "a failed result carries no working tree");
    }

    @Test
    @DisplayName("the main working tree has no name, a detached one no branch and an unborn one no head")
    void worktreeWithoutNameBranchOrHead() {
        var worktree = new Worktree(Path.of(text()), null, null, null, true, false, false);

        assertAll(
                () -> assertNull(worktree.name(), "the main working tree has no administrative name"),
                () -> assertNull(worktree.branch(), "a detached working tree has no branch"),
                () -> assertNull(worktree.head(), "an unborn working tree has no head"));
    }

    @Test
    @DisplayName("a detached working tree is added without a branch")
    void addInputWithoutBranch() {
        var input = new WorktreeAddInput(Path.of(text()), Path.of(text()), null, false, text());

        assertNull(input.branch(), "a detached working tree has no branch to check out");
    }
}
