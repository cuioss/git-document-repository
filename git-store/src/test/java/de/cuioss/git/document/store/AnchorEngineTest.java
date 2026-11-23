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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
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
                configuration(provider == null ? null : provider.id(), releases, retry), providers, operations,
                clock));
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
        assertTrue(ref != null, () -> "checkpoint " + number + " exists");
        return Objects.requireNonNull(ref).getObjectId();
    }

    private static ScheduledExecutorService executor() {
        return Executors.newSingleThreadScheduledExecutor();
    }

    private static void stop(ScheduledExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS), "the executor ends");
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
                         configuration(null, null, NO_RETRY), TestKeys.ed25519().source(),
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
        @DisplayName("GDR-STORE-15: a provider that blocks delays no save, release or fetch")
        void blockingProviderDelaysNothing(@Remote LocalRemote remote) throws Exception {
            var key = forked(remote.repository());
            var executor = executor();
            var release = new CountDownLatch(1);
            try (var fixture = open(remote)) {
                fixture.anchors().beforeNextObtain(() -> {
                    try {
                        assertTrue(release.await(WAIT.toSeconds(), TimeUnit.SECONDS), "the test releases the provider");
                    } catch (InterruptedException e) {
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
                release.countDown();
                var anchoring = fixture.anchor();
                schedule.cancel();

                assertAll(
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
    }
}
