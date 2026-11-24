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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import java.util.logging.LogRecord;


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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GDR-NFR-3 for {@code git-timestamp}: the bearer token of {@code timestamp.credentials} and the user information of a
 * URL never appear in a result detail, an exception message, a log message or the {@code toString} of
 * {@link TimestampConfiguration}, {@link TimestampTransport} and {@link Rfc3161AnchorProvider}, across obtain (success,
 * {@code REFUSED}, {@code UNAVAILABLE}), verify and the refusals of the configuration; the content of a file of
 * {@code timestamp.trust} never appears in a refusal of the configuration. Every log record is captured down to trace.
 * The secrets are generated, long enough to appear nowhere by chance.
 */
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("GDR-NFR-3: no secret of git-timestamp leaks")
class TimestampSecretsTest {

    private static final String REPOSITORY = "documents";

    private static TestTimestampAuthority authority;
    private static HttpClient client;

    @TempDir
    static Path directory;

    private static Path trust;

    @BeforeAll
    static void startAuthority() throws IOException {
        var ca = TestCertificates.ca("test ca");
        var tls = TestCertificates.tls(ca);
        authority = new TestTimestampAuthority(tls, ca, TestCertificates.signer(ca, "test tsa", true));
        client = TimestampTransport.clientBuilder().sslContext(TestCertificates.clientContext(ca)).build();
        trust = Files.writeString(directory.resolve("ca.pem"), TestCertificates.pem(ca.certificate()), US_ASCII);
    }

    @AfterAll
    static void stopAuthority() {
        authority.close();
        client.close();
    }

    private static String secret() {
        return Generators.letterStrings(24, 32).next();
    }

    private static Map<String, Object> configuration(String url, @Nullable String credentials, List<String> files) {
        Map<String, Object> section = new HashMap<>();
        section.put("url", url);
        section.put("trust", files);
        if (credentials != null) {
            section.put("credentials", credentials);
        }
        return Map.of("timestamp", section);
    }

    private static Rfc3161AnchorProvider provider(URI url, String credentials) {
        StoreResult<Rfc3161AnchorProvider> built = Rfc3161AnchorProvider.from(
                configuration(url.toString(), credentials, List.of(trust.toString())), client);
        assertTrue(built.isOk(), () -> "the provider is built: " + built.outcome());
        return built.value();
    }

    private static ObjectId subject() {
        return ObjectId.fromRaw(TimeStampRequests.sha256(Generators.letterStrings(8, 16).next().getBytes(US_ASCII)));
    }

    /** Every text a captured log record carries: message, parameters and the chain of its throwable. */
    private static List<String> logTexts() {
        List<String> texts = new ArrayList<>();
        for (LogRecord logRecord : TestLoggerFactory.getTestHandler().getRecords()) {
            texts.add(String.valueOf(logRecord.getMessage()));
            Object[] parameters = logRecord.getParameters();
            if (parameters != null) {
                for (Object parameter : parameters) {
                    texts.add(String.valueOf(parameter));
                }
            }
            for (Throwable thrown = logRecord.getThrown(); thrown != null; thrown = thrown.getCause()) {
                texts.add(String.valueOf(thrown));
            }
        }
        return texts;
    }

    private static void assertNoneCarries(List<String> texts, List<String> secrets) {
        for (String secret : secrets) {
            assertTrue(texts.stream().noneMatch(text -> text.contains(secret)),
                    () -> "a detail, a log text or a rendering carries a secret: " + texts.size() + " texts checked");
        }
    }

    @Test
    @DisplayName("GDR-NFR-3: the bearer token appears nowhere across obtain, verify and the renderings")
    void shouldNeverRenderBearerToken() throws Exception {
        String token = secret();
        var provider = provider(authority.url(), token);
        List<String> texts = new ArrayList<>();
        texts.add(provider.toString());
        var section = TimestampConfiguration.from(configuration(authority.url().toString(), token,
                List.of(trust.toString())));
        texts.add(String.valueOf(section.value()));
        texts.add(new TimestampTransport(authority.url(), token, client).toString());

        authority.answer(TestTimestampAuthority.Answer.GRANT);
        var granted = provider.obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);
        assertTrue(granted.isOk(), () -> "obtained: " + granted.detail());
        authority.answer(TestTimestampAuthority.Answer.REJECTION);
        var refused = provider.obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);
        assertEquals(StoreOutcome.REFUSED, refused.outcome(), "refused");
        texts.add(String.valueOf(refused.detail()));
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }
        var unavailable = provider(URI.create("https://127.0.0.1:" + port + "/tsa"), token)
                .obtain(REPOSITORY, subject(), Instant.now(), new byte[0]);
        assertEquals(StoreOutcome.UNAVAILABLE, unavailable.outcome(), "unavailable");
        texts.add(String.valueOf(unavailable.detail()));
        var verified = provider.verify(subject(), granted.value(), new byte[0]);
        assertEquals(StoreOutcome.IMPRINT_MISMATCH, verified.outcome(), "a token about another subject");
        texts.add(String.valueOf(verified.detail()));
        var unreadable = provider.verify(subject(), token.getBytes(US_ASCII), new byte[0]);
        texts.add(String.valueOf(unreadable.detail()));
        var thrown = assertThrows(NullPointerException.class, () -> provider.verify(subject(), null, new byte[0]));
        texts.add(String.valueOf(thrown.getMessage()));

        texts.addAll(logTexts());
        assertNoneCarries(texts, List.of(token, authority.url().toString()));
    }

    @Test
    @DisplayName("GDR-NFR-3: a URL with user information is refused without naming it")
    void shouldNeverRenderUserInformation() {
        String user = secret();
        String password = secret();
        String token = secret();
        String url = "https://" + user + ":" + password + "@127.0.0.1/tsa";

        var refused = Rfc3161AnchorProvider.from(configuration(url, token, List.of(trust.toString())), client);

        assertEquals(StoreOutcome.MALFORMED, refused.outcome(), "refused");
        assertEquals(TimestampConfiguration.URL + " is malformed", refused.detail(), "detail names the key");
        List<String> texts = new ArrayList<>(logTexts());
        texts.add(String.valueOf(refused.detail()));
        assertNoneCarries(texts, List.of(user, password, token));
    }

    @Test
    @DisplayName("GDR-NFR-3: a malformed token and a malformed trust file are refused without naming their content")
    void shouldNeverRenderRefusedValues() throws Exception {
        String content = secret();
        Path broken = Files.writeString(directory.resolve(secret() + ".pem"),
                "-----BEGIN CERTIFICATE-----\n" + content + "\n-----END CERTIFICATE-----\n", US_ASCII);
        String malformedToken = secret() + " " + secret();

        var refusedTrust = Rfc3161AnchorProvider.from(configuration(authority.url().toString(), null,
                List.of(broken.toString())), client);
        var refusedToken = Rfc3161AnchorProvider.from(configuration(authority.url().toString(), malformedToken,
                List.of(trust.toString())), client);

        assertEquals(StoreOutcome.MALFORMED, refusedTrust.outcome(), "trust refused");
        assertEquals(TimestampConfiguration.TRUST + "[0] is malformed", refusedTrust.detail(), "detail of the trust");
        assertEquals(StoreOutcome.MALFORMED, refusedToken.outcome(), "credentials refused");
        assertEquals(TimestampConfiguration.CREDENTIALS + " is malformed", refusedToken.detail(),
                "detail of the credentials");
        List<String> texts = new ArrayList<>(logTexts());
        texts.add(String.valueOf(refusedTrust.detail()));
        texts.add(String.valueOf(refusedToken.detail()));
        assertNoneCarries(texts, List.of(content, broken.toString(), malformedToken));
    }
}
