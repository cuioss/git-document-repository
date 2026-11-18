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

import static de.cuioss.git.document.worktrees.JGitWorktreeOperationsTest.name;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.git.document.worktrees.WorktreeOperations.WorktreeAddInput;
import de.cuioss.git.document.worktrees.WorktreeOperations.WorktreeStatus;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The status of a working tree mapped from JGit's {@code Status} (JGit 7.8.0, GDR-WT-3): one scenario per JGit
 * category on a main working tree cloned from a {@code git-testing} fixture repository, each asserting the exact
 * three sets, and the union of the three sets against the path union of the seven JGit categories the source
 * {@code worktreeSha} reads. The working trees are prepared with JGit's porcelain API in the test, never through the
 * operations under test. Names and contents are generated; {@code .git/info/exclude} is git's own layout.
 */
@EnableGeneratorController
@DisplayName("WorktreeStatus")
class WorktreeStatusTest {

    @TempDir
    Path temp;

    private JGitWorktreeOperations operations;

    @BeforeEach
    void createOperations() {
        operations = new JGitWorktreeOperations();
    }

    private static String content() {
        return Generators.letterStrings(1, 40).next();
    }

    private static PersonIdent ident() {
        return new PersonIdent(name(), name() + "@example.invalid");
    }

    private static void commitAll(Git git) throws Exception {
        git.commit().setAll(true).setMessage(content()).setAuthor(ident()).setCommitter(ident()).setSign(false)
                .call();
    }

    /**
     * A JGit category, the change that produces it on a tracked or a new path, and in which of the three sets that
     * path is expected.
     */
    enum Scenario {
        STAGED_ADDITION("a staged addition is changed", true, false, false),
        STAGED_EDIT("a staged edit is changed", true, false, false),
        UNSTAGED_EDIT("an unstaged edit is changed", true, false, false),
        CONFLICT("a conflict is changed", true, false, false),
        STAGED_DELETION("a staged deletion is deleted", false, false, true),
        MISSING("a file missing on disk is deleted", false, false, true),
        UNTRACKED("an untracked file in a directory is untracked, with / separators", false, true, false),
        IGNORED("an ignored file is in no set", false, false, false),
        CLEAN("a clean tree has empty sets", false, false, false),
        REMOVED_BUT_PRESENT("a file removed from the index but present is deleted and untracked", false, true, true);

        private final String description;
        private final boolean changed;
        private final boolean untracked;
        private final boolean deleted;

        Scenario(String description, boolean changed, boolean untracked, boolean deleted) {
            this.description = description;
            this.changed = changed;
            this.untracked = untracked;
            this.deleted = deleted;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * Applies a scenario to the main working tree.
     *
     * @return the repository-relative path the scenario changes
     */
    private static String apply(Scenario scenario, JGitWorktreeOperationsTest.MainTree main) throws Exception {
        var root = main.root();
        var tracked = main.files().getFirst();
        try (Git git = Git.open(root.toFile())) {
            switch (scenario) {
                case STAGED_ADDITION -> {
                    var added = name();
                    Files.writeString(root.resolve(added), content());
                    git.add().addFilepattern(added).call();
                    return added;
                }
                case STAGED_EDIT -> {
                    Files.writeString(root.resolve(tracked), content() + content());
                    git.add().addFilepattern(tracked).call();
                }
                case UNSTAGED_EDIT -> Files.writeString(root.resolve(tracked), content() + content());
                case CONFLICT -> conflict(git, main, tracked);
                case STAGED_DELETION -> git.rm().addFilepattern(tracked).call();
                case MISSING -> Files.delete(root.resolve(tracked));
                case UNTRACKED -> {
                    var directory = name();
                    var file = name();
                    Files.createDirectories(root.resolve(directory));
                    Files.writeString(root.resolve(directory).resolve(file), content());
                    return directory + "/" + file;
                }
                case IGNORED -> {
                    var ignored = name();
                    var info = Files.createDirectories(root.resolve(Constants.DOT_GIT).resolve("info"));
                    Files.writeString(info.resolve("exclude"), ignored + "\n");
                    Files.writeString(root.resolve(ignored), content());
                    return ignored;
                }
                case CLEAN -> {
                    // nothing changes
                }
                case REMOVED_BUT_PRESENT -> git.rm().setCached(true).addFilepattern(tracked).call();
            }
        }
        return tracked;
    }

    /** Commits two different edits of one file on two branches and merges them into a conflict. */
    private static void conflict(Git git, JGitWorktreeOperationsTest.MainTree main, String file) throws Exception {
        var other = name();
        var root = main.root();
        git.checkout().setCreateBranch(true).setName(other).call();
        Files.writeString(root.resolve(file), content() + "\n" + other + "\n");
        commitAll(git);
        git.checkout().setName(main.branch()).call();
        Files.writeString(root.resolve(file), content() + "\n" + main.branch() + "\n");
        commitAll(git);
        var merge = git.merge().include(git.getRepository().exactRef(Constants.R_HEADS + other)).call();
        assertEquals(MergeResult.MergeStatus.CONFLICTING, merge.getMergeStatus(), "the merge conflicts");
    }

    private static Set<String> expected(boolean member, String path) {
        return member ? Set.of(path) : Set.of();
    }

    private WorktreeStatus status(Path root) {
        var result = operations.worktreeStatus(root);
        return Objects.requireNonNull(result.value(), "the status is read: " + result.detail());
    }

    @ParameterizedTest(name = "GDR-WT-3: {0}")
    @EnumSource(Scenario.class)
    @DisplayName("GDR-WT-3: each JGit category lands in exactly the documented sets")
    void mapsEachCategory(Scenario scenario, @LocalRepository Repository source) throws Exception {
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        var path = apply(scenario, main);

        var status = status(main.root());

        assertAll(
                () -> assertEquals(expected(scenario.changed, path), status.changed(), "the changed paths"),
                () -> assertEquals(expected(scenario.untracked, path), status.untracked(), "the untracked paths"),
                () -> assertEquals(expected(scenario.deleted, path), status.deleted(), "the deleted paths"));
    }

    @ParameterizedTest(name = "GDR-WT-3: the union equals the JGit path union when {0}")
    @EnumSource(Scenario.class)
    @DisplayName("GDR-WT-3: the union of the three sets is the path union of the seven JGit categories")
    void unionEqualsJGitUnion(Scenario scenario, @LocalRepository Repository source) throws Exception {
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        apply(scenario, main);
        Status jgit;
        try (Git git = Git.open(main.root().toFile())) {
            jgit = git.status().call();
        }
        // the path union the source worktreeSha builds inline from JGit's Status
        var jgitUnion = new TreeSet<String>();
        jgitUnion.addAll(jgit.getAdded());
        jgitUnion.addAll(jgit.getChanged());
        jgitUnion.addAll(jgit.getModified());
        jgitUnion.addAll(jgit.getConflicting());
        jgitUnion.addAll(jgit.getRemoved());
        jgitUnion.addAll(jgit.getMissing());
        jgitUnion.addAll(jgit.getUntracked());

        var status = status(main.root());

        var union = new TreeSet<String>();
        union.addAll(status.changed());
        union.addAll(status.untracked());
        union.addAll(status.deleted());
        assertEquals(jgitUnion, union, "the three sets cover exactly the JGit categories");
    }

    @Test
    @DisplayName("GDR-WT-3: the status of a linked working tree is its own, not the main one's")
    void linkedWorktree(@LocalRepository Repository source) throws Exception {
        var main = JGitWorktreeOperationsTest.mainTree(source, temp);
        var added = operations.worktreeAdd(WorktreeAddInput.builder().root(main.root())
                .path(main.root().resolve(name())).branch(name()).createBranch(true).startPoint(Constants.HEAD)
                .build());
        var linked = Objects.requireNonNull(added.value(), "the linked working tree is added").path();
        var edited = main.files().getFirst();
        Files.writeString(linked.resolve(edited), content() + content());

        var linkedStatus = status(linked);

        assertAll(
                () -> assertEquals(Set.of(edited), linkedStatus.changed(), "the edit of the linked tree is changed"),
                () -> assertEquals(Set.of(), linkedStatus.untracked(), "the linked tree has no untracked file"),
                () -> assertEquals(Set.of(), linkedStatus.deleted(), "the linked tree has no deleted file"));
    }

    @Test
    @DisplayName("GDR-WT-3: a plain directory is not a repository, a repository without working tree fails")
    void notARepositoryAndFailed(@LocalRepository Repository bare) throws Exception {
        var plain = Files.createDirectories(temp.resolve(name()));

        var plainResult = operations.worktreeStatus(plain);
        var bareResult = operations.worktreeStatus(bare.getDirectory().toPath());

        assertAll(
                () -> assertEquals(GitOutcome.NOT_A_REPOSITORY, plainResult.outcome(), "no repository"),
                () -> assertEquals(GitOutcome.FAILED, bareResult.outcome(), "a bare repository has no status"),
                () -> assertEquals("NoWorkTreeException", bareResult.detail(), "the detail is the failure class"));
    }
}
