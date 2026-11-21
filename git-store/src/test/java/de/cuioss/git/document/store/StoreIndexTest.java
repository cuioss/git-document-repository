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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;


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
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The in-memory index against a {@code git-testing} local remote (GDR-STORE-9): the build at start from {@code main},
 * a working branch and a collection branch; the refresh after a save, a release and a fetch; the re-read of only the
 * changed documents after a save, counted by the contributor; the periodic fetch at {@code fetch.interval} on a test
 * executor; the snapshot of a tag and of a commit, which leaves the current index unchanged; and a throwing contributor.
 * With every log record captured down to trace, no path, content, id or pseudonym reaches a log record or a detail
 * (GDR-NFR-3).
 * <p>
 * Names, ids, pseudonyms, paths and contents are generated, long enough to appear nowhere by chance; the reference
 * layout and the outcomes are the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("In-memory index (GDR-STORE-9)")
class StoreIndexTest {

    /** The attempts of every save and release. */
    private static final int ATTEMPTS = 3;

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 16, 24).next();
    }

    private static String content() {
        return Generators.letterStrings(24, 40).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static DocumentCollection collection(Kind kind) {
        return value(DocumentCollection.of("c" + word(), kind));
    }

    private static StoreConfiguration configuration(Duration interval) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", ATTEMPTS));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", interval.toString()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    /** Writes a commit holding exactly the files onto a reference, forcing the reference. */
    private static ObjectId commit(Repository repository, String ref, Map<String, String> files,
            ObjectId... parents) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, String> file : files.entrySet()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(
                        inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
                builder.add(entry);
            }
            builder.finish();
            var commit = new CommitBuilder();
            var ident = new PersonIdent(pseudonym(), "");
            commit.setTreeId(index.writeTree(inserter));
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
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Repository repository, String ref) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setForceUpdate(true);
        assertEquals(RefUpdate.Result.FORCED, update.delete(), "the fixture reference is deleted");
    }

    private static ObjectId tip(Repository repository, String ref) throws IOException {
        Ref found = repository.exactRef(ref);
        assertNotNull(found, () -> "the reference exists: " + ref);
        return Objects.requireNonNull(found.getObjectId());
    }

    private static Map<String, String> merged(Map<String, String> base, Map<String, String> more) {
        Map<String, String> merged = new TreeMap<>(base);
        merged.putAll(more);
        return merged;
    }

    /** The entry value the {@link Recorder} returns for the files of a document. */
    private static String entry(Map<String, String> files) {
        return String.join("\n", new TreeMap<>(files).values());
    }

    /** The files of one document among the files of a tree. */
    private static Map<String, String> filesOf(DocumentKey key, Map<String, String> tree) {
        Map<String, String> own = new TreeMap<>();
        tree.forEach((path, text) -> {
            if (DocumentPaths.belongs(key, path)) {
                own.put(path, text);
            }
        });
        return own;
    }

    /** Every text a captured log record carries: message, parameters and the chain of its throwable. */
    private static List<String> logTexts() {
        List<String> texts = new ArrayList<>();
        for (LogRecord logRecord : TestLoggerFactory.getTestHandler().getRecords()) {
            texts.add(String.valueOf(logRecord.getMessage()));
            Object[] parameters = logRecord.getParameters();
            if (parameters != null) {
                for (Object parameter : parameters) {
                    texts.add(String.valueOf(parameter));
                }
            }
            for (Throwable thrown = logRecord.getThrown(); thrown != null; thrown = thrown.getCause()) {
                texts.add(String.valueOf(thrown));
            }
        }
        return texts;
    }

    private static void assertNothingLeaks(@Nullable String detail, List<String> secrets) {
        List<String> texts = new ArrayList<>(logTexts());
        texts.add(String.valueOf(detail));
        for (String secret : secrets) {
            assertTrue(texts.stream().noneMatch(text -> text.contains(secret)),
                    () -> "a log record or the detail carries a path, a content, an id or a pseudonym");
        }
    }

    /**
     * A contributor that records every document it is called for and returns the contents of its files joined in path
     * order; it throws for a document holding a poisoned content, the exception message carrying that content.
     */
    private static final class Recorder implements IndexContributor<String> {

        private final List<DocumentKey> calls = Collections.synchronizedList(new ArrayList<>());
        private final List<String> sources = Collections.synchronizedList(new ArrayList<>());
        private volatile @Nullable String poison;

        @Override
        public String contribute(Document document) {
            calls.add(document.key());
            sources.add(document.source());
            Map<String, String> files = new TreeMap<>();
            document.files().forEach((path, bytes) -> files.put(path, new String(bytes, StandardCharsets.UTF_8)));
            @Nullable String poisoned = poison;
            if (poisoned != null && files.containsValue(poisoned)) {
                throw new IllegalStateException(poisoned);
            }
            return entry(files);
        }

        List<DocumentKey> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        void reset() {
            calls.clear();
            sources.clear();
        }
    }

    /**
     * The repository of every test, on the remote: {@code main} with a released document and a released entry, a
     * working branch of a second document forked from {@code main}, and a collection branch with two entries and the
     * counter file.
     */
    private record Layout(DocumentCollection documents, DocumentCollection numbered, DocumentKey released,
    DocumentKey working, DocumentKey firstEntry, DocumentKey secondEntry, Map<String, String> main,
    Map<String, String> workingTree, Map<String, String> collectionTree, ObjectId mainTip) {

        String workingBranch() {
            return StoreReferences.target(working);
        }

        String collectionBranch() {
            return StoreReferences.target(firstEntry);
        }
    }

    private static Layout layout(Repository remote) {
        var documents = collection(Kind.PER_DOCUMENT);
        var numbered = collection(Kind.NUMBERED);
        var released = value(DocumentKey.of(documents, Ulid.next()));
        var working = value(DocumentKey.of(documents, Ulid.next()));
        int number = Generators.integers(1, 9_999).next();
        var firstEntry = value(DocumentKey.of(numbered, "R-" + number));
        var secondEntry = value(DocumentKey.of(numbered, "R-" + (number + 10_000)));
        Map<String, String> main = new TreeMap<>();
        main.put(word() + ".txt", content());
        main.put(path(released, ".json"), content());
        main.put(path(released, "/" + word()), content());
        main.put(path(firstEntry, ".json"), content());
        var mainTip = commit(remote, StoreReferences.MAIN, main);
        var workingTree = merged(main, Map.of(path(working, ".json"), content()));
        commit(remote, StoreReferences.target(working), workingTree, mainTip);
        Map<String, String> collectionTree = new TreeMap<>(main);
        collectionTree.put(path(firstEntry, ".json"), content());
        collectionTree.put(path(secondEntry, ".json"), content());
        collectionTree.put(numbered.name() + "/" + DocumentPaths.COUNTER, content());
        commit(remote, StoreReferences.target(firstEntry), collectionTree, mainTip);
        return new Layout(documents, numbered, released, working, firstEntry, secondEntry, main, workingTree,
                collectionTree, mainTip);
    }

    private static String path(DocumentKey key, String suffix) {
        return key.collection().name() + "/" + key.id() + suffix;
    }

    /** The writer, its transport double and the index under test. */
    private record Fixture(StoreWriter writer, ScriptedRepositoryOperations operations, StoreIndex<String> index,
    Recorder recorder) implements AutoCloseable {

        Map<DocumentKey, String> entries(String source, DocumentCollection collection) {
            return index.entries(source, collection);
        }

        @Override
        public void close() {
            index.close();
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote, Layout layout) {
        return open(remote, layout, Duration.ofMinutes(Generators.integers(1, 60).next()), new Recorder());
    }

    private Fixture open(LocalRemote remote, Layout layout, Duration interval, Recorder recorder) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var gitDir = temp.resolve(word() + ".git");
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var operations = new ScriptedRepositoryOperations();
        var now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(interval), operations,
                Clock.fixed(now, ZoneOffset.UTC)));
        Map<DocumentCollection, IndexContributor<String>> contributors = new LinkedHashMap<>();
        contributors.put(layout.documents(), recorder);
        contributors.put(layout.numbered(), recorder);
        var index = value(StoreIndex.open(writer, contributors));
        return new Fixture(writer, operations, index, recorder);
    }

    /** Writes a draft of one file of the document and saves it. */
    private static String save(StoreWriter writer, Author author, DocumentKey key, String path, String text) {
        value(writer.draft(author, key, List.of(new TreeEdit.Put(path, text.getBytes(StandardCharsets.UTF_8))),
                word()));
        var saved = writer.save(author, key, Map.of(), word());
        return assertInstanceOf(SaveResult.Saved.class, saved, () -> "saved: " + saved).commitId();
    }

    @Nested
    @DisplayName("Build and refresh (GDR-STORE-9)")
    class BuildAndRefresh {

        @Test
        @DisplayName("GDR-STORE-9: the index is built at start from main, the working and the collection branch")
        void buildsAtStart(@Remote LocalRemote remote) throws Exception {
            var layout = layout(remote.repository());

            try (var fixture = open(remote, layout)) {
                assertAll(
                        () -> assertEquals(Set.of(StoreReferences.MAIN, layout.workingBranch(),
                                layout.collectionBranch()), fixture.index().sources(), "the three source branches"),
                        () -> assertEquals(Optional.of(layout.mainTip().name()),
                                fixture.index().commitId(StoreReferences.MAIN), "main indexed at its tip"),
                        () -> assertEquals(Map.of(layout.released(), entry(filesOf(layout.released(), layout.main()))),
                                fixture.entries(StoreReferences.MAIN, layout.documents()),
                                "main holds the released document, its file and its directory"),
                        () -> assertEquals(Map.of(layout.firstEntry(),
                                        entry(filesOf(layout.firstEntry(), layout.main()))),
                                fixture.entries(StoreReferences.MAIN, layout.numbered()), "the released entry"),
                        () -> assertEquals(Map.of(layout.working(),
                                        entry(filesOf(layout.working(), layout.workingTree()))),
                                fixture.entries(layout.workingBranch(), layout.documents()),
                                "the working branch holds its one document, not the released one"),
                        () -> assertEquals(Map.of(layout.firstEntry(),
                                        entry(filesOf(layout.firstEntry(), layout.collectionTree())), layout.secondEntry(),
                                        entry(filesOf(layout.secondEntry(), layout.collectionTree()))),
                                fixture.entries(layout.collectionBranch(), layout.numbered()),
                                "the collection branch holds both entries; the counter file is no document"),
                        () -> assertEquals(Map.of(), fixture.entries(layout.collectionBranch(), layout.documents()),
                                "a collection branch holds no other collection"),
                        () -> assertTrue(fixture.recorder().sources.contains(StoreReferences.MAIN),
                                "the contributor is told the source"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: after a save only the changed document is re-read")
        void saveReReadsOnlyTheChangedDocument(@Remote LocalRemote remote) throws Exception {
            var layout = layout(remote.repository());
            var author = Author.of(pseudonym());
            var workingText = content();
            var entryText = content();

            try (var fixture = open(remote, layout)) {
                fixture.recorder().reset();
                var workingCommit = save(fixture.writer(), author, layout.working(), path(layout.working(), ".json"),
                        workingText);
                var afterWorkingSave = fixture.recorder().calls();
                fixture.recorder().reset();
                var entryCommit = save(fixture.writer(), author, layout.secondEntry(),
                        path(layout.secondEntry(), ".json"), entryText);

                assertAll(
                        () -> assertEquals(List.of(layout.working()), afterWorkingSave,
                                "the save of the document re-read that document only"),
                        () -> assertEquals(List.of(layout.secondEntry()), fixture.recorder().calls(),
                                "the save of the entry re-read that entry only"),
                        () -> assertEquals(Optional.of(workingCommit), fixture.index().commitId(layout.workingBranch()),
                                "the working branch at the save commit"),
                        () -> assertEquals(Optional.of(entryCommit), fixture.index().commitId(layout.collectionBranch()),
                                "the collection branch at the save commit"),
                        () -> assertEquals(Map.of(layout.working(), workingText),
                                fixture.entries(layout.workingBranch(), layout.documents()), "the saved document"),
                        () -> assertEquals(entryText,
                                fixture.entries(layout.collectionBranch(), layout.numbered()).get(layout.secondEntry()),
                                "the saved entry"),
                        () -> assertEquals(entry(filesOf(layout.firstEntry(), layout.collectionTree())),
                                fixture.entries(layout.collectionBranch(), layout.numbered()).get(layout.firstEntry()),
                                "the other entry is kept"),
                        () -> assertNothingLeaks(null, List.of(author.pseudonym(), workingText, entryText,
                                path(layout.working(), ".json"), path(layout.secondEntry(), ".json"))));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: after a release main holds the released document")
        void releaseRefreshesMain(@Remote LocalRemote remote) throws Exception {
            var layout = layout(remote.repository());

            try (var fixture = open(remote, layout)) {
                var release = value(fixture.writer().release(Author.of(pseudonym()), layout.working()));

                assertAll(
                        () -> assertEquals(Optional.of(release.commitId()),
                                fixture.index().commitId(StoreReferences.MAIN), "main at the release commit"),
                        () -> assertEquals(Set.of(layout.released(), layout.working()),
                                fixture.entries(StoreReferences.MAIN, layout.documents()).keySet(),
                                "the released and the newly released document"),
                        () -> assertEquals(entry(filesOf(layout.working(), layout.workingTree())),
                                fixture.entries(StoreReferences.MAIN, layout.documents()).get(layout.working()),
                                "the value of the released document"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: after a fetch the index holds a change made outside the store and drops a gone branch")
        void fetchRefreshes(@Remote LocalRemote remote) throws Exception {
            var layout = layout(remote.repository());

            try (var fixture = open(remote, layout)) {
                var added = value(DocumentKey.of(layout.documents(), Ulid.next()));
                var addedFiles = Map.of(path(added, ".json"), content());
                var outside = commit(remote.repository(), StoreReferences.MAIN, merged(layout.main(), addedFiles),
                        layout.mainTip());
                delete(remote.repository(), layout.workingBranch());

                var moved = value(fixture.writer().fetch());

                assertAll(
                        () -> assertEquals(List.of(layout.workingBranch(), StoreReferences.MAIN).stream().sorted()
                                .toList(), moved, "the moved references, sorted"),
                        () -> assertEquals(Optional.of(outside.name()), fixture.index().commitId(StoreReferences.MAIN),
                                "main at the outside commit"),
                        () -> assertEquals(entry(addedFiles),
                                fixture.entries(StoreReferences.MAIN, layout.documents()).get(added),
                                "the document added outside the store"),
                        () -> assertFalse(fixture.index().sources().contains(layout.workingBranch()),
                                "the pruned working branch is dropped"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: a closed index is no longer refreshed")
        void closedIndexIsNotRefreshed(@Remote LocalRemote remote) throws Exception {
            var layout = layout(remote.repository());

            try (var fixture = open(remote, layout)) {
                fixture.index().close();
                commit(remote.repository(), StoreReferences.MAIN,
                        merged(layout.main(), Map.of(word() + ".txt", content())), layout.mainTip());

                value(fixture.writer().fetch());

                assertEquals(Optional.of(layout.mainTip().name()), fixture.index().commitId(StoreReferences.MAIN),
                        "main stays at the commit it was indexed at");
            }
        }
    }

    @Nested
    @DisplayName("Periodic fetch (GDR-STORE-9)")
    class PeriodicFetch {

        /** A test executor that records the schedule and runs nothing on its own; the test runs the command. */
        private static final class RecordingExecutor extends ScheduledThreadPoolExecutor {

            private @Nullable Runnable command;
            private long initialDelay;
            private long delay;
            private @Nullable TimeUnit unit;

            RecordingExecutor() {
                super(1);
            }

            @Override
            public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initial, long between,
                    TimeUnit timeUnit) {
                this.command = task;
                this.initialDelay = initial;
                this.delay = between;
                this.unit = timeUnit;
                return super.schedule(() -> {
                    // never runs within the test: the test runs the recorded command itself
                }, 1, TimeUnit.DAYS);
            }

            void runOnce() {
                Objects.requireNonNull(command, "a command was scheduled").run();
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: the periodic fetch runs at fetch.interval and picks up a change made outside")
        void periodicFetchPicksUpOutsideChange(@Remote LocalRemote remote) throws Exception {
            var layout = layout(remote.repository());
            var interval = Duration.ofSeconds(Generators.integers(1, 3_600).next());
            var executor = new RecordingExecutor();

            try (var fixture = open(remote, layout, interval, new Recorder())) {
                var future = fixture.writer().fetchPeriodically(executor);
                var outside = commit(remote.repository(), StoreReferences.MAIN,
                        merged(layout.main(), Map.of(word() + ".txt", content())), layout.mainTip());

                executor.runOnce();

                assertAll(
                        () -> assertNotNull(future, "the schedule"),
                        () -> assertEquals(TimeUnit.NANOSECONDS, executor.unit, "in nanoseconds"),
                        () -> assertEquals(interval.toNanos(), executor.initialDelay, "first run one interval on"),
                        () -> assertEquals(interval.toNanos(), executor.delay, "then every interval"),
                        () -> assertEquals(Optional.of(outside.name()), fixture.index().commitId(StoreReferences.MAIN),
                                "the index holds the outside change"));
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: a failed periodic fetch is logged and never ends the schedule")
        void failedPeriodicFetchIsLogged(@Remote LocalRemote remote) throws Exception {
            var layout = layout(remote.repository());
            var executor = new RecordingExecutor();

            try (var fixture = open(remote, layout, Duration.ofMinutes(1), new Recorder())) {
                fixture.writer().fetchPeriodically(executor);
                fixture.operations().failFetch(GitOutcome.UNREACHABLE);

                assertDoesNotThrow(executor::runOnce, "the failure does not escape the scheduled run");
                var outside = commit(remote.repository(), StoreReferences.MAIN,
                        merged(layout.main(), Map.of(word() + ".txt", content())), layout.mainTip());
                executor.runOnce();

                assertAll(
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.PERIODIC_FETCH_FAILED.resolveIdentifierString()),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitOutcome.UNREACHABLE.name()),
                        () -> assertEquals(Optional.of(outside.name()), fixture.index().commitId(StoreReferences.MAIN),
                                "the next run fetches again"));
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("Snapshot (GDR-STORE-9)")
    class Snapshots {

        @Test
        @DisplayName("GDR-STORE-9: a snapshot of a tag or a commit leaves the current index unchanged")
        void snapshotLeavesCurrentUnchanged(@Remote LocalRemote remote) throws Exception {
            var layout = layout(remote.repository());
            var author = Author.of(pseudonym());
            var later = content();

            try (var fixture = open(remote, layout)) {
                var first = value(fixture.writer().release(author, layout.working()));
                save(fixture.writer(), author, layout.working(), path(layout.working(), ".json"), later);
                var second = value(fixture.writer().release(author, layout.working()));
                var sourcesBefore = fixture.index().sources();
                var firstValue = entry(filesOf(layout.working(), layout.workingTree()));
                var shortTag = first.tag().substring(StoreReferences.TAGS.length());

                var byTag = value(fixture.index().snapshot(shortTag));
                var byFullTag = value(fixture.index().snapshot(first.tag()));
                var byCommit = value(fixture.index().snapshot(first.commitId()));
                var missing = fixture.index().snapshot(shortTag + "0");

                assertAll(
                        () -> assertEquals(first.commitId(), byTag.commitId(), "the tag's commit"),
                        () -> assertEquals(firstValue, byTag.entries(layout.documents()).get(layout.working()),
                                "the document as released in v1"),
                        () -> assertEquals(byTag, byFullTag, "the full tag name builds the same snapshot"),
                        () -> assertEquals(byTag, byCommit, "the commit builds the same snapshot"),
                        () -> assertEquals(Set.of(layout.released(), layout.working()),
                                byTag.entries(layout.documents()).keySet(), "indexed as main is"),
                        () -> assertEquals(Optional.of(second.commitId()),
                                fixture.index().commitId(StoreReferences.MAIN), "the current main is unchanged"),
                        () -> assertEquals(later,
                                fixture.entries(StoreReferences.MAIN, layout.documents()).get(layout.working()),
                                "the current value is the second release"),
                        () -> assertEquals(sourcesBefore, fixture.index().sources(), "the current sources"),
                        () -> assertEquals(new StoreResult.PassedOn(GitOutcome.NOT_FOUND), missing.outcome(),
                                "a missing tag"),
                        () -> assertNothingLeaks(missing.detail(), List.of(shortTag + "0", later)));
            }
        }
    }

    @Nested
    @DisplayName("Contributor failure (GDR-STORE-9, GDR-NFR-3)")
    class ContributorFailure {

        @Test
        @DisplayName("GDR-STORE-9: a throwing contributor leaves its document out, logged by class name only")
        void throwingContributorLeavesDocumentOut(@Remote LocalRemote remote) throws Exception {
            var layout = layout(remote.repository());
            var recorder = new Recorder();
            recorder.poison = layout.collectionTree().get(path(layout.secondEntry(), ".json"));

            try (var fixture = open(remote, layout, Duration.ofMinutes(1), recorder)) {
                var entries = fixture.entries(layout.collectionBranch(), layout.numbered());
                String identifier = GitStoreLogMessages.WARN.CONTRIBUTOR_FAILED.resolveIdentifierString();
                List<LogRecord> failures = TestLoggerFactory.getTestHandler().getRecords().stream()
                        .filter(logRecord -> String.valueOf(logRecord.getMessage()).contains(identifier))
                        .collect(Collectors.toList());

                assertAll(
                        () -> assertEquals(Set.of(layout.firstEntry()), entries.keySet(),
                                "the other entry is indexed, the failing one left out"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, identifier),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                IllegalStateException.class.getSimpleName()),
                        () -> assertFalse(failures.isEmpty(), "the failure is logged"),
                        () -> failures.forEach(logRecord -> assertNull(logRecord.getThrown(), "no throwable attached")),
                        () -> assertNothingLeaks(null, List.of(Objects.requireNonNull(recorder.poison),
                                layout.secondEntry().id(), path(layout.secondEntry(), ".json"))));
            }
        }
    }
}
