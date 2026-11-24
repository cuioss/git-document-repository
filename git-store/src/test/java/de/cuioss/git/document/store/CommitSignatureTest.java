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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;


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
 * The commit signature of GDR-STORE-12 against a {@code git-testing} local remote: every save commit, a save commit
 * carrying the edits of a {@link SaveStep}, a per-document release commit and a numbered release commit carry a
 * {@code gpgsig} header in the SSH signature format that verifies, over the commit without that header, under the
 * public key of the {@link TestKeys} source; draft commits carry none. A source that refuses or cannot be reached, for
 * the public key or for the signature, and a source that hands out an {@code ssh-rsa} key end the save and the release
 * with that outcome, nothing pushed, draft and branches unchanged; the public key is fetched once across a save with
 * several attempts; and {@link StoreWriter#open} without {@code signing.key} is {@link StoreOutcome#MALFORMED}.
 * {@link ScriptedRepositoryOperations} refuses leases and records the transport inputs.
 * <p>
 * Names, ids, pseudonyms, paths, contents, keys and times are generated; the outcomes and the details are the
 * specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("Commit signature (GDR-STORE-12)")
class CommitSignatureTest {

    /** The attempts of every test: enough for two lost leases. */
    private static final int ATTEMPTS = 3;

    /** The fixed claims of every save and release. */
    private static final IdentityClaims CLAIMS = new IdentityClaims("https://issuer.example/realms/test",
            Instant.parse("2026-03-01T08:15:30Z"), "urn:example:acr:mfa", List.of("pwd", "otp"));

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 8, 24).next();
    }

    private static String content() {
        return Generators.letterStrings(16, 40).next();
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

    private static StoreConfiguration configuration(boolean withKey) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", ATTEMPTS));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        if (withKey) {
            tree.put("signing", Map.of("key", word()));
        }
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
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED,
                    () -> "the fixture reference is written: " + result);
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

    /**
     * The raw commit without its {@code gpgsig} header and the header's continuation lines: the bytes the signature
     * covers. ISO-8859-1 maps every byte to one character and back, so the message bytes stay as they are.
     */
    private static byte[] unsigned(byte[] raw) {
        String text = new String(raw, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("\n\n");
        var kept = new StringBuilder();
        boolean signature = false;
        for (String line : text.substring(0, end).split("\n", -1)) {
            if (line.startsWith(" ") && signature) {
                continue;
            }
            signature = line.startsWith("gpgsig ");
            if (!signature) {
                kept.append(line).append('\n');
            }
        }
        kept.append(text.substring(end + 1));
        return kept.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Asserts that the commit carries an SSH signature by the key that verifies over the unsigned commit. */
    private static void assertSignedBy(Repository repository, ObjectId id, TestKeys.Ed25519 key) throws IOException {
        RevCommit commit = parse(repository, id);
        byte[] signature = commit.getRawGpgSignature();
        assertNotNull(signature, "the commit carries a gpgsig header");
        var decoded = assertInstanceOf(SshSignatures.DecodedSignature.class,
                SshSignatures.decode(new String(signature, StandardCharsets.US_ASCII)), "an SSH signature");
        assertAll(
                () -> assertArrayEquals(key.publicKeyBlob(), decoded.publicKeyBlob(), "by the test key"),
                () -> assertEquals(SshSignatures.NAMESPACE_GIT, decoded.namespace(), "in the namespace git"),
                () -> assertTrue(SshSignatures.verify(decoded, SshSignatures.NAMESPACE_GIT,
                        unsigned(commit.getRawBuffer())), "the signature verifies over the commit"));
    }

    /**
     * A signing key source over a test key that refuses or is unavailable for the public key or the signature, or hands
     * out another public key line.
     */
    private static final class ScriptedSource implements SigningKeySource {

        private final TestKeys.Source keys;
        private final StoreOutcome publicKeyOutcome;
        private final StoreOutcome signOutcome;
        private final @Nullable String publicKeyLine;

        ScriptedSource(TestKeys.Source keys, StoreOutcome publicKeyOutcome, StoreOutcome signOutcome,
                @Nullable String publicKeyLine) {
            this.keys = keys;
            this.publicKeyOutcome = publicKeyOutcome;
            this.signOutcome = signOutcome;
            this.publicKeyLine = publicKeyLine;
        }

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            return signOutcome == StoreOutcome.OK ? keys.sign(keyReference, data)
                    : StoreResult.of(signOutcome, "the scripted source does not sign");
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            if (publicKeyOutcome != StoreOutcome.OK) {
                return StoreResult.of(publicKeyOutcome, "the scripted source hands out no key");
            }
            return publicKeyLine == null ? keys.publicKey(keyReference) : StoreResult.ok(publicKeyLine);
        }
    }

    /** A signing key source over a test key that throws a {@link RuntimeException} on one of its calls. */
    private static final class Throwing implements SigningKeySource {

        /** The message of the thrown exception, which must reach no log record. */
        static final String MESSAGE = "thrown-by-the-source";

        /** The call that throws. */
        enum Call {
            PUBLIC_KEY, SIGN
        }

        private final TestKeys.Source keys;
        private final Call call;

        Throwing(TestKeys.Source keys, Call call) {
            this.keys = keys;
            this.call = call;
        }

        @Override
        public String id() {
            return "throwing";
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            if (call == Call.SIGN) {
                throw new IllegalStateException(MESSAGE);
            }
            return keys.sign(keyReference, data);
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            if (call == Call.PUBLIC_KEY) {
                throw new IllegalStateException(MESSAGE);
            }
            return keys.publicKey(keyReference);
        }
    }

    /** The writer under test with its clone and its transport double. */
    private record Fixture(StoreWriter writer, Path gitDir, ScriptedRepositoryOperations operations)
            implements AutoCloseable {

        @Nullable
        ObjectId localTip(String ref) throws IOException {
            try (Repository clone = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true)
                         .build()) {
                return tip(clone, ref);
            }
        }

        Repository openClone() throws IOException {
            return new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build();
        }

        String draft(Author author, DocumentKey key) {
            return value(writer.draft(author, key, List.of(new TreeEdit.Put(word() + "x",
                    content().getBytes(StandardCharsets.UTF_8))), word()));
        }

        SaveResult save(Author author, DocumentKey key) {
            return writer.save(author, CLAIMS, key, Map.of(), word());
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote, SigningKeySource signing) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var gitDir = temp.resolve(word() + ".git");
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var operations = new ScriptedRepositoryOperations();
        var now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(true), List.of(), signing,
                operations, Clock.fixed(now, ZoneOffset.UTC)));
        return new Fixture(writer, gitDir, operations);
    }

    /** A per-document repository: {@code main} and a working branch that forked from it. */
    private static ObjectId fork(Repository repository, DocumentKey key) {
        var base = files(word());
        var start = commit(repository, StoreReferences.MAIN, base);
        commit(repository, StoreReferences.target(key),
                merged(base, files(key.collection().name() + "/" + key.id() + ".json")), start);
        return commit(repository, StoreReferences.MAIN, merged(base, files(word() + "m")), start);
    }

    @Nested
    @DisplayName("Signed commits")
    class Signed {

        @Test
        @DisplayName("GDR-STORE-12: a save commit is signed by the source's key; the draft commit is unsigned")
        void saveCommitIsSigned(@Remote LocalRemote remote) throws Exception {
            var key = document();
            fork(remote.repository(), key);
            var author = Author.of(pseudonym());
            var source = TestKeys.ed25519().source();

            try (var fixture = open(remote, source)) {
                var draft = ObjectId.fromString(fixture.draft(author, key));
                try (Repository clone = fixture.openClone()) {
                    assertNull(parse(clone, draft).getRawGpgSignature(), "the draft commit is unsigned");
                }

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key));

                assertSignedBy(remote.repository(), ObjectId.fromString(saved.commitId()), source.key());
                assertEquals(1, source.signCalls(), "one signature");
            }
        }

        @Test
        @DisplayName("GDR-STORE-12: the save commit carrying the edits of a step is signed")
        void stepSaveCommitIsSigned(@Remote LocalRemote remote) throws Exception {
            var key = document();
            fork(remote.repository(), key);
            var author = Author.of(pseudonym());
            var source = TestKeys.ed25519().source();
            var stepped = word() + "s";

            try (var fixture = open(remote, source)) {
                fixture.draft(author, key);

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.writer().save(author, CLAIMS, key,
                        Map.of(), word(), attempt -> new SaveStep.Edits(List.of(new TreeEdit.Put(stepped,
                                content().getBytes(StandardCharsets.UTF_8))))));

                var commit = ObjectId.fromString(saved.commitId());
                assertSignedBy(remote.repository(), commit, source.key());
                try (TreeWalk found = TreeWalk.forPath(remote.repository(), stepped,
                             parse(remote.repository(), commit).getTree())) {
                    assertNotNull(found, "the step's edit is in the signed commit");
                }
            }
        }

        @Test
        @DisplayName("GDR-STORE-12: a per-document release commit is signed")
        void perDocumentReleaseIsSigned(@Remote LocalRemote remote) throws Exception {
            var key = document();
            fork(remote.repository(), key);
            var source = TestKeys.ed25519().source();

            try (var fixture = open(remote, source)) {
                var release = value(fixture.writer().release(Author.of(pseudonym()), CLAIMS, key));

                assertSignedBy(remote.repository(), ObjectId.fromString(release.commitId()), source.key());
            }
        }

        @Test
        @DisplayName("GDR-STORE-12: a numbered release commit is signed")
        void numberedReleaseIsSigned(@Remote LocalRemote remote) throws Exception {
            var collection = collection(Kind.NUMBERED);
            var key = value(DocumentKey.of(collection, "R-" + Generators.integers(1, 99_999).next()));
            var base = files(word());
            var mainTip = commit(remote.repository(), StoreReferences.MAIN, base);
            commit(remote.repository(), StoreReferences.target(key),
                    merged(base, files(collection.name() + "/" + key.id() + ".json")), mainTip);
            var source = TestKeys.ed25519().source();

            try (var fixture = open(remote, source)) {
                var release = value(fixture.writer().release(Author.of(pseudonym()), CLAIMS, key));

                assertSignedBy(remote.repository(), ObjectId.fromString(release.commitId()), source.key());
            }
        }

        @Test
        @DisplayName("GDR-STORE-12: the public key is fetched once across a save with several attempts")
        void publicKeyFetchedOnce(@Remote LocalRemote remote) throws Exception {
            var key = document();
            fork(remote.repository(), key);
            var author = Author.of(pseudonym());
            var source = TestKeys.ed25519().source();

            try (var fixture = open(remote, source)) {
                fixture.draft(author, key);
                fixture.operations().failPush(GitOutcome.LEASE_REFUSED, GitOutcome.LEASE_REFUSED);

                var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key));

                assertAll(
                        () -> assertEquals(ATTEMPTS, fixture.operations().pushes().size(), "every attempt pushed"),
                        () -> assertEquals(1, source.publicKeyCalls(), "the public key is fetched once"),
                        () -> assertEquals(ATTEMPTS, source.signCalls(), "each attempt signs its own commit"));
                assertSignedBy(remote.repository(), ObjectId.fromString(saved.commitId()), source.key());
            }
        }
    }

    @Nested
    @DisplayName("Refused signing")
    class Refused {

        /** Asserts that a refused save and release left the remote, the local branch and the draft as they were. */
        private void assertNothingChanged(Fixture fixture, LocalRemote remote, DocumentKey key, Author author,
                String draft, ObjectId targetTip, ObjectId mainTip) {
            var target = StoreReferences.target(key);
            assertAll(
                    () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                    () -> assertEquals(targetTip, tip(remote.repository(), target), "the remote target is unchanged"),
                    () -> assertEquals(mainTip, tip(remote.repository(), StoreReferences.MAIN), "main is unchanged"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1)), "no tag"),
                    () -> assertEquals(targetTip, fixture.localTip(target), "the local target is unchanged"),
                    () -> assertEquals(ObjectId.fromString(draft),
                            fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                            "the draft is kept"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            GitStoreLogMessages.WARN.SIGNING_FAILED.resolveIdentifierString()));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = StoreOutcome.class, names = {"REFUSED", "UNAVAILABLE"})
        @DisplayName("GDR-STORE-12: a source without a public key ends save and release with its outcome")
        void publicKeyNotHandedOut(StoreOutcome outcome, @Remote LocalRemote remote) throws Exception {
            var key = document();
            var mainTip = fork(remote.repository(), key);
            var targetTip = Objects.requireNonNull(tip(remote.repository(), StoreReferences.target(key)));
            var author = Author.of(pseudonym());
            var source = new ScriptedSource(TestKeys.ed25519().source(), outcome, StoreOutcome.OK, null);
            var detail = outcome == StoreOutcome.REFUSED ? "signing: key source refused"
                    : "signing: key source unavailable";

            try (var fixture = open(remote, source)) {
                var draft = fixture.draft(author, key);

                var saved = fixture.save(author, key);
                var released = fixture.writer().release(author, CLAIMS, key);

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(outcome, detail), saved, "the save"),
                        () -> assertEquals(StoreResult.of(outcome, detail), released, "the release"),
                        () -> assertEquals(List.of(), fixture.operations().pushes(),
                                "the key is asked for after the fetch, and nothing is pushed"));
                assertNothingChanged(fixture, remote, key, author, draft, targetTip, mainTip);
            }
        }

        @Test
        @DisplayName("GDR-STORE-12: the refusing test source ends save and release with REFUSED")
        void refusingTestSource(@Remote LocalRemote remote) throws Exception {
            var key = document();
            var mainTip = fork(remote.repository(), key);
            var targetTip = Objects.requireNonNull(tip(remote.repository(), StoreReferences.target(key)));
            var author = Author.of(pseudonym());

            try (var fixture = open(remote, new TestSigningKeySource())) {
                var draft = fixture.draft(author, key);

                var saved = fixture.save(author, key);
                var released = fixture.writer().release(author, CLAIMS, key);

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(StoreOutcome.REFUSED,
                                "signing: key source refused"), saved, "the save"),
                        () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signing: key source refused"),
                                released, "the release"));
                assertNothingChanged(fixture, remote, key, author, draft, targetTip, mainTip);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = StoreOutcome.class, names = {"REFUSED", "UNAVAILABLE"})
        @DisplayName("GDR-STORE-12: a signature the source does not make ends save and release with its outcome")
        void signatureNotMade(StoreOutcome outcome, @Remote LocalRemote remote) throws Exception {
            var key = document();
            var mainTip = fork(remote.repository(), key);
            var target = StoreReferences.target(key);
            var targetTip = Objects.requireNonNull(tip(remote.repository(), target));
            var author = Author.of(pseudonym());
            var source = new ScriptedSource(TestKeys.ed25519().source(), StoreOutcome.OK, outcome, null);

            try (var fixture = open(remote, source)) {
                var draft = fixture.draft(author, key);

                var saved = fixture.save(author, key);
                var released = fixture.writer().release(author, CLAIMS, key);

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(outcome, target + ": signing: not signed"), saved,
                                "the save names the target only"),
                        () -> assertEquals(StoreResult.of(outcome, target + ": signing: not signed"), released,
                                "the release names the working branch only"));
                assertNothingChanged(fixture, remote, key, author, draft, targetTip, mainTip);
            }
        }

        @ParameterizedTest(name = "throws on {0}")
        @EnumSource(Throwing.Call.class)
        @DisplayName("GDR-STORE-12, GDR-NFR-3: a source that throws is isolated as REFUSED; GIT_STORE-110 names no reference")
        void throwingSource(Throwing.Call call, @Remote LocalRemote remote) throws Exception {
            var key = document();
            var mainTip = fork(remote.repository(), key);
            var target = StoreReferences.target(key);
            var targetTip = Objects.requireNonNull(tip(remote.repository(), target));
            var author = Author.of(pseudonym());
            var source = new Throwing(TestKeys.ed25519().source(), call);
            var detail = call == Throwing.Call.PUBLIC_KEY ? CommitSignatures.SOURCE_REFUSED
                    : target + CommitSignatures.NOT_SIGNED;

            try (var fixture = open(remote, source)) {
                var draft = fixture.draft(author, key);

                var saved = fixture.save(author, key);
                var released = fixture.writer().release(author, CLAIMS, key);

                var logged = SourceSignerTest.logged();
                var signing = logged.lines()
                        .filter(line -> line.startsWith(GitStoreLogMessages.WARN.SIGNING_FAILED
                                .resolveIdentifierString()))
                        .toList();
                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(StoreOutcome.REFUSED, detail), saved, "the save"),
                        () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, detail), released, "the release"),
                        () -> assertEquals(2, signing.size(), () -> "one signing record each: " + logged),
                        () -> assertTrue(signing.stream().noneMatch(line -> line.contains(key.id())),
                                () -> "the signing records name the reference class only: " + signing),
                        () -> assertFalse(logged.contains(Throwing.MESSAGE), "the throwable is not logged"));
                assertNothingChanged(fixture, remote, key, author, draft, targetTip, mainTip);
            }
        }

        @Test
        @DisplayName("GDR-STORE-12: a source handing out an ssh-rsa key ends save and release with REFUSED")
        void rsaKeyRefused(@Remote LocalRemote remote) throws Exception {
            var key = document();
            var mainTip = fork(remote.repository(), key);
            var targetTip = Objects.requireNonNull(tip(remote.repository(), StoreReferences.target(key)));
            var author = Author.of(pseudonym());
            var rsaLine = TestKeys.writeRsa(Files.createTempFile(temp, "rsa", ".key"));
            var keys = TestKeys.ed25519().source();
            var source = new ScriptedSource(keys, StoreOutcome.OK, StoreOutcome.OK, rsaLine);

            try (var fixture = open(remote, source)) {
                var draft = fixture.draft(author, key);

                var saved = fixture.save(author, key);
                var released = fixture.writer().release(author, CLAIMS, key);

                assertAll(
                        () -> assertEquals(new SaveResult.NotSaved(StoreOutcome.REFUSED,
                                "signing: key type not supported"), saved, "the save"),
                        () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signing: key type not supported"),
                                released, "the release"),
                        () -> assertEquals(0, keys.signCalls(), "nothing is signed"));
                assertNothingChanged(fixture, remote, key, author, draft, targetTip, mainTip);
            }
        }
    }

    @Nested
    @DisplayName("Configuration")
    class Configuration {

        @Test
        @DisplayName("GDR-STORE-12: StoreWriter.open without signing.key is MALFORMED and clones nothing")
        void openWithoutSigningKey(@Remote LocalRemote remote) {
            var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
            var gitDir = temp.resolve(word() + ".git");
            var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
            var operations = new ScriptedRepositoryOperations();

            var opened = StoreWriter.open(storeRepository, gitDir, user, configuration(false), List.of(),
                    TestKeys.ed25519().source(), operations, Clock.systemUTC());

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "signing.key is missing"), opened,
                            "malformed"),
                    () -> assertFalse(Files.exists(gitDir), "nothing is cloned"),
                    () -> assertEquals(List.of(), operations.credentials(), "no transport call"));
        }
    }
}
