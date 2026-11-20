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
package de.cuioss.git.document.store;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * The contract of {@link Codec} (GDR-STORE-11), asserted with a canonical test codec: a round trip is byte-identical,
 * equal values encode identically whatever their insertion order, and a content the codec cannot read is
 * {@link StoreOutcome#MALFORMED} with a detail that does not repeat the content.
 */
@EnableGeneratorController
@DisplayName("Codec (GDR-STORE-11)")
class CodecTest {

    /** The number of generated values each repeated test checks. */
    private static final int REPETITIONS = 10;

    /** The codec under test. */
    private final Codec<Map<String, String>> codec = new KeyValueCodec();

    /**
     * Generates a map of letter keys to letter values, possibly empty values.
     *
     * @return a generated map, in insertion order
     */
    private static Map<String, String> value() {
        Map<String, String> value = new LinkedHashMap<>();
        int size = Generators.integers(0, 8).next();
        while (value.size() < size) {
            value.put(Generators.letterStrings(1, 12).next(), Generators.letterStrings(0, 20).next());
        }
        return value;
    }

    @RepeatedTest(REPETITIONS)
    @DisplayName("GDR-STORE-11: encode(decode(encode(v))) equals encode(v) byte for byte")
    void roundTripIsByteIdentical() {
        var value = value();
        var encoded = codec.encode(value);

        var decoded = codec.decode(encoded);

        assertTrue(decoded.isOk(), "the codec reads its own content");
        var decodedValue = decoded.value();
        assertNotNull(decodedValue, "a decoded content yields a value");
        assertAll(
                () -> assertArrayEquals(encoded, codec.encode(decodedValue), "the round trip is byte-identical"),
                () -> assertEquals(value, decodedValue, "the round trip keeps the value"));
    }

    @RepeatedTest(REPETITIONS)
    @DisplayName("GDR-STORE-11: equal values in different insertion order encode identically")
    void equalValuesEncodeIdentically() {
        var value = value();
        List<Map.Entry<String, String>> reversed = new ArrayList<>(value.entrySet());
        Collections.reverse(reversed);
        Map<String, String> reordered = new LinkedHashMap<>();
        reversed.forEach(entry -> reordered.put(entry.getKey(), entry.getValue()));
        Map<String, String> hashed = new HashMap<>(value);

        var encoded = codec.encode(value);

        assertAll(
                () -> assertArrayEquals(encoded, codec.encode(reordered), "the reversed insertion order"),
                () -> assertArrayEquals(encoded, codec.encode(hashed), "a hash map of the same entries"),
                () -> assertArrayEquals(encoded, codec.encode(value), "an unchanged value encoded again"));
    }

    @Test
    @DisplayName("GDR-STORE-11, GDR-NFR-3: an undecodable content is MALFORMED, the detail without the content")
    void undecodableContentIsMalformed() {
        var unreadable = Generators.letterStrings(5, 30).next().toUpperCase(Locale.ROOT);
        var content = unreadable.getBytes(StandardCharsets.UTF_8);

        var result = codec.decode(content);

        var detail = result.detail();
        assertAll(
                () -> assertEquals(StoreOutcome.MALFORMED, result.outcome(), "the outcome is MALFORMED"),
                () -> assertNull(result.value(), "a non-success carries no value"),
                () -> assertNotNull(detail, "a non-success carries a detail"),
                () -> assertFalse(detail != null && detail.contains(unreadable),
                        "the detail does not repeat the content"));
    }

    /**
     * A canonical test codec: one {@code key=value} line per entry, sorted by key, each line ending in a line feed,
     * UTF-8. A key is not empty and holds neither {@code =} nor a line feed; a value holds no line feed.
     */
    private static final class KeyValueCodec implements Codec<Map<String, String>> {

        /** The separator of key and value. */
        private static final char SEPARATOR = '=';

        /** The end of a line. */
        private static final char LINE_FEED = '\n';

        @Override
        public byte[] encode(Map<String, String> value) {
            var text = new StringBuilder();
            new TreeMap<>(value).forEach(
                    (key, entry) -> text.append(key).append(SEPARATOR).append(entry).append(LINE_FEED));
            return text.toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public StoreResult<Map<String, String>> decode(byte[] content) {
            var text = new String(content, StandardCharsets.UTF_8);
            Map<String, String> value = new TreeMap<>();
            int start = 0;
            int line = 1;
            while (start < text.length()) {
                int end = text.indexOf(LINE_FEED, start);
                if (end < 0) {
                    return StoreResult.of(StoreOutcome.MALFORMED, "line " + line + " has no line end");
                }
                int separator = text.indexOf(SEPARATOR, start);
                if (separator < 0 || separator >= end) {
                    return StoreResult.of(StoreOutcome.MALFORMED, "line " + line + " has no separator");
                }
                if (separator == start) {
                    return StoreResult.of(StoreOutcome.MALFORMED, "line " + line + " has no key");
                }
                if (value.put(text.substring(start, separator), text.substring(separator + 1, end)) != null) {
                    return StoreResult.of(StoreOutcome.MALFORMED, "line " + line + " repeats a key");
                }
                start = end + 1;
                line++;
            }
            return StoreResult.ok(value);
        }
    }
}
