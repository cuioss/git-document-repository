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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import dev.harrel.jsonschema.SimpleType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Schema validation (GDR-FORMS-2) with {@code dev.harrel:json-schema} in Draft 2020-12: every violation with its JSON
 * Pointer and a message composed from keyword and constraint, a document with mandatory fields missing passing
 * {@link SchemaValidator#validate(FormSchema, Map)}, {@link SchemaValidator#required(FormSchema, Map)} returning exactly
 * the pointers of the missing fields, nested ones included; and reading a schema: the declaration order, the split
 * parts and every refusal as {@link StoreOutcome#MALFORMED} naming the schema file.
 * <p>
 * The schema is a literal, being the contract; the strings of the data are generated, the numbers that violate a
 * constraint follow from the constraint.
 */
@EnableGeneratorController
@DisplayName("SchemaValidator and FormSchema (GDR-FORMS-2)")
class SchemaValidatorTest {

    /** The path of the schema file, named in every detail of a refused schema. */
    static final String PATH = "schemas/item.schema.json";

    /** A schema of the dialect with mandatory fields on the root, in a split part and in the items of an array. */
    static final String SCHEMA = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "required": ["title", "owner"],
              "properties": {
                "title": { "type": "string", "maxLength": 10 },
                "owner": { "type": "string" },
                "score": { "type": "integer", "minimum": 1, "maximum": 5 },
                "details": {
                  "type": "object",
                  "gdr-split": true,
                  "required": ["reason"],
                  "properties": { "reason": { "type": "string" }, "note": { "type": "string" } }
                },
                "tags": {
                  "type": "array",
                  "items": { "type": "object", "required": ["name"], "properties": { "name": { "type": "string" } } }
                }
              }
            }
            """;

    private static final FormsResult.Outcome MALFORMED = new FormsResult.PassedOn(StoreOutcome.MALFORMED);

    private final SchemaValidator validator = new SchemaValidator();

    static FormSchema schema(String text) {
        var result = FormSchema.parse(PATH, text.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isOk(), () -> "the schema is read: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the schema");
    }

    private static String text(int maxLength) {
        return Generators.letterStrings(1, maxLength).next();
    }

    /** Data that satisfies every keyword of {@link #SCHEMA}, {@code required} included. */
    private static Map<String, @Nullable Object> complete() {
        Map<String, @Nullable Object> data = new LinkedHashMap<>();
        data.put("title", text(10));
        data.put("owner", text(20));
        data.put("score", (long) Generators.integers(1, 5).next());
        data.put("details", new LinkedHashMap<>(Map.of("reason", text(20))));
        data.put("tags", List.of(Map.of("name", text(20))));
        return data;
    }

    @Nested
    @DisplayName("validate")
    class Validate {

        @Test
        @DisplayName("GDR-FORMS-2: data that satisfies the schema is valid")
        void valid() {
            assertEquals(new ValidationResult.Valid(), validator.validate(schema(SCHEMA), complete()),
                    "complete data is valid");
        }

        @Test
        @DisplayName("GDR-FORMS-2: a document with mandatory fields missing passes validate")
        void mandatoryFieldsMissingPassValidate() {
            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            data.put("details", new LinkedHashMap<>());
            data.put("tags", List.of(Map.of()));

            assertEquals(new ValidationResult.Valid(), validator.validate(schema(SCHEMA), data),
                    "required is not evaluated by validate");
        }

        @Test
        @DisplayName("GDR-FORMS-2: every violation is reported with its JSON Pointer and message")
        void everyViolation() {
            var data = complete();
            data.put("title", Generators.letterStrings(11, 30).next());
            data.put("owner", (long) Generators.integers(0, 1000).next());
            data.put("score", 6L);
            data.put("tags", List.of(Map.of("name", true)));

            var result = validator.validate(schema(SCHEMA), data);

            var invalid = assertInstanceOf(ValidationResult.Invalid.class, result, "the data is invalid");
            assertAll(
                    () -> assertEquals(Set.of(
                                    new Violation("/title", "must have at most 10 characters"),
                                    new Violation("/owner", "must be of type string"),
                                    new Violation("/score", "must be at most 5"),
                                    new Violation("/tags/0/name", "must be of type string")),
                            new HashSet<>(invalid.violations()), "every violation, pointer and message"),
                    () -> assertEquals(FormsOutcome.INVALID, result.outcome(), "the outcome is INVALID"),
                    () -> assertEquals(Optional.of("4 violations"), result.detail(), "the detail names the number"));
        }
    }

    @Nested
    @DisplayName("required")
    class Required {

        @Test
        @DisplayName("GDR-FORMS-2: required returns exactly the pointers of the missing fields, nested ones included")
        void missingFields() {
            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            data.put("owner", text(20));
            data.put("details", new LinkedHashMap<>(Map.of("note", text(20))));
            data.put("tags", List.of(Map.of("name", text(20)), Map.of()));

            var result = validator.required(schema(SCHEMA), data);

            assertAll(
                    () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(
                            List.of("/title", "/details/reason", "/tags/1/name")), result, "the missing fields"),
                    () -> assertEquals(FormsOutcome.MANDATORY_FIELDS_MISSING, result.outcome(), "the outcome"),
                    () -> assertEquals(Optional.of("3 mandatory fields missing"), result.detail(), "the detail"));
        }

        @Test
        @DisplayName("GDR-FORMS-2: complete data has no mandatory field missing")
        void completeData() {
            assertEquals(new ValidationResult.Valid(), validator.required(schema(SCHEMA), SchemaValidatorTest.complete()),
                    "no field is missing");
        }

        @Test
        @DisplayName("GDR-FORMS-2: a key that needs escaping is escaped in the pointer (RFC 6901)")
        void escapedPointer() {
            var schema = schema("""
                    { "type": "object", "required": ["a/b", "c~d"] }
                    """);

            assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/a~1b", "/c~0d")),
                    validator.required(schema, Map.of()), "the pointers are escaped");
        }
    }

    @Nested
    @DisplayName("FormSchema")
    class Schema {

        @Test
        @DisplayName("GDR-FORMS-3: the schema gives the declaration order and the split parts")
        void orderAndSplit() {
            var schema = schema(SCHEMA);

            assertAll(
                    () -> assertEquals(PATH, schema.path(), "the path"),
                    () -> assertEquals(List.of("title", "owner", "score", "details", "tags"), schema.properties(),
                            "the top-level properties in declaration order"),
                    () -> assertEquals(List.of("details"), schema.splitProperties(), "the split part"));
        }

        @Test
        @DisplayName("GDR-FORMS-2: a schema without $schema is read in the dialect Draft 2020-12")
        void withoutDialect() {
            var schema = schema("""
                    { "type": "object", "properties": { "count": { "type": "integer" } } }
                    """);

            var result = validator.validate(schema, Map.of("count", "x"));

            assertEquals(new ValidationResult.Invalid(List.of(new Violation("/count", "must be of type integer"))),
                    result, "the schema is evaluated");
        }

        static Stream<Arguments> refusedSchemas() {
            return Stream.of(
                    Arguments.of("text that is not JSON", "{ \"type\": [", "line 1, column 12: not well-formed YAML"),
                    Arguments.of("a root that is not an object", "[]", "the root: not an object"),
                    Arguments.of("another dialect", "{ \"$schema\": \"http://json-schema.org/draft-07/schema#\" }",
                            "$schema: not the dialect Draft 2020-12"),
                    Arguments.of("a reference outside the file", "{ \"$ref\": \"other.schema.json\" }",
                            "$ref: a reference outside the schema file"),
                    Arguments.of("gdr-split that is not a boolean",
                            "{ \"properties\": { \"a\": { \"type\": \"object\", \"gdr-split\": \"yes\" } } }",
                            "gdr-split: not a boolean"),
                    Arguments.of("gdr-split on a nested property",
                            "{ \"properties\": { \"a\": { \"type\": \"object\", \"properties\": "
                                    + "{ \"b\": { \"type\": \"object\", \"gdr-split\": true } } } } }",
                            "gdr-split: only on a top-level property of type object"),
                    Arguments.of("gdr-split on a property that is not an object",
                            "{ \"properties\": { \"a\": { \"type\": \"string\", \"gdr-split\": true } } }",
                            "gdr-split: only on a top-level property of type object"),
                    Arguments.of("a schema against the meta-schema", "{ \"type\": 5 }",
                            "not a JSON Schema the validator accepts"),
                    Arguments.of("required that is a boolean", "{ \"required\": true }",
                            "required: not an array of unique strings"),
                    Arguments.of("required with an item that is not a string", "{ \"required\": [\"a\", 1] }",
                            "required: not an array of unique strings"),
                    Arguments.of("required with a duplicate name", "{ \"required\": [\"a\", \"a\"] }",
                            "required: not an array of unique strings"),
                    Arguments.of("required that is not an array in a sub-schema",
                            "{ \"properties\": { \"a\": { \"type\": \"object\", \"required\": \"b\" } } }",
                            "required: not an array of unique strings"),
                    Arguments.of("a reference outside the file in contentSchema",
                            "{ \"contentSchema\": { \"$ref\": \"other.schema.json\" } }",
                            "$ref: a reference outside the schema file"));
        }

        @ParameterizedTest(name = "GDR-FORMS-2: {0} is MALFORMED, naming the schema file")
        @MethodSource("refusedSchemas")
        void refused(String description, String text, String problem) {
            var result = FormSchema.parse(PATH, text.getBytes(StandardCharsets.UTF_8));

            assertAll(
                    () -> assertEquals(MALFORMED, result.outcome(), "the schema is refused"),
                    () -> assertEquals(PATH + ": " + problem, result.detail(), "the detail names the file"));
        }

        @Test
        @DisplayName("GDR-FORMS-2: a schema that is not UTF-8 is MALFORMED")
        void notUtf8() {
            var result = FormSchema.parse(PATH, new byte[]{(byte) 0xC3, (byte) 0x28});

            assertEquals(PATH + ": not UTF-8", result.detail(), "the detail names the file");
        }

        @Test
        @DisplayName("GDR-GEN-3: a null argument is refused")
        void nullArguments() {
            byte[] content = SCHEMA.getBytes(StandardCharsets.UTF_8);
            var schema = schema(SCHEMA);
            Map<String, @Nullable Object> data = Map.of();
            String nullPath = nullValue();
            byte[] nullContent = nullValue();
            FormSchema nullSchema = nullValue();
            Map<String, @Nullable Object> nullData = nullValue();

            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> FormSchema.parse(nullPath, content)),
                    () -> assertThrows(NullPointerException.class, () -> FormSchema.parse(PATH, nullContent)),
                    () -> assertThrows(NullPointerException.class, () -> validator.validate(nullSchema, data)),
                    () -> assertThrows(NullPointerException.class, () -> validator.validate(schema, nullData)),
                    () -> assertThrows(NullPointerException.class, () -> validator.required(nullSchema, data)),
                    () -> assertThrows(NullPointerException.class, () -> validator.required(schema, nullData)));
        }

        @Test
        @DisplayName("GDR-FORMS-2: contentSchema is a sub-schema, so required is removed from it for validate")
        void contentSchemaIsSubSchema() {
            var schema = schema("""
                    {
                      "type": "object",
                      "contentSchema": { "type": "object", "required": ["x"] },
                      "properties": { "a": { "$ref": "#/contentSchema" } }
                    }
                    """);
            Map<String, @Nullable Object> data = Map.of("a", Map.of());

            assertEquals(new ValidationResult.Valid(), validator.validate(schema, data),
                    "required inside contentSchema is not evaluated by validate");
        }
    }

    @Nested
    @DisplayName("data model")
    class DataModel {

        @Test
        @DisplayName("GDR-FORMS-2: a value outside the data model is refused, also where no keyword leads")
        void valueOutsideDataModel() {
            var schema = schema("{}");
            Map<String, @Nullable Object> data = Map.of("values", List.of(1.5d));

            assertThrows(IllegalArgumentException.class, () -> validator.validate(schema, data),
                    "a Double inside a list is not of the data model");
        }

        @Test
        @DisplayName("GDR-FORMS-2: a decimal without fraction is an integer up to the digit limit, whatever its scale")
        void integralDecimals() {
            var hugeExponent = new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE);

            assertAll(
                    () -> assertEquals(SimpleType.INTEGER, DataNode.root(new BigDecimal("1e3")).getNodeType(),
                            "a negative scale is integral"),
                    () -> assertEquals(SimpleType.NUMBER, DataNode.root(hugeExponent).getNodeType(),
                            "a number beyond the digit limit is of the type number and does not throw"),
                    () -> assertEquals(SimpleType.INTEGER, DataNode.root(new BigDecimal("1.00")).getNodeType(),
                            "trailing zeros are integral"),
                    () -> assertEquals(SimpleType.NUMBER, DataNode.root(new BigDecimal("1.50")).getNodeType(),
                            "a fraction is a number"));
        }
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    @SuppressWarnings("unchecked")
    private static <T> T nullValue() {
        return (T) null;
    }
}
