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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
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
