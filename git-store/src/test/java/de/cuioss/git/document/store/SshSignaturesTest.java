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
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The SSH signature format of GDR-STORE-12 as {@link SshSignatures} encodes, decodes and verifies it
 * (<a href="https://github.com/openssh/openssh-portable/blob/master/PROTOCOL.sshsig">PROTOCOL.sshsig</a>), with a
 * per-run Ed25519 key of {@link TestKeys}: the round trip, the armor, the refusal of tampered, foreign and malformed
 * input without exception, the refusal of other key types, and, when the {@code ssh-keygen} on the path signs and
 * verifies SSH signatures ({@link ExternalTools#verifiesSignatures}), the acceptance of an encoded signature by that
 * same {@code ssh-keygen -Y verify}.
 */
@EnableGeneratorController
@DisplayName("SshSignatures (GDR-STORE-12)")
class SshSignaturesTest {

    private static byte[] message() {
        return Generators.letterStrings(1, 200).next().getBytes(UTF_8);
    }

    private static String sign(TestKeys.Ed25519 key, String namespace, byte[] message) {
        return SshSignatures.encode(key.publicKeyBlob(), namespace,
                key.sign(SshSignatures.signedData(namespace, message)));
    }

    private static SshSignatures.DecodedSignature decoded(String armored) {
        return assertInstanceOf(SshSignatures.DecodedSignature.class, SshSignatures.decode(armored),
                "the signature decodes");
    }

    @Nested
    @DisplayName("Encoding")
    class Encoding {

        @Test
        @DisplayName("GDR-STORE-12: an encoded signature decodes to its fields and verifies over its message")
        void roundTrip() {
            var key = TestKeys.ed25519();
            byte[] message = message();

            var decoded = decoded(sign(key, SshSignatures.NAMESPACE_GIT, message));

            assertAll(() -> assertArrayEquals(key.publicKeyBlob(), decoded.publicKeyBlob(), "the embedded public key"),
                    () -> assertEquals(SshSignatures.NAMESPACE_GIT, decoded.namespace(), "the namespace"),
                    () -> assertEquals(SshSignatures.HASH_SHA512, decoded.hashAlgorithm(), "the hash algorithm"),
                    () -> assertEquals(SshSignatures.KEY_TYPE, decoded.signatureType(), "the signature type"),
                    () -> assertEquals(64, decoded.signature().length, "an Ed25519 signature"),
                    () -> assertTrue(SshSignatures.verify(decoded, SshSignatures.NAMESPACE_GIT, message),
                            "the signature verifies"));
        }

        @Test
        @DisplayName("GDR-STORE-12: the armor has the markers of the format and base64 lines of 70 characters")
        void armor() {
            String armored = sign(TestKeys.ed25519(), SshSignatures.NAMESPACE_GIT, message());

            List<String> lines = armored.lines().toList();
            List<String> body = lines.subList(1, lines.size() - 1);
            assertAll(() -> assertEquals("-----BEGIN SSH SIGNATURE-----", lines.getFirst(), "the first line"),
                    () -> assertEquals("-----END SSH SIGNATURE-----", lines.getLast(), "the last line"),
                    () -> assertTrue(armored.endsWith("\n"), "a final line break"),
                    () -> assertTrue(body.subList(0, body.size() - 1).stream().allMatch(line -> line.length() == 70),
                            "every body line but the last has 70 characters"),
                    () -> assertTrue(body.getLast().length() <= 70, "the last body line has at most 70 characters"),
                    () -> assertArrayEquals("SSHSIG".getBytes(US_ASCII),
                            Arrays.copyOf(Base64.getDecoder().decode(String.join("", body)), 6),
                            "the blob starts with the magic preamble"));
        }

        @Test
        @DisplayName("GDR-STORE-12: the signed blob is the preamble, the namespace, the reserved field, sha512 and the digest")
        void signedData() throws Exception {
            byte[] message = message();

            byte[] blob = SshSignatures.signedData(SshSignatures.NAMESPACE_GIT, message);

            byte[] digest = MessageDigest.getInstance("SHA-512").digest(message);
            var expected = new ByteArrayOutputStream();
            expected.writeBytes("SSHSIG".getBytes(US_ASCII));
            for (byte[] field : List.of("git".getBytes(US_ASCII), new byte[0], "sha512".getBytes(US_ASCII), digest)) {
                expected.writeBytes(ByteBuffer.allocate(4).putInt(field.length).array());
                expected.writeBytes(field);
            }
            assertArrayEquals(expected.toByteArray(), blob, "the blob of PROTOCOL.sshsig");
        }

        @Test
        @DisplayName("GDR-STORE-12: decoding tolerates the whitespace a commit header adds to each line")
        void indentedLines() {
            var key = TestKeys.ed25519();
            byte[] message = message();
            String indented = sign(key, SshSignatures.NAMESPACE_GIT, message).lines().map(line -> " " + line + " ")
                    .reduce("", (left, right) -> left + right + "\n");

            assertTrue(SshSignatures.verify(decoded(indented), SshSignatures.NAMESPACE_GIT, message),
                    "the indented signature verifies");
        }
    }

    @Nested
    @DisplayName("Refusal")
    class Refusal {

        @Test
        @DisplayName("GDR-STORE-12: a signature does not verify over a tampered message")
        void tamperedMessage() {
            var key = TestKeys.ed25519();
            byte[] message = message();
            var decoded = decoded(sign(key, SshSignatures.NAMESPACE_GIT, message));
            byte[] tampered = message.clone();
            tampered[0] ^= 1;

            assertFalse(SshSignatures.verify(decoded, SshSignatures.NAMESPACE_GIT, tampered),
                    "a changed message is refused");
        }

        @Test
        @DisplayName("GDR-STORE-12: a signature does not verify in another namespace")
        void wrongNamespace() {
            var key = TestKeys.ed25519();
            byte[] message = message();

            assertAll(() -> assertFalse(SshSignatures.verify(decoded(sign(key, SshSignatures.NAMESPACE_GIT, message)),
                            "file", message), "a commit signature checked as a file signature"),
                    () -> assertFalse(SshSignatures.verify(decoded(sign(key, "file", message)),
                            SshSignatures.NAMESPACE_GIT, message), "a file signature checked as a commit signature"));
        }

        @Test
        @DisplayName("GDR-STORE-12: a signature embedding another key than the signing one does not verify")
        void foreignKey() {
            var key = TestKeys.ed25519();
            byte[] message = message();
            String armored = SshSignatures.encode(TestKeys.ed25519().publicKeyBlob(), SshSignatures.NAMESPACE_GIT,
                    key.sign(SshSignatures.signedData(SshSignatures.NAMESPACE_GIT, message)));

            assertFalse(SshSignatures.verify(decoded(armored), SshSignatures.NAMESPACE_GIT, message),
                    "the foreign key does not verify the signature");
        }

        static Stream<String> malformedArmor() {
            String valid = sign(TestKeys.ed25519(), SshSignatures.NAMESPACE_GIT, message());
            List<String> lines = valid.lines().toList();
            String truncatedBody = SshSignatures.BEGIN + "\n" + lines.get(1).substring(0, 40) + "\n"
                    + SshSignatures.END + "\n";
            String randomBlob = SshSignatures.BEGIN + "\n"
                    + Base64.getEncoder().encodeToString(Generators.letterStrings(10, 80).next().getBytes(UTF_8))
                    + "\n" + SshSignatures.END + "\n";
            return Stream.of("", "not a signature", valid.substring(0, valid.length() / 2),
                    SshSignatures.BEGIN + "\n!!!!\n" + SshSignatures.END, truncatedBody, randomBlob,
                    SshSignatures.BEGIN + SshSignatures.END);
        }

        static Stream<String> markerNotOnItsOwnLine() {
            String valid = sign(TestKeys.ed25519(), SshSignatures.NAMESPACE_GIT, message());
            List<String> lines = valid.lines().toList();
            String body = String.join("\n", lines.subList(1, lines.size() - 1));
            return Stream.of(SshSignatures.BEGIN + body + "\n" + SshSignatures.END + "\n",
                    SshSignatures.BEGIN + "\n" + body + SshSignatures.END + "\n",
                    SshSignatures.BEGIN + body + SshSignatures.END);
        }

        @ParameterizedTest
        @MethodSource("markerNotOnItsOwnLine")
        @DisplayName("GDR-STORE-12: a base64 body attached to the BEGIN or END marker is malformed, as OpenSSH reads it")
        void markerNotOnItsOwnLine(String armored) {
            var malformed = assertInstanceOf(SshSignatures.Decoding.Malformed.class, SshSignatures.decode(armored),
                    "the armor is refused");
            assertEquals("armor missing", malformed.reason(), "the reason");
        }

        @ParameterizedTest
        @MethodSource("malformedArmor")
        @DisplayName("GDR-STORE-12: a truncated or garbage armor is reported as malformed, never thrown")
        void malformed(String armored) {
            assertInstanceOf(SshSignatures.Decoding.Malformed.class, SshSignatures.decode(armored),
                    "the input is malformed");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ssh-rsa", "ecdsa-sha2-nistp256", "ssh-dss", "sk-ssh-ed25519@openssh.com"})
        @DisplayName("GDR-STORE-12: a public key line of another key type is refused")
        void otherKeyType(String type) {
            String line = type + " " + Base64.getEncoder().encodeToString(message()) + " comment";

            var refused = assertInstanceOf(SshSignatures.KeyLine.Refused.class,
                    SshSignatures.parsePublicKeyLine(line), "the line is refused");
            assertEquals("key type not supported", refused.reason(), "the reason");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "ssh-ed25519", "ssh-ed25519 !!!!", "ssh-ed25519 AAAA"})
        @DisplayName("GDR-STORE-12: a malformed Ed25519 public key line is refused, its reason naming no part of it")
        void malformedKeyLine(String line) {
            var refused = assertInstanceOf(SshSignatures.KeyLine.Refused.class,
                    SshSignatures.parsePublicKeyLine(line), "the line is refused");
            assertEquals("key malformed", refused.reason(), "the reason");
        }

        @Test
        @DisplayName("GDR-STORE-12: a public key line of type ssh-ed25519 yields its 32 key bytes")
        void ed25519KeyLine() {
            var key = TestKeys.ed25519();

            var accepted = assertInstanceOf(SshSignatures.AcceptedKey.class,
                    SshSignatures.parsePublicKeyLine(key.publicKeyLine()), "the line is accepted");
            assertAll(() -> assertArrayEquals(key.publicKey(), accepted.key(), "the key"),
                    () -> assertArrayEquals(key.publicKeyBlob(), accepted.blob(), "the blob"),
                    () -> assertFalse(accepted.toString().contains(Base64.getEncoder().encodeToString(key.publicKey())
                            .substring(0, 16)), "the rendering carries no key byte"));
        }
    }

    @Nested
    @DisplayName("Values")
    class Values {

        @Test
        @DisplayName("GDR-STORE-12: an accepted key copies its bytes in and out and compares them by content")
        void acceptedKey() {
            byte[] bytes = TestKeys.ed25519().publicKey();
            var accepted = new SshSignatures.AcceptedKey(bytes);
            byte[] original = bytes.clone();

            bytes[0] ^= 1;
            accepted.key()[1] ^= 1;

            var same = new SshSignatures.AcceptedKey(original);
            assertAll(() -> assertArrayEquals(original, accepted.key(), "neither the input nor an output changes it"),
                    () -> assertEquals(same, accepted, "equal bytes, equal keys"),
                    () -> assertEquals(same.hashCode(), accepted.hashCode(), "equal bytes, equal hashes"),
                    () -> assertNotEquals(new SshSignatures.AcceptedKey(bytes), accepted, "other bytes, other key"),
                    () -> assertEquals("AcceptedKey[ssh-ed25519]", accepted.toString(), "the rendering"));
        }

        @Test
        @DisplayName("GDR-STORE-12: a decoded signature copies its byte arrays in and out and compares them by content")
        void decodedSignature() {
            var key = TestKeys.ed25519();
            String armored = sign(key, SshSignatures.NAMESPACE_GIT, message());
            var decoded = decoded(armored);

            decoded.publicKeyBlob()[0] ^= 1;
            decoded.signature()[0] ^= 1;
            byte[] blob = decoded.publicKeyBlob();
            byte[] signature = decoded.signature();
            var built = new SshSignatures.DecodedSignature(blob, SshSignatures.NAMESPACE_GIT,
                    SshSignatures.HASH_SHA512, SshSignatures.KEY_TYPE, signature);
            blob[0] ^= 1;
            signature[0] ^= 1;
            byte[] otherSignature = decoded.signature();
            otherSignature[0] ^= 1;
            var other = new SshSignatures.DecodedSignature(decoded.publicKeyBlob(), SshSignatures.NAMESPACE_GIT,
                    SshSignatures.HASH_SHA512, SshSignatures.KEY_TYPE, otherSignature);

            assertAll(() -> assertArrayEquals(key.publicKeyBlob(), decoded.publicKeyBlob(), "an output changes nothing"),
                    () -> assertEquals(decoded(armored), decoded, "the same armor, an equal signature"),
                    () -> assertEquals(decoded(armored).hashCode(), decoded.hashCode(), "an equal hash"),
                    () -> assertEquals(decoded, built, "the input arrays changed after construction change nothing"),
                    () -> assertNotEquals(decoded, other, "other signature bytes, another signature"),
                    () -> assertEquals("DecodedSignature[namespace=git, hash=sha512, type=ssh-ed25519]",
                            decoded.toString(), "the rendering names no byte"));
        }
    }

    @Nested
    @DisplayName("Stock ssh-keygen")
    class StockTool {

        @Test
        @DisplayName("GDR-STORE-12: ssh-keygen -Y verify -n git accepts an encoded signature (skipped without -Y verify)")
        void sshKeygenAccepts(@TempDir Path directory) throws Exception {
            Optional<Path> keygen = ExternalTools.onPath("ssh-keygen");
            assumeTrue(keygen.isPresent(), "ssh-keygen is not on the path");
            assumeTrue(ExternalTools.verifiesSignatures(keygen.get(), directory),
                    "the ssh-keygen on the path signs and verifies SSH signatures (-Y sign, -Y verify)");
            var key = TestKeys.ed25519();
            byte[] message = message();
            Path allowed = Files.writeString(directory.resolve("allowed_signers"),
                    "signer@example.invalid namespaces=\"git\" " + key.publicKeyLine() + "\n", US_ASCII);
            Path signature = Files.writeString(directory.resolve("message.sig"),
                    sign(key, SshSignatures.NAMESPACE_GIT, message), US_ASCII);
            Path messageFile = Files.write(directory.resolve("message"), message);

            var verified = ExternalTools.run(new ProcessBuilder(keygen.get().toString(), "-Y", "verify", "-f",
                            allowed.toString(), "-I", "signer@example.invalid", "-n", "git", "-s", signature.toString()),
                    directory.resolve("output"), messageFile);

            assertEquals(0, verified.code(), () -> "ssh-keygen accepted the signature: " + verified.output());
        }
    }
}
