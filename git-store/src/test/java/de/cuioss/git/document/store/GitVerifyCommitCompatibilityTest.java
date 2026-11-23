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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;


import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cross-check of GDR-STORE-14 with a stock Git installation: {@code git verify-commit} with
 * {@code gpg.format=ssh} and {@code gpg.ssh.allowedSignersFile}
 * (<a href="https://git-scm.com/docs/git-verify-commit">git-verify-commit(1)</a>) accepts a commit the store signed,
 * and rejects it with an allowed-signers file whose {@code valid-before} lies before the committer time, which Git
 * passes to {@code ssh-keygen} since Git 2.35. The test runs only when {@code git --version} reports 2.35 or later and
 * {@code ssh-keygen} can be started (JUnit assumptions, skipped otherwise); the processes run with a temporary
 * {@code HOME} and {@code GIT_CONFIG_NOSYSTEM=1}, without network.
 */
@EnableGeneratorController
@DisplayName("git verify-commit compatibility (GDR-STORE-14)")
class GitVerifyCommitCompatibilityTest {

    private static final Pattern VERSION = Pattern.compile("git version (\\d+)\\.(\\d+)");

    /** The committer time of the signed commit: 2027-01-15T08:00:00Z, far from every validity bound. */
    private static final Instant COMMIT_TIME = Instant.ofEpochSecond(1_800_000_000L);

    private static final long PROCESS_SECONDS = 60;

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    /** The exit code and output of a finished process. */
    private record Finished(int code, String output) {
    }

    private Finished execute(List<String> command) throws IOException, InterruptedException {
        var home = Files.createDirectories(temp.resolve("home"));
        var builder = new ProcessBuilder(command).redirectErrorStream(true).directory(home.toFile());
        builder.environment().put("HOME", home.toString());
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().remove("GIT_DIR");
        Process process = builder.start();
        process.getOutputStream().close();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(PROCESS_SECONDS, TimeUnit.SECONDS), "the process ends");
        return new Finished(process.exitValue(), output);
    }

    /** Whether a program can be started at all. */
    private boolean startable(String... command) {
        try {
            execute(List.of(command));
            return true;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void assumeToolsPresent() throws InterruptedException {
        Finished version;
        try {
            version = execute(List.of("git", "--version"));
        } catch (IOException e) {
            assumeTrue(false, "git is not installed");
            return;
        }
        var matcher = VERSION.matcher(version.output());
        assumeTrue(matcher.find(), "git reports its version");
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        assumeTrue(major > 2 || major == 2 && minor >= 35, "git 2.35 or later");
        assumeTrue(startable("ssh-keygen", "-?"), "ssh-keygen is installed");
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
                     configuration, source, new JGitRepositoryOperations(RemotePolicy.localOnly()),
                     Clock.fixed(COMMIT_TIME, ZoneOffset.UTC)))) {
            value(writer.draft(author, key, List.of(new TreeEdit.Put(word(), word().getBytes(StandardCharsets.UTF_8))),
                    word()));
            var saved = assertInstanceOf(SaveResult.Saved.class, writer.save(author, claims, key, Map.of(), word()));
            return new Signed(saved.commitId(), source.key());
        }
    }

    private Finished verifyCommit(LocalRemote remote, Path allowedSigners, String commitId)
            throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "--git-dir=" + remote.repository().getDirectory(),
                "-c", "gpg.format=ssh", "-c", "gpg.ssh.allowedSignersFile=" + allowedSigners, "verify-commit",
                commitId));
        return execute(command);
    }

    @Test
    @DisplayName("GDR-STORE-14: git verify-commit accepts a store-signed commit and honours valid-before")
    void gitVerifyCommitAcceptsStoreSignature(@Remote LocalRemote remote) throws Exception {
        assumeToolsPresent();
        var signed = saveSigned(remote);
        var principal = word() + "@example.org";
        var valid = Files.writeString(temp.resolve("valid"), principal + " " + signed.key().publicKeyLine() + "\n");
        var expiredBefore = DateTimeFormatter.ofPattern("uuuuMMdd").withZone(ZoneOffset.UTC)
                .format(COMMIT_TIME.minus(Duration.ofDays(3)));
        var expired = Files.writeString(temp.resolve("expired"), principal + " valid-before=\"" + expiredBefore
                + "\" " + signed.key().publicKeyLine() + "\n");

        var accepted = verifyCommit(remote, valid, signed.commitId());
        var rejected = verifyCommit(remote, expired, signed.commitId());

        assertAll(
                () -> assertEquals(0, accepted.code(), () -> "accepted: " + accepted.output()),
                () -> assertNotEquals(0, rejected.code(), () -> "rejected: " + rejected.output()));
    }
}
