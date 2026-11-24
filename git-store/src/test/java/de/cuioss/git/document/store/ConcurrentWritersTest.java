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
package de.cuioss.git.document.store;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Concurrent writers of GDR-NFR-2 against one {@code git-testing} local remote with {@code save.max-attempts} 10: ten
 * writers with their own bare clones save different files of one working branch at the same time and all succeed, two
 * writers that change the same file from the same loaded blob end in exactly one save and one conflict naming that
 * file, and ten threads sharing one writer all succeed as well. A second save of one draft, started while the first is
 * about to push, waits for it and finds the draft settled instead of replaying its changes.
 * <p>
 * Names, ids, pseudonyms, paths and contents are generated; the number of writers, the attempts and the details are
 * the specified scenario and kept as literals.
 */
@EnableGeneratorController
@DisplayName("Concurrent writers (GDR-NFR-2)")
class ConcurrentWritersTest {

    /** The number of concurrent writers of the scenario. */
    private static final int WRITERS = 10;

    /**
     * The margin of lost attempts per other writer: after the first-push barrier a writer can lose more than one
     * attempt to the same winner, for example once to the lease and once more to the remote reference lock it still
     * holds while its push completes (GDR-CORE-7), so one loss per other writer is not a bound.
     */
    private static final int LOSSES_PER_OTHER_WRITER = 3;

    /**
     * {@code save.max-attempts} of the scenario, sized from the number of writers: up to
     * {@value #LOSSES_PER_OTHER_WRITER} lost attempts per other writer, and the attempt that wins.
     */
    private static final int ATTEMPTS = (WRITERS - 1) * LOSSES_PER_OTHER_WRITER + 1;

    /** The time a scenario may take before it counts as hanging. */
    private static final long TIMEOUT_SECONDS = 120;

    /** The time the teardown waits for the workers after the scenario ended or timed out. */
    private static final long TEARDOWN_SECONDS = 10;

    /** The fixed claims of every save. */
    private static final IdentityClaims CLAIMS = new IdentityClaims("https://issuer.example/realms/test",
            Instant.parse("2026-03-01T08:15:30Z"), "urn:example:acr:mfa", List.of("pwd", "otp"));

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 6, 16).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-", 8, 24).next();
    }

    private static String text() {
        return Generators.letterStrings(16, 64).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static DocumentKey key() {
        var collection = value(DocumentCollection.of("c" + word(), Kind.PER_DOCUMENT));
        return value(DocumentKey.of(collection, Ulid.next()));
    }

    private static StoreConfiguration configuration() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", ATTEMPTS));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", word()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    private static String blob(String content) {
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
            return formatter.idFor(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8)).name();
        }
    }

    private static List<TreeEdit> put(String path, String content) {
        return List.of(new TreeEdit.Put(path, content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Writes a commit holding exactly the files onto a reference of the remote, forcing the reference. */
    private static ObjectId commit(Repository repository, String ref, Map<String, String> files) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
                tree.append(file.getKey(), FileMode.REGULAR_FILE,
                        inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            var commit = new CommitBuilder();
            var ident = new PersonIdent(pseudonym(), "");
            commit.setTreeId(inserter.insert(tree));
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(ref);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            var result = update.update();
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED,
                    () -> "the fixture reference is written: " + result);
            return id;
        }
    }

    /** The files of the tip of a reference, path to content. */
    private static Map<String, String> files(Repository repository, String ref) throws IOException {
        Ref tip = Objects.requireNonNull(repository.exactRef(ref), "the reference exists");
        Map<String, String> files = new TreeMap<>();
        try (RevWalk walk = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(tip.getObjectId()).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                files.put(tree.getPathString(),
                        new String(repository.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private StoreWriter open(StoreRepository storeRepository) {
        return open(storeRepository, new JGitRepositoryOperations(RemotePolicy.localOnly()));
    }

    private StoreWriter open(StoreRepository storeRepository, RepositoryOperations operations) {
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        return value(StoreWriter.open(storeRepository, temp.resolve(word() + ".git"), user, configuration(), List.of(),
                TestKeys.ed25519().source(), operations, Clock.fixed(Instant.now(), ZoneOffset.UTC)));
    }

    /** Waits until the thread parks on a lock or ends, from a callback that may not throw a checked exception. */
    private static void awaitParked(Thread thread) {
        try {
            ThreadStates.awaitParkedOrEnded(thread, TIMEOUT_SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the wait for the parked thread is interrupted", e);
        }
    }

    /**
     * Runs the tasks on one thread each, released together, and returns their results in task order. One deadline
     * bounds the whole scenario: a worker that has not ended by then fails the test with a {@code TimeoutException},
     * and the teardown cancels every outstanding task and waits for the workers at most {@value #TEARDOWN_SECONDS}
     * seconds, so a blocked worker cannot hang the test.
     */
    private static <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        var start = new CountDownLatch(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        List<Future<T>> futures = new ArrayList<>();
        List<T> results = new ArrayList<>();
        boolean ended;
        try {
            for (Callable<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the threads are released");
                    return task.call();
                }));
            }
            start.countDown();
            for (Future<T> future : futures) {
                results.add(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            ended = executor.awaitTermination(TEARDOWN_SECONDS, TimeUnit.SECONDS);
        }
        assertTrue(ended, "the workers end");
        return results;
    }

    /**
     * Ten writers, each with its own clone and its own repository operations, are released together and held before
     * their first push until all ten fetched the same base, so all ten push against the same lease: one wins and nine
     * lose, and each loser fetches and re-applies. A writer can lose at most once per other writer, nine times, so with
     * {@code save.max-attempts} 10 every writer ends {@link SaveResult.Saved}.
     * <p>
     * The lost race was observed in two forms on the {@code git-testing} local remote (JGit 7.8.0 in-process
     * {@code ReceivePack}): a refused lease when the advertisement already showed the moved branch, and, when the
     * branch moved after the advertisement, the status line {@code ng <ref> failed to lock}, which {@code git-core}
     * reported before as
     * {@code NotSaved[outcome=PassedOn[git=REJECTED], reason=refs/heads/<collection>/<id>: rejected_other_reason]} and
     * now reports as {@code LEASE_REFUSED} (GDR-CORE-7), so the save retries it and ends a permanent rejection at once.
     */
    @Test
    @DisplayName("GDR-NFR-2: ten writers with their own clones racing at one lease all succeed after retrying")
    void tenWritersWithOwnClones(@Remote LocalRemote remote) throws Exception {
        var key = key();
        var target = StoreReferences.target(key);
        Map<String, String> expected = new TreeMap<>(Map.of(word() + "-base", text()));
        commit(remote.repository(), target, expected);
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var atTheLease = new CountDownLatch(WRITERS);
        List<ScriptedRepositoryOperations> handles = new ArrayList<>();
        List<StoreWriter> writers = new ArrayList<>();
        try {
            List<Callable<SaveResult>> saves = new ArrayList<>();
            for (int index = 0; index < WRITERS; index++) {
                var operations = new ScriptedRepositoryOperations();
                operations.beforeNextPush(() -> {
                    atTheLease.countDown();
                    awaitAll(atTheLease);
                });
                handles.add(operations);
                var writer = open(storeRepository, operations);
                writers.add(writer);
                var author = Author.of(pseudonym());
                var path = word() + "-" + index;
                var content = text();
                expected.put(path, content);
                value(writer.draft(author, key, put(path, content), word()));
                saves.add(() -> writer.save(author, CLAIMS, key, Map.of(), word()));
            }

            var results = concurrently(saves);

            List<Integer> pushes = handles.stream().map(handle -> handle.pushes().size()).toList();
            assertAll(
                    () -> assertTrue(results.stream().allMatch(SaveResult.Saved.class::isInstance),
                            () -> "every save succeeds: " + results),
                    () -> assertEquals(expected, files(remote.repository(), target), "the branch holds all files"),
                    () -> assertTrue(pushes.stream().anyMatch(count -> count > 1),
                            () -> "at least one writer lost the race and retried: " + pushes),
                    () -> assertTrue(pushes.stream().mapToInt(Integer::intValue).sum() >= 2 * WRITERS - 1,
                            () -> "every writer but the winner of the first round pushed again: " + pushes),
                    () -> assertTrue(pushes.stream().allMatch(count -> count <= ATTEMPTS),
                            () -> "no writer needed more than save.max-attempts: " + pushes));
        } finally {
            // every writer is closed before the fixture deletes the temporary remote
            writers.forEach(StoreWriter::close);
        }
    }

    /** Waits for every writer at the lease, from a callback that may not throw a checked exception. */
    private static void awaitAll(CountDownLatch latch) {
        try {
            assertTrue(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "every writer reaches the lease");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the wait at the lease is interrupted", e);
        }
    }

    @Test
    @DisplayName("GDR-NFR-2: two writers saving the same file from the same blob end in one save and one conflict")
    void twoWritersOnSameFile(@Remote LocalRemote remote) throws Exception {
        var key = key();
        var target = StoreReferences.target(key);
        var path = word();
        var loaded = text();
        commit(remote.repository(), target, Map.of(path, loaded));
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var expectedBlobIds = Map.of(path, blob(loaded));
        List<String> contents = List.of(text() + "a", text() + "b");

        try (var first = open(storeRepository); var second = open(storeRepository)) {
            List<StoreWriter> writers = List.of(first, second);
            List<Callable<SaveResult>> saves = new ArrayList<>();
            for (int index = 0; index < writers.size(); index++) {
                var writer = writers.get(index);
                var author = Author.of(pseudonym());
                value(writer.draft(author, key, put(path, contents.get(index)), word()));
                saves.add(() -> writer.save(author, CLAIMS, key, expectedBlobIds, word()));
            }

            var results = concurrently(saves);

            var saved = results.stream().filter(SaveResult.Saved.class::isInstance).toList();
            var conflicting = results.stream().filter(SaveResult.Conflicting.class::isInstance)
                    .map(SaveResult.Conflicting.class::cast).toList();
            assertAll(
                    () -> assertEquals(1, saved.size(), () -> "exactly one save succeeds: " + results),
                    () -> assertEquals(1, conflicting.size(), () -> "exactly one save conflicts: " + results),
                    () -> assertEquals(List.of(path), conflicting.getFirst().paths(), "the conflict names the file"),
                    () -> assertEquals(contents.get(results.indexOf(saved.getFirst())),
                            files(remote.repository(), target).get(path), "the branch holds the content of the save"));
        }
    }

    @Test
    @DisplayName("GDR-NFR-2: a second save of one draft waits for the first and finds the draft settled, not replayed")
    void secondSaveOfOneDraftWaitsForTheFirst(@Remote LocalRemote remote) throws Exception {
        var key = key();
        var target = StoreReferences.target(key);
        commit(remote.repository(), target, Map.of(word() + "-base", text()));
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var operations = new ScriptedRepositoryOperations();
        var author = Author.of(pseudonym());
        AtomicReference<SaveResult> second = new AtomicReference<>();

        try (var writer = open(storeRepository, operations)) {
            value(writer.draft(author, key, put(word(), text()), word()));
            var secondSave = new Thread(() -> second.set(writer.save(author, CLAIMS, key, Map.of(), word())));
            operations.beforeNextPush(() -> {
                secondSave.start();
                awaitParked(secondSave);
            });

            var first = writer.save(author, CLAIMS, key, Map.of(), word());
            secondSave.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

            var tip = Objects.requireNonNull(remote.repository().exactRef(target), "the branch exists").getObjectId();
            assertAll(
                    () -> assertFalse(secondSave.isAlive(), "the second save ends"),
                    () -> assertEquals(new SaveResult.Saved(tip.name()), first,
                            "the first save pushes the only commit of the branch"),
                    () -> assertEquals(new SaveResult.NotSaved(new StoreResult.PassedOn(GitOutcome.NOT_FOUND),
                            "draft: not found"), second.get(), "the second save finds the draft settled"),
                    () -> assertEquals(1, operations.pushes().size(), "the draft's changes are pushed once"));
        }
    }

    @Test
    @DisplayName("GDR-NFR-2: ten threads sharing one writer save different files of one branch and all succeed")
    void tenThreadsSharingOneWriter(@Remote LocalRemote remote) throws Exception {
        var key = key();
        var target = StoreReferences.target(key);
        Map<String, String> expected = new TreeMap<>(Map.of(word() + "-base", text()));
        commit(remote.repository(), target, expected);
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);

        try (var writer = open(storeRepository)) {
            List<Callable<SaveResult>> saves = new ArrayList<>();
            for (int index = 0; index < WRITERS; index++) {
                var author = Author.of(pseudonym());
                var path = word() + "-" + index;
                var content = text();
                expected.put(path, content);
                saves.add(() -> {
                    value(writer.draft(author, key, put(path, content), word()));
                    return writer.save(author, CLAIMS, key, Map.of(), word());
                });
            }

            var results = concurrently(saves);

            assertAll(
                    () -> assertTrue(results.stream().allMatch(SaveResult.Saved.class::isInstance),
                            () -> "every save succeeds: " + results),
                    () -> assertEquals(expected, files(remote.repository(), target), "the branch holds all files"));
        }
    }
}
