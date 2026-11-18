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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RepositoryOperations.Identity;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.git.document.worktrees.WorktreeOperations.CommitInput;
import de.cuioss.git.document.worktrees.WorktreeOperations.Worktree;
import de.cuioss.git.document.worktrees.WorktreeOperations.WorktreeAddInput;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The working-tree operations on JGit: add, list, remove and commit against a main working tree cloned from a
 * {@code git-testing} fixture repository, with every outcome of each operation (GDR-WT-1, GDR-WT-2), and the absence of
 * author and committer names, e-mail addresses and commit messages from every log message and every result detail
 * (GDR-NFR-3).
 * <p>
 * The worktree part of {@code JGitOperationsTest} of {@code pm-provider-git} (pm-mcp-core, commit {@code 287e1a86}),
 * rewritten onto the fixtures. Names, contents and identities are generated; reference prefixes, hook names,
 * configuration keys and the administration layout are git's own vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("JGitWorktreeOperations")
class JGitWorktreeOperationsTest {

    // names from letters outside the hex digits, so no name reads as an abbreviated commit id
    private static final TypedGenerator<String> NAMES =
            Generators.uniqueValues(Generators.strings("ghijklmnopqrstuvwxyz", 4, 12));

    @TempDir
    Path temp;

    private JGitWorktreeOperations operations;

    @BeforeEach
    void createOperations() {
        operations = new JGitWorktreeOperations();
    }

    /**
     * Generates a name that is a valid reference and path name and never reads as a commit id.
     *
     * @return a new name
     */
    static String name() {
        return NAMES.next();
    }

    private static String content() {
        return Generators.letterStrings(1, 40).next();
    }

    /**
     * A main working tree cloned from a fixture repository: its real root, the checked-out branch, the commit it is
     * at, and the two files of that commit.
     *
     * @param root   the real working-tree root
     * @param branch the short name of the checked-out branch
     * @param head   the commit the branch is at
     * @param files  the two files of the commit
     */
    record MainTree(Path root, String branch, ObjectId head, List<String> files) {

        Path admin(String name) {
            return root.resolve(Constants.DOT_GIT).resolve(LinkedWorktrees.WORKTREES).resolve(name);
        }
    }

    /**
     * Writes a commit with two generated files on a new branch of the fixture repository and clones it into a main
     * working tree with that branch checked out.
     *
     * @param source the fixture repository
     * @param parent the directory the working tree is created in
     * @return the main working tree
     * @throws Exception if the commit or the clone fails
     */
    static MainTree mainTree(Repository source, Path parent) throws Exception {
        var branch = name();
        var files = List.of(name(), name());
        var head = commit(source, null, files);
        setRef(source, Constants.R_HEADS + branch, head);
        var root = parent.resolve(name());
        Git.cloneRepository().setURI(source.getDirectory().toURI().toString()).setDirectory(root.toFile())
                .setBranch(Constants.R_HEADS + branch).call().close();
        return new MainTree(root.toRealPath(), branch, head, files);
    }

    private static ObjectId commit(Repository repository, @Nullable ObjectId parent, List<String> files)
            throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            for (String file : files.stream().sorted().toList()) {
                tree.append(file, FileMode.REGULAR_FILE,
                        inserter.insert(Constants.OBJ_BLOB, content().getBytes(StandardCharsets.UTF_8)));
            }
            var ident = new PersonIdent(name(), name() + "@example.invalid");
            var builder = new CommitBuilder();
            builder.setTreeId(inserter.insert(tree));
            if (parent != null) {
                builder.setParentId(parent);
            }
            builder.setAuthor(ident);
            builder.setCommitter(ident);
            builder.setMessage(content());
            ObjectId id = inserter.insert(builder);
            inserter.flush();
            return id;
        }
    }

    private static void setRef(Repository repository, String ref, ObjectId target) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(target);
        update.setForceUpdate(true);
        var result = update.update();
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED) {
            throw new IOException("the fixture reference " + ref + " was not written: " + result);
        }
    }

    private static Repository open(Path root) throws IOException {
        return new FileRepositoryBuilder().setWorkTree(root.toFile()).setMustExist(true).build();
    }

    /** Creates a branch of the main working tree at a new commit on top of its head, without checking it out. */
    private static ObjectId branchOn(MainTree main, String branch) throws IOException {
        try (Repository repository = open(main.root())) {
            var commit = commit(repository, main.head(), List.of(name()));
            setRef(repository, Constants.R_HEADS + branch, commit);
            return commit;
        }
    }

    private static @Nullable ObjectId refId(Path root, String ref) throws IOException {
        try (Repository repository = open(root)) {
            var found = repository.exactRef(ref);
            return found == null ? null : found.getObjectId();
        }
    }

    private static WorktreeAddInput onNewBranch(MainTree main, Path path, String branch) {
        return WorktreeAddInput.builder().root(main.root()).path(path).branch(branch).createBranch(true)
                .startPoint(Constants.HEAD).build();
    }

    private static WorktreeAddInput onExistingBranch(MainTree main, Path path, String branch, String startPoint) {
        return WorktreeAddInput.builder().root(main.root()).path(path).branch(branch).createBranch(false)
                .startPoint(startPoint).build();
    }

    private static WorktreeAddInput detached(MainTree main, Path path, String startPoint) {
        return WorktreeAddInput.builder().root(main.root()).path(path).createBranch(false).startPoint(startPoint)
                .build();
    }

    /** Adds a linked working tree on a new branch at {@code HEAD} and fails unless it is added. */
    private Worktree added(MainTree main, String branch) {
        var result = operations.worktreeAdd(onNewBranch(main, main.root().resolve(name()), branch));
        return Objects.requireNonNull(result.value(), "the fixture working tree is added: " + result.detail());
    }

    private List<Worktree> listed(MainTree main) {
        var result = operations.worktreeList(main.root());
        return Objects.requireNonNull(result.value(), "the working trees are listed: " + result.detail());
    }

    /** An administration directory without its {@code gitdir} file, which the list cannot read. */
    private static void corruptAdministration(MainTree main) throws IOException {
        Files.createDirectories(main.admin(name()));
    }

    /**
     * Configures a filter driver for every path of the repository through a global attributes file, so that a
     * checkout or a content comparison would start the driver's process.
     */
    private void configureFilter(MainTree main, String command) throws IOException {
        var driver = name();
        var attributes = Files.writeString(temp.resolve(name()), "* filter=" + driver + "\n");
        try (Repository repository = open(main.root())) {
            var config = repository.getConfig();
            config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_ATTRIBUTESFILE,
                    attributes.toString());
            config.setString(ConfigConstants.CONFIG_FILTER_SECTION, driver, command, "cat");
            config.save();
        }
    }

    private static Identity identity() {
        var name = name();
        return new Identity(name, name + "@example.invalid");
    }

    private static CommitInput commitInput(Path root) {
        return CommitInput.builder().root(root).message(content()).author(identity()).committer(identity()).build();
    }

    @Nested
    @DisplayName("add")
    class Add {

        @Test
        @DisplayName("GDR-WT-1: a working tree on a new branch is created in git's layout, checked out and listed")
        void onNewBranchAtHead(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var path = main.root().resolve(name());

            var result = operations.worktreeAdd(onNewBranch(main, path, branch));

            var worktree = Objects.requireNonNull(result.value(), "the working tree is added");
            var all = listed(main);
            assertAll(
                    () -> assertEquals(path, worktree.path(), "the new root is reported"),
                    () -> assertEquals(path.getFileName().toString(), worktree.name(),
                            "the administrative name is the name of the root"),
                    () -> assertEquals(branch, worktree.branch(), "the new branch is checked out"),
                    () -> assertEquals(main.head().name(), worktree.head(), "the working tree starts at HEAD"),
                    () -> assertFalse(worktree.main(), "the working tree is linked"),
                    () -> assertFalse(worktree.locked(), "the initialization lock is released"),
                    () -> assertFalse(worktree.prunable(), "the working tree is present"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + branch),
                            "the branch is created at the start point"),
                    () -> assertTrue(main.files().stream().allMatch(file -> Files.isRegularFile(path.resolve(file))),
                            "the start commit is checked out"),
                    () -> assertEquals("gitdir: " + main.admin(path.getFileName().toString()),
                            Files.readString(path.resolve(Constants.DOT_GIT)).trim(),
                            "the .git file points to git's administration directory"),
                    () -> assertEquals(List.of(main.root(), path), all.stream().map(Worktree::path).toList(),
                            "the new working tree is listed after the main one"),
                    () -> assertEquals(branch, all.get(1).branch(), "the listed working tree is on the branch"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    GitWorktreesLogMessages.INFO.WORKTREE_ADDED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-WT-1: a detached working tree at a full commit id has no branch and records the commit")
        void detachedAtFullCommitId(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var path = main.root().resolve(name());

            var result = operations.worktreeAdd(detached(main, path, main.head().name()));

            var worktree = Objects.requireNonNull(result.value(), "the working tree is added");
            var linked = listed(main).get(1);
            assertAll(
                    () -> assertNull(worktree.branch(), "a detached working tree has no branch"),
                    () -> assertEquals(main.head().name(), worktree.head(), "the working tree is at the commit"),
                    () -> assertNull(linked.branch(), "the listed working tree has no branch"),
                    () -> assertEquals(main.head().name(), linked.head(), "the listed working tree records the commit"),
                    () -> assertTrue(Files.isRegularFile(path.resolve(main.files().getFirst())),
                            "the commit is checked out"));
        }

        @Test
        @DisplayName("GDR-WT-1: a working tree on an existing branch checks out the branch's commit")
        void existingBranchAtItsCommit(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var tip = branchOn(main, branch);
            var path = main.root().resolve(name());
            var pinned = main.root().resolve(name());
            var other = name();
            var otherTip = branchOn(main, other);

            var byHead = operations.worktreeAdd(onExistingBranch(main, path, branch, Constants.HEAD));
            var byPin = operations.worktreeAdd(onExistingBranch(main, pinned, other, otherTip.name()));

            assertAll(
                    () -> assertEquals(GitOutcome.OK, byHead.outcome(), "the existing branch is checked out"),
                    () -> assertEquals(tip.name(), Objects.requireNonNull(byHead.value()).head(),
                            "the working tree is at the branch's commit, not at HEAD"),
                    () -> assertEquals(GitOutcome.OK, byPin.outcome(), "a pin that matches the branch is accepted"),
                    () -> assertEquals(tip, refId(main.root(), Constants.R_HEADS + branch),
                            "the existing branch is not moved"));
        }

        @Test
        @DisplayName("GDR-WT-1: an empty directory is taken, a non-empty one or an existing new branch already exists")
        void alreadyExists(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var empty = Files.createDirectories(main.root().resolve(name()));
            var occupied = Files.createDirectories(main.root().resolve(name()));
            Files.writeString(occupied.resolve(name()), content());
            var existing = name();
            branchOn(main, existing);

            var intoEmpty = operations.worktreeAdd(onNewBranch(main, empty, name()));
            var intoOccupied = operations.worktreeAdd(onNewBranch(main, occupied, name()));
            var existingBranch = operations.worktreeAdd(onNewBranch(main, main.root().resolve(name()), existing));

            assertAll(
                    () -> assertEquals(GitOutcome.OK, intoEmpty.outcome(), "an empty directory is taken"),
                    () -> assertEquals(GitOutcome.ALREADY_EXISTS, intoOccupied.outcome(),
                            "a non-empty directory already exists"),
                    () -> assertEquals(occupied.toString(), intoOccupied.detail(), "the detail names the path"),
                    () -> assertEquals(GitOutcome.ALREADY_EXISTS, existingBranch.outcome(),
                            "a branch to create that exists already exists"),
                    () -> assertEquals("branch: already exists", existingBranch.detail(),
                            "the detail names the field"),
                    () -> assertEquals(2, listed(main).size(), "only the empty directory became a working tree"));
        }

        @Test
        @DisplayName("GDR-WT-1: a branch checked out in the main or in a linked working tree is refused")
        void branchCheckedOut(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var linkedBranch = name();
            added(main, linkedBranch);
            var first = main.root().resolve(name());
            var second = main.root().resolve(name());

            var inMain = operations.worktreeAdd(onExistingBranch(main, first, main.branch(), Constants.HEAD));
            var inLinked = operations.worktreeAdd(onExistingBranch(main, second, linkedBranch, Constants.HEAD));

            assertAll(
                    () -> assertEquals(GitOutcome.BRANCH_CHECKED_OUT, inMain.outcome(),
                            "the branch of the main working tree is checked out"),
                    () -> assertEquals(GitOutcome.BRANCH_CHECKED_OUT, inLinked.outcome(),
                            "the branch of a linked working tree is checked out"),
                    () -> assertEquals("branch: checked out in another working tree", inLinked.detail(),
                            "the detail names the field and the reason"),
                    () -> assertFalse(Files.exists(first), "no working tree is created for the main branch"),
                    () -> assertFalse(Files.exists(second), "no working tree is created for the linked branch"));
        }

        @Test
        @DisplayName("GDR-WT-1: an unknown branch, an unknown, abbreviated or absent start point is not found")
        void notFound(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var absent = ObjectId.fromRaw(Generators.letterStrings(20, 20).next()
                    .getBytes(StandardCharsets.US_ASCII)).name();

            var unknownBranch = operations.worktreeAdd(
                    onExistingBranch(main, main.root().resolve(name()), name(), Constants.HEAD));
            var unknownStart = operations.worktreeAdd(detached(main, main.root().resolve(name()), name()));
            var abbreviated = operations.worktreeAdd(
                    detached(main, main.root().resolve(name()), main.head().name().substring(0, 8)));
            var absentId = operations.worktreeAdd(detached(main, main.root().resolve(name()), absent));

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, unknownBranch.outcome(), "the branch is not found"),
                    () -> assertEquals("branch: not found", unknownBranch.detail(), "the detail names the field"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, unknownStart.outcome(), "the start point is not found"),
                    () -> assertEquals("startPoint: not found", unknownStart.detail(), "the detail names the field"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, abbreviated.outcome(),
                            "an abbreviated commit id is never resolved"),
                    () -> assertEquals("startPoint: abbreviated commit id", abbreviated.detail(),
                            "the detail names the field and the abbreviation, not the value"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, absentId.outcome(),
                            "a full commit id absent from the repository is not found"),
                    () -> assertEquals("startPoint: not found", absentId.detail(), "the detail names the field"),
                    () -> assertEquals(1, listed(main).size(), "no working tree is created"));
        }

        @Test
        @DisplayName("GDR-WT-1: a start point naming a branch and a tag is ambiguous and lists both")
        void ambiguous(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var tag = Constants.R_TAGS + main.branch();
            try (Repository repository = open(main.root())) {
                setRef(repository, tag, main.head());
            }

            var result = operations.worktreeAdd(detached(main, main.root().resolve(name()), main.branch()));

            var detail = Objects.requireNonNull(result.detail(), "an ambiguous start point has a detail");
            assertAll(
                    () -> assertEquals(GitOutcome.AMBIGUOUS, result.outcome(), "the start point is ambiguous"),
                    () -> assertTrue(detail.contains(Constants.R_HEADS + main.branch()), "the branch is a candidate"),
                    () -> assertTrue(detail.contains(tag), "the tag is a candidate"));
        }

        @Test
        @DisplayName("GDR-WT-1: a pin the branch is not at, or that reaches another commit, is an identity mismatch")
        void identityMismatch(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var tip = branchOn(main, branch);
            ObjectId tag;
            try (Repository repository = open(main.root()); ObjectInserter inserter = repository.newObjectInserter()) {
                var builder = new TagBuilder();
                builder.setObjectId(main.head(), Constants.OBJ_COMMIT);
                builder.setTag(name());
                builder.setTagger(new PersonIdent(name(), name() + "@example.invalid"));
                builder.setMessage(content());
                tag = inserter.insert(builder);
                inserter.flush();
            }
            var newBranch = name();
            var onBranch = main.root().resolve(name());

            var branchElsewhere = operations.worktreeAdd(onExistingBranch(main, onBranch, branch, main.head().name()));
            var tagPin = operations.worktreeAdd(WorktreeAddInput.builder().root(main.root())
                    .path(main.root().resolve(name())).branch(newBranch).createBranch(true).startPoint(tag.name())
                    .build());

            assertAll(
                    () -> assertEquals(GitOutcome.IDENTITY_MISMATCH, branchElsewhere.outcome(),
                            "a pin the existing branch is not at is a mismatch"),
                    () -> assertEquals("the branch is at " + tip.name() + ", not " + main.head().name(),
                            branchElsewhere.detail(), "the detail names both commits"),
                    () -> assertEquals(GitOutcome.IDENTITY_MISMATCH, tagPin.outcome(),
                            "the id of a tag object reaches another commit"),
                    () -> assertEquals("the pin " + tag.name() + " reached " + main.head().name(), tagPin.detail(),
                            "the detail names the pin and the commit it reached"),
                    () -> assertNull(refId(main.root(), Constants.R_HEADS + newBranch), "no branch is created"),
                    () -> assertFalse(Files.exists(onBranch), "no working tree is created"));
        }

        @Test
        @DisplayName("GDR-WT-1: a path outside the main working tree or the main working tree itself is refused")
        void pathRefused(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var outside = temp.resolve(name());

            var outsideResult = operations.worktreeAdd(onNewBranch(main, outside, name()));
            var rootResult = operations.worktreeAdd(onNewBranch(main, main.root(), name()));

            assertAll(
                    () -> assertEquals(GitOutcome.PATH_REFUSED, outsideResult.outcome(), "a path outside is refused"),
                    () -> assertEquals(GitOutcome.PATH_REFUSED, rootResult.outcome(), "the main root is refused"),
                    () -> assertFalse(Files.exists(outside), "nothing is created outside"));
        }

        @Test
        @DisplayName("GDR-WT-1: a root outside any repository is not a repository")
        void notARepository() throws Exception {
            var plain = Files.createDirectories(temp.resolve(name())).toRealPath();

            var result = operations.worktreeAdd(WorktreeAddInput.builder().root(plain).path(plain.resolve(name()))
                    .createBranch(false).startPoint(Constants.HEAD).build());

            assertEquals(GitOutcome.NOT_A_REPOSITORY, result.outcome(), "a plain directory is no repository");
        }

        @Test
        @DisplayName("GDR-WT-2: a checkout that would run a filter driver is refused and leaves no working tree")
        void filterDriverRefused(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            configureFilter(main, "smudge");
            var path = main.root().resolve(name());

            var result = operations.worktreeAdd(onNewBranch(main, path, name()));

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the filter driver is refused"),
                    () -> assertEquals("process_execution", result.detail(), "the detail names the capability"),
                    () -> assertFalse(Files.exists(path), "the working tree is removed again"),
                    () -> assertFalse(Files.exists(main.admin(path.getFileName().toString())),
                            "the administration directory is removed again"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitWorktreesLogMessages.WARN.CAPABILITY_REFUSED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-WT-1: unreadable administration files fail the add with the failure class only")
        void failed(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            branchOn(main, branch);
            corruptAdministration(main);

            var result = operations.worktreeAdd(
                    onExistingBranch(main, main.root().resolve(name()), branch, Constants.HEAD));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the add fails"),
                    () -> assertEquals("NoSuchFileException", result.detail(), "the detail is the failure class"));
        }
    }

    @Nested
    @DisplayName("list")
    class ListWorktrees {

        @Test
        @DisplayName("GDR-WT-1: the main working tree comes first, then the linked ones by name, from any root")
        void mainFirstThenByName(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var first = added(main, name());
            var second = added(main, name());
            var byName = Stream.of(first, second).sorted((a, b) -> Objects.requireNonNull(a.name())
                    .compareTo(Objects.requireNonNull(b.name()))).map(Worktree::path).toList();

            var fromMain = listed(main);
            var fromLinked = operations.worktreeList(first.path());

            var mainEntry = fromMain.getFirst();
            assertAll(
                    () -> assertEquals(main.root(), mainEntry.path(), "the main working tree comes first"),
                    () -> assertTrue(mainEntry.main(), "the first entry is the main working tree"),
                    () -> assertNull(mainEntry.name(), "the main working tree has no administrative name"),
                    () -> assertEquals(main.branch(), mainEntry.branch(), "the branch of the main working tree"),
                    () -> assertEquals(main.head().name(), mainEntry.head(), "the commit of the main working tree"),
                    () -> assertEquals(byName, fromMain.subList(1, 3).stream().map(Worktree::path).toList(),
                            "the linked working trees follow by name"),
                    () -> assertEquals(fromMain, fromLinked.value(), "a linked root lists the same working trees"));
        }

        @Test
        @DisplayName("GDR-WT-1: a locked working tree is listed locked, one whose directory is gone prunable")
        void lockedAndPrunable(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var locked = added(main, name());
            var gone = added(main, name());
            Files.writeString(main.admin(Objects.requireNonNull(locked.name())).resolve(LinkedWorktrees.LOCKED),
                    content());
            LinkedWorktrees.deleteTree(gone.path());

            var all = listed(main);

            var lockedEntry = all.stream().filter(w -> w.path().equals(locked.path())).findFirst().orElseThrow();
            var goneEntry = all.stream().filter(w -> w.path().equals(gone.path())).findFirst().orElseThrow();
            assertAll(
                    () -> assertTrue(lockedEntry.locked(), "the locked working tree is listed locked"),
                    () -> assertFalse(lockedEntry.prunable(), "the locked working tree is present"),
                    () -> assertTrue(goneEntry.prunable(), "the working tree without directory is prunable"),
                    () -> assertFalse(goneEntry.locked(), "the gone working tree is not locked"));
        }

        @Test
        @DisplayName("GDR-WT-1: a directory outside any repository is not a repository")
        void notARepository() throws Exception {
            var plain = Files.createDirectories(temp.resolve(name()));

            assertEquals(GitOutcome.NOT_A_REPOSITORY, operations.worktreeList(plain).outcome(),
                    "a plain directory is no repository");
        }

        @Test
        @DisplayName("GDR-WT-1: unreadable administration files fail the list with the failure class only")
        void failed(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            corruptAdministration(main);

            var result = operations.worktreeList(main.root());

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the list fails"),
                    () -> assertEquals("NoSuchFileException", result.detail(), "the detail is the failure class"));
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("GDR-WT-1: a clean linked working tree is removed with its administration directory")
        void removesClean(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var worktree = added(main, name());

            var result = operations.worktreeRemove(main.root(), worktree.path());

            assertAll(
                    () -> assertEquals(worktree.path(), result.value(), "the removed root is reported"),
                    () -> assertFalse(Files.exists(worktree.path()), "the working tree is deleted"),
                    () -> assertFalse(Files.exists(main.admin(Objects.requireNonNull(worktree.name()))),
                            "the administration directory is deleted"),
                    () -> assertEquals(List.of(main.root()), listed(main).stream().map(Worktree::path).toList(),
                            "only the main working tree is listed"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    GitWorktreesLogMessages.INFO.WORKTREE_REMOVED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-WT-1: a prunable working tree is removed by its administration directory")
        void removesPrunable(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var worktree = added(main, name());
            LinkedWorktrees.deleteTree(worktree.path());

            var result = operations.worktreeRemove(main.root(), worktree.path());

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the prunable working tree is removed"),
                    () -> assertFalse(Files.exists(main.admin(Objects.requireNonNull(worktree.name()))),
                            "the administration directory is deleted"));
        }

        @Test
        @DisplayName("GDR-WT-1: a dirty or a locked working tree is kept, the removal is never forced")
        void dirtyAndLocked(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var dirty = added(main, name());
            var locked = added(main, name());
            Files.writeString(dirty.path().resolve(main.files().getFirst()), content() + content());
            Files.writeString(main.admin(Objects.requireNonNull(locked.name())).resolve(LinkedWorktrees.LOCKED),
                    content());

            var dirtyResult = operations.worktreeRemove(main.root(), dirty.path());
            var lockedResult = operations.worktreeRemove(main.root(), locked.path());

            assertAll(
                    () -> assertEquals(GitOutcome.WORKTREE_DIRTY, dirtyResult.outcome(), "a dirty tree is kept"),
                    () -> assertEquals(dirty.path().toString(), dirtyResult.detail(), "the detail names the root"),
                    () -> assertEquals(GitOutcome.WORKTREE_LOCKED, lockedResult.outcome(), "a locked tree is kept"),
                    () -> assertTrue(Files.isDirectory(dirty.path()), "the dirty working tree remains"),
                    () -> assertTrue(Files.isDirectory(locked.path()), "the locked working tree remains"));
        }

        @Test
        @DisplayName("GDR-WT-1: the main working tree is refused and an unknown root is not found")
        void mainRefusedUnknownNotFound(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var unknown = main.root().resolve(name());

            var mainResult = operations.worktreeRemove(main.root(), main.root());
            var unknownResult = operations.worktreeRemove(main.root(), unknown);

            assertAll(
                    () -> assertEquals(GitOutcome.PATH_REFUSED, mainResult.outcome(), "the main tree is refused"),
                    () -> assertEquals("the main working tree cannot be removed", mainResult.detail(),
                            "the detail names the reason"),
                    () -> assertEquals(GitOutcome.WORKTREE_NOT_FOUND, unknownResult.outcome(),
                            "an unknown root is not found"),
                    () -> assertTrue(Files.isRegularFile(main.root().resolve(main.files().getFirst())),
                            "the main working tree is untouched"));
        }

        @Test
        @DisplayName("GDR-WT-1: a dirty check that would run a filter driver is refused and keeps the working tree")
        void filterDriverRefused(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var worktree = added(main, name());
            configureFilter(main, "clean");
            var file = worktree.path().resolve(main.files().getFirst());
            Files.setLastModifiedTime(file, FileTime.fromMillis(
                    Files.getLastModifiedTime(file).toMillis() - 60_000));

            var result = operations.worktreeRemove(main.root(), worktree.path());

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the filter driver is refused"),
                    () -> assertEquals("process_execution", result.detail(), "the detail names the capability"),
                    () -> assertTrue(Files.isDirectory(worktree.path()), "the working tree remains"));
        }

        @Test
        @DisplayName("GDR-WT-1: a root outside any repository is not a repository, unreadable administration fails")
        void notARepositoryAndFailed(@LocalRepository Repository source) throws Exception {
            var plain = Files.createDirectories(temp.resolve(name()));
            var main = mainTree(source, temp);
            var worktree = added(main, name());
            corruptAdministration(main);

            var plainResult = operations.worktreeRemove(plain, plain.resolve(name()));
            var failedResult = operations.worktreeRemove(main.root(), worktree.path());

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_A_REPOSITORY, plainResult.outcome(), "no repository"),
                    () -> assertEquals(GitOutcome.FAILED, failedResult.outcome(), "the removal fails"),
                    () -> assertEquals("NoSuchFileException", failedResult.detail(), "the detail is the failure class"),
                    () -> assertTrue(Files.isDirectory(worktree.path()), "the working tree remains"));
        }
    }

    @Nested
    @DisplayName("commit")
    class Commit {

        @Test
        @DisplayName("GDR-WT-2: every addition, modification and deletion is committed unsigned on the tree's branch")
        void commitsEveryChange(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            try (Repository repository = open(main.root())) {
                var config = repository.getConfig();
                // a signing request in the configuration must change nothing: commits are never signed
                config.setBoolean(ConfigConstants.CONFIG_COMMIT_SECTION, null, ConfigConstants.CONFIG_KEY_GPGSIGN,
                        true);
                config.save();
            }
            var branch = name();
            var worktree = added(main, branch);
            var modified = main.files().getFirst();
            var deleted = main.files().get(1);
            var addedFile = name();
            var modifiedContent = content() + content();
            Files.writeString(worktree.path().resolve(modified), modifiedContent);
            Files.delete(worktree.path().resolve(deleted));
            Files.writeString(worktree.path().resolve(addedFile), content());
            var input = commitInput(worktree.path());

            var result = operations.commit(input);

            var info = Objects.requireNonNull(result.value(), "the commit is created: " + result.detail());
            try (Repository repository = open(main.root()); RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(info.id()));
                assertAll(
                        () -> assertEquals(List.of(main.head().name()), info.parents(),
                                "the commit's parent is the start of the working tree"),
                        () -> assertEquals(ObjectId.fromString(info.id()), refId(main.root(), Constants.R_HEADS + branch),
                                "the branch of the working tree moves to the commit"),
                        () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + main.branch()),
                                "the branch of the main working tree stays"),
                        () -> assertEquals(input.message(), info.message(), "the message is the input's"),
                        () -> assertEquals(input.author(), info.author(), "the author is the input's"),
                        () -> assertEquals(input.committer(), info.committer(), "the committer is the input's"),
                        () -> assertNull(commit.getRawGpgSignature(), "the commit is not signed"),
                        () -> assertEquals(modifiedContent, read(repository, commit, modified),
                                "the modification is committed"),
                        () -> assertNotNull(TreeWalk.forPath(repository, addedFile, commit.getTree()),
                                "the addition is committed"),
                        () -> assertNull(TreeWalk.forPath(repository, deleted, commit.getTree()),
                                "the deletion is committed"));
            }
        }

        @Test
        @DisplayName("GDR-WT-2: a commit from the main working tree moves its branch")
        void commitsInMain(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            Files.writeString(main.root().resolve(name()), content());

            var result = operations.commit(commitInput(main.root()));

            var info = Objects.requireNonNull(result.value(), "the commit is created");
            assertEquals(ObjectId.fromString(info.id()), refId(main.root(), Constants.R_HEADS + main.branch()),
                    "the branch of the main working tree moves to the commit");
        }

        @Test
        @DisplayName("GDR-WT-2: a clean working tree has nothing to commit")
        void nothingToCommit(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var worktree = added(main, branch);

            var result = operations.commit(commitInput(worktree.path()));

            assertAll(
                    () -> assertEquals(GitOutcome.NOTHING_TO_COMMIT, result.outcome(), "nothing is committed"),
                    () -> assertEquals("no changes", result.detail(), "the detail names the reason"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + branch),
                            "the branch stays"));
        }

        @Test
        @DisplayName("GDR-WT-2: a hook present in the repository is refused and never run")
        void hookRefused(@LocalRepository Repository source) throws Exception {
            assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                    "The default file system supports the posix file attribute view");
            var main = mainTree(source, temp);
            var branch = name();
            var worktree = added(main, branch);
            var marker = temp.resolve(name());
            // the hook name is git's own; the script would create the marker if it ran
            var hooks = Files.createDirectories(temp.resolve(name()));
            var hook = Files.writeString(hooks.resolve("pre-commit"), "#!/bin/sh\ntouch " + marker + "\n");
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
            // a repository-local hooks path, so the developer's core.hooksPath does not decide which hook is found
            try (Repository repository = open(main.root())) {
                var config = repository.getConfig();
                config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_HOOKS_PATH,
                        hooks.toString());
                config.save();
            }
            Files.writeString(worktree.path().resolve(name()), content());

            var result = operations.commit(commitInput(worktree.path()));

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the present hook is refused"),
                    () -> assertEquals("repository_hook", result.detail(), "the detail names the capability"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + branch),
                            "nothing is committed"),
                    () -> assertFalse(Files.exists(marker), "the hook is never run"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitWorktreesLogMessages.WARN.CAPABILITY_REFUSED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-WT-2: a root outside any repository is not a repository, a locked index fails the commit")
        void notARepositoryAndFailed(@LocalRepository Repository source) throws Exception {
            var plain = Files.createDirectories(temp.resolve(name()));
            var main = mainTree(source, temp);
            var worktree = added(main, name());
            Files.writeString(worktree.path().resolve(name()), content());
            // git's lock file of the index of the linked working tree, as a concurrent writer leaves it
            Files.writeString(main.admin(Objects.requireNonNull(worktree.name())).resolve("index.lock"), "");

            var plainResult = operations.commit(commitInput(plain));
            var failedResult = operations.commit(commitInput(worktree.path()));

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_A_REPOSITORY, plainResult.outcome(), "no repository"),
                    () -> assertEquals(GitOutcome.FAILED, failedResult.outcome(), "the commit fails"),
                    () -> assertFalse(Objects.requireNonNull(failedResult.detail()).isBlank(),
                            "the detail names the failure class"));
        }

        private static String read(Repository repository, RevCommit commit, String path) throws IOException {
            try (TreeWalk walk = TreeWalk.forPath(repository, path, commit.getTree())) {
                assertNotNull(walk, "the path " + path + " is in the commit");
                return new String(repository.open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8);
            }
        }
    }

    @Nested
    @DisplayName("secrets (GDR-NFR-3)")
    class Secrets {

        // upper case, so a value cannot occur by chance in the lower-case names and paths of a detail
        private final TypedGenerator<String> upper = Generators.strings("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 12, 20);

        @Test
        @DisplayName("GDR-NFR-3: no log message and no result detail carries a name, an e-mail address or a message")
        void noPersonalDataInLogsOrDetails(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var author = new Identity(upper.next(), upper.next() + "@example.invalid");
            var committer = new Identity(upper.next(), upper.next() + "@example.invalid");
            var message = upper.next();
            var worktree = added(main, name());
            Files.writeString(worktree.path().resolve(name()), content());
            var input = CommitInput.builder().root(worktree.path()).message(message).author(author)
                    .committer(committer).build();
            var plain = Files.createDirectories(temp.resolve(name()));

            var results = new ArrayList<GitResult<?>>();
            results.add(operations.commit(input));
            results.add(operations.commit(input));
            results.add(operations.commit(CommitInput.builder().root(plain).message(message).author(author)
                    .committer(committer).build()));
            results.add(operations.worktreeAdd(detached(main, main.root().resolve(name()), author.email())));
            results.add(operations.worktreeAdd(
                    onExistingBranch(main, main.root().resolve(name()), committer.name(), Constants.HEAD)));
            results.add(operations.worktreeRemove(main.root(), worktree.path()));

            var details = results.stream().map(GitResult::detail).filter(Objects::nonNull).toList();
            var logs = TestLoggerFactory.getTestHandler().getRecordsAsString();
            var forbidden = List.of(author.name(), author.email(), committer.name(), committer.email(), message);
            assertAll(
                    () -> assertEquals(GitOutcome.OK, results.getFirst().outcome(), "the commit is created"),
                    () -> assertEquals(GitOutcome.NOTHING_TO_COMMIT, results.get(1).outcome(),
                            "the second commit has nothing to commit"),
                    () -> assertEquals(GitOutcome.OK, results.getLast().outcome(), "the committed tree is removed"),
                    () -> assertEquals(4, details.size(), "every non-success carries a detail"),
                    () -> assertTrue(details.stream().noneMatch(detail -> detail.contains("@")),
                            "no detail carries an e-mail address"),
                    () -> assertAll(forbidden.stream().<Executable>map(value -> () -> assertAll(
                            () -> assertTrue(details.stream().noneMatch(detail -> detail.contains(value)),
                                    "no detail carries " + value),
                            () -> assertFalse(logs.contains(value), "no log message carries " + value)))));
        }
    }
}
