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
package de.cuioss.git.document.timestamp;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GDR-TSA-2: the provider {@code rfc3161} verifies a stored token against the object id and {@code timestamp.trust},
 * reporting the time the token proves, an imprint mismatch, or an invalid signature, also for a token that cannot be
 * read. The tokens come from the in-process test authority, over TLS on the loopback address, without network access.
 */
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("GDR-TSA-2: verifying a timestamp token")
class Rfc3161VerifyTest {

    private static final String REPOSITORY = "documents";

    private static TestCertificates.Issued ca;
    private static TestCertificates.Issued signer;
    private static TestTimestampAuthority authority;
    private static HttpClient client;

    @TempDir
    static Path directory;

    @BeforeAll
    static void startAuthority() throws IOException {
        ca = TestCertificates.ca("test ca");
        var tls = TestCertificates.tls(ca);
        signer = TestCertificates.signer(ca, "test tsa", true);
        authority = new TestTimestampAuthority(tls, ca, signer);
        client = TimestampTransport.clientBuilder().sslContext(TestCertificates.clientContext(ca)).build();
    }

    @AfterAll
    static void stopAuthority() {
        authority.close();
        client.close();
    }

    @BeforeEach
    void resetAuthority() {
        authority.reset();
    }

    private static Rfc3161AnchorProvider provider(X509Certificate trusted) throws IOException {
        Path trust = Files.writeString(directory.resolve(Generators.letterStrings(12, 20).next() + ".pem"),
                TestCertificates.pem(trusted), US_ASCII);
        Map<String, Object> section = new HashMap<>();
        section.put("url", authority.url().toString());
        section.put("trust", List.of(trust.toString()));
        StoreResult<Rfc3161AnchorProvider> built = Rfc3161AnchorProvider.from(Map.of("timestamp", section), client);
        assertTrue(built.isOk(), () -> "the provider is built: " + built.detail());
        return built.value();
    }

    private static Rfc3161AnchorProvider provider() throws IOException {
        return provider(ca.certificate());
    }

    private static ObjectId subject() {
        return ObjectId.fromRaw(TimeStampRequests.sha256(Generators.letterStrings(8, 16).next().getBytes(US_ASCII)));
    }

    private static byte[] obtain(Rfc3161AnchorProvider provider, ObjectId subject) {
        var obtained = provider.obtain(REPOSITORY, subject, Instant.now(), new byte[0]);
        assertTrue(obtained.isOk(), () -> "obtained: " + obtained.detail());
        return obtained.value();
    }

    private static void assertRejected(StoreOutcome outcome, String detail, StoreResult<Instant> result) {
        assertAll(
                () -> assertEquals(outcome, result.outcome(), "outcome"),
                () -> assertEquals(detail, result.detail(), "detail"),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                        GitTimestampLogMessages.WARN.PROOF_REJECTED.resolveIdentifierString()));
    }

    @Test
    @DisplayName("GDR-TSA-2: a token of the authority verifies with the time it states")
    void shouldVerifyToken() throws Exception {
        var provider = provider();
        var subject = subject();
        byte[] token = obtain(provider, subject);

        var result = provider.verify(subject, token, new byte[0]);

        assertTrue(result.isOk(), () -> "verified: " + result.detail());
        assertEquals(TimeStampRequests.tstInfo(token).genTime(), result.value(), "the time the token states");
    }

    @Test
    @DisplayName("GDR-TSA-2: the previous proof is ignored, rfc3161 forms no chain")
    void shouldIgnorePreviousProof() throws Exception {
        var provider = provider();
        var subject = subject();
        byte[] token = obtain(provider, subject);
        byte[] unrelated = Generators.letterStrings(40, 80).next().getBytes(US_ASCII);

        var result = provider.verify(subject, token, unrelated);

        assertTrue(result.isOk(), () -> "verified with any previous proof: " + result.outcome());
    }

    @Test
    @DisplayName("GDR-TSA-2: a token for another object id is IMPRINT_MISMATCH")
    void shouldReportImprintMismatch() throws Exception {
        var provider = provider();
        byte[] token = obtain(provider, subject());

        var result = provider.verify(subject(), token, new byte[0]);

        assertRejected(StoreOutcome.IMPRINT_MISMATCH, Rfc3161AnchorProvider.IMPRINT_MISMATCH, result);
    }

    @Test
    @DisplayName("GDR-TSA-2: a token with a flipped signature byte is SIGNATURE_INVALID")
    void shouldRejectFlippedSignature() throws Exception {
        var provider = provider();
        var subject = subject();
        byte[] token = obtain(provider, subject);
        // the signature OCTET STRING closes the SignerInfo, the SignedData and the ContentInfo, so the last octet of
        // the token is the last octet of the signature value
        token[token.length - 1] ^= 0x01;

        var result = provider.verify(subject, token, new byte[0]);

        assertRejected(StoreOutcome.SIGNATURE_INVALID, TimeStampTokens.SIGNATURE_MISMATCH, result);
    }

    private static byte[] tstInfo(ObjectId subject) {
        return Der.sequence(Der.integer(1), Der.oid(TestTimestampAuthority.POLICY),
                TimeStampRequests.messageImprint(TimeStampRequests.imprint(subject)), Der.integer(1),
                Der.generalizedTime(Instant.now()));
    }

    @Test
    @DisplayName("GDR-TSA-2: a token signed by a certificate outside timestamp.trust is SIGNATURE_INVALID")
    void shouldRejectUntrustedSigner() throws Exception {
        var subject = subject();
        var untrusted = TestCertificates.signer(TestCertificates.ca("untrusted ca"), "untrusted tsa", true);
        byte[] token = TestTimestampAuthority.token(untrusted, tstInfo(subject), true);

        var result = provider().verify(subject, token, new byte[0]);

        assertRejected(StoreOutcome.SIGNATURE_INVALID, TimeStampTokens.UNTRUSTED, result);
    }

    @Test
    @DisplayName("GDR-TSA-2: a signer certificate without id-kp-timeStamping is SIGNATURE_INVALID (RFC 3161, 2.3)")
    void shouldRejectSignerWithoutTimeStamping() throws Exception {
        var subject = subject();
        var withoutPurpose = TestCertificates.signer(ca, "tsa without time stamping", false);
        byte[] token = TestTimestampAuthority.token(withoutPurpose, tstInfo(subject), true);

        var result = provider().verify(subject, token, new byte[0]);

        assertRejected(StoreOutcome.SIGNATURE_INVALID, TimeStampTokens.NOT_TIMESTAMPING, result);
    }

    @Test
    @DisplayName("GDR-TSA-2: a signer whose key usage is keyEncipherment only is SIGNATURE_INVALID (RFC 5280, 4.2.1.12)")
    void shouldRejectInconsistentKeyUsage() throws Exception {
        var subject = subject();
        var encipherer = TestCertificates.signer(ca, "tsa enciphering", true, TestCertificates.KEY_ENCIPHERMENT_ONLY);
        byte[] token = TestTimestampAuthority.token(encipherer, tstInfo(subject), true);

        var throughPath = provider().verify(subject, token, new byte[0]);
        var trustedDirectly = provider(encipherer.certificate()).verify(subject, token, new byte[0]);

        assertAll(
                () -> assertEquals(StoreOutcome.SIGNATURE_INVALID, throughPath.outcome(), "path branch"),
                () -> assertEquals(TimeStampTokens.NOT_TIMESTAMPING, throughPath.detail(), "detail of the path branch"),
                () -> assertEquals(StoreOutcome.SIGNATURE_INVALID, trustedDirectly.outcome(), "direct trust"),
                () -> assertEquals(TimeStampTokens.NOT_TIMESTAMPING, trustedDirectly.detail(),
                        "detail of the direct trust"));
    }

    @Test
    @DisplayName("GDR-TSA-2: the path is built to the exact signer past an intermediate of the same subject name")
    void shouldBuildPathPastSameNameIntermediate() throws Exception {
        var subject = subject();
        var decoy = TestCertificates.intermediate(ca, "intermediate");
        var issuing = TestCertificates.intermediate(ca, "intermediate");
        var below = TestCertificates.signer(issuing, "tsa below intermediate", true);
        byte[] token = TestTimestampAuthority.token(below, tstInfo(subject),
                List.of(TestTimestampAuthority.signingCertificateV2(below)),
                List.of(TestTimestampAuthority.certificatesField(decoy, issuing, below)));

        var result = provider().verify(subject, token, new byte[0]);

        assertTrue(result.isOk(), () -> "verified: " + result.detail());
    }

    @Test
    @DisplayName("GDR-TSA-2: two certificates fields or crls before certificates are an unreadable token")
    void shouldRefuseMisplacedSignedDataFields() throws Exception {
        var subject = subject();
        byte[] certificates = TestTimestampAuthority.certificatesField(signer);
        byte[] crls = Der.tlv(Der.contextConstructed(1), new byte[0]);
        List<byte[]> ess = List.of(TestTimestampAuthority.signingCertificateV2(signer));
        byte[] twice = TestTimestampAuthority.token(signer, tstInfo(subject), ess, List.of(certificates, certificates));
        byte[] outOfOrder = TestTimestampAuthority.token(signer, tstInfo(subject), ess, List.of(crls, certificates));
        byte[] inOrder = TestTimestampAuthority.token(signer, tstInfo(subject), ess, List.of(certificates, crls));
        var provider = provider();

        var twiceResult = provider.verify(subject, twice, new byte[0]);
        var outOfOrderResult = provider.verify(subject, outOfOrder, new byte[0]);
        var inOrderResult = provider.verify(subject, inOrder, new byte[0]);

        assertAll(
                () -> assertEquals(TimeStampTokens.UNREADABLE, twiceResult.detail(), "two certificates fields"),
                () -> assertEquals(TimeStampTokens.UNREADABLE, outOfOrderResult.detail(), "crls before certificates"),
                () -> assertTrue(inOrderResult.isOk(), () -> "certificates then crls: " + inOrderResult.detail()));
    }

    @Test
    @DisplayName("GDR-TSA-2: signingCertificate and signingCertificateV2 together are evaluated independently")
    void shouldEvaluateBothSigningCertificateAttributes() throws Exception {
        var subject = subject();
        var other = TestCertificates.signer(ca, "another tsa", true);
        List<byte[]> middle = List.of(TestTimestampAuthority.certificatesField(signer));
        byte[] both = TestTimestampAuthority.token(signer, tstInfo(subject),
                List.of(TestTimestampAuthority.signingCertificate(signer),
                        TestTimestampAuthority.signingCertificateV2(signer)), middle);
        byte[] v1Only = TestTimestampAuthority.token(signer, tstInfo(subject),
                List.of(TestTimestampAuthority.signingCertificate(signer)), middle);
        byte[] disagreeing = TestTimestampAuthority.token(signer, tstInfo(subject),
                List.of(TestTimestampAuthority.signingCertificate(signer),
                        TestTimestampAuthority.signingCertificateV2(other)), middle);
        byte[] twoV2 = TestTimestampAuthority.token(signer, tstInfo(subject),
                List.of(TestTimestampAuthority.signingCertificateV2(signer),
                        TestTimestampAuthority.signingCertificateV2(signer)), middle);
        var provider = provider();

        var bothResult = provider.verify(subject, both, new byte[0]);
        var v1Result = provider.verify(subject, v1Only, new byte[0]);
        var disagreeingResult = provider.verify(subject, disagreeing, new byte[0]);
        var twoV2Result = provider.verify(subject, twoV2, new byte[0]);

        assertAll(
                () -> assertTrue(bothResult.isOk(), () -> "both: " + bothResult.detail()),
                () -> assertTrue(v1Result.isOk(), () -> "signingCertificate only: " + v1Result.detail()),
                () -> assertEquals(TimeStampTokens.SIGNER_UNKNOWN, disagreeingResult.detail(),
                        "the two attributes name different certificates"),
                () -> assertEquals(TimeStampTokens.ATTRIBUTES_MISMATCH, twoV2Result.detail(),
                        "signingCertificateV2 twice"));
    }

    @Test
    @DisplayName("GDR-TSA-2: a token without its signer certificate verifies when timestamp.trust holds the signer")
    void shouldFindSignerInTrust() throws Exception {
        var subject = subject();
        byte[] tstInfo = tstInfo(subject);
        byte[] token = authority.token(tstInfo, false);

        var trustingCa = provider().verify(subject, token, new byte[0]);
        var trustingSigner = provider(signer.certificate()).verify(subject, token, new byte[0]);

        assertAll(
                () -> assertEquals(StoreOutcome.SIGNATURE_INVALID, trustingCa.outcome(), "unknown signer"),
                () -> assertEquals(TimeStampTokens.SIGNER_UNKNOWN, trustingCa.detail(), "detail of the unknown signer"),
                () -> assertTrue(trustingSigner.isOk(), () -> "verified: " + trustingSigner.detail()));
    }

    @Test
    @DisplayName("GDR-TSA-2: a truncated token, random octets and no octets are SIGNATURE_INVALID")
    void shouldRejectUnreadableToken() throws Exception {
        var provider = provider();
        var subject = subject();
        byte[] token = obtain(provider, subject);
        byte[] random = new byte[Generators.integers(16, 256).next()];
        new SecureRandom().nextBytes(random);
        List<byte[]> unreadable = List.of(Arrays.copyOf(token, token.length - 7), random, new byte[0]);

        for (byte[] proof : unreadable) {
            var result = provider.verify(subject, proof, new byte[0]);

            assertEquals(StoreOutcome.SIGNATURE_INVALID, result.outcome(), "outcome of an unreadable token");
            assertEquals(TimeStampTokens.UNREADABLE, result.detail(), "detail of an unreadable token");
        }
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                GitTimestampLogMessages.WARN.PROOF_REJECTED.resolveIdentifierString());
    }

    @Test
    @DisplayName("GDR-TSA-2: the log record of a rejected proof names the object id and the outcome")
    void shouldLogRejectedProof() throws Exception {
        var provider = provider();
        var subject = subject();
        byte[] token = obtain(provider, subject());

        provider.verify(subject, token, new byte[0]);

        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                GitTimestampLogMessages.WARN.PROOF_REJECTED.resolveIdentifierString() + ": Rejected the timestamp proof"
                        + " of object " + subject.name() + ": " + StoreOutcome.IMPRINT_MISMATCH);
    }
}
