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
package de.cuioss.git.document.forms;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.RepositoryReader;
import de.cuioss.git.document.store.RepositoryRegistry;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreRepository;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The kinds of documents as configuration (GDR-FORMS-1): the example of the specification loaded from a directory and
 * from a commit of an in-memory repository of {@code git-testing}, a numbered kind without template, a kind no class
 * knows found through {@link KindConfiguration#find(String)}, the success logged as {@code GIT_FORMS-1}, and every
 * refusal: the passed-on {@link StoreOutcome#MALFORMED} with a detail naming the file, the key path and the rule (among
 * them the pair of repository and collection, the prefix form, the range of digits, the relative path form, the file
 * size bound and the symbolic link), the passed-on {@link GitOutcome#FAILED} for an I/O failure, and the Git outcome of
 * an unresolvable revision. No detail and no log record carries a configured value (GDR-NFR-3).
 * <p>
 * The example of the specification is kept as a literal, being the contract, and so are the values each rule is about
 * (a prefix {@code /}, a path {@code ../x}, the bounds). Every other id, collection, alias, prefix and reference is
 * generated: names from lower-case letters only and prefixes from upper-case letters only, at least twelve long, so
 * that none can occur in the fixed text of a detail (whose longest run of letters is shorter); references between
 * tildes, which no fixed text contains.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@DisplayName("KindConfiguration (GDR-FORMS-1)")
class KindConfigurationTest {

    /** The HTTPS origin the remote policy of the registry admits. */
    private static final String ORIGIN = "https://git.example";

    /** The alias of the example of the specification. */
    private static final String EXAMPLE_ALIAS = "register";

    /** The example of GDR-FORMS-1 in {@code doc/specification/git-forms.adoc}, verbatim. */
    private static final String EXAMPLE = """
            kinds:
              - id: assessment                   # kind id, used in links: <alias>:assessment/<id>
                repository: register             # alias from the repository registry
                collection: assessments          # per-document: working branches refs/heads/assessments/<id>
                schema: schemas/assessment.schema.json
                template: templates/assessment.adoc.tpl
                workflow: workflows/approval.yaml
              - id: risk                         # links: register:risk/R-042
                repository: register
                collection: risks                # numbered: one collection branch refs/heads/risks
                schema: schemas/risk.schema.json
                workflow: workflows/approval.yaml
                numbering: { prefix: R-, digits: 3 }   # optional, see GDR-FORMS-9
            """;

    /** The kinds of the example. */
    private static final List<Kind> EXAMPLE_KINDS = List.of(
            new Kind("assessment", EXAMPLE_ALIAS, "assessments", "schemas/assessment.schema.json",
                    "templates/assessment.adoc.tpl", "workflows/approval.yaml", null),
            new Kind("risk", EXAMPLE_ALIAS, "risks", "schemas/risk.schema.json", null, "workflows/approval.yaml",
                    new Numbering("R-", 3)));

    /** Names: lower-case letters only, unique within the run. */
    private static final TypedGenerator<String> NAMES =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20));

    /** Prefixes: upper-case letters only, within the prefix form {@code [A-Za-z0-9_-]{0,16}}, unique within the run. */
    private static final TypedGenerator<String> PREFIXES =
            Generators.uniqueValues(Generators.strings("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 12, 16));

    /** The passed-on outcome of a configuration the loader does not accept. */
    private static final FormsResult.Outcome MALFORMED = new FormsResult.PassedOn(StoreOutcome.MALFORMED);

    /** The detail of a prefix outside its form. */
    private static final String PREFIX_REFUSED = "kinds[0].numbering.prefix: not of the form [A-Za-z0-9_-]{0,16}";

    /** The detail of digits outside 1 to 19. */
    private static final String DIGITS_REFUSED = "kinds[0].numbering.digits: not an integer between 1 and 19";

    /** The detail of a file above the size bound. */
    private static final String TOO_LARGE = "kinds.yaml: too large, more than 1048576 bytes";

    private static String name() {
        return NAMES.next();
    }

    /** The example of the specification padded with a comment to exactly the given number of bytes. */
    private static String exampleOfSize(int bytes) {
        int padding = bytes - EXAMPLE.length() - 2;
        return EXAMPLE + "#" + "x".repeat(padding) + "\n";
    }

    /** A reference between tildes, such as a schema path. */
    private static String reference() {
        return "~" + Generators.letterStrings(4, 16).next() + "~";
    }

    /** A YAML double-quoted scalar of a text without quotes and backslashes. */
    private static String quoted(String text) {
        return "\"" + text + "\"";
    }

    /** A registry holding a repository for each alias. */
    private static RepositoryRegistry registry(String... aliases) {
        List<StoreRepository> repositories = Stream.of(aliases)
                .map(alias -> new StoreRepository(alias, ORIGIN + "/" + alias + ".git", name(), null))
                .toList();
        StoreResult<RepositoryRegistry> result =
                RepositoryRegistry.of(repositories, RemotePolicy.httpsOrigin(URI.create(ORIGIN)));
        return Objects.requireNonNull(result.value(), () -> "the registry is built: " + result.detail());
    }

    /** The configuration of a successful load. */
    private static KindConfiguration loaded(FormsResult<KindConfiguration> result) {
        assertTrue(result.isOk(), () -> "the kinds are loaded: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the configuration");
    }

    /** Writes the configuration file into a directory. */
    private static Path write(Path directory, String yaml) throws IOException {
        Files.writeString(directory.resolve(KindConfiguration.FILE), yaml);
        return directory;
    }

    /** Commits the files, by path at the root, to a branch and returns the commit. */
    private static ObjectId commit(Repository repository, String branch, Map<String, String> files)
            throws IOException {
        ObjectId commit;
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            for (var file : new TreeMap<>(files).entrySet()) {
                tree.append(file.getKey(), FileMode.REGULAR_FILE,
                        inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(StandardCharsets.UTF_8)));
            }
            var builder = new CommitBuilder();
            builder.setTreeId(inserter.insert(tree));
            var ident = new PersonIdent(name(), name() + "@example.invalid");
            builder.setAuthor(ident);
            builder.setCommitter(ident);
            builder.setMessage(name());
            commit = inserter.insert(builder);
            inserter.flush();
        }
        RefUpdate update = repository.updateRef(Constants.R_HEADS + branch);
        update.setNewObjectId(commit);
        assertEquals(RefUpdate.Result.NEW, update.forceUpdate(), "the branch is created");
        return commit;
    }

    /** Asserts that a detail carries none of the configured values. */
    private static void assertNoValue(@Nullable String detail, List<String> values) {
        assertAll(values.stream().<Executable>map(value -> () -> assertFalse(String.valueOf(detail).contains(value),
                () -> "the detail names no configured value: " + detail)));
    }

    /** The configured values of one kind, generated. */
    private record Values(String id, String alias, String collection, String schema, String template,
    String workflow, String prefix) {

        static Values generate(String alias) {
            return new Values(name(), alias, name(), reference(), reference(), reference(), PREFIXES.next());
        }

        /** The keys of a per-document kind, each with its YAML value, in the order of the example. */
        Map<String, String> perDocument() {
            Map<String, String> keys = new LinkedHashMap<>();
            keys.put("id", id);
            keys.put("repository", alias);
            keys.put("collection", collection);
            keys.put("schema", quoted(schema));
            keys.put("template", quoted(template));
            keys.put("workflow", quoted(workflow));
            return keys;
        }

        /** The keys of a numbered kind without template. */
        Map<String, String> numbered(String digits) {
            Map<String, String> keys = perDocument();
            keys.remove("template");
            keys.put("numbering", "{ prefix: " + quoted(prefix) + ", digits: " + digits + " }");
            return keys;
        }

        List<String> all() {
            return List.of(id, alias, collection, schema, template, workflow, prefix);
        }
    }

    /** The YAML of a configuration with the given kinds, each a mapping of keys to YAML values. */
    @SafeVarargs
    private static String yaml(Map<String, String>... kinds) {
        var text = new StringBuilder("kinds:\n");
        for (Map<String, String> kind : kinds) {
            String indent = "  - ";
            for (var entry : kind.entrySet()) {
                text.append(indent).append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
                indent = "    ";
            }
        }
        return text.toString();
    }

    /** A copy of the keys of a kind with one key replaced, or removed when the value is {@code null}. */
    private static Map<String, String> with(Map<String, String> keys, String key,
            @Nullable String value) {
        Map<String, String> changed = new LinkedHashMap<>(keys);
        if (value == null) {
            changed.remove(key);
        } else {
            changed.put(key, value);
        }
        return changed;
    }

    @Nested
    @DisplayName("Loading")
    class Loading {

        @Test
        @DisplayName("GDR-FORMS-1: the example of the specification loads from a directory, in configuration order")
        void exampleFromDirectory(@TempDir Path directory) throws Exception {
            var result = KindConfiguration.fromDirectory(write(directory, EXAMPLE), registry(EXAMPLE_ALIAS));

            var configuration = loaded(result);
            assertAll(
                    () -> assertEquals(EXAMPLE_KINDS, configuration.kinds(), "the kinds in configuration order"),
                    () -> assertFalse(configuration.kinds().getFirst().isNumbered(), "assessment is per-document"),
                    () -> assertTrue(configuration.kinds().get(1).isNumbered(), "risk is numbered"),
                    () -> assertEquals(Optional.of(EXAMPLE_KINDS.get(1)), configuration.find("risk"),
                            "a kind is found by its id"),
                    () -> assertEquals(Optional.empty(), configuration.find(name()), "an unknown id finds nothing"));
        }

        @Test
        @DisplayName("GDR-FORMS-1: the example loads from a commit of a configuration repository, by branch and id")
        void exampleFromRepository(@InMemory Repository repository) throws Exception {
            var branch = name();
            var commit = commit(repository, branch,
                    Map.of(KindConfiguration.FILE, EXAMPLE, name(), name()));
            var registry = registry(EXAMPLE_ALIAS);

            var byBranch = KindConfiguration.fromRepository(repository, branch, registry);
            var byCommitId = KindConfiguration.fromRepository(repository, commit.name(), registry);

            assertAll(
                    () -> assertEquals(EXAMPLE_KINDS, loaded(byBranch).kinds(), "the kinds of the branch"),
                    () -> assertEquals(EXAMPLE_KINDS, loaded(byCommitId).kinds(), "the kinds of the commit"));
        }

        @Test
        @DisplayName("GDR-FORMS-1: a kind no class knows is added by configuration only and found through find(id)")
        void kindInConfigurationOnly(@TempDir Path directory) throws Exception {
            var values = Values.generate(name());

            var configuration = loaded(KindConfiguration.fromDirectory(write(directory, yaml(values.perDocument())),
                    registry(values.alias())));

            var expected = new Kind(values.id(), values.alias(), values.collection(), values.schema(),
                    values.template(), values.workflow(), null);
            assertAll(
                    () -> assertEquals(Optional.of(expected), configuration.find(values.id()),
                            "the configured kind is found by its id"),
                    () -> assertEquals(List.of(expected), configuration.kinds(), "the one configured kind"));
        }

        @Test
        @DisplayName("GDR-FORMS-1: a numbered kind without template loads, with its numbering")
        void numberedWithoutTemplate(@TempDir Path directory) throws Exception {
            var values = Values.generate(name());
            var digits = Generators.integers(1, 9).next();

            var configuration = loaded(KindConfiguration.fromDirectory(
                    write(directory, yaml(values.numbered(String.valueOf(digits)))), registry(values.alias())));

            var kind = configuration.find(values.id()).orElseThrow();
            assertAll(
                    () -> assertTrue(kind.isNumbered(), "the kind is numbered"),
                    () -> assertEquals(new Numbering(values.prefix(), digits), kind.numbering(), "the numbering"),
                    () -> assertNull(kind.template(), "no template"));
        }

        @Test
        @DisplayName("GDR-FORMS-1: a numbering with an empty prefix loads, numbers without a prefix")
        void numberedWithEmptyPrefix(@TempDir Path directory) throws Exception {
            var values = Values.generate(name());
            var keys = with(values.numbered("3"), "numbering", "{ prefix: \"\", digits: 3 }");

            var configuration = loaded(KindConfiguration.fromDirectory(write(directory, yaml(keys)),
                    registry(values.alias())));

            assertEquals(new Numbering("", 3), configuration.find(values.id()).orElseThrow().numbering(),
                    "the empty prefix is kept");
        }

        @ParameterizedTest(name = "GDR-FORMS-1: the prefix \"{0}\", within the prefix form, loads")
        @MethodSource("de.cuioss.git.document.forms.KindConfigurationTest#acceptedPrefixes")
        void prefixInForm(String prefix, @TempDir Path directory) throws Exception {
            var values = Values.generate(name());
            var keys = with(values.numbered("3"), "numbering", "{ prefix: " + quoted(prefix) + ", digits: 3 }");

            var configuration = loaded(KindConfiguration.fromDirectory(write(directory, yaml(keys)),
                    registry(values.alias())));

            assertEquals(new Numbering(prefix, 3), configuration.find(values.id()).orElseThrow().numbering(),
                    "the prefix is kept");
        }

        @Test
        @DisplayName("GDR-FORMS-1: digits of 19, the digits of the largest long counter, load")
        void largestDigits(@TempDir Path directory) throws Exception {
            var values = Values.generate(name());

            var configuration = loaded(KindConfiguration.fromDirectory(write(directory, yaml(values.numbered("19"))),
                    registry(values.alias())));

            assertEquals(new Numbering(values.prefix(), 19),
                    configuration.find(values.id()).orElseThrow().numbering(), "the 19 digits are kept");
        }

        @Test
        @DisplayName("GDR-FORMS-1: the same collection in two repositories loads, one namespace per repository")
        void sameCollectionInTwoRepositories(@TempDir Path directory) throws Exception {
            var first = Values.generate(name());
            var second = Values.generate(name());
            var keys = with(second.perDocument(), "collection", first.collection());

            var configuration = loaded(KindConfiguration.fromDirectory(
                    write(directory, yaml(first.numbered("3"), keys)), registry(first.alias(), second.alias())));

            assertAll(
                    () -> assertEquals(first.collection(), configuration.find(first.id()).orElseThrow().collection(),
                            "the first kind has the collection"),
                    () -> assertEquals(first.collection(), configuration.find(second.id()).orElseThrow().collection(),
                            "the second kind, of the other repository, has the same collection"));
        }

        @Test
        @DisplayName("GDR-FORMS-1: a file of exactly 1,048,576 bytes loads from a directory")
        void fileAtBoundFromDirectory(@TempDir Path directory) throws Exception {
            var text = exampleOfSize(KindConfiguration.MAX_FILE_BYTES);

            var result = KindConfiguration.fromDirectory(write(directory, text), registry(EXAMPLE_ALIAS));

            assertAll(
                    () -> assertEquals(1_048_576, text.getBytes(StandardCharsets.UTF_8).length, "the file size"),
                    () -> assertEquals(EXAMPLE_KINDS, loaded(result).kinds(), "the kinds of the file"));
        }

        @Test
        @DisplayName("GDR-FORMS-1: a blob of exactly 1,048,576 bytes loads from a commit")
        void fileAtBoundFromRepository(@InMemory Repository repository) throws Exception {
            var branch = name();
            commit(repository, branch, Map.of(KindConfiguration.FILE, exampleOfSize(KindConfiguration.MAX_FILE_BYTES)));

            var result = KindConfiguration.fromRepository(repository, branch, registry(EXAMPLE_ALIAS));

            assertEquals(EXAMPLE_KINDS, loaded(result).kinds(), "the kinds of the blob");
        }

        @Test
        @DisplayName("GDR-FORMS-1: a load is logged as GIT_FORMS-1 with the number and the source, no configured value")
        void loadIsLogged(@TempDir Path directory) throws Exception {
            var first = Values.generate(name());
            var second = Values.generate(first.alias());

            loaded(KindConfiguration.fromDirectory(write(directory, yaml(first.perDocument(), second.numbered("3"))),
                    registry(first.alias())));

            var identifier = GitFormsLogMessages.INFO.KINDS_LOADED.resolveIdentifierString();
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO, identifier);
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                    GitFormsLogMessages.INFO.KINDS_LOADED.format(2, "directory " + directory));
            var values = new ArrayList<>(first.all());
            values.addAll(second.all());
            assertAll(TestLoggerFactory.getTestHandler().getRecords().stream()
                    .<Executable>map(logRecord -> () -> assertNoValue(String.valueOf(logRecord.getMessage()), values)));
        }
    }

    @Nested
    @DisplayName("Refusal")
    class Refusal {

        /** Every configuration the loader refuses: description, YAML, expected detail and the configured values. */
        static Stream<Arguments> malformedConfigurations() {
            var alias = name();
            var values = Values.generate(alias);
            var other = Values.generate(alias);
            var kind = values.perDocument();
            var marker = reference();
            var upperId = values.id().toUpperCase(Locale.ROOT);
            var underscored = name() + "_" + name();
            var unknownAlias = name();
            var numbered = values.numbered("3");
            var longPrefix = PREFIXES.next().substring(0, 12) + "ABCDE";
            var configured = new ArrayList<>(values.all());
            configured.addAll(other.all());
            configured.addAll(List.of(marker, upperId, underscored, unknownAlias, longPrefix));
            return Stream.of(
                    Arguments.of("a per-document kind without template",
                            yaml(kind, with(other.perDocument(), "template", null)),
                            "kinds[1].template: missing key, required for a per-document kind"),
                    Arguments.of("a root that is not a mapping", "- " + quoted(marker) + "\n",
                            "the root: not a mapping"),
                    Arguments.of("an unknown key at the root", yaml(kind) + "other: " + quoted(marker) + "\n",
                            "the root: unknown key"),
                    Arguments.of("kinds without a value", "kinds:\n", "kinds: missing key"),
                    Arguments.of("kinds that is not a list", "kinds: { id: " + quoted(marker) + " }\n",
                            "kinds: not a list"),
                    Arguments.of("a kind that is not a mapping", "kinds:\n  - " + quoted(marker) + "\n",
                            "kinds[0]: not a mapping"),
                    Arguments.of("an unknown key in a kind", yaml(with(kind, "colour", quoted(marker))),
                            "kinds[0]: unknown key"),
                    Arguments.of("a kind without id", yaml(with(kind, "id", null)), "kinds[0].id: missing key"),
                    Arguments.of("an id with upper-case letters", yaml(with(kind, "id", upperId)),
                            "kinds[0].id: not of the form [a-z0-9-]+"),
                    Arguments.of("a collection with an underscore", yaml(with(kind, "collection", underscored)),
                            "kinds[0].collection: not of the form [a-z0-9-]+"),
                    Arguments.of("a blank schema", yaml(with(kind, "schema", quoted("  "))),
                            "kinds[0].schema: not a non-blank string"),
                    Arguments.of("a workflow that is not a string", yaml(with(kind, "workflow", "42")),
                            "kinds[0].workflow: not a non-blank string"),
                    Arguments.of("a duplicate id", yaml(kind, with(other.perDocument(), "id", values.id())),
                            "kinds[1].id: duplicate id"),
                    Arguments.of("an alias the registry does not know",
                            yaml(with(kind, "repository", unknownAlias)), "kinds[0].repository: unknown alias"),
                    Arguments.of("a numbering that is not a mapping", yaml(with(numbered, "numbering", "3")),
                            "kinds[0].numbering: not a mapping"),
                    Arguments.of("an unknown key in a numbering",
                            yaml(with(numbered, "numbering", "{ prefix: " + quoted(values.prefix())
                                    + ", digits: 3, start: 1 }")),
                            "kinds[0].numbering: unknown key"),
                    Arguments.of("a numbering without prefix", yaml(with(numbered, "numbering", "{ digits: 3 }")),
                            "kinds[0].numbering.prefix: missing key"),
                    Arguments.of("a prefix that is not a string",
                            yaml(with(numbered, "numbering", "{ prefix: 42, digits: 3 }")),
                            "kinds[0].numbering.prefix: not a string"),
                    Arguments.of("a numbering without digits",
                            yaml(with(numbered, "numbering", "{ prefix: " + quoted(values.prefix()) + " }")),
                            "kinds[0].numbering.digits: missing key"),
                    Arguments.of("digits of 0", yaml(values.numbered("0")), DIGITS_REFUSED),
                    Arguments.of("digits of 20, more than the digits of the largest long counter",
                            yaml(values.numbered("20")), DIGITS_REFUSED),
                    Arguments.of("digits that are a string", yaml(values.numbered(quoted("3"))), DIGITS_REFUSED),
                    Arguments.of("a prefix /", yaml(prefixed(numbered, quoted("/"))), PREFIX_REFUSED),
                    Arguments.of("a prefix ..", yaml(prefixed(numbered, quoted(".."))), PREFIX_REFUSED),
                    Arguments.of("a prefix :", yaml(prefixed(numbered, quoted(":"))), PREFIX_REFUSED),
                    Arguments.of("a prefix #", yaml(prefixed(numbered, quoted("#"))), PREFIX_REFUSED),
                    Arguments.of("a prefix of a space", yaml(prefixed(numbered, quoted(" "))), PREFIX_REFUSED),
                    Arguments.of("a prefix of a newline", yaml(prefixed(numbered, "\"\\n\"")), PREFIX_REFUSED),
                    Arguments.of("a prefix of 17 characters", yaml(prefixed(numbered, quoted(longPrefix))),
                            PREFIX_REFUSED),
                    Arguments.of("a per-document kind with the collection of a numbered kind of the same repository",
                            yaml(numbered, with(other.perDocument(), "collection", values.collection())),
                            "kinds[1].collection: a collection another kind of the same repository uses"),
                    Arguments.of("a numbered kind with the collection of a per-document kind of the same repository",
                            yaml(kind, with(other.numbered("3"), "collection", values.collection())),
                            "kinds[1].collection: a collection another kind of the same repository uses"),
                    Arguments.of("text the YAML reader refuses",
                            "kinds:\n  - id: " + values.id() + "\n    id: " + other.id() + "\n",
                            "line 3, column 5: a duplicate key"))
                    .map(arguments -> Arguments.of(arguments.get()[0], arguments.get()[1], arguments.get()[2],
                            alias, configured));
        }

        /**
         * Every reference that is not a relative path, for each of {@code schema}, {@code template} and
         * {@code workflow}: description, YAML, expected detail and the configured values.
         */
        static Stream<Arguments> nonRelativeReferences() {
            var alias = name();
            var values = Values.generate(alias);
            List<List<String>> references = List.of(
                    List.of("../x", quoted("../x"), "empty, . or .. segment"),
                    List.of("a/./b", quoted("a/./b"), "empty, . or .. segment"),
                    List.of("/x", quoted("/x"), "leading /"),
                    List.of("a//b", quoted("a//b"), "empty, . or .. segment"),
                    List.of("x/", quoted("x/"), "empty, . or .. segment"),
                    List.of("a\\b", "'a\\b'", "backslash"),
                    List.of("a\\u0000b", "\"a\\0b\"", "control character"));
            return Stream.of("schema", "template", "workflow")
                    .flatMap(key -> references.stream().map(reference -> Arguments.of(
                            "the " + key + " " + reference.getFirst(),
                            yaml(with(values.perDocument(), key, reference.get(1))),
                            "kinds[0]." + key + ": not a relative path, " + reference.get(2),
                            alias, values.all())));
        }

        /** The keys of a numbered kind with its prefix replaced by a YAML value. */
        private static Map<String, String> prefixed(Map<String, String> numbered, String prefix) {
            return with(numbered, "numbering", "{ prefix: " + prefix + ", digits: 3 }");
        }

        @ParameterizedTest(name = "GDR-FORMS-1: {0} is MALFORMED, naming the key path and no value")
        @MethodSource({"malformedConfigurations", "nonRelativeReferences"})
        void malformed(String description, String yaml, String problem, String alias, List<String> configured,
                @TempDir Path directory) throws Exception {
            var result = KindConfiguration.fromDirectory(write(directory, yaml), registry(alias));

            assertAll(
                    () -> assertEquals(MALFORMED, result.outcome(), "the configuration is refused as MALFORMED"),
                    () -> assertEquals(KindConfiguration.FILE + ": " + problem, result.detail(),
                            "the detail names the file, the key path and the rule"),
                    () -> assertNoValue(result.detail(), configured),
                    () -> assertAll(TestLoggerFactory.getTestHandler().getRecords().stream()
                            .<Executable>map(logRecord -> () -> assertNoValue(String.valueOf(logRecord.getMessage()),
                                    configured))));
        }

        @Test
        @DisplayName("GDR-FORMS-1: a file of 1,048,577 bytes in a directory is MALFORMED, too large")
        void fileAboveBoundInDirectory(@TempDir Path directory) throws Exception {
            write(directory, exampleOfSize(KindConfiguration.MAX_FILE_BYTES + 1));

            var result = KindConfiguration.fromDirectory(directory, registry(EXAMPLE_ALIAS));

            assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, TOO_LARGE)), result,
                    "a file above the bound is refused");
        }

        @Test
        @DisplayName("GDR-FORMS-1: a blob of 1,048,577 bytes in a commit is MALFORMED, too large")
        void fileAboveBoundInCommit(@InMemory Repository repository) throws Exception {
            var branch = name();
            commit(repository, branch,
                    Map.of(KindConfiguration.FILE, exampleOfSize(KindConfiguration.MAX_FILE_BYTES + 1)));

            var result = KindConfiguration.fromRepository(repository, branch, registry(EXAMPLE_ALIAS));

            assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, TOO_LARGE)), result,
                    "a blob above the bound is refused");
        }

        @Test
        @DisplayName("GDR-FORMS-1: a kinds.yaml that is a symbolic link is MALFORMED and not followed")
        void symbolicLink(@TempDir Path directory, @TempDir Path elsewhere) throws Exception {
            Path target = write(elsewhere, EXAMPLE).resolve(KindConfiguration.FILE);
            Files.createSymbolicLink(directory.resolve(KindConfiguration.FILE), target);

            var result = KindConfiguration.fromDirectory(directory, registry(EXAMPLE_ALIAS));

            assertAll(
                    () -> assertTrue(KindConfiguration.fromDirectory(elsewhere, registry(EXAMPLE_ALIAS)).isOk(),
                            "the target itself is a valid configuration"),
                    () -> assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                            "kinds.yaml: a symbolic link, not read")), result, "the link is refused"));
        }

        @Test
        @DisplayName("GDR-FORMS-1: a directory without the file is MALFORMED")
        void missingFileInDirectory(@TempDir Path directory) {
            var result = KindConfiguration.fromDirectory(directory, registry(name()));

            assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, "kinds.yaml: missing file")),
                    result, "a missing file is MALFORMED");
        }

        @Test
        @DisplayName("GDR-FORMS-1: a commit without the file is MALFORMED")
        void missingFileInCommit(@InMemory Repository repository) throws Exception {
            var branch = name();
            commit(repository, branch, Map.of(name(), name()));

            var result = KindConfiguration.fromRepository(repository, branch, registry(name()));

            assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, "kinds.yaml: missing file")),
                    result, "a missing file is MALFORMED");
        }

        @Test
        @DisplayName("GDR-FORMS-1: a file that is not UTF-8 is MALFORMED")
        void notUtf8(@TempDir Path directory) throws Exception {
            Files.write(directory.resolve(KindConfiguration.FILE), new byte[]{(byte) 0xC3, (byte) 0x28});

            var result = KindConfiguration.fromDirectory(directory, registry(name()));

            assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, "kinds.yaml: not UTF-8")),
                    result, "text that is not UTF-8 is MALFORMED");
        }

        @Test
        @DisplayName("GDR-FORMS-1: an I/O failure is the passed-on FAILED, naming the exception class only")
        void ioFailure(@TempDir Path directory) throws Exception {
            Files.createDirectory(directory.resolve(KindConfiguration.FILE));

            var result = KindConfiguration.fromDirectory(directory, registry(name()));

            var detail = String.valueOf(result.detail());
            assertAll(
                    () -> assertEquals(new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.FAILED)),
                            result.outcome(), "an I/O failure is FAILED of git-core"),
                    () -> assertTrue(detail.matches("kinds\\.yaml: \\w+Exception"),
                            () -> "the detail names the exception class only: " + detail),
                    () -> assertFalse(detail.contains(directory.toString()), "the detail names no path"));
        }

        @Test
        @DisplayName("GDR-FORMS-1: a revision that cannot be resolved passes on the outcome of the snapshot")
        void unknownRevision(@InMemory Repository repository) throws Exception {
            commit(repository, name(), Map.of(KindConfiguration.FILE, EXAMPLE));
            var revision = name();
            var snapshot = new RepositoryReader().snapshot(repository, revision);

            var result = KindConfiguration.fromRepository(repository, revision, registry(EXAMPLE_ALIAS));

            assertAll(
                    () -> assertFalse(snapshot.isOk(), "the revision does not resolve"),
                    () -> assertEquals(FormsResult.passOn(StoreResult.passOn(snapshot)), result,
                            "the Git outcome of the snapshot is passed on"));
        }

        @Test
        @DisplayName("GDR-GEN-3: a null argument is refused")
        void nullArguments(@TempDir Path directory, @InMemory Repository repository) {
            var registry = registry(name());
            var revision = name();
            Path nullPath = nullValue();
            Repository nullRepository = nullValue();
            String nullRevision = nullValue();
            RepositoryRegistry nullRegistry = nullValue();

            assertAll(
                    () -> assertThrows(NullPointerException.class,
                            () -> KindConfiguration.fromDirectory(nullPath, registry), "a null directory"),
                    () -> assertThrows(NullPointerException.class,
                            () -> KindConfiguration.fromDirectory(directory, nullRegistry), "a null registry"),
                    () -> assertThrows(NullPointerException.class,
                            () -> KindConfiguration.fromRepository(nullRepository, revision, registry),
                            "a null repository"),
                    () -> assertThrows(NullPointerException.class,
                            () -> KindConfiguration.fromRepository(repository, nullRevision, registry),
                            "a null revision"),
                    () -> assertThrows(NullPointerException.class,
                            () -> KindConfiguration.fromRepository(repository, revision, nullRegistry),
                            "a null registry"));
        }
    }

    @Nested
    @DisplayName("Physical repository")
    class PhysicalRepository {

        /** A registry holding two aliases of one remote. */
        private RepositoryRegistry twoAliasesOfOneRemote(String first, String second) {
            String remote = ORIGIN + "/" + name() + ".git";
            StoreResult<RepositoryRegistry> result = RepositoryRegistry.of(List.of(
                            new StoreRepository(first, remote, name(), null), new StoreRepository(second, remote, name(), null)),
                    RemotePolicy.httpsOrigin(URI.create(ORIGIN)));
            return Objects.requireNonNull(result.value(), () -> "the registry accepts both: " + result.detail());
        }

        @Test
        @DisplayName("GDR-FORMS-1: two aliases of one repository with one collection are refused as MALFORMED")
        void twoAliasesOneCollectionRefused(@TempDir Path directory) throws Exception {
            var first = Values.generate(name());
            var second = Values.generate(name());
            var keys = with(second.numbered("3"), "collection", first.collection());

            var result = KindConfiguration.fromDirectory(write(directory, yaml(first.perDocument(), keys)),
                    twoAliasesOfOneRemote(first.alias(), second.alias()));

            var values = new ArrayList<>(first.all());
            values.addAll(second.all());
            assertAll(
                    () -> assertEquals(MALFORMED, result.outcome(), "the configuration is refused"),
                    () -> assertEquals("kinds.yaml: kinds[1].collection: a collection another kind of the same "
                            + "repository uses", result.detail(), "the key path and the rule"),
                    () -> assertNoValue(result.detail(), values));
        }

        @Test
        @DisplayName("GDR-FORMS-1: two aliases of one repository with two collections load")
        void twoAliasesTwoCollectionsLoad(@TempDir Path directory) throws Exception {
            var first = Values.generate(name());
            var second = Values.generate(name());

            var configuration = loaded(KindConfiguration.fromDirectory(
                    write(directory, yaml(first.perDocument(), second.numbered("3"))),
                    twoAliasesOfOneRemote(first.alias(), second.alias())));

            assertEquals(2, configuration.kinds().size(), "both kinds");
        }
    }

    /** Prefixes within the form {@code [A-Za-z0-9_-]{0,16}}: the one of the example, the empty one, 16 characters. */
    static Stream<String> acceptedPrefixes() {
        return Stream.of("R-", "", "aZ_-09aZ_-09aZ_-");
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> @Nullable T nullValue() {
        return null;
    }
}
