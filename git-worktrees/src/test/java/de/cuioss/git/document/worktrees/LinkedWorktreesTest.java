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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import de.cuioss.test.generator.TypedGenerator;
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
 * administration directory is claimed atomically under the next free suffix and removed again when its initialization
 * fails, an incomplete entry is skipped and a relative {@code gitdir} resolved, a branch is reserved for one creation at
 * a time, and a deletion removes a tree of files and directories, while a missing root or lock is no error. The common
 * directory is the git directory of a bare repository of the {@code git-testing} fixture.
 */
@EnableGeneratorController
@DisplayName("Linked worktrees")
class LinkedWorktreesTest {

    // unique, so two entries of one test never share a name
    private static final TypedGenerator<String> NAMES = Generators.uniqueValues(Generators.letterStrings(3, 12));

    @TempDir
    Path base;

    private static String name() {
        return NAMES.next();
    }

    private static String content() {
        return Generators.letterStrings(1, 40).next();
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
            // a distinct parent per worker and the shared basename, so no two roots are equal
            roots.add(Files.createDirectories(base.resolve(String.valueOf(i)).resolve(name)));
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
                    () -> assertEquals(creations, listed.size(), "every creation is listed"),
                    () -> assertEquals(new HashSet<>(roots),
                            new HashSet<>(listed.stream().map(WorktreeOperations.Worktree::path).toList()),
                            "every administration directory points back to its own worktree"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("GDR-WT-1: a failed initialization write leaves no administration directory and keeps the worktree")
    void failedCreationRemovesAdministration(@LocalRepository Repository repository) throws Exception {
        var name = name();
        var worktree = worktreeRoot(name);
        // a non-empty directory where the .git file goes, so its write fails after the administration files
        var blocker = Files.createDirectories(worktree.resolve(Constants.DOT_GIT));
        var kept = Files.writeString(blocker.resolve(name()), content());

        var failure = assertThrows(IOException.class,
                () -> LinkedWorktrees.create(commonDir(repository), worktree, Constants.R_HEADS + name()),
                "the write of the .git file fails");

        assertAll(
                () -> assertEquals(0, failure.getSuppressed().length,
                        "the original failure is thrown and the cleanup did not fail"),
                () -> assertFalse(Files.exists(commonDir(repository).resolve(LinkedWorktrees.WORKTREES).resolve(name)),
                        "the claimed administration directory is removed"),
                () -> assertEquals(List.of(blocker), listFiles(worktree),
                        "the worktree directory keeps what it held and gets no partial file"),
                () -> assertTrue(Files.isRegularFile(kept), "the caller's file is kept"),
                () -> assertEquals(List.of(), LinkedWorktrees.list(commonDir(repository)), "no worktree is listed"));
    }

    @Test
    @DisplayName("GDR-WT-1: an entry without gitdir or HEAD is skipped, a complete locked one is listed")
    void incompleteEntriesSkipped(@LocalRepository Repository repository) throws Exception {
        var worktree = worktreeRoot(name());
        var complete = LinkedWorktrees.create(commonDir(repository), worktree, Constants.R_HEADS + name());
        var worktrees = commonDir(repository).resolve(LinkedWorktrees.WORKTREES);
        var withoutGitdir = Files.createDirectories(worktrees.resolve(name()));
        Files.writeString(withoutGitdir.resolve(LinkedWorktrees.LOCKED), content());
        var withoutHead = Files.createDirectories(worktrees.resolve(name()));
        Files.writeString(withoutHead.resolve(Constants.GITDIR_FILE),
                worktreeRoot(name()).resolve(Constants.DOT_GIT).toString());

        var listed = LinkedWorktrees.list(commonDir(repository));

        assertAll(
                () -> assertEquals(1, listed.size(), "only the complete entry is listed"),
                () -> assertEquals(complete.getFileName().toString(), listed.getFirst().name(),
                        "the listed entry is the complete one"),
                () -> assertTrue(listed.getFirst().locked(), "the complete entry is listed while locked"));
    }

    @Test
    @DisplayName("GDR-WT-1: a relative gitdir entry resolves against the administration directory")
    void relativeGitdirResolved(@LocalRepository Repository repository) throws Exception {
        var worktree = worktreeRoot(name());
        var admin = LinkedWorktrees.create(commonDir(repository), worktree, Constants.R_HEADS + name());
        var relative = admin.relativize(worktree.resolve(Constants.DOT_GIT));
        Files.writeString(admin.resolve(Constants.GITDIR_FILE), relative + "\n");

        var listed = LinkedWorktrees.list(commonDir(repository)).getFirst();

        assertAll(
                () -> assertFalse(relative.isAbsolute(), "the entry is relative"),
                () -> assertEquals(worktree, listed.path(), "the relative entry names the worktree root"),
                () -> assertFalse(listed.prunable(), "the resolved worktree is present"));
    }

    @Test
    @DisplayName("GDR-WT-1: a branch is reserved for one creation at a time and free again once released")
    void branchReservation(@LocalRepository Repository repository) throws Exception {
        var branch = Constants.R_HEADS + name();

        var first = LinkedWorktrees.reserveBranch(commonDir(repository), branch);
        var second = LinkedWorktrees.reserveBranch(commonDir(repository), branch);
        LinkedWorktrees.release(first.orElseThrow());
        var afterRelease = LinkedWorktrees.reserveBranch(commonDir(repository), branch);

        assertAll(
                () -> assertTrue(first.isPresent(), "the first reservation succeeds"),
                () -> assertTrue(second.isEmpty(), "a second reservation of the same branch is refused"),
                () -> assertTrue(afterRelease.isPresent(), "the released branch can be reserved again"));
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
