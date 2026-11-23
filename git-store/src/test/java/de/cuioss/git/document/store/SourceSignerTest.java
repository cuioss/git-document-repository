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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


import de.cuioss.git.document.core.CommitMetadata;
import de.cuioss.git.document.core.CommitSigning;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The JGit signer of GDR-STORE-12, {@link SourceSigner} (checked against JGit 7.8.0), through {@link InCoreWriter} of
 * {@code git-core} on the in-memory fixture of {@code git-testing}: the written commit carries a {@code gpgsig} header
 * that {@link SshSignatures#verify} accepts for the commit without that header; a refusing and an unavailable source end
 * the write as {@link GitOutcome#FAILED} with {@link SourceSigner#failure()} naming {@link StoreOutcome#REFUSED} resp.
 * {@link StoreOutcome#UNAVAILABLE}; and neither the exception, the result detail nor the rendering carries the key
 * reference or the source's detail (GDR-NFR-3).
 */
@EnableGeneratorController
@DisplayName("SourceSigner (GDR-STORE-12)")
class SourceSignerTest {

    /** The start of the header a signed commit carries its signature in: part of git's commit format. */
    private static final byte[] GPGSIG_HEADER = "gpgsig ".getBytes(US_ASCII);

    private final InCoreWriter writer = new InCoreWriter();

    private static String keyReference() {
        return "/keys/" + Generators.letterStrings(8, 16).next();
    }

    private static PersonIdent person() {
        return new PersonIdent("store", "", Instant.ofEpochSecond(Generators.integers(0, Integer.MAX_VALUE).next()),
                ZoneOffset.UTC);
    }

    private GitResult<String> write(Repository repository, SourceSigner signer) {
        return writer.write(repository, List.of(), new CommitMetadata(person(), person(), "subject\n",
                        new CommitSigning(signer, null, null)),
                List.of(new TreeEdit.Put("file.txt", Generators.letterStrings(1, 40).next().getBytes(UTF_8))));
    }

    private static RevCommit parse(Repository repository, String id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(ObjectId.fromString(id));
        }
    }

    /**
     * Reconstructs the bytes a commit's signature covers: the raw commit with its {@code gpgsig} header, continuation
     * lines included, removed. The header section ends at the first empty line.
     *
     * @param commit the parsed commit
     * @return the signed payload
     */
    static byte[] signedPayload(RevCommit commit) {
        byte[] raw = commit.getRawBuffer();
        var payload = new ByteArrayOutputStream(raw.length);
        boolean inSignature = false;
        int line = 0;
        while (line < raw.length) {
            if (raw[line] == '\n') {
                payload.write(raw, line, raw.length - line);
                break;
            }
            int next = line;
            while (next < raw.length && raw[next] != '\n') {
                next++;
            }
            next = Math.min(raw.length, next + 1);
            if (raw.length - line >= GPGSIG_HEADER.length
                    && Arrays.equals(raw, line, line + GPGSIG_HEADER.length, GPGSIG_HEADER, 0, GPGSIG_HEADER.length)) {
                inSignature = true;
            } else if (raw[line] != ' ') {
                inSignature = false;
            }
            if (!inSignature) {
                payload.write(raw, line, next - line);
            }
            line = next;
        }
        return payload.toByteArray();
    }

    @Test
    @DisplayName("GDR-STORE-12: the written commit carries an SSH signature that verifies under namespace git")
    void signedCommit(@InMemory Repository repository) throws Exception {
        var source = TestKeys.ed25519().source();
        String keyReference = keyReference();
        var signer = new SourceSigner(source, keyReference, source.key().publicKeyLine());

        var result = write(repository, signer);

        assertTrue(result.isOk(), () -> "written: " + result.detail());
        RevCommit commit = parse(repository, Objects.requireNonNull(result.value()));
        byte[] raw = commit.getRawGpgSignature();
        assertNotNull(raw, "the commit carries a gpgsig header");
        String armored = new String(raw, US_ASCII);
        var decoded = assertInstanceOf(SshSignatures.DecodedSignature.class, SshSignatures.decode(armored),
                "the header decodes");
        assertAll(() -> assertTrue(armored.startsWith("-----BEGIN SSH SIGNATURE-----"), "the armor"),
                () -> assertArrayEquals(source.key().publicKeyBlob(), decoded.publicKeyBlob(),
                        "the signature embeds the key of the source"),
                () -> assertTrue(SshSignatures.verify(decoded, SshSignatures.NAMESPACE_GIT, signedPayload(commit)),
                        "the signature verifies over the commit without its header"),
                () -> assertEquals(1, source.signCalls(), "the source signed once"),
                () -> assertTrue(signer.failure().isEmpty(), "no failure was captured"),
                () -> assertTrue(signer.canLocateSigningKey(repository, new GpgConfig(repository.getConfig()),
                        person(), null, null), "the key is located"));
    }

    @ParameterizedTest
    @EnumSource(value = StoreOutcome.class, names = {"REFUSED", "UNAVAILABLE"})
    @DisplayName("GDR-STORE-12, GDR-NFR-3: a failing source ends the write as FAILED and failure() names its outcome")
    void failingSource(StoreOutcome outcome, @InMemory Repository repository) {
        String keyReference = keyReference();
        String sourceDetail = "detail-" + Generators.letterStrings(8, 16).next();
        var source = new ScriptedSource(StoreResult.of(outcome, sourceDetail), TestKeys.ed25519().publicKeyLine());
        var signer = new SourceSigner(source, keyReference, source.line);

        var result = write(repository, signer);

        var failure = signer.failure().orElseThrow();
        String detail = Objects.requireNonNull(result.detail());
        assertAll(() -> assertEquals(GitOutcome.FAILED, result.outcome(), "the write failed"),
                () -> assertEquals(outcome, failure.outcome(), "the captured outcome"),
                () -> assertEquals(sourceDetail, failure.detail(), "the captured detail of the source"),
                () -> assertFalse(detail.contains(keyReference), "the detail carries no key reference"),
                () -> assertFalse(detail.contains(sourceDetail), "the detail carries no detail of the source"));
    }

    @ParameterizedTest
    @EnumSource(value = StoreOutcome.class, names = {"REFUSED", "UNAVAILABLE"})
    @DisplayName("GDR-NFR-3: the exception of a failed signing carries neither the key reference nor the source's detail")
    void exceptionMessage(StoreOutcome outcome, @InMemory Repository repository) {
        String keyReference = keyReference();
        String sourceDetail = "detail-" + Generators.letterStrings(8, 16).next();
        var source = new ScriptedSource(StoreResult.of(outcome, sourceDetail), TestKeys.ed25519().publicKeyLine());
        var signer = new SourceSigner(source, keyReference, source.line);
        var config = new GpgConfig(repository.getConfig());

        var thrown = assertThrows(IOException.class,
                () -> signer.sign(repository, config, new byte[]{1, 2, 3}, person(), keyReference, null));

        String message = Objects.requireNonNull(thrown.getMessage());
        assertAll(() -> assertFalse(message.contains(keyReference), "no key reference"),
                () -> assertFalse(message.contains(sourceDetail), "no detail of the source"),
                () -> assertFalse(signer.toString().contains(keyReference), "the rendering names no key reference"));
    }

    @Test
    @DisplayName("GDR-STORE-12: a public key line of another type is not located and every signing is REFUSED")
    void otherKeyType(@InMemory Repository repository) {
        var source = TestKeys.ed25519().source();
        var signer = new SourceSigner(source, keyReference(), "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQ test");

        var result = write(repository, signer);

        var failure = signer.failure().orElseThrow();
        assertAll(() -> assertFalse(signer.canLocateSigningKey(repository, new GpgConfig(repository.getConfig()),
                        person(), null, null), "the key is not located"),
                () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the write failed"),
                () -> assertEquals(StoreOutcome.REFUSED, failure.outcome(), "refused"),
                () -> assertEquals("signing: key type not supported", failure.detail(), "the detail"),
                () -> assertEquals(0, source.signCalls(), "the source was not asked to sign"));
    }

    @Test
    @DisplayName("GDR-STORE-12: a signature made with another key than the public key line is REFUSED and writes nothing")
    void replacedKey(@InMemory Repository repository) throws Exception {
        var announced = TestKeys.ed25519();
        var replacement = TestKeys.ed25519();
        var source = new SigningWith(replacement, announced.publicKeyLine());
        var signer = new SourceSigner(source, keyReference(), announced.publicKeyLine());

        var result = write(repository, signer);

        var failure = signer.failure().orElseThrow();
        assertAll(() -> assertEquals(GitOutcome.FAILED, result.outcome(), "the write failed"),
                () -> assertEquals(StoreOutcome.REFUSED, failure.outcome(), "refused"),
                () -> assertEquals(SourceSigner.KEY_MISMATCH, failure.detail(), "the detail"),
                () -> assertEquals(0, repository.getRefDatabase().getRefs().size(), "no reference moved"),
                () -> assertNull(result.value(), "no commit id is returned"));
    }

    /** A signing key source that signs with one key and hands out the public key line of another. */
    private record SigningWith(TestKeys.Ed25519 key, String line) implements SigningKeySource {

        @Override
        public String id() {
            return "signing-with";
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            return StoreResult.ok(key.sign(data));
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            return StoreResult.ok(line);
        }
    }

    /** A signing key source that answers every signing with a fixed result and hands out a fixed public key line. */
    private record ScriptedSource(StoreResult<byte[]> signing, String line) implements SigningKeySource {

        @Override
        public String id() {
            return "scripted";
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            return signing;
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            return StoreResult.ok(line);
        }
    }
}
