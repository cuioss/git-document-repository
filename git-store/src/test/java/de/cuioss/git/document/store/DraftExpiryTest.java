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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
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


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.ReferenceUpdater;
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
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
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
import org.eclipse.jgit.revwalk.ObjectWalk;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The expiry of drafts of GDR-STORE-7 with fixed clocks: a draft whose tip is at or before {@code now} minus
 * {@code drafts.expiry} is deleted, a younger one kept, and a draft changed between the read and the delete kept; after
 * the run the objects reachable only from deleted drafts are gone from the object database, while every object
 * reachable from {@code main}, the working branches and the kept drafts is still there. A stale draft whose update is
 * paused between its existence check and its compare-and-swap is not deleted beneath it. A failed garbage collection
 * is passed on as {@link GitOutcome#FAILED} naming the exception class only.
 * <p>
 * Names, ids, pseudonyms, paths, contents, periods and times are generated; the reference prefixes and the details are
 * the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("DraftExpiry (GDR-STORE-7)")
class DraftExpiryTest {

    /** The attempts of a compare-and-delete in the tests that build the expiry directly. */
    private static final int ATTEMPTS = 3;

    /** The time a paused scenario may take before it counts as hanging. */
    private static final long TIMEOUT_SECONDS = 60;

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 6, 16).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-", 8, 24).next();
    }

    /** A content long enough to be unique among the generated contents of a test. */
    private static byte[] content() {
        return Generators.letterStrings(32, 64).next().getBytes(StandardCharsets.UTF_8);
    }

    /** A commit time in whole seconds, the resolution a commit keeps. */
    private static Instant time() {
        return Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
    }

    private static Period expiry() {
        return Period.ofDays(Generators.integers(1, 90).next());
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static DocumentKey key(Kind kind) {
        var collection = value(DocumentCollection.of("c" + word(), kind));
        return value(DocumentKey.of(collection, kind == Kind.PER_DOCUMENT ? Ulid.next() : "R-" + word()));
    }

    /** A configuration tree with the three required keys, the draft expiry given. */
    private static StoreConfiguration configuration(Period expiry) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", Generators.integers(1, 10).next()));
        tree.put("drafts", Map.of("expiry", expiry.toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    private static TechnicalUser user() {
        return new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
    }

    /** The id the object database gives a blob of the content. */
    private static ObjectId blobId(byte[] content) {
        return new ObjectInserter.Formatter().idFor(Constants.OBJ_BLOB, content);
    }

    /** Writes a commit with one file at the given committer time onto a reference, forced. */
    private static ObjectId commit(Repository repository, String ref, byte[] content, Instant when,
            ObjectId... parents) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var blob = inserter.insert(Constants.OBJ_BLOB, content);
            var tree = new TreeFormatter();
            tree.append(word(), FileMode.REGULAR_FILE, blob);
            var commit = new CommitBuilder();
            var ident = new PersonIdent(pseudonym(), "", when, ZoneOffset.UTC);
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
            update.disableRefLog();
            var result = update.update();
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED,
                    () -> "the fixture reference " + ref + " is written: " + result);
            return id;
        }
    }

    /** Every object reachable from the tips: commits, trees and blobs. */
    private static Set<ObjectId> reachable(Repository repository, List<ObjectId> tips) throws IOException {
        Set<ObjectId> ids = new HashSet<>();
        try (ObjectWalk walk = new ObjectWalk(repository)) {
            for (ObjectId tip : tips) {
                walk.markStart(walk.parseAny(tip));
            }
            RevObject object;
            while ((object = walk.next()) != null) {
                ids.add(object.copy());
            }
            while ((object = walk.nextObject()) != null) {
                ids.add(object.copy());
            }
        }
        return ids;
    }

    private static Repository openRepository(Path gitDir) throws IOException {
        return new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build();
    }

    /** Creates an empty bare repository and returns its git directory. */
    private Path bareRepository() throws IOException {
        var gitDir = temp.resolve(word() + ".git");
        try (Repository created = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setBare().build()) {
            created.create(true);
        }
        return gitDir;
    }

    private static StoreWriter open(StoreRepository storeRepository, Path gitDir, StoreConfiguration configuration,
            Instant now) {
        return value(StoreWriter.open(storeRepository, gitDir, user(), configuration,
                new JGitRepositoryOperations(RemotePolicy.localOnly()), Clock.fixed(now, ZoneOffset.UTC)));
    }

    /**
     * A repository whose first detached update of one reference is preceded by another writer moving that reference
     * onto a new commit: the delete of the expiry then meets a reference changed between its read and its delete.
     */
    private static final class MovingRepository extends FileRepository {

        private final String moved;
        private final Instant when;
        private @Nullable ObjectId movedTo;

        MovingRepository(File gitDir, String moved, Instant when) throws IOException {
            super(gitDir);
            this.moved = moved;
            this.when = when;
        }

        @Override
        public RefUpdate updateRef(String ref, boolean detach) throws IOException {
            if (detach && movedTo == null && moved.equals(ref)) {
                Ref current = Objects.requireNonNull(exactRef(ref), "the moved reference exists");
                movedTo = commit(this, ref, content(), when, current.getObjectId());
            }
            return super.updateRef(ref, detach);
        }

        ObjectId movedTo() {
            return Objects.requireNonNull(movedTo, "the reference was moved");
        }
    }

    @Nested
    @DisplayName("Through the writer")
    class ThroughWriter {

        @Test
        @DisplayName("GDR-STORE-7: a stale draft is deleted, a fresh one kept, and only the stale objects collected")
        void deletesStaleKeepsFreshAndCollects(@Remote LocalRemote remote) throws Exception {
            var main = commit(remote.repository(), StoreReferences.MAIN, content(), time());
            var staleKey = key(Kind.PER_DOCUMENT);
            var target = commit(remote.repository(), StoreReferences.target(staleKey), content(), time(), main);
            var expiry = expiry();
            var configuration = configuration(expiry);
            var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
            var gitDir = temp.resolve(word() + ".git");
            var staleAt = time();
            var now = staleAt.atOffset(ZoneOffset.UTC).plus(expiry).toInstant();
            var staleAuthor = Author.of(pseudonym());
            var staleContent = content();
            var staleRef = value(StoreReferences.draft(staleAuthor.pseudonym(), staleKey));
            var freshAuthor = Author.of(pseudonym());
            var freshKey = key(Kind.NUMBERED);
            var freshRef = value(StoreReferences.draft(freshAuthor.pseudonym(), freshKey));

            String staleCommitId;
            try (var staleWriter = open(storeRepository, gitDir, configuration, staleAt)) {
                staleCommitId = value(staleWriter.draft(staleAuthor, staleKey,
                        List.of(new TreeEdit.Put(word(), staleContent)), word()));
            }
            try (var writer = open(storeRepository, gitDir, configuration, now)) {
                var freshCommitId = value(writer.draft(freshAuthor, freshKey,
                        List.of(new TreeEdit.Put(word(), content())), word()));

                ObjectId staleCommit = ObjectId.fromString(staleCommitId);
                List<ObjectId> staleOnly = new ArrayList<>();
                Set<ObjectId> kept;
                try (Repository clone = openRepository(gitDir); RevWalk walk = new RevWalk(clone)) {
                    RevCommit parsed = walk.parseCommit(staleCommit);
                    staleOnly.add(staleCommit);
                    staleOnly.add(parsed.getTree().copy());
                    staleOnly.add(blobId(staleContent));
                    List<ObjectId> keptTips = new ArrayList<>();
                    for (Ref ref : clone.getRefDatabase().getRefsByPrefix(Constants.R_REFS)) {
                        if (!staleRef.equals(ref.getName())) {
                            keptTips.add(ref.getObjectId());
                        }
                    }
                    kept = reachable(clone, keptTips);
                    List<ObjectId> staleBefore = List.copyOf(staleOnly);
                    assertAll("the fixture before the expiry",
                            () -> assertTrue(kept.containsAll(List.of(main, target,
                                    ObjectId.fromString(freshCommitId))), "main, branch and fresh draft are kept tips"),
                            () -> assertTrue(staleBefore.stream().allMatch(id -> !kept.contains(id)),
                                    "the stale objects are reachable from the stale draft only"),
                            () -> assertTrue(staleBefore.stream().allMatch(id -> has(clone, id)),
                                    "the stale objects exist before the expiry"));
                }

                var deleted = value(writer.expireDrafts(now));

                try (Repository clone = openRepository(gitDir)) {
                    Ref fresh = clone.exactRef(freshRef);
                    assertAll(
                            () -> assertEquals(List.of(staleRef), deleted, "the stale draft is reported deleted"),
                            () -> assertNull(clone.exactRef(staleRef), "the stale draft is deleted"),
                            () -> assertNotNull(fresh, "the fresh draft is kept"),
                            () -> assertEquals(freshCommitId, Objects.requireNonNull(fresh).getObjectId().name(),
                                    "the fresh draft is unchanged"),
                            () -> assertTrue(staleOnly.stream().noneMatch(id -> has(clone, id)),
                                    "no object reachable only from the stale draft is left"),
                            () -> assertTrue(kept.stream().allMatch(id -> has(clone, id)),
                                    "every object reachable from main, the branches and the kept draft is present"));
                }
            }
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    GitStoreLogMessages.INFO.DRAFTS_EXPIRED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-STORE-7: a draft one second younger than drafts.expiry is kept")
        void keepsDraftJustYoungerThanExpiry(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN, content(), time());
            var expiry = expiry();
            var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
            var gitDir = temp.resolve(word() + ".git");
            var draftAt = time();
            var author = Author.of(pseudonym());
            var key = key(Kind.MAIN);
            var now = draftAt.atOffset(ZoneOffset.UTC).plus(expiry).toInstant().minusSeconds(1);

            try (var writer = open(storeRepository, gitDir, configuration(expiry), draftAt)) {
                var commitId = value(writer.draft(author, key, List.of(new TreeEdit.Put(word(), content())), word()));

                var deleted = value(writer.expireDrafts(now));

                try (Repository clone = openRepository(gitDir)) {
                    Ref draft = clone.exactRef(value(StoreReferences.draft(author.pseudonym(), key)));
                    assertAll(
                            () -> assertEquals(List.of(), deleted, "no draft is deleted"),
                            () -> assertNotNull(draft, "the draft is kept"),
                            () -> assertEquals(commitId, Objects.requireNonNull(draft).getObjectId().name(),
                                    "the draft is unchanged"),
                            () -> assertTrue(has(clone, ObjectId.fromString(commitId)), "its commit is present"));
                }
            }
        }

        @Test
        @DisplayName("GDR-STORE-7: a null time is refused")
        void refusesNull(@Remote LocalRemote remote) {
            var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
            Instant nullTime = nullValue();

            try (var writer = open(storeRepository, temp.resolve(word() + ".git"), configuration(expiry()), time())) {
                assertThrows(NullPointerException.class, () -> writer.expireDrafts(nullTime));
            }
        }
    }

    /**
     * A clock whose first reading pauses its caller until released: a draft update reads it once, between its
     * existence check and its compare-and-swap.
     */
    private static final class PausingClock extends Clock {

        private final Instant now;
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final CountDownLatch paused = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        PausingClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException("the fixture clock stays in UTC");
        }

        @Override
        public Instant instant() {
            if (armed.compareAndSet(true, false)) {
                paused.countDown();
                try {
                    if (!released.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the paused reading is never released");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("the paused reading is interrupted", e);
                }
            }
            return now;
        }

        boolean awaitPaused() throws InterruptedException {
            return paused.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }
    }

    @Nested
    @DisplayName("Races and failures")
    class RacesAndFailures {

        @Test
        @DisplayName("GDR-STORE-7: a stale draft is not deleted between an update's existence check and its swap")
        void keepsDraftUpdatedDuringExpiry(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN, content(), time());
            var expiry = expiry();
            var configuration = configuration(expiry);
            var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
            var gitDir = temp.resolve(word() + ".git");
            var staleAt = time();
            var now = staleAt.atOffset(ZoneOffset.UTC).plus(expiry).toInstant();
            var author = Author.of(pseudonym());
            var key = key(Kind.PER_DOCUMENT);
            var draftRef = value(StoreReferences.draft(author.pseudonym(), key));
            String staleCommitId;
            try (var staleWriter = open(storeRepository, gitDir, configuration, staleAt)) {
                staleCommitId = value(staleWriter.draft(author, key, List.of(new TreeEdit.Put(word(), content())),
                        word()));
            }
            var clock = new PausingClock(now);
            AtomicReference<StoreResult<List<String>>> expired = new AtomicReference<>();

            String updated;
            try (var writer = value(StoreWriter.open(storeRepository, gitDir, user(), configuration,
                         new JGitRepositoryOperations(RemotePolicy.localOnly()), clock));
                 ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<StoreResult<String>> update = executor.submit(() -> writer.draft(author, key,
                        List.of(new TreeEdit.Put(word(), content())), word()));
                assertTrue(clock.awaitPaused(), "the update pauses before its compare-and-swap");
                var expiryRun = new Thread(() -> expired.set(writer.expireDrafts(now)));
                expiryRun.start();
                ThreadStates.awaitParkedOrEnded(expiryRun, TIMEOUT_SECONDS);
                clock.release();
                updated = value(update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                expiryRun.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                assertFalse(expiryRun.isAlive(), "the expiry ends");
            }

            try (Repository clone = openRepository(gitDir); RevWalk walk = new RevWalk(clone)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(updated));
                Ref draft = clone.exactRef(draftRef);
                assertAll(
                        () -> assertEquals(List.of(ObjectId.fromString(staleCommitId)), List.of(commit.getParents()),
                                "the update stacks on the draft it found, not on nothing"),
                        () -> assertNotNull(draft, "the draft is kept"),
                        () -> assertEquals(updated, Objects.requireNonNull(draft).getObjectId().name(),
                                "the draft points at the update"),
                        () -> assertEquals(List.of(), value(Objects.requireNonNull(expired.get())),
                                "the updated draft is not deleted"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-7: a draft changed between the read and the delete is kept with its new commit")
        void keepsDraftChangedMeanwhile() throws Exception {
            var gitDir = bareRepository();
            var expiry = expiry();
            var draftAt = time();
            var now = draftAt.atOffset(ZoneOffset.UTC).plus(expiry).toInstant();
            var draftRef = value(StoreReferences.draft(pseudonym(), key(Kind.PER_DOCUMENT)));
            ObjectId read;
            try (Repository writer = openRepository(gitDir)) {
                read = commit(writer, draftRef, content(), draftAt);
            }

            try (var repository = new MovingRepository(gitDir.toFile(), draftRef, draftAt)) {
                var expiryRun = new DraftExpiry(word(), repository, new RepositoryGuard(),
                        new ReferenceUpdater(new InCoreWriter()), expiry, ATTEMPTS);

                var deleted = value(expiryRun.expire(now));

                try (Repository clone = openRepository(gitDir)) {
                    Ref draft = clone.exactRef(draftRef);
                    ObjectId movedTo = repository.movedTo();
                    assertAll(
                            () -> assertEquals(List.of(), deleted, "no draft is deleted"),
                            () -> assertNotNull(draft, "the changed draft is kept"),
                            () -> assertEquals(movedTo, Objects.requireNonNull(draft).getObjectId(),
                                    "the draft keeps the commit of the other writer"),
                            () -> assertTrue(has(clone, movedTo), "the new commit is present"),
                            () -> assertTrue(has(clone, read), "the commit that was read, its parent, is present"));
                }
            }
        }

        @Test
        @DisplayName("GDR-STORE-7: an applied-draft reference is deleted with its draft and kept beside a live one")
        void deletesOrphanedAppliedReferences() throws Exception {
            var gitDir = bareRepository();
            var expiry = expiry();
            var now = time();
            var orphanDraft = value(StoreReferences.draft(pseudonym(), key(Kind.PER_DOCUMENT)));
            var liveDraft = value(StoreReferences.draft(pseudonym(), key(Kind.NUMBERED)));
            var orphan = StoreReferences.applied(orphanDraft);
            var live = StoreReferences.applied(liveDraft);
            ObjectId orphanCommit;
            ObjectId liveCommit;
            try (Repository writer = openRepository(gitDir)) {
                orphanCommit = commit(writer, orphan, content(), now);
                liveCommit = commit(writer, live, content(), now);
                commit(writer, liveDraft, content(), now, liveCommit);
            }

            try (Repository repository = openRepository(gitDir)) {
                var deleted = value(new DraftExpiry(word(), repository, new RepositoryGuard(),
                        new ReferenceUpdater(new InCoreWriter()), expiry, ATTEMPTS).expire(now));

                assertAll(
                        () -> assertEquals(List.of(), deleted, "no draft is stale"),
                        () -> assertNull(repository.exactRef(orphan), "the reference without draft is deleted"),
                        () -> assertFalse(has(repository, orphanCommit), "its commit is collected"),
                        () -> assertEquals(liveCommit, Objects.requireNonNull(repository.exactRef(live)).getObjectId(),
                                "the reference of a live draft is kept"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-7: a garbage collection skipped for a held gc.pid is a retryable CONFLICT, no success")
        void skippedGarbageCollectionIsNoSuccess() throws Exception {
            var gitDir = bareRepository();
            var now = time();
            var draftRef = value(StoreReferences.draft(pseudonym(), key(Kind.MAIN)));
            try (Repository writer = openRepository(gitDir)) {
                commit(writer, draftRef, content(), now);
            }
            Files.writeString(gitDir.resolve(DraftExpiry.GC_PID), word() + " " + word());

            StoreResult<List<String>> result;
            try (Repository repository = openRepository(gitDir)) {
                result = new DraftExpiry(word(), repository, new RepositoryGuard(),
                        new ReferenceUpdater(new InCoreWriter()), expiry(), ATTEMPTS).expire(now);
            }

            assertAll(
                    () -> assertEquals(new StoreResult<List<String>>(new StoreResult.PassedOn(GitOutcome.CONFLICT),
                            null, DraftExpiry.GC_SKIPPED), result, "the skipped collection is a conflict"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            GitStoreLogMessages.WARN.GARBAGE_COLLECTION_SKIPPED.resolveIdentifierString()));
        }

        @Test
        @DisplayName("GDR-STORE-7: a failed garbage collection is passed on as FAILED naming the exception class only")
        void passesOnFailedGarbageCollection() throws Exception {
            var gitDir = bareRepository();
            var now = time();
            var draftRef = value(StoreReferences.draft(pseudonym(), key(Kind.MAIN)));
            try (Repository writer = openRepository(gitDir)) {
                commit(writer, draftRef, content(), now);
            }
            File pack = Files.createDirectories(gitDir.resolve("objects").resolve("pack")).toFile();
            assertTrue(pack.setWritable(false), "the pack directory is made read-only");
            try {
                assumeFalse(Files.isWritable(pack.toPath()), "the user can still write a read-only directory");
                var alias = word();

                StoreResult<List<String>> result;
                try (Repository repository = openRepository(gitDir)) {
                    result = new DraftExpiry(alias, repository, new RepositoryGuard(),
                            new ReferenceUpdater(new InCoreWriter()), expiry(), ATTEMPTS).expire(now);
                }

                var detail = Objects.requireNonNull(result.detail(), "a failure carries a detail");
                assertAll(
                        () -> assertEquals(new StoreResult.PassedOn(GitOutcome.FAILED), result.outcome(),
                                "the failure is passed on"),
                        () -> assertEquals("gc: " + JGitInternalException.class.getSimpleName(), detail,
                                "the detail names the exception class"),
                        () -> assertFalse(detail.contains(temp.toString()), "the detail names no path"));
                LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                        GitStoreLogMessages.WARN.GARBAGE_COLLECTION_FAILED.resolveIdentifierString());
            } finally {
                assertTrue(pack.setWritable(true), "the pack directory is writable again");
            }
        }
    }

    private static boolean has(Repository repository, ObjectId id) {
        try {
            return repository.getObjectDatabase().has(id);
        } catch (IOException e) {
            throw new IllegalStateException("the object database is readable", e);
        }
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null check. */
    @SuppressWarnings("unchecked")
    private static <T> T nullValue() {
        return (T) null;
    }
}
