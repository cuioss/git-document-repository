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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The counter file format (GDR-FORMS-3): one decimal integer without sign and without leading zero, followed by one line
 * feed; every other content is {@link StoreOutcome#MALFORMED}. The counter values are generated; the refused contents
 * and the bounds of {@code long} are the contract and kept as literals.
 */
@EnableGeneratorController
@DisplayName("CounterCodec (GDR-FORMS-3)")
class CounterCodecTest {

    private static final String FORMAT =
            ".counter: not one decimal integer without sign or leading zero, followed by one line feed";

    private final CounterCodec codec = new CounterCodec();

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("GDR-FORMS-3: a counter is its decimal digits and one line feed, and reads back")
    void format() {
        long value = Generators.longs(1, Long.MAX_VALUE).next();

        var encoded = codec.encode(value);

        assertAll(
                () -> assertArrayEquals(ascii(value + "\n"), encoded, "the digits and a line feed"),
                () -> assertEquals(StoreResult.ok(value), codec.decode(encoded), "the value reads back"),
                () -> assertArrayEquals(encoded, codec.encode(codec.decode(encoded).value()), "the round trip"));
    }

    @Test
    @DisplayName("GDR-FORMS-3: the counter before the first number is 0, the largest is Long.MAX_VALUE")
    void bounds() {
        assertAll(
                () -> assertArrayEquals(ascii("0\n"), codec.encode(0L), "zero"),
                () -> assertEquals(StoreResult.ok(0L), codec.decode(ascii("0\n")), "zero reads back"),
                () -> assertEquals(StoreResult.ok(Long.MAX_VALUE), codec.decode(ascii(Long.MAX_VALUE + "\n")),
                        "the largest counter"));
    }

    @ParameterizedTest(name = "GDR-FORMS-3: \"{0}\" is MALFORMED")
    @ValueSource(strings = {"", "\n", "42", "042\n", "00\n", "+42\n", "-42\n", "4 2\n", "42 \n", "42\n\n", "42\r\n",
            "x\n", "4.2\n", "４\n"})
    void refused(String content) {
        assertEquals(StoreResult.of(StoreOutcome.MALFORMED, FORMAT),
                codec.decode(content.getBytes(StandardCharsets.UTF_8)), "the content is refused");
    }

    @Test
    @DisplayName("GDR-FORMS-3: a counter beyond the range of long is MALFORMED")
    void beyondLong() {
        assertEquals(StoreResult.of(StoreOutcome.MALFORMED, ".counter: beyond the range of a long"),
                codec.decode(ascii("9223372036854775808\n")), "Long.MAX_VALUE + 1");
    }

    @Test
    @DisplayName("GDR-FORMS-3: a negative counter and null are refused on encode")
    void refusedEncode() {
        long negative = -Generators.longs(1, Long.MAX_VALUE).next();
        Long nullValue = nullValue();
        byte[] nullContent = nullValue();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> codec.encode(negative)),
                () -> assertThrows(NullPointerException.class, () -> codec.encode(nullValue)),
                () -> assertThrows(NullPointerException.class, () -> codec.decode(nullContent)));
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> @Nullable T nullValue() {
        return null;
    }
}
