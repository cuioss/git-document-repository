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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
import java.util.Set;


import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The main class of the signature verification (GDR-STORE-14) through {@link VerifySignatures#run}: exit code
 * {@code 0} for a reference whose commits all verify, {@code 1} with one line {@code <commit id> <reason>} per
 * unverified commit, {@code 2} for a usage error, an argument that is no valid path, an unreadable allowed-signers file,
 * an unknown reference, a directory that is no repository and a history behind a shallow boundary; nothing printed
 * carries key text, a principal or a path.
 * <p>
 * Principals, paths, contents and pseudonyms are generated; the exit codes, the reasons and the printed details are the
 * specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@DisplayName("VerifySignatures (GDR-STORE-14)")
class VerifySignaturesTest {

    private static final IdentityClaims CLAIMS = new IdentityClaims("https://issuer.example/realms/test",
            Instant.parse("2026-03-01T08:15:30Z"), null, List.of());

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    /** What a run printed and returned. */
    private record Ran(int code, String out, String err) {
    }

    private static Ran run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code;
        try (var outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             var errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            code = VerifySignatures.run(args, outStream, errStream);
        }
        return new Ran(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    /** Saves one signed commit onto {@code main} of the remote and returns the signing key. */
    private TestKeys.Ed25519 saveSigned(LocalRemote remote) {
        var source = TestKeys.ed25519().source();
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(30).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(5).toString()));
        tree.put("signing", Map.of("key", word()));
        var configuration = value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
        var key = value(DocumentKey.of(value(DocumentCollection.of("c" + word(), Kind.MAIN)), "R-" + word()));
        var author = Author.of("p-" + word());
        try (var writer = value(StoreWriter.open(new StoreRepository(word(), remote.uri().toString(), word(), null),
                     temp.resolve(word() + ".git"),
                     new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word())),
                     configuration, List.of(), source, new JGitRepositoryOperations(RemotePolicy.localOnly()),
                     Clock.fixed(Instant.ofEpochSecond(1_800_000_000L), ZoneOffset.UTC)))) {
            value(writer.draft(author, key, List.of(new TreeEdit.Put(word(), word().getBytes(StandardCharsets.UTF_8))),
                    word()));
            assertInstanceOf(SaveResult.Saved.class, writer.save(author, CLAIMS, key, Map.of(), word()));
        }
        return source.key();
    }

    /** Writes an unsigned commit onto {@code main} of the remote, on top of its tip. */
    private static ObjectId unsignedOnTop(LocalRemote remote) throws IOException {
        var repository = remote.repository();
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(word(), FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "");
            commit.setTreeId(inserter.insert(tree));
            commit.setParentId(Objects.requireNonNull(repository.exactRef(StoreReferences.MAIN)).getObjectId());
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(StoreReferences.MAIN);
            update.setNewObjectId(id);
            assertEquals(RefUpdate.Result.FAST_FORWARD, update.update(), "main moves");
            return id;
        }
    }

    private Path allowed(String principal, TestKeys.Ed25519 key) throws IOException {
        return Files.writeString(temp.resolve(word() + ".allowed"), principal + " " + key.publicKeyLine() + "\n");
    }

    private static String gitDir(LocalRemote remote) {
        return remote.repository().getDirectory().toString();
    }

    private static void assertNoSecret(Ran ran, String principal, TestKeys.Ed25519 key, Path allowed) {
        String keyText = key.publicKeyLine().split(" ")[1];
        for (String text : List.of(ran.out(), ran.err())) {
            assertAll(
                    () -> assertFalse(text.contains(keyText), "no key text"),
                    () -> assertFalse(text.contains(principal), "no principal"),
                    () -> assertFalse(text.contains(allowed.toString()), "no path"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-14: a reference whose commits all verify exits 0 and prints nothing")
    void allVerified(@Remote LocalRemote remote) throws Exception {
        var key = saveSigned(remote);
        var principal = word() + "@example.org";
        var allowed = allowed(principal, key);

        var ran = run(gitDir(remote), allowed.toString(), StoreReferences.MAIN);

        assertAll(
                () -> assertEquals(new Ran(VerifySignatures.VERIFIED, "", ""), ran, "verified"),
                () -> assertNoSecret(ran, principal, key, allowed));
    }

    @Test
    @DisplayName("GDR-STORE-14: an unverified commit exits 1 and prints its id and its reason")
    void unverifiedCommit(@Remote LocalRemote remote) throws Exception {
        var key = saveSigned(remote);
        var unsigned = unsignedOnTop(remote);
        var principal = word() + "@example.org";
        var allowed = allowed(principal, key);

        var ran = run(gitDir(remote), allowed.toString(), StoreReferences.MAIN);

        assertAll(
                () -> assertEquals(VerifySignatures.UNVERIFIED, ran.code(), "unverified"),
                () -> assertEquals(unsigned.name() + " UNSIGNED" + System.lineSeparator(), ran.out(), "one line"),
                () -> assertEquals("", ran.err(), "no error"),
                () -> assertNoSecret(ran, principal, key, allowed));
    }

    @Test
    @DisplayName("GDR-STORE-14: a usage error exits 2 and prints the usage")
    void usageError() {
        var ran = run(word(), word());

        assertEquals(new Ran(VerifySignatures.FAILED, "", VerifySignatures.USAGE + System.lineSeparator()), ran,
                "usage");
    }

    @Test
    @DisplayName("GDR-STORE-14: an argument that is no valid path exits 2 with a fixed diagnostic naming no input")
    void invalidPath() {
        var invalid = word() + "\0" + word();
        var valid = temp.resolve(word()).toString();

        var badGitDir = run(invalid, valid, StoreReferences.MAIN);
        var badAllowedSigners = run(valid, invalid, StoreReferences.MAIN);

        var expected = new Ran(VerifySignatures.FAILED, "", VerifySignatures.INVALID_PATH + System.lineSeparator());
        assertAll(
                () -> assertEquals(expected, badGitDir, "the git-dir"),
                () -> assertEquals(expected, badAllowedSigners, "the allowed-signers file"),
                () -> assertFalse(badAllowedSigners.err().contains(invalid.substring(0, 8)), "no input"));
    }

    @Test
    @DisplayName("GDR-STORE-14: a history behind a shallow boundary exits 2 with INCOMPLETE, never 0")
    void shallowHistory(@Remote LocalRemote remote) throws Exception {
        var key = saveSigned(remote);
        var allowed = allowed(word() + "@example.org", key);
        var tip = Objects.requireNonNull(remote.repository().exactRef(StoreReferences.MAIN)).getObjectId();
        remote.repository().getObjectDatabase().setShallowCommits(Set.of(tip));

        var ran = run(gitDir(remote), allowed.toString(), StoreReferences.MAIN);

        assertEquals(new Ran(VerifySignatures.FAILED, "", "INCOMPLETE " + StoreReferences.MAIN + ": "
                + SignatureVerification.SHALLOW + System.lineSeparator()), ran, "incomplete");
    }

    @Test
    @DisplayName("GDR-STORE-14: an unreadable allowed-signers file, an unknown reference and no repository exit 2")
    void failures(@Remote LocalRemote remote) throws Exception {
        var key = saveSigned(remote);
        var principal = word() + "@example.org";
        var allowed = allowed(principal, key);
        var reference = "refs/heads/" + word();
        var missing = temp.resolve(word());

        var unreadable = run(gitDir(remote), missing.toString(), StoreReferences.MAIN);
        var unknown = run(gitDir(remote), allowed.toString(), reference);
        var noRepository = run(Files.createDirectory(temp.resolve(word())).toString(), allowed.toString(),
                StoreReferences.MAIN);

        assertAll(
                () -> assertEquals(new Ran(VerifySignatures.FAILED, "",
                                "UNAVAILABLE signing.allowed-signers: not readable" + System.lineSeparator()), unreadable,
                        "unreadable"),
                () -> assertFalse(unreadable.err().contains(missing.toString()), "no path"),
                () -> assertEquals(new Ran(VerifySignatures.FAILED, "",
                        "NOT_FOUND " + reference + ": not found" + System.lineSeparator()), unknown, "unknown"),
                () -> assertEquals(new Ran(VerifySignatures.FAILED, "",
                                "NOT_A_REPOSITORY git-dir: not a repository" + System.lineSeparator()), noRepository,
                        "no repository"),
                () -> assertNoSecret(unknown, principal, key, allowed));
    }
}
