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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;


import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.git.document.worktrees.WorktreeOperations.WorktreeAddInput;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The working trees {@link JGitWorktreeOperations#worktreeAdd} creates are read by an installed git: {@code git worktree
 * list --porcelain} lists each with its path, its branch and its {@code HEAD} (GDR-WT-1). The test is skipped through a
 * JUnit assumption when no {@code git} binary is found. Only this test starts a process, never the library. The
 * porcelain keys ({@code worktree}, {@code HEAD}, {@code branch}, {@code detached}) are git's own vocabulary.
 */
@EnableGeneratorController
@DisplayName("git worktree list compatibility")
class GitWorktreeListCompatibilityTest {

    /** The bound of every git process of this test. */
    private static final long TIMEOUT_SECONDS = 30;

    @TempDir
    Path temp;

    @Test
    @DisplayName("GDR-WT-1: git worktree list --porcelain lists the added working trees with path, branch and HEAD")
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

    /** Whether {@code git --version} runs and succeeds. */
    private static boolean gitInstalled() throws InterruptedException {
        try {
            var process = git(List.of("--version")).start();
            process.getInputStream().readAllBytes();
            return process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Runs {@code git worktree list --porcelain} in a working tree and parses its records.
     *
     * @return the attributes of each record by the path of its working tree; a key without value maps to {@code ""}
     */
    private static Map<Path, Map<String, String>> porcelain(Path root) throws IOException, InterruptedException {
        var process = git(List.of("-C", root.toString(), "worktree", "list", "--porcelain")).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "git ends in time");
        assertEquals(0, process.exitValue(), "git lists the working trees");
        var records = new HashMap<Path, Map<String, String>>();
        for (String block : output.split("\n\n")) {
            var attributes = new HashMap<String, String>();
            for (String line : block.strip().split("\n")) {
                int space = line.indexOf(' ');
                attributes.put(space < 0 ? line : line.substring(0, space), space < 0 ? "" : line.substring(space + 1));
            }
            records.put(Path.of(attributes.get("worktree")).toRealPath(), attributes);
        }
        return records;
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
