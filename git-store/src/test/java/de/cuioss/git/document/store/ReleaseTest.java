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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
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
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The release of a document or numbered entry against a {@code git-testing} local remote (GDR-STORE-6): the merge into
 * {@code main} with the tags {@code v1}, {@code v2}, the entry-path-only merge of a numbered collection, the push of
 * {@code main} and the tag, {@code INCOMPLETE} with its recovery, {@code REJECTED}, the lost lease, the tag a pruning
 * fetch of a concurrent save cannot delete, and an existing tag that is never moved. {@link ScriptedRepositoryOperations}
 * fails the push of one named reference and moves the remote between fetch and push. With every log record captured
 * down to trace, no path, content or pseudonym reaches a log record or a detail (GDR-NFR-3).
 * <p>
 * Names, ids, pseudonyms, paths and contents are generated, long enough to appear nowhere by chance; the reference
 * layout, the message of the release commit, the outcomes and the details are the specified vocabulary and kept as
 * literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("Release (GDR-STORE-6)")
class ReleaseTest {

    /** The attempts of every test: enough for one lost lease. */
    private static final int ATTEMPTS = 3;

    /** The deadline of a wait for a thread to park on a lock. */
    private static final long PARK_SECONDS = 10;

    @TempDir
    Path temp;

    /** A path segment long enough to appear in no log text by chance. */
    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 16, 24).next();
    }

    /** A content long enough to appear in no log text by chance. */
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

    private static DocumentKey document() {
        return value(DocumentKey.of(collection(Kind.PER_DOCUMENT), Ulid.next()));
    }

    private static DocumentKey entry(DocumentCollection collection) {
        return value(DocumentKey.of(collection, "R-" + Generators.integers(1, 99_999).next()));
    }

    private static StoreConfiguration configuration() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", ATTEMPTS));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    private static Map<String, String> files(String... names) {
        Map<String, String> files = new TreeMap<>();
        for (String name : names) {
            files.put(name, content());
        }
        return files;
    }

    private static Map<String, String> merged(Map<String, String> base, Map<String, String> changes) {
        Map<String, String> merged = new TreeMap<>(base);
        merged.putAll(changes);
        return merged;
    }

    /** Writes a commit holding exactly the files, nested paths included, onto a reference, forcing the reference. */
    private static ObjectId commit(Repository repository, String ref, Map<String, String> files,
            ObjectId... parents) {
        return commit(repository, ref, files, Map.of(), word(), parents);
    }

    /**
     * Writes a commit holding exactly the files, each a regular file unless the modes name another mode, with the
     * message, onto a reference, forcing the reference.
     */
    private static ObjectId commit(Repository repository, String ref, Map<String, String> files,
            Map<String, FileMode> modes, String message, ObjectId... parents) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, String> file : files.entrySet()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(modes.getOrDefault(file.getKey(), FileMode.REGULAR_FILE));
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
            commit.setMessage(message);
            var id = inserter.insert(commit);
            inserter.flush();
            setRef(repository, ref, id);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void setRef(Repository repository, String ref, ObjectId id) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(id);
        update.setForceUpdate(true);
        var result = update.update();
        assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                || result == RefUpdate.Result.FAST_FORWARD, () -> "the fixture reference is written: " + result);
    }

    private static @Nullable ObjectId tip(Repository repository, String ref) throws IOException {
        Ref found = repository.exactRef(ref);
        return found == null ? null : found.getObjectId();
    }

    private static ObjectId requiredTip(Repository repository, String ref) throws IOException {
        @Nullable ObjectId found = tip(repository, ref);
        assertNotNull(found, () -> "the reference exists: " + ref);
        return found;
    }

    private static RevCommit parse(Repository repository, ObjectId id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(id);
            walk.parseBody(commit);
            return commit;
        }
    }

    private static List<ObjectId> parents(Repository repository, ObjectId id) throws IOException {
        return List.of(parse(repository, id).getParents());
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

    /** The modes of the files of a commit, path to mode. */
    private static Map<String, FileMode> modes(Repository repository, ObjectId id) throws IOException {
        Map<String, FileMode> modes = new TreeMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(parse(repository, id).getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                modes.put(walk.getPathString(), walk.getFileMode(0));
            }
        }
        return modes;
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

    /** Asserts that no log text and no detail carries one of the secrets. */
    private static void assertNothingLeaks(@Nullable String detail, List<String> secrets) {
        List<String> texts = new ArrayList<>(logTexts());
        texts.add(String.valueOf(detail));
        for (String secret : secrets) {
            assertTrue(texts.stream().noneMatch(text -> text.contains(secret)),
                    () -> "a log record or the detail carries a path, a content or a pseudonym");
        }
    }

    /** The writer under test with its transport double. */
    private record Fixture(StoreWriter writer, ScriptedRepositoryOperations operations) implements AutoCloseable {

        StoreResult<Release> release(Author author, DocumentKey key) {
            return writer.release(author, key);
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var gitDir = temp.resolve(word() + ".git");
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var operations = new ScriptedRepositoryOperations();
        var now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(), operations,
                Clock.fixed(now, ZoneOffset.UTC)));
        return new Fixture(writer, operations);
    }

    /** A per-document repository: {@code main} and a working branch that forked from it, both moved since. */
    private record Forked(ObjectId main, ObjectId working, Map<String, String> merged) {
    }

    private static Forked fork(Repository repository, DocumentKey key) {
        var base = files(word());
        var start = commit(repository, StoreReferences.MAIN, base);
        var documentFiles = files(key.collection().name() + "/" + key.id() + ".json");
        var working = commit(repository, StoreReferences.target(key), merged(base, documentFiles), start);
        var mainFiles = merged(base, files(word() + "m"));
        var main = commit(repository, StoreReferences.MAIN, mainFiles, start);
        return new Forked(main, working, merged(mainFiles, documentFiles));
    }

    private static String message(DocumentKey key, int version) {
        return "Release " + key.collection().name() + "/" + key.id() + " v" + version;
    }

    @Nested
    @DisplayName("Release commit and tag (GDR-STORE-6)")
    class Releases {

        @Test
        @DisplayName("GDR-STORE-6: a per-document release merges into main with tag v1, then v2; main and tag pushed")
        void perDocumentReleasesV1ThenV2(@Remote LocalRemote remote) {
            var key = document();
            var target = StoreReferences.target(key);
            var forked = fork(remote.repository(), key);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var first = value(fixture.release(author, key));
                var firstCommit = ObjectId.fromString(first.commitId());
                var later = merged(forked.merged(), files(key.collection().name() + "/" + key.id() + "/" + word()));
                var working = commit(remote.repository(), target, later, forked.working());

                var second = value(fixture.release(author, key));

                var secondCommit = ObjectId.fromString(second.commitId());
                assertAll(
                        () -> assertEquals(new Release(StoreReferences.releaseTag(key, 1), first.commitId(), 1), first,
                                "the first release is v1"),
                        () -> assertEquals(List.of(forked.main(), forked.working()),
                                parents(remote.repository(), firstCommit), "main tip and working tip"),
                        () -> assertEquals(forked.merged(), tree(remote.repository(), firstCommit),
                                "the merged tree"),
                        () -> assertEquals(message(key, 1),
                                parse(remote.repository(), firstCommit).getFullMessage().strip(),
                                "the store-composed message"),
                        () -> assertEquals(author.pseudonym(),
                                parse(remote.repository(), firstCommit).getAuthorIdent().getName(),
                                "the user who releases is the author"),
                        () -> assertEquals(firstCommit, tip(remote.repository(), StoreReferences.releaseTag(key, 1)),
                                "v1 stays on the first release"),
                        () -> assertEquals(StoreReferences.releaseTag(key, 2), second.tag(), "the second is v2"),
                        () -> assertEquals(2, second.version(), "version 2"),
                        () -> assertEquals(List.of(firstCommit, working),
                                parents(remote.repository(), secondCommit), "on the first release"),
                        () -> assertEquals(later, tree(remote.repository(), secondCommit), "the later change"),
                        () -> assertEquals(secondCommit, tip(remote.repository(), StoreReferences.MAIN),
                                "main on the remote"),
                        () -> assertEquals(secondCommit, tip(remote.repository(), second.tag()),
                                "the tag on the remote"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                                GitStoreLogMessages.INFO.RELEASED.resolveIdentifierString()));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: a numbered release merges only the entry's paths, main its only parent")
        void numberedReleaseMergesOnlyTheEntry(@Remote LocalRemote remote) {
            var collection = collection(Kind.NUMBERED);
            var key = entry(collection);
            var other = value(DocumentKey.of(collection, key.id() + "0"));
            var prefix = collection.name() + "/";
            var base = files(word());
            var mainTip = commit(remote.repository(), StoreReferences.MAIN, base);
            var entryFiles = files(prefix + key.id() + ".json", prefix + key.id() + "/" + word());
            var otherFiles = files(prefix + other.id() + ".json");
            var counter = files(prefix + DocumentPaths.COUNTER);
            Map<String, String> branch = new TreeMap<>(base);
            branch.putAll(entryFiles);
            branch.putAll(otherFiles);
            branch.putAll(counter);
            commit(remote.repository(), StoreReferences.target(key), branch, mainTip);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var release = value(fixture.release(author, key));

                var commit = ObjectId.fromString(release.commitId());
                assertAll(
                        () -> assertEquals(StoreReferences.releaseTag(key, 1), release.tag(), "v1"),
                        () -> assertEquals(List.of(mainTip), parents(remote.repository(), commit),
                                "main is the only parent"),
                        () -> assertEquals(merged(base, entryFiles), tree(remote.repository(), commit),
                                "only the entry's paths; no other entry, no counter file"),
                        () -> assertEquals(commit, tip(remote.repository(), release.tag()), "the tag pushed"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: an existing tag is never moved; the release takes the next version")
        void existingTagIsNeverMoved(@Remote LocalRemote remote) throws Exception {
            var key = document();
            var forked = fork(remote.repository(), key);
            var v1 = StoreReferences.releaseTag(key, 1);
            setRef(remote.repository(), v1, forked.main());
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var release = value(fixture.release(author, key));

                assertAll(
                        () -> assertEquals(StoreReferences.releaseTag(key, 2), release.tag(), "the next version"),
                        () -> assertEquals(forked.main(), tip(remote.repository(), v1), "v1 is not moved"),
                        () -> assertEquals(ObjectId.fromString(release.commitId()),
                                tip(remote.repository(), release.tag()), "v2 on the release commit"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: content without release and a missing branch release nothing")
        void refusals(@Remote LocalRemote remote) {
            var mainContent = entry(collection(Kind.MAIN));
            var missing = document();
            var mainTip = commit(remote.repository(), StoreReferences.MAIN, files(word()));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                assertAll(
                        () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "key: content without release"),
                                fixture.release(author, mainContent), "content without release"),
                        () -> assertEquals(new StoreResult<>(new StoreResult.PassedOn(GitOutcome.NOT_FOUND), null,
                                        StoreReferences.target(missing) + ": not found"), fixture.release(author, missing),
                                "a missing working branch"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                        () -> assertEquals(mainTip, tip(remote.repository(), StoreReferences.MAIN),
                                "main is unchanged"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.RELEASE_NOT_COMPLETED.resolveIdentifierString()));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: a conflicting merge is CONFLICT naming the number of paths; nothing is pushed")
        void conflictingMerge(@Remote LocalRemote remote) {
            var key = document();
            var target = StoreReferences.target(key);
            var path = key.collection().name() + "/" + key.id() + ".json";
            var base = files(path);
            var start = commit(remote.repository(), StoreReferences.MAIN, base);
            var mine = content();
            var theirs = content();
            commit(remote.repository(), target, Map.of(path, mine), start);
            var mainTip = commit(remote.repository(), StoreReferences.MAIN, Map.of(path, theirs), start);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var result = fixture.release(author, key);

                assertAll(
                        () -> assertEquals(new StoreResult<>(new StoreResult.PassedOn(GitOutcome.CONFLICT), null,
                                target + ": 1 conflicting paths"), result, "the number of paths only"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                        () -> assertEquals(mainTip, tip(remote.repository(), StoreReferences.MAIN),
                                "main is unchanged"),
                        () -> assertNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1)), "no tag"),
                        () -> assertNothingLeaks(result.detail(),
                                List.of(path, base.get(path), mine, theirs, author.pseudonym())));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: a release keeps the mode of an executable file the working branch adds or changes")
        void releaseKeepsFileModes(@Remote LocalRemote remote) throws Exception {
            var key = document();
            var prefix = key.collection().name() + "/" + key.id();
            var changed = prefix + ".sh";
            var added = prefix + "/" + word();
            var base = files(word(), changed);
            var start = commit(remote.repository(), StoreReferences.MAIN, base);
            var working = merged(base, files(added));
            var executable = Map.of(changed, FileMode.EXECUTABLE_FILE, added, FileMode.EXECUTABLE_FILE);
            commit(remote.repository(), StoreReferences.target(key), working, executable, word(), start);
            var mainFiles = merged(base, files(word() + "m"));
            commit(remote.repository(), StoreReferences.MAIN, mainFiles, start);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var release = value(fixture.release(author, key));

                var commit = ObjectId.fromString(release.commitId());
                Map<String, FileMode> expected = new TreeMap<>();
                merged(mainFiles, working).keySet().forEach(path -> expected.put(path, FileMode.REGULAR_FILE));
                expected.putAll(executable);
                assertAll(
                        () -> assertEquals(merged(mainFiles, working), tree(remote.repository(), commit),
                                "the merged bytes"),
                        () -> assertEquals(expected, modes(remote.repository(), commit),
                                "the added and the changed file stay executable, the others regular"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: a save on main with the message of a missing release is no release; the release merges")
        void saveWithReleaseMessageIsNoRelease(@Remote LocalRemote remote) throws Exception {
            var key = document();
            var forked = fork(remote.repository(), key);
            var saved = commit(remote.repository(), StoreReferences.MAIN,
                    merged(tree(remote.repository(), forked.main()), files("m" + word() + "/" + word())), Map.of(),
                    message(key, 1), forked.main());
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var release = value(fixture.release(author, key));

                var commit = ObjectId.fromString(release.commitId());
                assertAll(
                        () -> assertNotEquals(saved, commit, "the save is not taken as the release"),
                        () -> assertEquals(StoreReferences.releaseTag(key, 1), release.tag(), "v1"),
                        () -> assertEquals(List.of(saved, forked.working()), parents(remote.repository(), commit),
                                "merged onto the save"),
                        () -> assertEquals(commit, tip(remote.repository(), StoreReferences.MAIN), "main moved"),
                        () -> assertEquals(commit, tip(remote.repository(), release.tag()),
                                "the tag on the release commit, not on the save"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: a save outside the entry with the message of its missing release is no release")
        void numberedSaveWithReleaseMessageIsNoRelease(@Remote LocalRemote remote) throws Exception {
            var collection = collection(Kind.NUMBERED);
            var key = entry(collection);
            var base = files(word());
            var mainTip = commit(remote.repository(), StoreReferences.MAIN, base);
            var entryFiles = files(collection.name() + "/" + key.id() + ".json");
            commit(remote.repository(), StoreReferences.target(key), merged(base, entryFiles), mainTip);
            var savedFiles = merged(base, files("m" + word() + "/" + word()));
            var saved = commit(remote.repository(), StoreReferences.MAIN, savedFiles, Map.of(), message(key, 1),
                    mainTip);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                var release = value(fixture.release(author, key));

                var commit = ObjectId.fromString(release.commitId());
                assertAll(
                        () -> assertNotEquals(saved, commit, "the save is not taken as the release"),
                        () -> assertEquals(List.of(saved), parents(remote.repository(), commit), "onto the save"),
                        () -> assertEquals(merged(savedFiles, entryFiles), tree(remote.repository(), commit),
                                "the entry merged"),
                        () -> assertEquals(commit, tip(remote.repository(), release.tag()),
                                "the tag on the release commit, not on the save"));
            }
        }
    }

    @Nested
    @DisplayName("Push of main and the tag (GDR-STORE-6)")
    class Pushes {

        @Test
        @DisplayName("GDR-STORE-6: a failed tag push is INCOMPLETE; the repeat creates only the missing tag")
        void incompleteThenRepeatCreatesOnlyTheTag(@Remote LocalRemote remote) throws Exception {
            var key = document();
            var forked = fork(remote.repository(), key);
            var tag = StoreReferences.releaseTag(key, 1);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.operations().failPushTo(tag, GitOutcome.UNREACHABLE);

                var incomplete = fixture.release(author, key);

                var mainTip = requiredTip(remote.repository(), StoreReferences.MAIN);
                int pushesBefore = fixture.operations().pushes().size();
                var repeated = value(fixture.release(author, key));
                var pushes = fixture.operations().pushes();
                assertAll(
                        () -> assertEquals(StoreResult.of(StoreOutcome.INCOMPLETE, tag + ": not pushed (UNREACHABLE)"),
                                incomplete, "incomplete"),
                        () -> assertEquals(List.of(forked.main(), forked.working()),
                                parents(remote.repository(), mainTip), "main moved to the release commit"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.RELEASE_INCOMPLETE.resolveIdentifierString()),
                        () -> assertEquals(new Release(tag, mainTip.name(), 1), repeated, "the tag on that commit"),
                        () -> assertEquals(mainTip, tip(remote.repository(), StoreReferences.MAIN),
                                "no second merge"),
                        () -> assertEquals(mainTip, tip(remote.repository(), tag), "the tag on the remote"),
                        () -> assertEquals(pushesBefore + 1, pushes.size(), "one push only"),
                        () -> assertEquals(tag, pushes.getLast().remoteRef(), "the push of the tag"),
                        () -> assertNothingLeaks(incomplete.detail(),
                                List.of(key.collection().name() + "/" + key.id() + ".json", author.pseudonym())));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: a refused push of main is REJECTED at once; no tag reaches the remote")
        void refusedPushOfMainIsRejected(@Remote LocalRemote remote) {
            var key = document();
            var forked = fork(remote.repository(), key);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                fixture.operations().failPushTo(StoreReferences.MAIN, GitOutcome.REJECTED);

                var result = fixture.release(author, key);

                assertAll(
                        () -> assertEquals(new StoreResult<>(new StoreResult.PassedOn(GitOutcome.REJECTED), null,
                                ScriptedRepositoryOperations.SCRIPTED), result, "rejected"),
                        () -> assertEquals(1, fixture.operations().pushes().size(), "no further attempt, no tag"),
                        () -> assertEquals(forked.main(), tip(remote.repository(), StoreReferences.MAIN),
                                "main is unchanged"),
                        () -> assertNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1)),
                                "no tag on the remote"),
                        () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                                GitStoreLogMessages.WARN.RELEASE_NOT_COMPLETED.resolveIdentifierString()));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: a lost lease of main retries from the fetch and releases onto the new tip")
        void lostLeaseRetries(@Remote LocalRemote remote) {
            var key = document();
            var forked = fork(remote.repository(), key);
            var author = Author.of(pseudonym());

            try (var fixture = open(remote)) {
                ObjectId[] moved = new ObjectId[1];
                fixture.operations().beforeNextPush(() -> moved[0] = commit(remote.repository(),
                        StoreReferences.MAIN, merged(forked.merged(), files(word() + "y")), forked.main()));

                var release = value(fixture.release(author, key));

                var commit = ObjectId.fromString(release.commitId());
                assertAll(
                        () -> assertEquals(StoreReferences.releaseTag(key, 1), release.tag(),
                                "still v1: the unpushed local tag was pruned by the next fetch"),
                        () -> assertEquals(2, fixture.operations().fetches().size(), "a second attempt"),
                        () -> assertEquals(List.of(moved[0], forked.working()), parents(remote.repository(), commit),
                                "on the new tip"),
                        () -> assertEquals(commit, tip(remote.repository(), StoreReferences.MAIN), "main moved"),
                        () -> assertEquals(commit, tip(remote.repository(), release.tag()), "the tag pushed"));
            }
        }

        @Test
        @DisplayName("GDR-STORE-6: a pruning fetch of a concurrent save waits on the transport lock for the tag push")
        void concurrentSaveCannotPruneTheUnpushedTag(@Remote LocalRemote remote) throws Exception {
            var key = document();
            fork(remote.repository(), key);
            var saveKey = document();
            commit(remote.repository(), StoreReferences.target(saveKey), files(word()));
            var author = Author.of(pseudonym());
            var saver = Author.of(pseudonym());
            AtomicReference<SaveResult> saved = new AtomicReference<>();
            Thread[] save = new Thread[1];
            Thread.State[] parked = new Thread.State[1];

            try (var fixture = open(remote)) {
                value(fixture.writer().draft(saver, saveKey, List.of(new TreeEdit.Put(word(),
                        content().getBytes(StandardCharsets.UTF_8))), word()));
                fixture.operations().beforeNextPush(() -> {
                    save[0] = new Thread(() -> saved.set(fixture.writer().save(saver, saveKey, Map.of(), word())));
                    save[0].start();
                    try {
                        ThreadStates.awaitParkedOrEnded(save[0], PARK_SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    parked[0] = save[0].getState();
                });

                var release = value(fixture.release(author, key));
                save[0].join(TimeUnit.SECONDS.toMillis(PARK_SECONDS));

                assertAll(
                        () -> assertEquals(Thread.State.WAITING, parked[0],
                                "the save waits on the transport lock while the release pushes"),
                        () -> assertEquals(ObjectId.fromString(release.commitId()),
                                tip(remote.repository(), release.tag()), "the tag reached the remote"),
                        () -> assertInstanceOf(SaveResult.Saved.class, saved.get(), "the save ran afterwards"),
                        () -> assertEquals(2, fixture.operations().fetches().size(),
                                "the save fetched after the release"));
            }
        }
    }

    @Nested
    @DisplayName("Release record (GDR-STORE-6)")
    class Records {

        @Test
        @DisplayName("GDR-STORE-6: a release names a tag, a full commit id and a version from 1")
        void releaseRecordChecksItsComponents() {
            var key = document();
            var commit = ObjectId.zeroId().name().replace('0', 'a');
            var tag = StoreReferences.releaseTag(key, 1);
            var notACommit = word();

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> new Release(StoreReferences.MAIN, commit,
                            1), "not a tag"),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Release(tag, notACommit, 1),
                            "not a commit id"),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Release(tag, commit, 0),
                            "version 0"),
                    () -> assertNotEquals(StoreReferences.releaseTag(key, 1), StoreReferences.releaseTag(key, 2),
                            "one tag per version"));
        }
    }
}
