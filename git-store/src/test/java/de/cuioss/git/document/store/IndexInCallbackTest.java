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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The snapshot and the opening of an index from inside a callback of the writer, against a {@code git-testing} local
 * remote (GDR-STORE-9, GDR-STORE-3): a save step holds the locks of its save, the transport lock among them, so a
 * snapshot or an opening from there, which would wait for the one lock of the writer's indexes, is refused without
 * waiting; a contributor of another index, a listener and a caller outside every callback are not refused. The
 * cross-thread case of finding 4efeb8, a contributor that fetches while a save step snapshots another index, ends
 * instead of deadlocking.
 * <p>
 * Every test that runs another thread has one deadline from which every wait takes what is left; its threads are
 * daemons, and the writer is closed only when they ended. Names, ids and contents are generated; the detail of the
 * refusal is the specified vocabulary and kept as a literal constant.
 */
@EnableGeneratorController
@DisplayName("Index inside a callback (GDR-STORE-9)")
class IndexInCallbackTest {

    /** The deadline of a test that runs several threads. */
    private static final long TIMEOUT_SECONDS = 60;

    /** The fixed claims of every save. */
    private static final IdentityClaims CLAIMS = new IdentityClaims("https://issuer.example/realms/test",
            Instant.parse("2026-03-01T08:15:30Z"), "urn:example:acr:mfa", List.of("pwd", "otp"));

    /** The refusal of a snapshot or an opening inside a callback that holds a lock of the clone. */
    private static final StoreResult<?> REFUSED = StoreResult.of(StoreOutcome.REFUSED, StoreIndex.IN_CALLBACK);

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static String content() {
        return Generators.letterStrings(24, 40).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static StoreConfiguration configuration() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", word()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    /** The one deadline of a test that runs several threads, from which every wait takes what is left. */
    private record Deadline(long nanos) {

        static Deadline in(long seconds) {
            return new Deadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
        }

        long remainingNanos() {
            return Math.max(0, nanos - System.nanoTime());
        }

        boolean passed() {
            return System.nanoTime() >= nanos;
        }
    }

    /** The document of every test, on its working branch of the remote, with the commit it starts at. */
    private record Layout(DocumentKey key, String path, ObjectId start) {

        String target() {
            return StoreReferences.target(key);
        }
    }

    /** Writes the working branch of a generated per-document key, holding the document file. */
    private static Layout layout(Repository remote) {
        var collection = value(DocumentCollection.of("c" + word(), Kind.PER_DOCUMENT));
        var key = value(DocumentKey.of(collection, Ulid.next()));
        var path = collection.name() + "/" + key.id() + ".json";
        try (ObjectInserter inserter = remote.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            var entry = new DirCacheEntry(path);
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, content().getBytes(StandardCharsets.UTF_8)));
            builder.add(entry);
            builder.finish();
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "");
            commit.setTreeId(index.writeTree(inserter));
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = remote.updateRef(StoreReferences.target(key));
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            var result = update.update();
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED,
                    () -> "the fixture reference is written: " + result);
            return new Layout(key, path, id);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private StoreWriter open(LocalRemote remote) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var gitDir = temp.resolve(word() + ".git");
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
        return value(StoreWriter.open(storeRepository, gitDir, user, configuration(), List.of(),
                TestKeys.ed25519().source(), new ScriptedRepositoryOperations(), Clock.fixed(now, ZoneOffset.UTC)));
    }

    private static Map<DocumentCollection, IndexContributor<String>> contributors(Layout layout,
            IndexContributor<String> contributor) {
        return Map.of(layout.key().collection(), contributor);
    }

    /** Drafts a change of the document file, so the save moves the working branch and re-reads the document. */
    private static Author draft(StoreWriter writer, Layout layout) {
        var author = Author.of(word());
        value(writer.draft(author, layout.key(),
                List.of(new TreeEdit.Put(layout.path(), content().getBytes(StandardCharsets.UTF_8))), word()));
        return author;
    }

    /** Keeps an opened index for the test to close, and returns the result of the opening. */
    private static StoreResult<StoreIndex<String>> keep(List<StoreIndex<String>> opened,
            StoreResult<StoreIndex<String>> result) {
        @Nullable StoreIndex<String> index = result.value();
        if (index != null) {
            opened.add(index);
        }
        return result;
    }

    /** A contributor that counts its calls. */
    private static final class Counter implements IndexContributor<String> {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public String contribute(Document document) {
            calls.incrementAndGet();
            return document.key().id();
        }
    }

    /**
     * Finding 4efeb8, on one thread: without the refusal, the snapshot of the step was built while the step held the
     * transport lock, and the assertion "the snapshot of the step is refused" failed.
     */
    @Test
    @DisplayName("GDR-STORE-9: a save step that snapshots an index of its writer is refused at once; the save completes")
    void snapshotFromSaveStepIsRefused(@Remote LocalRemote remote) {
        var layout = layout(remote.repository());

        try (var writer = open(remote);
             var index = value(StoreIndex.open(writer, contributors(layout, new Counter())))) {
            var author = draft(writer, layout);
            AtomicReference<@Nullable StoreResult<StoreIndex.Snapshot<String>>> nested = new AtomicReference<>();

            var saved = writer.save(author, CLAIMS, layout.key(), Map.of(), word(), attempt -> {
                nested.set(index.snapshot(layout.start().name()));
                return new SaveStep.Edits(List.of());
            });

            assertAll(
                    () -> assertEquals(REFUSED, nested.get(), "the snapshot of the step is refused"),
                    () -> assertInstanceOf(SaveResult.Saved.class, saved, "the save completes"));
        }
    }

    /**
     * Finding 4efeb8, the opening: without the refusal, the index opened from the step registered its observer and
     * was refreshed by the save that moved the working branch, so the assertions "the opening from the step is
     * refused" and "the moved working branch refreshes no refused index" failed.
     */
    @Test
    @DisplayName("GDR-STORE-9: a save step that opens an index of its writer is refused; no observer is registered")
    void openFromSaveStepIsRefused(@Remote LocalRemote remote) {
        var layout = layout(remote.repository());
        var refusedContributor = new Counter();

        try (var writer = open(remote)) {
            var author = draft(writer, layout);
            AtomicReference<@Nullable StoreResult<StoreIndex<String>>> nested = new AtomicReference<>();

            var saved = writer.save(author, CLAIMS, layout.key(), Map.of(), word(), attempt -> {
                nested.set(StoreIndex.open(writer, contributors(layout, refusedContributor)));
                return new SaveStep.Edits(List.of());
            });
            int afterSave = refusedContributor.calls.get();
            var fetched = writer.fetch();

            assertAll(
                    () -> assertEquals(REFUSED, nested.get(), "the opening from the step is refused"),
                    () -> assertInstanceOf(SaveResult.Saved.class, saved, "the save completes"),
                    () -> assertTrue(fetched.isOk(), "the later fetch succeeds"),
                    () -> assertEquals(0, afterSave, "the moved working branch refreshes no refused index"),
                    () -> assertEquals(0, refusedContributor.calls.get(),
                            "the later operation calls no contributor of the refused opening"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-9: snapshot and open from a contributor, a listener and outside every callback are served")
    void snapshotAndOpenOutsideCloneLocksAreServed(@Remote LocalRemote remote) {
        var layout = layout(remote.repository());
        var other = new Counter();
        var armed = new AtomicBoolean();
        AtomicReference<@Nullable StoreIndex<String>> otherIndex = new AtomicReference<>();
        List<StoreResult<?>> fromContributor = Collections.synchronizedList(new ArrayList<>());
        List<StoreResult<?>> fromListener = Collections.synchronizedList(new ArrayList<>());
        List<StoreIndex<String>> opened = Collections.synchronizedList(new ArrayList<>());

        try (var writer = open(remote)) {
            IndexContributor<String> calling = document -> {
                if (armed.compareAndSet(true, false)) {
                    fromContributor.add(Objects.requireNonNull(otherIndex.get()).snapshot(layout.start().name()));
                    fromContributor.add(keep(opened, StoreIndex.open(writer, contributors(layout, new Counter()))));
                }
                return document.key().id();
            };
            var callingIndex = value(StoreIndex.open(writer, contributors(layout, calling)));
            var index = value(StoreIndex.open(writer, contributors(layout, other)));
            opened.add(callingIndex);
            opened.add(index);
            try (var subscription = writer.subscribe(layout.target(), (branch, commitId) -> {
                     fromListener.add(index.snapshot(layout.start().name()));
                     fromListener.add(keep(opened, StoreIndex.open(writer, contributors(layout, new Counter()))));
                 })) {
                Objects.requireNonNull(subscription);
                otherIndex.set(index);
                var author = draft(writer, layout);
                armed.set(true);

                var saved = writer.save(author, CLAIMS, layout.key(), Map.of(), word());
                var outsideSnapshot = index.snapshot(layout.start().name());
                var outsideOpen = keep(opened, StoreIndex.open(writer, contributors(layout, new Counter())));

                assertAll(
                        () -> assertInstanceOf(SaveResult.Saved.class, saved, "the save completes"),
                        () -> assertEquals(2, fromContributor.size(), "the contributor was called"),
                        () -> fromContributor.forEach(result -> assertTrue(result.isOk(),
                                () -> "served to a contributor of another index: " + result.detail())),
                        () -> assertEquals(2, fromListener.size(), "the listener was called"),
                        () -> fromListener.forEach(result -> assertTrue(result.isOk(),
                                () -> "served to a listener: " + result.detail())),
                        () -> assertTrue(outsideSnapshot.isOk(), "the snapshot outside every callback is built"),
                        () -> assertTrue(outsideOpen.isOk(), "the opening outside every callback succeeds"));
            } finally {
                List.copyOf(opened).forEach(StoreIndex::close);
            }
        }
    }

    /**
     * Finding 4efeb8: thread 1 runs a snapshot of index A, whose contributor, holding the one lock of the writer's
     * indexes, fetches once thread 2 is inside its save step; that step, holding the transport lock, snapshots index B.
     * Without the refusal, the step waited for the index lock while thread 1 waited for the transport lock, and the
     * assertion "both threads end within the deadline" failed.
     */
    @Test
    @DisplayName("GDR-STORE-9: a contributor that fetches while a save step snapshots another index does not deadlock")
    void fetchingContributorAndSnapshottingStepDoNotDeadlock(@Remote LocalRemote remote) throws Exception {
        var layout = layout(remote.repository());
        var deadline = Deadline.in(TIMEOUT_SECONDS);
        var armed = new AtomicBoolean();
        var contributorInside = new CountDownLatch(1);
        var stepInside = new CountDownLatch(1);
        AtomicReference<@Nullable Thread> saveThread = new AtomicReference<>();
        AtomicReference<@Nullable StoreResult<List<String>>> fetched = new AtomicReference<>();
        AtomicReference<@Nullable StoreResult<StoreIndex.Snapshot<String>>> snapshotA = new AtomicReference<>();
        AtomicReference<@Nullable StoreResult<StoreIndex.Snapshot<String>>> fromStep = new AtomicReference<>();
        AtomicReference<@Nullable SaveResult> saved = new AtomicReference<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        var writer = open(remote);
        // a deadlocked thread holds a lock of the clone, so the writer is closed only when both threads ended
        var bothEnded = new AtomicBoolean();

        try {
            IndexContributor<String> fetching = document -> {
                if (armed.compareAndSet(true, false)) {
                    contributorInside.countDown();
                    awaitStep(stepInside, saveThread, deadline);
                    fetched.set(writer.fetch());
                }
                return document.key().id();
            };
            var a = value(StoreIndex.open(writer, contributors(layout, fetching)));
            var b = value(StoreIndex.open(writer, contributors(layout, new Counter())));
            var author = draft(writer, layout);
            var snapshotting = new Thread(() -> snapshotA.set(a.snapshot(layout.start().name())), "snapshot-a");
            var saving = new Thread(() -> saved.set(writer.save(author, CLAIMS, layout.key(), Map.of(), word(),
                    attempt -> {
                        // the step holds the shared side and the transport lock of its save
                        stepInside.countDown();
                        fromStep.set(b.snapshot(layout.start().name()));
                        return new SaveStep.Edits(List.of());
                    })), "saving-step");
            for (Thread thread : List.of(snapshotting, saving)) {
                thread.setDaemon(true);
                thread.setUncaughtExceptionHandler((failed, e) -> failures.add(e));
            }
            saveThread.set(saving);
            armed.set(true);

            snapshotting.start();
            assertTrue(contributorInside.await(deadline.remainingNanos(), TimeUnit.NANOSECONDS),
                    "the contributor of A runs under the index lock");
            saving.start();
            TimeUnit.NANOSECONDS.timedJoin(saving, deadline.remainingNanos());
            TimeUnit.NANOSECONDS.timedJoin(snapshotting, deadline.remainingNanos());
            boolean ended = !saving.isAlive() && !snapshotting.isAlive();
            bothEnded.set(ended);

            assertAll(
                    () -> assertTrue(ended, "both threads end within the deadline, so nothing deadlocked"),
                    () -> assertEquals(REFUSED, fromStep.get(), "the snapshot of the step is refused"),
                    () -> assertInstanceOf(SaveResult.Saved.class, saved.get(), "the save completes"),
                    () -> assertTrue(Objects.requireNonNull(fetched.get(), "the contributor fetched").isOk(),
                            "the fetch of the contributor succeeds"),
                    () -> assertTrue(Objects.requireNonNull(snapshotA.get(), "snapshot A returned").isOk(),
                            "the snapshot of index A is built"),
                    () -> assertEquals(List.of(), failures, "no thread failed"));
            a.close();
            b.close();
        } finally {
            if (bothEnded.get()) {
                writer.close();
            }
        }
    }

    /**
     * Waits, bounded by the one deadline of the test, until the save thread is inside its step, parked on a lock or
     * ended.
     */
    private static void awaitStep(CountDownLatch stepInside, AtomicReference<@Nullable Thread> saveThread,
            Deadline deadline) {
        var thread = Objects.requireNonNull(saveThread.get(), "the save thread");
        try {
            while (stepInside.getCount() > 0 && thread.getState() != Thread.State.WAITING
                    && thread.getState() != Thread.State.TERMINATED && !deadline.passed()) {
                thread.join(1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
