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

import static de.cuioss.git.document.forms.SchemaValidatorTest.PATH;
import static de.cuioss.git.document.forms.SchemaValidatorTest.schema;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The handling of the keyword {@code required} (GDR-FORMS-2): {@link SchemaValidator#required(FormSchema, Map)} follows
 * the references within the schema file, cycles included, and applies {@code patternProperties},
 * {@code additionalProperties}, {@code prefixItems}, {@code items}, {@code dependentSchemas},
 * {@code unevaluatedProperties} and {@code unevaluatedItems} as JSON Schema Draft 2020-12 applies them, while
 * {@link SchemaValidator#validate(FormSchema, Map)}
 * still ignores {@code required}; and {@link FormSchema#parse(String, byte[])} refuses {@code required} under a keyword
 * that decides between alternatives or conditions, also when reached through a reference.
 * <p>
 * The schemas are literals, being the contract.
 */
@EnableGeneratorController
@DisplayName("Handling of required in schema validation (GDR-FORMS-2)")
class SchemaRequiredTest {

    private static final FormsResult.Outcome MALFORMED = new FormsResult.PassedOn(StoreOutcome.MALFORMED);

    private final SchemaValidator validator = new SchemaValidator();

    /**
     * The flat definitions of the long chain, side by side under {@code $defs}: 55,000, as many as fit, with names in
     * base 36, within the 3 Mi code points the reader reads.
     */
    private static final int FLAT_DEFINITIONS = 55_000;

    /**
     * A schema whose {@code anyOf} references the first of {@code count} flat definitions, each reaching the next
     * through {@code properties} and {@code $ref}; the last one holds {@code last}. {@code properties} descends, so the
     * bound of a chain applied to the same value does not count it, and the check of {@code required} under
     * {@code anyOf} follows the whole chain.
     */
    private static byte[] flatChain(int count, String last) {
        var text = new StringBuilder("{\"anyOf\":[{\"$ref\":\"#/$defs/0\"}],\"$defs\":{");
        for (int index = 0; index < count - 1; index++) {
            text.append('"').append(Integer.toString(index, 36)).append("\":{\"properties\":{\"p\":{\"$ref\":\"#/$defs/")
                    .append(Integer.toString(index + 1, 36)).append("\"}}},");
        }
        text.append('"').append(Integer.toString(count - 1, 36)).append("\":").append(last).append("}}");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("long chains")
    class LongChains {

        /** Parses a schema file on a thread with the default stack size, as {@link NestingDepthTest} runs its cases. */
        private FormsResult<FormSchema> parseOnDefaultStack(byte[] file) throws Throwable {
            AtomicReference<@Nullable FormsResult<FormSchema>> parsed = new AtomicReference<>();
            NestingDepthTest.onDefaultStack(() -> parsed.set(FormSchema.parse(PATH, file)));
            return Objects.requireNonNull(parsed.get(), "the parse returned");
        }

        @Test
        @DisplayName("GDR-FORMS-2: a schema of 55,000 flat $defs chained through properties to $ref parses")
        void flatChainWithoutRequiredParses() throws Throwable {
            var result = parseOnDefaultStack(flatChain(FLAT_DEFINITIONS, "{}"));

            assertTrue(result.isOk(), () -> "a result, accepted: " + result.detail());
        }

        @Test
        @DisplayName("GDR-FORMS-2: required at the end of 55,000 flat $defs under anyOf is refused as MALFORMED")
        void flatChainWithRequiredRefused() throws Throwable {
            var result = parseOnDefaultStack(flatChain(FLAT_DEFINITIONS, "{\"required\":[\"p\"]}"));

            assertAll(
                    () -> assertEquals(MALFORMED, result.outcome(), "the schema is refused"),
                    () -> assertEquals(PATH + ": required: not allowed under anyOf, where it cannot be deferred",
                            result.detail(), "the fixed reason"));
        }
    }

    @Nested
    @DisplayName("references")
    class References {

        @Test
        @DisplayName("GDR-FORMS-2: required follows a same-file $ref, validate still ignores required")
        void sameFileReference() {
            var schema = schema("""
                    { "$ref": "#/$defs/contact",
                      "$defs": { "contact": { "type": "object", "required": ["name"] } } }
                    """);
            Map<String, @Nullable Object> data = Map.of();

            assertAll(
                    () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/name")),
                            validator.required(schema, data), "the field of the referenced definition is missing"),
                    () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, data),
                            "validate does not evaluate required in the referenced definition"));
        }

        @Test
        @DisplayName("GDR-FORMS-2: required follows a $dynamicRef to a $dynamicAnchor within a property")
        void dynamicReference() {
            var schema = schema("""
                    { "type": "object",
                      "properties": { "person": { "$dynamicRef": "#person" } },
                      "$defs": { "person": { "$dynamicAnchor": "person", "type": "object", "required": ["name"] } } }
                    """);

            assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/person/name")),
                    validator.required(schema, Map.of("person", Map.of())), "the nested field is missing");
        }

        @Test
        @DisplayName("GDR-FORMS-2: a recursive schema is checked as deep as the data goes")
        void recursiveSchema() {
            var schema = schema("""
                    { "type": "object", "required": ["name"],
                      "properties": { "name": { "type": "string" }, "child": { "$ref": "#" } } }
                    """);
            var name = Generators.letterStrings(1, 20).next();
            Map<String, @Nullable Object> data = Map.of("name", name, "child", Map.of("child", Map.of()));

            assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/child/name", "/child/child/name")),
                    validator.required(schema, data), "every level of the data is checked");
        }

        @Test
        @DisplayName("GDR-FORMS-2: a definition applied twice to one value reports each missing field once")
        void referencedTwice() {
            var schema = schema("""
                    { "allOf": [ { "$ref": "#/$defs/a" }, { "$ref": "#/$defs/b" } ],
                      "$defs": { "a": { "$ref": "#/$defs/b", "required": ["x"] },
                                 "b": { "required": ["y"] } } }
                    """);

            assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/x", "/y")),
                    validator.required(schema, Map.of()), "the definition applied twice is walked once");
        }
    }

    @Nested
    @DisplayName("applicator positions")
    class ApplicatorPositions {

        static Stream<Arguments> positions() {
            return Stream.of(
                    Arguments.of("patternProperties checks a matching property",
                            "{ \"patternProperties\": { \"^entry-\": { \"required\": [\"name\"] } } }",
                            Map.of("entry-a", Map.of()), List.of("/entry-a/name")),
                    Arguments.of("patternProperties does not check a property no pattern matches",
                            "{ \"patternProperties\": { \"^entry-\": { \"required\": [\"name\"] } } }",
                            Map.of("other", Map.of()), List.of()),
                    Arguments.of("a pattern matches anywhere in the name, not anchored",
                            "{ \"patternProperties\": { \"entry\": { \"required\": [\"name\"] } } }",
                            Map.of("my-entry-1", Map.of()), List.of("/my-entry-1/name")),
                    Arguments.of("a property that matches two patterns is checked by both",
                            "{ \"patternProperties\": { \"^entry-\": { \"required\": [\"name\"] }, "
                                    + "\"-a$\": { \"required\": [\"id\"] } } }",
                            Map.of("entry-a", Map.of()), List.of("/entry-a/name", "/entry-a/id")),
                    Arguments.of("additionalProperties checks a property nothing else selects",
                            "{ \"additionalProperties\": { \"required\": [\"name\"] } }",
                            Map.of("entry", Map.of()), List.of("/entry/name")),
                    Arguments.of("additionalProperties does not check a property properties or a pattern selects",
                            "{ \"properties\": { \"a\": { \"type\": \"object\" } }, "
                                    + "\"patternProperties\": { \"^p-\": { \"type\": \"object\" } }, "
                                    + "\"additionalProperties\": { \"required\": [\"name\"] } }",
                            Map.of("a", Map.of(), "p-x", Map.of()), List.of()),
                    Arguments.of("prefixItems checks the item at its position",
                            "{ \"properties\": { \"rows\": { \"prefixItems\": [ { \"required\": [\"name\"] } ] } } }",
                            Map.of("rows", List.of(Map.of())), List.of("/rows/0/name")),
                    Arguments.of("prefixItems does not require an absent item",
                            "{ \"properties\": { \"rows\": { \"prefixItems\": [ { \"required\": [\"name\"] } ] } } }",
                            Map.of("rows", List.of()), List.of()),
                    Arguments.of("items does not apply to the items of prefixItems",
                            "{ \"properties\": { \"rows\": { \"prefixItems\": [ {} ], "
                                    + "\"items\": { \"required\": [\"tail\"] } } } }",
                            Map.of("rows", List.of(Map.of(), Map.of("tail", 1))), List.of()),
                    Arguments.of("items applies from the number of entries of prefixItems onwards",
                            "{ \"properties\": { \"rows\": { \"prefixItems\": [ {} ], "
                                    + "\"items\": { \"required\": [\"tail\"] } } } }",
                            Map.of("rows", List.of(Map.of(), Map.of())), List.of("/rows/1/tail")),
                    Arguments.of("dependentSchemas applies when the object holds its property",
                            "{ \"dependentSchemas\": { \"enabled\": { \"required\": [\"name\"] } } }",
                            Map.of("enabled", true), List.of("/name")),
                    Arguments.of("dependentSchemas does not apply when the object lacks its property",
                            "{ \"dependentSchemas\": { \"enabled\": { \"required\": [\"name\"] } } }",
                            Map.of(), List.of()),
                    Arguments.of("a cycle through additionalProperties ends with the data",
                            "{ \"$ref\": \"#/$defs/node\", \"$defs\": { \"node\": { \"required\": [\"id\"], "
                                    + "\"additionalProperties\": { \"$ref\": \"#/$defs/node\" } } } }",
                            Map.of("id", 1, "a", Map.of("b", Map.of())), List.of("/a/id", "/a/b/id")),
                    Arguments.of("a cycle through patternProperties ends with the data",
                            "{ \"$ref\": \"#/$defs/node\", \"$defs\": { \"node\": { \"required\": [\"id\"], "
                                    + "\"patternProperties\": { \"^n\": { \"$ref\": \"#/$defs/node\" } } } } }",
                            Map.of("id", 1, "n1", Map.of("n2", Map.of())), List.of("/n1/id", "/n1/n2/id")));
        }

        @ParameterizedTest(name = "GDR-FORMS-2: {0}")
        @MethodSource("positions")
        void position(String description, String text, Map<String, @Nullable Object> data, List<String> missing) {
            var schema = schema(text);

            assertAll(
                    () -> assertEquals(missing.isEmpty() ? new ValidationResult.Valid()
                                    : new ValidationResult.MandatoryFieldsMissing(missing),
                            validator.required(schema, data), "required reports exactly the missing fields"),
                    () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, data),
                            "validate does not evaluate required"));
        }
    }

    @Nested
    @DisplayName("unevaluated positions")
    class UnevaluatedPositions {

        private static final String REST = "\"unevaluatedProperties\": { \"required\": [\"name\"] }";
        private static final String REST_ITEMS = "\"unevaluatedItems\": { \"required\": [\"name\"] }";

        static Stream<Arguments> positions() {
            return Stream.of(
                    Arguments.of("unevaluatedProperties checks a property nothing evaluates",
                            "{ " + REST + " }", Map.of("entry", Map.of()), List.of("/entry/name")),
                    Arguments.of("unevaluatedProperties requires nothing of an absent property",
                            "{ " + REST + " }", Map.of(), List.of()),
                    Arguments.of("a property of a sibling properties entry is evaluated",
                            "{ \"properties\": { \"entry\": { \"type\": \"object\" } }, " + REST + " }",
                            Map.of("entry", Map.of(), "other", Map.of()), List.of("/other/name")),
                    Arguments.of("a property a sibling pattern matches is evaluated",
                            "{ \"patternProperties\": { \"^p-\": {} }, " + REST + " }",
                            Map.of("p-a", Map.of(), "other", Map.of()), List.of("/other/name")),
                    Arguments.of("a property the target of a reference declares is evaluated",
                            "{ \"$ref\": \"#/$defs/base\", \"$defs\": { \"base\": { \"properties\": { \"entry\": {} } } },"
                                    + " " + REST + " }",
                            Map.of("entry", Map.of(), "other", Map.of()), List.of("/other/name")),
                    Arguments.of("a property an allOf member declares is evaluated",
                            "{ \"allOf\": [ { \"properties\": { \"entry\": {} } } ], " + REST + " }",
                            Map.of("entry", Map.of()), List.of()),
                    Arguments.of("only the anyOf member the value satisfies evaluates",
                            "{ \"anyOf\": [ { \"properties\": { \"a\": { \"type\": \"object\" } } }, "
                                    + "{ \"properties\": { \"b\": { \"minProperties\": 1 } } } ], " + REST + " }",
                            Map.of("a", Map.of(), "b", Map.of()), List.of("/b/name")),
                    Arguments.of("then evaluates when if holds",
                            "{ \"if\": { \"properties\": { \"kind\": { \"const\": \"x\" } } }, "
                                    + "\"then\": { \"properties\": { \"entry\": {} } }, " + REST + " }",
                            Map.of("kind", "x", "entry", Map.of()), List.of()),
                    Arguments.of("then does not evaluate when if fails",
                            "{ \"if\": { \"properties\": { \"kind\": { \"const\": \"x\" } } }, "
                                    + "\"then\": { \"properties\": { \"entry\": {} } }, " + REST + " }",
                            Map.of("kind", "y", "entry", Map.of()), List.of("/entry/name")),
                    Arguments.of("an applied unevaluatedProperties evaluates every remaining property",
                            "{ \"allOf\": [ { \"unevaluatedProperties\": { \"type\": \"object\" } } ], " + REST + " }",
                            Map.of("entry", Map.of()), List.of()),
                    Arguments.of("unevaluatedItems checks the items after prefixItems",
                            "{ \"properties\": { \"rows\": { \"prefixItems\": [ {} ], " + REST_ITEMS + " } } }",
                            Map.of("rows", List.of(Map.of(), Map.of())), List.of("/rows/1/name")),
                    Arguments.of("an item contains matches is evaluated",
                            "{ \"properties\": { \"rows\": { \"contains\": { \"minProperties\": 1 }, " + REST_ITEMS
                                    + " } } }",
                            Map.of("rows", List.of(Map.of("a", 1), Map.of())), List.of("/rows/1/name")),
                    Arguments.of("items evaluates every item",
                            "{ \"properties\": { \"rows\": { \"items\": {}, " + REST_ITEMS + " } } }",
                            Map.of("rows", List.of(Map.of())), List.of()));
        }

        @ParameterizedTest(name = "GDR-FORMS-2: {0}")
        @MethodSource("positions")
        void position(String description, String text, Map<String, @Nullable Object> data, List<String> missing) {
            var schema = schema(text);

            assertAll(
                    () -> assertEquals(missing.isEmpty() ? new ValidationResult.Valid()
                                    : new ValidationResult.MandatoryFieldsMissing(missing),
                            validator.required(schema, data), "required reports exactly the unevaluated fields"),
                    () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, data),
                            "validate does not evaluate required"));
        }
    }

    @Nested
    @DisplayName("conditional positions")
    class ConditionalPositions {

        static Stream<Arguments> refusedSchemas() {
            return Stream.of(
                    Arguments.of("oneOf", "{ \"oneOf\": [ { \"required\": [\"a\"] }, { \"type\": \"object\" } ] }"),
                    Arguments.of("anyOf", "{ \"anyOf\": [ { \"type\": \"object\", \"required\": [\"a\"] } ] }"),
                    Arguments.of("not", "{ \"not\": { \"required\": [\"a\"] } }"),
                    Arguments.of("if", "{ \"if\": { \"required\": [\"a\"] }, \"then\": { \"type\": \"object\" } }"),
                    Arguments.of("then", "{ \"if\": { \"type\": \"object\" }, \"then\": { \"required\": [\"a\"] } }"),
                    Arguments.of("else", "{ \"if\": { \"type\": \"object\" }, \"else\": { \"required\": [\"a\"] } }"),
                    Arguments.of("contains", "{ \"contains\": { \"required\": [\"a\"] } }"),
                    Arguments.of("anyOf",
                            "{ \"anyOf\": [ { \"properties\": { \"a\": { \"required\": [\"b\"] } } } ] }"),
                    Arguments.of("oneOf", "{ \"oneOf\": [ { \"$ref\": \"#/$defs/a\" }, { \"type\": \"string\" } ], "
                            + "\"$defs\": { \"a\": { \"type\": \"object\", \"required\": [\"b\"] } } }"),
                    Arguments.of("not", "{ \"not\": { \"$ref\": \"#/$defs/a\" }, "
                            + "\"$defs\": { \"a\": { \"$ref\": \"#/$defs/b\" }, \"b\": { \"required\": [\"c\"] } } }"));
        }

        @ParameterizedTest(name = "GDR-FORMS-2: required under {0} is MALFORMED, naming the schema file: {1}")
        @MethodSource("refusedSchemas")
        void refused(String keyword, String text) {
            var result = FormSchema.parse(PATH, text.getBytes(StandardCharsets.UTF_8));

            assertAll(
                    () -> assertEquals(MALFORMED, result.outcome(), "the schema is refused"),
                    () -> assertEquals(PATH + ": required: not allowed under " + keyword
                            + ", where it cannot be deferred", result.detail(), "the detail names file and keyword"));
        }

        @Test
        @DisplayName("GDR-FORMS-2: required at object level, under properties, items and in referenced definitions is "
                + "accepted")
        void acceptedPositions() {
            var text = """
                    { "type": "object", "required": ["a"],
                      "properties": {
                        "a": { "type": "object", "required": ["b"] },
                        "list": { "type": "array", "items": { "required": ["c"] } },
                        "choice": { "oneOf": [ { "$ref": "#/$defs/plain" }, { "type": "string" } ] },
                        "contact": { "$ref": "#/$defs/contact" } },
                      "allOf": [ { "required": ["d"] } ],
                      "$defs": { "plain": { "type": "object" }, "contact": { "required": ["name"] } } }
                    """;

            var result = FormSchema.parse(PATH, text.getBytes(StandardCharsets.UTF_8));

            assertTrue(result.isOk(), () -> "the schema is accepted: " + result.detail());
        }
    }
}
