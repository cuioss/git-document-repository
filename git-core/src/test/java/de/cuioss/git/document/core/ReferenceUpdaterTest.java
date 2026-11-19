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
package de.cuioss.git.document.core;

import static de.cuioss.git.document.core.InCoreWriterTest.content;
import static de.cuioss.git.document.core.InCoreWriterTest.files;
import static de.cuioss.git.document.core.InCoreWriterTest.metadata;
import static de.cuioss.git.document.core.InCoreWriterTest.parse;
import static de.cuioss.git.document.core.InCoreWriterTest.put;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


import de.cuioss.git.document.testing.InMemory;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.RefRename;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Compare-and-swap reference updates (GDR-CORE-5) against the in-memory and the local-repository fixtures of
 * {@code git-testing}: a new reference is created and an existing one fast-forwarded, a lost race is re-read and the
 * operation re-applied, the last lost attempt is a conflict that leaves the competing commit in place, the update never
 * forces, and concurrent updaters never lose a commit.
 * <p>
 * The races are deterministic: the operation itself moves the reference on its first call, so the update that follows
 * meets a reference that no longer holds the expected id. The threaded tests start their updaters together through a
 * latch and wait for them with a bounded timeout, and neither sleeps nor retries.
 * <p>
 * The two reference databases fail an update differently (checked against JGit 7.8.0): the in-memory DFS database
 * compares the expected id and stores the new one atomically, so an attempt is lost only when another updater advanced
 * the reference, and {@code N} updaters with {@code N} attempts each all succeed. The file-based {@code RefDirectory}
 * locks a loose reference through {@code LockFile.lock()}, which fails while another updater merely holds the
 * {@code .lock} file, and {@code RefDirectoryUpdate.tryLock} then ends the update in {@code LOCK_FAILURE}; a single
 * competing success can cost several attempts, so on the local fixture the test asserts the invariant that no commit is
 * lost, not that every updater succeeds.
 * <p>
 * Compare-and-delete (GDR-CORE-5) runs on the local fixture, where a held {@code .lock} file of the reference makes
 * the delete end in {@code LOCK_FAILURE} (checked against JGit 7.8.0: {@code RefUpdate.updateImpl} returns it when
 * {@code RefDirectoryUpdate.tryLock} fails, and {@code LockFile.lock()} fails while the {@code .lock} file exists).
 * The release of a held lock between two attempts is deterministic: {@link LockReleasingRepository} deletes the lock
 * file in the re-read that {@code compareAndDelete} performs after a {@code LOCK_FAILURE}, so no test sleeps, starts
 * a thread or depends on timing.
 * <p>
 * A symbolic reference replaced or deleted itself is tested on both fixtures, because the two databases check its
 * expected id differently (JGit 7.8.0): {@code RefDirectoryUpdate} compares it with the resolved id under the lock,
 * while {@code DfsRefUpdate} compares it with no id at all, so on the in-memory fixture the updater's guarded batch
 * decides, and a wrong expected id or a target moved in between must still be a lost race there.
 */
@EnableGeneratorController
@DisplayName("ReferenceUpdater (GDR-CORE-5)")
class ReferenceUpdaterTest {

    /** The number of concurrent updaters of the threaded tests. */
    private static final int UPDATERS = 4;

    /** The bound on the threaded tests' wait for their updaters, far above their run time. */
    private static final long TIMEOUT_SECONDS = 60;

    private final ReferenceUpdater updater = new ReferenceUpdater(new InCoreWriter());

    private static @Nullable ObjectId refId(Repository repository, String refName) throws IOException {
        Ref ref = repository.exactRef(refName);
        return ref == null ? null : ref.getObjectId();
    }

    private static String treeOf(Repository repository, ObjectId commit) throws IOException {
        return parse(repository, commit.name()).getTree().name();
    }

    /**
     * Follows the first parents from a commit down to, excluding, a base commit.
     *
     * @return the commits from the newest down
     */
    private static List<String> historyAbove(Repository repository, ObjectId head, ObjectId base) throws IOException {
        List<String> history = new ArrayList<>();
        RevCommit commit = parse(repository, head.name());
        while (!commit.equals(base)) {
            history.add(commit.name());
            assertEquals(1, commit.getParentCount(), "every commit above the base has exactly one parent");
            commit = parse(repository, commit.getParent(0).name());
        }
        return history;
    }

    /** An operation that records the tree ids it is called with and puts one file. */
    private static final class Recording implements TreeOperation {

        /** The tree ids of the calls, in order; {@code null} for a reference that did not exist. */
        private final List<@Nullable String> treeIds = new ArrayList<>();
        private final String path = TestCommits.name();
        private final String content = content();

        @Override
        public List<TreeEdit> apply(@Nullable String currentTreeId) {
            treeIds.add(currentTreeId);
            return List.of(put(path, content));
        }

        List<@Nullable String> treeIds() {
            return new ArrayList<>(treeIds);
        }
    }

    @Nested
    @DisplayName("compareAndSwap")
    class CompareAndSwap {

        @Test
        @DisplayName("GDR-CORE-5: creates a reference that does not exist with a root commit")
        void createsReference(@InMemory Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var operation = new Recording();

            var result = updater.compareAndSwap(repository, refName, metadata(), operation, 1);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the reference is created"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the reference is created"),
                    () -> assertEquals(ObjectId.fromString(result.value()), refId(repository, refName),
                            "the reference points at the new commit"),
                    () -> assertEquals(0, commit.getParentCount(), "the first commit of a reference is a root commit"),
                    () -> assertEquals(Collections.singletonList(null), operation.treeIds(),
                            "the operation is called once, without a tree"),
                    () -> assertEquals(Map.of(operation.path, operation.content), files(repository, commit),
                            "the commit holds the operation's edit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: fast-forwards an existing reference with a commit on its current commit")
        void fastForwards(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var operation = new Recording();

            var result = updater.compareAndSwap(repository, refName, metadata(), operation, 1);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the reference is moved"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the reference is fast-forwarded"),
                    () -> assertEquals(commit, refId(repository, refName), "the reference points at the new commit"),
                    () -> assertEquals(initial, commit.getParent(0), "the new commit's parent is the read commit"),
                    () -> assertEquals(List.of(treeOf(repository, initial)), operation.treeIds(),
                            "the operation receives the tree of the read commit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a lost race is re-read and the operation re-applied on the competing commit")
        void lostRaceRetried(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var competing = new ArrayList<ObjectId>();
            var operation = new Recording();
            TreeOperation racing = tree -> {
                if (competing.isEmpty()) {
                    competing.add(commitOnRef(repository, refName));
                }
                return operation.apply(tree);
            };

            var result = updater.compareAndSwap(repository, refName, metadata(), racing, 2);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the second attempt succeeds"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the second attempt succeeds"),
                    () -> assertEquals(commit, refId(repository, refName), "the reference points at the new commit"),
                    () -> assertEquals(competing.getFirst(), commit.getParent(0),
                            "the new commit's parent is the competing commit, which is kept"),
                    () -> assertEquals(List.of(treeOf(repository, initial), treeOf(repository, competing.getFirst())),
                            operation.treeIds(), "the operation is re-applied on the competing commit's tree"),
                    () -> assertEquals(operation.content, files(repository, commit).get(operation.path),
                            "the re-applied edit is in the new commit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a race lost in the last attempt is a conflict, the competing commit stays")
        void lastAttemptLost(@InMemory Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            TestCommits.commitOn(repository, refName);
            var competing = new ArrayList<ObjectId>();
            TreeOperation racing = tree -> {
                competing.add(commitOnRef(repository, refName));
                return List.of(put(TestCommits.name(), content()));
            };

            var result = updater.compareAndSwap(repository, refName, metadata(), racing, 1);

            assertAll(
                    () -> assertEquals(GitOutcome.CONFLICT, result.outcome(), "the lost race is a conflict"),
                    () -> assertNull(result.value(), "a conflict carries no commit"),
                    () -> assertEquals(refName + ": LOCK_FAILURE on every attempt (1)", result.detail(),
                            "the detail names the reference and the result"),
                    () -> assertEquals(1, competing.size(), "the operation is called once"),
                    () -> assertEquals(competing.getFirst(), refId(repository, refName),
                            "the reference stays at the competing commit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a reference moved to an unrelated commit is never overwritten, the update never forces")
        void neverForces(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            TestCommits.commitOn(repository, refName);
            var unrelated = new ArrayList<ObjectId>();
            TreeOperation racing = tree -> {
                // a root commit: the reference no longer descends from the commit the attempt read
                unrelated.add(moveRef(repository, refName, null));
                return List.of(put(TestCommits.name(), content()));
            };
            int attempts = 3;

            var result = updater.compareAndSwap(repository, refName, metadata(), racing, attempts);

            assertAll(
                    () -> assertEquals(GitOutcome.CONFLICT, result.outcome(), "the moved reference is a conflict"),
                    () -> assertEquals(attempts, unrelated.size(), "every attempt re-reads and re-applies"),
                    () -> assertEquals(unrelated.getLast(), refId(repository, refName),
                            "the reference stays at the unrelated commit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a failed in-core write ends the update with its result, the reference unchanged")
        void failedWrite(@InMemory Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);

            var result = updater.compareAndSwap(repository, refName, metadata(),
                    tree -> List.of(put(".git/config", content())), 2);

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "the writer's result is returned"),
                    () -> assertEquals("edits: invalid path", result.detail(), "the writer's detail is returned"),
                    () -> assertEquals(initial, refId(repository, refName), "the reference is unchanged"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a symbolic reference is followed, its target moves and it stays symbolic")
        void symbolicReferenceFollowed(@LocalRepository Repository repository) throws Exception {
            var target = GitIdentity.branchRef(TestCommits.name());
            var alias = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, target);
            link(repository, alias, target);

            var result = updater.compareAndSwap(repository, alias, metadata(), new Recording(), 1);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the target is moved"));
            var aliasRef = Objects.requireNonNull(repository.exactRef(alias), "the symbolic reference exists");
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the update through the alias succeeds"),
                    () -> assertEquals(initial, commit.getParent(0), "the new commit's parent is the target's commit"),
                    () -> assertEquals(commit, refId(repository, target), "the target points at the new commit"),
                    () -> assertTrue(aliasRef.isSymbolic(), "the alias stays a symbolic reference"),
                    () -> assertEquals(target, aliasRef.getTarget().getName(), "the alias still points at the target"));
        }

        @Test
        @DisplayName("GDR-NFR-3: an I/O failure of an anonymous exception class is a failure with a nonblank detail")
        void anonymousFailure(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            try (var failing = new LockReleasingRepository(directoryOf(repository))) {
                failing.failOnNextRead();

                var result = updater.compareAndSwap(failing, refName, metadata(), new Recording(), 1);

                assertAll(
                        () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the failed read is a failure"),
                        () -> assertFalse(Objects.requireNonNull(result.detail()).isBlank(),
                                "the detail is not blank although the simple name is empty"),
                        () -> assertEquals(initial, refId(repository, refName), "the reference is unchanged"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-5: fewer than one attempt is a programming error, nothing is read or written")
        void attemptsBelowOne(@InMemory Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var operation = new Recording();
            var metadata = metadata();

            // 0 is the boundary of the rule "at least one attempt", -1 a value below it
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> updater.compareAndSwap(repository, refName, metadata, operation, 0),
                            "zero attempts are rejected"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> updater.compareAndSwap(repository, refName, metadata, operation, -1),
                            "a negative number of attempts is rejected"),
                    () -> assertTrue(operation.treeIds().isEmpty(), "the operation is never called"),
                    () -> assertEquals(initial, refId(repository, refName), "the reference is unchanged"));
        }

        @Test
        @DisplayName("GDR-CORE-5: concurrent updaters on the in-memory database all succeed, no commit is lost")
        void concurrentInMemory(@InMemory Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var files = generatedFiles(UPDATERS);

            var results = runConcurrently(repository, refName, files, UPDATERS);

            var head = Objects.requireNonNull(refId(repository, refName), "the reference exists");
            var history = historyAbove(repository, head, initial);
            var expectedFiles = new HashMap<>(files(repository, parse(repository, initial.name())));
            expectedFiles.putAll(files);
            assertAll(
                    () -> assertTrue(results.stream().allMatch(GitResult::isOk), "every updater succeeds"),
                    () -> assertEquals(UPDATERS, history.size(), "the history holds one commit per updater"),
                    () -> assertEquals(okCommits(results), new HashSet<>(history),
                            "the history holds exactly the updaters' commits"),
                    () -> assertEquals(expectedFiles, files(repository, parse(repository, head.name())),
                            "the final tree holds every updater's edit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: concurrent updaters on a local repository never lose a commit, the losers conflict")
        void concurrentLocal(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var files = generatedFiles(UPDATERS);

            var results = runConcurrently(repository, refName, files, UPDATERS);

            var head = Objects.requireNonNull(refId(repository, refName), "the reference exists");
            var history = historyAbove(repository, head, initial);
            var succeeded = okCommits(results);
            assertAll(
                    () -> assertTrue(results.stream().filter(result -> !result.isOk())
                                    .allMatch(result -> result.outcome() == GitOutcome.CONFLICT),
                            "an updater that did not succeed conflicts, never fails or is rejected"),
                    () -> assertEquals(succeeded.size(), history.size(),
                            "the history holds exactly one commit per success on top of the initial commit"),
                    () -> assertEquals(succeeded, new HashSet<>(history),
                            "every successful commit is in the history of the reference"),
                    () -> assertTrue(succeeded.isEmpty() || succeeded.contains(head.name()),
                            "the reference points at the head of that history"));
        }
    }

    /** A tree source that records the tree ids it is called with and puts one file on the tree it is handed. */
    private static final class RecordingSource implements InCoreWriter.TreeSource {

        /** The tree ids of the calls, in order; {@code null} for a reference that did not exist. */
        private final List<@Nullable String> treeIds = new ArrayList<>();
        private final String path = TestCommits.name();
        private final String content = content();

        @Override
        public ObjectId write(ObjectReader reader, ObjectInserter inserter, @Nullable ObjectId firstParentTree)
                throws IOException {
            treeIds.add(firstParentTree == null ? null : firstParentTree.name());
            DirCache tree = firstParentTree == null ? DirCache.newInCore() : DirCache.read(reader, firstParentTree);
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content.getBytes(UTF_8));
            DirCacheEditor editor = tree.editor();
            editor.add(new DirCacheEditor.PathEdit(path) {
                @Override
                public void apply(DirCacheEntry entry) {
                    entry.setFileMode(FileMode.REGULAR_FILE);
                    entry.setObjectId(blob);
                }
            });
            editor.finish();
            return tree.writeTree(inserter);
        }

        List<@Nullable String> treeIds() {
            return new ArrayList<>(treeIds);
        }
    }

    @Nested
    @DisplayName("compareAndSwapTree")
    class CompareAndSwapTree {

        @Test
        @DisplayName("GDR-CORE-5: creates a reference that does not exist with a root commit of the source's tree")
        void createsReference(@InMemory Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var source = new RecordingSource();

            var result = updater.compareAndSwapTree(repository, refName, metadata(), source, 1);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the reference is created"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the reference is created"),
                    () -> assertEquals(commit, refId(repository, refName), "the reference points at the new commit"),
                    () -> assertEquals(0, commit.getParentCount(), "the first commit of a reference is a root commit"),
                    () -> assertEquals(Collections.singletonList(null), source.treeIds(),
                            "the source is called once, without a tree"),
                    () -> assertEquals(Map.of(source.path, source.content), files(repository, commit),
                            "the commit holds the source's tree"));
        }

        @Test
        @DisplayName("GDR-CORE-5: fast-forwards an existing reference, the source receives the tree of its commit")
        void fastForwards(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var source = new RecordingSource();

            var result = updater.compareAndSwapTree(repository, refName, metadata(), source, 1);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the reference is moved"));
            var expectedFiles = new HashMap<>(files(repository, parse(repository, initial.name())));
            expectedFiles.put(source.path, source.content);
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the reference is fast-forwarded"),
                    () -> assertEquals(commit, refId(repository, refName), "the reference points at the new commit"),
                    () -> assertEquals(initial, commit.getParent(0), "the new commit's parent is the read commit"),
                    () -> assertEquals(List.of(treeOf(repository, initial)), source.treeIds(),
                            "the source receives the tree of the read commit"),
                    () -> assertEquals(expectedFiles, files(repository, commit),
                            "the commit holds the read tree with the source's file"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a lost race re-runs the source on the tree of the competing commit")
        void lostRaceRetried(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var competing = new ArrayList<ObjectId>();
            var source = new RecordingSource();
            InCoreWriter.TreeSource racing = (reader, inserter, tree) -> {
                if (competing.isEmpty()) {
                    competing.add(commitOnRef(repository, refName));
                }
                return source.write(reader, inserter, tree);
            };

            var result = updater.compareAndSwapTree(repository, refName, metadata(), racing, 2);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the second attempt succeeds"));
            var expectedFiles = new HashMap<>(files(repository, parse(repository, competing.getFirst().name())));
            expectedFiles.put(source.path, source.content);
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the second attempt succeeds"),
                    () -> assertEquals(commit, refId(repository, refName), "the reference points at the new commit"),
                    () -> assertEquals(competing.getFirst(), commit.getParent(0),
                            "the new commit's parent is the competing commit, which is kept"),
                    () -> assertEquals(List.of(treeOf(repository, initial), treeOf(repository, competing.getFirst())),
                            source.treeIds(), "the source is re-run on the competing commit's tree"),
                    () -> assertEquals(expectedFiles, files(repository, commit),
                            "the new commit keeps the competing commit's files and adds the source's file"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a race lost in the last attempt is a conflict, the competing commit stays")
        void lastAttemptLost(@InMemory Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            TestCommits.commitOn(repository, refName);
            var competing = new ArrayList<ObjectId>();
            var source = new RecordingSource();
            InCoreWriter.TreeSource racing = (reader, inserter, tree) -> {
                competing.add(commitOnRef(repository, refName));
                return source.write(reader, inserter, tree);
            };
            int attempts = 2;

            var result = updater.compareAndSwapTree(repository, refName, metadata(), racing, attempts);

            assertAll(
                    () -> assertEquals(GitOutcome.CONFLICT, result.outcome(), "the lost race is a conflict"),
                    () -> assertNull(result.value(), "a conflict carries no commit"),
                    () -> assertEquals(refName + ": LOCK_FAILURE on every attempt (" + attempts + ")",
                            result.detail(), "the detail names the reference, the result and the attempts"),
                    () -> assertEquals(attempts, competing.size(), "the source is called once per attempt"),
                    () -> assertEquals(competing.getLast(), refId(repository, refName),
                            "the reference stays at the last competing commit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a symbolic reference is replaced by the update, the reference it pointed at is unchanged")
        void symbolicReferenceDetached(@LocalRepository Repository repository) throws Exception {
            var target = GitIdentity.branchRef(TestCommits.name());
            var alias = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, target);
            link(repository, alias, target);
            var source = new RecordingSource();

            var result = updater.compareAndSwapTree(repository, alias, metadata(), source, 1);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the alias is replaced"));
            var aliasRef = Objects.requireNonNull(repository.exactRef(alias), "the alias exists");
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the update of the alias succeeds"),
                    () -> assertFalse(aliasRef.isSymbolic(), "the alias is no longer a symbolic reference"),
                    () -> assertEquals(commit, aliasRef.getObjectId(), "the alias points at the new commit"),
                    () -> assertEquals(initial, commit.getParent(0),
                            "the new commit's parent is the commit the alias resolved to"),
                    () -> assertEquals(List.of(treeOf(repository, initial)), source.treeIds(),
                            "the source receives the tree the alias resolved to"),
                    () -> assertEquals(initial, refId(repository, target), "the former target keeps its commit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: on the in-memory database a symbolic reference is replaced, its target unchanged")
        void symbolicReferenceDetachedInMemory(@InMemory Repository repository) throws Exception {
            var target = GitIdentity.branchRef(TestCommits.name());
            var alias = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, target);
            link(repository, alias, target);
            var source = new RecordingSource();

            var result = updater.compareAndSwapTree(repository, alias, metadata(), source, 1);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the alias is replaced"));
            var aliasRef = Objects.requireNonNull(repository.exactRef(alias), "the alias exists");
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the update of the alias succeeds"),
                    () -> assertFalse(aliasRef.isSymbolic(), "the alias is no longer a symbolic reference"),
                    () -> assertEquals(commit, aliasRef.getObjectId(), "the alias points at the new commit"),
                    () -> assertEquals(initial, commit.getParent(0),
                            "the new commit's parent is the commit the alias resolved to"),
                    () -> assertEquals(List.of(treeOf(repository, initial)), source.treeIds(),
                            "the source receives the tree the alias resolved to"),
                    () -> assertEquals(initial, refId(repository, target), "the former target keeps its commit"));
        }

        /**
         * The expected-id check on the in-memory database: the source moves the alias's target while it runs, so the
         * update meets a base reference that no longer holds the id the attempt read. JGit 7.8.0 {@code DfsRefUpdate}
         * compares only the symbolic reference itself; the updater's guarded batch must still see the moved target.
         */
        @Test
        @DisplayName("GDR-CORE-5: on the in-memory database a symbolic reference whose target moved is a lost race")
        void symbolicTargetMovedInMemory(@InMemory Repository repository) throws Exception {
            var target = GitIdentity.branchRef(TestCommits.name());
            var alias = GitIdentity.branchRef(TestCommits.name());
            TestCommits.commitOn(repository, target);
            link(repository, alias, target);
            var competing = new ArrayList<ObjectId>();
            var source = new RecordingSource();
            InCoreWriter.TreeSource racing = (reader, inserter, tree) -> {
                competing.add(commitOnRef(repository, target));
                return source.write(reader, inserter, tree);
            };

            var result = updater.compareAndSwapTree(repository, alias, metadata(), racing, 1);

            var aliasRef = Objects.requireNonNull(repository.exactRef(alias), "the alias exists");
            assertAll(
                    () -> assertEquals(GitOutcome.CONFLICT, result.outcome(), "the moved target is a lost race"),
                    () -> assertTrue(aliasRef.isSymbolic(), "the alias stays a symbolic reference"),
                    () -> assertEquals(target, aliasRef.getTarget().getName(), "the alias still points at the target"),
                    () -> assertEquals(competing.getFirst(), aliasRef.getObjectId(),
                            "the alias resolves to the concurrent writer's commit"),
                    () -> assertEquals(competing.getFirst(), refId(repository, target),
                            "the target keeps the concurrent writer's commit"));
        }

        @Test
        @DisplayName("GDR-NFR-3: an I/O failure of the source is a failure naming the class, the reference unchanged")
        void sourceFailure(@InMemory Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var marker = TestCommits.name();

            var result = updater.compareAndSwapTree(repository, refName, metadata(), (reader, inserter, tree) -> {
                throw new IOException(marker);
            }, 2);

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the source's failure is a failure"),
                    () -> assertEquals(IOException.class.getSimpleName(), result.detail(),
                            "the detail names the exception class, never its message"),
                    () -> assertEquals(initial, refId(repository, refName), "the reference is unchanged"));
        }

        @Test
        @DisplayName("GDR-CORE-5: fewer than one attempt is a programming error, the source is never called")
        void attemptsBelowOne(@InMemory Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var source = new RecordingSource();
            var metadata = metadata();

            // 0 is the boundary of the rule "at least one attempt", -1 a value below it
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> updater.compareAndSwapTree(repository, refName, metadata, source, 0),
                            "zero attempts are rejected"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> updater.compareAndSwapTree(repository, refName, metadata, source, -1),
                            "a negative number of attempts is rejected"),
                    () -> assertTrue(source.treeIds().isEmpty(), "the source is never called"),
                    () -> assertEquals(initial, refId(repository, refName), "the reference is unchanged"));
        }
    }

    @Nested
    @DisplayName("compareAndDelete")
    class CompareAndDelete {

        @Test
        @DisplayName("GDR-CORE-5: deletes a reference that points at the expected id")
        void deletesAtExpectedId(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);

            var result = updater.compareAndDelete(repository, refName, initial.name(), 1);

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the reference is deleted"),
                    () -> assertEquals(initial.name(), result.value(), "the value is the deleted id"),
                    () -> assertNull(refId(repository, refName), "the reference is gone"));
        }

        @Test
        @DisplayName("GDR-CORE-5: deleting a symbolic reference removes it and keeps the reference it points at")
        void deletesSymbolicReferenceOnly(@LocalRepository Repository repository) throws Exception {
            var target = GitIdentity.branchRef(TestCommits.name());
            var alias = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, target);
            link(repository, alias, target);

            var result = updater.compareAndDelete(repository, alias, initial.name(), 1);

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the symbolic reference is deleted"),
                    () -> assertEquals(initial.name(), result.value(), "the value is the id it resolved to"),
                    () -> assertNull(repository.exactRef(alias), "the symbolic reference is gone"),
                    () -> assertEquals(initial, refId(repository, target), "the target keeps its commit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: on the in-memory database deleting a symbolic reference keeps its target")
        void deletesSymbolicReferenceOnlyInMemory(@InMemory Repository repository) throws Exception {
            var target = GitIdentity.branchRef(TestCommits.name());
            var alias = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, target);
            link(repository, alias, target);

            var result = updater.compareAndDelete(repository, alias, initial.name(), 1);

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the symbolic reference is deleted"),
                    () -> assertEquals(initial.name(), result.value(), "the value is the id it resolved to"),
                    () -> assertNull(repository.exactRef(alias), "the symbolic reference is gone"),
                    () -> assertEquals(initial, refId(repository, target), "the target keeps its commit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: on the in-memory database a symbolic reference at another id is not deleted")
        void symbolicReferenceAtOtherIdInMemory(@InMemory Repository repository) throws Exception {
            var target = GitIdentity.branchRef(TestCommits.name());
            var alias = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, target);
            link(repository, alias, target);
            var other = TestCommits.commit(repository, null);

            var result = updater.compareAndDelete(repository, alias, other.name(), 2);

            var aliasRef = Objects.requireNonNull(repository.exactRef(alias), "the symbolic reference is kept");
            assertAll(
                    () -> assertEquals(GitOutcome.CONFLICT, result.outcome(), "the other id is a conflict"),
                    () -> assertEquals(alias + ": LOCK_FAILURE, the reference moved or is gone", result.detail(),
                            "the detail names the reference and the result"),
                    () -> assertTrue(aliasRef.isSymbolic(), "the alias stays a symbolic reference"),
                    () -> assertEquals(target, aliasRef.getTarget().getName(), "the alias still points at the target"),
                    () -> assertEquals(initial, refId(repository, target), "the target keeps its commit"));
        }

        @Test
        @DisplayName("GDR-NFR-3: an I/O failure of an anonymous exception class is a failure with a nonblank detail")
        void anonymousFailure(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var lock = Files.createFile(lockFile(repository, refName));
            try (var failing = new LockReleasingRepository(directoryOf(repository))) {
                failing.failOnNextRead();

                var result = updater.compareAndDelete(failing, refName, initial.name(), 2);

                assertAll(
                        () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the failed re-read is a failure"),
                        () -> assertFalse(Objects.requireNonNull(result.detail()).isBlank(),
                                "the detail is not blank although the simple name is empty"),
                        () -> assertTrue(Files.exists(lock), "the other writer's lock is left in place"),
                        () -> assertEquals(initial, refId(repository, refName), "the reference is unchanged"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-5: a delete racing a newer draft commit is a conflict, the newer draft is kept")
        void deleteRacingNewerDraft(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var draft = updater.compareAndSwap(repository, refName, metadata(), new Recording(), 1);
            var newer = ObjectId.fromString(Objects.requireNonNull(draft.value(), "the newer draft is written"));

            var result = updater.compareAndDelete(repository, refName, initial.name(), 3);

            assertAll(
                    () -> assertEquals(GitOutcome.CONFLICT, result.outcome(), "the moved reference is a conflict"),
                    () -> assertNull(result.value(), "a conflict carries no id"),
                    () -> assertEquals(refName + ": LOCK_FAILURE, the reference moved or is gone", result.detail(),
                            "the detail names the reference and the result"),
                    () -> assertEquals(newer, refId(repository, refName),
                            "the reference stays at the newer draft commit"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a reference that does not exist is a conflict, nothing is created")
        void referenceGone(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var unreferenced = TestCommits.commit(repository, null);

            var result = updater.compareAndDelete(repository, refName, unreferenced.name(), 2);

            assertAll(
                    () -> assertEquals(GitOutcome.CONFLICT, result.outcome(), "the missing reference is a conflict"),
                    () -> assertEquals(refName + ": LOCK_FAILURE, the reference moved or is gone", result.detail(),
                            "the detail names the reference and the result"),
                    () -> assertNull(refId(repository, refName), "the reference is still missing"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a held lock is retried with the same expected id and the delete succeeds once released")
        void lockReleasedRetried(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var lock = Files.createFile(lockFile(repository, refName));
            try (var releasing = new LockReleasingRepository(directoryOf(repository))) {
                releasing.releaseOnNextRead(lock);

                var result = updater.compareAndDelete(releasing, refName, initial.name(), 2);

                int reads = releasing.exactReads();
                assertAll(
                        () -> assertEquals(GitOutcome.OK, result.outcome(), "the second attempt deletes"),
                        () -> assertEquals(initial.name(), result.value(), "the value is the expected id"),
                        () -> assertEquals(1, reads,
                                "the reference is re-read exactly once, after the first attempt met the lock"),
                        () -> assertFalse(Files.exists(lock), "the lock was released between the attempts"),
                        () -> assertNull(refId(releasing, refName), "the reference is gone"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-5: a lock held through every attempt is a conflict, the reference is unchanged")
        void lockNeverReleased(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var lock = Files.createFile(lockFile(repository, refName));
            int attempts = 3;

            var result = updater.compareAndDelete(repository, refName, initial.name(), attempts);

            assertAll(
                    () -> assertEquals(GitOutcome.CONFLICT, result.outcome(), "the held lock is a conflict"),
                    () -> assertNull(result.value(), "a conflict carries no id"),
                    () -> assertEquals(refName + ": LOCK_FAILURE on every attempt (" + attempts + ")",
                            result.detail(), "the detail names the reference, the result and the attempts"),
                    () -> assertTrue(Files.exists(lock), "the other writer's lock is left in place"),
                    () -> assertEquals(initial, refId(repository, refName), "the reference is unchanged"));
        }

        @Test
        @DisplayName("GDR-CORE-5: an expected id that is no full commit id is not found, nothing is deleted")
        void abbreviatedExpectedId(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);

            var result = updater.compareAndDelete(repository, refName, initial.abbreviate(7).name(), 1);

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "the abbreviated id is not found"),
                    () -> assertEquals("expectedOldId: not a full commit id", result.detail(),
                            "the detail names the field"),
                    () -> assertEquals(initial, refId(repository, refName), "the reference is unchanged"));
        }

        @Test
        @DisplayName("GDR-CORE-5: fewer than one attempt is a programming error, nothing is deleted")
        void attemptsBelowOne(@LocalRepository Repository repository) throws Exception {
            var refName = GitIdentity.branchRef(TestCommits.name());
            var initial = TestCommits.commitOn(repository, refName);
            var expected = initial.name();

            // 0 is the boundary of the rule "at least one attempt", -1 a value below it
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> updater.compareAndDelete(repository, refName, expected, 0),
                            "zero attempts are rejected"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> updater.compareAndDelete(repository, refName, expected, -1),
                            "a negative number of attempts is rejected"),
                    () -> assertEquals(initial, refId(repository, refName), "the reference is unchanged"));
        }
    }

    /** Creates a symbolic reference {@code alias} pointing at {@code target}. */
    private static void link(Repository repository, String alias, String target) throws IOException {
        var result = repository.updateRef(alias).link(target);
        assertEquals(RefUpdate.Result.NEW, result, "the symbolic reference is created");
    }

    private static File directoryOf(Repository repository) {
        return Objects.requireNonNull(repository.getDirectory(), "a local repository has a directory");
    }

    /** The {@code .lock} file JGit creates next to a loose reference while it updates or deletes it. */
    private static Path lockFile(Repository repository, String refName) {
        return directoryOf(repository).toPath().resolve(refName + ".lock");
    }

    /**
     * A second handle on a local repository, opened with the builder settings of the {@code @LocalRepository} fixture
     * (a bare repository on the fixture's directory), whose reference database releases a held lock file in the first
     * exact read after {@link #releaseOnNextRead(Path)}.
     * <p>
     * Checked against JGit 7.8.0: {@code Repository.exactRef(String)} is {@code final} and returns
     * {@code getRefDatabase().exactRef(String)}, so the double overrides {@link FileRepository#getRefDatabase()} with a
     * database that delegates every call to the real {@code RefDirectory}. The updates it creates
     * ({@code RefDirectory.newUpdate}) work on the real database, so only the updater's own {@code exactRef} calls pass
     * through the double. {@code compareAndDelete} reads the reference only after a {@code LOCK_FAILURE}, so the lock is
     * released exactly between the first and the second attempt. After {@link #failOnNextRead()} the next exact read
     * throws an anonymous {@link IOException} instead, whose simple class name is empty.
     */
    private static final class LockReleasingRepository extends FileRepository {

        private @Nullable Path heldLock;
        private boolean failNextRead;
        private int exactReads;

        LockReleasingRepository(File gitDir) throws IOException {
            super(new FileRepositoryBuilder().setGitDir(gitDir).setBare().setup());
        }

        /** Deletes the given lock file in the next exact read and starts counting the exact reads. */
        void releaseOnNextRead(Path lock) {
            heldLock = lock;
            exactReads = 0;
        }

        int exactReads() {
            return exactReads;
        }

        @Override
        public RefDatabase getRefDatabase() {
            return new Releasing(super.getRefDatabase());
        }

        /** Fails the next exact read with an instance of an anonymous {@link IOException} subclass. */
        void failOnNextRead() {
            failNextRead = true;
        }

        private @Nullable Ref readReleasing(RefDatabase database, String name) throws IOException {
            exactReads++;
            if (failNextRead) {
                failNextRead = false;
                throw new IOException(TestCommits.name()) {

                    @Serial
                    private static final long serialVersionUID = 1L;
                };
            }
            var lock = heldLock;
            if (lock != null) {
                heldLock = null;
                Files.delete(lock);
            }
            return database.exactRef(name);
        }

        /** Delegates to the real reference database; only the exact read releases the lock first. */
        private final class Releasing extends RefDatabase {

            private final RefDatabase delegate;

            Releasing(RefDatabase delegate) {
                this.delegate = delegate;
            }

            @Override
            public void create() throws IOException {
                delegate.create();
            }

            @Override
            public void close() {
                delegate.close();
            }

            @Override
            public boolean isNameConflicting(String name) throws IOException {
                return delegate.isNameConflicting(name);
            }

            @Override
            public RefUpdate newUpdate(String name, boolean detach) throws IOException {
                return delegate.newUpdate(name, detach);
            }

            @Override
            public RefRename newRename(String fromName, String toName) throws IOException {
                return delegate.newRename(fromName, toName);
            }

            @Override
            public @Nullable Ref exactRef(String name) throws IOException {
                return readReleasing(delegate, name);
            }

            @Override
            public ReflogReader getReflogReader(Ref ref) throws IOException {
                return delegate.getReflogReader(ref);
            }

            // the abstract method JGit 7.8.0 still declares; it delegates the deprecated call unchanged
            @SuppressWarnings("deprecation")
            @Override
            public Map<String, Ref> getRefs(String prefix) throws IOException {
                return delegate.getRefs(prefix);
            }

            @Override
            public List<Ref> getAdditionalRefs() throws IOException {
                return delegate.getAdditionalRefs();
            }

            @Override
            public Ref peel(Ref ref) throws IOException {
                return delegate.peel(ref);
            }
        }
    }

    /** One generated file per updater, so every updater's edit is distinguishable in the final tree. */
    private static Map<String, String> generatedFiles(int count) {
        Map<String, String> files = new HashMap<>();
        while (files.size() < count) {
            files.put(TestCommits.name(), content());
        }
        return files;
    }

    private List<GitResult<String>> runConcurrently(Repository repository, String refName, Map<String, String> files,
            int attempts) throws InterruptedException, ExecutionException {
        // generated before the updaters start, so no updater thread draws from the generators
        var commitMetadata = new ArrayList<CommitMetadata>();
        while (commitMetadata.size() < files.size()) {
            commitMetadata.add(metadata());
        }
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(files.size());
        try {
            List<Future<GitResult<String>>> futures = new ArrayList<>();
            int index = 0;
            for (var file : files.entrySet()) {
                var updaterMetadata = commitMetadata.get(index++);
                var edit = put(file.getKey(), file.getValue());
                futures.add(pool.submit(() -> {
                    start.await();
                    return updater.compareAndSwap(repository, refName, updaterMetadata, tree -> List.of(edit),
                            attempts);
                }));
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS), "every updater finishes");
            List<GitResult<String>> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Set<String> okCommits(List<GitResult<String>> results) {
        Set<String> commits = new HashSet<>();
        results.stream().filter(GitResult::isOk).forEach(result -> commits.add(result.value()));
        return commits;
    }

    /** Writes a competing commit on the reference's current commit and moves the reference to it. */
    private static ObjectId commitOnRef(Repository repository, String refName) {
        try {
            return TestCommits.commitOn(repository, refName);
        } catch (IOException e) {
            throw new IllegalStateException("the competing commit was not written", e);
        }
    }

    /** Writes a commit on a parent, or a root commit, and force-moves the reference to it. */
    private static ObjectId moveRef(Repository repository, String refName, @Nullable ObjectId parent) {
        try {
            var commit = TestCommits.commit(repository, parent);
            TestCommits.setRef(repository, refName, commit);
            return commit;
        } catch (IOException e) {
            throw new IllegalStateException("the competing commit was not written", e);
        }
    }
}
