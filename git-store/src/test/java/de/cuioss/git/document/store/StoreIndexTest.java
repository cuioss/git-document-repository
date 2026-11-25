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
import java.nio.file.Files;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;


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
        tree.put("signing", Map.of("key", word()));
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
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(interval), List.of(),
                TestKeys.ed25519().source(), operations, Clock.fixed(now, ZoneOffset.UTC)));
        Map<DocumentCollection, IndexContributor<String>> contributors = new LinkedHashMap<>();
        contributors.put(layout.documents(), recorder);
        contributors.put(layout.numbered(), recorder);
        var index = value(StoreIndex.open(writer, contributors));
        return new Fixture(writer, operations, index, recorder);
    }

    /**
     * Writes a commit whose tree holds one file of the document at a blob id the repository lacks, onto a reference: a
     * source whose documents cannot be read.
     */
    private static ObjectId brokenCommit(Repository repository, String ref, String path, ObjectId parent) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            var entry = new DirCacheEntry(path);
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(ObjectId.fromString(Generators.strings("0123456789abcdef", 40, 40).next()));
            builder.add(entry);
            builder.finish();
            var commit = new CommitBuilder();
            var ident = new PersonIdent(pseudonym(), "");
            commit.setTreeId(index.writeTree(inserter));
            commit.setParentIds(parent);
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

    @Nested
    @DisplayName("Per-source refresh (GDR-STORE-9)")
    class PerSource {

        @Test
        @DisplayName("GDR-STORE-9: an unreadable working branch keeps its entries, the other sources are fresh")
        void unreadableSourceKeepsItsEntries(@Remote LocalRemote remote) {
            var repository = remote.repository();
            var layout = layout(repository);
            var other = value(DocumentKey.of(layout.documents(), Ulid.next()));
            var otherBranch = StoreReferences.target(other);
            var otherTip = commit(repository, otherBranch,
                    merged(layout.main(), Map.of(path(other, ".json"), content())), layout.mainTip());
            var recorder = new Recorder();
            var events = new StoreEvents(updates -> {
                // no listeners in this test
            });
            Map<DocumentCollection, IndexContributor<String>> contributors = new LinkedHashMap<>();
            contributors.put(layout.documents(), recorder);
            contributors.put(layout.numbered(), recorder);
            String alias = word();

            try (var index = value(StoreIndex.open(alias, repository, events, contributors))) {
                var workingBefore = index.entries(layout.workingBranch(), layout.documents());
                var workingCommit = index.commitId(layout.workingBranch());
                var broken = brokenCommit(repository, layout.workingBranch(), path(layout.working(), ".json"),
                        ObjectId.fromString(workingCommit.orElseThrow()));
                Map<String, String> mainNow = merged(layout.main(), Map.of(path(layout.released(), ".json"),
                        content()));
                var mainTip = commit(repository, StoreReferences.MAIN, mainNow, layout.mainTip());
                var otherText = content();
                var otherMoved = commit(repository, otherBranch,
                        merged(layout.main(), Map.of(path(other, ".json"), otherText)), otherTip);

                events.emit(List.of(new StoreEvents.Update(layout.workingBranch(), workingCommit.orElseThrow(),
                        broken.name())));

                assertAll(
                        () -> assertEquals(workingBefore, index.entries(layout.workingBranch(), layout.documents()),
                                "the unreadable source keeps its previous entries"),
                        () -> assertEquals(workingCommit, index.commitId(layout.workingBranch()),
                                "at its previous commit"),
                        () -> assertEquals(Optional.of(mainTip.name()), index.commitId(StoreReferences.MAIN),
                                "main is published fresh"),
                        () -> assertEquals(entry(filesOf(layout.released(), mainNow)),
                                index.entries(StoreReferences.MAIN, layout.documents()).get(layout.released()),
                                "with its new entry"),
                        () -> assertEquals(Optional.of(otherMoved.name()), index.commitId(otherBranch),
                                "the other working branch is published fresh"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.INDEX_SOURCE_UNREAD.resolveIdentifierString()),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, "working branch"),
                        () -> assertNothingLeaks(null, List.of(layout.workingBranch(), layout.working().id(),
                                otherText)));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: a new source that cannot be read stays out of the index, the others are indexed")
        void unreadableNewSourceStaysOut(@Remote LocalRemote remote) {
            var repository = remote.repository();
            var layout = layout(repository);
            var other = value(DocumentKey.of(layout.documents(), Ulid.next()));
            var otherBranch = StoreReferences.target(other);
            brokenCommit(repository, otherBranch, path(other, ".json"), layout.mainTip());
            var recorder = new Recorder();
            var events = new StoreEvents(updates -> {
                // no listeners in this test
            });
            Map<DocumentCollection, IndexContributor<String>> contributors = new LinkedHashMap<>();
            contributors.put(layout.documents(), recorder);
            contributors.put(layout.numbered(), recorder);

            try (var index = value(StoreIndex.open(word(), repository, events, contributors))) {
                assertAll(
                        () -> assertFalse(index.sources().contains(otherBranch), "the unreadable source is left out"),
                        () -> assertTrue(index.sources().containsAll(Set.of(StoreReferences.MAIN,
                                layout.workingBranch(), layout.collectionBranch())), "every other source"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.INDEX_SOURCE_UNREAD.resolveIdentifierString()));
            }
        }
    }

    /** Writes a draft of one file of the document and saves it. */
    private static String save(StoreWriter writer, Author author, DocumentKey key, String path, String text) {
        value(writer.draft(author, key, List.of(new TreeEdit.Put(path, text.getBytes(StandardCharsets.UTF_8))),
                word()));
        var saved = writer.save(author, CLAIMS, key, Map.of(), word());
        return assertInstanceOf(SaveResult.Saved.class, saved, () -> "saved: " + saved).commitId();
    }

    @Nested
    @DisplayName("Build and refresh (GDR-STORE-9)")
    class BuildAndRefresh {

        @Test
        @DisplayName("GDR-STORE-9: the index is built at start from main, the working and the collection branch")
        void buildsAtStart(@Remote LocalRemote remote) {
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
        void saveReReadsOnlyTheChangedDocument(@Remote LocalRemote remote) {
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
        void releaseRefreshesMain(@Remote LocalRemote remote) {
            var layout = layout(remote.repository());

            try (var fixture = open(remote, layout)) {
                var release = value(fixture.writer().release(Author.of(pseudonym()), CLAIMS, layout.working()));

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
        void closedIndexIsNotRefreshed(@Remote LocalRemote remote) {
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
        void periodicFetchPicksUpOutsideChange(@Remote LocalRemote remote) {
            var layout = layout(remote.repository());
            var interval = Duration.ofSeconds(Generators.integers(1, 3_600).next());
            var executor = new RecordingExecutor();

            try (var fixture = open(remote, layout, interval, new Recorder())) {
                var future = fixture.writer().fetchPeriodically(executor);
                var outside = commit(remote.repository(), StoreReferences.MAIN,
                        merged(layout.main(), Map.of(word() + ".txt", content())), layout.mainTip());

                executor.runOnce();
                boolean doneBefore = future.isDone();
                boolean cancelled = future.cancel();

                assertAll(
                        () -> assertNotNull(future, "the schedule"),
                        () -> assertFalse(doneBefore, "the schedule runs until it is cancelled"),
                        () -> assertTrue(cancelled, "the schedule is cancelled"),
                        () -> assertTrue(future.isDone(), "the cancelled schedule is done"),
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
        void failedPeriodicFetchIsLogged(@Remote LocalRemote remote) {
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
        void snapshotLeavesCurrentUnchanged(@Remote LocalRemote remote) {
            var layout = layout(remote.repository());
            var author = Author.of(pseudonym());
            var later = content();

            try (var fixture = open(remote, layout)) {
                var first = value(fixture.writer().release(author, CLAIMS, layout.working()));
                save(fixture.writer(), author, layout.working(), path(layout.working(), ".json"), later);
                var second = value(fixture.writer().release(author, CLAIMS, layout.working()));
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
        void throwingContributorLeavesDocumentOut(@Remote LocalRemote remote) {
            var layout = layout(remote.repository());
            var recorder = new Recorder();
            recorder.poison = layout.collectionTree().get(path(layout.secondEntry(), ".json"));

            try (var fixture = open(remote, layout, Duration.ofMinutes(1), recorder)) {
                var entries = fixture.entries(layout.collectionBranch(), layout.numbered());
                String identifier = GitStoreLogMessages.WARN.CONTRIBUTOR_FAILED.resolveIdentifierString();
                List<LogRecord> failures = TestLoggerFactory.getTestHandler().getRecords().stream()
                        .filter(logRecord -> String.valueOf(logRecord.getMessage()).contains(identifier))
                        .toList();

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

    /** The deadline of every wait for another thread. */
    private static final long WAIT_SECONDS = 10;

    /** The calls after which a re-entering contributor stops re-entering, far below the depth of the stack. */
    private static final int REENTRY_BOUND = 50;

    /** Opens an index directly over a repository and its own events, as {@link StoreIndex#open} does for a writer. */
    private static StoreIndex<String> direct(Repository repository, Layout layout, IndexContributor<String> contributor,
            StoreEvents events) {
        Map<DocumentCollection, IndexContributor<String>> contributors = new LinkedHashMap<>();
        contributors.put(layout.documents(), contributor);
        contributors.put(layout.numbered(), contributor);
        return value(StoreIndex.open(word(), repository, events, contributors));
    }

    private static ObjectId tip(StoreIndex<String> index, String source) {
        return ObjectId.fromString(index.commitId(source).orElseThrow());
    }

    /**
     * Writes a commit holding the files and, at one more path, a blob id the repository does not hold yet, onto a
     * reference.
     */
    private static ObjectId commitWithMissingBlob(Repository repository, String ref, Map<String, String> files,
            String path, ObjectId blob, ObjectId parent) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            Map<String, ObjectId> entries = new TreeMap<>();
            for (Map.Entry<String, String> file : files.entrySet()) {
                entries.put(file.getKey(),
                        inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            entries.put(path, blob);
            entries.forEach((name, id) -> {
                var entry = new DirCacheEntry(name);
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(id);
                builder.add(entry);
            });
            builder.finish();
            var commit = new CommitBuilder();
            var ident = new PersonIdent(pseudonym(), "");
            commit.setTreeId(index.writeTree(inserter));
            commit.setParentIds(parent);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(ref);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            update.update();
            return id;
        }
    }

    /** A contributor that counts the threads inside it, and blocks its first call once told to. */
    private static final class Probe implements IndexContributor<String> {

        private final AtomicInteger active = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final CountDownLatch inside = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile boolean blocking;

        @Override
        public String contribute(Document document) {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                if (blocking) {
                    blocking = false;
                    inside.countDown();
                    assertTrue(release.await(WAIT_SECONDS, TimeUnit.SECONDS), "released");
                }
                return document.key().id();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return document.key().id();
            } finally {
                active.decrementAndGet();
            }
        }
    }

    /**
     * A contributor that records every document it is called for, blocks its first call for a document of one source
     * once told to, until released (bounded), and runs an action once on its first call when given one.
     */
    private static final class Gate implements IndexContributor<String> {

        private final List<DocumentKey> calls = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch inside = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicReference<@Nullable String> blockOn = new AtomicReference<>();
        private final AtomicReference<@Nullable Runnable> once = new AtomicReference<>();

        @Override
        public String contribute(Document document) {
            calls.add(document.key());
            @Nullable Runnable action = once.getAndSet(null);
            if (action != null) {
                action.run();
            }
            @Nullable String source = blockOn.get();
            if (source != null && source.equals(document.source()) && blockOn.compareAndSet(source, null)) {
                inside.countDown();
                try {
                    assertTrue(release.await(WAIT_SECONDS, TimeUnit.SECONDS), "released");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return document.key().id();
        }

        void blockOn(String source) {
            blockOn.set(source);
        }

        void runOnce(Runnable action) {
            once.set(action);
        }

        boolean awaitInside() throws InterruptedException {
            return inside.await(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }

        List<DocumentKey> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }

        void reset() {
            calls.clear();
        }
    }

    /**
     * A contributor of one of two indexes on the same events: once armed, its first call marks it inside, waits until
     * the thread of the other index is inside the other contributor, parked on a lock or ended (bounded by the one
     * deadline of the test), and then emits an operation that moved nothing, as a fetch of a current clone does.
     */
    private static final class NoMoveContributor implements IndexContributor<String> {

        private final StoreEvents events;
        private final AtomicBoolean armed;
        private final long deadlineNanos;
        private final CountDownLatch inside = new CountDownLatch(1);
        private final AtomicInteger actions = new AtomicInteger();
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile @Nullable NoMoveContributor partner;
        private volatile @Nullable Thread partnerThread;

        NoMoveContributor(StoreEvents events, AtomicBoolean armed, long deadlineNanos) {
            this.events = events;
            this.armed = armed;
            this.deadlineNanos = deadlineNanos;
        }

        void partner(NoMoveContributor contributor, Thread thread) {
            partner = contributor;
            partnerThread = thread;
        }

        @Override
        public String contribute(Document document) {
            if (armed.get() && done.compareAndSet(false, true)) {
                actions.incrementAndGet();
                inside.countDown();
                try {
                    awaitPartner();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                events.emit(List.of());
            }
            return document.key().id();
        }

        private void awaitPartner() throws InterruptedException {
            var other = Objects.requireNonNull(partner, "partner");
            var thread = Objects.requireNonNull(partnerThread, "partner thread");
            while (other.inside.getCount() > 0 && thread.getState() != Thread.State.WAITING
                    && thread.getState() != Thread.State.TERMINATED && System.nanoTime() < deadlineNanos) {
                thread.join(1);
            }
        }
    }

    /** Whether a captured WARN record names an unread source of the index. */
    private static boolean sourceUnreadLogged() {
        String identifier = GitStoreLogMessages.WARN.INDEX_SOURCE_UNREAD.resolveIdentifierString();
        return TestLoggerFactory.getTestHandler().getRecords().stream()
                .anyMatch(logRecord -> String.valueOf(logRecord.getMessage()).contains(identifier));
    }

    @Nested
    @DisplayName("Serialisation, retry and log (GDR-STORE-9, GDR-NFR-3)")
    class Robustness {

        /**
         * A refresh that will end stale is blocked inside the contributor on {@code main}, the last source it reads, after
         * the working branch failed to be read; an operation that moved nothing, issued on another thread meanwhile,
         * waits for that refresh under the lock of the index and then retries it, now that the missing blob is present.
         * Reading the stale flag without the lock, it would see the index current and skip the retry.
         */
        @Test
        @DisplayName("GDR-STORE-9: unchanged() during a refresh that ends stale waits for it and retries the refresh")
        void unchangedDuringFailingRefreshWaitsAndRetries(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });
            var gate = new Gate();

            try (var index = direct(repository, layout, gate, events)) {
                var workingBefore = tip(index, layout.workingBranch());
                var mainBefore = tip(index, StoreReferences.MAIN);
                var text = content();
                ObjectId blob;
                try (var formatter = new ObjectInserter.Formatter()) {
                    blob = formatter.idFor(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
                }
                var path = path(layout.working(), ".json");
                Map<String, String> others = new TreeMap<>(layout.workingTree());
                others.remove(path);
                var broken = commitWithMissingBlob(repository, layout.workingBranch(), others, path, blob,
                        workingBefore);
                var mainMoved = commit(repository, StoreReferences.MAIN, merged(layout.main(),
                        Map.of(path(layout.released(), ".json"), content())), mainBefore);
                gate.blockOn(StoreReferences.MAIN);
                var refresh = new Thread(() -> events.emit(List.of(
                        new StoreEvents.Update(layout.workingBranch(), workingBefore.name(), broken.name()),
                        new StoreEvents.Update(StoreReferences.MAIN, mainBefore.name(), mainMoved.name()))));
                Thread retry = new Thread(() -> events.emit(List.of()));
                boolean inside;
                boolean failedFirst;
                boolean waited;
                try {
                    refresh.start();
                    inside = gate.awaitInside();
                    failedFirst = sourceUnreadLogged();
                    try (ObjectInserter inserter = repository.newObjectInserter()) {
                        inserter.insert(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
                        inserter.flush();
                    }
                    gate.reset();
                    retry.start();
                    ThreadStates.awaitParkedOrEnded(retry, WAIT_SECONDS);
                    waited = retry.isAlive();
                } finally {
                    gate.release();
                }
                refresh.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                retry.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

                assertAll(
                        () -> assertTrue(inside, "the refresh is inside the contributor on main"),
                        () -> assertTrue(failedFirst, "the working branch failed before main was read"),
                        () -> assertTrue(waited, "unchanged() waits for the running refresh"),
                        () -> assertFalse(refresh.isAlive(), "the refresh ends"),
                        () -> assertFalse(retry.isAlive(), "the unchanged() call ends"),
                        () -> assertTrue(gate.calls().contains(layout.working()),
                                "the retry calls the contributor for the document of the failed source"),
                        () -> assertEquals(broken, tip(index, layout.workingBranch()),
                                "the retried refresh indexes the working branch at its new tip"),
                        () -> assertEquals(mainMoved, tip(index, StoreReferences.MAIN), "main is indexed fresh"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: unchanged() on a current index calls no contributor")
        void unchangedOnCurrentIndexSkipsRebuild(@Remote LocalRemote remote) {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });
            var gate = new Gate();

            try (var index = direct(repository, layout, gate, events)) {
                var built = gate.calls();
                gate.reset();

                events.emit(List.of());

                assertAll(
                        () -> assertFalse(built.isEmpty(), "the build called the contributor"),
                        () -> assertEquals(List.of(), gate.calls(), "unchanged() rebuilt nothing"),
                        () -> assertEquals(layout.mainTip(), tip(index, StoreReferences.MAIN), "main is unchanged"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: unchanged() from the contributor's own thread during a refresh does not deadlock")
        void unchangedFromContributorThreadDoesNotDeadlock(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });
            var gate = new Gate();

            try (var index = direct(repository, layout, gate, events)) {
                var mainBefore = tip(index, StoreReferences.MAIN);
                var mainMoved = commit(repository, StoreReferences.MAIN, merged(layout.main(),
                        Map.of(path(layout.released(), ".json"), content())), mainBefore);
                var returned = new CountDownLatch(1);
                gate.runOnce(() -> {
                    events.emit(List.of());
                    returned.countDown();
                });
                var refresh = new Thread(() -> events.emit(List.of(
                        new StoreEvents.Update(StoreReferences.MAIN, mainBefore.name(), mainMoved.name()))));
                refresh.setDaemon(true);

                refresh.start();
                boolean reentered = returned.await(WAIT_SECONDS, TimeUnit.SECONDS);
                refresh.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

                assertAll(
                        () -> assertTrue(reentered, "the nested unchanged() returns"),
                        () -> assertFalse(refresh.isAlive(), "the refresh ends"),
                        () -> assertEquals(mainMoved, tip(index, StoreReferences.MAIN), "main is indexed fresh"));
            }
        }

        /**
         * A contributor that performs a no-move operation on every call, while the index is stale: without the retry
         * restricted to the outermost holder of the index lock, every nested {@code unchanged()} read the flag still
         * set and refreshed again, calling the contributor again, until the stack overflowed. The contributor stops
         * re-entering at {@value #REENTRY_BOUND} calls, so the test fails on that bound instead of overflowing.
         */
        @Test
        @DisplayName("GDR-STORE-9: a contributor re-entering unchanged() on a stale index defers the retry, no recursion")
        void reenteringContributorOnStaleIndexDoesNotRecurse(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });
            var calls = new AtomicInteger();
            var armed = new AtomicBoolean();
            var bounded = new AtomicBoolean();
            IndexContributor<String> contributor = document -> {
                if (armed.get()) {
                    if (calls.incrementAndGet() < REENTRY_BOUND) {
                        events.emit(List.of());
                    } else {
                        bounded.set(true);
                    }
                }
                return document.key().id();
            };

            try (var index = direct(repository, layout, contributor, events)) {
                var workingBefore = tip(index, layout.workingBranch());
                var text = content();
                ObjectId blob;
                try (var formatter = new ObjectInserter.Formatter()) {
                    blob = formatter.idFor(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
                }
                var path = path(layout.working(), ".json");
                Map<String, String> others = new TreeMap<>(layout.workingTree());
                others.remove(path);
                var broken = commitWithMissingBlob(repository, layout.workingBranch(), others, path, blob,
                        workingBefore);
                events.emit(List.of(new StoreEvents.Update(layout.workingBranch(), workingBefore.name(),
                        broken.name())));
                var staleAfterMove = tip(index, layout.workingBranch());

                armed.set(true);
                events.emit(List.of());
                int failingRetryCalls = calls.getAndSet(0);
                var staleAfterFailingRetry = tip(index, layout.workingBranch());
                try (ObjectInserter inserter = repository.newObjectInserter()) {
                    inserter.insert(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
                    inserter.flush();
                }
                events.emit(List.of());
                int recoveringRetryCalls = calls.getAndSet(0);
                var recovered = tip(index, layout.workingBranch());
                events.emit(List.of());
                int currentCalls = calls.get();

                assertAll(
                        () -> assertFalse(bounded.get(), "the contributor never reached the re-entry bound"),
                        () -> assertEquals(workingBefore, staleAfterMove, "the unreadable source keeps its tip"),
                        () -> assertTrue(failingRetryCalls < REENTRY_BOUND,
                                () -> "a retry that still fails calls the contributor one pass at most: "
                                        + failingRetryCalls),
                        () -> assertEquals(workingBefore, staleAfterFailingRetry,
                                "a retry that still fails keeps the index stale"),
                        () -> assertTrue(recoveringRetryCalls > 0 && recoveringRetryCalls < REENTRY_BOUND,
                                () -> "the next operation retries in one pass: " + recoveringRetryCalls),
                        () -> assertEquals(broken, recovered, "the retry indexes the restored source"),
                        () -> assertEquals(0, currentCalls, "the outer refresh cleared the stale mark"));
            }
        }

        /**
         * Finding c5f522: two indexes on the events of one writer, whose contributors each emit an operation that moved
         * nothing from inside a snapshot, on two threads. With one lock per index, the first thread held the lock of
         * index A and its nested {@code unchanged()} on index B waited for B's lock, which the second thread held while
         * its nested {@code unchanged()} waited for A's: neither ended, and the assertion "both threads end within the
         * deadline, so the two indexes did not wait for each other" failed. With the one lock of the writer's indexes
         * the second thread waits until the first released it, and both end.
         */
        @Test
        @DisplayName("GDR-STORE-9: two indexes of one writer whose contributors move nothing never wait for each other")
        void twoIndexesWithNoMoveContributorsDoNotDeadlock(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            var armed = new AtomicBoolean();
            var first = new NoMoveContributor(events, armed, deadline);
            var second = new NoMoveContributor(events, armed, deadline);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

            try (var a = direct(repository, layout, first, events);
                 var b = direct(repository, layout, second, events)) {
                AtomicReference<@Nullable StoreResult<StoreIndex.Snapshot<String>>> fromA = new AtomicReference<>();
                AtomicReference<@Nullable StoreResult<StoreIndex.Snapshot<String>>> fromB = new AtomicReference<>();
                var threadA = new Thread(() -> fromA.set(a.snapshot(layout.mainTip().name())), "snapshot-a");
                var threadB = new Thread(() -> fromB.set(b.snapshot(layout.mainTip().name())), "snapshot-b");
                for (Thread thread : List.of(threadA, threadB)) {
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler((failed, e) -> failures.add(e));
                }
                first.partner(second, threadB);
                second.partner(first, threadA);
                armed.set(true);

                threadA.start();
                threadB.start();
                TimeUnit.NANOSECONDS.timedJoin(threadA, Math.max(0, deadline - System.nanoTime()));
                TimeUnit.NANOSECONDS.timedJoin(threadB, Math.max(0, deadline - System.nanoTime()));
                boolean ended = !threadA.isAlive() && !threadB.isAlive();
                armed.set(false);

                assertAll(
                        () -> assertTrue(ended,
                                "both threads end within the deadline, so the two indexes did not wait for each other"),
                        () -> assertTrue(Objects.requireNonNull(fromA.get(), "snapshot A returned").isOk(),
                                "the snapshot of index A is built"),
                        () -> assertTrue(Objects.requireNonNull(fromB.get(), "snapshot B returned").isOk(),
                                "the snapshot of index B is built"),
                        () -> assertEquals(1, first.actions.get(), "the contributor of A emitted once"),
                        () -> assertEquals(1, second.actions.get(), "the contributor of B emitted once"),
                        () -> assertEquals(List.of(), failures, "no thread failed"),
                        () -> assertEquals(layout.mainTip(), tip(a, StoreReferences.MAIN), "index A is unchanged"),
                        () -> assertEquals(layout.mainTip(), tip(b, StoreReferences.MAIN), "index B is unchanged"));
            }
        }

        /**
         * Finding c5f522, the deferral through the other index: both indexes of one writer are stale, and the
         * contributor of index A emits an operation that moved nothing from inside a snapshot of A. The nested
         * {@code unchanged()} of index B runs under the one lock A's snapshot holds, so it defers its retry like a
         * nested call on the same index: B's contributor is not called and B stays stale until the next operation.
         * With one lock per index, B took its own free lock as the outermost holder and refreshed inside A's snapshot,
         * so the assertion "the retry of B is deferred" failed.
         */
        @Test
        @DisplayName("GDR-STORE-9: a nested no-move call through another index of the writer defers its retry")
        void nestedCallThroughOtherIndexDefersRetry(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });
            var gateA = new Gate();
            var gateB = new Gate();

            try (var a = direct(repository, layout, gateA, events); var b = direct(repository, layout, gateB, events)) {
                var before = tip(b, layout.workingBranch());
                var text = content();
                ObjectId blob;
                try (var formatter = new ObjectInserter.Formatter()) {
                    blob = formatter.idFor(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
                }
                var path = path(layout.working(), ".json");
                Map<String, String> others = new TreeMap<>(layout.workingTree());
                others.remove(path);
                var broken = commitWithMissingBlob(repository, layout.workingBranch(), others, path, blob, before);
                events.emit(List.of(new StoreEvents.Update(layout.workingBranch(), before.name(), broken.name())));
                try (ObjectInserter inserter = repository.newObjectInserter()) {
                    inserter.insert(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
                    inserter.flush();
                }
                gateA.reset();
                gateB.reset();
                AtomicReference<@Nullable List<DocumentKey>> callsOfBDuring = new AtomicReference<>();
                AtomicReference<@Nullable ObjectId> tipOfBDuring = new AtomicReference<>();
                gateA.runOnce(() -> {
                    events.emit(List.of());
                    callsOfBDuring.set(gateB.calls());
                    tipOfBDuring.set(tip(b, layout.workingBranch()));
                });

                var snapshot = a.snapshot(layout.mainTip().name());
                var staleAfterSnapshot = tip(b, layout.workingBranch());
                events.emit(List.of());

                assertAll(
                        () -> assertTrue(snapshot.isOk(), "the snapshot of index A is built"),
                        () -> assertEquals(List.of(), callsOfBDuring.get(),
                                "the retry of B is deferred: its contributor is not called inside A's snapshot"),
                        () -> assertEquals(before, tipOfBDuring.get(), "B stays stale inside A's snapshot"),
                        () -> assertEquals(before, staleAfterSnapshot, "and after it, until the next operation"),
                        () -> assertEquals(broken, tip(b, layout.workingBranch()),
                                "the next operation retries B as the outermost holder"),
                        () -> assertEquals(broken, tip(a, layout.workingBranch()), "and retries A"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: a snapshot during a refresh waits; no contributor is called by two threads at once")
        void snapshotDuringRefreshWaits(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });
            var probe = new Probe();

            try (var index = direct(repository, layout, probe, events)) {
                var before = tip(index, layout.workingBranch());
                var moved = commit(repository, layout.workingBranch(),
                        merged(layout.workingTree(), Map.of(path(layout.working(), ".json"), content())), before);
                probe.blocking = true;
                var refresh = new Thread(() -> events.emit(List.of(new StoreEvents.Update(layout.workingBranch(),
                        before.name(), moved.name()))));
                refresh.start();
                assertTrue(probe.inside.await(WAIT_SECONDS, TimeUnit.SECONDS), "the refresh is inside the contributor");
                AtomicReference<StoreResult<StoreIndex.Snapshot<String>>> snapshot = new AtomicReference<>();
                var reader = new Thread(() -> snapshot.set(index.snapshot(layout.mainTip().name())));
                reader.start();
                ThreadStates.awaitParkedOrEnded(reader, WAIT_SECONDS);
                boolean waited = reader.isAlive();

                probe.release.countDown();
                refresh.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
                reader.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

                assertAll(
                        () -> assertTrue(waited, "the snapshot waits for the refresh"),
                        () -> assertEquals(1, probe.maxActive.get(), "one thread at a time in the contributor"),
                        () -> assertTrue(snapshot.get().isOk(), "the snapshot is built afterwards"),
                        () -> assertEquals(moved, tip(index, layout.workingBranch()), "the refresh completed"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: a fetched blob above 16 MiB keeps the source's previous entries, never read whole")
        void oversizedBlobKeepsPreviousEntries(@Remote LocalRemote remote) {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });

            try (var index = direct(repository, layout, new Recorder(), events)) {
                var before = tip(index, layout.workingBranch());
                var entriesBefore = index.entries(layout.workingBranch(), layout.documents());
                var mainBefore = tip(index, StoreReferences.MAIN);
                var moved = commit(repository, layout.workingBranch(), merged(layout.workingTree(),
                        Map.of(path(layout.working(), ".json"), "x".repeat(ExplicitSave.BLOB_LIMIT + 1))), before);

                events.emit(List.of(new StoreEvents.Update(layout.workingBranch(), before.name(), moved.name())));

                String identifier = GitStoreLogMessages.WARN.INDEX_SOURCE_UNREAD.resolveIdentifierString();
                assertAll(
                        () -> assertEquals(before, tip(index, layout.workingBranch()),
                                "the source keeps the commit it was indexed at"),
                        () -> assertEquals(entriesBefore, index.entries(layout.workingBranch(), layout.documents()),
                                "and its previous entries"),
                        () -> assertEquals(mainBefore, tip(index, StoreReferences.MAIN), "main is unaffected"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, identifier),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                ExplicitSave.BlobTooLargeException.class.getSimpleName()));
            }
        }

        @Test
        @DisplayName("GDR-STORE-9: an operation that moves no reference retries a refresh that failed")
        void unchangedOperationRetriesAFailedRefresh(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });

            try (var index = direct(repository, layout, new Recorder(), events)) {
                var before = tip(index, layout.workingBranch());
                var text = content();
                ObjectId blob;
                try (var formatter = new ObjectInserter.Formatter()) {
                    blob = formatter.idFor(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
                }
                var path = path(layout.working(), ".json");
                Map<String, String> others = new TreeMap<>(layout.workingTree());
                others.remove(path);
                var moved = commitWithMissingBlob(repository, layout.workingBranch(), others, path, blob, before);

                events.emit(List.of(new StoreEvents.Update(layout.workingBranch(), before.name(), moved.name())));
                var afterFailure = tip(index, layout.workingBranch());
                try (ObjectInserter inserter = repository.newObjectInserter()) {
                    inserter.insert(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
                    inserter.flush();
                }
                events.emit(List.of());

                assertAll(
                        () -> assertEquals(before, afterFailure, "the failed refresh keeps the previous index"),
                        () -> assertEquals(moved, tip(index, layout.workingBranch()),
                                "the operation without a moved reference retried the refresh"),
                        () -> assertEquals(text, index.entries(layout.workingBranch(), layout.documents())
                                .get(layout.working()), "the document as the clone holds it"));
            }
        }

        @Test
        @DisplayName("GDR-NFR-3: the full re-read when the indexed commit is gone logs no document id")
        void fallbackLogsNoDocumentId(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var layout = layout(repository);
            var events = new StoreEvents(updates -> {
                // no listeners
            });

            try (var index = direct(repository, layout, new Recorder(), events)) {
                var before = tip(index, layout.workingBranch());
                var text = content();
                var unrelated = commit(repository, layout.workingBranch(),
                        Map.of(path(layout.working(), ".json"), text));
                var name = before.name();
                Files.delete(repository.getDirectory().toPath().resolve("objects").resolve(name.substring(0, 2))
                        .resolve(name.substring(2)));

                events.emit(List.of(new StoreEvents.Update(layout.workingBranch(), name, unrelated.name())));

                assertAll(
                        () -> assertEquals(unrelated, tip(index, layout.workingBranch()), "read in full"),
                        () -> assertEquals(text, index.entries(layout.workingBranch(), layout.documents())
                                .get(layout.working()), "the document of the new tip"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG,
                                "since its indexed commit were not read"),
                        () -> assertNothingLeaks(null, List.of(layout.working().id())));
            }
        }
    }
}
