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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serial;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The ULID of GDR-STORE-2: 26 characters of upper-case Crockford base32, a 48-bit millisecond timestamp before 80
 * random bits, so that ids of different milliseconds sort by creation time.
 * <p>
 * The times are generated; the alphabet, the length and the encodings of the bounds are the ULID specification and
 * kept as literals.
 */
@EnableGeneratorController
@DisplayName("Ulid (GDR-STORE-2)")
class UlidTest {

    /** The Crockford base32 alphabet of the ULID specification. */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** The largest timestamp a ULID encodes, {@code 2^48 - 1} milliseconds. */
    private static final long MAX_TIME = (1L << 48) - 1;

    /** A source of randomness that fills every byte with the same value. */
    private static final class FixedRandom extends Random {

        @Serial
        private static final long serialVersionUID = 1L;

        private final byte value;

        FixedRandom(int value) {
            this.value = (byte) value;
        }

        @Override
        public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, value);
        }
    }

    /** The largest step between two times of {@link #timeOrdering()}. */
    private static final long MAX_STEP = 1_000_000;

    /** A time that leaves room for a later time {@link #MAX_STEP} milliseconds after it. */
    private static Instant time() {
        return Instant.ofEpochMilli(Generators.longs(0, MAX_TIME - MAX_STEP).next());
    }

    @Test
    @DisplayName("GDR-STORE-2: a new ULID has 26 characters of the upper-case Crockford alphabet")
    void lengthAndAlphabet() {
        var ulid = Ulid.next();

        assertAll(
                () -> assertEquals(26, ulid.length(), "26 characters"),
                () -> assertTrue(ulid.chars().allMatch(character -> ALPHABET.indexOf(character) >= 0),
                        () -> "only Crockford base32: " + ulid),
                () -> assertTrue(Ulid.isValid(ulid), "a generated ULID is valid"));
    }

    @Test
    @DisplayName("GDR-STORE-2: the bounds encode as the specification states")
    void boundEncodings() {
        assertAll(
                () -> assertEquals("00000000000000000000000000", Ulid.next(Instant.EPOCH, new FixedRandom(0)),
                        "the epoch and zero bits"),
                () -> assertEquals("7ZZZZZZZZZZZZZZZZZZZZZZZZZ",
                        Ulid.next(Instant.ofEpochMilli(MAX_TIME), new FixedRandom(0xFF)),
                        "the largest time and all bits set"));
    }

    @Test
    @DisplayName("GDR-STORE-2: the time prefix orders ULIDs of different milliseconds by creation time")
    void timeOrdering() {
        var earlier = time();
        var later = earlier.plusMillis(Generators.longs(1, MAX_STEP).next());
        var random = new Random(Generators.longs().next());

        var first = Ulid.next(earlier, random);
        var second = Ulid.next(later, new FixedRandom(0));

        assertAll(
                () -> assertTrue(first.compareTo(second) < 0, () -> first + " sorts before " + second),
                () -> assertEquals(first.substring(0, 10), Ulid.next(earlier, new FixedRandom(0)).substring(0, 10),
                        "the first ten characters depend on the time only"));
    }

    @Test
    @DisplayName("GDR-STORE-2: generated ULIDs are unique")
    void uniqueness() {
        Set<String> ids = new HashSet<>();
        int count = 10_000;

        for (int index = 0; index < count; index++) {
            ids.add(Ulid.next());
        }

        assertEquals(count, ids.size(), "no ULID is generated twice");
    }

    @Test
    @DisplayName("GDR-STORE-2: a time outside the 48-bit range is refused")
    void timeOutOfRange() {
        var random = new FixedRandom(0);
        var beforeEpoch = Instant.EPOCH.minusMillis(1);
        var beyondRange = Instant.ofEpochMilli(MAX_TIME + 1);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> Ulid.next(beforeEpoch, random),
                        "before the epoch"),
                () -> assertThrows(IllegalArgumentException.class, () -> Ulid.next(beyondRange, random),
                        "beyond 2^48 - 1 milliseconds"));
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "0000000000000000000000000", "000000000000000000000000000",
            "0000000000000000000000000a", "0000000000000000000000000I", "0000000000000000000000000L",
            "0000000000000000000000000O", "0000000000000000000000000U", "80000000000000000000000000",
            "0000000000000000000000000-", "000000000000/0000000000000"})
    @DisplayName("GDR-STORE-2: a text of the wrong length, outside the alphabet or beyond the time range is no ULID")
    void invalid(String value) {
        assertFalse(Ulid.isValid(value), "not a ULID");
    }

    @Test
    @DisplayName("GDR-STORE-2: null is no ULID")
    void nullIsInvalid() {
        assertFalse(Ulid.isValid(null), "null is not a ULID");
    }
}
