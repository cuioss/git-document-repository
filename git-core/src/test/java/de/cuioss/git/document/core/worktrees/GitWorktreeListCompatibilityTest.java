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
package de.cuioss.git.document.core.worktrees;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.worktrees.WorktreeOperations.Worktree;
import de.cuioss.git.document.core.worktrees.WorktreeOperations.WorktreeAddInput;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The working trees {@link JGitWorktreeOperations#worktreeAdd} creates are read by an installed git: {@code git worktree
 * list --porcelain} lists each with its path, its branch and its {@code HEAD}, a locked one {@code locked} and one
 * whose directory is gone {@code prunable}, and {@code git worktree remove} removes one (GDR-CORE-12). The tests are
 * skipped through a JUnit assumption when no {@code git} binary is found. Only this test starts a process, never the
 * library. The porcelain keys ({@code worktree}, {@code HEAD}, {@code branch}, {@code detached}, {@code locked},
 * {@code prunable}) are git's own vocabulary.
 */
@EnableGeneratorController
@DisplayName("git worktree list compatibility")
class GitWorktreeListCompatibilityTest {

    /** The bound of every git process of this test. */
    private static final long TIMEOUT_SECONDS = 30;

    @TempDir
    Path temp;

    @Test
    @DisplayName("GDR-CORE-12: git worktree list --porcelain lists the added working trees with path, branch and HEAD")
    void installedGitListsAddedWorktrees(@LocalRepository Repository source) throws Exception {
        assumeTrue(gitInstalled(), "a git binary is installed");
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        var operations = new JGitWorktreeOperations();
        var branch = JGitWorktreeOperationsTest.name();
        var onBranch = main.root().resolve(JGitWorktreeOperationsTest.name());
        var detached = main.root().resolve(JGitWorktreeOperationsTest.name());
        var addedOnBranch = operations.worktreeAdd(WorktreeAddInput.builder().root(main.root()).path(onBranch)
                .branch(branch).createBranch(true).startPoint(Constants.HEAD).build());
        var addedDetached = operations.worktreeAdd(WorktreeAddInput.builder().root(main.root()).path(detached)
                .createBranch(false).startPoint(main.head().name()).build());
        assertTrue(addedOnBranch.isOk() && addedDetached.isOk(), "both working trees are added");

        var listed = porcelain(main.root());

        var branchRecord = Objects.requireNonNull(listed.get(onBranch), "git lists the working tree on the branch");
        var detachedRecord = Objects.requireNonNull(listed.get(detached), "git lists the detached working tree");
        assertAll(
                () -> assertEquals(3, listed.size(), "git lists the main and both linked working trees"),
                () -> assertEquals(main.head().name(), branchRecord.get("HEAD"), "the HEAD of the branch tree"),
                () -> assertEquals(Constants.R_HEADS + branch, branchRecord.get("branch"), "the branch of the tree"),
                () -> assertEquals(main.head().name(), detachedRecord.get("HEAD"), "the HEAD of the detached tree"),
                () -> assertTrue(detachedRecord.containsKey("detached"), "the detached tree is listed detached"));
    }

    @Test
    @DisplayName("GDR-CORE-12: git worktree list --porcelain lists a locked tree locked and one whose directory is gone "
            + "prunable")
    void installedGitListsLockedAndPrunable(@LocalRepository Repository source) throws Exception {
        assumeTrue(gitInstalled(), "a git binary is installed");
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        var operations = new JGitWorktreeOperations();
        var locked = added(operations, main);
        var gone = added(operations, main);
        Files.writeString(main.admin(Objects.requireNonNull(locked.name())).resolve(LinkedWorktrees.LOCKED),
                JGitWorktreeOperationsTest.name());
        LinkedWorktrees.deleteTree(gone.path());

        var listed = porcelain(main.root());

        var lockedRecord = Objects.requireNonNull(listed.get(locked.path()), "git lists the locked working tree");
        var goneRecord = Objects.requireNonNull(listed.get(gone.path()), "git lists the working tree that is gone");
        assertAll(
                () -> assertEquals(3, listed.size(), "git lists the main and both linked working trees"),
                () -> assertTrue(lockedRecord.containsKey("locked"), "git reads the locked file of the library"),
                () -> assertFalse(lockedRecord.containsKey("prunable"), "the locked working tree is present"),
                () -> assertTrue(goneRecord.containsKey("prunable"), "git finds the gone working tree prunable"),
                () -> assertFalse(goneRecord.containsKey("locked"), "the gone working tree is not locked"));
    }

    @Test
    @DisplayName("GDR-CORE-12: git worktree remove removes a working tree the library added")
    void installedGitRemovesAddedWorktree(@LocalRepository Repository source) throws Exception {
        assumeTrue(gitInstalled(), "a git binary is installed");
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        var operations = new JGitWorktreeOperations();
        var worktree = added(operations, main);

        var removed = run(List.of("-C", main.root().toString(), "worktree", "remove", worktree.path().toString()));

        var listed = Objects.requireNonNull(operations.worktreeList(main.root()).value(), "the library lists");
        assertAll(
                () -> assertTrue(removed.isPresent(), "git ends in time"),
                () -> assertEquals(0, removed.orElseThrow().exitCode(), "git removes the working tree"),
                () -> assertFalse(Files.exists(worktree.path()), "the working-tree directory is deleted"),
                () -> assertFalse(Files.exists(main.admin(Objects.requireNonNull(worktree.name()))),
                        "the administration directory is deleted"),
                () -> assertEquals(List.of(main.root()), listed.stream().map(Worktree::path).toList(),
                        "the library lists only the main working tree"));
    }

    @Test
    @DisplayName("GDR-CORE-12: the branch-name guard refuses every name git refuses as a branch or reads as another "
            + "name")
    void branchNameGuardAgreesWithGit(@LocalRepository Repository source) throws Exception {
        assumeTrue(gitInstalled(), "a git binary is installed");
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        var operations = new JGitWorktreeOperations();
        var guarded = JGitWorktreeOperationsTest.guardedBranchNames();
        var gitRefused = guarded.subList(0, 3);
        var at = guarded.get(3);
        var beyondGit = guarded.get(4);
        var valid = JGitWorktreeOperationsTest.name();

        var checks = new ArrayList<Executable>();
        for (String branch : gitRefused) {
            var readAsGiven = gitReadsBranchAsGiven(main.root(), branch);
            var released = operations.releaseBranchReservation(main.root(), branch);
            checks.add(() -> assertFalse(readAsGiven, "git refuses " + branch));
            checks.add(() -> assertEquals(GitOutcome.REFUSED_BY_POLICY, released.outcome(),
                    "the guard refuses " + branch));
        }
        // git accepts @ as a branch name, but reads @ as HEAD, here the checked-out branch (gitrevisions(7))
        var atRead = run(List.of("-C", main.root().toString(), "rev-parse", "--symbolic-full-name", at));
        var atReleased = operations.releaseBranchReservation(main.root(), at);
        checks.add(() -> assertEquals(Optional.of(Constants.R_HEADS + main.branch()),
                atRead.filter(r -> r.exitCode() == 0).map(r -> r.output().strip()),
                "git reads @ as the checked-out branch, not as a branch named @"));
        checks.add(() -> assertEquals(GitOutcome.REFUSED_BY_POLICY, atReleased.outcome(), "the guard refuses @"));
        var beyondReadAsGiven = gitReadsBranchAsGiven(main.root(), beyondGit);
        var beyondReleased = operations.releaseBranchReservation(main.root(), beyondGit);
        var validReadAsGiven = gitReadsBranchAsGiven(main.root(), valid);
        var validReleased = operations.releaseBranchReservation(main.root(), valid);
        checks.add(() -> assertTrue(beyondReadAsGiven, "git accepts a name starting refs/ as given"));
        checks.add(() -> assertEquals(GitOutcome.REFUSED_BY_POLICY, beyondReleased.outcome(),
                "the guard refuses a name starting refs/ beyond git"));
        checks.add(() -> assertTrue(validReadAsGiven, "git accepts a plain name as given"));
        checks.add(() -> assertEquals(GitOutcome.NOT_FOUND, validReleased.outcome(),
                "the guard lets a plain name pass to the reservation lookup"));
        assertAll(checks);
    }

    /**
     * Whether {@code git check-ref-format --branch} in the working tree accepts the name and prints it unchanged; a
     * refused or expanded name, or a run that does not end in time, is not read as given.
     */
    private boolean gitReadsBranchAsGiven(Path root, String branch) throws IOException, InterruptedException {
        var result = run(List.of("-C", root.toString(), "check-ref-format", "--branch", branch));
        return result.filter(r -> r.exitCode() == 0 && branch.equals(r.output().strip())).isPresent();
    }

    /** Adds a linked working tree on a new branch at {@code HEAD} through the library and fails unless it is added. */
    private static Worktree added(JGitWorktreeOperations operations, JGitWorktreeOperationsTest.MainTree main) {
        var result = operations.worktreeAdd(WorktreeAddInput.builder().root(main.root())
                .path(main.root().resolve(JGitWorktreeOperationsTest.name()))
                .branch(JGitWorktreeOperationsTest.name()).createBranch(true).startPoint(Constants.HEAD).build());
        return Objects.requireNonNull(result.value(), "the working tree is added: " + result.detail());
    }

    /** Whether {@code git --version} runs and succeeds in time. */
    private boolean gitInstalled() throws InterruptedException {
        try {
            return run(List.of("--version")).filter(result -> result.exitCode() == 0).isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Runs {@code git worktree list --porcelain} in a working tree and parses its records.
     *
     * @return the attributes of each record by the path of its working tree; a key without value maps to {@code ""}
     */
    private Map<Path, Map<String, String>> porcelain(Path root) throws IOException, InterruptedException {
        var result = run(List.of("-C", root.toString(), "worktree", "list", "--porcelain"));
        assertTrue(result.isPresent(), "git ends in time");
        assertEquals(0, result.get().exitCode(), "git lists the working trees");
        var output = result.get().output();
        var records = new HashMap<Path, Map<String, String>>();
        for (String block : output.split("\n\n")) {
            var attributes = new HashMap<String, String>();
            for (String line : block.strip().split("\n")) {
                int space = line.indexOf(' ');
                attributes.put(space < 0 ? line : line.substring(0, space), space < 0 ? "" : line.substring(space + 1));
            }
            // a prunable working tree's directory is gone, so only an existing path is resolved to its real path
            var path = Path.of(attributes.get("worktree"));
            records.put(Files.exists(path) ? path.toRealPath() : path.toAbsolutePath().normalize(), attributes);
        }
        return records;
    }

    /** The exit code and the standard output of a git process that ended in time. */
    private record GitRun(int exitCode, String output) {
    }

    /**
     * Runs git bounded by {@link #TIMEOUT_SECONDS}: its output goes to a file, so the deadline applies to the whole
     * run and a full pipe cannot block it, and a process that does not end in time or whose wait is interrupted is
     * terminated.
     *
     * @return the run, or empty if git did not end in time
     */
    private Optional<GitRun> run(List<String> arguments) throws IOException, InterruptedException {
        var output = Files.createTempFile(temp, "git", ".out");
        var process = git(arguments).redirectOutput(output.toFile()).start();
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return Optional.empty();
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return Optional.of(new GitRun(process.exitValue(), Files.readString(output)));
    }

    /** A git process isolated from the {@code GIT_*} environment of the build, with its errors discarded. */
    private static ProcessBuilder git(List<String> arguments) {
        var command = new ArrayList<String>();
        command.add("git");
        command.addAll(arguments);
        var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().keySet().removeIf(key -> key.startsWith("GIT_"));
        return builder;
    }
}
