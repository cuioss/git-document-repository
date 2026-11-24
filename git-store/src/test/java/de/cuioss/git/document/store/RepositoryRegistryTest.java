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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The repository registry (GDR-STORE-1): a valid registry under an HTTPS origin and under the local-only policy, every
 * refusal of {@link RepositoryRegistry#of(List, RemotePolicy)} with a detail naming the alias or the position and never
 * the remote (GDR-NFR-3), the lookup by alias and the retention period, and the rendering of a remote as scheme, host,
 * port and path without user, password, query and fragment (GDR-NFR-3).
 * <p>
 * Aliases, confidentiality classes and path segments are generated; the scheme {@code https}, the HTTPS origin
 * {@value #ORIGIN}, the user information, the query key {@code token} and the separators of a remote are URL vocabulary
 * and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("RepositoryRegistry (GDR-STORE-1)")
class RepositoryRegistryTest {

    /** The HTTPS origin the policy of most tests admits. */
    private static final String ORIGIN = "https://git.example";

    /** Valid aliases: lower-case letters, digits and hyphens, unique within one test. */
    private static final TypedGenerator<String> ALIASES =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz0123456789-", 1, 20));

    private static final RemotePolicy HTTPS = RemotePolicy.httpsOrigin(URI.create(ORIGIN));

    private static String alias() {
        return ALIASES.next();
    }

    private static String word() {
        return Generators.letterStrings(4, 16).next();
    }

    /**
     * A credential marker of a user or a password: a generated word between tildes. No alias, generated word, host or
     * fixed text of a detail or a rendering contains a tilde, so the marker cannot occur in the output that is allowed
     * to survive.
     */
    private static String credential() {
        return "~" + word() + "~";
    }

    /** A remote under the admitted origin whose port lies beyond the range of {@code int}. */
    private static String remoteWithPortBeyondInt() {
        return ORIGIN + ":" + (Integer.MAX_VALUE + 1L) + "/" + word() + ".git";
    }

    /** An HTTPS remote under the admitted origin. */
    private static String httpsRemote() {
        return ORIGIN + "/" + word() + "/" + word() + ".git";
    }

    private static StoreRepository repository(String alias) {
        return new StoreRepository(alias, httpsRemote(), word(), null);
    }

    private static StoreRepository repository(String alias, String remote) {
        return new StoreRepository(alias, remote, word(), null);
    }

    private static RepositoryRegistry registry(StoreResult<RepositoryRegistry> result) {
        assertTrue(result.isOk(), () -> "the registry is accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the registry");
    }

    /** Asserts a {@link StoreOutcome#MALFORMED} refusal with exactly the given detail. */
    private static void assertMalformed(String detail, StoreResult<RepositoryRegistry> result) {
        assertEquals(StoreResult.of(StoreOutcome.MALFORMED, detail), result, "the registry is refused");
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> T nullValue() {
        // the null literal converts to every reference type, so no unchecked cast is needed
        return null;
    }

    @Nested
    @DisplayName("Valid registry")
    class Valid {

        @Test
        @DisplayName("GDR-STORE-1: HTTPS remotes under the HTTPS origin are registered in order")
        void httpsRegistry() {
            var first = repository(alias());
            var second = repository(alias());
            var third = repository(alias());

            var registry = registry(RepositoryRegistry.of(List.of(first, second, third), HTTPS));

            assertEquals(List.of(first, second, third), registry.repositories(),
                    "every repository is kept in registration order");
        }

        @Test
        @DisplayName("GDR-STORE-1: a file URL and a plain path of a local bare repository are registered under localOnly")
        void localRegistry(@Remote LocalRemote remote) {
            var url = repository(alias(), remote.uri().toString());
            var path = repository(alias(), remote.repository().getDirectory().getAbsolutePath());

            var registry = registry(RepositoryRegistry.of(List.of(url, path), RemotePolicy.localOnly()));

            assertEquals(List.of(url, path), registry.repositories(), "both local remotes are registered");
        }

        @Test
        @DisplayName("GDR-STORE-1: an empty list is an empty registry")
        void emptyRegistry() {
            var registry = registry(RepositoryRegistry.of(List.of(), HTTPS));

            assertTrue(registry.repositories().isEmpty(), "the registry holds no repository");
        }

        @Test
        @DisplayName("GDR-STORE-1: the repositories of the registry cannot be changed")
        void repositoriesAreUnmodifiable() {
            var input = new ArrayList<>(List.of(repository(alias())));
            var registry = registry(RepositoryRegistry.of(input, HTTPS));
            var other = repository(alias());

            input.add(other);
            var repositories = registry.repositories();

            assertAll(
                    () -> assertEquals(1, repositories.size(), "a later change of the input is not seen"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> repositories.add(other),
                            "the returned list is unmodifiable"));
        }
    }

    @Nested
    @DisplayName("Refusal")
    class Refusal {

        @ParameterizedTest(name = "alias [{0}]")
        @ValueSource(strings = {"", "Register", "risk_register", "risk.register", "risk register", "räte", "risk/x"})
        @DisplayName("GDR-STORE-1: an alias outside [a-z0-9-]+ is malformed, named by its position")
        void invalidAlias(String invalid) {
            var result = RepositoryRegistry.of(List.of(repository(alias()), repository(invalid)), HTTPS);

            assertMalformed("repositories[1]: invalid alias", result);
        }

        @Test
        @DisplayName("GDR-STORE-1: a duplicate alias is malformed")
        void duplicateAlias() {
            var alias = alias();

            var result = RepositoryRegistry.of(List.of(repository(alias), repository(alias)), HTTPS);

            assertMalformed("repository " + alias + ": duplicate alias", result);
        }

        @ParameterizedTest(name = "confidentiality [{0}]")
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("GDR-STORE-1: a blank confidentiality class is malformed")
        void blankConfidentiality(String blank) {
            var alias = alias();

            var result = RepositoryRegistry.of(List.of(new StoreRepository(alias, httpsRemote(), blank, null)),
                    HTTPS);

            assertMalformed("repository " + alias + ": blank confidentiality", result);
        }

        /**
         * JGit 7.8.0 {@code URIish(String)} throws {@code URISyntaxException} for an empty string ("The uri was empty
         * or null") before any pattern is tried; the test observes that refusal through the registry.
         */
        @Test
        @DisplayName("GDR-STORE-1: a remote that is no Git URL is malformed")
        void malformedRemote() {
            var alias = alias();

            var result = RepositoryRegistry.of(List.of(repository(alias, "")), HTTPS);

            assertMalformed("repository " + alias + ": malformed remote", result);
        }

        /**
         * JGit 7.8.0 {@code URIish(String)} matches the port as a run of digits and converts it with
         * {@code Integer.parseInt}, so a port beyond the range of {@code int} throws {@code NumberFormatException}
         * instead of {@code URISyntaxException}. The registry refuses it as malformed whichever of the two JGit throws.
         */
        @Test
        @DisplayName("GDR-STORE-1, GDR-NFR-3: a remote with a port beyond the range of int is malformed")
        void portBeyondIntRange() {
            var alias = alias();
            var remote = remoteWithPortBeyondInt();

            var result = RepositoryRegistry.of(List.of(repository(alias, remote)), HTTPS);

            assertMalformed("repository " + alias + ": malformed remote", result);
        }

        @Test
        @DisplayName("GDR-STORE-1, GDR-NFR-3: a remote with user information is malformed, the detail carries neither")
        void remoteWithUserInformation() {
            var alias = alias();
            var user = credential();
            var password = credential();
            var withPassword = "https://" + user + ":" + password + "@git.example/" + word() + ".git";
            var withUser = "https://" + user + "@git.example/" + word() + ".git";

            assertAll(Stream.of(withPassword, withUser).<Executable>map(remote -> () -> {
                var result = RepositoryRegistry.of(List.of(repository(alias, remote)), HTTPS);
                var detail = Objects.requireNonNull(result.detail());
                assertAll(
                        () -> assertMalformed("repository " + alias + ": remote carries user information", result),
                        () -> assertFalse(detail.contains(user), "the detail carries no user"),
                        () -> assertFalse(detail.contains(password), "the detail carries no password"));
            }));
        }

        @Test
        @DisplayName("GDR-STORE-1: a remote of another host is refused by the policy, passed on")
        void remoteOfAnotherHost() {
            var alias = alias();
            var host = word().toLowerCase(Locale.ROOT) + ".example";
            var remote = "https://" + host + "/" + word() + ".git";

            var result = RepositoryRegistry.of(List.of(repository(alias, remote)), HTTPS);

            assertAll(
                    () -> assertEquals(new StoreResult.PassedOn(GitOutcome.REFUSED_BY_POLICY), result.outcome(),
                            "the refusal of the policy is passed on"),
                    () -> assertEquals("repository " + alias + ": remote not permitted by the remote policy",
                            result.detail(), "the detail names the alias"),
                    () -> assertFalse(Objects.requireNonNull(result.detail()).contains(host),
                            "the detail carries no part of the remote"));
        }

        @Test
        @DisplayName("GDR-STORE-1: an HTTPS remote under localOnly is the passed-on REFUSED_BY_POLICY")
        void httpsRemoteUnderLocalOnly() {
            var alias = alias();

            var result = RepositoryRegistry.of(List.of(repository(alias)), RemotePolicy.localOnly());

            assertAll(
                    () -> assertEquals(new StoreResult.PassedOn(GitOutcome.REFUSED_BY_POLICY), result.outcome(),
                            "the refusal of the policy is passed on"),
                    () -> assertEquals("repository " + alias + ": remote not permitted by the remote policy",
                            result.detail(), "the detail names the alias"),
                    () -> assertNull(result.value(), "a refusal carries no registry"));
        }

        @Test
        @DisplayName("GDR-STORE-1: the first refusal wins")
        void firstRefusalWins() {
            var alias = alias();

            var result = RepositoryRegistry.of(
                    List.of(repository(alias), repository(alias), repository("Invalid")), HTTPS);

            assertMalformed("repository " + alias + ": duplicate alias", result);
        }

        @Test
        @DisplayName("GDR-STORE-1: null arguments and null elements are refused")
        void refusesNull() {
            List<StoreRepository> nullList = nullValue();
            RemotePolicy nullPolicy = nullValue();
            List<StoreRepository> empty = List.of();
            List<StoreRepository> withNull = new ArrayList<>();
            withNull.add(nullValue());

            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> RepositoryRegistry.of(nullList, HTTPS),
                            "a null list"),
                    () -> assertThrows(NullPointerException.class, () -> RepositoryRegistry.of(empty, nullPolicy),
                            "a null policy"),
                    () -> assertThrows(NullPointerException.class, () -> RepositoryRegistry.of(withNull, HTTPS),
                            "a null element"));
        }
    }

    @Nested
    @DisplayName("Lookup and retention")
    class Lookup {

        @Test
        @DisplayName("GDR-STORE-1: find returns the repository of a known alias and nothing for an unknown one")
        void find() {
            var known = repository(alias());
            var registry = registry(RepositoryRegistry.of(List.of(repository(alias()), known), HTTPS));

            assertAll(
                    () -> assertEquals(Optional.of(known), registry.find(known.alias()), "a known alias"),
                    () -> assertEquals(Optional.empty(), registry.find(alias()), "an unknown alias"));
        }

        @Test
        @DisplayName("GDR-STORE-1: the retention period is kept when present and absent when not configured")
        void retention() {
            var retention = Period.ofYears(Generators.integers(1, 30).next());
            var kept = new StoreRepository(alias(), httpsRemote(), word(), retention);
            var none = repository(alias());

            var registry = registry(RepositoryRegistry.of(List.of(kept, none), HTTPS));

            assertAll(
                    () -> assertEquals(Optional.of(retention),
                            registry.find(kept.alias()).map(StoreRepository::retention), "a retention period"),
                    () -> assertNull(registry.find(none.alias()).orElseThrow().retention(), "no retention period"));
        }
    }

    @Nested
    @DisplayName("Rendering")
    class Rendering {

        @Test
        @DisplayName("GDR-NFR-3: a repository renders its remote without user information")
        void rendersWithoutUserInformation() {
            var user = credential();
            var password = credential();
            var path = word();
            var repository = repository(alias(), "https://" + user + ":" + password + "@git.example/" + path + ".git");

            var rendered = repository.toString();

            assertAll(
                    () -> assertTrue(rendered.contains("https://git.example/" + path + ".git"),
                            "the remote is rendered without user information"),
                    () -> assertFalse(rendered.contains(user), "no user"),
                    () -> assertFalse(rendered.contains(password), "no password"));
        }

        @Test
        @DisplayName("GDR-NFR-3: a repository renders its remote without query and fragment")
        void rendersWithoutQueryAndFragment() {
            var token = credential();
            var anchor = credential();
            var path = word();
            var expected = "remote=" + ORIGIN + "/" + path + ".git,";
            var withQuery = repository(alias(), ORIGIN + "/" + path + ".git?token=" + token);
            var withFragment = repository(alias(), ORIGIN + "/" + path + ".git#" + anchor);
            var withBoth = repository(alias(), ORIGIN + "/" + path + ".git?token=" + token + "#" + anchor);

            assertAll(Stream.of(withQuery, withFragment, withBoth).<Executable>map(repository -> () -> {
                var rendered = repository.toString();
                assertAll(
                        () -> assertTrue(rendered.contains(expected), () -> "scheme, host and path: " + rendered),
                        () -> assertFalse(rendered.contains(token), "no query"),
                        () -> assertFalse(rendered.contains(anchor), "no fragment"),
                        () -> assertFalse(rendered.contains("?"), "no query separator"),
                        () -> assertFalse(rendered.contains("#"), "no fragment separator"));
            }));
        }

        @Test
        @DisplayName("GDR-NFR-3: a remote without a slash before its query or fragment renders without either")
        void rendersWithoutQueryAndFragmentAfterTheHost() {
            var token = credential();
            var anchor = credential();
            var expected = "remote=" + ORIGIN + ",";
            var withQuery = repository(alias(), ORIGIN + "?token=" + token);
            var withFragment = repository(alias(), ORIGIN + "#" + anchor);

            assertAll(Stream.of(withQuery, withFragment).<Executable>map(repository -> () -> {
                var rendered = repository.toString();
                assertAll(
                        () -> assertTrue(rendered.contains(expected), () -> "scheme and host: " + rendered),
                        () -> assertFalse(rendered.contains(token), "no query"),
                        () -> assertFalse(rendered.contains(anchor), "no fragment"));
            }));
        }

        @Test
        @DisplayName("GDR-NFR-3: a question mark in a password does not decide the cut, no part of it is rendered")
        void questionMarkInPasswordRendersNoSecret() {
            var user = credential();
            var before = credential();
            var after = credential();
            var path = word();
            var repository = repository(alias(),
                    "https://" + user + ":" + before + "?" + after + "@git.example/" + path + ".git");

            var rendered = repository.toString();

            assertAll(
                    () -> assertTrue(rendered.contains("remote=https://git.example/" + path + ".git,"),
                            () -> "scheme, host and path: " + rendered),
                    () -> assertFalse(rendered.contains(user), "no user"),
                    () -> assertFalse(rendered.contains(before), "no part of the password before the question mark"),
                    () -> assertFalse(rendered.contains(after), "no part of the password after the question mark"));
        }

        @Test
        @DisplayName("GDR-NFR-3: an encoded delimiter belongs to the path and stays")
        void encodedDelimiterStays() {
            var path = word();
            var repository = repository(alias(), ORIGIN + "/" + path + "%3F" + path + ".git");

            assertTrue(repository.toString().contains("remote=" + ORIGIN + "/" + path + "%3F" + path + ".git,"),
                    repository::toString);
        }

        @Test
        @DisplayName("GDR-NFR-3: a repository renders the port of its remote and none of user, password and query")
        void rendersPortWithoutSecrets() {
            var user = credential();
            var password = credential();
            var token = credential();
            var port = Generators.integers(1, 65_535).next();
            var path = word();
            var repository = repository(alias(),
                    "https://" + user + ":" + password + "@git.example:" + port + "/" + path + ".git?token=" + token);

            var rendered = repository.toString();

            assertAll(
                    () -> assertTrue(rendered.contains("remote=https://git.example:" + port + "/" + path + ".git,"),
                            () -> "scheme, host, port and path: " + rendered),
                    () -> assertFalse(rendered.contains(user), "no user"),
                    () -> assertFalse(rendered.contains(password), "no password"),
                    () -> assertFalse(rendered.contains(token), "no query"));
        }

        @Test
        @DisplayName("GDR-NFR-3: the record of a refused start carries no user, password or query of a remote")
        void startRefusedRecordCarriesNoSecret() {
            var user = credential();
            var password = credential();
            var token = credential();
            Map<String, Object> repository = new LinkedHashMap<>();
            repository.put("alias", alias());
            repository.put("remote", "https://" + user + ":" + password + "@git.example/" + word() + ".git?token="
                    + token);
            repository.put("confidentiality", word());
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("save", Map.of("max-attempts", 1));
            tree.put("drafts", Map.of("expiry", "P1D"));
            tree.put("fetch", Map.of("interval", "PT1M"));
            tree.put("repositories", List.of(repository));

            var result = StoreConfiguration.from(tree, HTTPS);

            var records = TestLoggerFactory.getTestHandler().getRecords();
            assertAll(
                    () -> assertEquals(StoreOutcome.MALFORMED, result.outcome(), "the start is refused"),
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                            GitStoreLogMessages.WARN.START_REFUSED.resolveIdentifierString()),
                    () -> assertTrue(records.stream().map(logRecord -> String.valueOf(logRecord.getMessage()))
                                    .noneMatch(message -> message.contains(user) || message.contains(password)
                                            || message.contains(token)),
                            "no log record carries a user, a password or a query"),
                    () -> assertTrue(records.stream().allMatch(logRecord -> logRecord.getThrown() == null),
                            "no throwable is attached"));
        }

        @Test
        @DisplayName("GDR-NFR-3: an unparsable remote is rendered as (malformed)")
        void rendersMalformedRemote() {
            assertTrue(repository(alias(), "").toString().contains("remote=(malformed)"),
                    "the remote is replaced");
        }

        @Test
        @DisplayName("GDR-NFR-3: a remote with a port beyond the range of int is rendered as (malformed)")
        void rendersRemoteWithPortBeyondInt() {
            var repository = repository(alias(), remoteWithPortBeyondInt());

            var rendered = repository.toString();

            assertTrue(rendered.contains("remote=(malformed)"), "the remote is replaced, the rendering does not fail");
        }

        @Test
        @DisplayName("GDR-STORE-1: the registry renders its repositories")
        void rendersRegistry() {
            var repository = repository(alias());
            var registry = registry(RepositoryRegistry.of(List.of(repository), HTTPS));

            assertEquals("RepositoryRegistry[" + repository + "]", registry.toString(), "the rendering");
        }
    }
}
