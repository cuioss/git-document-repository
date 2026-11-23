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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.LogRecord;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The signing key source {@code file} of GDR-STORE-12, {@link FileSigningKeySource}, over the {@code openssh-key-v1}
 * files {@link TestKeys} writes per run: a signature of an Ed25519 key verifies and its public key line is the
 * generated one; an {@code ssh-rsa} file, an encrypted file, a malformed file and a missing file give the documented
 * outcome and detail; and GDR-NFR-3: neither a result detail nor a log record carries the key path or a fragment of
 * the key.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@DisplayName("FileSigningKeySource (GDR-STORE-12)")
class FileSigningKeySourceTest {

    private final FileSigningKeySource source = new FileSigningKeySource();

    @TempDir
    Path directory;

    private Path keyFile() {
        return directory.resolve("key-" + Generators.letterStrings(8, 16).next());
    }

    private static byte[] message() {
        return Generators.letterStrings(1, 200).next().getBytes(UTF_8);
    }

    /** The line of a public key without its comment. */
    private static String withoutComment(String line) {
        return line.substring(0, line.lastIndexOf(' '));
    }

    @Nested
    @DisplayName("Ed25519 key")
    class Ed25519Key {

        @Test
        @DisplayName("GDR-STORE-12: the signature of a per-run Ed25519 key verifies in the SSH signature format")
        void signatureVerifies() throws Exception {
            var key = TestKeys.ed25519();
            Path file = keyFile();
            key.writePrivateKey(file);
            byte[] message = message();

            var signed = source.sign(file.toString(),
                    SshSignatures.signedData(SshSignatures.NAMESPACE_GIT, message));

            assertTrue(signed.isOk(), () -> "signed: " + signed.detail());
            String armored = SshSignatures.encode(key.publicKeyBlob(), SshSignatures.NAMESPACE_GIT,
                    Objects.requireNonNull(signed.value()));
            var decoded = assertInstanceOf(SshSignatures.DecodedSignature.class, SshSignatures.decode(armored),
                    "the signature decodes");
            assertTrue(SshSignatures.verify(decoded, SshSignatures.NAMESPACE_GIT, message), "the signature verifies");
        }

        @Test
        @DisplayName("GDR-STORE-12: the public key line is the one of the generated key")
        void publicKeyLine() throws Exception {
            var key = TestKeys.ed25519();
            Path file = keyFile();
            key.writePrivateKey(file);

            var line = source.publicKey(file.toString());

            assertEquals(StoreResult.ok(withoutComment(key.publicKeyLine())), line, "the OpenSSH public key line");
        }

        @Test
        @DisplayName("GDR-STORE-12: the file is read on every call, so a replaced key signs from then on")
        void readOnEveryCall() throws Exception {
            Path file = keyFile();
            TestKeys.ed25519().writePrivateKey(file);
            var first = source.publicKey(file.toString());
            var replacement = TestKeys.ed25519();
            replacement.writePrivateKey(file);

            var second = source.publicKey(file.toString());
            byte[] message = message();
            var signed = source.sign(file.toString(), SshSignatures.signedData(SshSignatures.NAMESPACE_GIT, message));

            assertTrue(signed.isOk(), () -> "signed: " + signed.detail());
            var decoded = assertInstanceOf(SshSignatures.DecodedSignature.class,
                    SshSignatures.decode(SshSignatures.encode(replacement.publicKeyBlob(), SshSignatures.NAMESPACE_GIT,
                            Objects.requireNonNull(signed.value()))), "the signature decodes");
            assertAll(() -> assertTrue(first.isOk(), "the first key is read"),
                    () -> assertEquals(StoreResult.ok(withoutComment(replacement.publicKeyLine())), second,
                            "the replacement key is read"),
                    () -> assertTrue(SshSignatures.verify(decoded, SshSignatures.NAMESPACE_GIT, message),
                            "the replacement key made the signature"));
        }

        @Test
        @DisplayName("GDR-STORE-12: the id is file, and the rendering names nothing else")
        void identity() {
            assertAll(() -> assertEquals("file", source.id(), "the id signing.key-source names"),
                    () -> assertEquals("FileSigningKeySource[file]", source.toString(), "the rendering"));
        }

        @Test
        @DisplayName("GDR-STORE-12: ssh-keygen derives the same public key from the test file (skipped without it)")
        void sshKeygenReadsTheFile() throws Exception {
            Optional<Path> keygen = ExternalTools.onPath("ssh-keygen");
            assumeTrue(keygen.isPresent(), "ssh-keygen is not on the path");
            assumeTrue(ExternalTools.posix(directory), "the temporary file system has no POSIX permissions");
            var key = TestKeys.ed25519();
            Path file = keyFile();
            key.writePrivateKey(file);
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));

            var finished = ExternalTools.run(new ProcessBuilder(keygen.get().toString(), "-y", "-f", file.toString()),
                    directory.resolve("ssh-keygen.out"), null);
            String[] fields = finished.output().strip().split("\\s+", 3);

            assertAll(() -> assertEquals(0, finished.code(), () -> "ssh-keygen read the file: " + finished.output()),
                    () -> assertEquals(withoutComment(key.publicKeyLine()), fields[0] + " " + fields[1],
                            "ssh-keygen reads the written file"));
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        private void assertBoth(StoreOutcome outcome, String detail, Path file) {
            var signed = source.sign(file.toString(), message());
            var publicKey = source.publicKey(file.toString());
            assertAll(() -> assertEquals(StoreResult.of(outcome, detail), signed, "the signing"),
                    () -> assertEquals(StoreResult.of(outcome, detail), publicKey, "the public key"));
        }

        @Test
        @DisplayName("GDR-STORE-12: an ssh-rsa key is REFUSED as key type not supported")
        void rsaKey() throws Exception {
            Path file = keyFile();
            TestKeys.writeRsa(file);

            assertBoth(StoreOutcome.REFUSED, "signing: key type not supported", file);
        }

        @Test
        @DisplayName("GDR-STORE-12: a PEM key of another format is REFUSED as key type not supported")
        void pemKey() throws Exception {
            Path file = Files.writeString(keyFile(), "-----BEGIN RSA PRIVATE KEY-----\n"
                    + Base64.getEncoder().encodeToString(message()) + "\n-----END RSA PRIVATE KEY-----\n", US_ASCII);

            assertBoth(StoreOutcome.REFUSED, "signing: key type not supported", file);
        }

        @Test
        @DisplayName("GDR-STORE-12: an encrypted Ed25519 key is REFUSED as encrypted key not supported")
        void encryptedKey() throws Exception {
            Path file = keyFile();
            TestKeys.writeEncrypted(file);

            assertBoth(StoreOutcome.REFUSED, "signing: encrypted key not supported", file);
        }

        @Test
        @DisplayName("GDR-STORE-12: a file that is no openssh-key-v1 key is REFUSED as key malformed")
        void malformedFiles() throws Exception {
            Path valid = keyFile();
            TestKeys.ed25519().writePrivateKey(valid);
            String text = Files.readString(valid, US_ASCII);
            List<String> lines = text.lines().toList();
            List<String> malformed = new ArrayList<>(List.of(
                    Generators.letterStrings(1, 200).next(),
                    TestKeys.PRIVATE_BEGIN + "\n" + Base64.getEncoder().encodeToString(message()) + "\n"
                            + TestKeys.PRIVATE_END + "\n",
                    TestKeys.PRIVATE_BEGIN + "\n" + lines.get(1) + "\n" + TestKeys.PRIVATE_END + "\n",
                    text.substring(0, text.length() / 2)));

            for (String content : malformed) {
                Path file = Files.writeString(keyFile(), content, US_ASCII);
                assertBoth(StoreOutcome.REFUSED, "signing: key malformed", file);
            }
        }

        @Test
        @DisplayName("GDR-STORE-12: a missing file and a directory are UNAVAILABLE as key unavailable")
        void unreadable() {
            assertAll(() -> assertBoth(StoreOutcome.UNAVAILABLE, "signing: key unavailable", keyFile()),
                    () -> assertBoth(StoreOutcome.UNAVAILABLE, "signing: key unavailable", directory));
        }
    }

    @Test
    @DisplayName("GDR-NFR-3: no detail and no log record carries the key path or a fragment of the key")
    void noPathOrKeyMaterial() throws Exception {
        var key = TestKeys.ed25519();
        Path valid = keyFile();
        key.writePrivateKey(valid);
        Path rsa = keyFile();
        TestKeys.writeRsa(rsa);
        Path encrypted = keyFile();
        TestKeys.writeEncrypted(encrypted);
        Path missing = keyFile();
        String keyBase64 = key.publicKeyLine().split(" ")[1];
        String fragment = keyBase64.substring(keyBase64.length() - 24);
        String privateFragment = Files.readString(valid, US_ASCII).lines().toList().get(2);

        List<StoreResult<?>> results = new ArrayList<>();
        for (Path file : List.of(valid, rsa, encrypted, missing, directory)) {
            results.add(source.sign(file.toString(), message()));
            results.add(source.publicKey(file.toString()));
        }

        List<LogRecord> records = TestLoggerFactory.getTestHandler().getRecords();
        for (Path file : List.of(valid, rsa, encrypted, missing, directory)) {
            String path = file.toString();
            assertAll(() -> assertTrue(results.stream().map(StoreResult::detail).filter(Objects::nonNull)
                            .noneMatch(detail -> detail.contains(path) || detail.contains(fragment)
                                    || detail.contains(privateFragment)), "no detail carries the path or the key"),
                    () -> assertFalse(records.stream().anyMatch(logRecord -> {
                        String message = String.valueOf(logRecord.getMessage());
                        return message.contains(path) || message.contains(fragment)
                                || message.contains(privateFragment) || logRecord.getThrown() != null;
                    }), "no log record carries the path, the key or a throwable"));
        }
    }
}
