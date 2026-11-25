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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The parse of Git's {@code allowed_signers} format (GDR-STORE-14, ssh-keygen(1) ALLOWED SIGNERS): principals, options
 * and quoting, both time forms of {@code valid-after} and {@code valid-before} inclusive at their bounds, the
 * {@code namespaces} option, comments and blank lines, and every refusal with its line number and without key text or
 * a principal; the file is read from a path or from {@code signing.allowed-signers}, bounded: a file above 64 KiB or
 * 1024 lines, a directory or a FIFO, content that is not UTF-8 and a configured value with a NUL character each end as a
 * result.
 * <p>
 * Principals and keys are generated; the option names, the time forms and the details are the specified vocabulary and
 * kept as literals.
 */
@EnableGeneratorController
@DisplayName("AllowedSigners (GDR-STORE-14, GDR-STORE-18)")
class AllowedSignersTest {

    @TempDir
    Path temp;

    private static String principal() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next() + "@example.org";
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private Path file(String content) throws IOException {
        return Files.writeString(temp.resolve(Generators.letterStrings(8, 12).next()), content);
    }

    private StoreResult<AllowedSigners> read(String content) throws IOException {
        return AllowedSigners.read(file(content));
    }

    @Test
    @DisplayName("GDR-STORE-14: a plain line lists the key for commits at every time; comments and blanks are skipped")
    void plainLine() throws Exception {
        var key = TestKeys.ed25519();
        var other = TestKeys.ed25519();

        var signers = value(read("# the release signers\n\n   \n" + principal() + " " + key.publicKeyLine() + "\n"));

        assertAll(
                () -> assertEquals(1, signers.size(), "one entry"),
                () -> assertEquals(AllowedSigners.Match.VALID, signers.check(key.publicKeyBlob(), Instant.EPOCH),
                        "valid at any time"),
                () -> assertEquals(AllowedSigners.Match.UNKNOWN_KEY, signers.check(other.publicKeyBlob(),
                        Instant.EPOCH), "another key is unknown"));
    }

    @Test
    @DisplayName("GDR-STORE-14: quoted principals and quoted options are read; the options are case-insensitive")
    void quoting() throws Exception {
        var key = TestKeys.ed25519();

        var signers = value(read("\"" + principal() + "," + principal() + "\" Valid-After=\"20260101\","
                + "NAMESPACES=\"file,git\" " + key.publicKeyLine() + "\n"));

        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-06-01T00:00:00Z")), "valid"),
                () -> assertEquals(AllowedSigners.Match.OUTSIDE_VALIDITY,
                        signers.check(key.publicKeyBlob(), Instant.parse("2025-06-01T00:00:00Z")),
                        "before the validity"));
    }

    @Test
    @DisplayName("GDR-STORE-14: valid-after and valid-before in UTC are inclusive bounds, date and date-time forms")
    void validityInUtc() throws Exception {
        var key = TestKeys.ed25519();

        var signers = value(read(principal() + " valid-after=\"20260301Z\",valid-before=\"202603311200Z\" "
                + key.publicKeyLine() + "\n" + principal() + " valid-after=\"20270101083015Z\" "
                + TestKeys.ed25519().publicKeyLine()));

        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-03-01T00:00:00Z")),
                        "the start of the day of valid-after"),
                () -> assertEquals(AllowedSigners.Match.OUTSIDE_VALIDITY,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-02-28T23:59:59Z")),
                        "a second before valid-after"),
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-03-31T12:00:00Z")),
                        "valid-before itself"),
                () -> assertEquals(AllowedSigners.Match.OUTSIDE_VALIDITY,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-03-31T12:00:01Z")),
                        "a second after valid-before"),
                () -> assertEquals(2, signers.size(), "the seconds form is read"));
    }

    @Test
    @DisplayName("GDR-STORE-14: a timestamp without Z is in the system time zone, as ssh-keygen reads it")
    void validityInSystemZone() throws Exception {
        var key = TestKeys.ed25519();
        var start = LocalDateTime.of(2026, 5, 4, 3, 2).atZone(ZoneId.systemDefault()).toInstant();

        var signers = value(read(principal() + " valid-after=\"202605040302\" " + key.publicKeyLine()));

        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID, signers.check(key.publicKeyBlob(), start),
                        "at the local time"),
                () -> assertEquals(AllowedSigners.Match.OUTSIDE_VALIDITY,
                        signers.check(key.publicKeyBlob(), start.minusSeconds(1)), "a second before"));
    }

    @Test
    @DisplayName("GDR-STORE-14: a key whose namespaces exclude git is not used for commits")
    void namespacesExcludingGit() throws Exception {
        var key = TestKeys.ed25519();

        var signers = value(read(principal() + " namespaces=\"file,email\" " + key.publicKeyLine()));

        assertEquals(AllowedSigners.Match.UNKNOWN_KEY, signers.check(key.publicKeyBlob(), Instant.EPOCH),
                "not listed for commits");
    }

    @Test
    @DisplayName("GDR-STORE-14: a key listed twice is valid when one of its entries is valid at the time")
    void severalEntriesOfOneKey() throws Exception {
        var key = TestKeys.ed25519();
        var line = key.publicKeyLine();

        var signers = value(read(principal() + " valid-before=\"20250101Z\" " + line + "\n" + principal()
                + " valid-after=\"20260101Z\" " + line + "\n"));

        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(key.publicKeyBlob(), Instant.parse("2024-06-01T00:00:00Z")), "the first"),
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-06-01T00:00:00Z")), "the second"),
                () -> assertEquals(AllowedSigners.Match.OUTSIDE_VALIDITY,
                        signers.check(key.publicKeyBlob(), Instant.parse("2025-06-01T00:00:00Z")), "in between"));
    }

    @Test
    @DisplayName("GDR-STORE-14: a namespace listed twice is read once, and the key stays listed for commits")
    void duplicateNamespaces() throws Exception {
        var key = TestKeys.ed25519();

        var signers = value(read(principal() + " namespaces=\"git,git\" " + key.publicKeyLine()));

        assertAll(
                () -> assertEquals(1, signers.size(), "one entry"),
                () -> assertEquals(AllowedSigners.Match.VALID, signers.check(key.publicKeyBlob(), Instant.EPOCH),
                        "listed for commits"));
    }

    @Test
    @DisplayName("GDR-STORE-14: each option given once, in any order, with a validity a second long, is read")
    void singleClausesInAnyOrder() throws Exception {
        var key = TestKeys.ed25519();

        var signers = value(read(principal() + " namespaces=\"git,git\",valid-before=\"20260101000001Z\","
                + "valid-after=\"20260101Z\" " + key.publicKeyLine()));

        assertAll(
                () -> assertEquals(1, signers.size(), "one entry"),
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-01-01T00:00:00Z")), "at the start"),
                () -> assertEquals(AllowedSigners.Match.OUTSIDE_VALIDITY,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-01-01T00:00:02Z")), "after the end"));
    }

    @Test
    @DisplayName("GDR-STORE-14: a leap day is a valid timestamp, in the date and in the date-time form")
    void leapDay() throws Exception {
        var key = TestKeys.ed25519();

        var signers = value(read(principal() + " valid-after=\"20280229Z\",valid-before=\"202802292359Z\" "
                + key.publicKeyLine()));

        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(key.publicKeyBlob(), Instant.parse("2028-02-29T12:00:00Z")), "on the leap day"),
                () -> assertEquals(AllowedSigners.Match.OUTSIDE_VALIDITY,
                        signers.check(key.publicKeyBlob(), Instant.parse("2028-03-01T00:00:00Z")), "the day after"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "cert-authority|cert-authority not supported",
            "valid-after=\"2026\"|timestamp malformed",
            "valid-before=\"20261332Z\"|timestamp malformed",
            "valid-after=\"202602300000Z\"|timestamp malformed",
            "valid-before=\"20260230\"|timestamp malformed",
            "valid-after=\"20270229120000Z\"|timestamp malformed",
            "namespaces=\"git,!git\"|namespace pattern not supported",
            "namespaces=\"!file\"|namespace pattern not supported",
            "namespaces=\"g*\"|namespace pattern not supported",
            "namespaces=\"gi?\"|namespace pattern not supported",
            "no-touch-required|option not supported",
            "valid-after=\"20260101|quote not closed",
            "valid-before=\"20200101Z\",valid-before=\"20300101Z\"|option repeated",
            "valid-after=\"20300101Z\",Valid-After=\"20200101Z\"|option repeated",
            "namespaces=\"file\",namespaces=\"git\"|option repeated",
            "valid-after=\"20260101Z\",valid-before=\"20260101Z\"|validity interval empty",
            "valid-after=\"20270101Z\",valid-before=\"20260101Z\"|validity interval empty",
            "valid-before=\"20260101Z\",valid-after=\"202601010000Z\"|validity interval empty"})
    @DisplayName("GDR-STORE-14: a refused option is MALFORMED naming the line number, never a principal or key text")
    void refusedOptions(String scenario) throws Exception {
        var parts = scenario.split("\\|");
        var key = TestKeys.ed25519();
        var principal = principal();

        var result = read("# a comment\n" + principal + " " + parts[0] + " " + key.publicKeyLine() + "\n");

        assertRefused(result, "line 2: " + parts[1], principal, key);
    }

    @Test
    @DisplayName("GDR-STORE-14: another key type, a malformed key and a missing key are MALFORMED with the line")
    void refusedKeys() throws Exception {
        var key = TestKeys.ed25519();
        var principal = principal();
        var rsa = TestKeys.writeRsa(temp.resolve("rsa"));
        var valid = principal + " " + key.publicKeyLine() + "\n";
        var truncated = Base64.getEncoder().encodeToString(new byte[]{0, 0, 0, 11, 's', 's', 'h'});

        assertAll(
                () -> assertRefused(read(valid + principal + " " + rsa + "\n"), "line 2: key type not supported",
                        principal, key),
                () -> assertRefused(read(valid + principal + " ssh-ed25519 " + truncated + "\n"),
                        "line 2: key malformed", principal, key),
                () -> assertRefused(read(valid + valid + principal + " ssh-ed25519\n"), "line 3: key missing",
                        principal, key),
                () -> assertRefused(read(principal + "\n"), "line 1: key missing", principal, key));
    }

    private static void assertRefused(StoreResult<AllowedSigners> result, String detail, String principal,
            TestKeys.Ed25519 key) {
        String text = String.valueOf(result.detail());
        String keyText = key.publicKeyLine().split(" ")[1];
        assertAll(
                () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, detail), result, "refused"),
                () -> assertFalse(text.contains(principal), "no principal"),
                () -> assertFalse(text.contains(keyText), "no key text"));
    }

    @Test
    @DisplayName("GDR-STORE-14: an unreadable file is UNAVAILABLE without naming its path")
    void unreadableFile() {
        var missing = temp.resolve(Generators.letterStrings(12, 20).next());

        var result = AllowedSigners.read(missing);

        assertAll(
                () -> assertEquals(StoreResult.of(StoreOutcome.UNAVAILABLE, "signing.allowed-signers: not readable"),
                        result, "unavailable"),
                () -> assertFalse(String.valueOf(result.detail()).contains(missing.toString()), "no path"));
    }

    private static StoreConfiguration configuration(@Nullable String allowedSigners) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 1));
        tree.put("drafts", Map.of("expiry", Period.ofDays(1).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(1).toString()));
        if (allowedSigners != null) {
            tree.put("signing", Map.of("allowed-signers", allowedSigners));
        }
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    @Test
    @DisplayName("GDR-STORE-14: from reads signing.allowed-signers, and an absent key is MALFORMED")
    void fromConfiguration() throws Exception {
        var key = TestKeys.ed25519();
        var path = file(principal() + " " + key.publicKeyLine());

        var configured = AllowedSigners.from(configuration(path.toString()));
        var absent = AllowedSigners.from(configuration(null));

        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID,
                        value(configured).check(key.publicKeyBlob(), Instant.EPOCH), "the configured file"),
                () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "signing.allowed-signers is missing"),
                        absent, "absent"));
    }

    @Test
    @DisplayName("GDR-STORE-18: the namespace-aware check uses a key for git-document-anchor and for git as its list says")
    void namespaceAwareCheck() throws Exception {
        var anchorOnly = TestKeys.ed25519();
        var gitOnly = TestKeys.ed25519();
        var both = TestKeys.ed25519();
        var unrestricted = TestKeys.ed25519();

        var signers = value(read(principal() + " namespaces=\"git-document-anchor\" " + anchorOnly.publicKeyLine()
                + "\n" + principal() + " namespaces=\"git\" " + gitOnly.publicKeyLine() + "\n" + principal()
                + " namespaces=\"git,git-document-anchor\" " + both.publicKeyLine() + "\n" + principal() + " "
                + unrestricted.publicKeyLine() + "\n"));

        String anchor = SignedAnchorProvider.NAMESPACE;
        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(anchorOnly.publicKeyBlob(), Instant.EPOCH, anchor), "anchor key for anchors"),
                () -> assertEquals(AllowedSigners.Match.UNKNOWN_KEY,
                        signers.check(anchorOnly.publicKeyBlob(), Instant.EPOCH), "anchor key not for commits"),
                () -> assertEquals(AllowedSigners.Match.UNKNOWN_KEY,
                        signers.check(gitOnly.publicKeyBlob(), Instant.EPOCH, anchor), "commit key not for anchors"),
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(gitOnly.publicKeyBlob(), Instant.EPOCH, SshSignatures.NAMESPACE_GIT),
                        "commit key for commits"),
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(both.publicKeyBlob(), Instant.EPOCH, anchor), "both: anchors"),
                () -> assertEquals(AllowedSigners.Match.VALID, signers.check(both.publicKeyBlob(), Instant.EPOCH),
                        "both: commits"),
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(unrestricted.publicKeyBlob(), Instant.EPOCH, anchor),
                        "no namespaces: every namespace"));
    }

    @Test
    @DisplayName("GDR-STORE-18: the validity of an anchor key bounds the namespace-aware check")
    void anchorValidity() throws Exception {
        var key = TestKeys.ed25519();

        var signers = value(read(principal() + " namespaces=\"git-document-anchor\",valid-after=\"20260101Z\" "
                + key.publicKeyLine()));

        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID, signers.check(key.publicKeyBlob(),
                        Instant.parse("2026-06-01T00:00:00Z"), SignedAnchorProvider.NAMESPACE), "inside"),
                () -> assertEquals(AllowedSigners.Match.OUTSIDE_VALIDITY, signers.check(key.publicKeyBlob(),
                        Instant.parse("2025-06-01T00:00:00Z"), SignedAnchorProvider.NAMESPACE), "before"));
    }

    private static StoreConfiguration anchorConfiguration(@Nullable String allowedSigners) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 1));
        tree.put("drafts", Map.of("expiry", Period.ofDays(1).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(1).toString()));
        if (allowedSigners != null) {
            tree.put("anchor", Map.of("signed", Map.of("allowed-signers", allowedSigners)));
        }
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    @Test
    @DisplayName("GDR-STORE-18: fromAnchor reads anchor.signed.allowed-signers; absent is MALFORMED, unreadable UNAVAILABLE")
    void fromAnchorConfiguration() throws Exception {
        var key = TestKeys.ed25519();
        var path = file(principal() + " " + key.publicKeyLine());
        var missing = temp.resolve(Generators.letterStrings(12, 20).next());

        var configured = AllowedSigners.fromAnchor(anchorConfiguration(path.toString()));
        var absent = AllowedSigners.fromAnchor(anchorConfiguration(null));
        var unreadable = AllowedSigners.fromAnchor(anchorConfiguration(missing.toString()));

        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID, value(configured).check(key.publicKeyBlob(),
                        Instant.EPOCH, SignedAnchorProvider.NAMESPACE), "the configured file"),
                () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "anchor.signed.allowed-signers is missing"),
                        absent, "absent"),
                () -> assertEquals(StoreResult.of(StoreOutcome.UNAVAILABLE,
                        "anchor.signed.allowed-signers: not readable"), unreadable, "unreadable"),
                () -> assertFalse(String.valueOf(unreadable.detail()).contains(missing.toString()), "no path"),
                () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "signing.allowed-signers is missing"),
                        AllowedSigners.from(anchorConfiguration(path.toString())),
                        "the commit-signing file is a separate key"));
    }

    @Test
    @DisplayName("GDR-STORE-14: a file above 64 KiB is REFUSED as too large without naming its path")
    void oversizedFile() throws Exception {
        var key = TestKeys.ed25519();
        var line = principal() + " " + key.publicKeyLine() + "\n";
        var content = new StringBuilder();
        while (content.length() <= AllowedSigners.MAX_FILE_SIZE) {
            content.append("# ").append(Generators.letterStrings(60, 80).next()).append('\n');
        }
        var path = file(line + content);

        var result = AllowedSigners.read(path);

        assertAll(
                () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signing.allowed-signers: too large"), result,
                        "too large"),
                () -> assertFalse(String.valueOf(result.detail()).contains(path.toString()), "no path"));
    }

    @Test
    @DisplayName("GDR-STORE-14: a file with more than 1024 lines is REFUSED as too large, 1024 lines are read")
    void tooManyLines() throws Exception {
        var key = TestKeys.ed25519();
        var line = principal() + " " + key.publicKeyLine() + "\n";

        var atBound = read(line + "\n".repeat(AllowedSigners.MAX_LINES - 1));
        var aboveBound = read(line + "\n".repeat(AllowedSigners.MAX_LINES));

        assertAll(
                () -> assertEquals(1, value(atBound).size(), "1024 lines are read"),
                () -> assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signing.allowed-signers: too large"),
                        aboveBound, "1025 lines are refused"));
    }

    @Test
    @DisplayName("GDR-STORE-14: content that is not valid UTF-8 is MALFORMED")
    void notUtf8() throws Exception {
        var key = TestKeys.ed25519();
        var path = temp.resolve(Generators.letterStrings(8, 12).next());
        var valid = (principal() + " " + key.publicKeyLine() + "\n").getBytes(StandardCharsets.UTF_8);
        var content = new byte[valid.length + 2];
        System.arraycopy(valid, 0, content, 0, valid.length);
        content[valid.length] = (byte) 0xC3;
        content[valid.length + 1] = (byte) 0x28;
        Files.write(path, content);

        assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "signing.allowed-signers: not UTF-8"),
                AllowedSigners.read(path), "not UTF-8");
    }

    @Test
    @DisplayName("GDR-STORE-14: a directory is REFUSED as no regular file")
    void directoryIsNoRegularFile() throws Exception {
        var directory = Files.createDirectory(temp.resolve(Generators.letterStrings(8, 12).next()));

        assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signing.allowed-signers: not a regular file"),
                AllowedSigners.read(directory), "a directory");
    }

    @Test
    @DisplayName("GDR-STORE-14: a FIFO is REFUSED as no regular file, without blocking on the read")
    void fifoIsNoRegularFile() throws Exception {
        var fifo = temp.resolve(Generators.letterStrings(8, 12).next());
        var mkfifo = ExternalTools.onPath("mkfifo");
        assumeTrue(mkfifo.isPresent(), "mkfifo is not on the PATH");
        var made = ExternalTools.run(new ProcessBuilder(mkfifo.get().toString(), fifo.toString()),
                temp.resolve("mkfifo.out"), null);
        assumeTrue(made.code() == 0 && Files.exists(fifo), "mkfifo could not create a FIFO");

        var result = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> AllowedSigners.read(fifo),
                "the read never opens the FIFO");

        assertEquals(StoreResult.of(StoreOutcome.REFUSED, "signing.allowed-signers: not a regular file"), result,
                "a FIFO");
    }

    @Test
    @DisplayName("GDR-STORE-14, GDR-STORE-18: a configured value with a NUL character is MALFORMED as no path")
    void nulPath() {
        var value = Generators.letterStrings(4, 8).next() + '\0' + Generators.letterStrings(4, 8).next();

        assertAll(
                () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "signing.allowed-signers: not a path"),
                        AllowedSigners.from(configuration(value)), "signing"),
                () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "anchor.signed.allowed-signers: not a path"),
                        AllowedSigners.fromAnchor(anchorConfiguration(value)), "anchor"));
    }

    @Test
    @DisplayName("GDR-STORE-14: a timestamp with a lower-case z is read in UTC, as OpenSSH compares the suffix")
    void lowerCaseZSuffix() throws Exception {
        var key = TestKeys.ed25519();

        var signers = value(read(principal() + " valid-after=\"20260301z\" " + key.publicKeyLine()));

        assertAll(
                () -> assertEquals(AllowedSigners.Match.VALID,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-03-01T00:00:00Z")), "at the start"),
                () -> assertEquals(AllowedSigners.Match.OUTSIDE_VALIDITY,
                        signers.check(key.publicKeyBlob(), Instant.parse("2026-02-28T23:59:59Z")), "a second before"));
    }

    @Test
    @DisplayName("GDR-STORE-14: an ssh-rsa line beside an Ed25519 line refuses the whole file at its line")
    void rsaLineRefusesTheFile() throws Exception {
        var key = TestKeys.ed25519();
        var principal = principal();
        var rsa = TestKeys.writeRsa(temp.resolve("rsa"));

        var result = read(principal + " " + rsa + "\n" + principal + " " + key.publicKeyLine() + "\n");

        assertRefused(result, "line 1: key type not supported", principal, key);
    }

    @Test
    @DisplayName("GDR-STORE-14: the rendering names the number of entries only")
    void rendering() throws Exception {
        var key = TestKeys.ed25519();
        var principal = principal();

        var signers = value(read(principal + " " + key.publicKeyLine()));

        assertAll(
                () -> assertEquals("AllowedSigners[entries=1]", signers.toString(), "the rendering"),
                () -> assertFalse(signers.toString().contains(principal), "no principal"));
    }
}
