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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.StreamSupport;


import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.api.Git;
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
 * reference whose parent is the tip of the target branch (else {@code main}, else none), a second save stacks on the
 * first, the listing returns the drafts of one user only, a push of every reference through the configured push
 * reference specifications never carries {@code refs/drafts/*}, and the commit identities follow {@code commit.email}
 * (GDR-NFR-3).
 * <p>
 * Names, ids, pseudonyms, paths, contents, addresses and times are generated; the configuration keys, the reference
 * prefixes and the details are the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@DisplayName("StoreWriter drafts (GDR-STORE-2)")
class StoreWriterDraftTest {

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
        if (email != null) {
            tree.put("commit", Map.of("email", email));
        }
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    /** Writes a commit with one file onto a reference of a repository, with the given parent or none. */
    private static ObjectId commit(Repository repository, String ref, String file, ObjectId... parents)
            throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var blob = inserter.insert(Constants.OBJ_BLOB, content());
            var tree = new TreeFormatter();
            tree.append(file, FileMode.REGULAR_FILE, blob);
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
            try (Repository clone = openClone(); RevWalk walk = new RevWalk(clone)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
                walk.parseBody(commit);
                return commit;
            }
        }

        boolean contains(String commitId, String path) throws IOException {
            try (Repository clone = openClone(); RevWalk walk = new RevWalk(clone)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(commitId));
                try (TreeWalk found = TreeWalk.forPath(clone, path, commit.getTree())) {
                    return found != null;
                }
            }
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote, StoreConfiguration configuration, TechnicalUser user) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var gitDir = temp.resolve(word() + ".git");
        var now = time();
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration,
                new JGitRepositoryOperations(RemotePolicy.localOnly()), Clock.fixed(now, ZoneOffset.UTC)));
        return new Fixture(writer, gitDir, user, now);
    }

    private Fixture open(LocalRemote remote) {
        return open(remote, configuration(null), user(null));
    }

    private static TechnicalUser user(@Nullable String email) {
        return new TechnicalUser(word(), email, new UsernamePasswordCredentialsProvider(word(), word()));
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    @SuppressWarnings("unchecked")
    private static <T> T nullValue() {
        return (T) null;
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
            var author = Author.of(pseudonym());
            var path = word();
            var message = word();

            try (var fixture = open(remote)) {
                var commitId = value(fixture.writer().draft(author, key, put(path), message));

                var commit = fixture.parse(commitId);
                try (Repository clone = fixture.openClone()) {
                    var draftRef = clone.exactRef(value(StoreReferences.draft(author.pseudonym(), key)));
                    assertAll(
                            () -> assertNotNull(draftRef, "the draft reference exists"),
                            () -> assertEquals(commitId, Objects.requireNonNull(draftRef).getObjectId().name(),
                                    "the draft reference points at the commit"),
                            () -> assertEquals(List.of(target), List.of(commit.getParents()),
                                    "the parent is the tip of the target branch"),
                            () -> assertEquals(message, commit.getFullMessage(), "the message"),
                            () -> assertTrue(fixture.contains(commitId, path), "the edit is applied"),
                            () -> assertTrue(fixture.contains(commitId, baseFile),
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

            try (var fixture = open(remote)) {
                var first = value(fixture.writer().draft(author, key, put(firstPath), word()));
                var second = value(fixture.writer().draft(author, key, put(secondPath), word()));

                assertAll(
                        () -> assertEquals(List.of(ObjectId.fromString(first)),
                                List.of(fixture.parse(second).getParents()), "the parent is the first draft commit"),
                        () -> assertTrue(fixture.contains(second, firstPath), "the first edit is kept"),
                        () -> assertTrue(fixture.contains(second, secondPath), "the second edit is applied"));
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
