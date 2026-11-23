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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.store.SignatureVerification.Reason;
import de.cuioss.git.document.store.SignatureVerification.UnverifiedCommit;
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
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The signature verification of GDR-STORE-14 over commits the store wrote, against a {@code git-testing} local remote:
 * every signed save verifies; an unsigned commit, a signature in another format, a malformed and a tampered signature,
 * an unknown key, a key outside its validity at the committer time and a key whose namespaces exclude {@code git} are
 * listed with their {@link Reason}, in the order of the walk, newest first; an allowed-signers file inside the
 * repository is never consulted; an unknown reference is the passed-on {@code NOT_FOUND}; a walk that reaches a shallow
 * commit is {@code INCOMPLETE}.
 * <p>
 * The commit time of the store is fixed per test, and every validity bound lies a day away from it, so the fixture
 * times differ by construction. Principals, paths, contents and pseudonyms are generated; the reasons, the outcomes and
 * the details are the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("SignatureVerification (GDR-STORE-14)")
class SignatureVerificationTest {

    private static final IdentityClaims CLAIMS = new IdentityClaims("https://issuer.example/realms/test",
            Instant.parse("2026-03-01T08:15:30Z"), "urn:example:acr:mfa", List.of("pwd", "otp"));

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("uuuuMMddHHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static StoreConfiguration configuration() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(30).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(5).toString()));
        tree.put("signing", Map.of("key", word()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    /** The store, its signing key and its fixed commit time, saving onto {@code main} of the remote. */
    private record Store(StoreWriter writer, TestKeys.Source source, Instant now, DocumentKey key)
            implements AutoCloseable {

        /** Saves one new file onto {@code main} and returns the commit. */
        ObjectId save() {
            var author = Author.of("p-" + word());
            value(writer.draft(author, key, List.of(new TreeEdit.Put(word(),
                    word().getBytes(StandardCharsets.UTF_8))), word()));
            var saved = assertInstanceOf(SaveResult.Saved.class,
                    writer.save(author, CLAIMS, key, Map.of(), word()));
            return ObjectId.fromString(saved.commitId());
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Store open(LocalRemote remote) {
        var source = TestKeys.ed25519().source();
        var now = Instant.ofEpochSecond(Generators.integers(1_500_000_000, 1_900_000_000).next());
        var writer = value(StoreWriter.open(new StoreRepository(word(), remote.uri().toString(), word(), null),
                temp.resolve(word() + ".git"),
                new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word())),
                configuration(), source, new JGitRepositoryOperations(RemotePolicy.localOnly()),
                Clock.fixed(now, ZoneOffset.UTC)));
        var key = value(DocumentKey.of(value(DocumentCollection.of("c" + word(), Kind.MAIN)), "R-" + word()));
        return new Store(writer, source, now, key);
    }

    /** Writes an allowed-signers file. */
    private AllowedSigners allowed(String... lines) throws IOException {
        var file = temp.resolve(word() + ".allowed");
        Files.writeString(file, String.join("\n", lines) + "\n");
        return value(AllowedSigners.read(file));
    }

    private static String line(String options, TestKeys.Ed25519 key) {
        return word() + "@example.org " + (options.isEmpty() ? "" : options + " ") + key.publicKeyLine();
    }

    /** Writes a commit with one file, an optional signature header and a committer time, onto a reference. */
    private static ObjectId commit(Repository repository, String ref, Instant when, @Nullable String signature,
            ObjectId... parents) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(word(), FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "", when, ZoneOffset.UTC);
            commit.setTreeId(inserter.insert(tree));
            commit.setParentIds(parents);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            if (signature != null) {
                commit.setGpgSignature(new GpgSignature(signature.getBytes(StandardCharsets.US_ASCII)));
            }
            var id = inserter.insert(commit);
            inserter.flush();
            setRef(repository, ref, id);
            return id;
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

    private static UnverifiedCommit unverified(ObjectId commit, Reason reason) {
        return new UnverifiedCommit(commit.name(), reason);
    }

    private static List<UnverifiedCommit> verify(LocalRemote remote, String reference, AllowedSigners signers) {
        return value(SignatureVerification.verify(remote.repository(), reference, signers));
    }

    @Test
    @DisplayName("GDR-STORE-14: every commit the store signed verifies under its listed key")
    void allValid(@Remote LocalRemote remote) throws Exception {
        try (var store = open(remote)) {
            store.save();
            store.save();
            var signers = allowed(line("", store.source().key()));

            assertAll(
                    () -> assertEquals(List.of(), verify(remote, StoreReferences.MAIN, signers), "all valid"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            GitStoreLogMessages.INFO.SIGNATURES_VERIFIED.resolveIdentifierString()));
        }
    }

    @Test
    @DisplayName("GDR-STORE-14: unsigned commits are listed in the walk order, newest first; signed ones are not")
    void unsignedInWalkOrder(@Remote LocalRemote remote) throws Exception {
        try (var store = open(remote)) {
            var base = commit(remote.repository(), StoreReferences.MAIN, store.now().minus(Duration.ofDays(1)),
                    null);
            var saved = store.save();
            var top = commit(remote.repository(), StoreReferences.MAIN, store.now().plus(Duration.ofDays(1)), null,
                    saved);
            var signers = allowed(line("", store.source().key()));

            assertEquals(List.of(unverified(top, Reason.UNSIGNED), unverified(base, Reason.UNSIGNED)),
                    verify(remote, StoreReferences.MAIN, signers), "the unsigned commits, newest first");
        }
    }

    @Test
    @DisplayName("GDR-STORE-14: a signature by a key not listed is UNKNOWN_KEY")
    void unknownKey(@Remote LocalRemote remote) throws Exception {
        try (var store = open(remote)) {
            var saved = store.save();
            var signers = allowed(line("", TestKeys.ed25519()));

            assertEquals(List.of(unverified(saved, Reason.UNKNOWN_KEY)), verify(remote, StoreReferences.MAIN, signers),
                    "unknown key");
        }
    }

    @Test
    @DisplayName("GDR-STORE-14: a key valid only after or only before the committer time is OUTSIDE_VALIDITY")
    void outsideValidity(@Remote LocalRemote remote) {
        try (var store = open(remote)) {
            var saved = store.save();
            var key = store.source().key();
            var dayAfter = TIMESTAMP.format(store.now().plus(Duration.ofDays(1)));
            var dayBefore = TIMESTAMP.format(store.now().minus(Duration.ofDays(1)));

            assertAll(
                    () -> assertEquals(List.of(unverified(saved, Reason.OUTSIDE_VALIDITY)),
                            verify(remote, StoreReferences.MAIN,
                                    allowed(line("valid-after=\"" + dayAfter + "\"", key))), "not yet valid"),
                    () -> assertEquals(List.of(unverified(saved, Reason.OUTSIDE_VALIDITY)),
                            verify(remote, StoreReferences.MAIN,
                                    allowed(line("valid-before=\"" + dayBefore + "\"", key))), "no longer valid"),
                    () -> assertEquals(List.of(), verify(remote, StoreReferences.MAIN, allowed(line(
                            "valid-after=\"" + dayBefore + "\",valid-before=\"" + dayAfter + "\"", key))),
                            "valid in between"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-14: a key whose namespaces exclude git is UNKNOWN_KEY")
    void namespaceExcludingGit(@Remote LocalRemote remote) throws Exception {
        try (var store = open(remote)) {
            var saved = store.save();

            assertEquals(List.of(unverified(saved, Reason.UNKNOWN_KEY)), verify(remote, StoreReferences.MAIN,
                    allowed(line("namespaces=\"file\"", store.source().key()))), "not for commits");
        }
    }

    @Test
    @DisplayName("GDR-STORE-14: a commit changed after its signing is INVALID")
    void tampered(@Remote LocalRemote remote) throws Exception {
        try (var store = open(remote)) {
            var saved = store.save();
            byte[] raw = remote.repository().open(saved).getBytes();
            byte[] changed = Arrays.copyOf(raw, raw.length + 1);
            changed[raw.length] = 'x';
            ObjectId tampered;
            try (ObjectInserter inserter = remote.repository().newObjectInserter()) {
                tampered = inserter.insert(Constants.OBJ_COMMIT, changed);
                inserter.flush();
            }
            setRef(remote.repository(), "refs/heads/tampered", tampered);
            var signers = allowed(line("", store.source().key()));

            try (RevWalk walk = new RevWalk(remote.repository())) {
                assertArrayEquals(walk.parseCommit(saved).getRawGpgSignature(),
                        walk.parseCommit(tampered).getRawGpgSignature(), "the signature is kept");
            }
            assertEquals(unverified(tampered, Reason.INVALID),
                    verify(remote, "refs/heads/tampered", signers).getFirst(), "the changed commit");
        }
    }

    @Test
    @DisplayName("GDR-STORE-14: a signature in another format is NOT_SSH and an unreadable one MALFORMED")
    void otherFormats(@Remote LocalRemote remote) throws Exception {
        var now = Instant.ofEpochSecond(Generators.integers(1_500_000_000, 1_900_000_000).next());
        var pgp = commit(remote.repository(), StoreReferences.MAIN, now,
                "-----BEGIN PGP SIGNATURE-----\n" + word() + "\n-----END PGP SIGNATURE-----");
        var malformed = commit(remote.repository(), StoreReferences.MAIN, now.plusSeconds(60),
                SshSignatures.BEGIN + "\n" + word() + "!\n" + SshSignatures.END, pgp);

        assertEquals(List.of(unverified(malformed, Reason.MALFORMED), unverified(pgp, Reason.NOT_SSH)),
                verify(remote, StoreReferences.MAIN, allowed(line("", TestKeys.ed25519()))), "both listed");
    }

    @Test
    @DisplayName("GDR-STORE-14: an allowed-signers file inside the repository is never consulted")
    void repositoryKeysAreNeverConsulted(@Remote LocalRemote remote) throws Exception {
        try (var store = open(remote)) {
            var trusted = line("", store.source().key());
            var author = Author.of("p-" + word());
            value(store.writer().draft(author, store.key(), List.of(new TreeEdit.Put(
                    ".allowed_signers", (trusted + "\n").getBytes(StandardCharsets.UTF_8))), word()));
            var saved = assertInstanceOf(SaveResult.Saved.class, store.writer().save(author, CLAIMS, store.key(),
                    Map.of(), word()));
            StoredConfig config = remote.repository().getConfig();
            config.setString("gpg", "ssh", "allowedSignersFile", ".allowed_signers");
            config.save();

            assertEquals(List.of(unverified(ObjectId.fromString(saved.commitId()), Reason.UNKNOWN_KEY)),
                    verify(remote, StoreReferences.MAIN, allowed(line("", TestKeys.ed25519()))),
                    "only the given allowed signers count");
        }
    }

    @Test
    @DisplayName("GDR-STORE-14: an unknown reference is the passed-on NOT_FOUND")
    void unknownReference(@Remote LocalRemote remote) throws Exception {
        var reference = "refs/heads/" + word();

        var result = SignatureVerification.verify(remote.repository(), reference, allowed(line("",
                TestKeys.ed25519())));

        assertEquals(new StoreResult<>(new StoreResult.PassedOn(GitOutcome.NOT_FOUND), null,
                reference + ": not found"), result, "not found");
    }

    @Test
    @DisplayName("GDR-STORE-14: an unverified commit names a full commit id and a reason")
    void unverifiedCommitChecksItsComponents() {
        var notAnId = word();

        assertThrows(IllegalArgumentException.class, () -> new UnverifiedCommit(notAnId, Reason.UNSIGNED),
                "not a commit id");
    }

    @Test
    @DisplayName("GDR-STORE-14: a walk that reaches a shallow commit is INCOMPLETE, so an unchecked ancestor never passes")
    void shallowBoundary(@Remote LocalRemote remote) throws Exception {
        try (var store = open(remote)) {
            commit(remote.repository(), StoreReferences.MAIN, store.now().minus(Duration.ofDays(1)), null);
            var saved = store.save();
            var signers = allowed(line("", store.source().key()));
            remote.repository().getObjectDatabase().setShallowCommits(Set.of(saved));

            var result = SignatureVerification.verify(remote.repository(), StoreReferences.MAIN, signers);

            assertEquals(StoreResult.of(StoreOutcome.INCOMPLETE, StoreReferences.MAIN + ": history is shallow"),
                    result, "incomplete, not an empty list");
        }
    }
}
