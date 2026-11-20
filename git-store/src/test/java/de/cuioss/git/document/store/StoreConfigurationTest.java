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

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.Level;
import java.util.stream.Stream;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.store.StoreConfiguration.Anchor;
import de.cuioss.git.document.store.StoreConfiguration.Directory;
import de.cuioss.git.document.store.StoreConfiguration.Drafts;
import de.cuioss.git.document.store.StoreConfiguration.Fetch;
import de.cuioss.git.document.store.StoreConfiguration.Save;
import de.cuioss.git.document.store.StoreConfiguration.Signing;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The typed configuration of the store (Configuration of {@code git-store}): a full tree, a minimal tree with every
 * documented default, every missing required key, every malformed typed key, unknown keys, the refusal of an anchor
 * key equal to the commit-signing key (GDR-STORE-18), and GDR-NFR-3: no refused value appears in a result detail or a
 * log record, and every refusal is logged once as {@code GIT_STORE-100}.
 * <p>
 * Values are generated; the configuration keys, the ISO 8601 texts of the documented defaults and the HTTPS origin
 * {@value #ORIGIN} are the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@DisplayName("StoreConfiguration (Configuration of git-store)")
class StoreConfigurationTest {

    /** The HTTPS origin the policy admits. */
    private static final String ORIGIN = "https://git.example";

    private static final RemotePolicy HTTPS = RemotePolicy.httpsOrigin(URI.create(ORIGIN));

    private static final TypedGenerator<String> ALIASES =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz", 4, 16));

    private static String word() {
        return Generators.letterStrings(8, 20).next();
    }

    private static String alias() {
        return ALIASES.next();
    }

    /**
     * A credential marker of a user or a password: a generated word between tildes. No key, alias, generated word,
     * host or fixed text of a detail or a log record contains a tilde, so the marker cannot occur in the output that
     * is allowed to survive.
     */
    private static String credential() {
        return "~" + word() + "~";
    }

    private static int count() {
        return Generators.integers(1, 10).next();
    }

    private static String httpsRemote() {
        return ORIGIN + "/" + word() + ".git";
    }

    /** A tree carrying exactly the three required keys. */
    private static Map<String, Object> minimal() {
        Map<String, Object> tree = new LinkedHashMap<>();
        put(tree, "save.max-attempts", count());
        put(tree, "drafts.expiry", Period.ofDays(count()).toString());
        put(tree, "fetch.interval", Duration.ofMinutes(count()).toString());
        return tree;
    }

    /** A repository section with every key. */
    private static Map<String, Object> repositorySection(String alias) {
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("alias", alias);
        section.put("remote", httpsRemote());
        section.put("confidentiality", word());
        return section;
    }

    /** Puts a value under a dotted key, creating the maps on the way and replacing a non-map. */
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> tree, String key, Object value) {
        String[] segments = key.split("\\.");
        Map<String, Object> current = tree;
        for (int index = 0; index < segments.length - 1; index++) {
            Object next = current.get(segments[index]);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                current.put(segments[index], next);
            }
            current = (Map<String, Object>) next;
        }
        current.put(segments[segments.length - 1], value);
    }

    /** Removes a dotted key. */
    @SuppressWarnings("unchecked")
    private static void remove(Map<String, Object> tree, String key) {
        String[] segments = key.split("\\.");
        Map<String, Object> current = tree;
        for (int index = 0; index < segments.length - 1; index++) {
            current = (Map<String, Object>) current.get(segments[index]);
        }
        current.remove(segments[segments.length - 1]);
    }

    private static StoreConfiguration configuration(StoreResult<StoreConfiguration> result) {
        assertTrue(result.isOk(), () -> "the configuration is accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the configuration");
    }

    /** The messages of every log record written in this test. */
    private static List<String> logMessages() {
        List<String> messages = new ArrayList<>();
        for (var logRecord : TestLoggerFactory.getTestHandler().getRecords()) {
            messages.add(String.valueOf(logRecord.getMessage()));
        }
        return messages;
    }

    /**
     * Asserts that the refusal was logged exactly once as {@code GIT_STORE-100}, and that this one WARN record carries
     * the detail of the refusal.
     */
    private static void assertRefusalLogged(StoreResult<?> result) {
        var identifier = GitStoreLogMessages.WARN.START_REFUSED.resolveIdentifierString();
        var detail = Objects.requireNonNull(result.detail());
        var refusals = TestLoggerFactory.getTestHandler().getRecords().stream()
                .filter(logRecord -> Level.WARNING.equals(logRecord.getLevel()))
                .map(logRecord -> String.valueOf(logRecord.getMessage()))
                .filter(message -> message.contains(identifier))
                .toList();
        assertEquals(1, refusals.size(), () -> "exactly one WARN record " + identifier + ": " + refusals);
        assertTrue(refusals.getFirst().contains(detail), () -> "the record " + identifier + " carries the detail");
    }

    /** Asserts a logged {@link StoreOutcome#MALFORMED} refusal with exactly the given detail. */
    private static void assertMalformed(String detail, StoreResult<StoreConfiguration> result) {
        assertEquals(StoreResult.of(StoreOutcome.MALFORMED, detail), result, "the configuration is refused");
        assertRefusalLogged(result);
    }

    /** Asserts that neither the detail of the result nor any log record carries one of the values. */
    private static void assertNoneCarried(StoreResult<?> result, String... values) {
        var detail = Objects.requireNonNull(result.detail());
        var messages = logMessages();
        assertFalse(messages.isEmpty(), "the refusal is logged");
        assertAll(Stream.of(values).<Executable>map(value -> () -> assertAll(
                () -> assertFalse(detail.contains(value), "the detail carries no refused value"),
                () -> assertTrue(messages.stream().noneMatch(message -> message.contains(value)),
                        "no log record carries a refused value"))));
    }

    @Nested
    @DisplayName("Accepted trees")
    class Accepted {

        @Test
        @DisplayName("a full tree is read into every section")
        void fullTree() {
            var tree = minimal();
            var alias = alias();
            var section = repositorySection(alias);
            var retention = Period.ofYears(count());
            section.put("retention", retention.toString());
            put(tree, "repositories", List.of(section));
            var attempts = count();
            var expiry = Period.ofDays(count());
            var interval = Duration.ofMinutes(count());
            put(tree, "save.max-attempts", attempts);
            put(tree, "drafts.expiry", expiry.toString());
            put(tree, "fetch.interval", interval.toString());
            var signing = new Signing(word(), word(), word());
            put(tree, "signing.key-source", signing.keySource());
            put(tree, "signing.key", Objects.requireNonNull(signing.key()));
            put(tree, "signing.allowed-signers", Objects.requireNonNull(signing.allowedSigners()));
            var signed = new Anchor.Signed(word(), word(), word(), Duration.ofSeconds(count()));
            var anchor = new Anchor(word(), false, Duration.ofHours(count()), Duration.ofMinutes(count()), signed);
            put(tree, "anchor.provider", Objects.requireNonNull(anchor.provider()));
            put(tree, "anchor.releases", anchor.releases());
            put(tree, "anchor.checkpoint-interval", anchor.checkpointInterval().toString());
            put(tree, "anchor.retry-interval", anchor.retryInterval().toString());
            put(tree, "anchor.signed.key-source", Objects.requireNonNull(signed.keySource()));
            put(tree, "anchor.signed.key", Objects.requireNonNull(signed.key()));
            put(tree, "anchor.signed.allowed-signers", Objects.requireNonNull(signed.allowedSigners()));
            put(tree, "anchor.signed.max-clock-skew", signed.maxClockSkew().toString());
            var directory = new Directory(word());
            put(tree, "directory.persons.provider", directory.personsProvider());

            var configuration = configuration(StoreConfiguration.from(tree, HTTPS));

            assertAll(
                    () -> assertEquals(List.of(new StoreRepository(alias, (String) section.get("remote"),
                                    (String) section.get("confidentiality"), retention)),
                            configuration.repositories().repositories(), "the registry"),
                    () -> assertEquals(new Save(attempts), configuration.save(), "save"),
                    () -> assertEquals(new Drafts(expiry), configuration.drafts(), "drafts"),
                    () -> assertEquals(new Fetch(interval), configuration.fetch(), "fetch"),
                    () -> assertEquals(signing, configuration.signing(), "signing"),
                    () -> assertEquals(anchor, configuration.anchor(), "anchor"),
                    () -> assertEquals(directory, configuration.directory(), "directory"));
        }

        @Test
        @DisplayName("a minimal tree takes every documented default")
        void minimalTree() {
            var configuration = configuration(StoreConfiguration.from(minimal(), HTTPS));

            assertAll(
                    () -> assertTrue(configuration.repositories().repositories().isEmpty(),
                            "repositories: an empty registry"),
                    () -> assertEquals(new Signing("file", null, null), configuration.signing(),
                            "signing: key source file, no key, no allowed signers"),
                    () -> assertNull(configuration.anchor().provider(), "anchor.provider: none"),
                    () -> assertTrue(configuration.anchor().releases(), "anchor.releases: true"),
                    () -> assertEquals(Duration.parse("P1D"), configuration.anchor().checkpointInterval(),
                            "anchor.checkpoint-interval: P1D"),
                    () -> assertEquals(Duration.parse("PT15M"), configuration.anchor().retryInterval(),
                            "anchor.retry-interval: PT15M"),
                    () -> assertEquals(new Anchor.Signed(null, null, null, Duration.parse("PT5M")),
                            configuration.anchor().signed(), "anchor.signed: no key, max-clock-skew PT5M"),
                    () -> assertEquals(new Directory("git"), configuration.directory(),
                            "directory.persons.provider: git"));
        }

        @Test
        @DisplayName("an accepted tree logs no refusal")
        void acceptedTreeLogsNothing() {
            configuration(StoreConfiguration.from(minimal(), HTTPS));

            var identifier = GitStoreLogMessages.WARN.START_REFUSED.resolveIdentifierString();
            assertTrue(logMessages().stream().noneMatch(message -> message.contains(identifier)),
                    "no GIT_STORE-100");
        }

        @Test
        @DisplayName("keys outside the configuration of git-store are ignored")
        void unknownKeysIgnored() {
            var tree = minimal();
            var expected = configuration(StoreConfiguration.from(minimal(), HTTPS));
            put(tree, word(), word());
            put(tree, word() + "." + word(), count());
            put(tree, "save." + word(), word());
            put(tree, "anchor.signed." + word(), List.of(word()));
            var section = repositorySection(alias());
            section.put(word(), word());
            put(tree, "repositories", List.of(section));

            var configuration = configuration(StoreConfiguration.from(tree, HTTPS));

            assertAll(
                    () -> assertEquals(1, configuration.repositories().repositories().size(),
                            "the repository is read"),
                    () -> assertEquals(expected.signing(), configuration.signing(), "signing unchanged"),
                    () -> assertEquals(expected.anchor(), configuration.anchor(), "anchor unchanged"),
                    () -> assertEquals(expected.directory(), configuration.directory(), "directory unchanged"));
        }

        @ParameterizedTest(name = "max-attempts as {0}")
        @MethodSource("de.cuioss.git.document.store.StoreConfigurationTest#integerForms")
        @DisplayName("save.max-attempts accepts every integral number type within int")
        void integerForms(Number value) {
            var tree = minimal();
            put(tree, "save.max-attempts", value);

            assertEquals(new Save(value.intValue()), configuration(StoreConfiguration.from(tree, HTTPS)).save(),
                    "the number is read");
        }

        @Test
        @DisplayName("GDR-NFR-3: the rendering of a configuration carries no key reference")
        void renderingHidesKeys() {
            var tree = minimal();
            var signingKey = word();
            var anchorKey = word();
            put(tree, "signing.key", signingKey);
            put(tree, "anchor.signed.key", anchorKey);

            var rendered = configuration(StoreConfiguration.from(tree, HTTPS)).toString();

            assertAll(
                    () -> assertFalse(rendered.contains(signingKey), "no commit-signing key reference"),
                    () -> assertFalse(rendered.contains(anchorKey), "no anchor key reference"),
                    () -> assertTrue(rendered.contains("key=present"), "only the presence of a key is rendered"));
        }
    }

    static Stream<Number> integerForms() {
        int value = Generators.integers(1, 100).next();
        return Stream.of(value, (long) value, (short) value, (byte) value, BigInteger.valueOf(value));
    }

    @Nested
    @DisplayName("Missing required keys")
    class Missing {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"save.max-attempts", "drafts.expiry", "fetch.interval"})
        @DisplayName("a missing required key is refused, naming the key")
        void missingKey(String key) {
            var tree = minimal();
            remove(tree, key);

            assertMalformed(key + " is missing", StoreConfiguration.from(tree, HTTPS));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"save", "drafts", "fetch"})
        @DisplayName("a missing required section is refused, naming its required key")
        void missingSection(String section) {
            var tree = minimal();
            var required = Map.of("save", "save.max-attempts", "drafts", "drafts.expiry", "fetch", "fetch.interval");
            tree.remove(section);

            assertMalformed(required.get(section) + " is missing", StoreConfiguration.from(tree, HTTPS));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"alias", "remote", "confidentiality"})
        @DisplayName("GDR-STORE-1: a missing key of a repository is refused, naming its position")
        void missingRepositoryKey(String key) {
            var tree = minimal();
            var second = repositorySection(alias());
            second.remove(key);
            put(tree, "repositories", List.of(repositorySection(alias()), second));

            assertMalformed("repositories[1]." + key + " is missing", StoreConfiguration.from(tree, HTTPS));
        }
    }

    static Stream<Arguments> malformedValues() {
        return Stream.of(
                Arguments.of("save.max-attempts", "3", "save.max-attempts"),
                Arguments.of("save.max-attempts", 0, "save.max-attempts"),
                Arguments.of("save.max-attempts", -1, "save.max-attempts"),
                Arguments.of("save.max-attempts", 2.5, "save.max-attempts"),
                Arguments.of("save.max-attempts", Long.MAX_VALUE, "save.max-attempts"),
                Arguments.of("save.max-attempts", BigInteger.TWO.pow(40), "save.max-attempts"),
                Arguments.of("save", "three", "save.max-attempts"),
                Arguments.of("drafts.expiry", "PT5M", "drafts.expiry"),
                Arguments.of("drafts.expiry", "P0D", "drafts.expiry"),
                Arguments.of("drafts.expiry", "P-1D", "drafts.expiry"),
                Arguments.of("drafts.expiry", "P1M-1D", "drafts.expiry"),
                Arguments.of("drafts.expiry", 30, "drafts.expiry"),
                Arguments.of("drafts.expiry", " ", "drafts.expiry"),
                Arguments.of("fetch.interval", "soon", "fetch.interval"),
                Arguments.of("fetch.interval", "PT0S", "fetch.interval"),
                Arguments.of("fetch.interval", "-PT5M", "fetch.interval"),
                Arguments.of("fetch.interval", 5, "fetch.interval"),
                Arguments.of("signing.key-source", 42, "signing.key-source"),
                Arguments.of("signing.key-source", "", "signing.key-source"),
                Arguments.of("signing.key", true, "signing.key"),
                Arguments.of("signing.allowed-signers", List.of(), "signing.allowed-signers"),
                Arguments.of("anchor.provider", 1, "anchor.provider"),
                Arguments.of("anchor.releases", "true", "anchor.releases"),
                Arguments.of("anchor.checkpoint-interval", "P1Y", "anchor.checkpoint-interval"),
                Arguments.of("anchor.retry-interval", "PT-1M", "anchor.retry-interval"),
                Arguments.of("anchor.signed.key-source", 7, "anchor.signed.key-source"),
                Arguments.of("anchor.signed.key", Map.of(), "anchor.signed.key"),
                Arguments.of("anchor.signed.allowed-signers", 7, "anchor.signed.allowed-signers"),
                Arguments.of("anchor.signed.max-clock-skew", "PT0S", "anchor.signed.max-clock-skew"),
                Arguments.of("anchor.signed", "signed", "anchor.signed.key-source"),
                Arguments.of("directory.persons.provider", 5, "directory.persons.provider"),
                Arguments.of("directory.persons", "ldap", "directory.persons.provider"),
                Arguments.of("repositories", "register", "repositories"),
                Arguments.of("repositories", List.of("register"), "repositories[0]"));
    }

    @Nested
    @DisplayName("Malformed values")
    class Malformed {

        @ParameterizedTest(name = "{0} = {1}")
        @MethodSource("de.cuioss.git.document.store.StoreConfigurationTest#malformedValues")
        @DisplayName("a malformed typed value is refused, naming the key and never the value")
        void malformedValue(String key, Object value, String namedKey) {
            var tree = minimal();
            put(tree, key, value);

            assertMalformed(namedKey + " is malformed", StoreConfiguration.from(tree, HTTPS));
        }

        @ParameterizedTest(name = "{0} = [{1}]")
        @MethodSource("de.cuioss.git.document.store.StoreConfigurationTest#malformedRepositoryValues")
        @DisplayName("GDR-STORE-1: a malformed value of a repository is refused, naming its position")
        void malformedRepositoryValue(String key, Object value) {
            var tree = minimal();
            var section = repositorySection(alias());
            section.put(key, value);
            put(tree, "repositories", List.of(section));

            assertMalformed("repositories[0]." + key + " is malformed", StoreConfiguration.from(tree, HTTPS));
        }

        @ParameterizedTest(name = "{0}: null")
        @ValueSource(strings = {"save.max-attempts", "signing.key", "anchor.releases", "anchor.signed.max-clock-skew",
                "directory.persons.provider", "repositories"})
        @DisplayName("a key supplied with a null leaf is malformed, not absent, naming the key")
        void nullLeaf(String key) {
            var tree = minimal();
            put(tree, key, nullValue());

            assertMalformed(key + " is malformed", StoreConfiguration.from(tree, HTTPS));
        }

        @ParameterizedTest(name = "{0}: null")
        @MethodSource("de.cuioss.git.document.store.StoreConfigurationTest#nullSections")
        @DisplayName("a section supplied as null is malformed, not absent, naming the first key read below it")
        void nullSection(String section, String namedKey) {
            var tree = minimal();
            put(tree, section, nullValue());

            assertMalformed(namedKey + " is malformed", StoreConfiguration.from(tree, HTTPS));
        }

        @ParameterizedTest(name = "{0}: null")
        @ValueSource(strings = {"alias", "remote", "confidentiality", "retention"})
        @DisplayName("GDR-STORE-1: a key of a repository supplied as null is malformed, naming its position")
        void nullRepositoryKey(String key) {
            var tree = minimal();
            var section = repositorySection(alias());
            section.put(key, nullValue());
            put(tree, "repositories", List.of(section));

            assertMalformed("repositories[0]." + key + " is malformed", StoreConfiguration.from(tree, HTTPS));
        }

        @Test
        @DisplayName("GDR-STORE-1: a duplicate alias of the registry is refused")
        void duplicateAlias() {
            var tree = minimal();
            var alias = alias();
            put(tree, "repositories", List.of(repositorySection(alias), repositorySection(alias)));

            assertMalformed("repository " + alias + ": duplicate alias", StoreConfiguration.from(tree, HTTPS));
        }

        @Test
        @DisplayName("GDR-STORE-1: a remote the policy refuses is the passed-on REFUSED_BY_POLICY")
        void refusedRemote() {
            var tree = minimal();
            var alias = alias();
            put(tree, "repositories", List.of(repositorySection(alias)));

            var result = StoreConfiguration.from(tree, RemotePolicy.localOnly());

            assertAll(
                    () -> assertEquals(new StoreResult.PassedOn(GitOutcome.REFUSED_BY_POLICY), result.outcome(),
                            "the refusal of the policy is passed on"),
                    () -> assertEquals("repository " + alias + ": remote not permitted by the remote policy",
                            result.detail(), "the detail names the alias"),
                    () -> assertRefusalLogged(result));
        }

        @Test
        @DisplayName("null arguments are refused")
        void refusesNull() {
            Map<String, Object> nullTree = nullValue();
            RemotePolicy nullPolicy = nullValue();
            var tree = minimal();

            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> StoreConfiguration.from(nullTree, HTTPS),
                            "a null tree"),
                    () -> assertThrows(NullPointerException.class, () -> StoreConfiguration.from(tree, nullPolicy),
                            "a null policy"),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Save(0),
                            "a section with no attempt"));
        }
    }

    static Stream<Arguments> nullSections() {
        return Stream.of(
                Arguments.of("save", "save.max-attempts"),
                Arguments.of("signing", "signing.key-source"),
                Arguments.of("anchor", "anchor.provider"),
                Arguments.of("anchor.signed", "anchor.signed.key-source"),
                Arguments.of("directory", "directory.persons.provider"),
                Arguments.of("directory.persons", "directory.persons.provider"));
    }

    static Stream<Arguments> malformedRepositoryValues() {
        return Stream.of(
                Arguments.of("alias", " "),
                Arguments.of("alias", 7),
                Arguments.of("remote", ""),
                Arguments.of("confidentiality", " "),
                Arguments.of("retention", "P0D"),
                Arguments.of("retention", "PT1H"),
                Arguments.of("retention", 10));
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    @SuppressWarnings("unchecked")
    private static <T> T nullValue() {
        return (T) null;
    }

    @Nested
    @DisplayName("Anchor key (GDR-STORE-18)")
    class AnchorKey {

        private static final String REFUSAL = "anchor.signed.key equals the commit-signing key";

        @Test
        @DisplayName("GDR-STORE-18: an anchor key equal to the commit-signing key under the default sources is refused")
        void equalKeyDefaultSources() {
            var tree = minimal();
            var key = word();
            put(tree, "signing.key", key);
            put(tree, "anchor.signed.key", key);

            assertMalformed(REFUSAL, StoreConfiguration.from(tree, HTTPS));
        }

        @Test
        @DisplayName("GDR-STORE-18: an equal key under the same explicit source is refused")
        void equalKeySameSource() {
            var tree = minimal();
            var key = word();
            var source = word();
            put(tree, "signing.key-source", source);
            put(tree, "signing.key", key);
            put(tree, "anchor.signed.key-source", source);
            put(tree, "anchor.signed.key", key);

            assertMalformed(REFUSAL, StoreConfiguration.from(tree, HTTPS));
        }

        @Test
        @DisplayName("GDR-STORE-18: an explicit source file equals the default source of the other key")
        void explicitDefaultSource() {
            var tree = minimal();
            var key = word();
            put(tree, "signing.key", key);
            put(tree, "anchor.signed.key-source", Signing.DEFAULT_KEY_SOURCE);
            put(tree, "anchor.signed.key", key);

            assertMalformed(REFUSAL, StoreConfiguration.from(tree, HTTPS));
        }

        @Test
        @DisplayName("GDR-STORE-18: the same key reference under another source is accepted")
        void equalKeyOtherSource() {
            var tree = minimal();
            var key = word();
            put(tree, "signing.key-source", word());
            put(tree, "signing.key", key);
            put(tree, "anchor.signed.key", key);

            assertEquals(key, configuration(StoreConfiguration.from(tree, HTTPS)).anchor().signed().key(),
                    "the anchor key is kept");
        }

        @Test
        @DisplayName("GDR-STORE-18: different keys and an absent anchor key are accepted")
        void differentKeys() {
            var different = minimal();
            put(different, "signing.key", word());
            put(different, "anchor.signed.key", word());
            var absent = minimal();
            put(absent, "signing.key", word());

            assertAll(
                    () -> configuration(StoreConfiguration.from(different, HTTPS)),
                    () -> assertNull(configuration(StoreConfiguration.from(absent, HTTPS)).anchor().signed().key(),
                            "no anchor key"));
        }
    }

    @Nested
    @DisplayName("No refused value in a detail or a log record (GDR-NFR-3)")
    class NoValue {

        @Test
        @DisplayName("GDR-NFR-3: the refused anchor key is neither in the detail nor logged")
        void anchorKey() {
            var tree = minimal();
            var key = word();
            put(tree, "signing.key", key);
            put(tree, "anchor.signed.key", key);

            var result = StoreConfiguration.from(tree, HTTPS);

            assertRefusalLogged(result);
            assertNoneCarried(result, key);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"save.max-attempts", "drafts.expiry", "fetch.interval", "anchor.signed.max-clock-skew"})
        @DisplayName("GDR-NFR-3: a malformed value is neither in the detail nor logged")
        void malformedValue(String key) {
            var tree = minimal();
            var value = word();
            put(tree, key, value);

            var result = StoreConfiguration.from(tree, HTTPS);

            assertRefusalLogged(result);
            assertNoneCarried(result, value);
        }

        @Test
        @DisplayName("GDR-NFR-3: a remote with user information is neither in the detail nor logged")
        void remoteWithUserInformation() {
            var tree = minimal();
            var user = credential();
            var password = credential();
            var path = word();
            var section = repositorySection(alias());
            section.put("remote", "https://" + user + ":" + password + "@git.example/" + path + ".git");
            put(tree, "repositories", List.of(section));

            var result = StoreConfiguration.from(tree, HTTPS);

            assertEquals(StoreOutcome.MALFORMED, result.outcome(), "the remote is refused");
            assertRefusalLogged(result);
            assertNoneCarried(result, user, password, path);
        }

        @Test
        @DisplayName("GDR-NFR-3: a remote the policy refuses is neither in the detail nor logged")
        void refusedRemote() {
            var tree = minimal();
            var host = Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next() + ".example";
            var section = repositorySection(alias());
            section.put("remote", "https://" + host + "/" + word() + ".git");
            put(tree, "repositories", List.of(section));

            var result = StoreConfiguration.from(tree, HTTPS);

            assertEquals(new StoreResult.PassedOn(GitOutcome.REFUSED_BY_POLICY), result.outcome(),
                    "the remote is refused by the policy");
            assertRefusalLogged(result);
            assertNoneCarried(result, host);
        }
    }
}
