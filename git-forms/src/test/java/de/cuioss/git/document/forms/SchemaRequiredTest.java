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
 * the references within the schema file, cycles included, while {@link SchemaValidator#validate(FormSchema, Map)}
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
        @DisplayName("GDR-FORMS-2: a reference cycle ends and reports each missing field once")
        void referenceCycle() {
            var schema = schema("""
                    { "$ref": "#/$defs/a",
                      "$defs": { "a": { "$ref": "#/$defs/b", "required": ["x"] },
                                 "b": { "$ref": "#/$defs/a", "required": ["y"] } } }
                    """);

            assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/x", "/y")),
                    validator.required(schema, Map.of()), "the cycle is walked once");
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
