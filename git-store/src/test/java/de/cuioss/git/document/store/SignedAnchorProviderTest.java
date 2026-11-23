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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The anchor provider {@code signed} (GDR-STORE-18): the statement and its sequence chaining, the SSH signature in the
 * namespace {@code git-document-anchor}, the clock checks, the key source failures, the single INFO record per proof
 * without a key reference, every outcome of the verification, the refusals of OpenSSH {@code sshsig.c} the store
 * mirrors, and, when the {@code ssh-keygen} on the path verifies SSH signatures, the acceptance of a proof by
 * {@code ssh-keygen -Y verify -n git-document-anchor}.
 * <p>
 * Keys, object ids, aliases and key references are generated; the namespace, the statement keywords and the details
 * are the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@DisplayName("SignedAnchorProvider (GDR-STORE-18)")
class SignedAnchorProviderTest {

    private static final Instant START = Instant.parse("2026-11-16T09:30:00Z");
    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final String PRINCIPAL = "anchor@example.invalid";

    @TempDir
    Path temp;

    private final MovableClock clock = new MovableClock(START);
    private final TestKeys.Source source = TestKeys.ed25519().source();
    private final String keyReference = "ghp_" + Generators.letterStrings(24, 32).next();
    private final String alias = Generators.strings("abcdefghijklmnopqrstuvwxyz", 4, 12).next();

    private static ObjectId objectId() {
        byte[] bytes = new byte[20];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = Generators.integers(0, 255).next().byteValue();
        }
        return ObjectId.fromRaw(bytes);
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "a success: " + result.outcome() + " " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static SshSignatures.AcceptedKey accepted(TestKeys.Ed25519 key) {
        return assertInstanceOf(SshSignatures.AcceptedKey.class,
                SshSignatures.parsePublicKeyLine(key.publicKeyLine()), "the public key line parses");
    }

    private AllowedSigners allowedSigners(TestKeys.Ed25519 key, String options) throws IOException {
        Path file = Files.writeString(temp.resolve(Generators.letterStrings(8, 12).next()),
                PRINCIPAL + " " + options + key.publicKeyLine() + "\n", US_ASCII);
        return value(AllowedSigners.read(file));
    }

    private AllowedSigners anchorSigners() throws IOException {
        return allowedSigners(source.key(), "namespaces=\"" + SignedAnchorProvider.NAMESPACE + "\" ");
    }

    private SignedAnchorProvider provider(SigningKeySource keySource, TestKeys.Ed25519 publicKey,
            @Nullable AllowedSigners signers) {
        return new SignedAnchorProvider(new SignedAnchorProvider.AnchorKey(keySource, keyReference,
                accepted(publicKey)), signers, SKEW, clock);
    }

    private SignedAnchorProvider provider() throws IOException {
        return provider(source, source.key(), anchorSigners());
    }

    /** Signs a statement with the key of the source as the provider does, bypassing its clock checks. */
    private byte[] sign(AnchorStatement statement) {
        byte[] statementBytes = statement.encode();
        byte[] signature = source.key().sign(SshSignatures.signedData(SignedAnchorProvider.NAMESPACE, statementBytes));
        return concat(statementBytes,
                SshSignatures.encode(source.key().publicKeyBlob(), SignedAnchorProvider.NAMESPACE, signature)
                        .getBytes(US_ASCII));
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        return joined;
    }

    private static AnchorStatement.Proof split(byte[] proof) {
        return AnchorStatement.split(proof).orElseThrow(() -> new AssertionError("the proof splits"));
    }

    @Nested
    @DisplayName("Obtaining a proof")
    class Obtain {

        @Test
        @DisplayName("GDR-STORE-18: the first proof is the statement with sequence 1 and previous none, signed in git-document-anchor")
        void firstProof() throws Exception {
            var subject = objectId();

            byte[] proof = value(provider().obtain(alias, subject, START, new byte[0]));

            var split = split(proof);
            var decoded = assertInstanceOf(SshSignatures.DecodedSignature.class,
                    SshSignatures.decode(split.signature()), "the signature decodes");
            assertAll(
                    () -> assertEquals(new AnchorStatement(subject, START, 1, null), split.statement(),
                            "object, time of the clock, sequence 1, previous none"),
                    () -> assertEquals("anchor signed 1\nobject " + subject.name()
                            + "\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious none\n",
                            new String(split.statementBytes(), UTF_8), "the statement lines"),
                    () -> assertEquals("git-document-anchor", decoded.namespace(), "the namespace"),
                    () -> assertArrayEquals(source.key().publicKeyBlob(), decoded.publicKeyBlob(), "the anchor key"),
                    () -> assertTrue(SshSignatures.verify(decoded, "git-document-anchor", split.statementBytes()),
                            "the signature covers the statement"),
                    () -> assertFalse(SshSignatures.verify(decoded, SshSignatures.NAMESPACE_GIT,
                            split.statementBytes()), "not valid as a commit signature"));
        }

        @Test
        @DisplayName("GDR-STORE-18: a following proof carries the next sequence and the SHA-256 of the previous proof")
        void chainedProof() throws Exception {
            var provider = provider();
            byte[] first = value(provider.obtain(alias, objectId(), START, new byte[0]));
            clock.set(START.plusSeconds(90));
            var subject = objectId();

            byte[] second = value(provider.obtain(alias, subject, START, first));

            var statement = split(second).statement();
            assertAll(
                    () -> assertEquals(2, statement.sequence(), "the next sequence"),
                    () -> assertArrayEquals(AnchorStatement.sha256(first), statement.previous(),
                            "the SHA-256 of the previous proof"),
                    () -> assertEquals(START.plusSeconds(90), statement.time(), "the time of the clock"),
                    () -> assertEquals(subject, statement.object(), "the subject"));
        }

        @Test
        @DisplayName("GDR-STORE-18: the time of the clock is truncated to whole seconds")
        void wholeSeconds() throws Exception {
            clock.set(START.plusMillis(999));

            byte[] proof = value(provider().obtain(alias, objectId(), START, new byte[0]));

            assertEquals(START, split(proof).statement().time(), "whole seconds");
        }

        @Test
        @DisplayName("GDR-STORE-18: a clock behind the previous proof is REFUSED and nothing is signed")
        void clockBehindPrevious() throws Exception {
            var provider = provider();
            byte[] first = value(provider.obtain(alias, objectId(), START, new byte[0]));
            int signed = source.signCalls();
            clock.set(START.minusSeconds(1));

            var result = provider.obtain(alias, objectId(), START.minusSeconds(1), first);

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED,
                            "signed: clock behind the previous proof"), result, "refused"),
                    () -> assertEquals(signed, source.signCalls(), "nothing signed"));
        }

        @Test
        @DisplayName("GDR-STORE-18: a clock at the time of the previous proof is not behind it")
        void clockAtPrevious() throws Exception {
            var provider = provider();
            byte[] first = value(provider.obtain(alias, objectId(), START, new byte[0]));

            var result = provider.obtain(alias, objectId(), START, first);

            assertEquals(2, split(value(result)).statement().sequence(), "signed");
        }

        @Test
        @DisplayName("GDR-STORE-18: a clock more than anchor.signed.max-clock-skew behind the commit time is REFUSED")
        void clockBeyondSkew() throws Exception {
            var provider = provider();

            var beyond = provider.obtain(alias, objectId(), START.plus(SKEW).plusSeconds(1), new byte[0]);
            int signed = source.signCalls();
            var within = provider.obtain(alias, objectId(), START.plus(SKEW), new byte[0]);

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED,
                            "signed: clock behind the commit time of the subject"), beyond, "beyond the skew"),
                    () -> assertEquals(0, signed, "nothing signed beyond the skew"),
                    () -> assertTrue(within.isOk(), "exactly the skew is accepted"));
        }

        @Test
        @DisplayName("GDR-STORE-18: an unreachable key source is UNAVAILABLE, a refusing one REFUSED, neither detail passed on")
        void keySourceFailures() throws Exception {
            String secret = "ghp_" + Generators.letterStrings(24, 32).next();
            var unavailable = provider(new FailingSource(StoreOutcome.UNAVAILABLE, secret), source.key(), null);
            var refusing = provider(new FailingSource(StoreOutcome.REFUSED, secret), source.key(), null);

            var unreachable = unavailable.obtain(alias, objectId(), START, new byte[0]);
            var refused = refusing.obtain(alias, objectId(), START, new byte[0]);

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.UNAVAILABLE, "signed: key source unavailable"),
                            unreachable, "unavailable"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signed: key source refused"), refused,
                            "refused"));
            assertNothingLogged(secret);
        }

        @Test
        @DisplayName("GDR-STORE-18: a signature that does not match the public key read at start is REFUSED")
        void keyMismatch() throws Exception {
            var provider = provider(TestKeys.ed25519().source(), source.key(), null);

            var result = provider.obtain(alias, objectId(), START, new byte[0]);

            assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signed: signature does not match the public key"),
                    result, "refused");
        }

        @Test
        @DisplayName("GDR-STORE-18: an unreadable previous proof and an exhausted sequence are REFUSED")
        void unusablePrevious() throws Exception {
            var provider = provider();
            byte[] exhausted = sign(new AnchorStatement(objectId(), START, Long.MAX_VALUE,
                    AnchorStatement.sha256(new byte[0])));

            var unreadable = provider.obtain(alias, objectId(), START, "garbage".getBytes(UTF_8));
            var last = provider.obtain(alias, objectId(), START, exhausted);

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signed: previous proof unreadable"),
                            unreadable, "unreadable"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signed: sequence exhausted"), last,
                            "exhausted"),
                    () -> assertEquals(0, source.signCalls(), "nothing signed"));
        }

        @Test
        @DisplayName("GDR-STORE-18: an instance that only verifies refuses to obtain")
        void verifierRefuses() throws Exception {
            var verifier = SignedAnchorProvider.verifier(anchorSigners());

            var result = verifier.obtain(alias, objectId(), START, new byte[0]);

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED,
                            "signed: no anchor key, the provider only verifies"), result, "refused"),
                    () -> assertEquals("signed", verifier.id(), "the id"));
        }
    }

    @Nested
    @DisplayName("The log record of a proof")
    class ProofRecord {

        @Test
        @DisplayName("GDR-STORE-18: every proof is logged once as GIT_STORE-7 with alias, object id, time, sequence and SHA-256")
        void singleInfoRecord() throws Exception {
            var subject = objectId();

            byte[] proof = value(provider().obtain(alias, subject, START, new byte[0]));

            String expected = GitStoreLogMessages.INFO.PROOF_SIGNED.format(alias, subject.name(), START, 1,
                    HexFormat.of().formatHex(AnchorStatement.sha256(proof)));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    GitStoreLogMessages.INFO.PROOF_SIGNED.resolveIdentifierString() + ":");
            LogAsserts.assertLogMessagePresent(TestLogLevel.INFO, expected);
            assertEquals("GIT_STORE-7: Signed an anchor proof of repository " + alias + " for object " + subject.name()
                    + " at 2026-11-16T09:30:00Z, sequence 1, SHA-256 "
                    + HexFormat.of().formatHex(AnchorStatement.sha256(proof)), expected, "exactly these fields");
            assertNothingLogged(keyReference);
            assertNothingLogged(source.key().publicKeyLine().split(" ")[1]);
        }

        @Test
        @DisplayName("GDR-STORE-18: a refused obtain logs no proof record")
        void noRecordOnRefusal() throws Exception {
            provider().obtain(alias, objectId(), START.plus(SKEW).plusSeconds(1), new byte[0]);

            assertTrue(TestLoggerFactory.getTestHandler().getRecords().stream()
                            .noneMatch(logRecord -> String.valueOf(logRecord.getMessage())
                                    .contains(GitStoreLogMessages.INFO.PROOF_SIGNED.resolveIdentifierString() + ":")),
                    "no GIT_STORE-7");
        }
    }

    @Nested
    @DisplayName("Verifying a proof")
    class Verify {

        @Test
        @DisplayName("GDR-STORE-18: a proof and its successor verify, returning the time of their statements")
        void validChain() throws Exception {
            var provider = provider();
            var first = objectId();
            var second = objectId();
            byte[] firstProof = value(provider.obtain(alias, first, START, new byte[0]));
            clock.set(START.plusSeconds(60));
            byte[] secondProof = value(provider.obtain(alias, second, START, firstProof));
            var verifier = SignedAnchorProvider.verifier(anchorSigners());

            assertAll(
                    () -> assertEquals(StoreResult.ok(START), verifier.verify(first, firstProof, new byte[0]),
                            "the first"),
                    () -> assertEquals(StoreResult.ok(START.plusSeconds(60)),
                            verifier.verify(second, secondProof, firstProof), "the second"));
        }

        @Test
        @DisplayName("GDR-STORE-18: a proof that cannot be read is SIGNATURE_INVALID")
        void unreadable() throws Exception {
            var subject = objectId();
            byte[] statement = new AnchorStatement(subject, START, 1, null).encode();

            var result = provider().verify(subject, statement, new byte[0]);

            assertEquals(StoreResult.of(StoreOutcome.SIGNATURE_INVALID, "signed: proof unreadable"), result,
                    "unreadable");
        }

        @Test
        @DisplayName("GDR-STORE-18: a proof about another object id is IMPRINT_MISMATCH")
        void imprintMismatch() throws Exception {
            var provider = provider();
            byte[] proof = value(provider.obtain(alias, objectId(), START, new byte[0]));

            var result = provider.verify(objectId(), proof, new byte[0]);

            assertEquals(StoreResult.of(StoreOutcome.IMPRINT_MISMATCH, "signed: proof about another object"), result,
                    "mismatch");
        }

        @Test
        @DisplayName("GDR-STORE-18: without anchor.signed.allowed-signers no proof verifies")
        void noAllowedSigners() {
            var provider = provider(source, source.key(), null);
            var subject = objectId();
            byte[] proof = value(provider.obtain(alias, subject, START, new byte[0]));

            var result = provider.verify(subject, proof, new byte[0]);

            assertEquals(StoreResult.of(StoreOutcome.SIGNATURE_INVALID,
                    "signed: anchor.signed.allowed-signers is missing"), result, "no trusted key");
        }

        @Test
        @DisplayName("GDR-STORE-18: a statement changed after signing is SIGNATURE_INVALID")
        void tamperedStatement() throws Exception {
            var provider = provider();
            var subject = objectId();
            byte[] proof = value(provider.obtain(alias, subject, START, new byte[0]));
            byte[] tampered = new String(proof, UTF_8).replace("time 2026-11-16T09:30:00Z", "time 2026-11-16T09:31:00Z")
                    .getBytes(UTF_8);

            var result = provider.verify(subject, tampered, new byte[0]);

            assertEquals(StoreResult.of(StoreOutcome.SIGNATURE_INVALID, "signed: signature invalid"), result,
                    "tampered");
        }

        @Test
        @DisplayName("GDR-STORE-18: a key not listed for git-document-anchor or outside its validity is SIGNATURE_INVALID")
        void keyNotAllowed() throws Exception {
            var subject = objectId();
            byte[] proof = sign(new AnchorStatement(subject, START, 1, null));
            var gitOnly = SignedAnchorProvider.verifier(allowedSigners(source.key(), "namespaces=\"git\" "));
            var expired = SignedAnchorProvider.verifier(allowedSigners(source.key(),
                    "namespaces=\"git-document-anchor\",valid-before=\"20261116Z\" "));
            var other = SignedAnchorProvider.verifier(allowedSigners(TestKeys.ed25519(), ""));
            var unrestricted = SignedAnchorProvider.verifier(allowedSigners(source.key(), ""));
            var refusal = StoreResult.<Instant>of(StoreOutcome.SIGNATURE_INVALID,
                    "signed: key not allowed at the statement time");

            assertAll(
                    () -> assertEquals(refusal, gitOnly.verify(subject, proof, new byte[0]), "listed for git only"),
                    () -> assertEquals(refusal, expired.verify(subject, proof, new byte[0]),
                            "valid before the statement time only"),
                    () -> assertEquals(refusal, other.verify(subject, proof, new byte[0]), "another key"),
                    () -> assertEquals(StoreResult.ok(START), unrestricted.verify(subject, proof, new byte[0]),
                            "a key without namespaces is used for every namespace"));
        }

        @Test
        @DisplayName("GDR-STORE-18: a first proof with a sequence other than 1 is CHAIN_BROKEN")
        void firstWithGap() throws Exception {
            var provider = provider();
            var subject = objectId();
            byte[] proof = sign(new AnchorStatement(subject, START, 2, AnchorStatement.sha256(new byte[0])));

            var result = provider.verify(subject, proof, new byte[0]);

            assertEquals(StoreResult.of(StoreOutcome.CHAIN_BROKEN,
                    "signed: sequence does not follow the previous proof"), result, "gap");
        }

        @Test
        @DisplayName("GDR-STORE-18: a sequence gap, a wrong predecessor hash and a decreasing time are CHAIN_BROKEN")
        void brokenChains() throws Exception {
            var provider = provider();
            var subject = objectId();
            byte[] first = sign(new AnchorStatement(objectId(), START, 1, null));
            byte[] otherFirst = sign(new AnchorStatement(objectId(), START, 1, null));
            byte[] gap = sign(new AnchorStatement(subject, START.plusSeconds(1), 3, AnchorStatement.sha256(first)));
            byte[] wrongHash = sign(new AnchorStatement(subject, START.plusSeconds(1), 2,
                    AnchorStatement.sha256(otherFirst)));
            byte[] earlier = sign(new AnchorStatement(subject, START.minusSeconds(1), 2,
                    AnchorStatement.sha256(first)));
            byte[] again = sign(new AnchorStatement(subject, START, 1, null));

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.CHAIN_BROKEN,
                                    "signed: sequence does not follow the previous proof"),
                            provider.verify(subject, gap, first), "a gap"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.CHAIN_BROKEN,
                                    "signed: previous hash does not match the previous proof"),
                            provider.verify(subject, wrongHash, first), "another predecessor"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.CHAIN_BROKEN, "signed: time before the previous proof"),
                            provider.verify(subject, earlier, first), "a decreasing time"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.CHAIN_BROKEN,
                                    "signed: sequence does not follow the previous proof"),
                            provider.verify(subject, again, first), "a second sequence 1"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.CHAIN_BROKEN, "signed: previous proof unreadable"),
                            provider.verify(subject, gap, "garbage".getBytes(UTF_8)), "an unreadable predecessor"));
        }

        @Test
        @DisplayName("GDR-STORE-18: an equal time as the previous proof keeps the chain intact")
        void equalTime() throws Exception {
            var provider = provider();
            var subject = objectId();
            byte[] first = sign(new AnchorStatement(objectId(), START, 1, null));
            byte[] second = sign(new AnchorStatement(subject, START, 2, AnchorStatement.sha256(first)));

            assertEquals(StoreResult.ok(START), provider.verify(subject, second, first), "never decreasing");
        }

        @Test
        @DisplayName("GDR-STORE-18: the proof is copied before it is read, so the caller's buffer is not used afterwards")
        void defensiveCopy() throws Exception {
            var provider = provider();
            var subject = objectId();
            byte[] proof = value(provider.obtain(alias, subject, START, new byte[0]));
            byte[] original = proof.clone();

            var result = provider.verify(subject, proof, new byte[0]);

            assertAll(
                    () -> assertEquals(StoreResult.ok(START), result, "verified"),
                    () -> assertArrayEquals(original, proof, "the caller's buffer is unchanged"));
        }
    }

    @Nested
    @DisplayName("The refusals of OpenSSH sshsig.c the store mirrors")
    class SshsigRefusals {

        private static final byte[] MAGIC = "SSHSIG".getBytes(US_ASCII);

        private static void writeString(ByteArrayOutputStream out, byte[] value) {
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            out.writeBytes(value);
        }

        /** Builds an armored signature blob field by field, so one field at a time can differ from the format. */
        private static String armor(byte[] magic, int version, byte[] publicKey, String namespace,
                String hashAlgorithm, String signatureType, byte[] rawSignature, byte[] trailing) {
            var signature = new ByteArrayOutputStream();
            writeString(signature, signatureType.getBytes(US_ASCII));
            writeString(signature, rawSignature);
            var blob = new ByteArrayOutputStream();
            blob.writeBytes(magic);
            blob.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(version).array());
            writeString(blob, publicKey);
            writeString(blob, namespace.getBytes(UTF_8));
            writeString(blob, new byte[0]);
            writeString(blob, hashAlgorithm.getBytes(US_ASCII));
            writeString(blob, signature.toByteArray());
            blob.writeBytes(trailing);
            return SshSignatures.BEGIN + "\n" + Base64.getEncoder().encodeToString(blob.toByteArray()) + "\n"
                    + SshSignatures.END + "\n";
        }

        private StoreResult<Instant> verifyWith(ObjectId subject, byte[] statement, String armored)
                throws IOException {
            return provider().verify(subject, concat(statement, armored.getBytes(US_ASCII)), new byte[0]);
        }

        private static StoreResult<Instant> invalid() {
            return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, "signed: signature invalid");
        }

        @Test
        @DisplayName("GDR-STORE-18: a signature blob with another magic preamble is refused (sshsig.c: invalid magic)")
        void wrongMagic() throws Exception {
            var subject = objectId();
            byte[] statement = new AnchorStatement(subject, START, 1, null).encode();
            byte[] raw = source.key().sign(SshSignatures.signedData(SignedAnchorProvider.NAMESPACE, statement));

            var result = verifyWith(subject, statement, armor("SSHSIH".getBytes(US_ASCII), 1,
                    source.key().publicKeyBlob(), SignedAnchorProvider.NAMESPACE, "sha512", "ssh-ed25519", raw,
                    new byte[0]));

            assertEquals(invalid(), result, "refused");
        }

        @Test
        @DisplayName("GDR-STORE-18: a signature blob of another version is refused (sshsig.c: unsupported version)")
        void otherVersion() throws Exception {
            var subject = objectId();
            byte[] statement = new AnchorStatement(subject, START, 1, null).encode();
            byte[] raw = source.key().sign(SshSignatures.signedData(SignedAnchorProvider.NAMESPACE, statement));

            var result = verifyWith(subject, statement, armor(MAGIC, 2, source.key().publicKeyBlob(),
                    SignedAnchorProvider.NAMESPACE, "sha512", "ssh-ed25519", raw, new byte[0]));

            assertEquals(invalid(), result, "refused");
        }

        @Test
        @DisplayName("GDR-STORE-18: a signature made in the namespace git is refused (sshsig.c: namespace mismatch)")
        void otherNamespace() throws Exception {
            var subject = objectId();
            byte[] statement = new AnchorStatement(subject, START, 1, null).encode();
            byte[] raw = source.key().sign(SshSignatures.signedData(SshSignatures.NAMESPACE_GIT, statement));

            var result = verifyWith(subject, statement, armor(MAGIC, 1, source.key().publicKeyBlob(),
                    SshSignatures.NAMESPACE_GIT, "sha512", "ssh-ed25519", raw, new byte[0]));

            assertEquals(invalid(), result, "refused");
        }

        @Test
        @DisplayName("GDR-STORE-18: a hash algorithm other than sha512 or sha256 is refused (sshsig.c: unsupported hash)")
        void otherHashAlgorithm() throws Exception {
            var subject = objectId();
            byte[] statement = new AnchorStatement(subject, START, 1, null).encode();
            byte[] raw = source.key().sign(SshSignatures.signedData(SignedAnchorProvider.NAMESPACE, statement));

            var result = verifyWith(subject, statement, armor(MAGIC, 1, source.key().publicKeyBlob(),
                    SignedAnchorProvider.NAMESPACE, "sha1", "ssh-ed25519", raw, new byte[0]));

            assertEquals(invalid(), result, "refused");
        }

        @Test
        @DisplayName("GDR-STORE-18: a signature type other than the key type is refused (sshsig.c: signature type)")
        void otherSignatureType() throws Exception {
            var subject = objectId();
            byte[] statement = new AnchorStatement(subject, START, 1, null).encode();
            byte[] raw = source.key().sign(SshSignatures.signedData(SignedAnchorProvider.NAMESPACE, statement));

            var result = verifyWith(subject, statement, armor(MAGIC, 1, source.key().publicKeyBlob(),
                    SignedAnchorProvider.NAMESPACE, "sha512", "rsa-sha2-512", raw, new byte[0]));

            assertEquals(invalid(), result, "refused");
        }

        @Test
        @DisplayName("GDR-STORE-18: bytes after the signature blob are refused (sshsig.c: trailing data)")
        void trailingData() throws Exception {
            var subject = objectId();
            byte[] statement = new AnchorStatement(subject, START, 1, null).encode();
            byte[] raw = source.key().sign(SshSignatures.signedData(SignedAnchorProvider.NAMESPACE, statement));

            var result = verifyWith(subject, statement, armor(MAGIC, 1, source.key().publicKeyBlob(),
                    SignedAnchorProvider.NAMESPACE, "sha512", "ssh-ed25519", raw, new byte[]{0}));

            assertEquals(invalid(), result, "refused");
        }

        @Test
        @DisplayName("GDR-STORE-18: a signature over other bytes is refused (sshsig.c: signature verification failed)")
        void otherMessage() throws Exception {
            var subject = objectId();
            byte[] statement = new AnchorStatement(subject, START, 1, null).encode();
            byte[] raw = source.key().sign(SshSignatures.signedData(SignedAnchorProvider.NAMESPACE,
                    new AnchorStatement(objectId(), START, 1, null).encode()));

            var result = verifyWith(subject, statement, armor(MAGIC, 1, source.key().publicKeyBlob(),
                    SignedAnchorProvider.NAMESPACE, "sha512", "ssh-ed25519", raw, new byte[0]));

            assertEquals(invalid(), result, "refused");
        }

        @Test
        @DisplayName("GDR-STORE-18: the blob built field by field with the format's values verifies, so each refusal is one field")
        void controlBlob() throws Exception {
            var subject = objectId();
            byte[] statement = new AnchorStatement(subject, START, 1, null).encode();
            byte[] raw = source.key().sign(SshSignatures.signedData(SignedAnchorProvider.NAMESPACE, statement));

            var result = verifyWith(subject, statement, armor(MAGIC, 1, source.key().publicKeyBlob(),
                    SignedAnchorProvider.NAMESPACE, "sha512", "ssh-ed25519", raw, new byte[0]));

            assertEquals(StoreResult.ok(START), result, "verified");
        }
    }

    @Nested
    @DisplayName("Stock ssh-keygen")
    class StockTool {

        @Test
        @DisplayName("GDR-STORE-18: ssh-keygen -Y verify -n git-document-anchor accepts a proof (skipped without -Y verify)")
        void sshKeygenAccepts(@TempDir Path directory) throws Exception {
            Optional<Path> keygen = ExternalTools.onPath("ssh-keygen");
            assumeTrue(keygen.isPresent(), "ssh-keygen is not on the path");
            assumeTrue(ExternalTools.verifiesSignatures(keygen.get(), directory),
                    "the ssh-keygen on the path signs and verifies SSH signatures (-Y sign, -Y verify)");
            var split = split(value(provider().obtain(alias, objectId(), START, new byte[0])));
            Path allowed = Files.writeString(directory.resolve("allowed_signers"), PRINCIPAL
                    + " namespaces=\"git-document-anchor\" " + source.key().publicKeyLine() + "\n", US_ASCII);
            Path signature = Files.writeString(directory.resolve("statement.sig"), split.signature(), US_ASCII);
            Path statement = Files.write(directory.resolve("statement"), split.statementBytes());

            var accepted = ExternalTools.run(new ProcessBuilder(keygen.get().toString(), "-Y", "verify", "-f",
                    allowed.toString(), "-I", PRINCIPAL, "-n", "git-document-anchor", "-s",
                    signature.toString()), directory.resolve("accepted"), statement);
            var otherNamespace = ExternalTools.run(new ProcessBuilder(keygen.get().toString(), "-Y", "verify", "-f",
                            allowed.toString(), "-I", PRINCIPAL, "-n", "git", "-s", signature.toString()),
                    directory.resolve("rejected"), statement);

            assertAll(
                    () -> assertEquals(0, accepted.code(), () -> "ssh-keygen accepted the proof: " + accepted.output()),
                    () -> assertNotEquals(0, otherNamespace.code(), "ssh-keygen rejects it in the namespace git"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-18: the rendering names neither the key reference nor key material")
    void rendering() throws Exception {
        var provider = provider();
        var verifier = SignedAnchorProvider.verifier(anchorSigners());

        assertAll(
                () -> assertEquals("SignedAnchorProvider[signs=true, verifies=true]", provider.toString(), "signing"),
                () -> assertEquals("SignedAnchorProvider[signs=false, verifies=true]", verifier.toString(), "verifier"),
                () -> assertEquals("AnchorKey[source=" + TestKeys.Source.ID + "]",
                        new SignedAnchorProvider.AnchorKey(source, keyReference, accepted(source.key())).toString(),
                        "the anchor key names its source only"));
    }

    private static void assertNothingLogged(String text) {
        assertTrue(TestLoggerFactory.getTestHandler().getRecords().stream()
                        .noneMatch(logRecord -> String.valueOf(logRecord.getMessage()).contains(text)
                                || Arrays.stream(Objects.requireNonNullElse(logRecord.getParameters(), new Object[0]))
                                .anyMatch(parameter -> String.valueOf(parameter).contains(text))),
                "no log record carries the text");
    }

    /** A signing key source that fails with an outcome and a detail carrying a secret. */
    private record FailingSource(StoreOutcome outcome, String secret) implements SigningKeySource {

        @Override
        public String id() {
            return "failing";
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            return StoreResult.of(outcome, "the source failed: " + secret);
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            return StoreResult.of(outcome, "the source failed: " + secret);
        }
    }

    /** A clock whose instant a test sets. */
    private static final class MovableClock extends Clock {

        private final AtomicReference<Instant> instant;

        MovableClock(Instant start) {
            this.instant = new AtomicReference<>(start);
        }

        void set(Instant next) {
            instant.set(next);
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
            return instant.get();
        }
    }
}
