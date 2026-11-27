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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The bounds of a number in text (GDR-FORMS-2, GDR-GEN-3): {@link YamlReader} refuses more than
 * {@value YamlReader#MAX_NUMBER_DIGITS} significant digits and an exponent beyond {@value YamlReader#MAX_EXPONENT} in
 * magnitude as {@link StoreOutcome#MALFORMED} before it parses the number, naming the bound and not the number, so
 * {@code multipleOf} cannot stall on a number read from text; a number at the bound is read and validated in bounded
 * time; the digits of an integer are counted in one radix, the decimal form the writer writes, so that every number at
 * the bound, octal, hexadecimal or signed, or a float with a long mantissa, decodes, encodes and decodes again to the
 * same value and text; and the documented underflow of {@code multipleOf}, {@code 1e-400} under
 * {@code "multipleOf": 1}, stays reachable.
 * <p>
 * The numbers are the contract, the bound and one beyond it, and kept as literals.
 */
@DisplayName("Number bounds of the reader (GDR-FORMS-2, GDR-GEN-3)")
class NumberBoundsTest {

    /** Generous: a bounded number takes milliseconds, an unbounded one minutes or longer. */
    private static final Duration BOUNDED = Duration.ofSeconds(20);

    private static final String KEY = "amount";

    /** The column of the value in {@code amount: <value>}. */
    private static final int COLUMN = KEY.length() + 3;

    private static final String TOO_MANY_DIGITS = "line 1, column " + COLUMN + ": a number with more than 1000 digits";

    private static final String EXPONENT_TOO_LARGE =
            "line 1, column " + COLUMN + ": a number whose exponent exceeds 1000 in magnitude";

    private final SchemaValidator validator = new SchemaValidator();

    private final FormSchema cents =
            SchemaValidatorTest.schema("{ \"properties\": { \"amount\": { \"multipleOf\": 0.01 } } }");

    private final YamlDocumentCodec codec = new YamlDocumentCodec(cents);

    private StoreResult<Map<String, @Nullable Object>> decode(String number) {
        return codec.decode((KEY + ": " + number + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, @Nullable Object> decoded(String number) {
        var result = decode(number);
        assertTrue(result.isOk(), () -> "the number is read: " + result.detail());
        return Objects.requireNonNull(result.value());
    }

    static Stream<Arguments> refused() {
        return Stream.of(
                Arguments.of("an exponent at the end of the range of int", "1e2147483647", EXPONENT_TOO_LARGE),
                Arguments.of("an exponent one beyond the bound", "1e1001", EXPONENT_TOO_LARGE),
                Arguments.of("a negative exponent one beyond the bound", "1e-1001", EXPONENT_TOO_LARGE),
                Arguments.of("a fraction whose first digit lies beyond the bound", "0." + "0".repeat(1001) + "1",
                        EXPONENT_TOO_LARGE),
                Arguments.of("a decimal of 1,001 digits", "1." + "1".repeat(1000), TOO_MANY_DIGITS),
                Arguments.of("an integer of 1,001 digits", "1".repeat(1001), TOO_MANY_DIGITS),
                Arguments.of("an integer of 3 Mi digits", "1".repeat(YamlReader.CODE_POINT_LIMIT - 16), TOO_MANY_DIGITS),
                Arguments.of("a negative integer of 1,001 digits", "-" + "1".repeat(1001), TOO_MANY_DIGITS),
                Arguments.of("a hexadecimal integer of 1,001 digits", "0x" + "f".repeat(1001), TOO_MANY_DIGITS),
                // 1,000 hexadecimal digits are about 1,205 decimal digits, which encode would refuse
                Arguments.of("a hexadecimal integer of 1,000 digits", "0x" + "f".repeat(1000), TOO_MANY_DIGITS),
                Arguments.of("the smallest hexadecimal integer beyond the bound", "0x" + HEX_BEYOND, TOO_MANY_DIGITS),
                Arguments.of("a hexadecimal integer of 3 Mi digits", "0x" + "f".repeat(YamlReader.CODE_POINT_LIMIT - 16),
                        TOO_MANY_DIGITS),
                // 1,200 octal digits are about 1,084 decimal digits
                Arguments.of("an octal integer of 1,200 digits", "0o" + "7".repeat(1200), TOO_MANY_DIGITS));
    }

    /** The largest integer of 1,000 decimal digits, 10^1000 - 1, in hexadecimal. */
    private static final String HEX_AT_BOUND = BigInteger.TEN.pow(1000).subtract(BigInteger.ONE).toString(16);

    /** The smallest integer of 1,001 decimal digits, 10^1000, in hexadecimal. */
    private static final String HEX_BEYOND = BigInteger.TEN.pow(1000).toString(16);

    @ParameterizedTest(name = "GDR-FORMS-2: {0} is MALFORMED in bounded time, naming the bound and not the number")
    @MethodSource("refused")
    void refusedInBoundedTime(String description, String number, String detail) {
        var result = assertTimeoutPreemptively(BOUNDED, () -> decode(number));

        assertAll(
                () -> assertEquals(StoreOutcome.MALFORMED, result.outcome(), "the number is refused"),
                () -> assertEquals(detail, result.detail(), "the bound and the position, nothing of the number"));
    }

    static Stream<Arguments> accepted() {
        var notCents = new ValidationResult.Invalid(List.of(new Violation("/" + KEY, "must be a multiple of 0.01")));
        var valid = new ValidationResult.Valid();
        return Stream.of(
                Arguments.of("an exponent at the bound", "1e1000", new BigDecimal("1e1000"), valid),
                // the remainder 1e-1000 underflows to zero as a double, see multipleOfUnderflow
                Arguments.of("a negative exponent at the bound", "1e-1000", new BigDecimal("1e-1000"), valid),
                Arguments.of("a decimal of 1,000 digits", "1." + "1".repeat(999),
                        new BigDecimal("1." + "1".repeat(999)), notCents),
                Arguments.of("leading zeros beside 1,000 digits", "000" + "1".repeat(1000),
                        new BigInteger("1".repeat(1000)), valid),
                Arguments.of("an integer of 1,000 digits", "1".repeat(1000), new BigInteger("1".repeat(1000)), valid),
                Arguments.of("a negative integer of 1,000 digits", "-" + "1".repeat(1000),
                        new BigInteger("-" + "1".repeat(1000)), valid),
                Arguments.of("a hexadecimal integer of 1,000 decimal digits", "0x" + HEX_AT_BOUND,
                        BigInteger.TEN.pow(1000).subtract(BigInteger.ONE), valid),
                // 1,001 octal digits are about 905 decimal digits
                Arguments.of("an octal integer of 1,001 digits", "0o" + "7".repeat(1001),
                        new BigInteger("7".repeat(1001), 8), valid));
    }

    static Stream<Arguments> roundTrips() {
        return Stream.of(
                Arguments.of("a hexadecimal integer of 1,000 decimal digits", "0x" + HEX_AT_BOUND),
                Arguments.of("an octal integer of 1,001 digits", "0o" + "7".repeat(1001)),
                Arguments.of("a negative integer of 1,000 digits", "-" + "1".repeat(1000)),
                Arguments.of("a positive sign on 1,000 digits", "+" + "1".repeat(1000)),
                Arguments.of("a long mantissa with a tiny exponent", "1." + "1".repeat(999) + "e-1000"),
                Arguments.of("a negative long mantissa with a large exponent", "-1." + "1".repeat(999) + "e1000"),
                Arguments.of("a fraction of 1,000 digits after zeros", "0." + "0".repeat(500) + "1".repeat(1000)));
    }

    @ParameterizedTest(name = "GDR-FORMS-3: {0} decodes, encodes and decodes again to the same value and text")
    @MethodSource("roundTrips")
    void roundTripAtTheBound(String description, String number) {
        var first = assertTimeoutPreemptively(BOUNDED, () -> decoded(number));
        var encoded = assertTimeoutPreemptively(BOUNDED, () -> codec.encode(first));
        var again = codec.decode(encoded);

        assertAll(
                () -> assertTrue(again.isOk(), () -> "the encoding reads back: " + again.detail()),
                () -> assertEquals(first, again.value(), "the same value"),
                () -> assertEquals(new String(encoded, StandardCharsets.UTF_8),
                        new String(codec.encode(Objects.requireNonNull(again.value())), StandardCharsets.UTF_8),
                        "the same text"));
    }

    @Test
    @DisplayName("GDR-FORMS-2: multipleOf with a large exponent is decided in bounded time")
    void multipleOfLargeExponent() {
        var huge = SchemaValidatorTest.schema("{ \"properties\": { \"amount\": { \"multipleOf\": 1e1000 } } }");
        var hugeCodec = new YamlDocumentCodec(huge);
        var multiple = Objects.requireNonNull(hugeCodec.decode("amount: 3e1000\n".getBytes(StandardCharsets.UTF_8))
                .value());
        var notMultiple = Objects.requireNonNull(hugeCodec.decode("amount: 1e999\n"
                .getBytes(StandardCharsets.UTF_8)).value());

        assertTimeoutPreemptively(BOUNDED, () -> assertAll(
                () -> assertEquals(new ValidationResult.Valid(), validator.validate(huge, multiple), "3e1000"),
                () -> assertInstanceOf(ValidationResult.Invalid.class, validator.validate(huge, notMultiple),
                        "1e999 is no multiple of 1e1000")));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: {0} is read and validated under multipleOf in bounded time")
    @MethodSource("accepted")
    void acceptedInBoundedTime(String description, String number, Object expected, ValidationResult validation) {
        var data = assertTimeoutPreemptively(BOUNDED, () -> decoded(number));
        var result = assertTimeoutPreemptively(BOUNDED, () -> validator.validate(cents, data));

        assertAll(
                () -> assertEquals(expected, data.get(KEY), "the value read"),
                () -> assertEquals(validation, result, "multipleOf decided"));
    }

    @Test
    @DisplayName("GDR-FORMS-2: 1e-400 under multipleOf 1 is valid, the underflow of the library pinned")
    void multipleOfUnderflow() {
        var wholes = SchemaValidatorTest.schema("{ \"properties\": { \"amount\": { \"multipleOf\": 1 } } }");
        var data = new YamlDocumentCodec(wholes).decode("amount: 1e-400\n".getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertTrue(data.isOk(), "1e-400 lies within the exponent bound"),
                () -> assertEquals(new ValidationResult.Valid(),
                        validator.validate(wholes, Objects.requireNonNull(data.value())),
                        "the remainder underflows to zero as a double, so multipleOf accepts it"));
    }

    @Test
    @DisplayName("GDR-FORMS-2: a schema whose number exceeds the bound is MALFORMED")
    void schemaNumberRefused() {
        var result = FormSchema.parse(SchemaValidatorTest.PATH,
                "{ \"properties\": { \"v\": { \"const\": 100e2147483647 } } }".getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertInstanceOf(FormsResult.PassedOn.class, result.outcome(), "the schema is refused"),
                () -> assertTrue(String.valueOf(result.detail())
                                .endsWith("a number whose exponent exceeds 1000 in magnitude"),
                        () -> "the bound: " + result.detail()));
    }
}
