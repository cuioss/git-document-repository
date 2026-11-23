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
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;


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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Per-branch post-commit listeners against a {@code git-testing} local remote (GDR-STORE-10): the calls after a pushed
 * save and a release, the oldest-first delivery of a fetch that moved a branch by several commits, an unsubscribed
 * branch, a throwing listener that leaves its trigger unchanged, a listener that saves from within its call, a closed
 * subscription, and no progress kept across writers. With every log record captured down to trace, no path, content
 * or pseudonym reaches a log record (GDR-NFR-3).
 * <p>
 * Names, ids, pseudonyms, paths and contents are generated, long enough to appear nowhere by chance; the reference
 * layout is the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("Post-commit listeners (GDR-STORE-10)")
class CommitListenerTest {

    /** The attempts of every save and release. */
    private static final int ATTEMPTS = 3;

    /** The deadline of every wait for another thread. */
    private static final long WAIT_SECONDS = 10;

    /**
     * The reference the dispatch tests write their later commits onto, outside {@code refs/heads/}, so a subscription
     * still starts at the branch tip the test set before; the dispatch reads the commits by the ids of its events.
     */
    private static final String SCRATCH = "refs/scratch/commits";

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

    private static StoreConfiguration configuration() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", ATTEMPTS));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
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

    private static void assertNothingLeaks(List<String> secrets) {
        List<String> texts = logTexts();
        for (String secret : secrets) {
            assertTrue(texts.stream().noneMatch(text -> text.contains(secret)),
                    () -> "a log record carries a path, a content or a pseudonym");
        }
    }

    /** A listener that records the commits it is called with, in order. */
    private static final class Recording implements CommitListener {

        private final List<String> commits = Collections.synchronizedList(new ArrayList<>());
        private final List<String> branches = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void committed(String branch, String commitId) {
            branches.add(branch);
            commits.add(commitId);
        }

        List<String> commits() {
            synchronized (commits) {
                return List.copyOf(commits);
            }
        }
    }

    /** The remote of every test: {@code main} with one file, and a working branch of one document forked from it. */
    private record Layout(DocumentKey key, ObjectId mainTip, ObjectId workingTip, Map<String, String> workingTree) {

        String working() {
            return StoreReferences.target(key);
        }

        String file() {
            return key.collection().name() + "/" + key.id() + ".json";
        }
    }

    private static Layout layout(Repository remote) {
        var collection = value(DocumentCollection.of("c" + word(), Kind.PER_DOCUMENT));
        var key = value(DocumentKey.of(collection, Ulid.next()));
        Map<String, String> main = new TreeMap<>(Map.of(word() + ".txt", content()));
        var mainTip = commit(remote, StoreReferences.MAIN, main);
        Map<String, String> workingTree = new TreeMap<>(main);
        workingTree.put(collection.name() + "/" + key.id() + ".json", content());
        var workingTip = commit(remote, StoreReferences.target(key), workingTree, mainTip);
        return new Layout(key, mainTip, workingTip, workingTree);
    }

    private StoreWriter open(LocalRemote remote, Path gitDir) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
        return value(StoreWriter.open(storeRepository, gitDir, user, configuration(), TestKeys.ed25519().source(),
                new ScriptedRepositoryOperations(), Clock.fixed(now, ZoneOffset.UTC)));
    }

    private StoreWriter open(LocalRemote remote) {
        return open(remote, temp.resolve(word() + ".git"));
    }

    /** Writes a draft of one file of the document and saves it. */
    private static SaveResult save(StoreWriter writer, Author author, DocumentKey key, String path, String text) {
        value(writer.draft(author, key, List.of(new TreeEdit.Put(path, text.getBytes(StandardCharsets.UTF_8))),
                word()));
        return writer.save(author, CLAIMS, key, Map.of(), word());
    }

    private static String saved(SaveResult result) {
        return assertInstanceOf(SaveResult.Saved.class, result, () -> "saved: " + result).commitId();
    }

    @Test
    @DisplayName("GDR-STORE-10: a listener is called after a pushed save and after a release, oldest first")
    void calledAfterSaveAndRelease(@Remote LocalRemote remote) {
        var layout = layout(remote.repository());
        var author = Author.of(pseudonym());
        var onWorking = new Recording();
        var onMain = new Recording();
        var text = content();

        try (var writer = open(remote); var working = writer.subscribe(layout.working(), onWorking);
             var main = writer.subscribe("main", onMain)) {
            var commit = saved(save(writer, author, layout.key(), layout.file(), text));
            var release = value(writer.release(author, CLAIMS, layout.key()));

            List<String> mainCommits = onMain.commits();
            assertAll(
                    () -> assertEquals(List.of(commit), onWorking.commits(), "the save commit of the working branch"),
                    () -> assertEquals(List.of(layout.working()), onWorking.branches, "with the full branch name"),
                    () -> assertEquals(release.commitId(), mainCommits.getLast(),
                            "the release commit is the newest commit of main"),
                    () -> assertTrue(mainCommits.indexOf(commit) >= 0
                            && mainCommits.indexOf(commit) < mainCommits.indexOf(release.commitId()),
                            "the merged save commit comes before the release commit"),
                    () -> assertFalse(mainCommits.contains(layout.mainTip().name()),
                            "nothing older than the subscription"),
                    () -> assertNothingLeaks(List.of(author.pseudonym(), text, layout.file())));
        }
    }

    @Test
    @DisplayName("GDR-STORE-10: a fetch that moves a branch by several commits delivers them oldest first")
    void fetchDeliversOldestFirst(@Remote LocalRemote remote) {
        var layout = layout(remote.repository());
        var listener = new Recording();

        try (var writer = open(remote); var subscription = writer.subscribe(layout.working(), listener)) {
            List<String> outside = new ArrayList<>();
            ObjectId parent = layout.workingTip();
            for (int i = 0; i < 3; i++) {
                Map<String, String> tree = new TreeMap<>(layout.workingTree());
                tree.put(layout.file(), content());
                parent = commit(remote.repository(), layout.working(), tree, parent);
                outside.add(parent.name());
            }

            var moved = value(writer.fetch());

            assertAll(
                    () -> assertEquals(List.of(layout.working()), moved, "the fetch moved the working branch"),
                    () -> assertEquals(outside, listener.commits(), "each new commit, oldest first"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-10: an unsubscribed branch and a closed subscription are not called")
    void unsubscribedAndClosedAreNotCalled(@Remote LocalRemote remote) {
        var layout = layout(remote.repository());
        var author = Author.of(pseudonym());
        var onMain = new Recording();
        var closed = new Recording();

        try (var writer = open(remote); var main = writer.subscribe(StoreReferences.MAIN, onMain)) {
            writer.subscribe(layout.working(), closed).close();

            saved(save(writer, author, layout.key(), layout.file(), content()));

            assertAll(
                    () -> assertEquals(List.of(), onMain.commits(), "main did not move"),
                    () -> assertEquals(List.of(), closed.commits(), "the closed subscription is not called"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-10: a throwing listener leaves the save, release and fetch unchanged, logged by class only")
    void throwingListenerNeverFailsItsTrigger(@Remote LocalRemote remote) {
        var layout = layout(remote.repository());
        var author = Author.of(pseudonym());
        var secret = content();
        var after = new Recording();
        CommitListener throwing = (branch, commitId) -> {
            throw new IllegalStateException(secret);
        };

        try (var writer = open(remote); var first = writer.subscribe(layout.working(), throwing);
             var onMain = writer.subscribe(StoreReferences.MAIN, throwing);
             var second = writer.subscribe(layout.working(), after)) {
            var saveResult = save(writer, author, layout.key(), layout.file(), content());
            var release = writer.release(author, CLAIMS, layout.key());
            Map<String, String> tree = new TreeMap<>(layout.workingTree());
            tree.put(layout.file(), content());
            var outside = commit(remote.repository(), layout.working(), tree,
                    ObjectId.fromString(saved(saveResult)));
            var fetched = writer.fetch();
            String identifier = GitStoreLogMessages.WARN.LISTENER_FAILED.resolveIdentifierString();
            List<LogRecord> failures = TestLoggerFactory.getTestHandler().getRecords().stream()
                    .filter(logRecord -> String.valueOf(logRecord.getMessage()).contains(identifier)).toList();

            assertAll(
                    () -> assertInstanceOf(SaveResult.Saved.class, saveResult, "the save is saved"),
                    () -> assertTrue(release.isOk(), "the release is released"),
                    () -> assertTrue(fetched.isOk(), "the fetch fetched"),
                    () -> assertEquals(List.of(saved(saveResult), outside.name()), after.commits(),
                            "the next listener of the branch is still called"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, identifier),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            IllegalStateException.class.getSimpleName()),
                    () -> assertFalse(failures.isEmpty(), "every failure is logged"),
                    () -> failures.forEach(logRecord -> assertNull(logRecord.getThrown(), "no throwable attached")),
                    () -> assertNothingLeaks(List.of(secret, author.pseudonym(), layout.file())));
        }
    }

    @Test
    @DisplayName("GDR-STORE-10: a listener that saves from within its call gets its own commit after it returned")
    void listenerThatSaves(@Remote LocalRemote remote) {
        var layout = layout(remote.repository());
        var author = Author.of(pseudonym());
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        List<SaveResult> inner = Collections.synchronizedList(new ArrayList<>());

        try (var writer = open(remote)) {
            CommitListener saving = (branch, commitId) -> {
                calls.add("start " + commitId);
                if (inner.isEmpty()) {
                    inner.add(save(writer, author, layout.key(), layout.file(), content()));
                }
                calls.add("end " + commitId);
            };
            try (var subscription = writer.subscribe(layout.working(), saving)) {
                var outer = saved(save(writer, author, layout.key(), layout.file(), content()));

                var innerCommit = saved(inner.getFirst());
                assertEquals(List.of("start " + outer, "end " + outer, "start " + innerCommit, "end " + innerCommit),
                        List.copyOf(calls), "the inner save is delivered after the call that made it returned");
            }
        }
    }

    /** The branch and the tree of a branch commit of the dispatch tests. */
    private static String branch() {
        return StoreReferences.MAIN.replace("main", "b" + word());
    }

    private static Map<String, String> files() {
        return Map.of(word(), content());
    }

    @Test
    @DisplayName("GDR-STORE-10: two moves of a new branch dispatched out of order deliver each commit once, oldest first")
    void outOfOrderMovesDeliverEveryCommitOnce(@Remote LocalRemote remote) {
        var repository = remote.repository();
        var mainTip = commit(repository, StoreReferences.MAIN, files());
        var branch = branch();
        var dispatch = new ListenerDispatch(word(), repository);
        var listener = new Recording();

        try (var subscription = dispatch.subscribe(branch, listener)) {
            var first = commit(repository, branch, files(), mainTip);
            var second = commit(repository, branch, files(), first);

            dispatch.moved(List.of(new StoreEvents.Update(branch, first.name(), second.name())));
            dispatch.moved(List.of(new StoreEvents.Update(branch, null, first.name())));

            assertEquals(List.of(first.name(), second.name()), listener.commits(),
                    "both commits, oldest first, none twice, nothing of main");
        }
    }

    @Test
    @DisplayName("GDR-STORE-10: a new branch merged into main before its event still delivers its commit")
    void newBranchMergedBeforeItsEventDelivers(@Remote LocalRemote remote) {
        var repository = remote.repository();
        var mainTip = commit(repository, StoreReferences.MAIN, files());
        var branch = branch();
        var dispatch = new ListenerDispatch(word(), repository);
        var listener = new Recording();

        try (var subscription = dispatch.subscribe(branch, listener)) {
            var saved = commit(repository, branch, files(), mainTip);
            commit(repository, StoreReferences.MAIN, files(), mainTip, saved);

            dispatch.moved(List.of(new StoreEvents.Update(branch, null, saved.name())));

            assertEquals(List.of(saved.name()), listener.commits(), "the saved commit, though main now holds it");
        }
    }

    @Test
    @DisplayName("GDR-STORE-10: listeners that save onto each other's branch on two threads do not deadlock")
    void crossSubscriptionDeliveryDoesNotDeadlock(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var xBranch = branch();
        var yBranch = branch();
        var x0 = commit(repository, xBranch, files());
        var y0 = commit(repository, yBranch, files());
        var x1 = commit(repository, SCRATCH, files(), x0);
        var x2 = commit(repository, SCRATCH, files(), x1);
        var y1 = commit(repository, SCRATCH, files(), y0);
        var y2 = commit(repository, SCRATCH, files(), y1);
        var dispatch = new ListenerDispatch(word(), repository);
        var bothInside = new CountDownLatch(2);
        var onX = new Recording();
        var onY = new Recording();

        try (var xSubscription = dispatch.subscribe(xBranch,
                     crossing(onX, bothInside, dispatch, new StoreEvents.Update(yBranch, y1.name(), y2.name())));
             var ySubscription = dispatch.subscribe(yBranch,
                     crossing(onY, bothInside, dispatch, new StoreEvents.Update(xBranch, x1.name(), x2.name())))) {
            var first = new Thread(() -> dispatch.moved(List.of(new StoreEvents.Update(xBranch, x0.name(),
                    x1.name()))));
            var second = new Thread(() -> dispatch.moved(List.of(new StoreEvents.Update(yBranch, y0.name(),
                    y1.name()))));
            first.start();
            second.start();
            first.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            second.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

            assertAll(
                    () -> assertFalse(first.isAlive(), "the first trigger ended"),
                    () -> assertFalse(second.isAlive(), "the second trigger ended"),
                    () -> assertEquals(List.of(x1.name(), x2.name()), onX.commits(), "every commit of x once"),
                    () -> assertEquals(List.of(y1.name(), y2.name()), onY.commits(), "every commit of y once"));
        }
    }

    /**
     * A listener that records its commits and, on its first call, waits until the other listener is inside its call
     * too, then moves the other branch, as a save onto it from within the call would.
     */
    private static CommitListener crossing(Recording recording, CountDownLatch bothInside, ListenerDispatch dispatch,
            StoreEvents.Update other) {
        boolean[] crossed = new boolean[1];
        return (branch, commitId) -> {
            recording.committed(branch, commitId);
            if (!crossed[0]) {
                crossed[0] = true;
                bothInside.countDown();
                try {
                    assertTrue(bothInside.await(WAIT_SECONDS, TimeUnit.SECONDS), "both listeners are inside");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                dispatch.moved(List.of(other));
            }
        };
    }

    @Test
    @DisplayName("GDR-STORE-10: close waits for a delivery in progress; no call starts after it returned")
    void closeWaitsForTheDeliveryInProgress(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var branch = branch();
        var start = commit(repository, branch, files());
        var first = commit(repository, SCRATCH, files(), start);
        var second = commit(repository, SCRATCH, files(), first);
        var dispatch = new ListenerDispatch(word(), repository);
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var listener = new Recording();
        var subscription = dispatch.subscribe(branch, (name, commitId) -> {
            listener.committed(name, commitId);
            inside.countDown();
            try {
                assertTrue(release.await(WAIT_SECONDS, TimeUnit.SECONDS), "released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        var delivery = new Thread(() -> dispatch.moved(List.of(new StoreEvents.Update(branch, start.name(),
                second.name()))));
        delivery.start();
        assertTrue(inside.await(WAIT_SECONDS, TimeUnit.SECONDS), "the delivery is inside the call");
        var closer = new Thread(subscription::close);
        closer.start();
        ThreadStates.awaitParkedOrEnded(closer, WAIT_SECONDS);
        boolean waited = closer.isAlive();

        release.countDown();
        closer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        delivery.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

        assertAll(
                () -> assertTrue(waited, "close waits while the listener is inside its call"),
                () -> assertFalse(closer.isAlive(), "close returned after the call"),
                () -> assertEquals(List.of(first.name()), listener.commits(),
                        "the second commit is not called after close"));
    }

    @Test
    @DisplayName("GDR-STORE-10: nothing is persisted; a new writer over the clone delivers nothing old")
    void noProgressKeptAcrossWriters(@Remote LocalRemote remote) {
        var layout = layout(remote.repository());
        var author = Author.of(pseudonym());
        var gitDir = temp.resolve(word() + ".git");
        try (var first = open(remote, gitDir)) {
            saved(save(first, author, layout.key(), layout.file(), content()));
        }
        var listener = new Recording();

        try (var second = open(remote, gitDir); var subscription = second.subscribe(layout.working(), listener)) {
            var nothingMoved = value(second.fetch());
            var commit = saved(save(second, author, layout.key(), layout.file(), content()));

            assertAll(
                    () -> assertEquals(List.of(), nothingMoved, "the clone is current"),
                    () -> assertEquals(List.of(commit), listener.commits(),
                            "only the commit after the subscription is delivered"));
        }
    }
}
