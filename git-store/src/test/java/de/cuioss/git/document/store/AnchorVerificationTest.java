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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.store.AnchorVerification.Finding;
import de.cuioss.git.document.store.AnchorVerification.Kind;
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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The anchor verification {@link AnchorVerification#verifyAnchors} against a {@code git-testing} local remote
 * (GDR-STORE-17, GDR-STORE-18). The anchoring is written by the engine through {@link StoreWriter#anchor()} with the
 * provider {@code signed} over a generated anchor key, and verified with {@link SignedAnchorProvider#verifier} against
 * the anchor allowed signers: a clean chain; a missing checkpoint number and a gap; an unreachable, a deleted and a
 * retargeted listed reference; a subject without a proof; and, with notes written directly onto the notes reference,
 * an imprint mismatch, an invalid signature, a key the allowed signers do not list, a sequence gap, a wrong predecessor
 * hash, a decreasing time and a note that is no base64. The checkpoint and notes commits the engine writes verify under
 * {@link SignatureVerification} with no unverified commit (GDR-STORE-14).
 * <p>
 * Keys, names, ids and contents are generated; the reference layout, the namespace and the outcomes are the specified
 * vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("Anchor verification (GDR-STORE-17, GDR-STORE-18)")
class AnchorVerificationTest {

    /** The checkpoint interval of every test. */
    private static final Duration INTERVAL = Duration.ofHours(1);

    /** The principal of every allowed-signers line. */
    private static final String PRINCIPAL = "anchor@example.invalid";

    @TempDir
    Path temp;

    private final TestKeys.Source commitKeys = TestKeys.ed25519().source();
    private final TestKeys.Source anchorKeys = TestKeys.ed25519().source();
    private final MovableClock clock = new MovableClock(
            Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next()));

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.outcome() + " " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    /** A clock that stands still until a test moves it. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant start) {
            now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static StoreConfiguration configuration() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", word()));
        tree.put("anchor", Map.of("provider", SignedAnchorProvider.PROVIDER_ID, "checkpoint-interval", INTERVAL.toString(),
                "retry-interval", Duration.ofHours(12).toString()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    private AllowedSigners signers(TestKeys.Ed25519 key, String options) throws IOException {
        Path file = Files.writeString(temp.resolve(word()), PRINCIPAL + " " + options + key.publicKeyLine() + "\n",
                US_ASCII);
        return value(AllowedSigners.read(file));
    }

    /** The anchor allowed signers: the anchor key, for the namespace of the anchoring only. */
    private AllowedSigners anchorSigners() throws IOException {
        return signers(anchorKeys.key(), "namespaces=\"" + SignedAnchorProvider.NAMESPACE + "\" ");
    }

    private SignedAnchorProvider signing() {
        var accepted = assertInstanceOf(SshSignatures.AcceptedKey.class,
                SshSignatures.parsePublicKeyLine(anchorKeys.key().publicKeyLine()), "the public key line parses");
        return new SignedAnchorProvider(new SignedAnchorProvider.AnchorKey(anchorKeys, word(), accepted), null,
                Duration.ofMinutes(5), clock);
    }

    private StoreWriter open(LocalRemote remote) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        return value(StoreWriter.open(storeRepository, temp.resolve(word() + ".git"), user, configuration(), List.of(),
                new StoreProviders(commitKeys, new TestPersonDirectory(), signing()),
                new ScriptedRepositoryOperations(), clock));
    }

    private AnchorVerification.Report verify(LocalRemote remote) throws IOException {
        return value(AnchorVerification.verifyAnchors(remote.repository(),
                SignedAnchorProvider.verifier(anchorSigners())));
    }

    /** Writes a commit holding one generated file at a time onto a reference, forcing the reference. */
    private static ObjectId commit(Repository repository, String ref, Instant time, ObjectId... parents)
            throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(word(), FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "", time, ZoneOffset.UTC);
            commit.setTreeId(inserter.insert(tree));
            commit.setParentIds(parents);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
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

    private static void delete(Repository repository, String ref) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setForceUpdate(true);
        assertEquals(RefUpdate.Result.FORCED, update.delete(), "the fixture reference is deleted");
    }

    private static ObjectId tip(Repository repository, String ref) throws IOException {
        Ref found = repository.exactRef(ref);
        assertNotNull(found, () -> "the reference exists: " + ref);
        return found.getObjectId();
    }

    private static String releaseTag() {
        var collection = value(DocumentCollection.of("c" + word(), DocumentCollection.Kind.PER_DOCUMENT));
        return StoreReferences.releaseTag(value(DocumentKey.of(collection, Ulid.next())), 1);
    }

    /** Anchors {@code count} checkpoints, moving {@code main} and the clock between them. */
    private void checkpoints(StoreWriter writer, Repository remote, int count) throws IOException {
        for (int n = 1; n <= count; n++) {
            value(writer.anchor());
            commit(remote, StoreReferences.MAIN, clock.instant(), tip(remote, StoreReferences.MAIN));
            clock.advance(INTERVAL);
        }
    }

    @Nested
    @DisplayName("Checkpoints, subjects and listed references (GDR-STORE-17)")
    class Anchoring {

        @Test
        @DisplayName("GDR-STORE-17, GDR-STORE-18: a chain the engine wrote with the provider signed verifies clean")
        void cleanChain(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
            commit(repository, releaseTag(), Instant.ofEpochSecond(200_000));

            try (var writer = open(remote)) {
                checkpoints(writer, repository, 2);
            }
            var report = verify(remote);

            assertAll(
                    () -> assertEquals(List.of(), report.findings(), "no finding"),
                    () -> assertTrue(report.clean(), "clean"),
                    () -> assertEquals(2, report.checkpoints(), "two checkpoints"),
                    () -> assertEquals(3, report.subjects(), "two checkpoints and a release commit"),
                    () -> assertEquals(3, report.proofs(), "three proofs in chain order"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            GitStoreLogMessages.INFO.ANCHORS_VERIFIED.resolveIdentifierString()));
        }

        @Test
        @DisplayName("GDR-STORE-17: a missing checkpoint number and a gap of numbers are listed")
        void missingCheckpointNumbers(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
            try (var writer = open(remote)) {
                checkpoints(writer, repository, 6);
            }
            delete(repository, StoreReferences.checkpoint(2));
            delete(repository, StoreReferences.checkpoint(4));
            delete(repository, StoreReferences.checkpoint(5));

            var report = verify(remote);

            assertEquals(List.of(new Finding(Kind.CHECKPOINT_MISSING, "refs/tags/checkpoints/2", null, null),
                            new Finding(Kind.CHECKPOINT_MISSING, "refs/tags/checkpoints/4..5", null, null)),
                    report.findings(), "the missing number and the gap, nothing else");
        }

        @Test
        @DisplayName("GDR-STORE-17: a listed branch rewritten or deleted and a listed tag retargeted are listed")
        void unreachableAndRetargetedReferences(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            var main = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
            String branch = "refs/heads/" + word();
            String tag = "refs/tags/" + word();
            commit(repository, branch, Instant.ofEpochSecond(110_000), main);
            setRef(repository, tag, main);
            try (var writer = open(remote)) {
                checkpoints(writer, repository, 1);
            }
            var checkpoint = tip(repository, StoreReferences.checkpoint(1)).name();
            commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(120_000));
            delete(repository, branch);
            setRef(repository, tag, commit(repository, "refs/heads/" + word(), Instant.ofEpochSecond(130_000)));

            var report = verify(remote);

            assertEquals(Set.of(new Finding(Kind.REFERENCE_UNREACHABLE, checkpoint, StoreReferences.MAIN, null),
                            new Finding(Kind.REFERENCE_UNREACHABLE, checkpoint, branch, null),
                            new Finding(Kind.TAG_RETARGETED, checkpoint, tag, null)), Set.copyOf(report.findings()),
                    "the rewritten main, the deleted branch and the retargeted tag");
        }

        @Test
        @DisplayName("GDR-STORE-17: a branch that moved forward still reaches the listed id")
        void forwardMoveReachesListedId(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
            try (var writer = open(remote)) {
                checkpoints(writer, repository, 1);
            }

            assertEquals(List.of(), verify(remote).findings(), "moving forward keeps the listed id reachable");
        }

        @Test
        @DisplayName("GDR-STORE-17: a release commit without a note is listed as without a proof")
        void subjectWithoutProof(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
            try (var writer = open(remote)) {
                checkpoints(writer, repository, 1);
            }
            var release = commit(repository, releaseTag(), Instant.ofEpochSecond(200_000));

            var report = verify(remote);

            assertAll(
                    () -> assertEquals(List.of(new Finding(Kind.NO_PROOF, release.name(), null, null)),
                            report.findings(), "the release commit has no proof"),
                    () -> assertEquals(2, report.subjects(), "the checkpoint and the release commit"));
        }

        @Test
        @DisplayName("GDR-STORE-17: an annotated release tag on a commit is peeled and verifies clean")
        void annotatedReleaseTagVerifies(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
            var release = commit(repository, "refs/heads/" + word(), Instant.ofEpochSecond(200_000));
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                var annotated = new TagBuilder();
                annotated.setObjectId(release, Constants.OBJ_COMMIT);
                annotated.setTag(word());
                annotated.setTagger(new PersonIdent(word(), "", Instant.ofEpochSecond(200_000), ZoneOffset.UTC));
                annotated.setMessage(word());
                var tagObject = inserter.insert(annotated);
                inserter.flush();
                setRef(repository, releaseTag(), tagObject);
            }
            try (var writer = open(remote)) {
                checkpoints(writer, repository, 1);
            }

            var report = verify(remote);

            assertAll(
                    () -> assertEquals(List.of(), report.findings(), "no finding"),
                    () -> assertEquals(2, report.subjects(), "the checkpoint and the peeled release commit"));
        }

        @Test
        @DisplayName("GDR-STORE-17: a release tag on a blob fails the verification, never a clean report")
        void releaseTagOnBlobFails(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
            try (var writer = open(remote)) {
                checkpoints(writer, repository, 1);
            }
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                var blob = inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8));
                inserter.flush();
                setRef(repository, releaseTag(), blob);
            }

            var result = AnchorVerification.verifyAnchors(repository, SignedAnchorProvider.verifier(anchorSigners()));

            assertAll(
                    () -> assertEquals(new StoreResult.PassedOn(GitOutcome.FAILED), result.outcome(), "a failure"),
                    () -> assertEquals("anchors: IncorrectObjectTypeException", result.detail(),
                            "the target is no commit"));
        }

        @Test
        @DisplayName("GDR-STORE-17: a checkpoint tag on a missing object fails the verification, never a clean report")
        void checkpointTagOnMissingObjectFails(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
            try (var writer = open(remote)) {
                checkpoints(writer, repository, 1);
            }
            // JGit refuses a reference update onto a missing object, so the loose reference is written as a file
            Path loose = repository.getDirectory().toPath().resolve(StoreReferences.checkpoint(2));
            Files.writeString(loose, Generators.strings("0123456789abcdef", 40, 40).next() + "\n", US_ASCII);

            var result = AnchorVerification.verifyAnchors(repository, SignedAnchorProvider.verifier(anchorSigners()));

            assertAll(
                    () -> assertEquals(new StoreResult.PassedOn(GitOutcome.FAILED), result.outcome(), "a failure"),
                    () -> assertEquals("anchors: MissingObjectException", result.detail(),
                            "the target is missing"));
        }

        @Test
        @DisplayName("GDR-STORE-14: the checkpoint and notes commits of the engine verify under SignatureVerification")
        void engineCommitsVerifyAsSigned(@Remote LocalRemote remote) throws Exception {
            var repository = remote.repository();
            commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
            try (var writer = open(remote)) {
                checkpoints(writer, repository, 2);
            }
            var commitSigners = signers(commitKeys.key(), "");

            var checkpoints = value(SignatureVerification.verify(repository, StoreReferences.checkpoint(2),
                    commitSigners));
            var notes = value(SignatureVerification.verify(repository,
                    StoreReferences.anchorNotes(SignedAnchorProvider.PROVIDER_ID), commitSigners));

            assertAll(
                    () -> assertEquals(List.of(), checkpoints, "every checkpoint commit is signed and valid"),
                    () -> assertEquals(List.of(), notes, "every notes commit is signed and valid"));
        }
    }

    @Nested
    @DisplayName("The chain of the provider signed (GDR-STORE-18)")
    class Chain {

        private final String notesRef = StoreReferences.anchorNotes(SignedAnchorProvider.PROVIDER_ID);

        private byte[] sign(TestKeys.Ed25519 key, AnchorStatement statement) {
            byte[] statementBytes = statement.encode();
            byte[] signature = key.sign(SshSignatures.signedData(SignedAnchorProvider.NAMESPACE, statementBytes));
            byte[] armored = SshSignatures.encode(key.publicKeyBlob(), SignedAnchorProvider.NAMESPACE, signature)
                    .getBytes(US_ASCII);
            byte[] proof = Arrays.copyOf(statementBytes, statementBytes.length + armored.length);
            System.arraycopy(armored, 0, proof, statementBytes.length, armored.length);
            return proof;
        }

        private byte[] sign(AnchorStatement statement) {
            return sign(anchorKeys.key(), statement);
        }

        private static byte[] sha256(byte[] data) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(data);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("every Java platform provides SHA-256", e);
            }
        }

        /** Adds one note in a new notes commit onto the notes reference of the provider signed. */
        private void note(Repository repository, ObjectId subject, byte[] content) throws IOException {
            Ref ref = repository.exactRef(notesRef);
            try (ObjectInserter inserter = repository.newObjectInserter();
                 ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
                NoteMap map = ref == null ? NoteMap.newEmptyMap()
                        : NoteMap.read(reader, walk.parseCommit(ref.getObjectId()));
                map.set(subject, inserter.insert(Constants.OBJ_BLOB, content));
                var commit = new CommitBuilder();
                var ident = new PersonIdent(word(), "");
                commit.setTreeId(map.writeTree(inserter));
                if (ref != null) {
                    commit.setParentId(ref.getObjectId());
                }
                commit.setAuthor(ident);
                commit.setCommitter(ident);
                commit.setMessage(word());
                var id = inserter.insert(commit);
                inserter.flush();
                setRef(repository, notesRef, id);
            }
        }

        private void proof(Repository repository, ObjectId subject, byte[] proof) throws IOException {
            note(repository, subject, Base64.getEncoder().encode(proof));
        }

        /** Two subjects; the first carries a valid first proof. */
        private record Started(ObjectId first, ObjectId second, byte[] firstProof, Instant time) {
        }

        private Started start(Repository repository) throws IOException {
            var first = commit(repository, "refs/heads/" + word(), Instant.ofEpochSecond(100_000));
            var second = commit(repository, "refs/heads/" + word(), Instant.ofEpochSecond(110_000));
            var time = Instant.ofEpochSecond(1_700_000_000);
            byte[] firstProof = sign(new AnchorStatement(first, time, 1, null));
            proof(repository, first, firstProof);
            return new Started(first, second, firstProof, time);
        }

        private List<Finding> verifyChain(LocalRemote remote) throws IOException {
            return verify(remote).findings();
        }

        private static Finding rejected(ObjectId subject, StoreOutcome outcome) {
            return new Finding(Kind.PROOF_REJECTED, subject.name(), null, outcome);
        }

        @Test
        @DisplayName("GDR-STORE-18: a chain of two proofs written directly verifies clean")
        void directChainVerifies(@Remote LocalRemote remote) throws Exception {
            var started = start(remote.repository());
            proof(remote.repository(), started.second(), sign(new AnchorStatement(started.second(),
                    started.time().plusSeconds(60), 2, sha256(started.firstProof()))));

            var report = verify(remote);

            assertAll(
                    () -> assertEquals(List.of(), report.findings(), "no finding"),
                    () -> assertEquals(2, report.proofs(), "two proofs"));
        }

        @Test
        @DisplayName("GDR-STORE-18: a proof about another object is an imprint mismatch")
        void imprintMismatch(@Remote LocalRemote remote) throws Exception {
            var started = start(remote.repository());
            proof(remote.repository(), started.second(), sign(new AnchorStatement(started.first(),
                    started.time().plusSeconds(60), 2, sha256(started.firstProof()))));

            assertEquals(List.of(rejected(started.second(), StoreOutcome.IMPRINT_MISMATCH)), verifyChain(remote),
                    "the second proof names the first subject");
        }

        @Test
        @DisplayName("GDR-STORE-18: a statement changed after signing has an invalid signature")
        void invalidSignature(@Remote LocalRemote remote) throws Exception {
            var started = start(remote.repository());
            byte[] signed = sign(new AnchorStatement(started.second(), started.time().plusSeconds(60), 2,
                    sha256(started.firstProof())));
            byte[] forged = new AnchorStatement(started.second(), started.time().plusSeconds(61), 2,
                    sha256(started.firstProof())).encode();
            byte[] tampered = Arrays.copyOf(signed, signed.length);
            System.arraycopy(forged, 0, tampered, 0, forged.length);
            proof(remote.repository(), started.second(), tampered);

            assertEquals(List.of(rejected(started.second(), StoreOutcome.SIGNATURE_INVALID)), verifyChain(remote),
                    "the signature does not cover the changed time");
        }

        @Test
        @DisplayName("GDR-STORE-18: a proof signed by a key the anchor allowed signers do not list is invalid")
        void keyNotAllowed(@Remote LocalRemote remote) throws Exception {
            var started = start(remote.repository());
            proof(remote.repository(), started.second(), sign(TestKeys.ed25519(), new AnchorStatement(
                    started.second(), started.time().plusSeconds(60), 2, sha256(started.firstProof()))));

            assertEquals(List.of(rejected(started.second(), StoreOutcome.SIGNATURE_INVALID)), verifyChain(remote),
                    "an unlisted key");
        }

        @Test
        @DisplayName("GDR-STORE-18: a sequence that skips a number breaks the chain")
        void sequenceGap(@Remote LocalRemote remote) throws Exception {
            var started = start(remote.repository());
            proof(remote.repository(), started.second(), sign(new AnchorStatement(started.second(),
                    started.time().plusSeconds(60), 3, sha256(started.firstProof()))));

            assertEquals(List.of(rejected(started.second(), StoreOutcome.CHAIN_BROKEN)), verifyChain(remote),
                    "sequence 3 after sequence 1");
        }

        @Test
        @DisplayName("GDR-STORE-18: a previous hash that is not the SHA-256 of the previous proof breaks the chain")
        void wrongPredecessorHash(@Remote LocalRemote remote) throws Exception {
            var started = start(remote.repository());
            proof(remote.repository(), started.second(), sign(new AnchorStatement(started.second(),
                    started.time().plusSeconds(60), 2, sha256(word().getBytes(US_ASCII)))));

            assertEquals(List.of(rejected(started.second(), StoreOutcome.CHAIN_BROKEN)), verifyChain(remote),
                    "the previous hash names another proof");
        }

        @Test
        @DisplayName("GDR-STORE-18: a time before the time of the previous proof breaks the chain")
        void decreasingTime(@Remote LocalRemote remote) throws Exception {
            var started = start(remote.repository());
            proof(remote.repository(), started.second(), sign(new AnchorStatement(started.second(),
                    started.time().minusSeconds(1), 2, sha256(started.firstProof()))));

            assertEquals(List.of(rejected(started.second(), StoreOutcome.CHAIN_BROKEN)), verifyChain(remote),
                    "one second before the previous proof");
        }

        @Test
        @DisplayName("GDR-STORE-18: a note that is no base64 is a proof that cannot be read")
        void noteNotBase64(@Remote LocalRemote remote) throws Exception {
            var started = start(remote.repository());
            note(remote.repository(), started.second(), "*** not base64 ***".getBytes(US_ASCII));

            assertEquals(List.of(rejected(started.second(), StoreOutcome.SIGNATURE_INVALID)), verifyChain(remote),
                    "unreadable");
        }

        @Test
        @DisplayName("GDR-STORE-18: a provider that throws rejects the proof and the verification goes on")
        void throwingProvider(@Remote LocalRemote remote) throws Exception {
            var started = start(remote.repository());
            var throwing = new AnchorProvider() {

                @Override
                public String id() {
                    return SignedAnchorProvider.PROVIDER_ID;
                }

                @Override
                public StoreResult<byte[]> obtain(String repository, ObjectId subject, Instant commitTime,
                        byte[] previous) {
                    return StoreResult.of(StoreOutcome.REFUSED, "verify only");
                }

                @Override
                public StoreResult<Instant> verify(ObjectId subject, byte[] proof, byte[] previous) {
                    throw new IllegalStateException(word());
                }
            };

            var report = value(AnchorVerification.verifyAnchors(remote.repository(), throwing));

            assertEquals(List.of(rejected(started.first(), StoreOutcome.SIGNATURE_INVALID)), report.findings(),
                    "rejected, not thrown");
        }

        @Test
        @DisplayName("GDR-STORE-17: a failure that ends the verification is a passed-on failure, never an exception")
        void failureIsPassedOn(@Remote LocalRemote remote) {
            var repository = remote.repository();
            String secret = word();
            var failing = new AnchorProvider() {

                @Override
                public String id() {
                    throw new IllegalStateException(secret);
                }

                @Override
                public StoreResult<byte[]> obtain(String alias, ObjectId subject, Instant commitTime,
                        byte[] previous) {
                    return StoreResult.of(StoreOutcome.REFUSED, "never");
                }

                @Override
                public StoreResult<Instant> verify(ObjectId subject, byte[] proof, byte[] previous) {
                    return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, "never");
                }
            };

            var result = AnchorVerification.verifyAnchors(repository, failing);

            assertAll(
                    () -> assertFalse(result.isOk(), "a non-success"),
                    () -> assertEquals("anchors: IllegalStateException", result.detail(),
                            "the simple class name only, never the message"));
        }
    }
}
