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

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.Random;


import org.jspecify.annotations.Nullable;

/**
 * The ULID, the id of a document of a per-document collection and of the draft of a new numbered entry (GDR-STORE-2).
 * <p>
 * A ULID is 26 characters of Crockford base32 in upper case: the first 10 encode a 48-bit timestamp in milliseconds
 * since the Unix epoch, the last 16 encode 80 random bits. Two ULIDs created in different milliseconds therefore sort
 * lexicographically by their creation time (<a href="https://github.com/ulid/spec">ULID specification</a>, sections
 * <em>Specification</em>, <em>Encoding</em> and <em>Sorting</em>). Ids created within the same millisecond are not
 * ordered among themselves; this class does not implement the optional monotonic mode of the specification.
 * <p>
 * The random part comes from a {@link SecureRandom}, so an id cannot be guessed from another one. The alphabet contains
 * neither a slash nor any character that git-check-ref-format(1) forbids, so a ULID is a valid segment of a reference
 * name by construction.
 *
 * @since 0.1
 */
public final class Ulid {

    /** The length of a ULID: 26 characters. */
    public static final int LENGTH = 26;

    /** The Crockford base32 alphabet: digits and upper-case letters without I, L, O and U. */
    private static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    /** The number of characters encoding the timestamp. */
    private static final int TIME_LENGTH = 10;

    /** The number of characters encoding each 40-bit half of the random part. */
    private static final int HALF_LENGTH = 8;

    /** The number of bytes of each 40-bit half of the random part. */
    private static final int HALF_BYTES = 5;

    /** The number of bits one character encodes. */
    private static final int BITS_PER_CHAR = 5;

    /** The mask of the bits one character encodes. */
    private static final int CHAR_MASK = 0x1F;

    /** The largest timestamp a ULID can encode, {@code 2^48 - 1} milliseconds. */
    private static final long MAX_TIME = (1L << 48) - 1;

    /**
     * The largest first character: the 10 time characters carry 50 bits, of which only 48 are used, so the first
     * character is at most {@code 7}.
     */
    private static final char MAX_FIRST = '7';

    /** The source of the random part of {@link #next()}. */
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    /**
     * Creates a new ULID from the current time and 80 random bits of a {@link SecureRandom}.
     *
     * @return a ULID of {@value #LENGTH} characters
     */
    public static String next() {
        return next(Instant.now(), RANDOM);
    }

    /**
     * Creates a ULID from a given time and a given source of randomness, so that tests are deterministic.
     *
     * @param time   the creation time, from the Unix epoch to {@code 2^48 - 1} milliseconds after it
     * @param random the source of the 80 random bits
     * @return a ULID of {@value #LENGTH} characters
     * @throws NullPointerException     if {@code time} or {@code random} is {@code null}
     * @throws IllegalArgumentException if {@code time} lies outside the range a ULID can encode
     */
    static String next(Instant time, Random random) {
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(random, "random");
        if (time.isBefore(Instant.EPOCH) || time.isAfter(Instant.ofEpochMilli(MAX_TIME))) {
            throw new IllegalArgumentException("the time lies outside the range of a ULID");
        }
        char[] chars = new char[LENGTH];
        encode(time.toEpochMilli(), chars, 0, TIME_LENGTH);
        byte[] bytes = new byte[2 * HALF_BYTES];
        random.nextBytes(bytes);
        encode(toLong(bytes, 0), chars, TIME_LENGTH, HALF_LENGTH);
        encode(toLong(bytes, HALF_BYTES), chars, TIME_LENGTH + HALF_LENGTH, HALF_LENGTH);
        return new String(chars);
    }

    /**
     * Tells whether a text is a ULID in its canonical form: 26 characters of the upper-case Crockford alphabet whose
     * first character is at most {@code 7}.
     *
     * @param value the text, possibly {@code null}
     * @return whether the text is a ULID; {@code false} for {@code null}
     */
    public static boolean isValid(@Nullable String value) {
        if (value == null || value.length() != LENGTH || value.charAt(0) > MAX_FIRST) {
            return false;
        }
        for (int index = 0; index < LENGTH; index++) {
            if (ALPHABET.indexOf(value.charAt(index)) < 0) {
                return false;
            }
        }
        return true;
    }

    /** Writes the lowest {@code count * 5} bits of a value as {@code count} characters, most significant first. */
    private static void encode(long value, char[] target, int offset, int count) {
        long rest = value;
        for (int index = offset + count - 1; index >= offset; index--) {
            target[index] = ALPHABET.charAt((int) (rest & CHAR_MASK));
            rest >>>= BITS_PER_CHAR;
        }
    }

    /** Reads five bytes, big-endian, as the lowest 40 bits of a long. */
    private static long toLong(byte[] bytes, int offset) {
        long value = 0;
        for (int index = offset; index < offset + HALF_BYTES; index++) {
            value = (value << Byte.SIZE) | (bytes[index] & 0xFFL);
        }
        return value;
    }
}
