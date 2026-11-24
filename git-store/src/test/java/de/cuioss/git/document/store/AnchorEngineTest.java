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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
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
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The anchoring engine through {@link StoreWriter} against a {@code git-testing} local remote (GDR-STORE-15,
 * GDR-STORE-16): without an active provider nothing is anchored; the commit of every release tag is a subject unless
 * {@code anchor.releases} is {@code false}; a checkpoint is due only once {@code anchor.checkpoint-interval} has passed
 * and a listed reference changed, and checkpoint tags and anchor notes never make one due; a failing, throwing,
 * refusing or blocking provider leaves every save, release and fetch successful; and a due check runs after a save, a
 * release and a fetch and on the retry schedule of {@link StoreWriter#anchorPeriodically}.
 * <p>
 * {@link ScriptedAnchorProvider} records every request; {@link ScriptedRepositoryOperations} records every fetch and
 * push. Names, ids, pseudonyms, paths and contents are generated; the reference layout, the configuration keys and the
 * outcomes are the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("Anchoring engine (GDR-STORE-15, GDR-STORE-16)")
class AnchorEngineTest {

    /** The checkpoint interval of every test. */
    private static final Duration INTERVAL = Duration.ofHours(1);

    /** The retry interval of every test that does not test the schedule: longer than any test runs. */
    private static final Duration NO_RETRY = Duration.ofHours(12);

    /** The deadline of a wait for a due check handed to an executor. */
    private static final Duration WAIT = Duration.ofSeconds(30);

    /** The fixed claims of every save and release. */
    private static final IdentityClaims CLAIMS = new IdentityClaims("https://issuer.example/realms/test",
            Instant.parse("2026-03-01T08:15:30Z"), "urn:example:acr:mfa", List.of("pwd", "otp"));

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 16, 24).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static DocumentKey document() {
        var collection = value(DocumentCollection.of("c" + word(), Kind.PER_DOCUMENT));
        return value(DocumentKey.of(collection, Ulid.next()));
    }

    private static StoreConfiguration configuration(@Nullable String provider, @Nullable Boolean releases,
            Duration retry) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", word()));
        Map<String, Object> anchor = new LinkedHashMap<>();
        if (provider != null) {
            anchor.put("provider", provider);
        }
        anchor.put("checkpoint-interval", INTERVAL.toString());
        anchor.put("retry-interval", retry.toString());
        if (releases != null) {
            anchor.put("releases", releases);
        }
        tree.put("anchor", anchor);
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    /** A clock that stands still until a test moves it. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant start) {
            now = start;
        }

        void advance(Duration duration) {
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
    @Nullable ScriptedAnchorProvider provider, MovableClock clock) implements AutoCloseable {

        ScriptedAnchorProvider anchors() {
            return Objects.requireNonNull(provider, "the fixture has a provider");
        }

        StoreWriter.Anchoring anchor() {
            return value(writer.anchor());
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote, @Nullable ScriptedAnchorProvider provider, @Nullable Boolean releases,
            Duration retry) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var operations = new ScriptedRepositoryOperations();
        var clock = new MovableClock(Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next()));
        var providers = new StoreProviders(TestKeys.ed25519().source(), new TestPersonDirectory(), provider);
        var writer = value(StoreWriter.open(storeRepository, temp.resolve(word() + ".git"), user,
                configuration(provider == null ? null : provider.id(), releases, retry), List.of(), providers,
                operations, clock));
        return new Fixture(writer, operations, provider, clock);
    }

    private Fixture open(LocalRemote remote) {
        return open(remote, new ScriptedAnchorProvider("a" + word()), null, NO_RETRY);
    }

    /** Writes a commit holding one generated file onto a reference of a repository, forcing the reference. */
    private static ObjectId commit(Repository repository, String ref, ObjectId... parents) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(word(), FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(pseudonym(), "");
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
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.FAST_FORWARD, () -> "the fixture reference is written: " + result);
            return id;
        }
    }

    /** A per-document repository whose working branch forked from {@code main}, ready to release and to save. */
    private static DocumentKey forked(Repository remote) throws IOException {
        var key = document();
        var main = commit(remote, StoreReferences.MAIN);
        commit(remote, StoreReferences.target(key), main);
        return key;
    }

    private static SaveResult.Saved save(StoreWriter writer, DocumentKey key) {
        var author = Author.of(pseudonym());
        value(writer.draft(author, key,
                List.of(new TreeEdit.Put(word(), word().getBytes(StandardCharsets.UTF_8))), word()));
        return assertInstanceOf(SaveResult.Saved.class, writer.save(author, CLAIMS, key, Map.of(), word()),
                "the save is saved");
    }

    private static Map<String, ObjectId> refs(Repository repository, String prefix) throws IOException {
        Map<String, ObjectId> refs = new LinkedHashMap<>();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(prefix)) {
            refs.put(ref.getName(), ref.getObjectId());
        }
        return refs;
    }

    /** The annotated commits of a notes reference with their decoded note. */
    private static Map<ObjectId, String> notes(Repository repository, String notesRef) throws IOException {
        Map<ObjectId, String> notes = new LinkedHashMap<>();
        Ref ref = repository.exactRef(notesRef);
        if (ref == null) {
            return notes;
        }
        try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
            for (Note note : NoteMap.read(reader, walk.parseCommit(ref.getObjectId()))) {
                notes.put(note.copy(), new String(reader.open(note.getData()).getBytes(), StandardCharsets.US_ASCII));
            }
        }
        return notes;
    }

    private static List<ObjectId> parents(Repository repository, ObjectId id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(id);
            return List.of(commit.getParents());
        }
    }

    private static ObjectId checkpoint(Repository repository, int number) throws IOException {
        Ref ref = repository.exactRef(StoreReferences.checkpoint(number));
        assertNotNull(ref, () -> "checkpoint " + number + " exists");
        return ref.getObjectId();
    }

    /** An observer of the events of a clone that records every call. */
    private static final class RecordingObserver implements StoreEvents.Observer {

        private final List<List<StoreEvents.Update>> moved = new CopyOnWriteArrayList<>();
        private final AtomicInteger unchanged = new AtomicInteger();

        @Override
        public void moved(List<StoreEvents.Update> updates) {
            moved.add(List.copyOf(updates));
        }

        @Override
        public void unchanged() {
            unchanged.incrementAndGet();
        }

        List<List<StoreEvents.Update>> moved() {
            return List.copyOf(moved);
        }

        int unchangedCalls() {
            return unchanged.get();
        }
    }

    /** Registers a recording observer on the events of the writer's clone. */
    private static RecordingObserver observe(StoreWriter writer) {
        var observer = new RecordingObserver();
        writer.storeClone().events().register(observer);
        return observer;
    }

    private static ScheduledExecutorService executor() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    private static void stop(ScheduledExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor ends");
    }

    @Test
    @DisplayName("GDR-STORE-16: a provider closing the writer is refused, the due check completes, a later close closes")
    void providerClosingWriterIsRefused(@Remote LocalRemote remote) throws Exception {
        var key = forked(remote.repository());
        try (var fixture = open(remote)) {
            save(fixture.writer(), key);
            fixture.clock().advance(INTERVAL.plusMinutes(1));
            fixture.anchors().beforeNextObtain(() -> fixture.writer().close());

            var anchoring = fixture.anchor();
            var open = fixture.writer().anchor();
            fixture.writer().close();
            var closed = fixture.writer().anchor();

            assertAll(
                    () -> assertFalse(fixture.anchors().calls().isEmpty(), "the provider was asked for a proof"),
                    () -> assertFalse(anchoring.anchored().isEmpty(), "the due check completes and pushes its note"),
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                            GitStoreLogMessages.WARN.CLOSE_REFUSED.resolveIdentifierString()),
                    () -> assertTrue(open.isOk(), () -> "the writer stays open: " + open.detail()),
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, "anchor: the writer is closed"), closed,
                            "a close after the due check closes the writer"));
        }
    }

    @Nested
    @DisplayName("Activation (GDR-STORE-15)")
    class Activation {

        @Test
        @DisplayName("GDR-STORE-15: without an active provider nothing is anchored, fetched or pushed")
        void nothingAnchoredWithoutProvider(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());

            try (var fixture = open(remote, null, null, NO_RETRY);
                 var legacy = value(StoreWriter.open(new StoreRepository(word(), remote.uri().toString(), word(), null),
                         temp.resolve(word() + ".git"), new TechnicalUser(word(), null,
                                 new UsernamePasswordCredentialsProvider(word(), word())),
                         configuration(null, null, NO_RETRY), List.of(), TestKeys.ed25519().source(),
                         new ScriptedRepositoryOperations(), Clock.systemUTC()))) {
                save(fixture.writer(), key);
                int fetches = fixture.operations().fetches().size();
                int pushes = fixture.operations().pushes().size();

                var anchoring = fixture.anchor();
                var legacyAnchoring = value(legacy.anchor());

                assertAll(
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of()), anchoring,
                                "nothing anchored, nothing pending"),
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of()), legacyAnchoring,
                                "the writer opened with a signing key source alone anchors nothing either"),
                        () -> assertEquals(fetches, fixture.operations().fetches().size(), "no fetch"),
                        () -> assertEquals(pushes, fixture.operations().pushes().size(), "no push"),
                        () -> assertTrue(refs(remote.repository(), StoreReferences.CHECKPOINTS).isEmpty(),
                                "no checkpoint tag"),
                        () -> assertTrue(refs(remote.repository(), StoreReferences.ANCHOR_NOTES).isEmpty(),
                                "no anchor notes"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-15: the commit of a release tag is anchored together with the first checkpoint")
        void releaseCommitsAnchored(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());

            try (var fixture = open(remote)) {
                var release = value(fixture.writer().release(Author.of(pseudonym()), CLAIMS, key));
                var releaseCommit = ObjectId.fromString(release.commitId());

                var anchoring = fixture.anchor();

                var checkpoint = checkpoint(remote.repository(), 1);
                var notesRef = StoreReferences.anchorNotes(fixture.anchors().id());
                var notes = notes(remote.repository(), notesRef);
                assertAll(
                        () -> assertEquals(Set.of(releaseCommit.name(), checkpoint.name()),
                                Set.copyOf(anchoring.anchored()), "the release commit and the checkpoint"),
                        () -> assertEquals(List.of(), anchoring.pending(), "nothing pending"),
                        () -> assertEquals(Set.of(releaseCommit, checkpoint), Set.copyOf(fixture.anchors().subjects()),
                                "the provider was asked for both"),
                        () -> assertEquals(2, fixture.anchors().calls().size(), "once each"),
                        () -> assertEquals(Set.of(releaseCommit, checkpoint), notes.keySet(),
                                "both carry a note on the remote"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-15: with anchor.releases false the commit of a release tag is no subject")
        void releasesFalseAnchorsCheckpointsOnly(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var provider = new ScriptedAnchorProvider("a" + word());

            try (var fixture = open(remote, provider, false, NO_RETRY)) {
                var release = value(fixture.writer().release(Author.of(pseudonym()), CLAIMS, key));

                var anchoring = fixture.anchor();

                var checkpoint = checkpoint(remote.repository(), 1);
                var notes = notes(remote.repository(), StoreReferences.anchorNotes(provider.id()));
                assertAll(
                        () -> assertEquals(List.of(checkpoint.name()), anchoring.anchored(), "the checkpoint only"),
                        () -> assertEquals(List.of(checkpoint), provider.subjects(), "the release was not asked for"),
                        () -> assertFalse(notes.containsKey(ObjectId.fromString(release.commitId())),
                                "the release commit carries no note"));
            }
        }
    }

    @Nested
    @DisplayName("Checkpoints due (GDR-STORE-16)")
    class Due {

        @Test
        @DisplayName("GDR-STORE-16: a checkpoint is due only once the interval has passed and a listed reference changed")
        void dueOnlyAfterIntervalAndChange(@Remote LocalRemote remote) throws Exception {
            var main = commit(remote.repository(), StoreReferences.MAIN);

            try (var fixture = open(remote)) {
                var first = fixture.anchor();
                var one = checkpoint(remote.repository(), 1);
                commit(remote.repository(), StoreReferences.MAIN, main);

                var changedEarly = fixture.anchor();
                fixture.clock().advance(INTERVAL.minusSeconds(1));
                var changedJustBefore = fixture.anchor();
                var countBefore = refs(remote.repository(), StoreReferences.CHECKPOINTS).size();
                fixture.clock().advance(Duration.ofSeconds(1));
                var changedAtInterval = fixture.anchor();
                var two = checkpoint(remote.repository(), 2);
                fixture.clock().advance(INTERVAL.multipliedBy(3));
                var unchanged = fixture.anchor();

                assertAll(
                        () -> assertEquals(List.of(one.name()), first.anchored(), "the first checkpoint, at once"),
                        () -> assertEquals(List.of(), changedEarly.anchored(), "changed, but within the interval"),
                        () -> assertEquals(List.of(), changedJustBefore.anchored(), "one second before the interval"),
                        () -> assertEquals(1, countBefore, "still one checkpoint"),
                        () -> assertEquals(List.of(two.name()), changedAtInterval.anchored(),
                                "due once the interval has passed"),
                        () -> assertEquals(List.of(one), parents(remote.repository(), two),
                                "the second checkpoint follows the first"),
                        () -> assertEquals(List.of(), unchanged.anchored(), "the interval passed, nothing changed"),
                        () -> assertEquals(2, refs(remote.repository(), StoreReferences.CHECKPOINTS).size(),
                                "two checkpoints"),
                        () -> assertEquals(List.of(one, two), fixture.anchors().subjects(), "each asked for once"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-16: checkpoint tags and anchor notes never make a checkpoint due, another notes reference does")
        void checkpointTagsAndAnchorNotesNeverMakeOneDue(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);

            try (var fixture = open(remote)) {
                fixture.anchor();
                assertEquals(1, notes(remote.repository(), StoreReferences.anchorNotes(fixture.anchors().id())).size(),
                        "the first checkpoint carries its note");
                fixture.clock().advance(INTERVAL.multipliedBy(2));

                var afterOwnReferences = fixture.anchor();
                commit(remote.repository(), "refs/notes/" + word());
                var afterOtherNotes = fixture.anchor();

                assertAll(
                        () -> assertEquals(List.of(), afterOwnReferences.anchored(),
                                "the checkpoint tag and the anchor note made nothing due"),
                        () -> assertEquals(1, afterOtherNotes.anchored().size(), "a listed notes reference did"),
                        () -> assertEquals(2, refs(remote.repository(), StoreReferences.CHECKPOINTS).size(),
                                "two checkpoints"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-16: a newest checkpoint tag that points at no commit makes no checkpoint and ends the due check incomplete")
        void untargetedCheckpointMakesNone(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);
            ObjectId blob;
            try (ObjectInserter inserter = remote.repository().newObjectInserter()) {
                blob = inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8));
                inserter.flush();
            }
            RefUpdate update = remote.repository().updateRef(StoreReferences.checkpoint(1));
            update.setNewObjectId(blob);
            assertEquals(RefUpdate.Result.NEW, update.update(), "the fixture tag is written");

            try (var fixture = open(remote)) {
                var anchoring = fixture.writer().anchor();

                assertAll(
                        () -> assertEquals(StoreOutcome.INCOMPLETE, anchoring.outcome(),
                                "the due check ends incomplete"),
                        () -> assertEquals(Set.of(StoreReferences.checkpoint(1)),
                                refs(remote.repository(), StoreReferences.CHECKPOINTS).keySet(),
                                "no checkpoint 2 was made"),
                        () -> assertEquals(List.of(), fixture.anchors().calls(), "the provider was not asked"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG,
                                "The newest checkpoint tag points at no commit; no checkpoint is made"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-16: without any checkpoint tag the first checkpoint is made without a parent")
        void firstCheckpointHasNoParent(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);

            try (var fixture = open(remote)) {
                var anchoring = fixture.anchor();

                var one = checkpoint(remote.repository(), 1);
                assertAll(
                        () -> assertEquals(List.of(one.name()), anchoring.anchored(), "checkpoint 1 is anchored"),
                        () -> assertEquals(List.of(), parents(remote.repository(), one), "it has no parent"));
            }
        }
    }

    @Nested
    @DisplayName("A failing provider (GDR-STORE-15)")
    class Failing {

        @Test
        @DisplayName("GDR-STORE-15: with the provider unavailable a save, a release and a fetch succeed; the subjects stay pending")
        void unavailableProviderLeavesOperationsSuccessful(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var executor = executor();
            try (var fixture = open(remote)) {
                fixture.anchors().always(StoreOutcome.UNAVAILABLE);
                var schedule = fixture.writer().anchorPeriodically(executor);

                var saved = save(fixture.writer(), key);
                var released = fixture.writer().release(Author.of(pseudonym()), CLAIMS, key);
                var fetched = fixture.writer().fetch();
                assertTrue(fixture.anchors().awaitCalls(1, WAIT), "a due check ran after the operations");
                // no handed-over due check may run once the provider recovers: only the due checks below count
                schedule.cancel();
                executor.shutdown();
                assertTrue(executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor ends");
                var pending = fixture.anchor();
                var notesRef = StoreReferences.anchorNotes(fixture.anchors().id());
                var noteBefore = remote.repository().exactRef(notesRef);
                fixture.anchors().recover();
                var retried = fixture.anchor();

                var releaseCommit = Objects.requireNonNull(released.value(), "the release").commitId();
                assertAll(
                        () -> assertFalse(saved.commitId().isEmpty(), "the save is saved"),
                        () -> assertTrue(released.isOk(), "the release succeeds"),
                        () -> assertTrue(fetched.isOk(), "the fetch succeeds"),
                        () -> assertEquals(List.of(), pending.anchored(), "nothing anchored"),
                        () -> assertTrue(pending.pending().contains(releaseCommit), "the release commit is pending"),
                        () -> assertNull(noteBefore, "no note on the remote"),
                        () -> assertEquals(Set.copyOf(pending.pending()), Set.copyOf(retried.anchored()),
                                "the next due check anchors every pending subject"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.ANCHORING_PENDING.resolveIdentifierString()));
            } finally {
                stop(executor);
            }
        }

        @Test
        @DisplayName("GDR-STORE-15: a throwing or refusing provider leaves the subject pending for the next due check")
        void throwingOrRefusingProviderLeavesSubjectPending(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);

            try (var fixture = open(remote)) {
                fixture.anchors().throwNext().fail(StoreOutcome.REFUSED);

                var thrown = fixture.anchor();
                var refused = fixture.anchor();
                var anchored = fixture.anchor();

                var checkpoint = checkpoint(remote.repository(), 1).name();
                assertAll(
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of(checkpoint)), thrown,
                                "a throwing provider leaves it pending"),
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of(checkpoint)), refused,
                                "a refusing provider leaves it pending"),
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(checkpoint), List.of()), anchored,
                                "the third due check anchors it"),
                        () -> assertEquals(3, fixture.anchors().calls().size(), "asked three times"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-15: an Error of the provider leaves the subject pending; a VirtualMachineError propagates")
        void errorOfProviderIsIsolated(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);

            try (var fixture = open(remote)) {
                fixture.anchors().errorNext(new AssertionError(word())).errorNext(new StackOverflowError());

                var failed = fixture.anchor();
                var writer = fixture.writer();
                var exhausted = assertThrows(StackOverflowError.class, writer::anchor,
                        "a VirtualMachineError is not isolated");
                var anchored = fixture.anchor();

                var checkpoint = checkpoint(remote.repository(), 1).name();
                assertAll(
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of(checkpoint)), failed,
                                "an AssertionError of the provider leaves the subject pending"),
                        () -> assertInstanceOf(VirtualMachineError.class, exhausted, "a JVM error"),
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(checkpoint), List.of()), anchored,
                                "the next due check anchors it"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG,
                                AssertionError.class.getName()));
            }
        }

        @Test
        @DisplayName("GDR-STORE-15: a proof whose base64 note exceeds the blob bound is refused, never kept or published")
        void oversizedProofIsRefused(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);

            try (var fixture = open(remote)) {
                fixture.anchors().sizedNext(AnchorEngine.PROOF_LIMIT + 1);
                var notesRef = StoreReferences.anchorNotes(fixture.anchors().id());

                var refused = fixture.anchor();
                var noteAfterRefusal = remote.repository().exactRef(notesRef);
                var anchored = fixture.anchor();

                var checkpoint = checkpoint(remote.repository(), 1).name();
                assertAll(
                        () -> assertEquals(ExplicitSave.BLOB_LIMIT,
                                Base64.getEncoder().encode(new byte[AnchorEngine.PROOF_LIMIT]).length,
                                "the largest accepted proof fills the blob bound with its base64 form"),
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of(checkpoint)), refused,
                                "the oversized proof leaves the subject pending"),
                        () -> assertNull(noteAfterRefusal, "no note was published"),
                        () -> assertEquals(2, fixture.anchors().calls().size(),
                                "nothing was kept: the next due check asks the provider again"),
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(checkpoint), List.of()), anchored,
                                "the next due check anchors the subject with a proof of a fitting size"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-15: anchor() of a closed writer without a provider is REFUSED, of an open one empty")
        void closedWriterWithoutProviderRefuses(@Remote LocalRemote remote) {
            var fixture = open(remote, null, null, NO_RETRY);

            var open = fixture.writer().anchor();
            fixture.close();
            var closed = fixture.writer().anchor();

            assertAll(
                    () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of()), value(open),
                            "an open writer without a provider anchors nothing"),
                    () -> assertEquals(StoreOutcome.REFUSED, closed.outcome(), "a closed writer refuses"),
                    () -> assertEquals(AnchorEngine.CLOSED, closed.detail(), "naming the close"));
        }

        @Test
        @DisplayName("GDR-STORE-15: a provider that blocks delays no save, release or fetch")
        void blockingProviderDelaysNothing(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var executor = executor();
            var release = new CountDownLatch(1);
            // the provider waits on the executor thread; what its wait ended with is asserted on the JUnit thread
            var awaited = new AtomicReference<@Nullable Boolean>();
            try (var fixture = open(remote)) {
                fixture.anchors().beforeNextObtain(() -> {
                    try {
                        awaited.set(release.await(WAIT.toSeconds(), TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        awaited.set(false);
                        Thread.currentThread().interrupt();
                    }
                });
                var schedule = fixture.writer().anchorPeriodically(executor);

                var saved = save(fixture.writer(), key);
                assertTrue(fixture.anchors().awaitCalls(1, WAIT), "the due check after the save asks the provider");
                var released = fixture.writer().release(Author.of(pseudonym()), CLAIMS, key);
                var fetched = fixture.writer().fetch();
                var savedAgain = save(fixture.writer(), key);
                var notesWhileBlocked = remote.repository()
                        .exactRef(StoreReferences.anchorNotes(fixture.anchors().id()));
                int callsWhileBlocked = fixture.anchors().calls().size();
                boolean providerStillWaiting = awaited.get() == null;
                release.countDown();
                // the anchoring lock lets this due check start only once the blocked one ended
                var anchoring = fixture.anchor();
                schedule.cancel();

                assertAll(
                        () -> assertTrue(providerStillWaiting,
                                "the save, release, fetch and second save finished before the provider wait ended"),
                        () -> assertEquals(Boolean.TRUE, awaited.get(),
                                "the provider was released by the test, not by the end of its wait"),
                        () -> assertFalse(saved.commitId().isEmpty(), "the first save is saved"),
                        () -> assertTrue(released.isOk(), "the release returns while the provider blocks"),
                        () -> assertTrue(fetched.isOk(), "the fetch returns while the provider blocks"),
                        () -> assertFalse(savedAgain.commitId().isEmpty(), "the second save is saved"),
                        () -> assertNull(notesWhileBlocked, "the blocked due check had published nothing"),
                        () -> assertEquals(1, callsWhileBlocked, "the provider was still blocked in its first call"),
                        () -> assertEquals(List.of(), anchoring.pending(), "afterwards nothing is pending"));
            } finally {
                release.countDown();
                stop(executor);
            }
        }
    }

    @Nested
    @DisplayName("Outcomes of a due check (GDR-STORE-15, GDR-STORE-17)")
    class Outcomes {

        @Test
        @DisplayName("GDR-STORE-15: the updates a due check fetched reach the listeners after an unchecked failure")
        void fetchedUpdatesEmittedAfterRuntimeException(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var main = commit(repository, StoreReferences.MAIN);

            try (var fixture = open(remote)) {
                List<String> received = new CopyOnWriteArrayList<>();
                var observer = observe(fixture.writer());
                try (var subscription = fixture.writer().subscribe(StoreReferences.MAIN,
                             (branch, commitId) -> received.add(commitId))) {
                    var moved = commit(repository, StoreReferences.MAIN, main);
                    fixture.operations().beforeNextPush(() -> {
                        throw new IllegalStateException(word());
                    });

                    var anchoring = fixture.writer().anchor();

                    assertAll(
                            () -> assertEquals(StoreResult.of(StoreOutcome.INCOMPLETE,
                                            "anchor: " + IllegalStateException.class.getName()), anchoring,
                                    "the unchecked failure is a result naming its class"),
                            () -> assertEquals(List.of(moved.name()), received,
                                    "the commit the due check fetched is delivered all the same"),
                            () -> assertEquals(1, observer.moved().size(), "the observer is told once"),
                            () -> assertTrue(observer.moved().getFirst().stream()
                                            .anyMatch(update -> moved.name().equals(update.newId())),
                                    "of the commit the due check fetched"),
                            () -> assertEquals(0, observer.unchangedCalls(), "not as an operation that moved nothing"),
                            () -> assertEquals(List.of(), fixture.anchors().calls(), "the provider was not asked"));
                }
            }
        }

        @Test
        @DisplayName("GDR-STORE-15: a due check of a closed writer, with or without a provider, reaches no observer")
        void closedDueCheckEmitsNothing(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);
            var withProvider = open(remote);
            var withoutProvider = open(remote, null, null, NO_RETRY);
            var observedWith = observe(withProvider.writer());
            var observedWithout = observe(withoutProvider.writer());
            withProvider.close();
            withoutProvider.close();

            var refusedWith = withProvider.writer().anchor();
            var refusedWithout = withoutProvider.writer().anchor();

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, AnchorEngine.CLOSED), refusedWith,
                            "refused with a provider, naming the close"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, AnchorEngine.CLOSED), refusedWithout,
                            "refused without a provider, naming the close"),
                    () -> assertEquals(List.of(), observedWith.moved(), "no moved call with a provider"),
                    () -> assertEquals(0, observedWith.unchangedCalls(), "no unchanged call with a provider"),
                    () -> assertEquals(List.of(), observedWithout.moved(), "no moved call without a provider"),
                    () -> assertEquals(0, observedWithout.unchangedCalls(), "no unchanged call without a provider"),
                    () -> assertEquals(List.of(), withProvider.anchors().calls(), "the provider was not asked"));
        }

        @Test
        @DisplayName("GDR-STORE-15: a due check of an open writer that moved nothing tells the observers once")
        void openDueCheckThatMovedNothingCallsUnchangedOnce(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);

            try (var fixture = open(remote, null, null, NO_RETRY)) {
                var observer = observe(fixture.writer());

                var anchoring = fixture.anchor();

                assertAll(
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of()), anchoring,
                                "nothing anchored without a provider"),
                        () -> assertEquals(List.of(), observer.moved(), "no moved call"),
                        () -> assertEquals(1, observer.unchangedCalls(), "one unchanged call"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-15: an empty proof is refused like none, and the subject stays pending")
        void emptyProofRefused(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);

            try (var fixture = open(remote)) {
                fixture.anchors().emptyNext();

                var refused = fixture.anchor();
                var notesAfterRefusal = refs(remote.repository(), StoreReferences.ANCHOR_NOTES);
                var anchored = fixture.anchor();

                var checkpoint = checkpoint(remote.repository(), 1).name();
                assertAll(
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of(checkpoint)), refused,
                                "the empty proof leaves the subject pending"),
                        () -> assertTrue(notesAfterRefusal.isEmpty(), "no empty note was published"),
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(checkpoint), List.of()), anchored,
                                "the next due check anchors it"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.ANCHORING_PENDING.resolveIdentifierString()));
            }
        }

        @Test
        @DisplayName("GDR-STORE-17: a failed push of a note is logged once, as GIT_STORE-112 only")
        void oneWarnPerFailedPush(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN);

            try (var fixture = open(remote)) {
                fixture.operations().failPushTo(StoreReferences.anchorNotes(fixture.anchors().id()),
                        GitOutcome.UNREACHABLE);

                var anchoring = fixture.anchor();

                var checkpoint = checkpoint(remote.repository(), 1).name();
                assertAll(
                        () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of(checkpoint)), anchoring,
                                "the subject stays pending"),
                        () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.ANCHOR_PUSH_INCOMPLETE.resolveIdentifierString()),
                        () -> assertEquals(List.of(), TestLoggerFactory.getTestHandler().getRecords().stream()
                                .map(logRecord -> String.valueOf(logRecord.getMessage()))
                                .filter(message -> message.contains(GitStoreLogMessages.WARN.ANCHORING_PENDING
                                        .resolveIdentifierString()))
                                .toList(), "no second record of the same failure"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-15: a closed writer runs no due check, neither asked for nor handed to an executor")
        void noDueCheckAfterClose(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var executor = executor();
            var busy = new CountDownLatch(1);
            var occupied = new CountDownLatch(1);
            try {
                var fixture = open(remote);
                fixture.writer().anchorPeriodically(executor);
                executor.execute(() -> {
                    occupied.countDown();
                    try {
                        assertTrue(busy.await(WAIT.toSeconds(), TimeUnit.SECONDS), "the test frees the executor");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(occupied.await(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor is occupied");
                save(fixture.writer(), key);
                fixture.close();
                busy.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor ends");

                var afterClose = fixture.writer().anchor();

                assertAll(
                        () -> assertEquals(List.of(), fixture.anchors().calls(),
                                "the due check handed over before the close did nothing"),
                        () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, AnchorEngine.CLOSED), afterClose,
                                "a due check asked for after the close is refused"),
                        () -> assertTrue(refs(remote.repository(), StoreReferences.CHECKPOINTS).isEmpty(),
                                "no checkpoint was made"));
            } finally {
                busy.countDown();
                stop(executor);
            }
        }
    }

    @Nested
    @DisplayName("When a due check runs (GDR-STORE-16)")
    class Triggers {

        @Test
        @DisplayName("GDR-STORE-16: a due check runs after the push of a save and a release and after a fetch")
        void dueCheckAfterPushAndFetch(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var executor = executor();
            try (var fixture = open(remote)) {
                var provider = fixture.anchors();
                save(fixture.writer(), key);
                assertEquals(0, provider.calls().size(), "without a registered executor no due check is handed over");
                var schedule = fixture.writer().anchorPeriodically(executor);

                save(fixture.writer(), key);
                assertTrue(provider.awaitCalls(1, WAIT), "a due check after the save");
                fixture.anchor();
                var one = checkpoint(remote.repository(), 1);

                commit(remote.repository(), StoreReferences.MAIN, refs(remote.repository(), StoreReferences.MAIN)
                        .get(StoreReferences.MAIN));
                fixture.clock().advance(INTERVAL);
                value(fixture.writer().fetch());
                assertTrue(provider.awaitCalls(2, WAIT), "a due check after the fetch");
                fixture.anchor();
                var two = checkpoint(remote.repository(), 2);

                var release = value(fixture.writer().release(Author.of(pseudonym()), CLAIMS, key));
                assertTrue(provider.awaitCalls(3, WAIT), "a due check after the release");
                var last = fixture.anchor();
                schedule.cancel();

                assertAll(
                        () -> assertEquals(List.of(one, two, ObjectId.fromString(release.commitId())),
                                provider.subjects(), "the checkpoints and the release commit, in turn"),
                        () -> assertEquals(List.of(), last.pending(), "nothing pending"));
            } finally {
                stop(executor);
            }
        }

        @Test
        @DisplayName("GDR-STORE-16: a due check runs on the retry schedule, and no longer once it is cancelled")
        void dueCheckOnRetrySchedule(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var provider = new ScriptedAnchorProvider("a" + word());
            var executor = executor();
            try (var fixture = open(remote, provider, null, Duration.ofSeconds(1))) {
                var schedule = fixture.writer().anchorPeriodically(executor);

                assertTrue(provider.awaitCalls(1, WAIT), "the schedule ran a due check");
                fixture.anchor();
                boolean cancelled = schedule.cancel();
                fixture.clock().advance(INTERVAL.multipliedBy(2));
                save(fixture.writer(), key);
                executor.shutdown();
                assertTrue(executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor ends");

                var subjects = new ArrayList<>(provider.subjects());
                assertAll(
                        () -> assertTrue(cancelled, "the schedule was running"),
                        () -> assertTrue(schedule.isDone(), "the schedule ended"),
                        () -> assertEquals(List.of(checkpoint(remote.repository(), 1)), subjects,
                                "the first checkpoint only: no due check after the cancel"));
            } finally {
                stop(executor);
            }
        }

        @Test
        @DisplayName("GDR-STORE-16: cancelling an earlier schedule again leaves a later one on the same executor in place")
        void earlierScheduleCancelLeavesLaterOne(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var executor = executor();
            try (var fixture = open(remote)) {
                var first = fixture.writer().anchorPeriodically(executor);
                first.cancel();
                var second = fixture.writer().anchorPeriodically(executor);
                first.cancel();

                save(fixture.writer(), key);
                boolean ran = fixture.anchors().awaitCalls(1, WAIT);
                second.cancel();

                assertTrue(ran, "the later schedule still hands the due check after the save to its executor");
            } finally {
                stop(executor);
            }
        }

        @Test
        @DisplayName("GDR-STORE-16: a due check handed over and not started when the schedule is cancelled does not run")
        void queuedDueCheckRejectedAfterCancel(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var executor = executor();
            var busy = new CountDownLatch(1);
            var occupied = new CountDownLatch(1);
            try (var fixture = open(remote)) {
                var schedule = fixture.writer().anchorPeriodically(executor);
                executor.execute(() -> {
                    occupied.countDown();
                    try {
                        assertTrue(busy.await(WAIT.toSeconds(), TimeUnit.SECONDS), "the test frees the executor");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(occupied.await(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor is occupied");

                save(fixture.writer(), key);
                schedule.cancel();
                busy.countDown();
                executor.shutdown();
                assertTrue(executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor ends");

                assertEquals(List.of(), fixture.anchors().calls(), "the queued due check did not run");
            } finally {
                busy.countDown();
                stop(executor);
            }
        }

        @Test
        @DisplayName("GDR-STORE-16: a later schedule whose executor refuses it leaves the earlier schedule in effect")
        void refusedScheduleLeavesEarlierOne(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var executor = executor();
            var refusing = executor();
            refusing.shutdown();
            try (var fixture = open(remote)) {
                var writer = fixture.writer();
                var first = writer.anchorPeriodically(executor);

                assertThrows(RejectedExecutionException.class, () -> writer.anchorPeriodically(refusing),
                        "the refusal of the executor propagates");
                save(writer, key);
                boolean ran = fixture.anchors().awaitCalls(1, WAIT);
                first.cancel();

                assertTrue(ran, "the earlier schedule still hands the due check after the save to its executor");
            } finally {
                stop(executor);
                stop(refusing);
            }
        }

        @Test
        @DisplayName("GDR-STORE-16: a due check its executor discarded holds back no due check of a later schedule")
        void discardedDueCheckHoldsBackNoLaterSchedule(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var discarding = executor();
            var executor = executor();
            var occupied = new CountDownLatch(1);
            try (var fixture = open(remote)) {
                var first = fixture.writer().anchorPeriodically(discarding);
                discarding.execute(() -> {
                    occupied.countDown();
                    try {
                        // ends when shutdownNow interrupts it
                        new CountDownLatch(1).await(WAIT.toSeconds(), TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                assertTrue(occupied.await(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor is occupied");
                save(fixture.writer(), key);
                first.cancel();
                var discarded = discarding.shutdownNow();
                assertTrue(discarding.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor ends");
                int callsBefore = fixture.anchors().calls().size();

                var second = fixture.writer().anchorPeriodically(executor);
                save(fixture.writer(), key);
                boolean ran = fixture.anchors().awaitCalls(1, WAIT);
                second.cancel();

                assertAll(
                        () -> assertFalse(discarded.isEmpty(), "the handed-over due check was discarded unstarted"),
                        () -> assertEquals(0, callsBefore, "the discarded due check never ran"),
                        () -> assertTrue(ran, "the later schedule hands the due check after the save to its executor"));
            } finally {
                stop(discarding);
                stop(executor);
            }
        }
    }
}
