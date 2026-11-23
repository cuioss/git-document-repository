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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RemotePolicy;
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
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The CI main class {@link VerifyAnchors} of the anchor verification (GDR-STORE-17, GDR-STORE-18) against a
 * {@code git-testing} local remote anchored by the engine with the provider {@code signed}: exit {@code 0} with no
 * output for a complete and valid anchoring, {@code 1} with one line per finding, and {@code 2} for a usage error, an
 * invalid path, an unreadable allowed-signers file and a directory that is no repository; no output names a path, the
 * principal or the key.
 * <p>
 * Keys, names, ids and paths are generated; the reference layout, the exit codes, the line format and the diagnostics
 * are the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@DisplayName("VerifyAnchors (GDR-STORE-17, GDR-STORE-18)")
class VerifyAnchorsTest {

    /** The principal of the allowed-signers line. */
    private static final String PRINCIPAL = "anchor@example.invalid";

    @TempDir
    Path temp;

    private final TestKeys.Source anchorKeys = TestKeys.ed25519().source();
    private final Instant now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.outcome() + " " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    /** What a run printed and returned. */
    private record Run(int code, String out, String err) {

        List<String> lines() {
            return out.isEmpty() ? List.of() : List.of(out.split("\n"));
        }
    }

    private static Run run(String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int code;
        try (var outStream = new PrintStream(out, true, UTF_8); var errStream = new PrintStream(err, true, UTF_8)) {
            code = VerifyAnchors.run(args, outStream, errStream);
        }
        return new Run(code, out.toString(UTF_8).replace("\r\n", "\n"), err.toString(UTF_8).replace("\r\n", "\n"));
    }

    private Path anchorSignersFile() throws IOException {
        return Files.writeString(temp.resolve(word()), PRINCIPAL + " namespaces=\"" + SignedAnchorProvider.NAMESPACE
                + "\" " + anchorKeys.key().publicKeyLine() + "\n", US_ASCII);
    }

    private static ObjectId commit(Repository repository, String ref) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(word(), FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, word().getBytes(UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "", Instant.ofEpochSecond(100_000), ZoneOffset.UTC);
            commit.setTreeId(inserter.insert(tree));
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
        }
    }

    /** Anchors the first checkpoint of the remote through the engine with the provider signed. */
    private void anchor(LocalRemote remote) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(30).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(5).toString()));
        tree.put("signing", Map.of("key", word()));
        tree.put("anchor", Map.of("provider", SignedAnchorProvider.ID));
        var configuration = value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        var accepted = assertInstanceOf(SshSignatures.AcceptedKey.class,
                SshSignatures.parsePublicKeyLine(anchorKeys.key().publicKeyLine()), "the public key line parses");
        var provider = new SignedAnchorProvider(new SignedAnchorProvider.AnchorKey(anchorKeys, word(), accepted),
                null, Duration.ofMinutes(5), clock);
        try (var writer = value(StoreWriter.open(new StoreRepository(word(), remote.uri().toString(), word(), null),
                     temp.resolve(word() + ".git"), new TechnicalUser(word(), null,
                             new UsernamePasswordCredentialsProvider(word(), word())), configuration,
                     new StoreProviders(TestKeys.ed25519().source(), new TestPersonDirectory(), provider),
                     new ScriptedRepositoryOperations(), clock))) {
            assertEquals(1, value(writer.anchor()).anchored().size(), "the first checkpoint is anchored");
        }
    }

    private static String gitDir(LocalRemote remote) {
        return remote.repository().getDirectory().getAbsolutePath();
    }

    /** Asserts that nothing printed names a path, the principal or the key. */
    private void assertNoSecret(Run run, String... paths) {
        String key = anchorKeys.key().publicKeyLine().split(" ")[1];
        for (String text : List.of(run.out(), run.err())) {
            assertAll(
                    () -> assertFalse(text.contains(temp.toString()), "no temporary path"),
                    () -> assertFalse(text.contains(PRINCIPAL), "no principal"),
                    () -> assertFalse(text.contains(key), "no key"));
            for (String path : paths) {
                assertFalse(text.contains(path), "no path of an argument");
            }
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: a complete and valid anchoring exits 0 and prints nothing")
    void cleanExitsZero(@Remote LocalRemote remote) throws Exception {
        commit(remote.repository(), StoreReferences.MAIN);
        anchor(remote);
        String signers = anchorSignersFile().toString();

        var run = run(gitDir(remote), signers);

        assertAll(
                () -> assertEquals(VerifyAnchors.VERIFIED, run.code(), "exit 0"),
                () -> assertEquals("", run.out(), "nothing on standard output"),
                () -> assertEquals("", run.err(), "nothing on standard error"));
        assertNoSecret(run, gitDir(remote), signers);
    }

    @Test
    @DisplayName("GDR-STORE-17: findings exit 1 with one line per finding")
    void findingsExitOne(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        commit(repository, StoreReferences.MAIN);
        anchor(remote);
        var collection = value(DocumentCollection.of("c" + word(), DocumentCollection.Kind.PER_DOCUMENT));
        var release = commit(repository, StoreReferences.releaseTag(value(DocumentKey.of(collection, Ulid.next())), 1));
        var checkpoint = Objects.requireNonNull(repository.exactRef(StoreReferences.checkpoint(1)), "checkpoint 1")
                .getObjectId();
        commit(repository, StoreReferences.checkpoint(3));
        commit(repository, StoreReferences.MAIN);
        String signers = anchorSignersFile().toString();

        var run = run(gitDir(remote), signers);

        assertAll(
                () -> assertEquals(VerifyAnchors.FINDINGS, run.code(), "exit 1"),
                () -> assertTrue(run.lines().contains("CHECKPOINT_MISSING refs/tags/checkpoints/2"), run.out()),
                () -> assertTrue(run.lines().contains("NO_PROOF " + release.name()), run.out()),
                () -> assertTrue(run.lines().contains("REFERENCE_UNREACHABLE " + checkpoint.name() + " "
                        + StoreReferences.MAIN), run.out()),
                () -> assertEquals("", run.err(), "nothing on standard error"));
        assertNoSecret(run, gitDir(remote), signers);
    }

    @Test
    @DisplayName("GDR-STORE-18: a rejected proof prints its outcome")
    void rejectedProofLine() {
        var finding = new AnchorVerification.Finding(AnchorVerification.Kind.PROOF_REJECTED, ObjectId.zeroId().name(),
                null, StoreOutcome.CHAIN_BROKEN);

        assertEquals("PROOF_REJECTED " + ObjectId.zeroId().name() + " CHAIN_BROKEN", VerifyAnchors.line(finding),
                "kind, subject and outcome");
    }

    @Test
    @DisplayName("GDR-STORE-17: a usage error exits 2 with the usage line")
    void usageExitsTwo() {
        var none = run();
        var three = run(word(), word(), word());

        assertAll(
                () -> assertEquals(VerifyAnchors.FAILED, none.code(), "no argument"),
                () -> assertEquals(VerifyAnchors.USAGE + "\n", none.err(), "the usage line"),
                () -> assertEquals(VerifyAnchors.FAILED, three.code(), "three arguments"),
                () -> assertEquals("", three.out(), "nothing on standard output"));
    }

    @Test
    @DisplayName("GDR-STORE-17: an invalid path, unreadable allowed signers or no repository exit 2 naming no path")
    void failuresExitTwo(@Remote LocalRemote remote) throws Exception {
        String missing = temp.resolve(word()).toString();
        String notRepository = Files.createDirectory(temp.resolve(word())).toString();
        String signers = anchorSignersFile().toString();

        var invalid = run("a\0b", signers);
        var unreadable = run(gitDir(remote), missing);
        var noRepository = run(notRepository, signers);

        assertAll(
                () -> assertEquals(VerifyAnchors.FAILED, invalid.code(), "invalid path"),
                () -> assertEquals(VerifyAnchors.INVALID_PATH + "\n", invalid.err(), "the diagnostic"),
                () -> assertEquals(VerifyAnchors.FAILED, unreadable.code(), "unreadable allowed signers"),
                () -> assertEquals(VerifyAnchors.FAILED, noRepository.code(), "no repository"),
                () -> assertEquals(GitOutcome.NOT_A_REPOSITORY + " git-dir: not a repository\n", noRepository.err(),
                        "the diagnostic"));
        assertNoSecret(unreadable, missing, gitDir(remote));
        assertNoSecret(noRepository, notRepository, signers);
    }
}
