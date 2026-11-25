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
import java.io.UncheckedIOException;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;


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
import org.eclipse.jgit.api.Git;
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
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The drafts of GDR-STORE-2 against a {@code git-testing} local remote: an intermediate save is a commit on the draft
 * reference whose parent is the tip of the target branch (else {@code main}, else none), unsigned and without identity
 * trailers (GDR-STORE-12, GDR-STORE-13), a second save stacks on the
 * first, the listing returns the drafts of one user only, a push of every reference through the configured push
 * reference specifications never carries {@code refs/drafts/*}, and the commit identities follow {@code commit.email}
 * (GDR-NFR-3). A draft deleted beneath a write, or whose publication met a held lock, is written again on its base
 * instead of becoming a root commit, a refused first write publishes no draft, and the write ends in a retryable
 * {@link GitOutcome#CONFLICT} when the lock is held on every attempt.
 * <p>
 * Names, ids, pseudonyms, paths, contents, addresses and times are generated; the configuration keys, the reference
 * prefixes and the details are the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@DisplayName("StoreWriter drafts (GDR-STORE-2)")
class StoreWriterDraftTest {

    /** The attempts of the tests that build the draft references directly. */
    private static final int ATTEMPTS = 3;

    /** The time a race scenario may take before it counts as hanging. */
    private static final long TIMEOUT_SECONDS = 60;

    /** The fixed claims of the saves. */
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

    private static String address() {
        return word() + "@" + word() + ".example";
    }

    private static byte[] content() {
        return Generators.letterStrings(1, 64).next().getBytes(StandardCharsets.UTF_8);
    }

    /** A commit time in whole seconds, the resolution a commit keeps. */
    private static Instant time() {
        return Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static DocumentKey key(Kind kind) {
        var collection = value(DocumentCollection.of("c" + word(), kind));
        return value(DocumentKey.of(collection, kind == Kind.PER_DOCUMENT ? Ulid.next() : "R-" + word()));
    }

    /** A configuration tree with the three required keys and, when not {@code null}, {@code commit.email}. */
    private static StoreConfiguration configuration(@Nullable Boolean email) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", Generators.integers(1, 10).next()));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", word()));
        if (email != null) {
            tree.put("commit", Map.of("email", email));
        }
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    /** Writes a commit with one file onto a reference of a repository, with the given parent or none. */
    private static ObjectId commit(Repository repository, String ref, String file, ObjectId... parents)
            throws IOException {
        return commit(repository, ref, List.of(file), parents);
    }

    /** Writes a commit with the files, each with a generated content, onto a reference, with the given parents. */
    private static ObjectId commit(Repository repository, String ref, List<String> files, ObjectId... parents)
            throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            for (String file : files.stream().sorted().toList()) {
                tree.append(file, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, content()));
            }
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
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED,
                    () -> "the fixture reference " + ref + " is written: " + result);
            return id;
        }
    }

    private static List<TreeEdit> put(String path) {
        return List.of(new TreeEdit.Put(path, content()));
    }

    /** The writer under test together with its clone directory, its technical user and its clock. */
    private record Fixture(StoreWriter writer, Path gitDir, TechnicalUser user, Instant now) implements AutoCloseable {

        Repository openClone() throws IOException {
            return new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build();
        }

        RevCommit parse(String commitId) throws IOException {
            try (Repository clone = openClone()) {
                return StoreWriterDraftTest.parse(clone, commitId);
            }
        }

        boolean contains(String commitId, String path) throws IOException {
            try (Repository clone = openClone()) {
                return StoreWriterDraftTest.contains(clone, commitId, path);
            }
        }

        ObjectId blobAt(String commitId, String path) throws IOException {
            try (Repository clone = openClone()) {
                return StoreWriterDraftTest.blobAt(clone, commitId, path);
            }
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    /** The blob id of a path on a commit; the path must exist. */
    private static ObjectId blobAt(Repository repository, String commitId, String path) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
            try (TreeWalk found = TreeWalk.forPath(repository, path, commit.getTree())) {
                return Objects.requireNonNull(found, "the path exists on the commit").getObjectId(0).copy();
            }
        }
    }

    /** The id the object database gives a blob of the content. */
    private static ObjectId blobId(byte[] content) {
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
            return formatter.idFor(Constants.OBJ_BLOB, content);
        }
    }

    private static RevCommit parse(Repository repository, String commitId) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
            walk.parseBody(commit);
            return commit;
        }
    }

    private static boolean contains(Repository repository, String commitId, String path) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
            try (TreeWalk found = TreeWalk.forPath(repository, path, commit.getTree())) {
                return found != null;
            }
        }
    }

    /**
     * A clock that deletes a reference, when it exists, on each of its first readings: another writer, a save or an
     * expiry, deleting the draft between the draft write's read of it and its compare-and-swap, the one reading in
     * between.
     */
    private static final class DeletingClock extends Clock {

        private final Repository repository;
        private final String deleted;
        private final Instant now;
        private final int times;
        private int deletions;

        DeletingClock(Repository repository, String deleted, Instant now, int times) {
            this.repository = repository;
            this.deleted = deleted;
            this.now = now;
            this.times = times;
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
            try {
                if (deletions < times && repository.exactRef(deleted) != null) {
                    RefUpdate delete = repository.updateRef(deleted);
                    delete.setForceUpdate(true);
                    delete.disableRefLog();
                    var result = delete.delete();
                    assertEquals(RefUpdate.Result.FORCED, result, "the fixture deletes the draft");
                    deletions++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return now;
        }

        int deletions() {
            return deletions;
        }
    }

    /**
     * A repository whose first updates of one reference meet its lock file held, as by a writer outside the process,
     * and whose later updates find it released: JGit 7.8.0 then reports {@link RefUpdate.Result#LOCK_FAILURE} for a
     * creation although the reference does not exist.
     */
    private static final class LockingRepository extends FileRepository {

        private final String locked;
        private final Path lockFile;
        private final int times;
        private int updates;

        LockingRepository(Path gitDir, String locked, int times) throws IOException {
            super(gitDir.toFile());
            this.locked = locked;
            this.lockFile = gitDir.resolve(locked + ".lock");
            this.times = times;
        }

        @Override
        public RefUpdate updateRef(String ref, boolean detach) throws IOException {
            if (!detach && locked.equals(ref)) {
                if (updates < times) {
                    Files.createDirectories(lockFile.getParent());
                    if (!Files.exists(lockFile)) {
                        Files.createFile(lockFile);
                    }
                } else {
                    Files.deleteIfExists(lockFile);
                }
                updates++;
            }
            return super.updateRef(ref, detach);
        }
    }

    private Fixture open(LocalRemote remote, StoreConfiguration configuration, TechnicalUser user) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var gitDir = temp.resolve(word() + ".git");
        var now = time();
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration, List.of(),
                TestKeys.ed25519().source(), new JGitRepositoryOperations(RemotePolicy.localOnly()),
                Clock.fixed(now, ZoneOffset.UTC)));
        return new Fixture(writer, gitDir, user, now);
    }

    private Fixture open(LocalRemote remote) {
        return open(remote, configuration(null), user(null));
    }

    private static TechnicalUser user(@Nullable String email) {
        return new TechnicalUser(word(), email, new UsernamePasswordCredentialsProvider(word(), word()));
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> T nullValue() {
        // the null literal converts to every reference type, so no unchecked cast is needed
        return null;
    }

    @Nested
    @DisplayName("Intermediate saves")
    class IntermediateSaves {

        @Test
        @DisplayName("GDR-STORE-2: an intermediate save is a commit on the draft reference with the branch tip as parent")
        void draftOnTargetBranch(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var baseFile = word();
            commit(remote.repository(), StoreReferences.MAIN, word());
            var target = commit(remote.repository(), StoreReferences.target(key), baseFile);
            var baseBlob = blobAt(remote.repository(), target.name(), baseFile);
            var author = Author.of(pseudonym());
            var path = word();
            var content = content();
            var message = word();

            try (var fixture = open(remote)) {
                var commitId = value(fixture.writer().draft(author, key, List.of(new TreeEdit.Put(path, content)),
                        message));

                var commit = fixture.parse(commitId);
                try (Repository clone = fixture.openClone()) {
                    var draftRef = clone.exactRef(value(StoreReferences.draft(author.pseudonym(), key)));
                    assertAll(
                            () -> assertNotNull(draftRef, "the draft reference exists"),
                            () -> assertEquals(commitId, Objects.requireNonNull(draftRef).getObjectId().name(),
                                    "the draft reference points at the commit"),
                            () -> assertEquals(List.of(target), List.of(commit.getParents()),
                                    "the parent is the tip of the target branch"),
                            () -> assertEquals(message, commit.getFullMessage(),
                                    "the message as given, without identity trailers"),
                            () -> assertNull(commit.getRawGpgSignature(), "a draft commit is unsigned"),
                            () -> assertEquals(blobId(content), fixture.blobAt(commitId, path),
                                    "the edit is applied with its content"),
                            () -> assertEquals(baseBlob, fixture.blobAt(commitId, baseFile),
                                    "the content of the branch tip is kept"));
                }
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: without a target branch the first draft commit has the tip of main as parent")
        void draftOnMain(@Remote LocalRemote remote) throws Exception {
            var main = commit(remote.repository(), StoreReferences.MAIN, word());
            var key = key(Kind.NUMBERED);

            try (var fixture = open(remote)) {
                var commitId = value(fixture.writer().draft(Author.of(pseudonym()), key, put(word()), word()));

                assertEquals(List.of(main), List.of(fixture.parse(commitId).getParents()),
                        "the parent is the tip of main");
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: in an empty repository the first draft commit is a root commit")
        void draftInEmptyRepository(@Remote LocalRemote remote) throws Exception {
            try (var fixture = open(remote)) {
                var commitId = value(fixture.writer().draft(Author.of(pseudonym()), key(Kind.MAIN), put(word()),
                        word()));

                assertEquals(0, fixture.parse(commitId).getParentCount(), "no parent");
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: a second intermediate save stacks on the first")
        void secondSaveStacks(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN, word());
            var key = key(Kind.PER_DOCUMENT);
            var author = Author.of(pseudonym());
            var firstPath = word();
            var secondPath = firstPath + "x";
            var firstContent = content();
            var secondContent = content();

            try (var fixture = open(remote)) {
                var first = value(fixture.writer().draft(author, key,
                        List.of(new TreeEdit.Put(firstPath, firstContent)), word()));
                var second = value(fixture.writer().draft(author, key,
                        List.of(new TreeEdit.Put(secondPath, secondContent)), word()));

                assertAll(
                        () -> assertEquals(List.of(ObjectId.fromString(first)),
                                List.of(fixture.parse(second).getParents()), "the parent is the first draft commit"),
                        () -> assertEquals(blobId(firstContent), fixture.blobAt(second, firstPath),
                                "the first edit is kept with its content"),
                        () -> assertEquals(blobId(secondContent), fixture.blobAt(second, secondPath),
                                "the second edit is applied with its content"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: a pseudonym outside [A-Za-z0-9-]+ is refused without naming it")
        void invalidPseudonym(@Remote LocalRemote remote) {
            var invalid = word() + "/" + word();

            try (var fixture = open(remote)) {
                assertAll(
                        () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "pseudonym: invalid"),
                                fixture.writer().draft(Author.of(invalid), key(Kind.MAIN), put(word()), word()),
                                "the draft is refused"),
                        () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "pseudonym: invalid"),
                                fixture.writer().drafts(invalid), "the listing is refused"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: null arguments are refused")
        void refusesNull(@Remote LocalRemote remote) {
            try (var fixture = open(remote)) {
                var writer = fixture.writer();
                var author = Author.of(pseudonym());
                var key = key(Kind.MAIN);
                var edits = put(word());
                var message = word();
                Author nullAuthor = nullValue();
                DocumentKey nullKey = nullValue();
                List<TreeEdit> nullEdits = nullValue();
                String nullText = nullValue();
                assertAll(
                        () -> assertThrows(NullPointerException.class,
                                () -> writer.draft(nullAuthor, key, edits, message), "author"),
                        () -> assertThrows(NullPointerException.class,
                                () -> writer.draft(author, nullKey, edits, message), "key"),
                        () -> assertThrows(NullPointerException.class,
                                () -> writer.draft(author, key, nullEdits, message), "edits"),
                        () -> assertThrows(NullPointerException.class,
                                () -> writer.draft(author, key, edits, nullText), "message"),
                        () -> assertThrows(NullPointerException.class, () -> writer.drafts(nullText), "pseudonym"));
            }
        }
    }

    @Nested
    @DisplayName("Races of the draft reference")
    class Races {

        /** Creates an empty bare repository and returns its git directory. */
        private Path bareRepository() throws IOException {
            var gitDir = temp.resolve(word() + ".git");
            try (Repository created = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setBare().build()) {
                created.create(true);
            }
            return gitDir;
        }

        private static DraftReferences drafts(Repository repository, Clock clock) {
            return new DraftReferences(repository, new ReferenceUpdater(new InCoreWriter()),
                    new CommitIdentities(configuration(null).commit(), user(null)), clock, ATTEMPTS);
        }

        @Test
        @DisplayName("GDR-STORE-2: a draft deleted before its compare-and-swap is created again on main, not as a root")
        void draftDeletedBeforeSwap() throws Exception {
            var gitDir = bareRepository();
            var kept = word();
            var gone = kept + "x";
            var author = Author.of(pseudonym());
            var key = key(Kind.NUMBERED);
            var draftRef = value(StoreReferences.draft(author.pseudonym(), key));

            var earlier = gone + "e";

            try (Repository repository = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true)
                         .build()) {
                var main = commit(repository, StoreReferences.MAIN, List.of(kept, gone));
                value(drafts(repository, Clock.fixed(time(), ZoneOffset.UTC)).write(author, key, put(earlier),
                        word()));
                var clock = new DeletingClock(repository, draftRef, time(), 1);

                var commitId = value(drafts(repository, clock).write(author, key, List.of(new TreeEdit.Delete(gone)),
                        word()));

                var commit = parse(repository, commitId);
                assertAll(
                        () -> assertEquals(1, clock.deletions(), "the draft was deleted beneath the write once"),
                        () -> assertEquals(List.of(main), List.of(commit.getParents()), "the parent is the tip of main"),
                        () -> assertTrue(contains(repository, commitId, kept), "the other file of main is kept"),
                        () -> assertFalse(contains(repository, commitId, gone), "the deleted file is gone"),
                        () -> assertFalse(contains(repository, commitId, earlier), "the deleted draft is not revived"),
                        () -> assertEquals(commitId,
                                Objects.requireNonNull(repository.exactRef(draftRef)).getObjectId().name(),
                                "the draft points at the commit"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: a refused first write publishes no draft, so none is listed")
        void refusedFirstWritePublishesNoDraft() throws Exception {
            var gitDir = bareRepository();
            var author = Author.of(pseudonym());
            var key = key(Kind.PER_DOCUMENT);
            var draftRef = value(StoreReferences.draft(author.pseudonym(), key));
            var path = word();

            try (Repository repository = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true)
                         .build()) {
                commit(repository, StoreReferences.MAIN, word());
                var drafts = drafts(repository, Clock.fixed(time(), ZoneOffset.UTC));

                var result = drafts.write(author, key,
                        List.of(new TreeEdit.Put(path, content()), new TreeEdit.Put(path, content())), word());

                assertAll(
                        () -> assertFalse(result.isOk(), () -> "the write is refused: " + result),
                        () -> assertNull(repository.exactRef(draftRef), "no draft is published"),
                        () -> assertEquals(List.of(), value(drafts.list(author.pseudonym())), "no draft is listed"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: a creation that meets a held lock while the draft is absent selects the base again")
        void creationLockFailureWithoutDraft() throws Exception {
            var gitDir = bareRepository();
            var author = Author.of(pseudonym());
            var key = key(Kind.PER_DOCUMENT);
            var draftRef = value(StoreReferences.draft(author.pseudonym(), key));
            var path = word();
            ObjectId main;
            try (Repository writer = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true)
                         .build()) {
                main = commit(writer, StoreReferences.MAIN, word());
            }

            try (var repository = new LockingRepository(gitDir, draftRef, 1)) {
                var commitId = value(drafts(repository, Clock.fixed(time(), ZoneOffset.UTC)).write(author, key,
                        put(path), word()));

                var commit = parse(repository, commitId);
                assertAll(
                        () -> assertEquals(List.of(main), List.of(commit.getParents()), "the parent is the tip of main"),
                        () -> assertTrue(contains(repository, commitId, path), "the edit is applied"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: a creation whose lock stays held ends in a retryable CONFLICT without a draft")
        void creationLockNeverAcquired() throws Exception {
            var gitDir = bareRepository();
            var author = Author.of(pseudonym());
            var key = key(Kind.MAIN);
            var draftRef = value(StoreReferences.draft(author.pseudonym(), key));
            try (Repository writer = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true)
                         .build()) {
                commit(writer, StoreReferences.MAIN, word());
            }

            try (var repository = new LockingRepository(gitDir, draftRef, Integer.MAX_VALUE)) {
                var result = drafts(repository, Clock.fixed(time(), ZoneOffset.UTC)).write(author, key, put(word()),
                        word());

                assertAll(
                        () -> assertEquals(new StoreResult.PassedOn(GitOutcome.CONFLICT), result.outcome(),
                                "the write is a retryable conflict"),
                        () -> assertNull(repository.exactRef(draftRef), "no draft is created"));
            }
        }
    }

    @Nested
    @DisplayName("Clones of the writers")
    class Clones {

        private StoreRepository registered(LocalRemote remote) {
            return new StoreRepository(word(), remote.uri().toString(), word(), null);
        }

        @Test
        @DisplayName("GDR-STORE-2: a clone of another repository is refused without naming a URL, and a match reused")
        void refusesForeignClone(@Remote LocalRemote remote, @Remote LocalRemote other) {
            var gitDir = temp.resolve(word() + ".git");
            var operations = new JGitRepositoryOperations(RemotePolicy.localOnly());
            var clock = Clock.fixed(time(), ZoneOffset.UTC);
            var configuration = configuration(null);
            var user = user(null);
            var mine = registered(remote);
            var signing = TestKeys.ed25519().source();
            value(StoreWriter.open(registered(other), gitDir, user, configuration, List.of(), signing, operations,
                    clock)).close();

            var refused = StoreWriter.open(mine, gitDir, user, configuration, List.of(), signing, operations, clock);
            var reused = StoreWriter.open(registered(other), gitDir, user, configuration, List.of(), signing,
                    operations, clock);

            try {
                var detail = Objects.requireNonNull(refused.detail(), "a refusal carries a detail");
                assertAll(
                        () -> assertEquals(new StoreResult.PassedOn(GitOutcome.NOT_A_REPOSITORY), refused.outcome(),
                                "the foreign clone is refused"),
                        () -> assertEquals(mine.alias() + ": " + StoreWriter.FOREIGN_CLONE, detail,
                                "the detail names the alias"),
                        () -> assertFalse(detail.contains(remote.uri().toString()), "no URL of the registry"),
                        () -> assertFalse(detail.contains(other.uri().toString()), "no URL of the clone"),
                        () -> assertFalse(detail.contains(temp.toString()), "no path"),
                        () -> assertTrue(reused.isOk(), () -> "a matching clone is reused: " + reused.detail()));
            } finally {
                if (reused.isOk()) {
                    Objects.requireNonNull(reused.value()).close();
                }
            }
        }

        @Test
        @DisplayName("GDR-STORE-7: two writers of one clone share its guard, so an expiry waits for the other's save")
        void writersOfOneCloneShareTheGuard(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN, word());
            var storeRepository = registered(remote);
            var gitDir = temp.resolve(word() + ".git");
            var now = time();
            var clock = Clock.fixed(now, ZoneOffset.UTC);
            var configuration = configuration(null);
            var user = user(null);
            var scripted = new ScriptedRepositoryOperations();
            var author = Author.of(pseudonym());
            var key = key(Kind.PER_DOCUMENT);
            AtomicReference<StoreResult<List<String>>> expired = new AtomicReference<>();
            AtomicReference<Thread> expiryRun = new AtomicReference<>();

            var signing = TestKeys.ed25519().source();

            try (var saving = value(StoreWriter.open(storeRepository, gitDir, user, configuration, List.of(), signing,
                         scripted, clock));
                 var expiring = value(StoreWriter.open(storeRepository, gitDir, user, configuration, List.of(), signing,
                         new JGitRepositoryOperations(RemotePolicy.localOnly()), clock))) {
                value(saving.draft(author, key, put(word()), word()));
                scripted.beforeNextPush(() -> {
                    var run = new Thread(() -> expired.set(expiring.expireDrafts(now)));
                    expiryRun.set(run);
                    run.start();
                    try {
                        ThreadStates.awaitParkedOrEnded(run, TIMEOUT_SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("the wait for the expiry is interrupted", e);
                    }
                });

                var result = saving.save(author, CLAIMS, key, Map.of(), word());

                var run = Objects.requireNonNull(expiryRun.get(), "the expiry ran during the save");
                run.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                var saved = assertInstanceOf(SaveResult.Saved.class, result, "the save keeps its commit");
                var target = remote.repository().exactRef(StoreReferences.target(key));
                assertAll(
                        () -> assertFalse(run.isAlive(), "the expiry ends"),
                        () -> assertNotNull(target, "the remote has the target"),
                        () -> assertEquals(saved.commitId(), Objects.requireNonNull(target).getObjectId().name(),
                                "the remote holds the save"),
                        () -> assertEquals(List.of(), value(Objects.requireNonNull(expired.get())),
                                "no draft is stale"));
            }
        }
    }

    @Nested
    @DisplayName("Listing")
    class Listing {

        @Test
        @DisplayName("GDR-STORE-2: the listing returns the drafts of the user only, sorted by reference name")
        void listsDraftsOfUserOnly(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN, word());
            var user = Author.of(pseudonym());
            var other = Author.of(user.pseudonym() + "-o");
            var keys = List.of(key(Kind.PER_DOCUMENT), key(Kind.NUMBERED), key(Kind.MAIN));

            try (var fixture = open(remote)) {
                List<String> commitIds = new ArrayList<>();
                for (DocumentKey key : keys) {
                    commitIds.add(value(fixture.writer().draft(user, key, put(word()), word())));
                }
                value(fixture.writer().draft(other, keys.getFirst(), put(word()), word()));

                List<Draft> expected = new ArrayList<>();
                for (int index = 0; index < keys.size(); index++) {
                    var key = keys.get(index);
                    expected.add(new Draft(key.collection().name(), key.id(), commitIds.get(index), fixture.now()));
                }
                expected.sort(Comparator.comparing(draft -> draft.collection() + "/" + draft.id()));

                assertAll(
                        () -> assertEquals(expected, value(fixture.writer().drafts(user.pseudonym())),
                                "the drafts of the user, sorted"),
                        () -> assertEquals(1, value(fixture.writer().drafts(other.pseudonym())).size(),
                                "the other user has one draft"),
                        () -> assertEquals(List.of(), value(fixture.writer().drafts(pseudonym() + "-n")),
                                "a user without drafts has none"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: a reference of the draft form whose tip is a blob is no draft and is listed around")
        void skipsReferenceAtBlob(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN, word());
            var user = Author.of(pseudonym());
            var key = key(Kind.PER_DOCUMENT);
            var foreign = value(StoreReferences.draft(user.pseudonym(), key(Kind.NUMBERED)));

            try (var fixture = open(remote)) {
                var commitId = value(fixture.writer().draft(user, key, put(word()), word()));
                try (Repository clone = fixture.openClone(); ObjectInserter inserter = clone.newObjectInserter()) {
                    var blob = inserter.insert(Constants.OBJ_BLOB, content());
                    inserter.flush();
                    RefUpdate update = clone.updateRef(foreign);
                    update.setNewObjectId(blob);
                    assertEquals(RefUpdate.Result.NEW, update.update(), "the foreign reference points at a blob");
                }

                var drafts = fixture.writer().drafts(user.pseudonym());

                assertEquals(List.of(new Draft(key.collection().name(), key.id(), commitId, fixture.now())),
                        value(drafts), "the listing holds the draft and skips the reference at the blob");
            }
        }
    }

    @Nested
    @DisplayName("Push")
    class Push {

        @Test
        @DisplayName("GDR-STORE-2: a push of every reference through the configured refspecs never carries refs/drafts")
        void pushNeverCarriesDrafts(@Remote LocalRemote remote) throws Exception {
            var main = commit(remote.repository(), StoreReferences.MAIN, word());
            var branch = "refs/heads/" + word();

            try (var fixture = open(remote)) {
                value(fixture.writer().draft(Author.of(pseudonym()), key(Kind.PER_DOCUMENT), put(word()), word()));
                Iterable<PushResult> results;
                try (Repository clone = fixture.openClone(); Git git = Git.wrap(clone)) {
                    commit(clone, branch, word(), main);
                    results = git.push().setRemote(Constants.DEFAULT_REMOTE_NAME).call();
                }
                List<RemoteRefUpdate> updates = StreamSupport.stream(results.spliterator(), false)
                        .flatMap(result -> result.getRemoteUpdates().stream()).toList();
                List<Ref> remoteDrafts = remote.repository().getRefDatabase().getRefsByPrefix(StoreReferences.DRAFTS);

                assertAll(
                        () -> assertTrue(updates.stream().anyMatch(update -> branch.equals(update.getRemoteName())
                                && update.getStatus() == RemoteRefUpdate.Status.OK), "the new branch is pushed"),
                        () -> assertTrue(updates.stream()
                                        .noneMatch(update -> update.getRemoteName().startsWith(StoreReferences.DRAFTS)),
                                "no update names refs/drafts"),
                        () -> assertEquals(List.of(), remoteDrafts, "the remote has no draft reference"),
                        () -> assertNotNull(remote.repository().exactRef(branch), "the remote has the new branch"));
            }
        }
    }

    @Nested
    @DisplayName("Commit identities (GDR-NFR-3)")
    class Identities {

        @Test
        @DisplayName("GDR-STORE-2, GDR-NFR-3: by default author and committer carry an empty e-mail despite addresses")
        void defaultWithoutEmail(@Remote LocalRemote remote) throws Exception {
            var author = new Author(pseudonym(), address());
            var user = user(address());

            try (var fixture = open(remote, configuration(null), user)) {
                var commit = fixture.parse(value(fixture.writer().draft(author, key(Kind.MAIN), put(word()), word())));

                assertAll(
                        () -> assertEquals(author.pseudonym(), commit.getAuthorIdent().getName(), "author name"),
                        () -> assertEquals("", commit.getAuthorIdent().getEmailAddress(), "no author address"),
                        () -> assertEquals(user.name(), commit.getCommitterIdent().getName(), "committer name"),
                        () -> assertEquals("", commit.getCommitterIdent().getEmailAddress(), "no committer address"),
                        () -> assertEquals(fixture.now(), commit.getCommitterIdent().getWhenAsInstant(),
                                "the commit time of the clock"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-2: with commit.email true author and committer carry the supplied addresses")
        void withEmail(@Remote LocalRemote remote) throws Exception {
            var author = new Author(pseudonym(), address());
            var user = user(address());

            try (var fixture = open(remote, configuration(true), user)) {
                var commit = fixture.parse(value(fixture.writer().draft(author, key(Kind.MAIN), put(word()), word())));

                assertAll(
                        () -> assertEquals(author.email(), commit.getAuthorIdent().getEmailAddress(), "author address"),
                        () -> assertEquals(user.email(), commit.getCommitterIdent().getEmailAddress(),
                                "committer address"));
            }
        }
    }
}
