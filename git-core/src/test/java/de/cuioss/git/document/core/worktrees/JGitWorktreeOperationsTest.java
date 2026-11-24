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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import de.cuioss.git.document.core.GitCoreLogMessages;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations.Identity;
import de.cuioss.git.document.core.worktrees.WorktreeOperations.CommitInput;
import de.cuioss.git.document.core.worktrees.WorktreeOperations.Worktree;
import de.cuioss.git.document.core.worktrees.WorktreeOperations.WorktreeAddInput;
import de.cuioss.git.document.testing.GitGenerators;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.hooks.PostCommitHook;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The working-tree operations on JGit: add, list, remove and commit against a main working tree cloned from a
 * {@code git-testing} fixture repository, with every outcome of each operation (GDR-CORE-12, GDR-CORE-13), and the absence of
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

    /**
     * The branch names the guard refuses beyond JGit 7.8.0 {@code Repository.isValidRefName}, which accepts each of them
     * under {@code refs/heads/}, in this order: the three git refuses as a branch name ({@code HEAD}, a leading
     * {@code -}, a component ending {@code .lock}), {@code @}, which git accepts but reads as {@code HEAD}, and a name
     * starting {@code refs/}, which the guard refuses on its own.
     *
     * @return new guarded names
     */
    static List<String> guardedBranchNames() {
        return List.of(Constants.HEAD, "-" + name(), name() + ".lock/" + name(), "@", Constants.R_REFS + name());
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

    /** Every path below {@code root}, including itself, so that a comparison shows any file created or removed. */
    private static Set<Path> tree(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.collect(Collectors.toSet());
        }
    }

    private List<Worktree> listed(MainTree main) {
        var result = operations.worktreeList(main.root());
        return Objects.requireNonNull(result.value(), "the working trees are listed: " + result.detail());
    }

    /** An administration directory whose {@code gitdir} is a directory, present but unreadable for the list. */
    private static void corruptAdministration(MainTree main) throws IOException {
        Files.createDirectories(main.admin(name()).resolve(Constants.GITDIR_FILE));
    }

    /** Removes the attributes file {@link #configureFilter} configured, so no filter driver applies any more. */
    private static void clearFilter(MainTree main) throws IOException {
        try (Repository repository = open(main.root())) {
            var config = repository.getConfig();
            config.unset(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_ATTRIBUTESFILE);
            config.save();
        }
    }

    /** Points the repository at a hooks directory holding one executable hook that would create a marker if it ran. */
    private Path installHook(MainTree main, String hookName) throws IOException {
        var marker = temp.resolve(name());
        var hooks = Files.createDirectories(temp.resolve(name()));
        var hook = Files.writeString(hooks.resolve(hookName), "#!/bin/sh\ntouch " + marker + "\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
        // a repository-local hooks path, so the developer's core.hooksPath does not decide which hook is found
        try (Repository repository = open(main.root())) {
            var config = repository.getConfig();
            config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_HOOKS_PATH,
                    hooks.toString());
            config.save();
        }
        return marker;
    }

    /** Runs the additions at the same time and returns their results in the order of the inputs. */
    private List<GitResult<Worktree>> concurrently(List<WorktreeAddInput> inputs) throws Exception {
        var start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(inputs.size());
        try {
            var futures = new ArrayList<Future<GitResult<Worktree>>>();
            for (WorktreeAddInput input : inputs) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return operations.worktreeAdd(input);
                }));
            }
            start.countDown();
            var results = new ArrayList<GitResult<Worktree>>();
            for (Future<GitResult<Worktree>> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static long count(List<GitResult<Worktree>> results, GitOutcome outcome) {
        return results.stream().filter(result -> result.outcome() == outcome).count();
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
        @DisplayName("GDR-CORE-12: a working tree on a new branch is created in git's layout, checked out and listed")
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
                    GitCoreLogMessages.INFO.WORKTREE_ADDED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-12: a detached working tree at a full commit id has no branch and records the commit")
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
        @DisplayName("GDR-CORE-12: a working tree on an existing branch checks out the branch's commit")
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
        @DisplayName("GDR-CORE-12: an empty directory is taken, a non-empty one or an existing new branch already exists")
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
        @DisplayName("GDR-CORE-12: a branch checked out in the main or in a linked working tree is refused")
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
        @DisplayName("GDR-CORE-12: an unknown branch, an unknown, abbreviated or absent start point is not found")
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
        @DisplayName("GDR-CORE-12: a start point naming a branch and a tag is ambiguous and lists both")
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
        @DisplayName("GDR-CORE-12: a pin the branch is not at, or that reaches another commit, is an identity mismatch")
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
        @DisplayName("GDR-CORE-12: a path outside the main working tree or the main working tree itself is refused")
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
        @DisplayName("GDR-CORE-12: a branch name that is no valid reference name is refused before anything is written")
        void invalidBranchNameRefused(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            // distinct values, so no part of a name occurs by chance in a detail
            var distinctTexts = GitGenerators.distinctTexts();
            var escape = distinctTexts.next();
            // git's reference-name rules: a '..' component, a space and a trailing '.lock' are invalid
            var hostile = List.of("../../../" + escape, distinctTexts.next() + " " + distinctTexts.next(),
                    distinctTexts.next() + ".lock");
            var existing = name();
            branchOn(main, existing);
            var before = tree(main.root());

            var results = new ArrayList<GitResult<Worktree>>();
            var paths = new ArrayList<Path>();
            for (String branch : hostile) {
                var path = main.root().resolve(name());
                paths.add(path);
                results.add(operations.worktreeAdd(onNewBranch(main, path, branch)));
            }
            var existingPath = main.root().resolve(name());
            var onExisting = operations.worktreeAdd(
                    onExistingBranch(main, existingPath, "../" + existing, Constants.HEAD));

            var after = tree(main.root());
            assertAll(
                    () -> assertTrue(results.stream().allMatch(r -> r.outcome() == GitOutcome.REFUSED_BY_POLICY),
                            "every invalid new branch name is refused"),
                    () -> assertTrue(results.stream().allMatch(r -> "branch: not a valid branch name".equals(
                            r.detail())), "the detail names the field and the reason"),
                    () -> assertTrue(hostile.stream().noneMatch(branch -> results.stream()
                                    .anyMatch(r -> Objects.requireNonNull(r.detail()).contains(branch))),
                            "no detail carries the caller's name"),
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, onExisting.outcome(),
                            "an invalid existing branch name is refused"),
                    () -> assertEquals("branch: not a valid branch name", onExisting.detail(),
                            "the detail names the field only"),
                    () -> assertFalse(Files.exists(main.root().resolve(escape), LinkOption.NOFOLLOW_LINKS),
                            "no reference file escapes refs/ of the common directory"),
                    () -> assertTrue(paths.stream().noneMatch(Files::exists), "no target is claimed"),
                    () -> assertFalse(Files.exists(existingPath), "no target is claimed for the existing branch"),
                    () -> assertEquals(before, after, "nothing in the main working tree or its git directory changes"),
                    () -> assertEquals(1, listed(main).size(), "no working tree is created"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a branch name JGit accepts but git does not read as that branch is refused")
        void gitBranchNamesRefused(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            // JGit 7.8.0 Repository.isValidRefName accepts each of them under refs/heads/
            var guarded = guardedBranchNames();
            var before = tree(main.root());

            var added = new ArrayList<GitResult<Worktree>>();
            var paths = new ArrayList<Path>();
            for (String branch : guarded) {
                var path = main.root().resolve(name());
                paths.add(path);
                added.add(operations.worktreeAdd(onNewBranch(main, path, branch)));
            }
            var released = guarded.stream().map(branch -> operations.releaseBranchReservation(main.root(), branch))
                    .toList();

            var after = tree(main.root());
            assertAll(
                    () -> assertTrue(guarded.stream().allMatch(b -> Repository.isValidRefName(Constants.R_HEADS + b)),
                            "JGit accepts every guarded name as a reference name"),
                    () -> assertAll(added.stream().<Executable>map(r -> () -> assertEquals(
                            GitOutcome.REFUSED_BY_POLICY, r.outcome(), "the addition is refused: " + r.detail()))),
                    () -> assertTrue(added.stream().allMatch(r -> "branch: not a valid branch name".equals(r.detail())),
                            "the addition's detail names the field only"),
                    () -> assertAll(released.stream().<Executable>map(r -> () -> assertEquals(
                            GitOutcome.REFUSED_BY_POLICY, r.outcome(), "the release is refused: " + r.detail()))),
                    () -> assertTrue(paths.stream().noneMatch(Files::exists), "no target is claimed"),
                    () -> assertEquals(before, after, "nothing in the main working tree or its git directory changes"),
                    () -> assertEquals(1, listed(main).size(), "no working tree is created"));
        }

        @Test
        @DisplayName("GDR-CORE-12: an addition to a bare repository is refused, its root being the git directory")
        void bareRootRefused() throws Exception {
            var bare = temp.resolve(name());
            Git.init().setBare(true).setDirectory(bare.toFile()).call().close();
            var path = bare.resolve(name());
            var before = tree(bare);

            var result = operations.worktreeAdd(WorktreeAddInput.builder().root(bare).path(path).branch(name())
                    .createBranch(true).startPoint(Constants.HEAD).build());

            assertAll(
                    () -> assertEquals(GitOutcome.PATH_REFUSED, result.outcome(), "the bare root is refused"),
                    () -> assertEquals(bare.toRealPath().resolve(path.getFileName().toString())
                            + " is inside the git directory", result.detail(),
                            "the detail names the target inside the git directory"),
                    () -> assertFalse(Files.exists(path), "no target is claimed"),
                    () -> assertEquals(before, tree(bare), "nothing in the bare repository changes"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a linked working tree as root is refused and nothing is created")
        void linkedRootRefused(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var linked = added(main, name());
            var path = linked.path().resolve(name());
            var branch = name();

            var result = operations.worktreeAdd(WorktreeAddInput.builder().root(linked.path()).path(path)
                    .branch(branch).createBranch(true).startPoint(Constants.HEAD).build());

            assertAll(
                    () -> assertEquals(GitOutcome.PATH_REFUSED, result.outcome(), "a linked root is refused"),
                    () -> assertEquals("root: not the main working tree", result.detail(),
                            "the detail names the field and the reason"),
                    () -> assertFalse(Files.exists(path), "no target is claimed"),
                    () -> assertNull(refId(main.root(), Constants.R_HEADS + branch), "no branch is created"),
                    () -> assertEquals(2, listed(main).size(), "no working tree is added"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a target inside the git directory, its administration area or another linked working "
                + "tree is refused")
        void targetInsideAdministrationRefused(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var linked = added(main, name());
            var gitDir = main.root().resolve(Constants.DOT_GIT);
            var inGitDir = gitDir.resolve(name());
            var inAdministration = gitDir.resolve(LinkedWorktrees.WORKTREES).resolve(name());
            var inLinked = linked.path().resolve(name());
            var before = tree(gitDir);

            var gitDirResult = operations.worktreeAdd(onNewBranch(main, inGitDir, name()));
            var administrationResult = operations.worktreeAdd(onNewBranch(main, inAdministration, name()));
            var linkedResult = operations.worktreeAdd(onNewBranch(main, inLinked, name()));

            assertAll(
                    () -> assertEquals(GitOutcome.PATH_REFUSED, gitDirResult.outcome(),
                            "a target inside the git directory is refused"),
                    () -> assertEquals(GitOutcome.PATH_REFUSED, administrationResult.outcome(),
                            "a target inside the administration area is refused"),
                    () -> assertEquals(GitOutcome.PATH_REFUSED, linkedResult.outcome(),
                            "a target inside another linked working tree is refused"),
                    () -> assertFalse(Files.exists(inGitDir), "nothing is left in the git directory"),
                    () -> assertFalse(Files.exists(inAdministration), "nothing is left in the administration area"),
                    () -> assertFalse(Files.exists(inLinked), "nothing is left in the linked working tree"),
                    () -> assertEquals(before, tree(gitDir), "the git directory is unchanged"),
                    () -> assertEquals(2, listed(main).size(), "no working tree is added"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a root outside any repository is not a repository")
        void notARepository() throws Exception {
            var plain = Files.createDirectories(temp.resolve(name())).toRealPath();

            var result = operations.worktreeAdd(WorktreeAddInput.builder().root(plain).path(plain.resolve(name()))
                    .createBranch(false).startPoint(Constants.HEAD).build());

            assertEquals(GitOutcome.NOT_A_REPOSITORY, result.outcome(), "a plain directory is no repository");
        }

        @Test
        @DisplayName("GDR-CORE-13: a checkout that would run a filter driver is refused and leaves no working tree")
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
                    GitCoreLogMessages.WARN.WORKTREE_CAPABILITY_REFUSED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-12: unreadable administration files fail the add with the failure class only")
        void failed(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            branchOn(main, branch);
            corruptAdministration(main);

            var result = operations.worktreeAdd(
                    onExistingBranch(main, main.root().resolve(name()), branch, Constants.HEAD));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the add fails"),
                    () -> assertEquals("IOException", result.detail(), "the detail is the failure class"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a refused checkout removes the branch it created, keeps an existing one, a retry succeeds")
        void refusedCheckoutRollsBackBranch(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var existing = name();
            var existingTip = branchOn(main, existing);
            var branch = name();
            var path = main.root().resolve(name());
            var onExisting = main.root().resolve(name());
            configureFilter(main, "smudge");

            var refused = operations.worktreeAdd(onNewBranch(main, path, branch));
            var refusedExisting = operations.worktreeAdd(onExistingBranch(main, onExisting, existing, Constants.HEAD));
            var branchAfterRefusal = refId(main.root(), Constants.R_HEADS + branch);
            var existingAfterRefusal = refId(main.root(), Constants.R_HEADS + existing);
            clearFilter(main);
            var retried = operations.worktreeAdd(onNewBranch(main, path, branch));
            var retriedExisting = operations.worktreeAdd(onExistingBranch(main, onExisting, existing, Constants.HEAD));

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, refused.outcome(), "the new-branch add is refused"),
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, refusedExisting.outcome(),
                            "the existing-branch add is refused"),
                    () -> assertNull(branchAfterRefusal, "the branch the refused add created is removed again"),
                    () -> assertEquals(existingTip, existingAfterRefusal, "the pre-existing branch is never deleted"),
                    () -> assertEquals(GitOutcome.OK, retried.outcome(), "the same new-branch add succeeds on retry"),
                    () -> assertEquals(GitOutcome.OK, retriedExisting.outcome(),
                            "the same existing-branch add succeeds on retry"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + branch),
                            "the retried branch is created at the start point"),
                    () -> assertTrue(Files.isRegularFile(path.resolve(main.files().getFirst())),
                            "the retried working tree is checked out"));
        }

        @Test
        @DisplayName("GDR-CORE-12: of concurrent additions for one target or one unused branch exactly one succeeds")
        void concurrentAdditions(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            int attempts = 4;
            var target = main.root().resolve(name());
            var sameTarget = new ArrayList<WorktreeAddInput>();
            for (int i = 0; i < attempts; i++) {
                sameTarget.add(onNewBranch(main, target, name()));
            }
            var branch = name();
            var tip = branchOn(main, branch);
            var paths = new ArrayList<Path>();
            var sameBranch = new ArrayList<WorktreeAddInput>();
            for (int i = 0; i < attempts; i++) {
                var path = main.root().resolve(name());
                paths.add(path);
                sameBranch.add(onExistingBranch(main, path, branch, Constants.HEAD));
            }

            var targetResults = concurrently(sameTarget);
            var branchResults = concurrently(sameBranch);

            var targetWinner = targetResults.stream().filter(GitResult::isOk).findFirst().orElseThrow().value();
            var branchWinner = branchResults.stream().filter(GitResult::isOk).findFirst().orElseThrow().value();
            var winnerPath = Objects.requireNonNull(branchWinner).path();
            var losers = paths.stream().filter(path -> !path.equals(winnerPath)).toList();
            var winnerStatus = Objects.requireNonNull(operations.worktreeStatus(winnerPath).value(), "the status");
            assertAll(
                    () -> assertEquals(1, count(targetResults, GitOutcome.OK), "one addition takes the target"),
                    () -> assertEquals(attempts - 1, count(targetResults, GitOutcome.ALREADY_EXISTS),
                            "every other addition finds the target taken"),
                    () -> assertEquals("gitdir: " + main.admin(Objects.requireNonNull(
                            Objects.requireNonNull(targetWinner).name())),
                            Files.readString(target.resolve(Constants.DOT_GIT)).trim(),
                            "the target belongs to the winner"),
                    () -> assertTrue(Files.isRegularFile(target.resolve(main.files().getFirst())),
                            "the winner's checkout survives"),
                    () -> assertEquals(1, count(branchResults, GitOutcome.OK), "one addition takes the branch"),
                    () -> assertEquals(attempts - 1, count(branchResults, GitOutcome.BRANCH_CHECKED_OUT),
                            "every other addition finds the branch checked out"),
                    () -> assertEquals(tip.name(), branchWinner.head(), "the winner is at the branch tip"),
                    () -> assertEquals(new WorktreeOperations.WorktreeStatus(Set.of(), Set.of(), Set.of()),
                            winnerStatus, "the winner's checkout survives complete"),
                    () -> assertTrue(losers.stream().noneMatch(Files::exists),
                            "no losing addition leaves its target behind"),
                    () -> assertEquals(3, listed(main).size(), "the main and the two winning working trees are listed"));
        }

        @Test
        @DisplayName("GDR-CORE-12: an unknown start point is not found for an existing branch, HEAD selects its tip")
        void unknownStartPointForExistingBranch(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var tip = branchOn(main, branch);
            var unknownPath = main.root().resolve(name());
            var headPath = main.root().resolve(name());

            var unknown = operations.worktreeAdd(onExistingBranch(main, unknownPath, branch, name()));
            var byHead = operations.worktreeAdd(onExistingBranch(main, headPath, branch, Constants.HEAD));

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, unknown.outcome(), "the unknown start point is not found"),
                    () -> assertEquals("startPoint: not found", unknown.detail(), "the detail names the field"),
                    () -> assertFalse(Files.exists(unknownPath), "no working tree is created"),
                    () -> assertEquals(GitOutcome.OK, byHead.outcome(), "HEAD is a supported start point"),
                    () -> assertEquals(tip.name(), Objects.requireNonNull(byHead.value()).head(),
                            "HEAD selects the branch tip"));
        }
    }

    @Nested
    @DisplayName("list")
    class ListWorktrees {

        @Test
        @DisplayName("GDR-CORE-12: the main working tree comes first, then the linked ones by name, from any root")
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
        @DisplayName("GDR-CORE-12: a locked working tree is listed locked, one whose directory is gone prunable")
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
        @DisplayName("GDR-CORE-12: a directory outside any repository is not a repository")
        void notARepository() throws Exception {
            var plain = Files.createDirectories(temp.resolve(name()));

            assertEquals(GitOutcome.NOT_A_REPOSITORY, operations.worktreeList(plain).outcome(),
                    "a plain directory is no repository");
        }

        @Test
        @DisplayName("GDR-CORE-12: unreadable administration files fail the list with the failure class only")
        void failed(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            corruptAdministration(main);

            var result = operations.worktreeList(main.root());

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the list fails"),
                    () -> assertEquals("IOException", result.detail(), "the detail is the failure class"));
        }

        @Test
        @DisplayName("GDR-CORE-12: an entry still being initialized fails neither the list nor another removal")
        void incompleteEntrySkipped(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var worktree = added(main, name());
            // an administration directory as a concurrent addition leaves it before its gitdir and HEAD are written
            var incomplete = Files.createDirectories(main.admin(name()));
            Files.writeString(incomplete.resolve(LinkedWorktrees.LOCKED), content());

            var all = operations.worktreeList(main.root());
            var removed = operations.worktreeRemove(main.root(), worktree.path());

            assertAll(
                    () -> assertEquals(List.of(main.root(), worktree.path()),
                            Objects.requireNonNull(all.value(), "the list succeeds").stream().map(Worktree::path)
                                    .toList(),
                            "the incomplete entry is skipped, the complete one listed"),
                    () -> assertEquals(GitOutcome.OK, removed.outcome(), "the other working tree is removed"),
                    () -> assertTrue(Files.isDirectory(incomplete), "the incomplete entry is left to its owner"));
        }
    }

    @Nested
    @DisplayName("remove")
    class Remove {

        @Test
        @DisplayName("GDR-CORE-12: a clean linked working tree is removed with its administration directory")
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
                    GitCoreLogMessages.INFO.WORKTREE_REMOVED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-12: a prunable working tree is removed by its administration directory")
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
        @DisplayName("GDR-CORE-12: a dirty or a locked working tree is kept, the removal is never forced")
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
        @DisplayName("GDR-CORE-12: the main working tree is refused and an unknown root is not found")
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
        @DisplayName("GDR-CORE-12: a dirty check that would run a filter driver is refused and keeps the working tree")
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
        @DisplayName("GDR-CORE-12: a root outside any repository is not a repository, unreadable administration fails")
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
                    () -> assertEquals("IOException", failedResult.detail(), "the detail is the failure class"),
                    () -> assertTrue(Files.isDirectory(worktree.path()), "the working tree remains"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a replaced repository at the root is not found, a reused prunable path is kept")
        void replacedRepositoryAndReusedPath(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var replaced = added(main, name());
            var reused = added(main, name());
            Files.delete(replaced.path().resolve(Constants.DOT_GIT));
            Git.init().setDirectory(replaced.path().toFile()).call().close();
            LinkedWorktrees.deleteTree(reused.path());
            Files.createDirectories(reused.path());
            var kept = Files.writeString(reused.path().resolve(name()), content());

            var replacedResult = operations.worktreeRemove(main.root(), replaced.path());
            var reusedResult = operations.worktreeRemove(main.root(), reused.path());

            assertAll(
                    () -> assertEquals(GitOutcome.WORKTREE_NOT_FOUND, replacedResult.outcome(),
                            "another repository at the root is not the registered working tree"),
                    () -> assertEquals(replaced.path() + " is not the registered working tree",
                            replacedResult.detail(), "the detail names the path and the reason"),
                    () -> assertTrue(Files.isRegularFile(replaced.path().resolve(main.files().getFirst())),
                            "the replaced repository's files are kept"),
                    () -> assertTrue(Files.isDirectory(replaced.path().resolve(Constants.DOT_GIT)),
                            "the replaced repository is kept"),
                    () -> assertTrue(Files.isDirectory(main.admin(Objects.requireNonNull(replaced.name()))),
                            "the administration directory is kept"),
                    () -> assertEquals(GitOutcome.OK, reusedResult.outcome(), "the prunable record is removed"),
                    () -> assertFalse(Files.exists(main.admin(Objects.requireNonNull(reused.name()))),
                            "the prunable record's administration directory is deleted"),
                    () -> assertTrue(Files.isRegularFile(kept), "the directory that reuses the path is kept"));
        }
    }

    @Nested
    @DisplayName("commit")
    class Commit {

        @Test
        @DisplayName("GDR-CORE-13: every addition, modification and deletion is committed unsigned on the tree's branch")
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
        @DisplayName("GDR-CORE-13: a commit from the main working tree moves its branch")
        void commitsInMain(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            Files.writeString(main.root().resolve(name()), content());

            var result = operations.commit(commitInput(main.root()));

            var info = Objects.requireNonNull(result.value(), "the commit is created");
            assertEquals(ObjectId.fromString(info.id()), refId(main.root(), Constants.R_HEADS + main.branch()),
                    "the branch of the main working tree moves to the commit");
        }

        @Test
        @DisplayName("GDR-CORE-13: a clean working tree has nothing to commit")
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
        @DisplayName("GDR-CORE-13: a hook present in the repository is refused and never run")
        void hookRefused(@LocalRepository Repository source) throws Exception {
            assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                    "The default file system supports the posix file attribute view");
            var main = mainTree(source, temp);
            var branch = name();
            var worktree = added(main, branch);
            // the hook name is git's own
            var marker = installHook(main, "pre-commit");
            Files.writeString(worktree.path().resolve(name()), content());

            var result = operations.commit(commitInput(worktree.path()));

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the present hook is refused"),
                    () -> assertEquals("repository_hook", result.detail(), "the detail names the capability"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + branch),
                            "nothing is committed"),
                    () -> assertFalse(Files.exists(marker), "the hook is never run"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.WORKTREE_CAPABILITY_REFUSED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-13: a present post-commit hook is refused before anything is staged or committed")
        void postCommitHookRefused(@LocalRepository Repository source) throws Exception {
            assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                    "The default file system supports the posix file attribute view");
            var main = mainTree(source, temp);
            var branch = name();
            var worktree = added(main, branch);
            // the hook name is git's own; JGit 7.8.0 would run it only after the branch moved
            var marker = installHook(main, "post-commit");
            var file = name();
            Files.writeString(worktree.path().resolve(file), content());

            var result = operations.commit(commitInput(worktree.path()));

            var status = Objects.requireNonNull(operations.worktreeStatus(worktree.path()).value(), "the status");
            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the present hook is refused"),
                    () -> assertEquals("repository_hook", result.detail(), "the detail names the capability"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + branch),
                            "the branch tip is the stored start commit"),
                    () -> assertEquals(Set.of(file), status.untracked(), "nothing is staged"),
                    () -> assertFalse(Files.exists(marker), "the hook is never run"));
        }

        @Test
        @DisplayName("GDR-CORE-13: a commit in the main working tree does not stage the root of a linked working tree")
        void linkedRootNotStaged(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var linked = added(main, name());
            var file = name();
            Files.writeString(main.root().resolve(file), content());

            var result = operations.commit(commitInput(main.root()));

            var info = Objects.requireNonNull(result.value(), "the commit is created: " + result.detail());
            var linkedRoot = main.root().relativize(linked.path()).toString();
            try (Repository repository = open(main.root()); RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(info.id()));
                assertAll(
                        () -> assertEquals(commit, refId(main.root(), Constants.R_HEADS + main.branch()),
                                "the branch tip is the stored commit"),
                        () -> assertNotNull(TreeWalk.forPath(repository, file, commit.getTree()),
                                "the new file is committed"),
                        () -> assertNull(TreeWalk.forPath(repository, linkedRoot, commit.getTree()),
                                "the linked root is not committed"),
                        () -> assertNull(repository.readDirCache().getEntry(linkedRoot),
                                "the linked root is not in the index"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-13: a commit in a linked working tree writes its HEAD reflog there, not into the main one")
        void linkedReflog(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var worktree = added(main, branch);
            var mainLog = main.root().resolve(Constants.DOT_GIT).resolve(Constants.LOGS).resolve(Constants.HEAD);
            var before = Files.exists(mainLog) ? Files.readAllLines(mainLog) : List.<String>of();
            Files.writeString(worktree.path().resolve(name()), content());

            var result = operations.commit(commitInput(worktree.path()));

            var id = Objects.requireNonNull(result.value(), "the commit is created: " + result.detail()).id();
            var linkedLog = main.admin(Objects.requireNonNull(worktree.name())).resolve(Constants.LOGS)
                    .resolve(Constants.HEAD);
            var branchLog = main.root().resolve(Constants.DOT_GIT).resolve(Constants.LOGS)
                    .resolve(Constants.R_HEADS + branch);
            var after = Files.exists(mainLog) ? Files.readAllLines(mainLog) : List.<String>of();
            assertAll(
                    () -> assertEquals(before, after, "the main working tree's HEAD reflog is unchanged"),
                    () -> assertTrue(Files.readString(linkedLog).contains(id),
                            "the linked working tree's HEAD reflog records the commit"),
                    () -> assertTrue(Files.readString(branchLog).contains(id), "the branch reflog records the commit"));
        }

        @Test
        @DisplayName("GDR-CORE-13: a root outside any repository is not a repository, a locked index fails the commit")
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

    /**
     * Registers a linked working tree through a symbolic link: the link is created outside the main working tree and
     * the administration directory's {@code gitdir} is rewritten to point through it, as {@code git worktree add}
     * records a path given through a link. Skips the test where the file system creates no symbolic links.
     */
    private Path registerThroughSymlink(MainTree main, Worktree worktree) throws IOException {
        var link = symbolicLink(temp.resolve(name()), worktree.path());
        Files.writeString(main.admin(Objects.requireNonNull(worktree.name())).resolve(Constants.GITDIR_FILE),
                link.resolve(Constants.DOT_GIT) + "\n");
        return link;
    }

    /**
     * Creates a symbolic link, or skips the test through an assumption where the file system creates none, as
     * {@code git-testing.adoc} states for every symlink-dependent test.
     *
     * @param link   the path of the link
     * @param target the path the link points to
     * @return the link
     */
    private static Path symbolicLink(Path link, Path target) {
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            return Assumptions.abort("The file system creates no symbolic links: " + e.getClass().getSimpleName());
        }
    }

    @Nested
    @DisplayName("reservation and registered paths")
    class RegisteredIdentity {

        @Test
        @DisplayName("GDR-CORE-12: a new or an existing branch whose reservation another addition holds is checked out")
        void reservedBranchCheckedOut(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var commonDir = main.root().resolve(Constants.DOT_GIT);
            // a concurrent new-branch addition between its reservation and its HEAD holds these reservations
            var newBranch = name();
            var heldNew = LinkedWorktrees.reserveBranch(commonDir, Constants.R_HEADS + newBranch).orElseThrow();
            var existing = name();
            branchOn(main, existing);
            var heldExisting = LinkedWorktrees.reserveBranch(commonDir, Constants.R_HEADS + existing)
                    .orElseThrow();
            var newPath = main.root().resolve(name());
            var existingPath = main.root().resolve(name());

            var onNew = operations.worktreeAdd(onNewBranch(main, newPath, newBranch));
            var onExisting = operations.worktreeAdd(onExistingBranch(main, existingPath, existing, Constants.HEAD));
            var createdBeforeRelease = refId(main.root(), Constants.R_HEADS + newBranch);
            var heldKept = Files.exists(heldNew) && Files.exists(heldExisting);
            LinkedWorktrees.release(heldNew);
            LinkedWorktrees.release(heldExisting);
            var retried = operations.worktreeAdd(onNewBranch(main, newPath, newBranch));

            assertAll(
                    () -> assertEquals(GitOutcome.BRANCH_CHECKED_OUT, onNew.outcome(),
                            "a new branch another addition reserved is checked out"),
                    () -> assertEquals("branch: checked out in another working tree", onNew.detail(),
                            "the detail names the field and the reason"),
                    () -> assertEquals(GitOutcome.BRANCH_CHECKED_OUT, onExisting.outcome(),
                            "an existing branch a new-branch addition reserved is checked out"),
                    () -> assertNull(createdBeforeRelease, "the refused addition creates no branch"),
                    () -> assertTrue(heldKept, "the other addition's reservations are kept"),
                    () -> assertEquals(GitOutcome.OK, retried.outcome(), "once released, the addition succeeds"),
                    () -> assertFalse(Files.exists(existingPath), "the refused addition claims no target"),
                    () -> assertEquals(List.of(), reservations(main), "no reservation is left"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a working tree registered through a symbolic link is found and removed by its real path")
        void removeThroughSymlink(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var worktree = added(main, name());
            var link = registerThroughSymlink(main, worktree);

            var result = operations.worktreeRemove(main.root(), worktree.path());

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the working tree is found by its real path"),
                    () -> assertFalse(Files.exists(worktree.path()), "the real working tree is deleted"),
                    () -> assertFalse(Files.exists(main.admin(Objects.requireNonNull(worktree.name()))),
                            "the administration directory is deleted"),
                    () -> assertTrue(Files.isSymbolicLink(link), "the link itself is not followed into a deletion"),
                    () -> assertEquals(List.of(main.root()), listed(main).stream().map(Worktree::path).toList(),
                            "only the main working tree is listed"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a prunable working tree added and removed through a symbolic link of its ancestor is found")
        void prunableRemovedThroughSymlinkedAncestor(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var alias = symbolicLink(temp.resolve(name()), main.root());
            var throughAlias = alias.resolve(name());
            var added = operations.worktreeAdd(WorktreeAddInput.builder().root(alias).path(throughAlias)
                    .branch(name()).createBranch(true).startPoint(Constants.HEAD).build());
            var worktree = Objects.requireNonNull(added.value(), "the working tree is added: " + added.detail());
            LinkedWorktrees.deleteTree(worktree.path());

            var result = operations.worktreeRemove(main.root(), throughAlias);

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(),
                            "the missing path is resolved through its existing ancestor: " + result.detail()),
                    () -> assertFalse(Files.exists(main.admin(Objects.requireNonNull(worktree.name()))),
                            "the administration directory is deleted"),
                    () -> assertTrue(Files.isSymbolicLink(alias), "the link of the ancestor is kept"),
                    () -> assertEquals(List.of(main.root()), listed(main).stream().map(Worktree::path).toList(),
                            "only the main working tree is listed"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a registered working tree whose directory disappeared does not fail another removal")
        void vanishedTreeDoesNotFailRemoval(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var vanished = added(main, name());
            var removed = added(main, name());
            LinkedWorktrees.deleteTree(vanished.path());

            var result = operations.worktreeRemove(main.root(), removed.path());

            var remaining = listed(main);
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the other working tree is removed"),
                    () -> assertFalse(Files.exists(removed.path()), "its directory is deleted"),
                    () -> assertEquals(2, remaining.size(), "the vanished working tree stays registered"),
                    () -> assertTrue(remaining.stream().anyMatch(w -> w.prunable()
                            && vanished.path().equals(w.path())), "the vanished working tree is listed prunable"));
        }

        @Test
        @DisplayName("GDR-CORE-13: a commit in the main working tree stages no root registered through a symbolic link")
        void linkedRootThroughSymlinkNotStaged(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var linked = added(main, name());
            registerThroughSymlink(main, linked);
            var file = name();
            Files.writeString(main.root().resolve(file), content());

            var result = operations.commit(commitInput(main.root()));

            var info = Objects.requireNonNull(result.value(), "the commit is created: " + result.detail());
            var linkedRoot = main.root().relativize(linked.path()).toString();
            try (Repository repository = open(main.root()); RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(info.id()));
                assertAll(
                        () -> assertNotNull(TreeWalk.forPath(repository, file, commit.getTree()),
                                "the new file is committed"),
                        () -> assertNull(TreeWalk.forPath(repository, linkedRoot, commit.getTree()),
                                "the linked root is not committed"),
                        () -> assertNull(repository.readDirCache().getEntry(linkedRoot),
                                "the linked root is not in the index"));
            }
        }
    }

    /**
     * A refusing file system whose checkout of the working tree at {@code armed} fails once with an unchecked
     * exception, after running {@code before}: JGit 7.8.0 {@code Checkout.checkout} reads the modification time of
     * every file it wrote through {@link org.eclipse.jgit.util.FS#lastModifiedInstant(File)}, so the failure strikes
     * after the branch was created and the administration directory was written.
     */
    private static final class FailingCheckoutFS extends RefusingFS {

        /** An action of the fixture, run just before the failure. */
        @FunctionalInterface
        interface Action {

            void run() throws IOException;
        }

        private final Path armed;
        private final Action before;
        private final AtomicBoolean fired = new AtomicBoolean();

        FailingCheckoutFS(Path armed, Action before) {
            this.armed = armed;
            this.before = before;
        }

        @Override
        public Instant lastModifiedInstant(File f) {
            if (f.toPath().toAbsolutePath().normalize().startsWith(armed) && fired.compareAndSet(false, true)) {
                try {
                    before.run();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                throw new IllegalStateException("an unchecked failure of the checkout");
            }
            return super.lastModifiedInstant(f);
        }
    }

    /**
     * A refusing file system that runs {@code action} once during the checkout of the working tree at {@code armed},
     * without failing the checkout, as {@link FailingCheckoutFS} does before it fails.
     */
    private static final class ActingCheckoutFS extends RefusingFS {

        private final Path armed;
        private final FailingCheckoutFS.Action action;
        private final AtomicBoolean fired = new AtomicBoolean();

        ActingCheckoutFS(Path armed, FailingCheckoutFS.Action action) {
            this.armed = armed;
            this.action = action;
        }

        @Override
        public Instant lastModifiedInstant(File f) {
            if (f.toPath().toAbsolutePath().normalize().startsWith(armed) && fired.compareAndSet(false, true)) {
                try {
                    action.run();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return super.lastModifiedInstant(f);
        }
    }

    /**
     * A refusing file system that runs {@code action} once, when JGit 7.8.0 {@code CommitCommand} looks up the
     * {@code post-commit} hook, which it does after it updated the branch; the first lookup of that hook is the
     * operation's own check before anything is staged.
     */
    private static final class PostCommitActionFS extends RefusingFS {

        private final FailingCheckoutFS.Action action;
        private final AtomicInteger lookups = new AtomicInteger();
        private final AtomicBoolean fired = new AtomicBoolean();

        PostCommitActionFS(FailingCheckoutFS.Action action) {
            this.action = action;
        }

        @Override
        public @Nullable File findHook(Repository repository, String hookName) {
            if (PostCommitHook.NAME.equals(hookName) && lookups.incrementAndGet() > 1
                    && fired.compareAndSet(false, true)) {
                try {
                    action.run();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return super.findHook(repository, hookName);
        }
    }

    /** Points the repository at an empty hooks directory, so no hook of the developer's configuration is found. */
    private void pinEmptyHooks(MainTree main) throws IOException {
        var hooks = Files.createDirectories(temp.resolve(name()));
        try (Repository repository = open(main.root())) {
            var config = repository.getConfig();
            config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_HOOKS_PATH,
                    hooks.toString());
            config.save();
        }
    }

    /** The branch reservation files in the common directory of the main working tree. */
    private static List<Path> reservations(MainTree main) throws IOException {
        try (Stream<Path> entries = Files.list(main.root().resolve(Constants.DOT_GIT))) {
            return entries.filter(path -> path.getFileName().toString().startsWith("worktree-branch-")).toList();
        }
    }

    @Nested
    @DisplayName("release of a branch reservation")
    class ReleaseBranchReservation {

        /** The common directory of the main working tree, as a reservation of an addition is created in it. */
        private Path commonDir(MainTree main) {
            return main.root().resolve(Constants.DOT_GIT);
        }

        @Test
        @DisplayName("GDR-CORE-15: a reservation a crashed process left is released, and the addition succeeds again")
        void crashedProcessRecovered(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            // a distinct value: a letters-only name such as "tion" occurs by chance in the text of an INFO message
            var branch = GitGenerators.distinctTexts().next();
            var tip = branchOn(main, branch);
            // the reservation of an addition whose process ended between reserving and releasing
            var left = LinkedWorktrees.reserveBranch(commonDir(main), Constants.R_HEADS + branch).orElseThrow();
            var path = main.root().resolve(name());

            var blocked = operations.worktreeAdd(onExistingBranch(main, path, branch, Constants.HEAD));
            var released = operations.releaseBranchReservation(main.root(), branch);
            var leftAfterRelease = Files.exists(left);
            var retried = operations.worktreeAdd(onExistingBranch(main, path, branch, Constants.HEAD));

            var handler = TestLoggerFactory.getTestHandler();
            assertAll(
                    () -> assertEquals(GitOutcome.BRANCH_CHECKED_OUT, blocked.outcome(),
                            "the left reservation blocks the branch no working tree has checked out"),
                    () -> assertEquals(GitOutcome.OK, released.outcome(), "the reservation is released"),
                    () -> assertEquals(Constants.R_HEADS + branch, released.value(),
                            "the result names the full branch name"),
                    () -> assertFalse(leftAfterRelease, "the reservation file is deleted"),
                    () -> assertEquals(GitOutcome.OK, retried.outcome(), "the addition succeeds again"),
                    () -> assertEquals(tip.name(), Objects.requireNonNull(retried.value()).head(),
                            "the working tree is at the branch tip"),
                    () -> assertTrue(handler.resolveLogMessagesContaining(TestLogLevel.INFO, branch).isEmpty(),
                            "no INFO message carries the branch name"),
                    () -> assertTrue(handler.resolveLogMessagesContaining(TestLogLevel.INFO,
                            main.root().toString()).isEmpty(), "no INFO message carries a path"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    GitCoreLogMessages.INFO.BRANCH_RESERVATION_RELEASED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-15: a branch without a reservation is not found")
        void noReservation(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);

            var result = operations.releaseBranchReservation(main.root(), name());

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "there is no reservation"),
                    () -> assertEquals("branch: no reservation", result.detail(),
                            "the detail names the field and the reason"),
                    () -> assertNull(result.value(), "no branch is reported"));
        }

        @Test
        @DisplayName("GDR-CORE-15: an invalid branch name is refused and deletes nothing in or outside the common directory")
        void invalidBranchNameRefused(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            // a distinct value: a letters-only name could occur by chance in the fixed words of the detail
            var escape = GitGenerators.distinctTexts().next();
            var hostile = "../../../" + escape;
            Files.writeString(temp.resolve(escape), content());
            var held = LinkedWorktrees.reserveBranch(commonDir(main), Constants.R_HEADS + name()).orElseThrow();
            var before = tree(temp);

            var result = operations.releaseBranchReservation(main.root(), hostile);

            var detail = Objects.requireNonNull(result.detail(), "a refusal has a detail");
            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the branch name is refused"),
                    () -> assertEquals("branch: not a valid branch name", detail, "the detail names the field only"),
                    () -> assertFalse(detail.contains(escape), "the detail does not carry the caller's name"),
                    () -> assertTrue(Files.exists(held), "another reservation is kept"),
                    () -> assertEquals(before, tree(temp), "no file is deleted in or outside the common directory"));
        }

        @Test
        @DisplayName("GDR-CORE-15: a path without a repository is not a repository")
        void notARepository() throws Exception {
            var plain = Files.createDirectories(temp.resolve(name()));

            var result = operations.releaseBranchReservation(plain, name());

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_A_REPOSITORY, result.outcome(), "a plain directory is no repository"),
                    () -> assertEquals(plain.toString(), result.detail(), "the detail names the path"));
        }

        @Test
        @DisplayName("GDR-CORE-15: a repository without a working tree fails as a result")
        void bareRepositoryFails() throws Exception {
            var bare = temp.resolve(name());
            Git.init().setBare(true).setDirectory(bare.toFile()).call().close();
            var branch = name();
            var held = LinkedWorktrees.reserveBranch(bare.toRealPath(), Constants.R_HEADS + branch).orElseThrow();

            var result = operations.releaseBranchReservation(bare, branch);

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the release fails"),
                    () -> assertEquals("NoWorkTreeException", result.detail(), "the detail is the failure class"),
                    () -> assertTrue(Files.exists(held), "nothing is deleted"));
        }

        @Test
        @DisplayName("GDR-CORE-15: called from a linked working tree it releases the reservation in the common directory")
        void fromLinkedRoot(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var linked = added(main, name());
            var branch = name();
            var left = LinkedWorktrees.reserveBranch(commonDir(main), Constants.R_HEADS + branch).orElseThrow();

            var result = operations.releaseBranchReservation(linked.path(), branch);

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the reservation is released"),
                    () -> assertEquals(Constants.R_HEADS + branch, result.value(),
                            "the result names the full branch name"),
                    () -> assertFalse(Files.exists(left), "the reservation in the common directory is deleted"),
                    () -> assertEquals(List.of(), reservations(main), "no reservation is left"));
        }

        @Test
        @DisplayName("GDR-CORE-15: the release deletes only the named branch's reservation")
        void onlyNamedReservation(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var other = name();
            var named = LinkedWorktrees.reserveBranch(commonDir(main), Constants.R_HEADS + branch).orElseThrow();
            var kept = LinkedWorktrees.reserveBranch(commonDir(main), Constants.R_HEADS + other).orElseThrow();

            var result = operations.releaseBranchReservation(main.root(), branch);

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the named reservation is released"),
                    () -> assertFalse(Files.exists(named), "the named reservation is deleted"),
                    () -> assertTrue(Files.exists(kept), "the other branch's reservation stays"),
                    () -> assertEquals(List.of(kept), reservations(main), "only the other reservation is left"));
        }
    }

    @Nested
    @DisplayName("result contract")
    class ResultContract {

        @Test
        @DisplayName("GDR-GEN-3: list, remove and commit on a repository without a working tree fail as a result")
        void bareRepositoryFails() throws Exception {
            var bare = temp.resolve(name());
            Git.init().setBare(true).setDirectory(bare.toFile()).call().close();

            var listed = operations.worktreeList(bare);
            var removed = operations.worktreeRemove(bare, bare.resolve(name()));
            var committed = operations.commit(commitInput(bare));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, listed.outcome(), "the list fails"),
                    () -> assertEquals("NoWorkTreeException", listed.detail(), "the detail is the failure class"),
                    () -> assertEquals(GitOutcome.FAILED, removed.outcome(), "the removal fails"),
                    () -> assertEquals("NoWorkTreeException", removed.detail(), "the detail is the failure class"),
                    () -> assertEquals(GitOutcome.FAILED, committed.outcome(), "the commit fails"),
                    () -> assertEquals("NoWorkTreeException", committed.detail(), "the detail is the failure class"));
        }

        @Test
        @DisplayName("GDR-CORE-12: an unchecked failure of the addition is a result and leaves nothing behind")
        void uncheckedFailureRolledBack(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var path = main.root().resolve(name());
            var existing = name();
            branchOn(main, existing);
            var onExisting = main.root().resolve(name());
            var failing = new JGitWorktreeOperations(new FailingCheckoutFS(path, () -> {
            }));
            var failingExisting = new JGitWorktreeOperations(new FailingCheckoutFS(onExisting, () -> {
            }));

            var result = failing.worktreeAdd(onNewBranch(main, path, branch));
            var existingResult = failingExisting.worktreeAdd(
                    onExistingBranch(main, onExisting, existing, Constants.HEAD));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the unchecked failure is a result"),
                    () -> assertEquals("IllegalStateException", result.detail(), "the detail is the failure class"),
                    () -> assertEquals(GitOutcome.FAILED, existingResult.outcome(),
                            "the unchecked failure on an existing branch is a result"),
                    () -> assertFalse(Files.exists(path), "the target is removed again"),
                    () -> assertFalse(Files.exists(onExisting), "the target on the existing branch is removed again"),
                    () -> assertFalse(Files.exists(main.admin(path.getFileName().toString())),
                            "the administration directory is removed again"),
                    () -> assertFalse(Files.exists(main.admin(onExisting.getFileName().toString())),
                            "the administration directory on the existing branch is removed again"),
                    () -> assertNull(refId(main.root(), Constants.R_HEADS + branch), "the created branch is deleted"),
                    () -> assertNotNull(refId(main.root(), Constants.R_HEADS + existing),
                            "the existing branch is kept"),
                    () -> assertEquals(List.of(), reservations(main), "no branch reservation is left"),
                    () -> assertEquals(1, listed(main).size(), "no working tree is registered"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a created branch another writer advanced is kept, one that cannot be deleted stays")
        void branchDeletionResultRead(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var advancedBranch = name();
            var advancedPath = main.root().resolve(name());
            var advancedTo = new ObjectId[1];
            var advancing = new JGitWorktreeOperations(new FailingCheckoutFS(advancedPath, () -> {
                try (Repository repository = open(main.root())) {
                    advancedTo[0] = commit(repository, main.head(), List.of(name()));
                    setRef(repository, Constants.R_HEADS + advancedBranch, advancedTo[0]);
                }
            }));
            var currentBranch = name();
            var currentPath = main.root().resolve(name());
            // the branch becomes the main working tree's own branch, which JGit 7.8.0 RefUpdate.delete rejects
            var checkingOut = new JGitWorktreeOperations(new FailingCheckoutFS(currentPath, () -> {
                try (Repository repository = open(main.root())) {
                    var link = repository.updateRef(Constants.HEAD).link(Constants.R_HEADS + currentBranch);
                    assertEquals(RefUpdate.Result.FORCED, link, "the main working tree switches branch");
                }
            }));

            var advanced = advancing.worktreeAdd(onNewBranch(main, advancedPath, advancedBranch));
            var undeletable = checkingOut.worktreeAdd(onNewBranch(main, currentPath, currentBranch));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, advanced.outcome(), "the failed addition is a result"),
                    () -> assertEquals(advancedTo[0], refId(main.root(), Constants.R_HEADS + advancedBranch),
                            "the branch another writer advanced is kept at its new commit"),
                    () -> assertFalse(Files.exists(advancedPath), "the target is removed again"),
                    () -> assertEquals(GitOutcome.FAILED, undeletable.outcome(),
                            "the addition whose branch cannot be deleted fails as a result"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + currentBranch),
                            "the branch whose deletion was rejected stays"),
                    () -> assertFalse(Files.exists(currentPath), "the target is removed although the deletion failed"),
                    () -> assertFalse(Files.exists(main.admin(currentPath.getFileName().toString())),
                            "the administration directory is removed although the deletion failed"));
        }

        @Test
        @DisplayName("GDR-CORE-12: the branch stays reserved until the rollback of a failed addition has deleted it")
        void reservationOutlivesRollback(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var path = main.root().resolve(name());
            var second = main.root().resolve(name());
            var during = new ArrayList<GitResult<Worktree>>();
            // the failing addition's administration directory loses its HEAD, as the rollback's first step removes
            // it, so no listed working tree has the branch checked out while the created branch still exists
            var failing = new JGitWorktreeOperations(new FailingCheckoutFS(path, () -> {
                Files.delete(main.admin(path.getFileName().toString()).resolve(Constants.HEAD));
                during.add(operations.worktreeAdd(onExistingBranch(main, second, branch, Constants.HEAD)));
            }));

            var failed = failing.worktreeAdd(onNewBranch(main, path, branch));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, failed.outcome(), "the failed addition is a result"),
                    () -> assertEquals(1, during.size(), "the second addition ran during the first one"),
                    () -> assertEquals(GitOutcome.BRANCH_CHECKED_OUT, during.getFirst().outcome(),
                            "the branch of the failing addition is still reserved"),
                    () -> assertFalse(Files.exists(second), "the second addition claims no target"),
                    () -> assertNull(refId(main.root(), Constants.R_HEADS + branch), "the created branch is deleted"),
                    () -> assertEquals(List.of(), reservations(main), "no branch reservation is left"),
                    () -> assertEquals(1, listed(main).size(), "no working tree is registered"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a created branch a held reference lock keeps at its start commit is reported")
        void heldRefLockReported(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var path = main.root().resolve(name());
            // git's lock file of the branch, as a concurrent writer holds it without moving the branch
            var lock = main.root().resolve(Constants.DOT_GIT).resolve(Constants.R_HEADS + branch + ".lock");
            var failing = new JGitWorktreeOperations(new FailingCheckoutFS(path, () -> Files.writeString(lock, "")));

            var result = failing.worktreeAdd(onNewBranch(main, path, branch));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the failed addition is a result"),
                    () -> assertEquals("IllegalStateException", result.detail(),
                            "the detail is the class of the failure that was rolled back"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + branch),
                            "the branch the lock kept is still at its start commit"),
                    () -> assertFalse(Files.exists(path), "the target is removed again"),
                    () -> assertEquals(List.of(), reservations(main), "no branch reservation is left"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.ROLLBACK_INCOMPLETE.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-13: a reflog entry that cannot be written does not fail the created commit")
        void reflogFailureKeepsCommit(@LocalRepository Repository source) throws Exception {
            // distinct values: none occurs by chance in a log message the commit did not put it in
            var distinctTexts = GitGenerators.distinctTexts();
            var main = mainTree(source, temp);
            var branch = name();
            var worktree = added(main, branch);
            // a directory where the linked working tree's HEAD reflog belongs, so the append cannot write it
            var linkedLog = main.admin(Objects.requireNonNull(worktree.name())).resolve(Constants.LOGS)
                    .resolve(Constants.HEAD);
            Files.createDirectories(linkedLog);
            Files.writeString(worktree.path().resolve(name()), content());
            var author = new Identity(distinctTexts.next(), distinctTexts.next() + "@example.invalid");
            var committer = new Identity(distinctTexts.next(), distinctTexts.next() + "@example.invalid");
            var message = distinctTexts.next();

            var result = operations.commit(CommitInput.builder().root(worktree.path()).message(message)
                    .author(author).committer(committer).build());

            var info = Objects.requireNonNull(result.value(), "the commit is created: " + result.detail());
            var logs = TestLoggerFactory.getTestHandler().getRecordsAsString();
            var forbidden = List.of(worktree.path().toString(), author.name(), author.email(), committer.name(),
                    committer.email(), message);
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the created commit is reported"),
                    () -> assertEquals(ObjectId.fromString(info.id()), refId(main.root(), Constants.R_HEADS + branch),
                            "the branch HEAD names is at the reported commit"),
                    () -> assertTrue(Files.isDirectory(linkedLog), "the reflog was not written"),
                    () -> assertTrue(logs.contains(info.id()), "the warning names the commit id"),
                    () -> assertAll(forbidden.stream().<Executable>map(value -> () -> assertFalse(
                            logs.contains(value), "no log message carries " + value))));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.REFLOG_NOT_WRITTEN.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-13: an invalid core.logAllRefUpdates value does not fail the created commit")
        void invalidLogAllRefUpdatesKeepsCommit(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var worktree = added(main, branch);
            // the generated letters spell none of the values JGit 7.8.0 Config.getEnum accepts for LogRefUpdates
            var invalid = name();
            try (Repository repository = open(main.root())) {
                var config = repository.getConfig();
                config.setString(ConfigConstants.CONFIG_CORE_SECTION, null,
                        ConfigConstants.CONFIG_KEY_LOGALLREFUPDATES, invalid);
                config.save();
            }
            Files.writeString(worktree.path().resolve(name()), content());

            var result = operations.commit(commitInput(worktree.path()));

            var info = Objects.requireNonNull(result.value(), "the commit is created: " + result.detail());
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the created commit is reported"),
                    () -> assertEquals(ObjectId.fromString(info.id()), refId(main.root(), Constants.R_HEADS + branch),
                            "the branch is at the stored commit"),
                    () -> assertTrue(TestLoggerFactory.getTestHandler().getRecordsAsString().contains(info.id()),
                            "the warning names the commit id"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.REFLOG_NOT_WRITTEN.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-GEN-3: an invalid core.autocrlf fails commit, status and removal with the class name only")
        void invalidAutoCrlfFails(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var linked = added(main, name());
            pinEmptyHooks(main);
            Files.writeString(main.root().resolve(name()), content());
            // the hyphen keeps the value off every name JGit 7.8.0 Config.getEnum accepts for AutoCRLF or a boolean
            var invalid = "invalid-" + name();
            try (Repository repository = open(main.root())) {
                var config = repository.getConfig();
                config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_AUTOCRLF,
                        invalid);
                config.save();
            }

            var committed = operations.commit(commitInput(main.root()));
            var status = operations.worktreeStatus(main.root());
            var removed = operations.worktreeRemove(main.root(), linked.path());

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, committed.outcome(), "the commit fails as a result"),
                    () -> assertEquals("IllegalArgumentException", committed.detail(),
                            "the commit detail is the failure class only"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + main.branch()),
                            "nothing is committed"),
                    () -> assertEquals(GitOutcome.FAILED, status.outcome(), "the status fails as a result"),
                    () -> assertEquals("IllegalArgumentException", status.detail(),
                            "the status detail is the failure class only"),
                    () -> assertEquals(GitOutcome.FAILED, removed.outcome(), "the removal fails as a result"),
                    () -> assertEquals("IllegalArgumentException", removed.detail(),
                            "the removal detail is the failure class only"),
                    () -> assertTrue(Files.isDirectory(linked.path()), "the linked working tree is kept"),
                    () -> assertEquals(2, listed(main).size(), "the linked working tree stays registered"));
        }

        @Test
        @DisplayName("GDR-CORE-12: an unchecked failure of one rollback step does not skip the steps after it")
        void uncheckedRollbackStepContinues() {
            // the rollback's order: releasing the target fails unchecked, the branch deletion and the release of
            // the reservation after it must still run
            var unchecked = new UncheckedIOException(new IOException(name()));
            var checked = new IOException(name());
            var branchDeleted = new AtomicBoolean();

            var failure = JGitWorktreeOperations.attempt(null, () -> {
                throw unchecked;
            });
            failure = JGitWorktreeOperations.attempt(failure, () -> branchDeleted.set(true));
            failure = JGitWorktreeOperations.attempt(failure, () -> {
                throw checked;
            });

            var reported = failure;
            assertAll(
                    () -> assertTrue(branchDeleted.get(), "the step after the unchecked failure runs"),
                    () -> assertSame(unchecked, reported, "the first failure is reported"),
                    () -> assertArrayEquals(new Throwable[]{checked}, Objects.requireNonNull(reported).getSuppressed(),
                            "the later failure is added as suppressed"));
        }

        @Test
        @DisplayName("GDR-CORE-12: a failed release of the reservation after a complete addition keeps the working tree")
        void failedReservationReleaseKeepsWorktree(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var path = main.root().resolve(name());
            // the reservation becomes a non-empty directory, so Files.deleteIfExists cannot release it
            var acting = new JGitWorktreeOperations(new ActingCheckoutFS(path, () -> {
                var held = reservations(main).getFirst();
                Files.delete(held);
                Files.createDirectories(held.resolve(name()));
            }));

            var result = acting.worktreeAdd(onNewBranch(main, path, branch));

            var worktree = Objects.requireNonNull(result.value(), "the working tree is added: " + result.detail());
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the complete addition is reported"),
                    () -> assertEquals(path, worktree.path(), "the result names the new working tree"),
                    () -> assertTrue(Files.isRegularFile(path.resolve(main.files().getFirst())),
                            "the checked-out working tree is kept"),
                    () -> assertEquals(main.head(), refId(main.root(), Constants.R_HEADS + branch),
                            "the created branch is kept"),
                    () -> assertEquals(2, listed(main).size(), "the working tree is registered"),
                    () -> assertEquals(1, reservations(main).size(), "the reservation is left for its release"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.RESERVATION_NOT_RELEASED.resolveIdentifierString());
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    GitCoreLogMessages.INFO.WORKTREE_ADDED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-13: an unchecked failure of the reflog step does not fail the created commit")
        void uncheckedReflogFailureKeepsCommit(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var branch = name();
            var worktree = added(main, branch);
            pinEmptyHooks(main);
            Files.writeString(worktree.path().resolve(name()), content());
            var config = main.root().resolve(Constants.DOT_GIT).resolve(Constants.CONFIG);
            var original = Files.readString(config);
            // an unterminated section after the branch was updated: JGit 7.8.0 FileRepository.getConfig reloads the
            // outdated configuration in the reflog step and throws the unchecked RuntimeException for it
            var committing = new JGitWorktreeOperations(new PostCommitActionFS(
                    () -> Files.writeString(config, original + "[" + name() + "\n")));

            var result = committing.commit(commitInput(worktree.path()));
            Files.writeString(config, original);

            var info = Objects.requireNonNull(result.value(), "the commit is created: " + result.detail());
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the created commit is reported"),
                    () -> assertEquals(ObjectId.fromString(info.id()), refId(main.root(), Constants.R_HEADS + branch),
                            "the branch is at the stored commit"),
                    () -> assertTrue(TestLoggerFactory.getTestHandler().getRecordsAsString().contains(info.id()),
                            "the warning names the commit id"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.REFLOG_NOT_WRITTEN.resolveIdentifierString());
        }
    }

    @Nested
    @DisplayName("secrets (GDR-NFR-3)")
    class Secrets {

        // distinct values: a value cannot occur by chance in the names and paths of a detail or a log message
        private final TypedGenerator<String> distinctTexts = GitGenerators.distinctTexts();

        @Test
        @DisplayName("GDR-NFR-3: no log message or result detail carries a name, an e-mail address or a message, no INFO "
                + "message a path")
        void noPersonalDataInLogsOrDetails(@LocalRepository Repository source) throws Exception {
            var main = mainTree(source, temp);
            var author = new Identity(distinctTexts.next(), distinctTexts.next() + "@example.invalid");
            var committer = new Identity(distinctTexts.next(), distinctTexts.next() + "@example.invalid");
            var message = distinctTexts.next();
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
            var handler = TestLoggerFactory.getTestHandler();
            var logs = handler.getRecordsAsString();
            var forbidden = List.of(author.name(), author.email(), committer.name(), committer.email(), message);
            assertAll(
                    () -> assertEquals(2, handler.resolveLogMessagesContaining(TestLogLevel.INFO,
                            GitCoreLogMessages.PREFIX).size(), "the add and the remove are logged at INFO"),
                    () -> assertTrue(handler.resolveLogMessagesContaining(TestLogLevel.INFO,
                            worktree.path().toString()).isEmpty(), "no INFO message carries the working-tree path"),
                    () -> assertTrue(handler.resolveLogMessagesContaining(TestLogLevel.INFO,
                                    Objects.requireNonNull(worktree.name())).isEmpty(),
                            "no INFO message carries the administrative name derived from the path"),
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
