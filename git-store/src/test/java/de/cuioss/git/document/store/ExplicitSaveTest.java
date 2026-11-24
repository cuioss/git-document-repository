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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.File;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.JGitRepositoryOperations;
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
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The explicit save against a {@code git-testing} local remote: one squash commit on the fetched tip pushed with lease
 * and re-applied after a lost lease (GDR-STORE-3), conflicts that change nothing (GDR-STORE-4), the expected blob ids
 * (GDR-STORE-5), and author, committer and credentials (GDR-STORE-8). {@link ScriptedRepositoryOperations} records the
 * transport inputs, scripts unreachable remotes, invalid credentials and refused leases, and moves the remote between
 * fetch and push as a concurrent writer would. Every save is signed with a {@link TestKeys} key and carries the
 * identity trailers of fixed {@link IdentityClaims}; the signature itself is checked by {@link CommitSignatureTest}. The pruning fetch of a save keeps the drafts of a clone whose stored
 * fetch refspec reaches beyond the mirrored namespaces.
 * <p>
 * Names, ids, pseudonyms, paths, contents, addresses and times are generated; the reference prefixes, the outcomes and
 * the details are the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("ExplicitSave (GDR-STORE-3, GDR-STORE-4, GDR-STORE-5, GDR-STORE-8)")
class ExplicitSaveTest {

    /** The attempts of most tests: enough for one lost lease. */
    private static final int ATTEMPTS = 3;

    private static final String ISSUER = "https://issuer.example/realms/test";
    private static final String AUTH_TIME = "2026-03-01T08:15:30Z";
    private static final String ACR = "urn:example:acr:mfa";
    private static final List<String> AMR = List.of("pwd", "otp");

    /** The fixed claims of every save. */
    private static final IdentityClaims CLAIMS = new IdentityClaims(ISSUER, Instant.parse(AUTH_TIME), ACR, AMR);

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

    private static String text() {
        return Generators.letterStrings(1, 64).next();
    }

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

    private static StoreConfiguration configuration(@Nullable Boolean email, int attempts) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", attempts));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", word()));
        if (email != null) {
            tree.put("commit", Map.of("email", email));
        }
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    /** The trailer block {@link #CLAIMS} and the pseudonym produce, after the blank line. */
    private static String trailers(String pseudonym) {
        return "Actor: " + pseudonym + "\nActor-Issuer: " + ISSUER + "\nAuth-Time: " + AUTH_TIME
                + "\nAuth-Context: acr=" + ACR + "; amr=" + String.join(",", AMR) + "\n";
    }

    private static TechnicalUser user(@Nullable String email) {
        return new TechnicalUser(word(), email, new UsernamePasswordCredentialsProvider(word(), word()));
    }

    /**
     * Files with generated contents that end in the tag, by name, so files of different roles (base, draft, concurrent
     * change) never share a content by chance.
     */
    private static Map<String, String> contents(String tag, String... names) {
        Map<String, String> files = new TreeMap<>();
        for (String name : names) {
            files.put(name, text() + "-" + tag);
        }
        return files;
    }

    /** Files with generated contents, by name; the names are flat, so a tree holds them without subtrees. */
    private static Map<String, String> files(String... names) {
        Map<String, String> files = new TreeMap<>();
        for (String name : names) {
            files.put(name, text());
        }
        return files;
    }

    /** Writes a commit holding exactly the files, nested paths included, onto a reference, forcing the reference. */
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

    private static @Nullable ObjectId tip(Repository repository, String ref) throws IOException {
        Ref found = repository.exactRef(ref);
        return found == null ? null : found.getObjectId();
    }

    private static RevCommit parse(Repository repository, ObjectId id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(id);
            walk.parseBody(commit);
            return commit;
        }
    }

    /** The files of a commit, path to content. */
    private static Map<String, String> tree(Repository repository, ObjectId id) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(parse(repository, id).getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                files.put(walk.getPathString(), new String(repository.open(walk.getObjectId(0)).getBytes(),
                        StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private static String blob(String content) {
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
            return formatter.idFor(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8)).name();
        }
    }

    private static List<TreeEdit> puts(Map<String, String> files) {
        return files.entrySet().stream().<TreeEdit>map(file -> new TreeEdit.Put(file.getKey(),
                file.getValue().getBytes(StandardCharsets.UTF_8))).toList();
    }

    private static Map<String, String> merged(Map<String, String> base, Map<String, String> changes) {
        Map<String, String> merged = new TreeMap<>(base);
        merged.putAll(changes);
        return merged;
    }

    /** The writer under test with its clone, its technical user, its clock and its transport double. */
    private record Fixture(StoreWriter writer, Path gitDir, TechnicalUser user, Instant now,
    ScriptedRepositoryOperations operations) implements AutoCloseable {

        Repository openClone() throws IOException {
            return new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build();
        }

        @Nullable
        ObjectId localTip(String ref) throws IOException {
            try (Repository clone = openClone()) {
                return tip(clone, ref);
            }
        }

        String draft(Author author, DocumentKey key, List<TreeEdit> edits) {
            return value(writer.draft(author, key, edits, word()));
        }

        SaveResult save(Author author, DocumentKey key, Map<String, String> expected) {
            return save(author, key, expected, word());
        }

        SaveResult save(Author author, DocumentKey key, Map<String, String> expected, String message) {
            return writer.save(author, CLAIMS, key, expected, message);
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote, StoreConfiguration configuration, TechnicalUser user) {
        return open(remote, configuration, user, TestKeys.ed25519().source());
    }

    private Fixture open(LocalRemote remote, StoreConfiguration configuration, TechnicalUser user,
            SigningKeySource source) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var gitDir = temp.resolve(word() + ".git");
        var now = time();
        var operations = new ScriptedRepositoryOperations();
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration, List.of(), source,
                operations, Clock.fixed(now, ZoneOffset.UTC)));
        return new Fixture(writer, gitDir, user, now, operations);
    }

    /** A signing key source that cannot be reached for any call. */
    private record UnavailableSource() implements SigningKeySource {

        @Override
        public String id() {
            return "unavailable";
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, "down");
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, "down");
        }
    }

    private Fixture open(LocalRemote remote) {
        return open(remote, configuration(null, ATTEMPTS), user(null));
    }

    @Nested
    @DisplayName("Explicit save (GDR-STORE-3)")
    class Save {

        @Test
        @DisplayName("GDR-STORE-3: an executable file and a symbolic link of the draft are saved with their modes")
        void savesTheDraftModes(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var regular = word() + "r";
            commit(remote.repository(), target, files(regular));
            var executable = word() + ".sh";
            var link = word() + "l";
            var script = text() + "-x";
            var linkTarget = word() + "-t";
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, List.of(
                        new TreeEdit.Put(executable, script.getBytes(StandardCharsets.UTF_8),
                                TreeEdit.Mode.EXECUTABLE_FILE),
                        new TreeEdit.Put(link, linkTarget.getBytes(StandardCharsets.UTF_8), TreeEdit.Mode.SYMLINK)));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = ObjectId.fromString(saved.commitId());
                Map<String, FileMode> modes = new TreeMap<>();
                try (TreeWalk walk = new TreeWalk(remote.repository())) {
                    walk.addTree(parse(remote.repository(), commit).getTree());
                    walk.setRecursive(true);
                    while (walk.next()) {
                        modes.put(walk.getPathString(), walk.getFileMode(0));
                    }
                }
                var files = tree(remote.repository(), commit);
                assertAll(
                        () -> assertEquals(FileMode.EXECUTABLE_FILE, modes.get(executable), "executable, 100755"),
                        () -> assertEquals(FileMode.SYMLINK, modes.get(link), "a symbolic link, 120000"),
                        () -> assertEquals(FileMode.REGULAR_FILE, modes.get(regular), "the tip's file unchanged"),
                        () -> assertEquals(script, files.get(executable), "the content of the executable"),
                        () -> assertEquals(linkTarget, files.get(link), "the target of the link"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a fetched blob above 16 MiB read by the step ends the save with a defined outcome")
        void oversizedFetchedBlobEndsTheSave(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var large = word() + "-large";
            var tipId = commit(remote.repository(), target, Map.of(large, "x".repeat(ExplicitSave.BLOB_LIMIT + 1)));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "d")));

                var result = fixture.writer().save(author, CLAIMS, key, Map.of(), word(),
                        attempt -> new SaveStep.Edits(attempt.tipContent(large).map(content -> List.<TreeEdit>of())
                                .orElse(List.of())));

                var notSaved = assertInstanceOf(SaveResult.NotSaved.class, result, "not saved");
                assertAll(
                        () -> assertEquals(new StoreResult.PassedOn(GitOutcome.FAILED), notSaved.outcome(),
                                "the passed-on failure of an unreadable object"),
                        () -> assertEquals(Optional.of(target + ": step: " + UncheckedIOException.class.getName()),
                                notSaved.detail(), "the read failure ends the save like a failing step"),
                        () -> assertEquals(tipId, tip(remote.repository(), target), "the remote target unchanged"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a save is one commit on the remote tip, the draft deleted and never pushed")
        void savesOneCommitOnRemoteTip(@Remote LocalRemote remote) throws Exception {
            commit(remote.repository(), StoreReferences.MAIN, files(word()));
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var base = files(word());
            var tipId = commit(remote.repository(), target, base);
            var author = Author.of(pseudonym());
            var changes = files(word() + "x");
            var message = word() + " " + word();

            try (var fixture = open(remote)) {
                var draft = fixture.draft(author, key, puts(changes));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of(), message));

                var commit = ObjectId.fromString(saved.commitId());
                var fullMessage = parse(remote.repository(), commit).getFullMessage();
                assertAll(
                        () -> assertEquals(message, fullMessage.substring(0, fullMessage.indexOf("\n\n")),
                                "the first paragraph is the caller's message"),
                        () -> assertEquals(message + "\n\n" + trailers(author.pseudonym()), fullMessage,
                                "the caller's message followed by the identity trailers"),
                        () -> assertEquals(commit, tip(remote.repository(), target), "the remote target moved"),
                        () -> assertEquals(List.of(tipId), List.of(parse(remote.repository(), commit).getParents()),
                                "the only parent is the remote tip"),
                        () -> assertEquals(merged(base, changes), tree(remote.repository(), commit),
                                "the draft's changes on the remote tip"),
                        () -> assertFalse(remote.repository().getObjectDatabase().has(ObjectId.fromString(draft)),
                                "no draft commit reached the remote"),
                        () -> assertEquals(commit, fixture.localTip(target), "the local target moved"),
                        () -> assertNull(fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the draft is deleted"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                                GitStoreLogMessages.INFO.SAVED.resolveIdentifierString()));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: the save applies onto the fetched tip, not the tip the draft started from")
        void appliesOntoFetchedTip(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var base = files(word());
            var start = commit(remote.repository(), target, base);
            var author = Author.of(pseudonym());
            var changes = files(word() + "x");

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(changes));
                var concurrent = merged(base, files(word() + "y"));
                var moved = commit(remote.repository(), target, concurrent, start);

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = ObjectId.fromString(saved.commitId());
                assertAll(
                        () -> assertEquals(List.of(moved), List.of(parse(remote.repository(), commit).getParents()),
                                "the only parent is the fetched tip"),
                        () -> assertEquals(merged(concurrent, changes), tree(remote.repository(), commit),
                                "the concurrent change is kept"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a path the draft deleted is deleted by the save")
        void deletesPath(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var kept = word();
            var deleted = kept + "d";
            var base = files(kept, deleted);
            commit(remote.repository(), target, base);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, List.of(new TreeEdit.Delete(deleted)));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                assertEquals(Map.of(kept, base.get(kept)),
                        tree(remote.repository(), ObjectId.fromString(saved.commitId())), "only the kept file");
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a draft moved during the save is kept for the next save")
        void keepsNewerDraft(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            commit(remote.repository(), target, files(word()));
            var author = Author.of(pseudonym());
            var later = word() + "l";

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "x")));
                String[] newer = new String[1];
                fixture.operations().beforeNextPush(() -> newer[0] = fixture.draft(author, key, puts(files(later))));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var remoteTree = tree(remote.repository(), ObjectId.fromString(saved.commitId()));
                assertAll(
                        () -> assertEquals(ObjectId.fromString(newer[0]),
                                fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the newer draft is kept"),
                        () -> assertFalse(remoteTree.containsKey(later), "the newer change is not saved"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: the pruning fetch of a save keeps every draft of a clone with a broader fetch refspec")
        void pruningFetchKeepsDraftsOfABroaderClone(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            commit(remote.repository(), StoreReferences.target(key), files(word()));
            var author = Author.of(pseudonym());
            var otherAuthor = Author.of(pseudonym());
            var otherKey = key(Kind.NUMBERED);
            var keptRef = value(StoreReferences.draft(otherAuthor.pseudonym(), otherKey));
            var keptApplied = StoreReferences.applied(keptRef);

            try (var fixture = open(remote)) {
                var kept = ObjectId.fromString(fixture.draft(otherAuthor, otherKey, puts(files(word()))));
                fixture.draft(author, key, puts(files(word() + "x")));
                try (Repository clone = fixture.openClone()) {
                    RefUpdate applied = clone.updateRef(keptApplied);
                    applied.setNewObjectId(kept);
                    applied.disableRefLog();
                    assertEquals(RefUpdate.Result.NEW, applied.update(), "the applied-draft reference is written");
                    var config = clone.getConfig();
                    config.setStringList(ConfigConstants.CONFIG_REMOTE_SECTION, Constants.DEFAULT_REMOTE_NAME,
                            ConfigConstants.CONFIG_FETCH_SECTION, List.of("+refs/*:refs/*"));
                    config.save();
                }

                assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                assertAll(
                        () -> assertEquals(kept, fixture.localTip(keptRef), "the unsaved draft is kept"),
                        () -> assertEquals(kept, fixture.localTip(keptApplied),
                                "its applied-draft reference is kept"),
                        () -> assertEquals(JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS,
                                fixture.operations().fetches().getLast().refSpecs(),
                                "the save fetches the mirrored namespaces only"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a retry after a lost lease re-applies the draft the save started with")
        void retryKeepsTheDraftItStartedWith(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var base = files(word());
            var start = commit(remote.repository(), target, base);
            var author = Author.of(pseudonym());
            var changes = files(word() + "x");
            var later = word() + "l";

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(changes));
                String[] newer = new String[1];
                fixture.operations().beforeNextPush(() -> {
                    newer[0] = fixture.draft(author, key, puts(files(later)));
                    commit(remote.repository(), target, merged(base, files(word() + "y")), start);
                });

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var remoteTree = tree(remote.repository(), ObjectId.fromString(saved.commitId()));
                assertAll(
                        () -> assertEquals(2, fixture.operations().pushes().size(), "a second attempt"),
                        () -> assertTrue(remoteTree.keySet().containsAll(changes.keySet()), "the started draft"),
                        () -> assertFalse(remoteTree.containsKey(later), "the draft written meanwhile is not saved"),
                        () -> assertEquals(ObjectId.fromString(newer[0]),
                                fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the newer draft is kept"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a save the remote accepted is Saved although the local branch cannot be moved")
        void acceptedSaveWithLocalMoveFailure(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            commit(remote.repository(), target, files(word()));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "x")));
                File branchDirectory = Files.createDirectories(fixture.gitDir().resolve(target).getParent()).toFile();
                fixture.operations().beforeNextPush(() -> assertTrue(branchDirectory.setWritable(false),
                        "the directory of the local branch is made read-only"));
                try {
                    var result = fixture.save(author, key, Map.of());
                    assumeFalse(Files.isWritable(branchDirectory.toPath()),
                            "the user can still write a read-only directory");

                    var saved = assertInstanceOf(SaveResult.Saved.class, result, "the accepted save");
                    assertAll(
                            () -> assertEquals(ObjectId.fromString(saved.commitId()), tip(remote.repository(), target),
                                    "the remote holds the commit"),
                            () -> assertNull(fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                    "the draft is deleted"),
                            () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                    GitStoreLogMessages.WARN.LOCAL_BRANCH_NOT_MOVED.resolveIdentifierString()));
                } finally {
                    assertTrue(branchDirectory.setWritable(true), "the directory is writable again");
                }
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a target branch the remote deleted is created again against the zero-id lease")
        void deletedRemoteBranchIsCreatedAgain(@Remote LocalRemote remote) throws Exception {
            var main = commit(remote.repository(), StoreReferences.MAIN, files(word()));
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            commit(remote.repository(), target, files(word()), main);
            var author = Author.of(pseudonym());
            var other = Author.of(pseudonym() + "-o");

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "x")));
                var otherDraft = fixture.draft(other, key, puts(files(word() + "o")));
                RefUpdate delete = remote.repository().updateRef(target);
                delete.setForceUpdate(true);
                assertEquals(RefUpdate.Result.FORCED, delete.delete(), "the remote branch is deleted");

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = ObjectId.fromString(saved.commitId());
                assertAll(
                        () -> assertEquals(commit, tip(remote.repository(), target), "the target is created again"),
                        () -> assertEquals(List.of(main), List.of(parse(remote.repository(), commit).getParents()),
                                "the parent is the tip of main"),
                        () -> assertEquals(ObjectId.zeroId().name(),
                                fixture.operations().pushes().getLast().expectedRemoteHead(), "the zero-id lease"),
                        () -> assertEquals(ObjectId.fromString(otherDraft),
                                fixture.localTip(value(StoreReferences.draft(other.pseudonym(), key))),
                                "the fetch leaves the drafts alone"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a kept newer draft saves only what it changed since the save that kept it")
        void keptDraftSavesOnlyItsLaterChanges(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var path = word();
            var later = path + "l";
            commit(remote.repository(), target, Map.of(path, text() + "-base"));
            var author = Author.of(pseudonym());
            var theirs = text() + "-theirs";
            var laterContent = text() + "-later";

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(Map.of(path, text() + "-mine")));
                String[] newer = new String[1];
                fixture.operations().beforeNextPush(
                        () -> newer[0] = fixture.draft(author, key, puts(Map.of(later, laterContent))));
                var first = ObjectId.fromString(
                        assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of())).commitId());
                var concurrent = commit(remote.repository(), target,
                        merged(tree(remote.repository(), first), Map.of(path, theirs)), first);

                var second = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = ObjectId.fromString(second.commitId());
                assertAll(
                        () -> assertEquals(List.of(concurrent),
                                List.of(parse(remote.repository(), commit).getParents()), "on the concurrent change"),
                        () -> assertEquals(merged(tree(remote.repository(), concurrent), Map.of(later, laterContent)),
                                tree(remote.repository(), commit),
                                "only the later change is saved, the concurrent change is kept"),
                        () -> assertNull(fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the draft is deleted"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a missing target branch starts at main and is pushed against the zero-id lease")
        void missingTargetStartsAtMain(@Remote LocalRemote remote) {
            var main = commit(remote.repository(), StoreReferences.MAIN, files(word()));
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "x")));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = ObjectId.fromString(saved.commitId());
                assertAll(
                        () -> assertEquals(commit, tip(remote.repository(), target), "the target is created"),
                        () -> assertEquals(List.of(main), List.of(parse(remote.repository(), commit).getParents()),
                                "the parent is the tip of main"),
                        () -> assertEquals(ObjectId.zeroId().name(),
                                fixture.operations().pushes().getFirst().expectedRemoteHead(), "the zero-id lease"),
                        () -> assertEquals(main, tip(remote.repository(), StoreReferences.MAIN), "main is unchanged"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: the zero-id lease is refused for a branch created meanwhile, and the save re-applies")
        void zeroIdLeaseRefusesCreatedBranch(@Remote LocalRemote remote) {
            var main = commit(remote.repository(), StoreReferences.MAIN, files(word()));
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "x")));
                ObjectId[] created = new ObjectId[1];
                fixture.operations().beforeNextPush(
                        () -> created[0] = commit(remote.repository(), target, files(word() + "y"), main));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = ObjectId.fromString(saved.commitId());
                var pushes = fixture.operations().pushes();
                assertAll(
                        () -> assertEquals(2, pushes.size(), "a second attempt"),
                        () -> assertEquals(ObjectId.zeroId().name(), pushes.getFirst().expectedRemoteHead(),
                                "the first push expects the branch absent"),
                        () -> assertEquals(created[0].name(), pushes.get(1).expectedRemoteHead(),
                                "the second push expects the created branch"),
                        () -> assertEquals(List.of(created[0]),
                                List.of(parse(remote.repository(), commit).getParents()),
                                "the save is applied onto the created branch"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a lease refusal re-applies the save onto the new tip")
        void reappliesAfterLeaseRefusal(@Remote LocalRemote remote) {
            var key = key(Kind.NUMBERED);
            var target = StoreReferences.target(key);
            var base = files(word());
            var start = commit(remote.repository(), target, base);
            var author = Author.of(pseudonym());
            var changes = files(word() + "x");
            var concurrent = merged(base, files(word() + "y"));

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(changes));
                ObjectId[] moved = new ObjectId[1];
                fixture.operations().beforeNextPush(() -> moved[0] = commit(remote.repository(), target, concurrent,
                        start));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = ObjectId.fromString(saved.commitId());
                assertAll(
                        () -> assertEquals(2, fixture.operations().pushes().size(), "a second attempt"),
                        () -> assertEquals(List.of(moved[0]), List.of(parse(remote.repository(), commit).getParents()),
                                "the only parent is the new tip"),
                        () -> assertEquals(merged(concurrent, changes), tree(remote.repository(), commit),
                                "both changes"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: content without release is saved onto main")
        void mainKindSavesOntoMain(@Remote LocalRemote remote) {
            var main = commit(remote.repository(), StoreReferences.MAIN, files(word()));
            var key = key(Kind.MAIN);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "x")));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = ObjectId.fromString(saved.commitId());
                assertAll(
                        () -> assertEquals(commit, tip(remote.repository(), StoreReferences.MAIN), "main moved"),
                        () -> assertEquals(List.of(main), List.of(parse(remote.repository(), commit).getParents()),
                                "the parent is the former tip of main"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: in an empty repository the save is a root commit")
        void emptyRepositoryRootCommit(@Remote LocalRemote remote) {
            var key = key(Kind.MAIN);
            var author = Author.of(pseudonym());
            var changes = files(word());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(changes));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = ObjectId.fromString(saved.commitId());
                assertAll(
                        () -> assertEquals(0, parse(remote.repository(), commit).getParentCount(), "a root commit"),
                        () -> assertEquals(changes, tree(remote.repository(), commit), "the draft's files"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a draft without changes is saved as the base, with no commit and no push")
        void draftWithoutChanges(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var base = files(word());
            var tipId = commit(remote.repository(), target, base);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(base));

                var result = fixture.save(author, key, Map.of());

                assertAll(
                        () -> assertEquals(new SaveResult.Saved(tipId.name()), result, "saved as the base"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "no push"),
                        () -> assertEquals(tipId, tip(remote.repository(), target), "the remote is unchanged"),
                        () -> assertNull(fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the draft is deleted"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-12: a draft without changes is saved as the base with an unavailable signing source")
        void draftWithoutChangesNeedsNoSigning(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var base = files(word());
            var tipId = commit(remote.repository(), target, base);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote, configuration(null, ATTEMPTS), user(null), new UnavailableSource())) {
                fixture.draft(author, key, puts(base));

                var result = fixture.save(author, key, Map.of());

                assertAll(
                        () -> assertEquals(new SaveResult.Saved(tipId.name()), result, "saved as the base"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "no push"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-13: a blank message gets the subject Save <collection>/<id> before the trailers")
        void blankMessageGetsDefaultSubject(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            commit(remote.repository(), target, files(word()));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "x")));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of(), " \n\t\n"));

                assertEquals("Save " + key.collection().name() + "/" + key.id() + "\n\n" + trailers(author.pseudonym()),
                        parse(remote.repository(), ObjectId.fromString(saved.commitId())).getFullMessage(),
                        "the default subject, then the identity trailers");
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a draft without changes and without its target branch is not saved and kept")
        void draftWithoutChangesOrTarget(@Remote LocalRemote remote) {
            var base = files(word());
            var main = commit(remote.repository(), StoreReferences.MAIN, base);
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var draft = fixture.draft(author, key, puts(base));

                var result = fixture.save(author, key, Map.of());

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(new StoreResult.PassedOn(GitOutcome.NOT_FOUND),
                                "draft: no changes and no target branch"), result, "no branch to name"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "no push"),
                        () -> assertNull(tip(remote.repository(), target), "no target branch is created"),
                        () -> assertEquals(main, tip(remote.repository(), StoreReferences.MAIN), "main is unchanged"),
                        () -> assertEquals(ObjectId.fromString(draft),
                                fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the draft is kept"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: an unreachable remote is reported and changes nothing")
        void unreachableChangesNothing(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var tipId = commit(remote.repository(), target, files(word()));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var draft = fixture.draft(author, key, puts(files(word() + "x")));
                fixture.operations().failFetch(GitOutcome.UNREACHABLE);

                var result = fixture.save(author, key, Map.of());

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(new StoreResult.PassedOn(GitOutcome.UNREACHABLE),
                                ScriptedRepositoryOperations.SCRIPTED), result, "unreachable"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "no push"),
                        () -> assertEquals(tipId, tip(remote.repository(), target), "the remote is unchanged"),
                        () -> assertEquals(tipId, fixture.localTip(target), "the local target is unchanged"),
                        () -> assertEquals(ObjectId.fromString(draft),
                                fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the draft keeps the work"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.SAVE_NOT_COMPLETED.resolveIdentifierString()));
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = GitOutcome.class, names = {"UNREACHABLE", "CREDENTIALS_INVALID"})
        @DisplayName("GDR-STORE-3: a push that fails ends the save at once; the local branch and the draft stay")
        void failedPushMovesNothing(GitOutcome outcome, @Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var tipId = commit(remote.repository(), target, files(word()));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var draft = fixture.draft(author, key, puts(files(word() + "x")));
                fixture.operations().failPush(outcome);

                var result = fixture.save(author, key, Map.of());

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(new StoreResult.PassedOn(outcome),
                                ScriptedRepositoryOperations.SCRIPTED), result, "passed on"),
                        () -> assertEquals(1, fixture.operations().pushes().size(), "no second attempt"),
                        () -> assertEquals(tipId, fixture.localTip(target), "the local target is unchanged"),
                        () -> assertEquals(ObjectId.fromString(draft),
                                fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the draft is kept"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a permanent rejection of the push, such as a protected branch, ends the save at once")
        void permanentRejectionEndsAtOnce(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var tipId = commit(remote.repository(), target, files(word()));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var draft = fixture.draft(author, key, puts(files(word() + "x")));
                // a protected branch: git-core reports the refusal of the remote as REJECTED, a lost race as
                // LEASE_REFUSED (GDR-CORE-7), so the save must not spend its attempts on REJECTED
                fixture.operations().failPush(GitOutcome.REJECTED, GitOutcome.REJECTED, GitOutcome.REJECTED);

                var result = fixture.save(author, key, Map.of());

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(new StoreResult.PassedOn(GitOutcome.REJECTED),
                                ScriptedRepositoryOperations.SCRIPTED), result, "rejected with the detail of git-core"),
                        () -> assertEquals(1, fixture.operations().pushes().size(), "exactly one attempt pushed"),
                        () -> assertEquals(1, fixture.operations().fetches().size(), "exactly one attempt fetched"),
                        () -> assertEquals(tipId, tip(remote.repository(), target), "the remote is unchanged"),
                        () -> assertEquals(tipId, fixture.localTip(target), "the local target is unchanged"),
                        () -> assertEquals(ObjectId.fromString(draft),
                                fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the draft is kept"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a save whose every attempt lost its lease is rejected after the last attempt")
        void rejectedAfterLastAttempt(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var tipId = commit(remote.repository(), target, files(word()));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var draft = fixture.draft(author, key, puts(files(word() + "x")));
                fixture.operations().failPush(GitOutcome.LEASE_REFUSED, GitOutcome.LEASE_REFUSED,
                        GitOutcome.LEASE_REFUSED);

                var result = fixture.save(author, key, Map.of());

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(new StoreResult.PassedOn(GitOutcome.REJECTED),
                                        target + ": lease refused on every attempt (" + ATTEMPTS + ")"), result,
                                "rejected"),
                        () -> assertEquals(ATTEMPTS, fixture.operations().pushes().size(), "every attempt pushed"),
                        () -> assertEquals(ATTEMPTS, fixture.operations().fetches().size(),
                                "every attempt fetched first"),
                        () -> assertEquals(tipId, tip(remote.repository(), target), "the remote is unchanged"),
                        () -> assertEquals(tipId, fixture.localTip(target), "the local target is unchanged"),
                        () -> assertEquals(ObjectId.fromString(draft),
                                fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the draft is kept"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-3: a missing draft, an invalid pseudonym and an invalid expected id are not saved")
        void refusals(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);

            try (var fixture = open(remote)) {
                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(new StoreResult.PassedOn(GitOutcome.NOT_FOUND),
                                        "draft: not found"), fixture.save(Author.of(pseudonym()), key, Map.of()),
                                "a missing draft"),
                        () -> assertEquals(new SaveResult.NotSaved(StoreOutcome.MALFORMED, "pseudonym: invalid"),
                                fixture.save(Author.of(word() + "/" + word()), key, Map.of()), "an invalid pseudonym"),
                        () -> {
                            // the marker character cannot occur in the detail, so the value is absent from it
                            var value = "#" + word();
                            var refused = fixture.save(Author.of(pseudonym()), key, Map.of(word(), value));
                            assertEquals(new SaveResult.NotSaved(StoreOutcome.MALFORMED,
                                            "expectedBlobIds: not a full object id"), refused,
                                    "an invalid expected id is a malformed argument");
                            assertFalse(Objects.requireNonNull(refused.toResult().detail()).contains(value),
                                    "the detail never names the value");
                        },
                        () -> assertEquals(List.of(), fixture.operations().fetches(), "nothing was fetched"));
            }
        }
    }

    @Nested
    @DisplayName("Conflicts (GDR-STORE-4)")
    class Conflicts {

        @Test
        @DisplayName("GDR-STORE-4: a conflict names every conflicting path; nothing is pushed, draft and branch unchanged")
        void namesEveryConflictingPath(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var stem = word();
            var first = stem + "a";
            var second = stem + "b";
            var added = stem + "c";
            var base = contents("base", first, second);
            var start = commit(remote.repository(), target, base);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var draft = fixture.draft(author, key, puts(contents("draft", first, second, added)));
                var moved = commit(remote.repository(), target, contents("concurrent", first, second), start);
                Map<String, String> expected = Map.of(first, blob(base.get(first)), second, blob(base.get(second)),
                        added, ObjectId.zeroId().name());

                var result = fixture.save(author, key, expected);

                assertAll(
                        () -> assertEquals(new SaveResult.Conflicting(List.of(first, second)), result,
                                "every conflicting path"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                        () -> assertEquals(moved, tip(remote.repository(), target), "the remote is unchanged"),
                        () -> assertEquals(moved, fixture.localTip(target), "the local target is the fetched tip"),
                        () -> assertEquals(ObjectId.fromString(draft),
                                fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                                "the draft is unchanged"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                                GitStoreLogMessages.INFO.SAVE_CONFLICT.resolveIdentifierString()));
            }
        }

        @Test
        @DisplayName("GDR-STORE-4: a draft adding a path below a file another writer added conflicts on that path")
        void fileAddedWhereTheDraftAddsADirectory(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var base = files(word());
            var start = commit(remote.repository(), target, base);
            var directory = word() + "d";
            var nested = directory + "/" + word();
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(Map.of(nested, text())));
                var moved = commit(remote.repository(), target, merged(base, Map.of(directory, text())), start);

                var result = fixture.save(author, key, Map.of(nested, ObjectId.zeroId().name()));

                assertAll(
                        () -> assertEquals(new SaveResult.Conflicting(List.of(nested)), result, "the nested path"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                        () -> assertEquals(moved, tip(remote.repository(), target), "the remote is unchanged"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-4: a draft adding a file where another writer added a directory conflicts on it")
        void directoryAddedWhereTheDraftAddsAFile(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var base = files(word());
            var start = commit(remote.repository(), target, base);
            var path = word() + "f";
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(Map.of(path, text())));
                var moved = commit(remote.repository(), target, merged(base, Map.of(path + "/" + word(), text())),
                        start);

                var result = fixture.save(author, key, Map.of());

                assertAll(
                        () -> assertEquals(new SaveResult.Conflicting(List.of(path)), result, "the file's path"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                        () -> assertEquals(moved, tip(remote.repository(), target), "the remote is unchanged"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-4: a draft adding a file below an existing directory is saved")
        void additionBelowExistingDirectory(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var directory = word() + "d";
            var base = Map.of(directory + "/" + word() + "a", text());
            commit(remote.repository(), target, base);
            var changes = Map.of(directory + "/" + word() + "b", text());
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(changes));

                var saved = assertInstanceOf(SaveResult.Saved.class,
                        fixture.save(author, key, Map.of(changes.keySet().iterator().next(),
                                ObjectId.zeroId().name())));

                assertEquals(merged(base, changes), tree(remote.repository(), ObjectId.fromString(saved.commitId())),
                        "both files");
            }
        }
    }

    @Nested
    @DisplayName("Concurrent change detection (GDR-STORE-5)")
    class ChangeDetection {

        @Test
        @DisplayName("GDR-STORE-5: an expected blob id that differs on the fetched tip is a conflict")
        void mismatchIsConflict(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var path = word();
            commit(remote.repository(), target, contents("base", path));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(contents("draft", path)));

                var result = fixture.save(author, key, Map.of(path, blob(text() + "-loaded")));

                assertEquals(new SaveResult.Conflicting(List.of(path)), result, "the path conflicts");
            }
        }

        @Test
        @DisplayName("GDR-STORE-5: a path without an expected blob id overwrites a concurrent change")
        void absentExpectationOverwrites(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var path = word();
            var start = commit(remote.repository(), target, contents("base", path));
            var author = Author.of(pseudonym());
            var mine = contents("draft", path);

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(mine));
                commit(remote.repository(), target, contents("concurrent", path), start);

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                assertEquals(mine, tree(remote.repository(), ObjectId.fromString(saved.commitId())),
                        "the draft's content wins");
            }
        }

        @Test
        @DisplayName("GDR-STORE-5: the zero id expects the path absent: saved when absent, a conflict when added")
        void zeroIdExpectsAbsence(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var stem = word();
            var absent = stem + "a";
            var addedMeanwhile = stem + "b";
            var start = commit(remote.repository(), target, files(stem));
            var author = Author.of(pseudonym());
            var other = Author.of(pseudonym() + "-o");
            var zero = ObjectId.zeroId().name();

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(absent)));
                fixture.draft(other, key, puts(files(addedMeanwhile)));
                commit(remote.repository(), target, files(stem, addedMeanwhile), start);

                var saved = fixture.save(author, key, Map.of(absent, zero));
                var conflicting = fixture.save(other, key, Map.of(addedMeanwhile, zero));

                assertAll(
                        () -> assertInstanceOf(SaveResult.Saved.class, saved, "an absent path is saved"),
                        () -> assertEquals(new SaveResult.Conflicting(List.of(addedMeanwhile)), conflicting,
                                "a path added meanwhile conflicts"));
            }
        }
    }

    @Nested
    @DisplayName("Author, committer and credentials (GDR-STORE-8)")
    class Identities {

        @Test
        @DisplayName("GDR-STORE-8: by default the save commit carries pseudonym and technical user with empty e-mail")
        void defaultIdentities(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.MAIN);
            var author = new Author(pseudonym(), address());
            var user = user(address());

            try (var fixture = open(remote, configuration(null, ATTEMPTS), user)) {
                fixture.draft(author, key, puts(files(word())));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = parse(remote.repository(), ObjectId.fromString(saved.commitId()));
                assertAll(
                        () -> assertEquals(author.pseudonym(), commit.getAuthorIdent().getName(), "author"),
                        () -> assertEquals("", commit.getAuthorIdent().getEmailAddress(), "no author address"),
                        () -> assertEquals(user.name(), commit.getCommitterIdent().getName(), "committer"),
                        () -> assertEquals("", commit.getCommitterIdent().getEmailAddress(), "no committer address"),
                        () -> assertEquals(fixture.now(), commit.getCommitterIdent().getWhenAsInstant(),
                                "the commit time of the clock"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-8: with commit.email true the save commit carries the supplied addresses")
        void identitiesWithEmail(@Remote LocalRemote remote) throws Exception {
            var key = key(Kind.MAIN);
            var author = new Author(pseudonym(), address());
            var user = user(address());

            try (var fixture = open(remote, configuration(true, ATTEMPTS), user)) {
                fixture.draft(author, key, puts(files(word())));

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var commit = parse(remote.repository(), ObjectId.fromString(saved.commitId()));
                assertAll(
                        () -> assertEquals(author.email(), commit.getAuthorIdent().getEmailAddress(), "author"),
                        () -> assertEquals(user.email(), commit.getCommitterIdent().getEmailAddress(), "committer"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-8: every clone, fetch and push receives exactly the technical user's credentials")
        void credentialsOfTechnicalUser(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            commit(remote.repository(), StoreReferences.target(key), files(word()));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "x")));
                assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, Map.of()));

                var credentials = fixture.operations().credentials();
                assertAll(
                        () -> assertEquals(1 + fixture.operations().fetches().size()
                                + fixture.operations().pushes().size(), credentials.size(), "every transport call"),
                        () -> assertTrue(fixture.operations().pushes().size() >= 1, "a push happened"),
                        () -> assertTrue(credentials.stream().allMatch(given -> given == fixture.user().credentials()),
                                "the technical user's credentials"),
                        () -> assertSame(fixture.user().credentials(), credentials.getFirst(), "the clone"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-8: invalid credentials on the fetch are passed on and change nothing")
        void credentialsInvalidPassedOn(@Remote LocalRemote remote) {
            var key = key(Kind.PER_DOCUMENT);
            var target = StoreReferences.target(key);
            var tipId = commit(remote.repository(), target, files(word()));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.draft(author, key, puts(files(word() + "x")));
                fixture.operations().failFetch(GitOutcome.CREDENTIALS_INVALID);

                var result = fixture.save(author, key, Map.of());

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(
                                new StoreResult.PassedOn(GitOutcome.CREDENTIALS_INVALID),
                                ScriptedRepositoryOperations.SCRIPTED), result, "passed on"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "no push"),
                        () -> assertEquals(tipId, tip(remote.repository(), target), "the remote is unchanged"));
            }
        }
    }
}
