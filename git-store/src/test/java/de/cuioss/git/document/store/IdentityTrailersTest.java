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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The identity trailers of GDR-STORE-13: the exact trailer block {@link IdentityTrailers} appends for full claims and
 * for absent {@code acr}, absent {@code amr} and both absent, {@code Auth-Time} in UTC with whole seconds, the checks
 * {@link IdentityClaims} applies per component without naming the refused value, and, against a {@code git-testing}
 * local remote, the trailers on save and release commits only, never on a draft commit, with no {@code sub}, no
 * {@code sid} and no address in a trailer or a commit header.
 * <p>
 * Pseudonyms, messages, issuers and times are generated; the trailer tokens, the trailer layout and the details are the
 * specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@DisplayName("Identity trailers (GDR-STORE-13)")
class IdentityTrailersTest {

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-", 8, 24).next();
    }

    private static String issuer() {
        return "https://" + word() + ".example/realms/" + word();
    }

    /** A time in whole seconds. */
    private static Instant time() {
        return Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static String append(String message, String pseudonym, IdentityClaims claims) {
        return value(IdentityTrailers.append(message, pseudonym, claims));
    }

    @Nested
    @DisplayName("Trailer block")
    class Block {

        @Test
        @DisplayName("GDR-STORE-13: full claims give the four trailers after a blank line")
        void fullClaims() {
            var message = word() + " " + word();
            var pseudonym = pseudonym();
            var issuer = issuer();
            var claims = new IdentityClaims(issuer, Instant.parse("2026-02-03T04:05:06Z"), "urn:example:acr:mfa",
                    List.of("pwd", "otp", "hwk"));

            assertEquals(message + "\n\n"
                    + "Actor: " + pseudonym + "\n"
                    + "Actor-Issuer: " + issuer + "\n"
                    + "Auth-Time: 2026-02-03T04:05:06Z\n"
                    + "Auth-Context: acr=urn:example:acr:mfa; amr=pwd,otp,hwk\n",
                    append(message, pseudonym, claims), "the exact trailer block");
        }

        @Test
        @DisplayName("GDR-STORE-13: an absent acr leaves out its part of Auth-Context")
        void absentAcr() {
            var message = word();
            var pseudonym = pseudonym();
            var issuer = issuer();
            var claims = new IdentityClaims(issuer, Instant.parse("2026-02-03T04:05:06Z"), null, List.of("pwd"));

            assertEquals(message + "\n\nActor: " + pseudonym + "\nActor-Issuer: " + issuer
                    + "\nAuth-Time: 2026-02-03T04:05:06Z\nAuth-Context: amr=pwd\n",
                    append(message, pseudonym, claims), "only amr");
        }

        @Test
        @DisplayName("GDR-STORE-13: an absent amr leaves out its part of Auth-Context")
        void absentAmr() {
            var message = word();
            var pseudonym = pseudonym();
            var issuer = issuer();
            var claims = new IdentityClaims(issuer, Instant.parse("2026-02-03T04:05:06Z"), "1", List.of());

            assertEquals(message + "\n\nActor: " + pseudonym + "\nActor-Issuer: " + issuer
                    + "\nAuth-Time: 2026-02-03T04:05:06Z\nAuth-Context: acr=1\n",
                    append(message, pseudonym, claims), "only acr");
        }

        @Test
        @DisplayName("GDR-STORE-13: without acr and amr there is no Auth-Context trailer")
        void absentAcrAndAmr() {
            var message = word();
            var pseudonym = pseudonym();
            var issuer = issuer();
            var claims = new IdentityClaims(issuer, Instant.parse("2026-02-03T04:05:06Z"), null, List.of());

            assertEquals(message + "\n\nActor: " + pseudonym + "\nActor-Issuer: " + issuer
                    + "\nAuth-Time: 2026-02-03T04:05:06Z\n", append(message, pseudonym, claims), "three trailers");
        }

        @Test
        @DisplayName("GDR-STORE-13: Auth-Time is written in UTC with whole seconds")
        void authTimeInUtcWithSeconds() {
            var seconds = time();
            var withFraction = seconds.plusNanos(Generators.integers(1, 999_999_999).next());
            var offset = ZoneOffset.ofHours(Generators.integers(-12, 14).next());
            var claims = new IdentityClaims(issuer(),
                    OffsetDateTime.ofInstant(withFraction, offset).toInstant(), null, List.of());

            var trailers = append(word(), pseudonym(), claims);

            assertTrue(trailers.contains("\nAuth-Time: " + seconds + "\n"), () -> "UTC, whole seconds: " + trailers);
        }

        @Test
        @DisplayName("GDR-STORE-13: the trailing line breaks of the message are stripped before the blank line")
        void trailingLineBreaksStripped() {
            var message = word() + "\n\n" + word();
            var pseudonym = pseudonym();

            var composed = append(message + "\r\n\n\n", pseudonym, new IdentityClaims(issuer(), time(), null,
                    List.of()));

            assertTrue(composed.startsWith(message + "\n\nActor: " + pseudonym + "\n"),
                    () -> "one blank line before the trailers: " + composed);
        }

        @Test
        @DisplayName("GDR-STORE-13: a pseudonym outside [A-Za-z0-9-]+ is MALFORMED without naming it")
        void invalidPseudonym() {
            var pseudonym = word() + "\n" + word();

            var result = IdentityTrailers.append(word(), pseudonym, new IdentityClaims(issuer(), time(), null,
                    List.of()));

            assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "pseudonym: invalid"), result, "malformed");
        }
    }

    @Nested
    @DisplayName("Claims")
    class Claims {

        @ParameterizedTest(name = "issuer with {0}")
        @ValueSource(strings = {"line\nbreak", "carriage\rreturn", "tab\tchar", "space char", "nul\u0000char", " "})
        @DisplayName("GDR-STORE-13: an issuer with whitespace or a control character, or blank, is refused")
        void refusesIssuer(String issuer) {
            var authTime = time();
            List<String> amr = List.of();
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> new IdentityClaims(issuer, authTime, null, amr));

            assertEquals("issuer: invalid", thrown.getMessage(), "names the component only");
        }

        @ParameterizedTest(name = "acr with {0}")
        @ValueSource(strings = {"line\nbreak", "carriage\rreturn", "nul\u0000char", "semi;colon", " "})
        @DisplayName("GDR-STORE-13: an acr with a control character or the separator ;, or blank, is refused")
        void refusesAcr(String acr) {
            var issuer = issuer();
            var authTime = time();
            List<String> amr = List.of();
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> new IdentityClaims(issuer, authTime, acr, amr));

            assertEquals("acr: invalid", thrown.getMessage(), "names the component only");
        }

        @ParameterizedTest(name = "amr with {0}")
        @ValueSource(strings = {"line\nbreak", "tab\tchar", "space char", "com,ma", "semi;colon", "nul\u0000char",
                ""})
        @DisplayName("GDR-STORE-13: an amr with whitespace, a control character or a separator, or blank, is refused")
        void refusesAmr(String method) {
            var issuer = issuer();
            var authTime = time();
            List<String> amr = List.of("pwd", method);
            var thrown = assertThrows(IllegalArgumentException.class,
                    () -> new IdentityClaims(issuer, authTime, null, amr));

            assertEquals("amr: invalid", thrown.getMessage(), "names the component only");
        }

        @ParameterizedTest(name = "of: {0}")
        @ValueSource(strings = {
                "issuer| |acr|pwd", "issuer|a\tb|acr|pwd", "issuer|a\nb|acr|pwd",
                "acr|ISSUER|semi;colon|pwd", "acr|ISSUER|com,ma|pwd", "acr|ISSUER|white space|pwd",
                "acr|ISSUER|nul\u0000char|pwd", "acr|ISSUER| |pwd",
                "amr|ISSUER|acr|semi;colon", "amr|ISSUER|acr|com,ma", "amr|ISSUER|acr|white space",
                "amr|ISSUER|acr|esc\u001Bape", "amr|ISSUER|acr| "})
        @DisplayName("GDR-STORE-13: of returns a token-derived value that fits no trailer line as MALFORMED")
        void ofReturnsMalformed(String scenario) {
            var parts = scenario.split("\\|", -1);
            var issuer = "ISSUER".equals(parts[1]) ? issuer() : parts[1];

            var result = IdentityClaims.of(issuer, time(), parts[2], List.of("otp", parts[3]));

            assertEquals(StoreResult.of(StoreOutcome.MALFORMED, parts[0] + ": invalid"), result,
                    "malformed, naming the component and never the value");
        }

        @Test
        @DisplayName("GDR-STORE-13: of returns well-formed claims as OK, equal to the checked constructor")
        void ofReturnsClaims() {
            var issuer = issuer();
            var authTime = time();
            List<String> amr = new ArrayList<>(List.of("pwd", "otp"));

            var result = IdentityClaims.of(issuer, authTime, "urn:example:acr:mfa", amr);
            amr.add("hwk");

            assertAll(
                    () -> assertEquals(StoreResult.ok(new IdentityClaims(issuer, authTime, "urn:example:acr:mfa",
                            List.of("pwd", "otp"))), result, "the claims"),
                    () -> assertEquals(StoreResult.ok(new IdentityClaims(issuer, authTime, null, List.of())),
                            IdentityClaims.of(issuer, authTime, null, List.of()), "absent acr and amr"));
        }

        @Test
        @DisplayName("GDR-STORE-13: the claims carry no sub, no sid and no address; amr is copied")
        void noSubjectSessionOrAddress() {
            List<String> amr = new ArrayList<>(List.of("pwd"));
            var claims = new IdentityClaims(issuer(), time(), null, amr);
            amr.add("otp");
            List<String> copied = claims.amr();

            assertAll(
                    () -> assertEquals(List.of("issuer", "authTime", "acr", "amr"),
                            Arrays.stream(IdentityClaims.class.getRecordComponents()).map(RecordComponent::getName)
                                    .toList(), "the components are the trailer claims only"),
                    () -> assertEquals(List.of("pwd"), claims.amr(), "a copy of amr"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> copied.add("otp"),
                            "an immutable amr"));
        }
    }

    @Nested
    @DisplayName("Commits")
    class Commits {

        private static StoreConfiguration configuration() {
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("save", Map.of("max-attempts", 3));
            tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
            tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
            tree.put("signing", Map.of("key", word()));
            return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
        }

        private static ObjectId commit(Repository repository, String ref, String path) throws IOException {
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                var tree = new TreeFormatter();
                tree.append(path, FileMode.REGULAR_FILE,
                        inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8)));
                var commit = new CommitBuilder();
                var ident = new PersonIdent(pseudonym(), "");
                commit.setTreeId(inserter.insert(tree));
                commit.setAuthor(ident);
                commit.setCommitter(ident);
                commit.setMessage(word());
                var id = inserter.insert(commit);
                inserter.flush();
                RefUpdate update = repository.updateRef(ref);
                update.setNewObjectId(id);
                update.setForceUpdate(true);
                assertEquals(RefUpdate.Result.NEW, update.update(), "the fixture reference is written");
                return id;
            }
        }

        private static RevCommit parse(Repository repository, String id) throws IOException {
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit commit = walk.parseCommit(ObjectId.fromString(id));
                walk.parseBody(commit);
                return commit;
            }
        }

        @Test
        @DisplayName("GDR-STORE-13: save and release commits carry the trailers, a draft commit none; no address")
        void trailersOnSaveAndReleaseOnly(@Remote LocalRemote remote) throws Exception {
            var key = value(DocumentKey.of(value(DocumentCollection.of("c" + word(), Kind.PER_DOCUMENT)),
                    Ulid.next()));
            commit(remote.repository(), StoreReferences.target(key), word());
            var author = new Author(pseudonym(), word() + "@" + word() + ".example");
            var issuer = issuer();
            var claims = new IdentityClaims(issuer, time(), "urn:example:acr:mfa", List.of("pwd"));
            var draftMessage = word();
            var saveMessage = word();
            var gitDir = temp.resolve(word() + ".git");
            var user = new TechnicalUser(word(), word() + "@" + word() + ".example",
                    new UsernamePasswordCredentialsProvider(word(), word()));
            var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);

            try (var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(), List.of(),
                         TestKeys.ed25519().source(), new JGitRepositoryOperations(RemotePolicy.localOnly()),
                         Clock.fixed(time(), ZoneOffset.UTC)))) {
                var draftId = value(writer.draft(author, key, List.of(new TreeEdit.Put(word() + "x",
                        word().getBytes(StandardCharsets.UTF_8))), draftMessage));
                var saved = assertInstanceOf(SaveResult.Saved.class,
                        writer.save(author, claims, key, Map.of(), saveMessage));
                var release = value(writer.release(author, claims, key));

                try (Repository clone = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true)
                             .build()) {
                    var draft = parse(clone, draftId);
                    var save = parse(remote.repository(), saved.commitId());
                    var released = parse(remote.repository(), release.commitId());
                    var expected = append(saveMessage, author.pseudonym(), claims);
                    assertAll(
                            () -> assertEquals(draftMessage, draft.getFullMessage(), "a draft carries no trailer"),
                            () -> assertEquals(expected, save.getFullMessage(), "the save carries the trailers"),
                            () -> assertEquals(append("Release " + key.collection().name() + "/" + key.id() + " v1",
                                    author.pseudonym(), claims), released.getFullMessage(),
                                    "the release carries the trailers"),
                            () -> assertTrue(save.getFullMessage().contains("\nActor-Issuer: " + issuer + "\n"),
                                    "the issuer"),
                            () -> {
                                for (RevCommit commit : List.of(draft, save, released)) {
                                    String raw = new String(commit.getRawBuffer(), StandardCharsets.UTF_8);
                                    assertFalse(raw.contains(Objects.requireNonNull(author.email())),
                                            "no author address in a header or a trailer");
                                    assertFalse(raw.contains(Objects.requireNonNull(user.email())),
                                            "no committer address in a header or a trailer");
                                }
                            });
                }
            }
        }
    }
}
