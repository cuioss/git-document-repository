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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import dev.harrel.jsonschema.JsonNode;
import dev.harrel.jsonschema.SimpleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The numbers of {@link DataNode} (GDR-FORMS-2): equality by value without expanding a number by its exponent, the
 * digit limit of an integer, and the keywords {@code const}, {@code enum} and {@code uniqueItems} on numbers with an
 * exponent near the end of the range of {@code int}; and the structure of a value: a child shares its parent's JSON
 * Pointer instead of a copy of it, and a container shared by several parents is checked once, at its deepest
 * occurrence.
 * <p>
 * The large exponents are literals, being the case under test; the ordinary numbers are generated.
 */
@EnableGeneratorController
@DisplayName("DataNode numbers (GDR-FORMS-2)")
class DataNodeTest {

    /** Generous: an expanded number takes minutes or fails for want of memory, a canonical one milliseconds. */
    private static final Duration NO_STALL = Duration.ofSeconds(30);

    /** 1 × 10^2147483647, built in memory, as {@link YamlReader} refuses its exponent in text. */
    private static final BigDecimal ONE_HUGE = new BigDecimal("1e2147483647");

    /** 100 × 10^2147483647, whose zeros {@link BigDecimal#stripTrailingZeros()} cannot strip without overflow. */
    private static final BigDecimal HUNDRED_HUGE = new BigDecimal("100e2147483647");

    /** 10 × 10^2147483648, the same value as {@link #HUNDRED_HUGE} in another notation. */
    private static final BigDecimal TEN_HUGER = new BigDecimal(BigInteger.TEN, Integer.MIN_VALUE);

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("GDR-FORMS-2: a number with a huge exponent equals itself and hashes stably, unexpanded")
        void hugeExponents() {
            assertTimeoutPreemptively(NO_STALL, () -> assertAll(
                    () -> assertEquals(DataNode.root(ONE_HUGE), DataNode.root(ONE_HUGE), "1e2147483647 equals itself"),
                    () -> assertEquals(DataNode.root(ONE_HUGE).hashCode(), DataNode.root(ONE_HUGE).hashCode(),
                            "the hash code of 1e2147483647 is stable"),
                    () -> assertEquals(DataNode.root(HUNDRED_HUGE), DataNode.root(HUNDRED_HUGE),
                            "100e2147483647 equals itself"),
                    () -> assertEquals(DataNode.root(HUNDRED_HUGE).hashCode(), DataNode.root(HUNDRED_HUGE).hashCode(),
                            "the hash code of 100e2147483647 is stable")));
        }

        @Test
        @DisplayName("GDR-FORMS-2: two notations of a value beyond stripTrailingZeros are equal")
        void notationsBeyondStripping() {
            assertTimeoutPreemptively(NO_STALL, () -> assertAll(
                    () -> assertEquals(DataNode.root(HUNDRED_HUGE), DataNode.root(TEN_HUGER),
                            "100e2147483647 equals 10e2147483648"),
                    () -> assertEquals(DataNode.root(HUNDRED_HUGE).hashCode(), DataNode.root(TEN_HUGER).hashCode(),
                            "and hashes equal"),
                    () -> assertNotEquals(DataNode.root(ONE_HUGE), DataNode.root(HUNDRED_HUGE),
                            "1e2147483647 differs from 100e2147483647")));
        }

        @Test
        @DisplayName("GDR-FORMS-2: 10, 1e1 and 10.0 compare equal and hash equal")
        void notationsOfTen() {
            var nodes = List.of(DataNode.root(10L), DataNode.root(10), DataNode.root(BigInteger.TEN),
                    DataNode.root(new BigDecimal("1e1")), DataNode.root(new BigDecimal("10.0")));

            assertAll(nodes.stream().map(node -> (Executable) () -> assertAll(
                    () -> assertEquals(nodes.getFirst(), node, "equal to 10"),
                    () -> assertEquals(nodes.getFirst().hashCode(), node.hashCode(), "hashes equal to 10"),
                    () -> assertEquals(SimpleType.INTEGER, node.getNodeType(), "an integer"))));
        }

        @Test
        @DisplayName("GDR-FORMS-2: an integer equals every notation of its value, a fraction by value as well")
        void integersAndFractions() {
            long integer = Generators.longs(-1_000_000_000L, 1_000_000_000L).next();
            var plain = DataNode.root(integer);
            var withFraction = DataNode.root(BigDecimal.valueOf(integer).setScale(2));
            var withExponent = DataNode.root(new BigDecimal(BigInteger.valueOf(integer).multiply(BigInteger.TEN), 1));

            assertAll(
                    () -> assertEquals(plain, withFraction, "an integer equals the same value with fraction digits"),
                    () -> assertEquals(plain.hashCode(), withFraction.hashCode(), "and hashes equal"),
                    () -> assertEquals(plain, withExponent, "and the same value with another scale"),
                    () -> assertEquals(DataNode.root(new BigDecimal("1.00")), DataNode.root(1L), "1.00 equals 1"),
                    () -> assertEquals(DataNode.root(new BigDecimal("1.50")), DataNode.root(new BigDecimal("1.5")),
                            "1.50 equals 1.5"),
                    () -> assertEquals(DataNode.root(new BigDecimal("1.50")).asNumber(),
                            DataNode.root(new BigDecimal("1.5")).asNumber(),
                            "the canonical number of 1.50 equals that of 1.5, as the library compares it"),
                    () -> assertNotEquals(plain, DataNode.root(integer + 1), "a different value differs"));
        }

        @Test
        @DisplayName("GDR-FORMS-2: many trailing zeros are stripped without one division per zero")
        void manyTrailingZeros() {
            var oneWithZeros = new BigDecimal("1." + "0".repeat(100_000));

            assertTimeoutPreemptively(NO_STALL, () -> assertAll(
                    () -> assertEquals(DataNode.root(1L), DataNode.root(oneWithZeros), "1.000... equals 1"),
                    () -> assertEquals(SimpleType.INTEGER, DataNode.root(oneWithZeros).getNodeType(),
                            "and is an integer")));
        }
    }

    @Nested
    @DisplayName("digit limit of an integer")
    class DigitLimit {

        @Test
        @DisplayName("GDR-FORMS-2: a number without fraction is an integer up to the digit limit, a number beyond it")
        void digitLimit() {
            var atLimit = BigInteger.TEN.pow(DataNode.MAX_INTEGER_DIGITS - 1);
            var beyondLimit = BigInteger.TEN.pow(DataNode.MAX_INTEGER_DIGITS);

            assertAll(
                    () -> assertEquals(SimpleType.INTEGER, DataNode.root(atLimit).getNodeType(), "at the limit"),
                    () -> assertEquals(SimpleType.INTEGER,
                            DataNode.root(new BigDecimal(BigInteger.ONE, 1 - DataNode.MAX_INTEGER_DIGITS))
                                    .getNodeType(), "at the limit with an exponent"),
                    () -> assertEquals(SimpleType.NUMBER, DataNode.root(beyondLimit).getNodeType(), "beyond it"),
                    () -> assertEquals(SimpleType.NUMBER, DataNode.root(ONE_HUGE).getNodeType(), "1e2147483647"),
                    () -> assertEquals(DataNode.root(beyondLimit),
                            DataNode.root(new BigDecimal(BigInteger.ONE, -DataNode.MAX_INTEGER_DIGITS)),
                            "beyond the limit an integer literal equals the same value with an exponent"));
        }

        @Test
        @DisplayName("GDR-FORMS-2: asInteger expands an integer and refuses to expand a number beyond the limit")
        void asInteger() {
            var atLimit = new BigDecimal(BigInteger.ONE, 1 - DataNode.MAX_INTEGER_DIGITS);
            var huge = DataNode.root(ONE_HUGE);

            assertAll(
                    () -> assertEquals(BigInteger.TEN.pow(DataNode.MAX_INTEGER_DIGITS - 1),
                            DataNode.root(atLimit).asInteger(), "an integer is expanded exactly"),
                    () -> assertEquals(BigInteger.ONE, DataNode.root(new BigDecimal("1.5")).asInteger(),
                            "of a fraction the integer part"),
                    () -> assertThrows(ArithmeticException.class, huge::asInteger,
                            "1e2147483647 is not expanded"),
                    () -> assertEquals("1E+2147483647", DataNode.root(ONE_HUGE).toPrintableString(),
                            "nor printed expanded"));
        }
    }

    @Nested
    @DisplayName("keywords")
    class Keywords {

        private final SchemaValidator validator = new SchemaValidator();

        private ValidationResult validate(String schema, Object value) {
            return validator.validate(SchemaValidatorTest.schema(schema), Map.of("v", value));
        }

        @Test
        @DisplayName("GDR-FORMS-2: const and enum compare data with a huge exponent without a stall")
        void constAndEnum() {
            // the schema is read from text, so its numbers stay within the exponent bound of the reader, 1000
            String constSchema = """
                    { "type": "object", "properties": { "v": { "const": 100e998 } } }
                    """;
            String enumSchema = """
                    { "type": "object", "properties": { "v": { "enum": [1, 1e1000, 1e-1000] } } }
                    """;
            var thousandOtherNotation = new BigDecimal(BigInteger.TEN, -999);

            assertTimeoutPreemptively(NO_STALL, () -> assertAll(
                    () -> assertEquals(new ValidationResult.Valid(), validate(constSchema, thousandOtherNotation),
                            "const matches the same value in another notation"),
                    () -> assertInstanceOf(ValidationResult.Invalid.class, validate(constSchema, ONE_HUGE),
                            "const refuses a value with a huge exponent, unexpanded"),
                    () -> assertInstanceOf(ValidationResult.Invalid.class, validate(enumSchema, ONE_HUGE),
                            "enum refuses 1e2147483647, unexpanded"),
                    () -> assertEquals(new ValidationResult.Valid(), validate(enumSchema, thousandOtherNotation),
                            "enum matches the same value in another notation"),
                    () -> assertEquals(new ValidationResult.Valid(), validate(enumSchema, new BigDecimal("1.0")),
                            "enum matches 1.0 against 1"),
                    () -> assertInstanceOf(ValidationResult.Invalid.class, validate(enumSchema, 2L),
                            "enum refuses a value it does not list")));
        }

        @Test
        @DisplayName("GDR-FORMS-2: uniqueItems finds duplicates with a huge exponent without a stall")
        void uniqueItems() {
            String schema = """
                    { "type": "object", "properties": { "v": { "type": "array", "uniqueItems": true } } }
                    """;

            assertTimeoutPreemptively(NO_STALL, () -> assertAll(
                    () -> assertEquals(new ValidationResult.Invalid(
                                    List.of(new Violation("/v", "must not contain duplicate items"))),
                            validate(schema, List.of(HUNDRED_HUGE, TEN_HUGER)), "two notations of one value"),
                    () -> assertEquals(new ValidationResult.Valid(), validate(schema, List.of(ONE_HUGE, HUNDRED_HUGE)),
                            "two different values")));
        }
    }

    @Nested
    @DisplayName("structure")
    class Structure {

        /** The levels of the long-key chain above the wide mapping. */
        private static final int CHAIN_LEVELS = 100;

        /** The characters of each key of the chain. */
        private static final int KEY_LENGTH = 1_000;

        /** The entries of the wide mapping at the end of the chain. */
        private static final int WIDTH = 20_000;

        /**
         * A chain of {@link #CHAIN_LEVELS} mappings with one long key each, ending in a mapping of {@link #WIDTH}
         * entries. A copied prefix costs {@code WIDTH * CHAIN_LEVELS * (KEY_LENGTH + 1)} characters, about two thousand
         * million, when the wide mapping's children are built; a shared one costs nothing until a pointer is asked for.
         */
        private Map<String, Object> longKeyChain(String key) {
            Map<String, Object> wide = new LinkedHashMap<>();
            for (int index = 0; index < WIDTH; index++) {
                wide.put("e" + index, index);
            }
            Map<String, Object> value = wide;
            for (int level = 0; level < CHAIN_LEVELS; level++) {
                Map<String, Object> outer = new LinkedHashMap<>();
                outer.put(key, value);
                value = outer;
            }
            return value;
        }

        @Test
        @DisplayName("GDR-FORMS-2: the children of a wide mapping under a long-key chain copy no pointer prefix")
        void pointerPrefixShared() {
            String key = Generators.letterStrings(KEY_LENGTH, KEY_LENGTH).next();
            var root = DataNode.root(longKeyChain(key));

            var deepest = assertTimeoutPreemptively(NO_STALL, () -> {
                JsonNode node = root;
                for (int level = 0; level < CHAIN_LEVELS; level++) {
                    node = node.asObject().get(key);
                }
                Map<String, JsonNode> children = node.asObject();
                assertEquals(WIDTH, children.size(), "every entry built");
                return node;
            }, "building the children copies no prefix");

            String prefix = ("/" + key).repeat(CHAIN_LEVELS);
            assertAll(
                    () -> assertEquals("", root.getJsonPointer(), "the root"),
                    () -> assertEquals(prefix, deepest.getJsonPointer(), "the pointer of the wide mapping"),
                    () -> assertEquals(prefix + "/e7", deepest.asObject().get("e7").getJsonPointer(),
                            "a child joins its parent's pointer on request"));
        }

        @Test
        @DisplayName("GDR-FORMS-2: a child's pointer encodes ~ and / and an item its index")
        void pointerEncoding() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("a/b~c", List.of("x", List.of("y")));
            var root = DataNode.root(data);

            JsonNode list = root.asObject().get("a/b~c");
            assertAll(
                    () -> assertEquals("/a~1b~0c", list.getJsonPointer(), "the key escaped"),
                    () -> assertEquals("/a~1b~0c/1/0", list.asArray().get(1).asArray().getFirst().getJsonPointer(),
                            "items by index"));
        }

        /** {@code x = List.of(x, x)} built {@code levels - 1} times over an empty list: {@code levels} containers deep. */
        private Object shared(int levels) {
            Object value = List.of();
            for (int level = 1; level < levels; level++) {
                value = List.of(value, value);
            }
            return value;
        }

        @Test
        @DisplayName("GDR-FORMS-2: x = List.of(x, x) built 128 times is checked quickly, each container once")
        void sharedSubtreeAtTheLimit() {
            var value = shared(DataNode.MAX_DEPTH);

            assertTimeoutPreemptively(NO_STALL, () -> DataNode.root(value),
                    "2^127 paths, 128 containers, one walk each");
        }

        @Test
        @DisplayName("GDR-FORMS-2: x = List.of(x, x) built 129 times is refused quickly, naming the limit")
        void sharedSubtreeBeyondTheLimit() {
            var value = shared(DataNode.MAX_DEPTH + 1);

            var thrown = assertTimeoutPreemptively(NO_STALL,
                    () -> assertThrows(IllegalArgumentException.class, () -> DataNode.root(value)));

            assertEquals("not of the JSON data model: nested deeper than 128 levels", thrown.getMessage(), "the limit");
        }

        @Test
        @DisplayName("GDR-FORMS-2: a container shared at two depths is bounded at its deepest, in either order")
        void sharedContainerBoundAtItsDeepest() {
            // two containers deep, reached once at the top and once below a chain of 127 lists: 129 at its deepest
            List<Object> container = List.of(List.of());
            Object chain = container;
            for (int level = 0; level < DataNode.MAX_DEPTH - 1; level++) {
                chain = List.of(chain);
            }
            var shallowFirst = List.of(container, chain);
            var deepFirst = List.of(chain, container);

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> DataNode.root(shallowFirst),
                            "the shallow occurrence walked first"),
                    () -> assertThrows(IllegalArgumentException.class, () -> DataNode.root(deepFirst),
                            "the deep occurrence walked first"),
                    () -> assertDoesNotThrow(() -> DataNode.root(List.of(container, List.of(container))),
                            "shared within the limit"));
        }
    }
}
