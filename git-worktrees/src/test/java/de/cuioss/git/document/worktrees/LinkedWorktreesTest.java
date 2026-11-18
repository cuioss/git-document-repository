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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The native administration of git's linked worktrees (JGit has no {@code git worktree} command): a common directory
 * without a {@code worktrees/} subtree has no linked worktree, a created worktree is listed back in git's layout, the
 * administration directory is claimed atomically under the next free suffix, and a deletion removes a tree of files and
 * directories, while a missing root or lock is no error. The common directory is the git directory of a bare
 * repository of the {@code git-testing} fixture.
 */
@EnableGeneratorController
@DisplayName("Linked worktrees")
class LinkedWorktreesTest {

    @TempDir
    Path base;

    private static String name() {
        return Generators.letterStrings(3, 12).next();
    }

    private static Path commonDir(Repository repository) {
        return repository.getDirectory().toPath();
    }

    private Path worktreeRoot(String name) throws Exception {
        return Files.createDirectories(base.resolve(name()).resolve(name));
    }

    @Test
    @DisplayName("a common directory without worktrees/ has no linked worktree")
    void listWithoutWorktrees(@LocalRepository Repository repository) throws Exception {
        assertEquals(List.of(), LinkedWorktrees.list(commonDir(repository)),
                "no worktrees directory lists no worktree");
    }

    @Test
    @DisplayName("a created worktree on a branch is listed back with its branch, locked until unlocked")
    void createAndListBranch(@LocalRepository Repository repository) throws Exception {
        var name = name();
        var branch = name();
        var worktree = worktreeRoot(name);

        var admin = LinkedWorktrees.create(commonDir(repository), worktree, Constants.R_HEADS + branch);
        var whileLocked = LinkedWorktrees.list(commonDir(repository));
        LinkedWorktrees.unlock(admin);
        var listed = LinkedWorktrees.list(commonDir(repository));

        assertAll(
                () -> assertEquals(commonDir(repository).resolve(LinkedWorktrees.WORKTREES).resolve(name), admin,
                        "the administration directory carries the worktree's name"),
                () -> assertEquals("gitdir: " + admin,
                        Files.readString(worktree.resolve(Constants.DOT_GIT)).trim(),
                        "the worktree's .git file points to the administration directory"),
                () -> assertTrue(whileLocked.getFirst().locked(), "the worktree is locked while initializing"),
                () -> assertEquals(1, listed.size(), "one linked worktree is listed"),
                () -> assertEquals(worktree, listed.getFirst().path(), "the listed path is the worktree root"),
                () -> assertEquals(name, listed.getFirst().name(), "the listed name is the administrative name"),
                () -> assertEquals(branch, listed.getFirst().branch(), "the listed branch is the short name"),
                () -> assertNull(listed.getFirst().head(), "a branch worktree records no commit id"),
                () -> assertFalse(listed.getFirst().main(), "a linked worktree is no main worktree"),
                () -> assertFalse(listed.getFirst().locked(), "the unlocked worktree is not locked"),
                () -> assertFalse(listed.getFirst().prunable(), "a present worktree is not prunable"));
    }

    @Test
    @DisplayName("a detached worktree is listed with its commit id and no branch")
    void createAndListDetached(@LocalRepository Repository repository) throws Exception {
        var worktree = worktreeRoot(name());
        var id = ObjectId.fromRaw(Generators.letterStrings(20, 20).next().getBytes(StandardCharsets.US_ASCII)).name();

        LinkedWorktrees.create(commonDir(repository), worktree, id);
        var listed = LinkedWorktrees.list(commonDir(repository)).getFirst();

        assertAll(
                () -> assertEquals(id, listed.head(), "the detached worktree records its commit id"),
                () -> assertNull(listed.branch(), "a detached worktree has no branch"));
    }

    @Test
    @DisplayName("a worktree whose directory is gone is listed as prunable")
    void listPrunable(@LocalRepository Repository repository) throws Exception {
        var worktree = worktreeRoot(name());
        LinkedWorktrees.create(commonDir(repository), worktree, Constants.R_HEADS + name());

        LinkedWorktrees.deleteTree(worktree);

        assertTrue(LinkedWorktrees.list(commonDir(repository)).getFirst().prunable(),
                "a worktree without its directory is prunable");
    }

    @Test
    @DisplayName("a taken administration name moves the claim to the next suffix and leaves the taken one untouched")
    void createClaimsNextSuffix(@LocalRepository Repository repository) throws Exception {
        var name = name();
        var worktrees = commonDir(repository).resolve(LinkedWorktrees.WORKTREES);
        var taken = Files.createDirectories(worktrees.resolve(name));
        var marker = Files.writeString(taken.resolve(name()), name());

        var admin = LinkedWorktrees.create(commonDir(repository), worktreeRoot(name), Constants.R_HEADS + name());

        assertAll(
                () -> assertEquals(worktrees.resolve(name + "1"), admin, "the claim takes the first free suffix"),
                () -> assertEquals(List.of(marker), listFiles(taken), "the taken directory is not written to"));
    }

    @Test
    @DisplayName("concurrent creations with one name each claim an administration directory of their own")
    void concurrentCreationsClaimDistinctDirectories(@LocalRepository Repository repository) throws Exception {
        var name = name();
        int creations = 8;
        var roots = new ArrayList<Path>();
        for (int i = 0; i < creations; i++) {
            roots.add(worktreeRoot(name));
        }
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(creations);
        var admins = new ArrayList<Future<Path>>();
        try {
            for (Path root : roots) {
                admins.add(executor.submit(() -> {
                    start.await();
                    return LinkedWorktrees.create(commonDir(repository), root, Constants.R_HEADS + name());
                }));
            }
            start.countDown();
            var claimed = new HashSet<Path>();
            for (Future<Path> admin : admins) {
                claimed.add(admin.get(30, TimeUnit.SECONDS));
            }
            var listed = LinkedWorktrees.list(commonDir(repository));

            assertAll(
                    () -> assertEquals(creations, claimed.size(), "every creation claimed its own directory"),
                    () -> assertEquals(new HashSet<>(roots),
                            new HashSet<>(listed.stream().map(WorktreeOperations.Worktree::path).toList()),
                            "every administration directory points back to its own worktree"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("a deletion removes the worktree and its administration directory")
    void deleteRemovesWorktreeAndAdmin(@LocalRepository Repository repository) throws Exception {
        var name = name();
        var worktree = worktreeRoot(name);
        var admin = LinkedWorktrees.create(commonDir(repository), worktree, Constants.R_HEADS + name());

        LinkedWorktrees.delete(commonDir(repository), name, worktree);

        assertAll(
                () -> assertFalse(Files.exists(worktree), "the worktree is deleted"),
                () -> assertFalse(Files.exists(admin), "the administration directory is deleted"),
                () -> assertEquals(List.of(), LinkedWorktrees.list(commonDir(repository)), "no worktree is listed"));
    }

    @Test
    @DisplayName("deleting an absent root is no error")
    void deleteAbsentTree() {
        var absent = base.resolve(name());

        assertDoesNotThrow(() -> LinkedWorktrees.deleteTree(absent), "deleting an absent tree does not fail");
    }

    @Test
    @DisplayName("a deletion removes the files and directories of the tree")
    void deleteTree() throws Exception {
        var tree = base.resolve(name());
        var nested = Files.createDirectories(tree.resolve(name()));
        var file = Files.writeString(nested.resolve(name() + ".txt"), Generators.letterStrings(1, 40).next());

        LinkedWorktrees.deleteTree(tree);

        assertAll(
                () -> assertFalse(Files.exists(file), "the nested file is deleted"),
                () -> assertFalse(Files.exists(tree), "the root of the tree is deleted"));
    }

    @Test
    @DisplayName("removing an absent lock is no error")
    void unlockAbsent() throws Exception {
        var admin = Files.createDirectories(base.resolve(name()));

        assertAll(
                () -> assertDoesNotThrow(() -> LinkedWorktrees.unlock(admin), "unlocking without a lock does not fail"),
                () -> assertTrue(Files.isDirectory(admin), "the administration directory remains"));
    }

    private static List<Path> listFiles(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            return entries.toList();
        }
    }
}
