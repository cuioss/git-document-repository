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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * GDR-TSA-1, configuration of the provider {@code rfc3161}: the section {@code timestamp} with {@code timestamp.url}
 * (HTTPS only, no user information), {@code timestamp.trust} (a list of paths to PEM files of X.509 certificates, read
 * without following a symbolic link and with a size limit) and {@code timestamp.credentials} (a bearer token); every
 * refusal is {@code MALFORMED} naming the key, never the value, and the token appears in no detail, log message or
 * {@code toString} (GDR-NFR-3).
 */
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("GDR-TSA-1: configuration of the timestamp section")
class TimestampConfigurationTest {

    private static final String URL = "https://tsa.example.org/tsa";

    private static String caPem;
    private static String twoCertificatesPem;

    @TempDir
    Path directory;

    @BeforeAll
    static void createCertificates() {
        var first = TestCertificates.ca("trust one");
        var second = TestCertificates.ca("trust two");
        caPem = TestCertificates.pem(first.certificate());
        twoCertificatesPem = TestCertificates.pem(first.certificate(), second.certificate());
    }

    private Path file(String name, String content) throws IOException {
        return Files.writeString(directory.resolve(name), content, US_ASCII);
    }

    private static Map<String, Object> configuration(Map<String, ?> section) {
        Map<String, Object> root = new HashMap<>();
        root.put("timestamp", section);
        return root;
    }

    private static Map<String, Object> section(Object url, Object trust) {
        Map<String, Object> section = new HashMap<>();
        section.put("url", url);
        section.put("trust", trust);
        return section;
    }

    private static void assertMalformed(StoreResult<?> result, String key) {
        assertAll(
                () -> assertEquals(StoreOutcome.MALFORMED, result.outcome(), "outcome"),
                () -> assertTrue(String.valueOf(result.detail()).startsWith(key), () -> "detail names " + key
                        + ": " + result.detail()));
    }

    @Test
    @DisplayName("GDR-TSA-1: a valid section is read with its URL, its certificates and its token")
    void shouldReadValidSection() throws Exception {
        var trust = file("ca.pem", caPem);
        String token = Generators.letterStrings(16, 32).next();
        var section = section(URL, List.of(trust.toString()));
        section.put("credentials", token);

        var result = TimestampConfiguration.from(configuration(section));

        assertTrue(result.isOk(), () -> "read: " + result.detail());
        var read = result.value();
        assertNotNull(read);
        assertAll(
                () -> assertEquals(URL, read.url().toString(), "url"),
                () -> assertEquals(1, read.trust().size(), "certificates"),
                () -> assertEquals(token, read.credentials(), "token"),
                () -> assertFalse(read.toString().contains(token), "toString without the token"));
    }

    @Test
    @DisplayName("GDR-TSA-1: a file with several certificates and several files are all read")
    void shouldReadSeveralCertificates() throws Exception {
        var one = file("one.pem", caPem);
        var two = file("two.pem", twoCertificatesPem);

        var result = TimestampConfiguration.from(configuration(section(URL, List.of(one.toString(),
                two.toString()))));

        assertTrue(result.isOk(), () -> "read: " + result.detail());
        assertEquals(3, result.value().trust().size(), "one certificate and two certificates");
    }

    @Test
    @DisplayName("GDR-TSA-1: without timestamp.credentials no token is held")
    void shouldReadWithoutCredentials() throws Exception {
        var trust = file("ca.pem", caPem);

        var result = TimestampConfiguration.from(configuration(section(URL, List.of(trust.toString()))));

        assertTrue(result.isOk(), () -> "read: " + result.detail());
        assertNull(result.value().credentials(), "no token");
    }

    @Test
    @DisplayName("GDR-TSA-1: a missing section or key is MALFORMED naming the key")
    void shouldRefuseMissingKeys() throws Exception {
        var trust = file("ca.pem", caPem);
        Map<String, Object> withoutUrl = new HashMap<>();
        withoutUrl.put("trust", List.of(trust.toString()));
        Map<String, Object> withoutTrust = new HashMap<>();
        withoutTrust.put("url", URL);

        assertAll(
                () -> assertMalformed(TimestampConfiguration.from(Map.of()), TimestampConfiguration.KEY_URL),
                () -> assertMalformed(TimestampConfiguration.from(Map.of("timestamp", "text")), "timestamp"),
                () -> assertMalformed(TimestampConfiguration.from(configuration(withoutUrl)),
                        TimestampConfiguration.KEY_URL),
                () -> assertMalformed(TimestampConfiguration.from(configuration(withoutTrust)),
                        TimestampConfiguration.KEY_TRUST));
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://tsa.example.org/tsa", "ftp://tsa.example.org/", "https:opaque", "tsa.example.org",
            "https://exa mple.org/", "  "})
    @DisplayName("GDR-TSA-1: a URL that is not an absolute HTTPS URL is MALFORMED naming timestamp.url")
    void shouldRefuseNonHttpsUrl(String url) throws Exception {
        var trust = file("ca.pem", caPem);

        assertMalformed(TimestampConfiguration.from(configuration(section(url, List.of(trust.toString())))),
                TimestampConfiguration.KEY_URL);
    }

    @Test
    @DisplayName("GDR-TSA-1: the port 65535 is accepted, the port 65536 is MALFORMED naming timestamp.url")
    void shouldBoundPort() throws Exception {
        var trust = file("ca.pem", caPem);

        var highest = TimestampConfiguration.from(configuration(section("https://127.0.0.1:65535/tsa",
                List.of(trust.toString()))));
        var beyond = TimestampConfiguration.from(configuration(section("https://127.0.0.1:65536/tsa",
                List.of(trust.toString()))));
        var omitted = TimestampConfiguration.from(configuration(section(URL, List.of(trust.toString()))));

        assertAll(
                () -> assertTrue(highest.isOk(), () -> "65535: " + highest.detail()),
                () -> assertTrue(omitted.isOk(), () -> "no port: " + omitted.detail()),
                () -> assertMalformed(beyond, TimestampConfiguration.KEY_URL));
    }

    @Test
    @DisplayName("GDR-TSA-1: a binary DER certificate file is no PEM file and is MALFORMED naming the entry")
    void shouldRefuseBinaryDer() throws Exception {
        var der = Files.write(directory.resolve("ca.der"), TestCertificates.ca("binary").certificate().getEncoded());

        var result = TimestampConfiguration.from(configuration(section(URL, List.of(der.toString()))));

        assertEquals(TimestampConfiguration.KEY_TRUST + "[0] is malformed", result.detail(), "detail");
        assertEquals(StoreOutcome.MALFORMED, result.outcome(), "outcome");
    }

    @Test
    @DisplayName("GDR-TSA-1: a binary PKCS #7 file followed by an incomplete BEGIN marker is MALFORMED")
    void shouldRefusePkcs7WithIncompleteMarker() throws Exception {
        var certificate = TestCertificates.ca("pkcs7").certificate();
        byte[] pkcs7 = CertificateFactory.getInstance("X.509").generateCertPath(List.of(certificate))
                .getEncoded("PKCS7");
        byte[] content = Der.concat(pkcs7, "\n-----BEGIN CERTIFICATE-----\n".getBytes(US_ASCII));
        var file = Files.write(directory.resolve("bundle.p7b"), content);

        var result = TimestampConfiguration.from(configuration(section(URL, List.of(file.toString()))));

        assertEquals(TimestampConfiguration.KEY_TRUST + "[0] is malformed", result.detail(), "detail");
        assertEquals(StoreOutcome.MALFORMED, result.outcome(), "outcome");
    }

    @Test
    @DisplayName("GDR-TSA-1: a block without its END, a body that is no base64 or no certificate is MALFORMED")
    void shouldRefuseIncompleteOrInvalidBlocks() throws Exception {
        String notCertificate = Base64.getEncoder().encodeToString(Der.sequence(Der.integer(1)));
        String withoutEnd = caPem + "-----BEGIN CERTIFICATE-----\n" + caPem.lines().skip(1).findFirst().orElseThrow()
                + "\n";
        String nested = "-----BEGIN CERTIFICATE-----\n" + caPem;
        var cases = Map.of(
                "without-end.pem", withoutEnd,
                "not-base64.pem", "-----BEGIN CERTIFICATE-----\n!!!!\n-----END CERTIFICATE-----\n",
                "not-certificate.pem",
                "-----BEGIN CERTIFICATE-----\n" + notCertificate + "\n-----END CERTIFICATE-----\n",
                "empty-body.pem", "-----BEGIN CERTIFICATE-----\n-----END CERTIFICATE-----\n",
                "nested.pem", nested,
                "only-text.pem", "no certificate here\n");

        for (var entry : cases.entrySet()) {
            var file = file(entry.getKey(), entry.getValue());

            var result = TimestampConfiguration.from(configuration(section(URL, List.of(file.toString()))));

            assertEquals(TimestampConfiguration.KEY_TRUST + "[0] is malformed", result.detail(), entry::getKey);
        }
    }

    @Test
    @DisplayName("GDR-TSA-1: a PEM file with a byte outside ASCII is MALFORMED")
    void shouldRefuseNonAsciiByte() throws Exception {
        byte[] content = Der.concat("Zertifikat f".getBytes(US_ASCII), new byte[]{(byte) 0xC3, (byte) 0xBC},
                "r die Pr\n".getBytes(US_ASCII), caPem.getBytes(US_ASCII));
        var file = Files.write(directory.resolve("umlaut.pem"), content);

        var result = TimestampConfiguration.from(configuration(section(URL, List.of(file.toString()))));

        assertEquals(TimestampConfiguration.KEY_TRUST + "[0] is malformed", result.detail(), "detail");
    }

    @Test
    @DisplayName("GDR-TSA-1: explanatory text around and between blocks and CRLF line ends are accepted")
    void shouldAcceptExplanatoryTextAndCrlf() throws Exception {
        String explained = "Subject: trust one\n" + caPem + "between the blocks\n\n" + caPem + "trailing text";
        String crlf = twoCertificatesPem.replace("\n", "\r\n");
        var one = file("explained.pem", explained);
        var two = file("crlf.pem", crlf);

        var result = TimestampConfiguration.from(configuration(section(URL, List.of(one.toString(),
                two.toString()))));

        assertTrue(result.isOk(), () -> "read: " + result.detail());
        assertEquals(4, result.value().trust().size(), "two blocks in each file");
    }

    @Test
    @DisplayName("GDR-TSA-1: a URL with user information is MALFORMED and the detail does not carry it")
    void shouldRefuseUserInformation() throws Exception {
        var trust = file("ca.pem", caPem);
        String password = Generators.letterStrings(12, 20).next();

        var result = TimestampConfiguration.from(configuration(section("https://user:" + password
                + "@tsa.example.org/tsa", List.of(trust.toString()))));

        assertMalformed(result, TimestampConfiguration.KEY_URL);
        assertFalse(String.valueOf(result.detail()).contains(password), "detail without the password");
    }

    @Test
    @DisplayName("GDR-TSA-1: a URL that is no string is MALFORMED naming timestamp.url")
    void shouldRefuseNonStringUrl() throws Exception {
        var trust = file("ca.pem", caPem);

        assertMalformed(TimestampConfiguration.from(configuration(section(42, List.of(trust.toString())))),
                TimestampConfiguration.KEY_URL);
    }

    @Test
    @DisplayName("GDR-TSA-1: an empty list or no list for timestamp.trust is MALFORMED naming timestamp.trust")
    void shouldRefuseEmptyTrust() {
        assertAll(
                () -> assertMalformed(TimestampConfiguration.from(configuration(section(URL, List.of()))),
                        TimestampConfiguration.KEY_TRUST),
                () -> assertMalformed(TimestampConfiguration.from(configuration(section(URL, "ca.pem"))),
                        TimestampConfiguration.KEY_TRUST));
    }

    @Test
    @DisplayName("GDR-TSA-1: a missing file of timestamp.trust is MALFORMED naming timestamp.trust")
    void shouldRefuseMissingTrustFile() {
        var missing = directory.resolve("missing.pem");

        assertMalformed(TimestampConfiguration.from(configuration(section(URL, List.of(missing.toString())))),
                TimestampConfiguration.KEY_TRUST);
    }

    @Test
    @DisplayName("GDR-TSA-1: a symbolic link in timestamp.trust is not followed and is MALFORMED")
    void shouldRefuseSymbolicLink() throws Exception {
        var target = file("ca.pem", caPem);
        var link = Files.createSymbolicLink(directory.resolve("link.pem"), target);

        assertMalformed(TimestampConfiguration.from(configuration(section(URL, List.of(link.toString())))),
                TimestampConfiguration.KEY_TRUST);
    }

    @Test
    @DisplayName("GDR-TSA-1: a file of timestamp.trust above the size limit is MALFORMED")
    void shouldRefuseOversizedFile() throws Exception {
        var padding = "#".repeat(TimestampConfiguration.MAX_TRUST_FILE_BYTES);
        var oversized = file("large.pem", caPem + padding);

        assertMalformed(TimestampConfiguration.from(configuration(section(URL, List.of(oversized.toString())))),
                TimestampConfiguration.KEY_TRUST);
    }

    @Test
    @DisplayName("GDR-TSA-1: an unparsable or empty file of timestamp.trust is MALFORMED without its content")
    void shouldRefuseUnparsableFile() throws Exception {
        String content = "-----BEGIN CERTIFICATE-----\n" + Generators.letterStrings(40, 60).next()
                + "\n-----END CERTIFICATE-----\n";
        var unparsable = file("broken.pem", content);
        var empty = file("empty.pem", "");

        var result = TimestampConfiguration.from(configuration(section(URL, List.of(unparsable.toString()))));

        assertMalformed(result, TimestampConfiguration.KEY_TRUST);
        assertFalse(String.valueOf(result.detail()).contains(content.strip()), "detail without the content");
        assertMalformed(TimestampConfiguration.from(configuration(section(URL, List.of(empty.toString())))),
                TimestampConfiguration.KEY_TRUST);
    }

    @Test
    @DisplayName("GDR-TSA-1: the second of two entries is named by its index")
    void shouldNameEntryByIndex() throws Exception {
        var trust = file("ca.pem", caPem);

        var result = TimestampConfiguration.from(configuration(section(URL, List.of(trust.toString(), ""))));

        assertMalformed(result, TimestampConfiguration.KEY_TRUST);
        assertEquals(TimestampConfiguration.KEY_TRUST + "[1] is malformed", result.detail(), "detail");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "two words", "line\nbreak", "tab\tbed", "ümlaut"})
    @DisplayName("GDR-TSA-1: a blank or non-token timestamp.credentials is MALFORMED without its value")
    void shouldRefuseMalformedCredentials(String token) throws Exception {
        var trust = file("ca.pem", caPem);
        var section = section(URL, List.of(trust.toString()));
        section.put("credentials", token);

        var result = TimestampConfiguration.from(configuration(section));

        assertEquals(TimestampConfiguration.KEY_CREDENTIALS + " is malformed", result.detail(), "detail");
        assertEquals(StoreOutcome.MALFORMED, result.outcome(), "outcome");
    }

    @Test
    @DisplayName("GDR-TSA-1: a timestamp.credentials that is no string is MALFORMED")
    void shouldRefuseNonStringCredentials() throws Exception {
        var trust = file("ca.pem", caPem);
        var section = section(URL, List.of(trust.toString()));
        section.put("credentials", List.of("token"));

        assertMalformed(TimestampConfiguration.from(configuration(section)), TimestampConfiguration.KEY_CREDENTIALS);
    }

    @Test
    @DisplayName("GDR-NFR-3: the token appears in no detail, log message or toString of the configuration")
    void shouldNeverRenderToken() throws Exception {
        var trust = file("ca.pem", caPem);
        String token = Generators.letterStrings(24, 32).next();
        var valid = section(URL, List.of(trust.toString()));
        valid.put("credentials", token);
        var invalid = section("http://tsa.example.org/", List.of(trust.toString()));
        invalid.put("credentials", token);

        var read = TimestampConfiguration.from(configuration(valid));
        var refused = TimestampConfiguration.from(configuration(invalid));

        List<String> texts = new ArrayList<>();
        texts.add(String.valueOf(read.value()));
        texts.add(String.valueOf(refused.detail()));
        texts.add(read.toString());
        texts.add(refused.toString());
        for (LogRecord logRecord : TestLoggerFactory.getTestHandler().getRecords()) {
            texts.add(String.valueOf(logRecord.getMessage()));
        }
        assertTrue(texts.stream().noneMatch(text -> text.contains(token)), () -> "texts without the token: " + texts);
    }
}
