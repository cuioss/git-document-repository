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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Map;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import dev.harrel.jsonschema.SimpleType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The numbers of {@link DataNode} (GDR-FORMS-2): equality by value without expanding a number by its exponent, the
 * digit limit of an integer, and the keywords {@code const}, {@code enum} and {@code uniqueItems} on numbers with an
 * exponent near the end of the range of {@code int}.
 * <p>
 * The large exponents are literals, being the case under test; the ordinary numbers are generated.
 */
@EnableGeneratorController
@DisplayName("DataNode numbers (GDR-FORMS-2)")
class DataNodeTest {

    /** Generous: an expanded number takes minutes or fails for want of memory, a canonical one milliseconds. */
    private static final Duration NO_STALL = Duration.ofSeconds(30);

    /** 1 × 10^2147483647, the largest exponent {@link YamlReader} accepts. */
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

            assertAll(
                    () -> assertEquals(BigInteger.TEN.pow(DataNode.MAX_INTEGER_DIGITS - 1),
                            DataNode.root(atLimit).asInteger(), "an integer is expanded exactly"),
                    () -> assertEquals(BigInteger.ONE, DataNode.root(new BigDecimal("1.5")).asInteger(),
                            "of a fraction the integer part"),
                    () -> assertThrows(ArithmeticException.class, () -> DataNode.root(ONE_HUGE).asInteger(),
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
        @DisplayName("GDR-FORMS-2: const and enum with a huge exponent validate without a stall")
        void constAndEnum() {
            String constSchema = """
                    { "type": "object", "properties": { "v": { "const": 100e2147483647 } } }
                    """;
            String enumSchema = """
                    { "type": "object", "properties": { "v": { "enum": [1, 1e2147483647, 100e2147483647] } } }
                    """;

            assertTimeoutPreemptively(NO_STALL, () -> assertAll(
                    () -> assertEquals(new ValidationResult.Valid(), validate(constSchema, TEN_HUGER),
                            "const matches the same value in another notation"),
                    () -> assertInstanceOf(ValidationResult.Invalid.class, validate(constSchema, ONE_HUGE),
                            "const refuses another value"),
                    () -> assertEquals(new ValidationResult.Valid(), validate(enumSchema, ONE_HUGE),
                            "enum matches 1e2147483647"),
                    () -> assertEquals(new ValidationResult.Valid(), validate(enumSchema, TEN_HUGER),
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
}
