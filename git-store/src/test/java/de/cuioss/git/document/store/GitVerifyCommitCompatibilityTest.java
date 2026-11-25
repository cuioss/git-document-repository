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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.regex.Pattern;


import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

/**
 * The cross-check of GDR-STORE-14 with a stock Git installation: {@code git verify-commit} with
 * {@code gpg.format=ssh} and {@code gpg.ssh.allowedSignersFile}
 * (<a href="https://git-scm.com/docs/git-verify-commit">git-verify-commit(1)</a>) accepts a commit the store signed
 * with an allowed-signers file whose validity includes the committer time, and rejects it with one whose
 * {@code valid-before} lies before it, which Git passes to {@code ssh-keygen} since Git 2.35. The commit time is
 * historical and the accepting validity ends after it but long before the test runs, so only a check at the commit
 * time, never one at the wall time, passes both cases.
 * <p>
 * The test runs only when {@code git --version} reports 2.35 or later and the {@code ssh-keygen} on the {@code PATH}
 * finds principals and honours key lifetimes at a verify time ({@link ExternalTools#verifiesWithLifetimes}); that
 * same executable is Git's {@code gpg.ssh.program}. The processes run with a temporary {@code HOME} and
 * {@code XDG_CONFIG_HOME}, an empty {@code GIT_CONFIG_GLOBAL}, {@code GIT_CONFIG_NOSYSTEM=1} and no inherited
 * {@code GIT_*} variable, without network.
 * <p>
 * A run without those tools is never silent: {@link ExternalTools#assumeCrossCheck} logs the reason as a WARN record
 * and aborts the test, and fails it instead under {@code GIT_STORE_REQUIRE_CROSS_CHECKS=true}, so a build that sets the
 * variable is green only when the cross-check ran.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("git verify-commit compatibility (GDR-STORE-14)")
class GitVerifyCommitCompatibilityTest {

    private static final Pattern VERSION = Pattern.compile("git version (\\d+)\\.(\\d+)");

    /** The committer time of the signed commit: 2020-06-01T08:00:00Z, years before any run of the test. */
    private static final Instant COMMIT_TIME = Instant.parse("2020-06-01T08:00:00Z");

    /**
     * A validity bound as {@code ssh-keygen} reads it: a day without the suffix {@code Z}, which OpenSSH 8.8 and 8.9
     * refuse, so in local time; every bound lies days away from the commit time.
     */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuuMMdd").withZone(ZoneOffset.UTC);

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    /**
     * Runs Git in an isolated environment: a temporary home and XDG configuration directory, an empty global
     * configuration file, no system configuration and no inherited {@code GIT_*} variable.
     */
    private ExternalTools.Finished git(List<String> arguments) throws IOException, InterruptedException {
        var home = Files.createDirectories(temp.resolve("home"));
        var xdg = Files.createDirectories(temp.resolve("xdg"));
        var global = temp.resolve("gitconfig");
        if (!Files.exists(global)) {
            Files.createFile(global);
        }
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(arguments);
        var builder = new ProcessBuilder(command).directory(home.toFile());
        Map<String, String> environment = builder.environment();
        environment.keySet().removeIf(name -> name.startsWith("GIT_"));
        environment.put("HOME", home.toString());
        environment.put("XDG_CONFIG_HOME", xdg.toString());
        environment.put("GIT_CONFIG_GLOBAL", global.toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        return ExternalTools.run(builder, Files.createTempFile(temp, "git", ".out"), null);
    }

    /** The {@code ssh-keygen} Git is to use, after the assumptions on both tools held. */
    private Path assumeToolsPresent() throws IOException, InterruptedException {
        Optional<Path> git = ExternalTools.onPath("git");
        ExternalTools.assumeCrossCheck(git.isPresent(), "git is not on the path");
        var version = git(List.of("--version"));
        var matcher = VERSION.matcher(version.output());
        ExternalTools.assumeCrossCheck(matcher.find(), "git does not report its version");
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        ExternalTools.assumeCrossCheck(major > 2 || major == 2 && minor >= 35, "git is older than 2.35");
        Optional<Path> keygen = ExternalTools.onPath("ssh-keygen");
        ExternalTools.assumeCrossCheck(keygen.isPresent(), "ssh-keygen is not on the path");
        ExternalTools.assumeCrossCheck(ExternalTools.verifiesWithLifetimes(keygen.get(), temp),
                "ssh-keygen does not find principals or honour key lifetimes at a verify time");
        return keygen.get();
    }

    /** Saves one signed commit onto {@code main} of the remote at {@link #COMMIT_TIME}. */
    private record Signed(String commitId, TestKeys.Ed25519 key) {
    }

    private Signed saveSigned(LocalRemote remote) {
        var source = TestKeys.ed25519().source();
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(30).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(5).toString()));
        tree.put("signing", Map.of("key", word()));
        var configuration = value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
        var key = value(DocumentKey.of(value(DocumentCollection.of("c" + word(), Kind.MAIN)), "R-" + word()));
        var author = Author.of("p-" + word());
        var claims = new IdentityClaims("https://issuer.example/realms/test", COMMIT_TIME, "1", List.of("pwd"));
        try (var writer = value(StoreWriter.open(new StoreRepository(word(), remote.uri().toString(), word(), null),
                     temp.resolve(word() + ".git"),
                     new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word())),
                     configuration, List.of(), source, new JGitRepositoryOperations(RemotePolicy.localOnly()),
                     Clock.fixed(COMMIT_TIME, ZoneOffset.UTC)))) {
            value(writer.draft(author, key, List.of(new TreeEdit.Put(word(), word().getBytes(StandardCharsets.UTF_8))),
                    word()));
            var saved = assertInstanceOf(SaveResult.Saved.class, writer.save(author, claims, key, Map.of(), word()));
            return new Signed(saved.commitId(), source.key());
        }
    }

    private ExternalTools.Finished verifyCommit(LocalRemote remote, Path keygen, Path allowedSigners,
            String commitId) throws IOException, InterruptedException {
        return git(List.of("--git-dir=" + remote.repository().getDirectory(), "-c", "gpg.format=ssh", "-c",
                "gpg.ssh.program=" + keygen, "-c", "gpg.ssh.allowedSignersFile=" + allowedSigners, "verify-commit",
                commitId));
    }

    @Test
    @DisplayName("GDR-STORE-14: git verify-commit accepts a store-signed commit and honours its validity at commit time")
    void gitVerifyCommitAcceptsStoreSignature(@Remote LocalRemote remote) throws Exception {
        var keygen = assumeToolsPresent();
        var signed = saveSigned(remote);
        var principal = word() + "@example.org";
        var validAfter = DAY.format(COMMIT_TIME.minus(Duration.ofDays(30)));
        var validBefore = DAY.format(COMMIT_TIME.plus(Duration.ofDays(30)));
        var valid = Files.writeString(temp.resolve("valid"), principal + " valid-after=\"" + validAfter
                + "\",valid-before=\"" + validBefore + "\" " + signed.key().publicKeyLine() + "\n");
        var expiredBefore = DAY.format(COMMIT_TIME.minus(Duration.ofDays(3)));
        var expired = Files.writeString(temp.resolve("expired"), principal + " valid-before=\"" + expiredBefore
                + "\" " + signed.key().publicKeyLine() + "\n");

        var accepted = verifyCommit(remote, keygen, valid, signed.commitId());
        var rejected = verifyCommit(remote, keygen, expired, signed.commitId());

        assertAll(
                () -> assertEquals(0, accepted.code(), () -> "accepted: " + accepted.output()),
                () -> assertNotEquals(0, rejected.code(), () -> "rejected: " + rejected.output()));
    }

    @Test
    @DisplayName("GDR-STORE-14: a cross-check that cannot run logs its reason and is aborted, never passed silently")
    void skippedCrossCheckIsLogged() {
        var reason = word() + " is not on the path";

        var aborted = assertThrows(TestAbortedException.class,
                () -> ExternalTools.assumeCrossCheck(false, reason, null));

        assertAll(
                () -> assertTrue(aborted.getMessage().contains(reason), "the report names the reason"),
                () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                        ExternalTools.CROSS_CHECK_SKIPPED.resolveIdentifierString()),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, reason));
    }

    @Test
    @DisplayName("GDR-STORE-14: under GIT_STORE_REQUIRE_CROSS_CHECKS=true a cross-check that cannot run fails")
    void requiredCrossCheckFails() {
        var reason = word() + " is not on the path";

        var failed = assertThrows(AssertionFailedError.class,
                () -> ExternalTools.assumeCrossCheck(false, reason, "true"));

        assertAll(
                () -> assertTrue(failed.getMessage().contains(ExternalTools.REQUIRE_CROSS_CHECKS),
                        "the failure names the variable"),
                () -> assertTrue(failed.getMessage().contains(reason), "and the reason"),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, reason));
    }

    @Test
    @DisplayName("GDR-STORE-14: a cross-check whose tools are present runs, whatever the variable says")
    void presentToolsRun() {
        assertAll(
                () -> assertDoesNotThrow(() -> ExternalTools.assumeCrossCheck(true, word(), null), "not set"),
                () -> assertDoesNotThrow(() -> ExternalTools.assumeCrossCheck(true, word(), "true"), "required"),
                () -> assertEquals(List.of(), TestLoggerFactory.getTestHandler().getRecords().stream()
                        .filter(logRecord -> logRecord.getLevel() == Level.WARNING).toList(), "nothing is logged"));
    }
}
