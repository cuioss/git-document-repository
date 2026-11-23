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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The YAML codec (GDR-FORMS-3, GDR-STORE-11): keys in the declaration order of the schema, nested objects and array
 * items by their sub-schema, undeclared keys after them in lexicographic order; the fixed formatting and quoting rule;
 * {@code encode(decode(encode(v)))} byte-identical to {@code encode(v)}; and the refusals of decoding with a position
 * and without content.
 * <p>
 * Keys and values of the ordering tests are generated from lower-case letters, at least twelve long, so that each is
 * written plain; the strings of the quoting test and the YAML vocabulary of the formatting are the contract and kept as
 * literals. The schema is that of {@link SchemaValidatorTest}.
 */
@EnableGeneratorController
@DisplayName("YamlDocumentCodec (GDR-FORMS-3, GDR-STORE-11)")
class YamlDocumentCodecTest {

    private static final TypedGenerator<String> WORDS =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20));

    private final YamlDocumentCodec codec = new YamlDocumentCodec(SchemaValidatorTest.schema(SchemaValidatorTest.SCHEMA));

    private static String word() {
        return WORDS.next();
    }

    private static String text(byte[] content) {
        return new String(content, StandardCharsets.UTF_8);
    }

    private static Map<String, @Nullable Object> decoded(StoreResult<Map<String, @Nullable Object>> result) {
        assertTrue(result.isOk(), () -> "the content is decoded: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the data");
    }

    /** A mapping in the given order of its entries. */
    private static Map<String, @Nullable Object> map(@Nullable Object... keysAndValues) {
        Map<String, @Nullable Object> map = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            map.put((String) Objects.requireNonNull(keysAndValues[index]), keysAndValues[index + 1]);
        }
        return map;
    }

    @Nested
    @DisplayName("Order")
    class Order {

        @Test
        @DisplayName("GDR-FORMS-3: keys follow the schema, nested and in array items, undeclared keys follow sorted")
        void schemaOrder() {
            var title = word();
            var owner = word();
            var reason = word();
            var note = word();
            var name = word();
            var first = "a" + word();
            var last = "z" + word();
            var data = map(last, null, "tags", List.of(map("other", 1L, "name", name)), "owner", owner,
                    "details", map("note", note, "reason", reason), first, true, "score", 3L, "title", title);

            var encoded = text(codec.encode(data));

            assertEquals("""
                    title: %s
                    owner: %s
                    score: 3
                    details:
                      reason: %s
                      note: %s
                    tags:
                      - name: %s
                        other: 1
                    %s: true
                    %s: null
                    """.formatted(title, owner, reason, note, name, first, last), encoded,
                    "declared keys in schema order, then undeclared keys sorted");
        }

        @Test
        @DisplayName("GDR-STORE-11: equal data in another insertion order encodes byte-identically")
        void canonical() {
            var title = word();
            var other = word();
            var forward = map("title", title, other, 1L, "score", 2L);
            var backward = map("score", 2L, other, 1L, "title", title);

            assertArrayEquals(codec.encode(forward), codec.encode(backward), "the encoding is canonical");
        }

        @Test
        @DisplayName("GDR-FORMS-3: a split part is ordered by the sub-schema of its property")
        void partOrder() {
            var reason = word();
            var note = word();

            var encoded = text(codec.part("details").encode(map("note", note, "reason", reason)));

            assertAll(
                    () -> assertEquals("reason: " + reason + "\nnote: " + note + "\n", encoded, "the part order"),
                    () -> assertThrows(IllegalArgumentException.class, () -> codec.part("title"),
                            "a property that is not split"));
        }
    }

    @Nested
    @DisplayName("Format")
    class Format {

        @Test
        @DisplayName("GDR-STORE-11: strings are plain only when they read back as strings, otherwise double-quoted")
        void quoting() {
            var data = map("a", "42", "b", "", "c", "x: y", "d", "line\nbreak", "e", "tab\there", "f",
                    "quote\"back\\", "g", " ", "h", "é", "i", " lead", "j", "trail ", "k", "true", "l",
                    "plain text", "m", "~", "n", "#comment", "o", "- item");

            assertEquals("""
                    a: "42"
                    b: ""
                    c: "x: y"
                    d: "line\\nbreak"
                    e: "tab\\there"
                    f: "quote\\"back\\\\"
                    g: "\\u2028"
                    h: é
                    i: " lead"
                    j: "trail "
                    k: "true"
                    l: plain text
                    m: "~"
                    n: "#comment"
                    o: "- item"
                    """, text(codec.encode(data)), "the quoting rule");
        }

        @Test
        @DisplayName("GDR-STORE-11: numbers, booleans, null and empty containers are plain, nested sequences compact")
        void scalarsAndContainers() {
            var data = map("a", 42L, "b", new BigDecimal("1.50"), "c", BigInteger.TWO.pow(70), "d", false, "e", null,
                    "f", Map.of(), "g", List.of(), "h", List.of(List.of(1L, 2L), map("u", 1L)), "i", -7);

            assertEquals("""
                    a: 42
                    b: 1.50
                    c: 1180591620717411303424
                    d: false
                    e: null
                    f: {}
                    g: []
                    h:
                      - - 1
                        - 2
                      - u: 1
                    i: -7
                    """, text(codec.encode(data)), "the formatting");
        }

        /**
         * Strings a YAML 1.1 reader takes for booleans, timestamps or numbers stay strings under the YAML 1.2 core
         * schema, so they are written plain; the byte output is unchanged by the owner's decision. A sexagesimal-like
         * string holds {@code :}, which a plain string never does, so it is double-quoted.
         */
        @Test
        @DisplayName("GDR-FORMS-3: strings that YAML 1.1 reads as other types are written as YAML 1.2 strings")
        void yaml11Strings() {
            var data = map("a", "no", "b", "yes", "c", "on", "d", "off", "e", "2026-11-21", "f", "12:30", "g",
                    "1_000", "h", "0b101", "i", "y", "j", "n");

            var encoded = codec.encode(data);

            assertAll(
                    () -> assertEquals("""
                            a: no
                            b: yes
                            c: on
                            d: off
                            e: 2026-11-21
                            f: "12:30"
                            g: 1_000
                            h: 0b101
                            i: y
                            j: n
                            """, text(encoded), "written plain, the sexagesimal-like string quoted"),
                    () -> assertEquals(data, decoded(codec.decode(encoded)), "read back as the same strings"));
        }

        @Test
        @DisplayName("GDR-STORE-11: an empty document is written as an empty mapping")
        void emptyDocument() {
            assertAll(
                    () -> assertEquals("{}\n", text(codec.encode(Map.of())), "an empty mapping"),
                    () -> assertEquals(Map.of(), decoded(codec.decode(codec.encode(Map.of()))), "read back empty"));
        }
    }

    @Nested
    @DisplayName("Round trip")
    class RoundTrip {

        /** Data of every kind of value the data model allows, with strings that need every quoting rule. */
        private Map<String, @Nullable Object> everyKind() {
            List<Object> strings = new ArrayList<>(List.of("42", "", "x: y", "line\nbreak", "\u0000\u0007\u007f",
                    " ﻿", "é😀", "~" + word() + "~", "null", "0x1f", ".inf", "'single'", "a #b"));
            Collections.shuffle(strings);
            return map("title", word(), "score", 3, "details", map("reason", word(), word(), List.of()),
                    "tags", List.of(map("name", word()), map(word(), Map.of())), "strings", strings,
                    "numbers", List.of(Long.MIN_VALUE, BigInteger.TEN.pow(30), new BigDecimal("1E+3"),
                            new BigDecimal("-0.001"), new BigDecimal("2.50")),
                    "nested", List.of(List.of(List.of()), List.of(true, false), Map.of()), word(), null);
        }

        @Test
        @DisplayName("GDR-STORE-11: encode(decode(encode(v))) is byte-identical to encode(v)")
        void byteIdentical() {
            var encoded = codec.encode(everyKind());

            var decoded = decoded(codec.decode(encoded));

            assertArrayEquals(encoded, codec.encode(decoded), "the round trip is byte-identical");
        }

        @Test
        @DisplayName("GDR-FORMS-3: strings decode to the strings encoded")
        void stringsRoundTrip() {
            var data = everyKind();

            var decoded = decoded(codec.decode(codec.encode(data)));

            assertAll(
                    () -> assertEquals(data.get("strings"), decoded.get("strings"), "every string read back"),
                    () -> assertEquals(data.get("tags"), decoded.get("tags"), "the items read back"));
        }
    }

    @Nested
    @DisplayName("Refusal")
    class Refusal {

        static Stream<Arguments> refusedContents() {
            var key = WORDS.next();
            var value = "~" + WORDS.next() + "~";
            var alias = WORDS.next();
            return Stream.of(
                    Arguments.of("an alias", key + ": *" + alias, key, alias),
                    Arguments.of("an explicit tag", key + ": !!str " + value, key, value),
                    Arguments.of("a key that is not a string", "42: \"" + value + "\"", key, value),
                    Arguments.of("a duplicate key", key + ": 1\n" + key + ": \"" + value + "\"", key, value),
                    Arguments.of("text that is not well-formed", key + ": [\"" + value + "\"", key, value));
        }

        @ParameterizedTest(name = "GDR-FORMS-3: {0} is MALFORMED, naming a position and no content")
        @MethodSource("refusedContents")
        void refused(String description, String yaml, String key, String value) {
            var result = new YamlDocumentCodec(SchemaValidatorTest.schema(SchemaValidatorTest.SCHEMA))
                    .decode(yaml.getBytes(StandardCharsets.UTF_8));

            var detail = String.valueOf(result.detail());
            assertAll(
                    () -> assertEquals(StoreOutcome.MALFORMED, result.outcome(), "the content is refused"),
                    () -> assertTrue(detail.matches("line \\d+, column \\d+: .+"), () -> "a position: " + detail),
                    () -> assertFalse(detail.contains(key), "no key in the detail"),
                    () -> assertFalse(detail.contains(value), "no value in the detail"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: content that is not UTF-8, or not a mapping, is MALFORMED")
        void notUtf8OrNotAMapping() {
            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "not UTF-8"),
                            codec.decode(new byte[]{(byte) 0xC3, (byte) 0x28}), "not UTF-8"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED,
                                    "line 1, column 1: a document that is not a mapping"),
                            codec.decode("- a\n".getBytes(StandardCharsets.UTF_8)), "a sequence at the root"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED,
                                    "line 1, column 1: a document without a value"),
                            codec.decode(new byte[0]), "an empty content"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: data outside the JSON data model is refused on encode")
        void outsideDataModel() {
            var unpaired = String.valueOf('\ud800');
            var aDouble = map("a", 1.5d);
            var aLoneSurrogate = map("a", unpaired);
            var aNumberKey = map("a", Map.of(1L, "x"));
            Map<String, @Nullable Object> nullData = nullValue();
            byte[] nullContent = nullValue();
            FormSchema nullSchema = nullValue();

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> codec.encode(aDouble)),
                    () -> assertThrows(IllegalArgumentException.class, () -> codec.encode(aLoneSurrogate)),
                    () -> assertThrows(IllegalArgumentException.class, () -> codec.encode(aNumberKey)),
                    () -> assertThrows(NullPointerException.class, () -> codec.encode(nullData)),
                    () -> assertThrows(NullPointerException.class, () -> codec.decode(nullContent)),
                    () -> assertThrows(NullPointerException.class, () -> new YamlDocumentCodec(nullSchema)));
        }

        @Test
        @DisplayName("GDR-FORMS-3: encode refuses YAML longer than decode reads, and accepts it up to the limit")
        void sizeLimit() {
            // "a: " + the text + a line feed: four code points beside the text, every letter plain
            var atLimit = map("a", "x".repeat(YamlReader.CODE_POINT_LIMIT - 4));
            var beyondLimit = map("a", "x".repeat(YamlReader.CODE_POINT_LIMIT - 3));

            var encoded = codec.encode(atLimit);

            assertAll(
                    () -> assertEquals(atLimit, decoded(codec.decode(encoded)), "the largest value reads back"),
                    () -> assertThrows(IllegalArgumentException.class, () -> codec.encode(beyondLimit),
                            "a value beyond the limit is refused"));
        }
    }

    /**
     * A number a caller builds in memory beyond the number bounds of {@link YamlReader}: {@code encode} refuses it, so
     * that it never writes text that {@code decode} refuses, and accepts the numbers at the bounds. The numbers are the
     * bounds themselves and kept as literals.
     */
    @Nested
    @DisplayName("Number bounds")
    class NumberBounds {

        static Stream<Arguments> beyondTheBounds() {
            return Stream.of(
                    Arguments.of("an exponent beyond 1000", new BigDecimal("1e1001"),
                            "a number whose exponent exceeds 1000 in magnitude"),
                    Arguments.of("more than 1000 digits", BigInteger.TEN.pow(1000),
                            "a number with more than 1000 digits"));
        }

        static Stream<Arguments> atTheBounds() {
            return Stream.of(
                    Arguments.of("an exponent of 1000", new BigDecimal("1e1000")),
                    Arguments.of("1000 digits", BigInteger.TEN.pow(999)));
        }

        @ParameterizedTest(name = "GDR-FORMS-3: encode refuses a number with {0}, naming the limit and not the number")
        @MethodSource("beyondTheBounds")
        void refused(String description, Number number, String problem) {
            var data = map(word(), number);

            var refusal = assertThrows(IllegalArgumentException.class, () -> codec.encode(data));

            var message = String.valueOf(refusal.getMessage());
            assertAll(
                    () -> assertEquals("value: " + problem + ", which decode refuses", message, "the limit named"),
                    () -> assertFalse(message.contains(number.toString()), "no number in the message"),
                    () -> assertEquals(StoreOutcome.MALFORMED,
                            codec.decode(new YamlWriter().write(data).getBytes(StandardCharsets.UTF_8)).outcome(),
                            "decode refuses the text encode would have written"));
        }

        @ParameterizedTest(name = "GDR-FORMS-3: a number with {0} round-trips byte for byte")
        @MethodSource("atTheBounds")
        void atTheBound(String description, Number number) {
            var data = map(word(), number);

            var encoded = codec.encode(data);

            assertAll(
                    () -> assertEquals(data, decoded(codec.decode(encoded)), "the number reads back"),
                    () -> assertArrayEquals(encoded, codec.encode(decoded(codec.decode(encoded))),
                            "the round trip is byte-identical"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: encode refuses a number beyond the bounds inside a list and inside a map")
        void nested() {
            var inList = map(word(), List.of(1L, map(word(), List.of(new BigDecimal("1e1001")))));
            var inMap = map(word(), map(word(), map(word(), BigInteger.TEN.pow(1000))));

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> codec.encode(inList), "inside a list"),
                    () -> assertThrows(IllegalArgumentException.class, () -> codec.encode(inMap), "inside a map"));
        }
    }

    @Nested
    @DisplayName("Arrays and keys")
    class ArraysAndKeys {

        @Test
        @DisplayName("GDR-FORMS-3: the leading items of an array are ordered by prefixItems, the others by items")
        void prefixItems() {
            var prefixCodec = new YamlDocumentCodec(SchemaValidatorTest.schema("""
                    {
                      "type": "object",
                      "properties": {
                        "pair": {
                          "type": "array",
                          "prefixItems": [ { "type": "object", "properties": { "z": {}, "a": {} } } ],
                          "items": { "type": "object", "properties": { "m": {}, "b": {} } }
                        }
                      }
                    }
                    """));
            var data = map("pair", List.of(map("a", 1L, "z", 2L), map("b", 3L, "m", 4L)));

            assertEquals("""
                    pair:
                      - z: 2
                        a: 1
                      - m: 4
                        b: 3
                    """, text(prefixCodec.encode(data)), "prefixItems first, items for the rest");
        }

        @Test
        @DisplayName("GDR-STORE-11: a key longer than 1024 characters is written explicitly and reads back")
        void longKey() {
            var longKey = "k".repeat(2000);
            var quotedKey = "\"" + "x\n".repeat(400) + "\"";
            var data = map(longKey, word(), "tags", List.of(map(longKey, map(quotedKey, 1L))));

            var encoded = codec.encode(data);

            assertAll(
                    () -> assertTrue(text(encoded).contains("? " + longKey + "\n: "), "the explicit key syntax"),
                    () -> assertEquals(data, decoded(codec.decode(encoded)), "the data reads back"),
                    () -> assertArrayEquals(encoded, codec.encode(decoded(codec.decode(encoded))),
                            "the round trip is byte-identical"));
        }
    }

    @Nested
    @DisplayName("Order through $ref and allOf")
    class ThroughReferences {

        private String encoded(String schema, Map<String, @Nullable Object> data) {
            return text(new YamlDocumentCodec(SchemaValidatorTest.schema(schema)).encode(data));
        }

        @Test
        @DisplayName("GDR-FORMS-3: properties from a local $ref are written in the order of the definition")
        void localReference() {
            var schema = """
                    { "type": "object",
                      "properties": { "contact": { "$ref": "#/$defs/contact" } },
                      "$defs": { "contact": { "type": "object",
                                              "properties": { "zeta": {}, "alpha": {}, "mid": {} } } } }
                    """;
            var data = map("contact", map("alpha", 1L, "mid", 2L, "zeta", 3L));

            assertEquals("""
                    contact:
                      zeta: 3
                      alpha: 1
                      mid: 2
                    """, encoded(schema, data), "the order of the definition, not lexicographic");
        }

        @Test
        @DisplayName("GDR-FORMS-3: the branches of allOf contribute in their declaration order")
        void allOfBranches() {
            var schema = """
                    { "allOf": [ { "properties": { "zeta": {}, "beta": {} } },
                                 { "properties": { "alpha": {} } } ] }
                    """;
            var data = map("alpha", 1L, "extra", 4L, "beta", 2L, "zeta", 3L);

            assertEquals("""
                    zeta: 3
                    beta: 2
                    alpha: 1
                    extra: 4
                    """, encoded(schema, data), "branch one, then branch two, then the undeclared key");
        }

        @Test
        @DisplayName("GDR-FORMS-3: a property declared inline and in allOf keeps its first position, its value merged")
        void firstPositionWins() {
            var inlineFirst = """
                    { "properties": { "mid": {}, "zeta": { "properties": { "y": {} } } },
                      "allOf": [ { "properties": { "alpha": {}, "zeta": { "properties": { "x": {} } } } } ] }
                    """;
            var allOfFirst = """
                    { "allOf": [ { "properties": { "zeta": {} } } ],
                      "properties": { "alpha": {}, "zeta": {} } }
                    """;
            var data = map("alpha", 1L, "mid", 2L, "zeta", map("x", 3L, "y", 4L));

            assertAll(
                    () -> assertEquals("""
                            mid: 2
                            zeta:
                              y: 4
                              x: 3
                            alpha: 1
                            """, encoded(inlineFirst, data), "inline first; the nested order merges both"),
                    () -> assertEquals("""
                            zeta:
                              x: 3
                              y: 4
                            alpha: 1
                            mid: 2
                            """, encoded(allOfFirst, data), "allOf first, as its keyword is declared first"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: a recursive tree of items that reference the root is ordered to the nesting limit")
        void recursiveTreeToTheLimit() {
            var schema = """
                    { "properties": { "z": {}, "a": {}, "kids": { "type": "array", "items": { "$ref": "#" } } } }
                    """;
            // each level is a mapping and its sequence: 64 mappings, the innermost sequence at depth 128
            int levels = DataNode.MAX_DEPTH / 2;
            Map<String, @Nullable Object> node = map("kids", List.of(), "a", (long) levels - 1, "z", (long) levels - 1);
            for (int level = levels - 2; level >= 0; level--) {
                node = map("kids", List.of(node), "a", (long) level, "z", (long) level);
            }
            var expected = new StringBuilder();
            for (int level = 0; level < levels; level++) {
                String indent = " ".repeat(4 * level);
                expected.append(level == 0 ? "" : " ".repeat(4 * level - 2) + "- ").append("z: ").append(level)
                        .append('\n').append(indent).append("a: ").append(level).append('\n').append(indent)
                        .append(level == levels - 1 ? "kids: []" : "kids:").append('\n');
            }

            assertEquals(expected.toString(), encoded(schema, node), "every level in schema order");
        }
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> @Nullable T nullValue() {
        return null;
    }
}
