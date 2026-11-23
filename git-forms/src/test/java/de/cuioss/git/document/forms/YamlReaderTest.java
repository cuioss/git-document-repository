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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reading YAML into the JSON data model of ADR-0008: mappings in the key order of the source, sequences, and plain
 * scalars resolved by the YAML 1.2 core schema while quoted scalars stay strings; and every refusal as the passed-on
 * {@link StoreOutcome#MALFORMED} with a detail that names the position (line and column, counted from 1) and the
 * problem, never the content (GDR-NFR-3): an anchor, an alias, an explicit tag, a key that is not a string, a duplicate
 * key, an infinite number or not-a-number, an exponent beyond the range of {@code int}, a lone surrogate from an
 * escape, more than one document, a document without a value, and text that is not well-formed YAML; and
 * {@link YamlReader#readMapping(String, String)}, which returns the root mapping typed and refuses any other root with
 * the detail its caller names.
 * <p>
 * Keys, anchor and tag names are generated from lower-case letters, at least twelve long, so that a plain key always
 * resolves to a string and no generated text can occur in the fixed text of a detail; values are generated between
 * tildes. The scalars of the core schema ({@code true}, {@code 0x1f}, {@code .inf}, ...) are YAML vocabulary and kept
 * as literals.
 */
@EnableGeneratorController
@DisplayName("YamlReader (ADR-0008 data model)")
class YamlReaderTest {

    /** The passed-on outcome of a refused text. */
    private static final FormsResult.Outcome MALFORMED = new FormsResult.PassedOn(StoreOutcome.MALFORMED);

    /** Names: lower-case letters only, unique within the run. */
    private static final TypedGenerator<String> NAMES =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20));

    private final YamlReader reader = new YamlReader();

    private static String name() {
        return NAMES.next();
    }

    /** A value between tildes, which no fixed text of a detail contains. */
    private static String marker() {
        return "~" + Generators.letterStrings(4, 16).next() + "~";
    }

    /** A YAML double-quoted scalar of a text without quotes and backslashes. */
    private static String quoted(String text) {
        return "\"" + text + "\"";
    }

    /** The value of a successful read. */
    private static Object value(FormsResult<Object> result) {
        assertTrue(result.isOk(), () -> "the text is read: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the value");
    }

    /** Asserts a refusal with exactly the detail, carrying none of the content. */
    private static void assertRefused(String detail, List<String> content, FormsResult<Object> result) {
        assertAll(
                () -> assertEquals(MALFORMED, result.outcome(), "the text is refused as MALFORMED"),
                () -> assertEquals(detail, result.detail(), "the detail names the position and the problem"),
                () -> assertAll(content.stream().<Executable>map(text -> () -> assertFalse(
                        String.valueOf(result.detail()).contains(text),
                        () -> "the detail carries no content: " + result.detail()))));
    }

    @Nested
    @DisplayName("Data model")
    class DataModel {

        @Test
        @DisplayName("ADR-0008: a mapping keeps the key order of the source")
        void keyOrder() {
            List<String> keys = IntStream.range(0, Generators.integers(5, 12).next()).mapToObj(index -> name())
                    .toList();
            String yaml = keys.stream().map(key -> key + ": " + quoted(marker())).collect(Collectors.joining("\n"));

            var value = value(reader.read(yaml));

            Map<?, ?> mapping = assertInstanceOf(LinkedHashMap.class, value, "a mapping is a LinkedHashMap");
            assertEquals(keys, new ArrayList<>(mapping.keySet()), "the keys in the order of the source");
        }

        @Test
        @DisplayName("ADR-0008: plain scalars resolve by the YAML 1.2 core schema, quoted scalars stay strings")
        void scalars() {
            var text = marker();
            var word = name();
            Map<String, String> yaml = new LinkedHashMap<>();
            Map<String, @Nullable Object> expected = new LinkedHashMap<>();
            Map<String, @Nullable Object> cases = new LinkedHashMap<>();
            cases.put(quoted(text), text);
            cases.put(word, word);
            cases.put("'true'", "true");
            cases.put("\"42\"", "42");
            cases.put("true", Boolean.TRUE);
            cases.put("False", Boolean.FALSE);
            cases.put("42", 42L);
            cases.put("-7", -7L);
            cases.put("0o17", 15L);
            cases.put("0x1f", 31L);
            cases.put("9223372036854775808", new BigInteger("9223372036854775808"));
            cases.put("1.5", new BigDecimal("1.5"));
            cases.put("1e3", new BigDecimal("1e3"));
            cases.put("~", null);
            cases.put("null", null);
            cases.put("", null);
            cases.put("[" + word + ", 1]", List.of(word, 1L));
            cases.put("{ " + word + ": false }", Map.of(word, false));
            cases.forEach((raw, converted) -> {
                var key = name();
                yaml.put(key, raw);
                expected.put(key, converted);
            });
            String source = yaml.entrySet().stream().map(entry -> entry.getKey() + ": " + entry.getValue())
                    .collect(Collectors.joining("\n"));

            var value = value(reader.read(source));

            assertEquals(expected, value, "every scalar in the JSON data model");
        }

        @Test
        @DisplayName("ADR-0008: a sequence at the root is a list")
        void sequenceRoot() {
            var word = name();

            var value = value(reader.read("- " + word + "\n- 1\n- [ ]\n"));

            assertAll(
                    () -> assertInstanceOf(ArrayList.class, value, "a sequence is an ArrayList"),
                    () -> assertEquals(List.of(word, 1L, List.of()), value, "the items in order"));
        }

        @Test
        @DisplayName("ADR-0008: readMapping returns the root mapping as read, typed")
        void readMapping() {
            var key = name();
            var value = marker();
            var notMapping = marker();

            var result = reader.readMapping(key + ": " + quoted(value) + "\n", notMapping);

            assertAll(
                    () -> assertTrue(result.isOk(), () -> "the text is read: " + result.detail()),
                    () -> assertEquals(Map.of(key, value), result.value(), "the root mapping"),
                    () -> assertInstanceOf(LinkedHashMap.class, result.value(), "the mapping the reader built"));
        }

        @Test
        @DisplayName("ADR-0008: readMapping refuses a root that is not a mapping with the given detail")
        void readMappingRefusesOtherRoots() {
            var notMapping = marker();

            assertAll(Stream.of("- " + name() + "\n", name() + "\n", "42\n").<Executable>map(yaml -> () -> assertEquals(
                    FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, notMapping)),
                    reader.readMapping(yaml, notMapping), "the root is refused: " + yaml)));
        }

        @Test
        @DisplayName("ADR-0008: readMapping passes on a refusal of the text unchanged")
        void readMappingPassesOnRefusal() {
            var key = name();

            var result = reader.readMapping(key + ": 1\n" + key + ": 2\n", marker());

            assertAll(
                    () -> assertEquals(MALFORMED, result.outcome(), "the text is refused as MALFORMED"),
                    () -> assertEquals("line 2, column 1: a duplicate key", result.detail(), "the reader's detail"));
        }

        @Test
        @DisplayName("GDR-GEN-3: a null text is refused")
        void nullText() {
            String nullText = nullValue();
            var notMapping = marker();

            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> reader.read(nullText), "a null text"),
                    () -> assertThrows(NullPointerException.class, () -> reader.readMapping(nullText, notMapping),
                            "a null text of a mapping"),
                    () -> assertThrows(NullPointerException.class, () -> reader.readMapping(name(), nullText),
                            "a null detail"));
        }
    }

    @Nested
    @DisplayName("Refusal")
    class Refusal {

        /** Every text outside the JSON data model: description, text, expected detail and the content. */
        static Stream<Arguments> refusedTexts() {
            var key = name();
            var other = name();
            var anchor = name();
            var tag = name();
            var value = marker();
            var column = key.length() + 3;
            var content = List.of(key, other, anchor, tag, value);
            return Stream.of(
                    Arguments.of("an alias", key + ": *" + anchor, "line 1, column " + column + ": an alias"),
                    Arguments.of("an anchor", key + ": &" + anchor + " " + quoted(value),
                            "line 1, column " + column + ": an anchor"),
                    Arguments.of("an anchor on a mapping", key + ": &" + anchor + "\n  " + other + ": 1",
                            "line 1, column " + column + ": an anchor"),
                    Arguments.of("a custom tag", key + ": !" + tag + " " + quoted(value),
                            "line 1, column " + column + ": an explicit tag"),
                    Arguments.of("a standard tag", key + ": !!str " + quoted(value),
                            "line 1, column " + column + ": an explicit tag"),
                    Arguments.of("an integer key", "42: " + quoted(value),
                            "line 1, column 1: a key that is not a string"),
                    Arguments.of("a null key", "~: " + quoted(value), "line 1, column 1: a key that is not a string"),
                    Arguments.of("a sequence key", "[" + key + "]: " + quoted(value),
                            "line 1, column 1: a key that is not a string"),
                    Arguments.of("a duplicate key", key + ": " + quoted(value) + "\n" + key + ": 1",
                            "line 2, column 1: a duplicate key"),
                    Arguments.of("an infinite number", key + ": .inf",
                            "line 1, column " + column + ": a number outside the JSON data model"),
                    Arguments.of("a negative infinite number", key + ": -.Inf",
                            "line 1, column " + column + ": a number outside the JSON data model"),
                    Arguments.of("not-a-number", key + ": .nan",
                            "line 1, column " + column + ": a number outside the JSON data model"),
                    Arguments.of("an exponent beyond the range of int, refused by the exponent bound",
                            key + ": 1e-2147483648",
                            "line 1, column " + column + ": a number whose exponent exceeds 1000 in magnitude"),
                    Arguments.of("a lone surrogate from an escape", key + ": \"\\uD800\"",
                            "line 1, column " + column + ": a string that is not valid Unicode"),
                    Arguments.of("more than one document", key + ": 1\n---\n" + other + ": 2",
                            "line 2, column 1: more than one document"),
                    Arguments.of("an empty text", "", "line 1, column 1: a document without a value"),
                    Arguments.of("a document of null", "~", "line 1, column 1: a document without a value"),
                    Arguments.of("a comment only", "# " + value, "line 1, column 1: a document without a value"))
                    .map(arguments -> Arguments.of(arguments.get()[0], arguments.get()[1], arguments.get()[2],
                            content));
        }

        @ParameterizedTest(name = "ADR-0008: {0} is MALFORMED, naming the position and no content")
        @MethodSource("refusedTexts")
        void refused(String description, String yaml, String detail, List<String> content) {
            assertRefused(detail, content, reader.read(yaml));
        }

        @Test
        @DisplayName("ADR-0008: text that is not well-formed YAML is MALFORMED, naming a position and no content")
        void notWellFormed() {
            var key = name();
            var value = marker();

            var result = reader.read(key + ": [" + quoted(value));

            var detail = String.valueOf(result.detail());
            assertAll(
                    () -> assertEquals(MALFORMED, result.outcome(), "the text is refused as MALFORMED"),
                    () -> assertTrue(detail.matches("line \\d+, column \\d+: not well-formed YAML"),
                            () -> "the detail names the position and the problem: " + detail),
                    () -> assertFalse(detail.contains(key), "the detail carries no key"),
                    () -> assertFalse(detail.contains(value), "the detail carries no value"));
        }
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> @Nullable T nullValue() {
        return null;
    }
}
