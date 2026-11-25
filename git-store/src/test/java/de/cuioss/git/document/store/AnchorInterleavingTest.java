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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.git.document.core.RemotePolicy;
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
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The interleaving of a due check of the anchoring with the saves of the same clone, whose fetch mirrors the remote and
 * prunes (GDR-STORE-16, GDR-STORE-17): a save that starts while the due check pushes a checkpoint tag or an anchor note
 * waits on the transport lock, so its pruning fetch deletes no local checkpoint tag and overwrites no local notes commit
 * before the push; and with saves and due checks running concurrently no checkpoint tag or anchor note is lost and no
 * proof is requested twice.
 * <p>
 * {@link ScriptedRepositoryOperations} runs a concurrent save right before a push of the due check,
 * {@link ScriptedAnchorProvider} right before a proof is answered; {@link ThreadStates} waits until the save parks.
 * Every test has one deadline from which every wait takes what is left; a task still running at the deadline is
 * cancelled and its executor stopped with {@code shutdownNow} and {@code awaitTermination}, so no thread outlives it.
 * Names, ids and contents are generated; the reference layout is the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@DisplayName("Anchoring interleaved with saves (GDR-STORE-16, GDR-STORE-17)")
class AnchorInterleavingTest {

    /** The checkpoint interval of every test. */
    private static final Duration INTERVAL = Duration.ofHours(1);

    /** The deadline of a wait for a thread of a race scenario. */
    private static final long TIMEOUT_SECONDS = 60;

    /** The fixed claims of every save. */
    private static final IdentityClaims CLAIMS = new IdentityClaims("https://issuer.example/realms/test",
            Instant.parse("2026-03-01T08:15:30Z"), "urn:example:acr:mfa", List.of("pwd", "otp"));

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static StoreConfiguration configuration(String provider) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 5));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", word()));
        tree.put("anchor", Map.of("provider", provider, "checkpoint-interval", INTERVAL.toString(),
                "retry-interval", Duration.ofHours(12).toString()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    /** A clock that stands still until a test moves it. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant start) {
            now = start;
        }

        synchronized void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The writer under test with its doubles. */
    private record Fixture(StoreWriter writer, ScriptedRepositoryOperations operations,
    ScriptedAnchorProvider provider, MovableClock clock, Path gitDir) implements AutoCloseable {

        String notesRef() {
            return StoreReferences.anchorNotes(provider.id());
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var operations = new ScriptedRepositoryOperations();
        var provider = new ScriptedAnchorProvider("a" + word());
        var clock = new MovableClock(Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next()));
        var gitDir = temp.resolve(word() + ".git");
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(provider.id()), List.of(),
                new StoreProviders(TestKeys.ed25519().source(), new TestPersonDirectory(), provider), operations,
                clock));
        return new Fixture(writer, operations, provider, clock, gitDir);
    }

    /** Writes a commit holding one generated file onto a reference of a repository, forcing the reference. */
    private static ObjectId commit(Repository repository, String ref, ObjectId... parents) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(word(), FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "", Instant.ofEpochSecond(100_000), ZoneOffset.UTC);
            commit.setTreeId(inserter.insert(tree));
            commit.setParentIds(parents);
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

    /** A per-document repository whose working branch forked from {@code main}. */
    private static DocumentKey document(Repository remote, ObjectId main) throws IOException {
        var collection = value(DocumentCollection.of("c" + word(), Kind.PER_DOCUMENT));
        var key = value(DocumentKey.of(collection, Ulid.next()));
        commit(remote, StoreReferences.target(key), main);
        return key;
    }

    private static Author draft(StoreWriter writer, DocumentKey key) {
        var author = Author.of(word());
        value(writer.draft(author, key, List.of(new TreeEdit.Put(word(), word().getBytes(StandardCharsets.UTF_8))),
                word()));
        return author;
    }

    private static Map<String, ObjectId> refs(Repository repository, String prefix) throws IOException {
        Map<String, ObjectId> refs = new LinkedHashMap<>();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(prefix)) {
            refs.put(ref.getName(), ref.getObjectId());
        }
        return refs;
    }

    private static @Nullable ObjectId tip(Repository repository, String ref) throws IOException {
        Ref found = repository.exactRef(ref);
        return found == null ? null : found.getObjectId();
    }

    private static Set<ObjectId> annotated(Repository repository, String notesRef) throws IOException {
        Set<ObjectId> annotated = new HashSet<>();
        @Nullable ObjectId notesTip = tip(repository, notesRef);
        if (notesTip == null) {
            return annotated;
        }
        try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
            for (Note note : NoteMap.read(reader, walk.parseCommit(notesTip))) {
                annotated.add(note.copy());
            }
        }
        return annotated;
    }

    private static Repository clone(Fixture fixture) throws IOException {
        return new FileRepositoryBuilder().setGitDir(fixture.gitDir().toFile()).setMustExist(true).build();
    }

    /** The one deadline of a test that runs several threads, from which every wait takes what is left. */
    private record Deadline(long nanos) {

        static Deadline in(long seconds) {
            return new Deadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds));
        }

        long remainingNanos() {
            return Math.max(0, nanos - System.nanoTime());
        }
    }

    /**
     * Ends the tasks of an executor by the deadline: an orderly shutdown and a wait for what is left of the deadline;
     * a task still running then is cancelled and interrupted ({@code shutdownNow}), and the executor is awaited again,
     * so no thread outlives the test.
     *
     * @return whether every task ended within the deadline
     */
    private static boolean stop(ExecutorService executor, List<Future<?>> tasks, Deadline deadline)
            throws InterruptedException {
        executor.shutdown();
        boolean ended = executor.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
        if (!ended) {
            tasks.forEach(task -> task.cancel(true));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the cancelled tasks end once interrupted");
        }
        return ended;
    }

    /** A save on its own thread; its result or failure is kept for the test. */
    private static final class SaveThread {

        private final AtomicReference<@Nullable SaveResult> result = new AtomicReference<>();
        private final AtomicReference<@Nullable Throwable> failure = new AtomicReference<>();
        private final Thread thread;

        SaveThread(StoreWriter writer, Author author, DocumentKey key) {
            thread = new Thread(() -> {
                try {
                    result.set(writer.save(author, CLAIMS, key, Map.of(), word()));
                }
                // the failure of the save is kept for the test to assert, whatever its type
                // cui-rewrite:disable InvalidExceptionUsageRecipe
                catch (RuntimeException | AssertionError e) {
                    failure.set(e);
                }
            });
        }

        /**
         * Starts the save and waits, with what is left of the test's one deadline, until it parks on a lock or ends;
         * whether it was still running then.
         */
        boolean startAndAwaitParked(Deadline deadline) {
            thread.start();
            try {
                ThreadStates.awaitParkedOrEnded(thread, deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return thread.isAlive();
        }

        /** Waits for the save with what is left of the test's one deadline. */
        SaveResult join(Deadline deadline) throws InterruptedException {
            TimeUnit.NANOSECONDS.timedJoin(thread, deadline.remainingNanos());
            assertFalse(thread.isAlive(), "the save ends within the deadline");
            assertNull(failure.get(), "the save threw nothing");
            return Objects.requireNonNull(result.get(), "the save returned");
        }

        /** Interrupts a save still running when the test ends and waits for it, so no thread outlives the test. */
        void stop() throws InterruptedException {
            if (thread.isAlive()) {
                thread.interrupt();
                thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            }
        }
    }

    @Test
    @DisplayName("GDR-STORE-16: a step closing the writer while a due check waits for the transport lock does not deadlock")
    void stepClosingDuringDueCheckDoesNotDeadlock(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var key = document(repository, commit(repository, StoreReferences.MAIN));
        var deadline = Deadline.in(TIMEOUT_SECONDS);
        var stepEntered = new CountDownLatch(1);
        var dueCheckParked = new CountDownLatch(1);
        var closeReturned = new AtomicBoolean();
        AtomicReference<@Nullable SaveResult> saved = new AtomicReference<>();
        AtomicReference<@Nullable StoreResult<StoreWriter.Anchoring>> anchored = new AtomicReference<>();
        var fixture = open(remote);
        // a deadlocked due check holds the anchoring lock, so the fixture is closed only when both threads ended
        var bothEnded = new AtomicBoolean();

        try {
            var writer = fixture.writer();
            var author = draft(writer, key);
            var save = new Thread(() -> saved.set(writer.save(author, CLAIMS, key, Map.of(), word(), attempt -> {
                // the step holds the shared side and the transport lock of its save
                stepEntered.countDown();
                try {
                    if (dueCheckParked.await(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                        writer.close();
                        closeReturned.set(true);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new SaveStep.Edits(List.of());
            })), "closing-step");
            // the due check takes the anchoring lock and then waits for the transport lock the step holds
            var dueCheck = new Thread(() -> anchored.set(writer.anchor()), "due-check");
            save.setDaemon(true);
            dueCheck.setDaemon(true);

            save.start();
            assertTrue(stepEntered.await(deadline.remainingNanos(), TimeUnit.NANOSECONDS), "the step runs");
            dueCheck.start();
            ThreadStates.awaitParkedOrEnded(dueCheck, deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            dueCheckParked.countDown();
            TimeUnit.NANOSECONDS.timedJoin(save, deadline.remainingNanos());
            TimeUnit.NANOSECONDS.timedJoin(dueCheck, deadline.remainingNanos());
            boolean ended = !save.isAlive() && !dueCheck.isAlive();
            bothEnded.set(ended);
            var stillOpen = ended ? writer.anchor() : null;

            assertAll(
                    () -> assertTrue(ended, "both threads end within the deadline, so nothing deadlocked"),
                    () -> assertTrue(closeReturned.get(), "the close from the step returned"),
                    () -> assertInstanceOf(SaveResult.Saved.class, saved.get(), "the save completes"),
                    () -> assertTrue(Objects.requireNonNull(anchored.get()).isOk(), "the due check completes"),
                    () -> assertTrue(Objects.requireNonNull(stillOpen).isOk(), "the writer stays open"));
        } finally {
            if (bothEnded.get()) {
                fixture.close();
            }
        }
    }

    /**
     * The deadlock of finding 230fcc: the due check of another thread holds the anchoring lock and waits for the
     * transport lock the step holds, and the step calls {@code anchor()}. Without the refusal before the anchoring lock
     * is requested, the step waited for the anchoring lock and neither thread ended.
     */
    @Test
    @DisplayName("GDR-STORE-16: anchor() from a step while a due check waits for the transport lock is refused at once")
    void stepAnchoringDuringDueCheckIsRefused(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var key = document(repository, commit(repository, StoreReferences.MAIN));
        var deadline = Deadline.in(TIMEOUT_SECONDS);
        var stepEntered = new CountDownLatch(1);
        var dueCheckParked = new CountDownLatch(1);
        AtomicReference<@Nullable StoreResult<StoreWriter.Anchoring>> nested = new AtomicReference<>();
        AtomicReference<@Nullable SaveResult> saved = new AtomicReference<>();
        AtomicReference<@Nullable StoreResult<StoreWriter.Anchoring>> anchored = new AtomicReference<>();
        var fixture = open(remote);
        // a deadlocked due check holds the anchoring lock, so the fixture is closed only when both threads ended
        var bothEnded = new AtomicBoolean();

        try {
            var writer = fixture.writer();
            var author = draft(writer, key);
            var save = new Thread(() -> saved.set(writer.save(author, CLAIMS, key, Map.of(), word(), attempt -> {
                // the step holds the shared side and the transport lock of its save
                stepEntered.countDown();
                try {
                    if (dueCheckParked.await(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                        nested.set(writer.anchor());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return new SaveStep.Edits(List.of());
            })), "anchoring-step");
            // the due check takes the anchoring lock and then waits for the transport lock the step holds
            var dueCheck = new Thread(() -> anchored.set(writer.anchor()), "due-check");
            save.setDaemon(true);
            dueCheck.setDaemon(true);

            save.start();
            assertTrue(stepEntered.await(deadline.remainingNanos(), TimeUnit.NANOSECONDS), "the step runs");
            dueCheck.start();
            ThreadStates.awaitParkedOrEnded(dueCheck, deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            dueCheckParked.countDown();
            TimeUnit.NANOSECONDS.timedJoin(save, deadline.remainingNanos());
            TimeUnit.NANOSECONDS.timedJoin(dueCheck, deadline.remainingNanos());
            boolean ended = !save.isAlive() && !dueCheck.isAlive();
            bothEnded.set(ended);

            assertAll(
                    () -> assertTrue(ended, "both threads end within the deadline, so nothing deadlocked"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, AnchorEngine.ANCHOR_IN_CALLBACK),
                            nested.get(), "the anchor() of the step is refused"),
                    () -> assertInstanceOf(SaveResult.Saved.class, saved.get(), "the save completes"),
                    () -> assertTrue(Objects.requireNonNull(anchored.get()).isOk(), "the due check completes"));
        } finally {
            if (bothEnded.get()) {
                fixture.close();
            }
        }
    }

    @Test
    @DisplayName("GDR-STORE-16, GDR-STORE-17: a save during the tag push and the note push waits; nothing is lost")
    void saveDuringPushesWaitsAndLosesNothing(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN);
        var firstKey = document(repository, main);
        var secondKey = document(repository, main);

        try (var fixture = open(remote)) {
            var deadline = Deadline.in(TIMEOUT_SECONDS);
            var firstSave = new SaveThread(fixture.writer(), draft(fixture.writer(), firstKey), firstKey);
            var secondSave = new SaveThread(fixture.writer(), draft(fixture.writer(), secondKey), secondKey);
            var firstWaited = new AtomicBoolean();
            var secondWaited = new AtomicBoolean();
            var firstResult = new AtomicReference<@Nullable SaveResult>();
            fixture.operations().beforeNextPush(() -> firstWaited.set(firstSave.startAndAwaitParked(deadline)));
            fixture.provider().beforeNextObtain(() -> {
                try {
                    firstResult.set(firstSave.join(deadline));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                fixture.operations().beforeNextPush(() -> secondWaited.set(secondSave.startAndAwaitParked(deadline)));
            });

            StoreWriter.Anchoring anchoring;
            SaveResult second;
            StoreWriter.Anchoring again;
            try {
                anchoring = value(fixture.writer().anchor());
                second = secondSave.join(deadline);
                again = value(fixture.writer().anchor());
            } finally {
                firstSave.stop();
                secondSave.stop();
            }

            var checkpoint = Objects.requireNonNull(tip(repository, StoreReferences.checkpoint(1)), "checkpoint 1");
            ObjectId localTag;
            ObjectId localNotes;
            try (Repository clone = clone(fixture)) {
                localTag = tip(clone, StoreReferences.checkpoint(1));
                localNotes = tip(clone, fixture.notesRef());
            }
            var remoteNotes = tip(repository, fixture.notesRef());
            assertAll(
                    () -> assertTrue(firstWaited.get(), "the first save waited while the tag was pushed"),
                    () -> assertInstanceOf(SaveResult.Saved.class, firstResult.get(), "and was saved"),
                    () -> assertTrue(secondWaited.get(), "the second save waited while the note was pushed"),
                    () -> assertInstanceOf(SaveResult.Saved.class, second, "and was saved"),
                    () -> assertEquals(List.of(checkpoint.name()), anchoring.anchored(), "the checkpoint anchored"),
                    () -> assertEquals(checkpoint, localTag, "the local checkpoint tag survived the pruning fetch"),
                    () -> assertEquals(remoteNotes, localNotes, "the local notes commit is the pushed one"),
                    () -> assertEquals(Set.of(checkpoint), annotated(repository, fixture.notesRef()),
                            "the note is on the remote"),
                    () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of()), again,
                            "nothing left for the next due check"),
                    () -> assertEquals(List.of(checkpoint), fixture.provider().subjects(),
                            "the proof was requested once"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-16, GDR-STORE-17: concurrent saves and due checks lose no tag or note and request no proof twice")
    void concurrentSavesAndDueChecks(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN);
        List<DocumentKey> keys = List.of(document(repository, main), document(repository, main));
        int savesPerWriter = 4;
        int dueChecks = 6;

        try (var fixture = open(remote)) {
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            var deadline = Deadline.in(TIMEOUT_SECONDS);
            ExecutorService executor = Executors.newFixedThreadPool(keys.size() + 1);
            List<Future<?>> tasks = new ArrayList<>();
            boolean ended;
            try {
                for (DocumentKey key : keys) {
                    tasks.add(executor.submit(() -> {
                        try {
                            for (int i = 0; i < savesPerWriter; i++) {
                                var author = draft(fixture.writer(), key);
                                assertInstanceOf(SaveResult.Saved.class,
                                        fixture.writer().save(author, CLAIMS, key, Map.of(), word()), "saved");
                            }
                        }
                        // the failure of a writer is kept for the test to assert, whatever its type
                        // cui-rewrite:disable InvalidExceptionUsageRecipe
                        catch (RuntimeException | AssertionError e) {
                            failures.add(e);
                        }
                    }));
                }
                tasks.add(executor.submit(() -> {
                    try {
                        for (int i = 0; i < dueChecks; i++) {
                            fixture.clock().advance(INTERVAL);
                            value(fixture.writer().anchor());
                        }
                    }
                    // the failure of a due check is kept for the test to assert, whatever its type
                    // cui-rewrite:disable InvalidExceptionUsageRecipe
                    catch (RuntimeException | AssertionError e) {
                        failures.add(e);
                    }
                }));
            } finally {
                ended = stop(executor, tasks, deadline);
            }
            assertTrue(ended, "every save and due check ended within the one deadline");
            assertEquals(List.of(), failures, "no save and no due check failed");
            fixture.clock().advance(INTERVAL);
            value(fixture.writer().anchor());
            var settled = value(fixture.writer().anchor());

            var checkpoints = refs(repository, StoreReferences.CHECKPOINTS);
            List<ObjectId> chain = new ArrayList<>();
            for (int n = 1; n <= checkpoints.size(); n++) {
                chain.add(Objects.requireNonNull(checkpoints.get(StoreReferences.checkpoint(n)),
                        "the checkpoint numbers have no gap"));
            }
            List<List<ObjectId>> parents = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repository)) {
                for (ObjectId id : chain) {
                    RevCommit commit = walk.parseCommit(id);
                    parents.add(List.of(commit.getParents()));
                }
            }
            Map<String, ObjectId> localCheckpoints;
            Map<String, ObjectId> kept;
            try (Repository clone = clone(fixture)) {
                localCheckpoints = refs(clone, StoreReferences.CHECKPOINTS);
                kept = refs(clone, StoreReferences.ANCHORING);
            }
            var subjects = fixture.provider().subjects();
            assertAll(
                    () -> assertFalse(chain.isEmpty(), "at least one checkpoint"),
                    () -> assertEquals(checkpoints, localCheckpoints, "the clone holds every checkpoint tag"),
                    () -> assertEquals(Set.copyOf(chain), annotated(repository, fixture.notesRef()),
                            "every checkpoint carries its note"),
                    () -> assertEquals(subjects.size(), Set.copyOf(subjects).size(), "no proof requested twice"),
                    () -> assertEquals(Set.copyOf(chain), Set.copyOf(subjects), "each checkpoint requested"),
                    () -> assertEquals(List.of(), settled.pending(), "nothing pending"),
                    () -> assertEquals(Map.of(), kept, "no proof or checkpoint left kept"),
                    () -> {
                        for (int i = 0; i < chain.size(); i++) {
                            assertEquals(i == 0 ? List.of() : List.of(chain.get(i - 1)), parents.get(i),
                                    "each checkpoint follows the previous one");
                        }
                    });
        }
    }
}
