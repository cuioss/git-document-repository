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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;


import de.cuioss.git.document.core.RepositoryOperations.Identity;
import de.cuioss.git.document.worktrees.WorktreeOperations.CommitInput;
import de.cuioss.git.document.worktrees.WorktreeOperations.Worktree;
import de.cuioss.git.document.worktrees.WorktreeOperations.WorktreeAddInput;
import de.cuioss.git.document.worktrees.WorktreeOperations.WorktreeStatus;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The builders of the value records of {@link WorktreeOperations} build the value the canonical constructor builds,
 * and the constructors refuse a missing required component, also through the builder. Every value is generated; the
 * component names the refusal tests assert are contract vocabulary rather than test data.
 */
@EnableGeneratorController
@DisplayName("WorktreeOperations value records")
class WorktreeOperationsRecordsTest {

    private static String text() {
        return Generators.letterStrings(1, 20).next();
    }

    private static Path path() {
        return Path.of(text());
    }

    private static Identity identity() {
        return new Identity(text(), text());
    }

    @Test
    @DisplayName("the worktree builder builds the constructor value")
    void worktreeBuilderEqualsConstructor() {
        var path = path();
        var name = text();
        var branch = text();
        var head = text();
        var main = Generators.booleans().next();
        var locked = Generators.booleans().next();
        var prunable = Generators.booleans().next();

        var built = Worktree.builder()
                .path(path)
                .name(name)
                .branch(branch)
                .head(head)
                .main(main)
                .locked(locked)
                .prunable(prunable)
                .build();
        var constructed = new Worktree(path, name, branch, head, main, locked, prunable);

        assertEquals(constructed, built, "the builder builds the same worktree value");
    }

    @Test
    @DisplayName("the worktree of a main working tree carries no name, branch or head")
    void worktreeAcceptsAbsentOptionalComponents() {
        var path = path();

        var worktree = Worktree.builder().path(path).main(true).build();

        assertAll(
                () -> assertEquals(path, worktree.path(), "the path is kept"),
                () -> assertNull(worktree.name(), "the main working tree has no administrative name"),
                () -> assertNull(worktree.branch(), "a detached working tree has no branch"),
                () -> assertNull(worktree.head(), "an unborn working tree has no head"));
    }

    @Test
    @DisplayName("the worktree builder refuses a missing path")
    void worktreeBuilderRefusesMissingPath() {
        var builder = Worktree.builder().name(text());

        var refusal = assertThrows(NullPointerException.class, builder::build, "the builder names the missing path");

        assertEquals("path", refusal.getMessage(), "the refusal names the missing path");
    }

    @Test
    @DisplayName("the worktree add builder builds the constructor value")
    void worktreeAddInputBuilderEqualsConstructor() {
        var root = path();
        var path = path();
        var branch = text();
        var createBranch = Generators.booleans().next();
        var startPoint = text();

        var built = WorktreeAddInput.builder()
                .root(root)
                .path(path)
                .branch(branch)
                .createBranch(createBranch)
                .startPoint(startPoint)
                .build();
        var constructed = new WorktreeAddInput(root, path, branch, createBranch, startPoint);

        assertEquals(constructed, built, "the builder builds the same add input");
    }

    @Test
    @DisplayName("the worktree add builder refuses a missing root")
    void worktreeAddInputBuilderRefusesMissingRoot() {
        var builder = WorktreeAddInput.builder()
                .path(path())
                .createBranch(false)
                .startPoint(text());

        var refusal = assertThrows(NullPointerException.class, builder::build, "the builder names the missing root");

        assertEquals("root", refusal.getMessage(), "the refusal names the missing root");
    }

    @Test
    @DisplayName("the worktree add input refuses a missing path and a missing start point")
    void worktreeAddInputRefusesMissingPathAndStartPoint() {
        var root = path();
        var path = path();
        var startPoint = text();

        var missingPath = assertThrows(NullPointerException.class,
                () -> new WorktreeAddInput(root, null, null, false, startPoint), "a missing path is refused");
        var missingStartPoint = assertThrows(NullPointerException.class,
                () -> new WorktreeAddInput(root, path, null, false, null), "a missing start point is refused");

        assertAll(
                () -> assertEquals("path", missingPath.getMessage(), "the refusal names the missing path"),
                () -> assertEquals("startPoint", missingStartPoint.getMessage(),
                        "the refusal names the missing start point"));
    }

    @Test
    @DisplayName("GDR-WT-3: the working-tree status refuses every missing set and every missing path")
    void worktreeStatusRefusesMissingSets() {
        var paths = Set.of(text());
        var withNull = new HashSet<String>();
        withNull.add(null);

        var missingChanged = assertThrows(NullPointerException.class,
                () -> new WorktreeStatus(null, paths, paths), "missing changed paths are refused");
        var missingUntracked = assertThrows(NullPointerException.class,
                () -> new WorktreeStatus(paths, null, paths), "missing untracked paths are refused");
        var missingDeleted = assertThrows(NullPointerException.class,
                () -> new WorktreeStatus(paths, paths, null), "missing deleted paths are refused");

        assertAll(
                () -> assertEquals("changed", missingChanged.getMessage(), "the refusal names the changed set"),
                () -> assertEquals("untracked", missingUntracked.getMessage(), "the refusal names the untracked set"),
                () -> assertEquals("deleted", missingDeleted.getMessage(), "the refusal names the deleted set"),
                () -> assertThrows(NullPointerException.class, () -> new WorktreeStatus(withNull, paths, paths),
                        "a missing path is refused"));
    }

    @Test
    @DisplayName("GDR-WT-3: the working-tree status keeps a sorted copy that neither the caller nor a reader changes")
    void worktreeStatusCopiesSortedAndUnmodifiable() {
        var changed = new HashSet<String>();
        while (changed.size() < 3) {
            changed.add(text());
        }
        var expected = List.copyOf(new TreeSet<>(changed));

        var status = new WorktreeStatus(changed, new HashSet<>(changed), new HashSet<>(changed));
        changed.add(text() + text());

        assertAll(
                () -> assertEquals(expected, List.copyOf(status.changed()), "the copy is sorted and unchanged"),
                () -> assertEquals(expected, List.copyOf(status.untracked()), "the untracked copy is sorted"),
                () -> assertEquals(expected, List.copyOf(status.deleted()), "the deleted copy is sorted"),
                () -> assertThrows(UnsupportedOperationException.class, () -> status.changed().add(text()),
                        "the changed paths cannot be modified"),
                () -> assertThrows(UnsupportedOperationException.class, () -> status.untracked().clear(),
                        "the untracked paths cannot be modified"),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> status.deleted().remove(expected.getFirst()), "the deleted paths cannot be modified"));
    }

    @Test
    @DisplayName("the commit input builder builds the constructor value")
    void commitInputBuilderEqualsConstructor() {
        var root = path();
        var message = text();
        var author = identity();
        var committer = identity();

        var built = CommitInput.builder()
                .root(root)
                .message(message)
                .author(author)
                .committer(committer)
                .build();
        var constructed = new CommitInput(root, message, author, committer);

        assertEquals(constructed, built, "the builder builds the same commit input");
    }

    @Test
    @DisplayName("the commit input refuses every missing component")
    void commitInputRefusesMissingComponents() {
        var root = path();
        var message = text();
        var author = identity();
        var committer = identity();

        var missingRoot = assertThrows(NullPointerException.class,
                () -> new CommitInput(null, message, author, committer), "a missing root is refused");
        var missingMessage = assertThrows(NullPointerException.class,
                () -> new CommitInput(root, null, author, committer), "a missing message is refused");
        var missingAuthor = assertThrows(NullPointerException.class,
                () -> new CommitInput(root, message, null, committer), "a missing author is refused");
        var missingCommitter = assertThrows(NullPointerException.class,
                () -> new CommitInput(root, message, author, null), "a missing committer is refused");

        assertAll(
                () -> assertEquals("root", missingRoot.getMessage(), "the refusal names the missing root"),
                () -> assertEquals("message", missingMessage.getMessage(), "the refusal names the missing message"),
                () -> assertEquals("author", missingAuthor.getMessage(), "the refusal names the missing author"),
                () -> assertEquals("committer", missingCommitter.getMessage(),
                        "the refusal names the missing committer"));
    }
}
