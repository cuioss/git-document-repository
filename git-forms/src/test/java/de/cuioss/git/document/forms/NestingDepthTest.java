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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The one compiled nesting limit of data and schemas, {@value DataNode#MAX_DEPTH} nested mappings and sequences
 * (GDR-FORMS-2, GDR-FORMS-3), and the bound of a chain of schemas applied to the same value,
 * {@value InPlaceCycles#MAX_IN_PLACE_CHAIN} schemas: a value at the limit is read, validated, checked by
 * {@code required} and encoded on a thread with the default stack size; one level more is refused as
 * {@link StoreOutcome#MALFORMED} from text and with an {@link IllegalArgumentException} in memory, naming the limit and
 * no content (GDR-GEN-3, GDR-NFR-3); a value 100,000 levels deep, and a chain of 100,000 schemas, is refused without an
 * {@link Error}. {@link DocumentLayout} copies only the top level and needs no check, which the layout pin holds.
 * <p>
 * The key of the data is generated; the limit, the reasons and the shape of the schemas are the contract and kept as
 * literals.
 */
@EnableGeneratorController
@DisplayName("Nesting limit and in-place chain bound (GDR-GEN-3, GDR-FORMS-2, GDR-FORMS-3)")
class NestingDepthTest {

    private static final TypedGenerator<String> KEYS =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20));

    private static final int LIMIT = 128;

    private static final int FAR_BEYOND = 100_000;

    private static final String IN_MEMORY_REFUSAL = "not of the JSON data model: nested deeper than 128 levels";

    private static final String TEXT_REFUSAL = "a nesting deeper than 128 levels";

    private static final String CHAIN_REFUSAL = "a chain of schemas applied to the same value longer than 128";

    private static final FormsResult.Outcome MALFORMED = new FormsResult.PassedOn(StoreOutcome.MALFORMED);

    private final SchemaValidator validator = new SchemaValidator();

    private final YamlDocumentCodec codec =
            new YamlDocumentCodec(SchemaValidatorTest.schema(SchemaValidatorTest.SCHEMA));

    /** A mapping with one key whose value is a chain of sequences: {@code levels} containers, the mapping included. */
    private static Map<String, @Nullable Object> sequenceChain(String key, int levels) {
        Object value = new ArrayList<>();
        for (int level = 2; level < levels; level++) {
            List<Object> outer = new ArrayList<>();
            outer.add(value);
            value = outer;
        }
        Map<String, @Nullable Object> root = new LinkedHashMap<>();
        root.put(key, value);
        return root;
    }

    /** A chain of mappings, each holding the next under the key: {@code levels} containers. */
    private static Map<String, @Nullable Object> mappingChain(String key, int levels) {
        Map<String, @Nullable Object> value = new LinkedHashMap<>();
        for (int level = 1; level < levels; level++) {
            Map<String, @Nullable Object> outer = new LinkedHashMap<>();
            outer.put(key, value);
            value = outer;
        }
        return value;
    }

    /** The YAML of {@link #sequenceChain(String, int)}, the sequences in flow style. */
    private static String sequenceChainYaml(String key, int levels) {
        return key + ": " + "[".repeat(levels - 1) + "]".repeat(levels - 1) + "\n";
    }

    /** A schema that applies itself to the value of the key and to every item. */
    private static FormSchema recursiveSchema(String key) {
        return SchemaValidatorTest.schema("{ \"required\": [\"" + key + "\"], \"properties\": { \"" + key
                + "\": { \"$ref\": \"#\" } }, \"items\": { \"$ref\": \"#\" } }");
    }

    /** A schema file whose root holds, under an unknown keyword, {@code levels - 1} nested arrays. */
    private static byte[] nestedSchemaFile(int levels) {
        return ("{ \"x-nested\": " + "[".repeat(levels - 1) + "]".repeat(levels - 1) + " }")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * A schema file whose root starts a chain of {@code length} schemas applied to the same value: the root references
     * the first item of the list {@code x-chain}, each item the next, the last one requires {@code name}.
     */
    private static byte[] chainSchemaFile(int length) {
        var text = new StringBuilder("{\"$ref\":\"#/x-chain/0\",\"x-chain\":[");
        for (int index = 0; index < length - 2; index++) {
            text.append("{\"$ref\":\"#/x-chain/").append(index + 1).append("\"},");
        }
        text.append("{\"required\":[\"name\"]}]}");
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Runs an executable on a new thread with the default stack size, failing on whatever it throws. */
    private static void onDefaultStack(Executable executable) throws Throwable {
        AtomicReference<@Nullable Throwable> thrown = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                executable.execute();
            }
            // cui-rewrite:disable InvalidExceptionUsageRecipe
            catch (Throwable failure) {
                // an assertion failure or a StackOverflowError of the thread is handed to the test
                thrown.set(failure);
            }
        });
        thread.start();
        thread.join();
        Throwable failure = thrown.get();
        if (failure != null) {
            throw failure;
        }
    }

    private static void assertRefusedText(String key, String detail) {
        assertAll(
                () -> assertTrue(detail.matches("line \\d+, column \\d+: " + TEXT_REFUSAL), () -> "the limit: " + detail),
                () -> assertFalse(detail.contains(key), "no key in the detail"));
    }

    @Nested
    @DisplayName("At the limit")
    class AtTheLimit {

        @Test
        @DisplayName("GDR-FORMS-3: data nested 128 levels decodes and encodes back to the same text")
        void decodesAndEncodes() throws Throwable {
            var key = KEYS.next();
            var yaml = sequenceChainYaml(key, LIMIT);

            onDefaultStack(() -> {
                var decoded = codec.decode(yaml.getBytes(StandardCharsets.UTF_8));
                assertTrue(decoded.isOk(), () -> "decoded: " + decoded.detail());
                var data = Objects.requireNonNull(decoded.value());
                assertAll(
                        () -> assertEquals(sequenceChain(key, LIMIT), data, "the value read"),
                        () -> assertEquals(data, codec.decode(codec.encode(data)).value(),
                                "the encoding reads back"));
            });
        }

        @Test
        @DisplayName("GDR-FORMS-2: data nested 128 levels in sequences validates under a recursive schema")
        void sequencesValidate() throws Throwable {
            var key = KEYS.next();
            var schema = recursiveSchema(key);
            var data = sequenceChain(key, LIMIT);

            onDefaultStack(() -> assertAll(
                    () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, data), "validate"),
                    () -> assertEquals(new ValidationResult.Valid(), validator.required(schema, data), "required")));
        }

        @Test
        @DisplayName("GDR-FORMS-2: data nested 128 levels in mappings validates and required names the deepest field")
        void mappingsValidate() throws Throwable {
            var key = KEYS.next();
            var schema = recursiveSchema(key);
            var data = mappingChain(key, LIMIT);

            onDefaultStack(() -> assertAll(
                    () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, data), "validate"),
                    () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of(("/" + key).repeat(LIMIT))),
                            validator.required(schema, data), "required"),
                    () -> assertEquals(data, codec.decode(codec.encode(data)).value(), "encode")));
        }

        @Test
        @DisplayName("GDR-FORMS-2: a schema file nested 128 levels is accepted")
        void schemaFileAccepted() throws Throwable {
            onDefaultStack(() -> {
                var result = FormSchema.parse(PATH, nestedSchemaFile(LIMIT));
                assertTrue(result.isOk(), () -> "accepted: " + result.detail());
            });
        }

        @Test
        @DisplayName("GDR-FORMS-2: a chain of 128 schemas applied to the same value parses, validates and is checked")
        void chainAccepted() throws Throwable {
            onDefaultStack(() -> {
                var parsed = FormSchema.parse(PATH, chainSchemaFile(LIMIT));
                assertTrue(parsed.isOk(), () -> "accepted: " + parsed.detail());
                var schema = Objects.requireNonNull(parsed.value());
                assertAll(
                        () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, Map.of()),
                                "validate"),
                        () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/name")),
                                validator.required(schema, Map.of()), "required follows the whole chain"));
            });
        }
    }

    @Nested
    @DisplayName("One level beyond")
    class OneBeyond {

        @Test
        @DisplayName("GDR-FORMS-3: text nested 129 levels is MALFORMED, naming the limit and no content")
        void decodeRefused() {
            var key = KEYS.next();

            var result = codec.decode(sequenceChainYaml(key, LIMIT + 1).getBytes(StandardCharsets.UTF_8));

            assertAll(
                    () -> assertEquals(StoreOutcome.MALFORMED, result.outcome(), "the content is refused"),
                    () -> assertRefusedText(key, String.valueOf(result.detail())));
        }

        @Test
        @DisplayName("GDR-FORMS-2: DataNode refuses a value nested 129 levels, naming the limit")
        void rootRefused() {
            var data = sequenceChain(KEYS.next(), LIMIT + 1);

            var thrown = assertThrows(IllegalArgumentException.class, () -> DataNode.root(data));

            assertEquals(IN_MEMORY_REFUSAL, thrown.getMessage(), "the limit and nothing of the value");
        }

        @Test
        @DisplayName("GDR-FORMS-2: validate refuses data nested 129 levels")
        void validateRefused() {
            var key = KEYS.next();
            var schema = recursiveSchema(key);
            var data = sequenceChain(key, LIMIT + 1);

            var thrown = assertThrows(IllegalArgumentException.class, () -> validator.validate(schema, data));

            assertEquals(IN_MEMORY_REFUSAL, thrown.getMessage(), "the limit");
        }

        @Test
        @DisplayName("GDR-FORMS-2: required refuses data nested 129 levels")
        void requiredRefused() {
            var key = KEYS.next();
            var schema = recursiveSchema(key);
            var data = mappingChain(key, LIMIT + 1);

            var thrown = assertThrows(IllegalArgumentException.class, () -> validator.required(schema, data));

            assertEquals(IN_MEMORY_REFUSAL, thrown.getMessage(), "the limit");
        }

        @Test
        @DisplayName("GDR-FORMS-3: encode refuses data nested 129 levels")
        void encodeRefused() {
            var data = mappingChain(KEYS.next(), LIMIT + 1);

            var thrown = assertThrows(IllegalArgumentException.class, () -> codec.encode(data));

            assertEquals(IN_MEMORY_REFUSAL, thrown.getMessage(), "the limit");
        }

        @Test
        @DisplayName("GDR-FORMS-2: a schema file nested 129 levels is MALFORMED, naming the limit")
        void schemaFileRefused() {
            var result = FormSchema.parse(PATH, nestedSchemaFile(LIMIT + 1));

            var detail = String.valueOf(result.detail());
            assertAll(
                    () -> assertEquals(MALFORMED, result.outcome(), "the schema is refused"),
                    () -> assertTrue(detail.matches(PATH + ": line \\d+, column \\d+: " + TEXT_REFUSAL),
                            () -> "the file and the limit: " + detail),
                    () -> assertFalse(detail.contains("x-nested"), "nothing of the schema"));
        }

        @Test
        @DisplayName("GDR-FORMS-2: a chain of 129 schemas applied to the same value is MALFORMED, naming the bound")
        void chainRefused() {
            var result = FormSchema.parse(PATH, chainSchemaFile(LIMIT + 1));

            assertAll(
                    () -> assertEquals(MALFORMED, result.outcome(), "the schema is refused"),
                    () -> assertEquals(PATH + ": " + CHAIN_REFUSAL, result.detail(),
                            "the file and the fixed reason, nothing of the schema"));
        }
    }

    @Nested
    @DisplayName("Far beyond")
    class FarBeyond {

        @Test
        @DisplayName("GDR-FORMS-3: text nested 100,000 levels is MALFORMED, not an Error")
        void decodeRefused() {
            var key = KEYS.next();

            var result = codec.decode(sequenceChainYaml(key, FAR_BEYOND).getBytes(StandardCharsets.UTF_8));

            assertAll(
                    () -> assertEquals(StoreOutcome.MALFORMED, result.outcome(), "the content is refused"),
                    () -> assertRefusedText(key, String.valueOf(result.detail())));
        }

        @Test
        @DisplayName("GDR-FORMS-2: validate refuses data nested 100,000 levels, not with an Error")
        void validateRefused() {
            var key = KEYS.next();
            var schema = recursiveSchema(key);
            var data = sequenceChain(key, FAR_BEYOND);

            assertThrows(IllegalArgumentException.class, () -> validator.validate(schema, data));
        }

        @Test
        @DisplayName("GDR-FORMS-2: required refuses data nested 100,000 levels, not with an Error")
        void requiredRefused() {
            var key = KEYS.next();
            var schema = recursiveSchema(key);
            var data = mappingChain(key, FAR_BEYOND);

            assertThrows(IllegalArgumentException.class, () -> validator.required(schema, data));
        }

        @Test
        @DisplayName("GDR-FORMS-3: encode refuses data nested 100,000 levels, not with an Error")
        void encodeRefused() {
            var data = mappingChain(KEYS.next(), FAR_BEYOND);

            assertThrows(IllegalArgumentException.class, () -> codec.encode(data));
        }

        @Test
        @DisplayName("GDR-FORMS-2: a chain of 100,000 schemas applied to the same value is MALFORMED, not an Error")
        void chainRefused() {
            var result = FormSchema.parse(PATH, chainSchemaFile(FAR_BEYOND));

            assertEquals(PATH + ": " + CHAIN_REFUSAL, result.detail(), "the fixed reason");
        }

        @Test
        @DisplayName("GDR-FORMS-3: split and join copy only the top level, so a value 100,000 levels deep passes")
        void layoutCopiesTopLevelOnly() {
            var kind = new Kind(KEYS.next(), KEYS.next(), KEYS.next(), KEYS.next(), KEYS.next(), KEYS.next(), null);
            var layout = new DocumentLayout(kind, SchemaValidatorTest.schema(SchemaValidatorTest.SCHEMA));
            var deep = mappingChain(KEYS.next(), FAR_BEYOND);
            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            data.put("title", deep);
            data.put("details", Map.of("note", deep));

            var split = assertDoesNotThrow(() -> layout.split(data), "split");
            var joined = assertDoesNotThrow(() -> layout.join(split.document(), split.parts()), "join");

            assertAll(
                    () -> assertSame(deep, split.document().get("title"), "the document keeps the value"),
                    () -> assertSame(deep, Objects.requireNonNull(split.parts().get("details")).get("note"),
                            "the part keeps the value"),
                    () -> assertTrue(joined.isOk(), "the join succeeds"),
                    () -> assertSame(deep, Objects.requireNonNull(joined.value()).get("title"),
                            "the joined data keeps the value"));
        }
    }
}
