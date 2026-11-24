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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.LogRecord;


import de.cuioss.git.document.store.AnchorProvider;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GDR-TSA-1: the provider {@code rfc3161} obtains an RFC 3161 timestamp token from the in-process test authority,
 * over TLS on the loopback address, without network access.
 */
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("GDR-TSA-1: obtaining a timestamp token")
class Rfc3161ObtainTest {

    private static final String REPOSITORY = "documents";

    private static TestCertificates.Issued ca;
    private static TestTimestampAuthority authority;
    private static HttpClient client;

    @TempDir
    static Path directory;

    private static Path trust;

    @BeforeAll
    static void startAuthority() throws IOException {
        ca = TestCertificates.ca("test ca");
        var tls = TestCertificates.tls(ca);
        var signer = TestCertificates.signer(ca, "test tsa", true);
        authority = new TestTimestampAuthority(tls, ca, signer);
        client = TimestampTransport.clientBuilder().sslContext(TestCertificates.clientContext(ca)).build();
        trust = Files.writeString(directory.resolve("ca.pem"), TestCertificates.pem(ca.certificate()), US_ASCII);
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

    private static Map<String, Object> configuration(URI url, @Nullable String credentials) {
        Map<String, Object> section = new HashMap<>();
        section.put("url", url.toString());
        section.put("trust", List.of(trust.toString()));
        if (credentials != null) {
            section.put("credentials", credentials);
        }
        return Map.of("timestamp", section);
    }

    private static Rfc3161AnchorProvider provider(URI url, @Nullable String credentials) {
        StoreResult<Rfc3161AnchorProvider> built = Rfc3161AnchorProvider.from(configuration(url, credentials), client);
        assertTrue(built.isOk(), () -> "the provider is built: " + built.detail());
        return built.value();
    }

    private static Rfc3161AnchorProvider provider() {
        return provider(authority.url(), null);
    }

    private static ObjectId subject() {
        return ObjectId.fromRaw(TimeStampRequests.sha256(Generators.letterStrings(8, 16).next().getBytes(US_ASCII)));
    }

    private static List<String> logTexts() {
        List<String> texts = new ArrayList<>();
        for (LogRecord logRecord : TestLoggerFactory.getTestHandler().getRecords()) {
            texts.add(String.valueOf(logRecord.getMessage()));
            if (logRecord.getParameters() != null) {
                for (Object parameter : logRecord.getParameters()) {
                    texts.add(String.valueOf(parameter));
                }
            }
        }
        return texts;
    }

    @Test
    @DisplayName("GDR-TSA-1: the provider id is rfc3161")
    void shouldCarryProviderId() {
        assertEquals("rfc3161", new Rfc3161AnchorProvider().id(), "id");
    }

    @Test
    @DisplayName("GDR-TSA-1: success returns a DER token whose imprint is SHA-256 over the lowercase hex id")
    void shouldObtainToken() throws Exception {
        var subject = subject();

        var result = provider().obtain(REPOSITORY, subject, Instant.now(), new byte[0]);

        assertTrue(result.isOk(), () -> "obtained: " + result.detail());
        var info = TimeStampRequests.tstInfo(result.value());
        byte[] expected = TimeStampRequests.sha256(subject.name().getBytes(US_ASCII));
        var received = authority.received().getFirst();
        assertAll(
                () -> assertEquals(TimeStampRequests.SHA_256, info.hashAlgorithm(), "hash algorithm of the token"),
                () -> assertArrayEquals(expected, info.hashedMessage(), "imprint of the token"),
                () -> assertEquals(TimeStampRequests.SHA_256, received.hashAlgorithm(), "hash algorithm requested"),
                () -> assertArrayEquals(expected, received.imprint(), "imprint requested"),
                () -> assertTrue(received.certReq(), "certReq requested"),
                () -> assertNotNull(received.nonce(), "nonce requested"),
                () -> assertEquals(received.nonce(), info.nonce(), "nonce of the token"));
        assertTrue(logTexts().stream().anyMatch(text -> text.startsWith("GIT_TIMESTAMP-1: Obtained a timestamp token"
                        + " for repository " + REPOSITORY + ", object " + subject.name())),
                () -> "obtained proof logged: " + logTexts());
    }

    @Test
    @DisplayName("GDR-TSA-1: the nonce differs across two requests")
    void shouldUseFreshNonce() {
        var provider = provider();
        var subject = subject();

        assertTrue(provider.obtain(REPOSITORY, subject, Instant.now(), new byte[0]).isOk(), "first");
        assertTrue(provider.obtain(REPOSITORY, subject, Instant.now(), new byte[0]).isOk(), "second");

        var received = authority.received();
        assertEquals(2, received.size(), "two requests");
        assertNotEquals(received.getFirst().nonce(), received.get(1).nonce(), "nonces");
    }

    @Test
    @DisplayName("GDR-TSA-1: the previous proof and the commit time are not used")
    void shouldIgnorePreviousProof() throws Exception {
        var provider = provider();
        var subject = subject();
        byte[] previous = Generators.letterStrings(40, 80).next().getBytes(US_ASCII);

        var withPrevious = provider.obtain(REPOSITORY, subject, Instant.EPOCH, previous);
        var without = provider.obtain(REPOSITORY, subject, Instant.now(), new byte[0]);

        assertTrue(withPrevious.isOk(), () -> "with a previous proof: " + withPrevious.detail());
        assertTrue(without.isOk(), () -> "without a previous proof: " + without.detail());
        var received = authority.received();
        assertArrayEquals(received.getFirst().imprint(), received.get(1).imprint(), "same request but the nonce");
        assertArrayEquals(TimeStampRequests.tstInfo(withPrevious.value()).hashedMessage(),
                TimeStampRequests.tstInfo(without.value()).hashedMessage(), "same imprint");
    }

    @Test
    @DisplayName("GDR-TSA-1: an error response is REFUSED")
    void shouldRefuseErrorResponse() {
        authority.answer(TestTimestampAuthority.Answer.REJECTION);

        var result = provider().obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);

        assertEquals(StoreOutcome.REFUSED, result.outcome(), "outcome");
        assertEquals(TimeStampRequests.STATUS_PREFIX + "2", result.detail(), "detail");
        assertTrue(logTexts().stream().anyMatch(text -> text.startsWith("GIT_TIMESTAMP-101:")),
                () -> "refusal logged: " + logTexts());
    }

    @Test
    @DisplayName("GDR-TSA-1: another HTTP status, another media type and an unreadable reply are REFUSED")
    void shouldRefuseBrokenReplies() {
        var provider = provider();
        List<String> details = new ArrayList<>();
        for (var answer : List.of(TestTimestampAuthority.Answer.HTTP_ERROR,
                TestTimestampAuthority.Answer.WRONG_CONTENT_TYPE, TestTimestampAuthority.Answer.UNREADABLE)) {
            authority.answer(answer);

            var result = provider.obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);

            assertEquals(StoreOutcome.REFUSED, result.outcome(), () -> "outcome of " + answer);
            details.add(result.detail());
        }
        assertEquals(List.of(TimestampTransport.HTTP_STATUS_PREFIX + "500", TimestampTransport.WRONG_CONTENT_TYPE,
                TimeStampRequests.REPLY_UNREADABLE), details, "details");
    }

    @Test
    @DisplayName("GDR-TSA-1: a token with a wrong nonce or a wrong imprint is REFUSED (RFC 3161, 2.4.2)")
    void shouldRefuseMismatchedToken() {
        var provider = provider();

        authority.answer(TestTimestampAuthority.Answer.WRONG_NONCE);
        var wrongNonce = provider.obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);
        authority.answer(TestTimestampAuthority.Answer.WRONG_IMPRINT);
        var wrongImprint = provider.obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);

        assertAll(
                () -> assertEquals(StoreOutcome.REFUSED, wrongNonce.outcome(), "wrong nonce"),
                () -> assertEquals(Rfc3161AnchorProvider.NONCE_MISMATCH, wrongNonce.detail(), "detail of the nonce"),
                () -> assertEquals(StoreOutcome.REFUSED, wrongImprint.outcome(), "wrong imprint"),
                () -> assertEquals(Rfc3161AnchorProvider.IMPRINT_DIFFERS, wrongImprint.detail(),
                        "detail of the imprint"));
    }

    @Test
    @DisplayName("GDR-TSA-1: an unreachable authority (closed port) is UNAVAILABLE")
    void shouldReportUnreachableAuthority() throws Exception {
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        var provider = provider(URI.create("https://127.0.0.1:" + port + "/tsa"), null);

        var result = provider.obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);

        assertEquals(StoreOutcome.UNAVAILABLE, result.outcome(), "outcome");
        assertEquals(TimestampTransport.UNREACHABLE, result.detail(), "detail");
        assertTrue(logTexts().stream().anyMatch(text -> text.startsWith("GIT_TIMESTAMP-100:")),
                () -> "unavailable authority logged: " + logTexts());
    }

    @Test
    @DisplayName("GDR-TSA-1: with timestamp.credentials the authority receives Authorization: Bearer <token>")
    void shouldSendBearerToken() {
        String token = Generators.letterStrings(24, 32).next();

        var result = provider(authority.url(), token).obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);

        assertTrue(result.isOk(), () -> "obtained: " + result.detail());
        assertEquals(Optional.of("Bearer " + token), authority.received().getFirst().authorization(), "header");
    }

    @Test
    @DisplayName("GDR-TSA-1: without timestamp.credentials no Authorization header is sent")
    void shouldSendNoAuthorizationWithoutCredentials() {
        var result = provider().obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);

        assertTrue(result.isOk(), () -> "obtained: " + result.detail());
        assertEquals(Optional.empty(), authority.received().getFirst().authorization(), "no header");
    }

    @Test
    @DisplayName("GDR-TSA-1: the unconfigured ServiceLoader instance opens no connection and refuses")
    void shouldNotReachNetworkUnconfigured() {
        long before = authority.calls();
        AnchorProvider unconfigured = new Rfc3161AnchorProvider();

        var obtained = unconfigured.obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);
        var verified = unconfigured.verify(subject(), new byte[]{1}, new byte[0]);

        assertAll(
                () -> assertEquals(StoreOutcome.REFUSED, obtained.outcome(), "obtain"),
                () -> assertEquals(Rfc3161AnchorProvider.UNCONFIGURED, obtained.detail(), "detail of obtain"),
                () -> assertEquals(StoreOutcome.SIGNATURE_INVALID, verified.outcome(), "verify"),
                () -> assertEquals(Rfc3161AnchorProvider.UNCONFIGURED, verified.detail(), "detail of verify"),
                () -> assertEquals(before, authority.calls(), "no connection"));
    }

    @Test
    @DisplayName("GDR-TSA-1: module-info provides the AnchorProvider rfc3161 to ServiceLoader")
    void shouldDeclareProvides() throws Exception {
        var classes = Path.of(Rfc3161AnchorProvider.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ModuleDescriptor descriptor;
        try (InputStream in = Files.newInputStream(classes.resolve("module-info.class"))) {
            descriptor = ModuleDescriptor.read(in);
        }

        assertTrue(descriptor.provides().stream().anyMatch(provides -> provides.service().equals(
                AnchorProvider.class.getName()) && provides.providers().equals(List.of(
                Rfc3161AnchorProvider.class.getName()))), () -> "provides: " + descriptor.provides());
    }

    @Test
    @DisplayName("GDR-NFR-3: the bearer token appears in no detail, log message or toString")
    void shouldNeverRenderToken() {
        String token = Generators.letterStrings(24, 32).next();
        var provider = provider(authority.url(), token);
        List<String> texts = new ArrayList<>();
        texts.add(provider.toString());
        for (var answer : TestTimestampAuthority.Answer.values()) {
            authority.answer(answer);
            var result = provider.obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);
            texts.add(String.valueOf(result.detail()));
        }

        texts.addAll(logTexts());
        assertFalse(texts.stream().anyMatch(text -> text.contains(token)), () -> "texts without the token: " + texts);
        assertFalse(texts.stream().anyMatch(text -> text.contains(authority.url().toString())),
                () -> "texts without the URL: " + texts);
    }
}
