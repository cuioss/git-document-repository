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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The statement of the anchor provider {@code signed} (GDR-STORE-18) as {@link AnchorStatement} encodes, parses and
 * splits it: the exact five lines, the round trip, the sequence chaining, and the strict refusal of every other form.
 * <p>
 * Object ids and hashes are generated; the statement keywords and the example time are the specified format and kept
 * as literals.
 */
@EnableGeneratorController
@DisplayName("AnchorStatement (GDR-STORE-18)")
class AnchorStatementTest {

    private static final Instant TIME = Instant.parse("2026-11-16T09:30:00Z");

    private static ObjectId objectId() {
        byte[] bytes = new byte[20];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = Generators.integers(0, 255).next().byteValue();
        }
        return ObjectId.fromRaw(bytes);
    }

    private static byte[] hash() {
        byte[] bytes = new byte[32];
        for (int index = 0; index < bytes.length; index++) {
            bytes[index] = Generators.integers(0, 255).next().byteValue();
        }
        return bytes;
    }

    private static String text(ObjectId object, String time, String sequence, String previous) {
        return "anchor signed 1\nobject " + object.name() + "\ntime " + time + "\nsequence " + sequence + "\nprevious "
                + previous + "\n";
    }

    @Test
    @DisplayName("GDR-STORE-18: the first statement is the five specified lines with previous none")
    void firstStatement() {
        var object = objectId();

        var statement = AnchorStatement.next(object, TIME.plusMillis(700), null);

        assertAll(
                () -> assertEquals(text(object, "2026-11-16T09:30:00Z", "1", "none"),
                        new String(statement.encode(), UTF_8), "the encoded lines"),
                () -> assertEquals(TIME, statement.time(), "the time in whole seconds"),
                () -> assertNull(statement.previous(), "no previous hash"));
    }

    @Test
    @DisplayName("GDR-STORE-18: a following statement carries the next sequence and the SHA-256 of the previous proof")
    void chainedStatement() {
        byte[] proof = (text(objectId(), "2026-11-16T09:30:00Z", "41", HexFormat.of().formatHex(hash()))
                + SshSignatures.BEGIN + "\n").getBytes(UTF_8);
        var previous = AnchorStatement.split(proof).orElseThrow();
        var object = objectId();

        var statement = AnchorStatement.next(object, TIME.plusSeconds(60), previous);

        assertAll(
                () -> assertEquals(42, statement.sequence(), "the next sequence"),
                () -> assertArrayEquals(AnchorStatement.sha256(proof), statement.previous(),
                        "the SHA-256 of the whole previous proof"),
                () -> assertEquals(text(object, "2026-11-16T09:31:00Z", "42",
                        HexFormat.of().formatHex(AnchorStatement.sha256(proof))), new String(statement.encode(), UTF_8),
                        "the encoded lines"));
    }

    @Test
    @DisplayName("GDR-STORE-18: an encoded statement parses back to an equal statement")
    void roundTrip() {
        var first = new AnchorStatement(objectId(), TIME, 1, null);
        var later = new AnchorStatement(objectId(), TIME, Long.MAX_VALUE, hash());

        assertAll(
                () -> assertEquals(first, AnchorStatement.parse(first.encode()).orElseThrow(), "the first"),
                () -> assertEquals(later, AnchorStatement.parse(later.encode()).orElseThrow(), "the largest sequence"),
                () -> assertEquals(later.hashCode(),
                        AnchorStatement.parse(later.encode()).orElseThrow().hashCode(), "an equal hash"),
                () -> assertNotEquals(first, later, "different statements"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "other first line|anchor signed 2\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious none\n",
            "lines reordered|anchor signed 1\ntime 2026-11-16T09:30:00Z\nobject {o}\nsequence 1\nprevious none\n",
            "extra line|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious none\nmore\n",
            "trailing empty line|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious none\n\n",
            "missing line|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 1\n",
            "no final LF|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious none",
            "CR LF|anchor signed 1\r\nobject {o}\r\ntime 2026-11-16T09:30:00Z\r\nsequence 1\r\nprevious none\r\n",
            "upper-case object|anchor signed 1\nobject {O}\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious none\n",
            "short object|anchor signed 1\nobject abc\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious none\n",
            "fractional seconds|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00.5Z\nsequence 1\nprevious none\n",
            "no seconds|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30Z\nsequence 1\nprevious none\n",
            "offset|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00+01:00\nsequence 1\nprevious none\n",
            "no calendar date|anchor signed 1\nobject {o}\ntime 2026-02-30T09:30:00Z\nsequence 1\nprevious none\n",
            "hour 24|anchor signed 1\nobject {o}\ntime 2026-11-16T24:00:00Z\nsequence 1\nprevious none\n",
            "sequence 0|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 0\nprevious none\n",
            "leading zero|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 01\nprevious none\n",
            "negative|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence -1\nprevious none\n",
            "beyond long|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 9223372036854775808\nprevious {h}\n",
            "none after 1|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 2\nprevious none\n",
            "hash for 1|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious {h}\n",
            "upper-case hash|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 2\nprevious {H}\n",
            "short hash|anchor signed 1\nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 2\nprevious abcd\n",
            "space before value|anchor signed 1\nobject  {o}\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious none\n",
            "trailing space|anchor signed 1 \nobject {o}\ntime 2026-11-16T09:30:00Z\nsequence 1\nprevious none\n"})
    @DisplayName("GDR-STORE-18: every form the encoder does not produce is refused")
    void refusedForms(String scenario) {
        var parts = scenario.split("\\|", 2);
        String object = objectId().name();
        String hash = HexFormat.of().formatHex(hash());
        String text = parts[1].replace("{o}", object).replace("{O}", object.toUpperCase())
                .replace("{h}", hash).replace("{H}", hash.toUpperCase());

        assertTrue(AnchorStatement.parse(text.getBytes(UTF_8)).isEmpty(), () -> "refused: " + parts[0]);
    }

    @Test
    @DisplayName("GDR-STORE-18: bytes that are not UTF-8 are refused")
    void notUtf8() {
        byte[] bytes = text(objectId(), "2026-11-16T09:30:00Z", "1", "none").getBytes(UTF_8);
        bytes[bytes.length - 2] = (byte) 0xC3;

        assertTrue(AnchorStatement.parse(bytes).isEmpty(), "refused");
    }

    @Test
    @DisplayName("GDR-STORE-18: a proof splits into the statement bytes and the armored signature")
    void split() {
        var statement = new AnchorStatement(objectId(), TIME, 1, null);
        String signature = SshSignatures.BEGIN + "\nAAAA\n" + SshSignatures.END + "\n";
        byte[] statementBytes = statement.encode();
        byte[] proof = (new String(statementBytes, UTF_8) + signature).getBytes(UTF_8);

        var split = AnchorStatement.split(proof).orElseThrow();

        assertAll(
                () -> assertEquals(statement, split.statement(), "the statement"),
                () -> assertArrayEquals(statementBytes, split.statementBytes(), "the statement bytes"),
                () -> assertEquals(signature, split.signature(), "the signature"),
                () -> assertArrayEquals(proof, split.bytes(), "the whole proof"),
                () -> assertFalse(split.toString().contains("AAAA"), "the rendering names no signature"));
    }

    @Test
    @DisplayName("GDR-STORE-18: a proof without a statement or without an armored signature does not split")
    void refusedSplits() {
        byte[] statement = new AnchorStatement(objectId(), TIME, 1, null).encode();
        String text = new String(statement, UTF_8);

        assertAll(
                () -> assertTrue(AnchorStatement.split(statement).isEmpty(), "no signature"),
                () -> assertTrue(AnchorStatement.split((text + "garbage\n").getBytes(UTF_8)).isEmpty(),
                        "no armor"),
                () -> assertTrue(AnchorStatement.split((text + SshSignatures.BEGIN + "é\n").getBytes(UTF_8))
                        .isEmpty(), "a signature that is not ASCII"),
                () -> assertTrue(AnchorStatement.split(("anchor signed 1\n" + SshSignatures.BEGIN + "\n\n\n\n")
                        .getBytes(UTF_8)).isEmpty(), "an unreadable statement"),
                () -> assertTrue(AnchorStatement.split(new byte[0]).isEmpty(), "nothing"));
    }

    @Test
    @DisplayName("GDR-STORE-18: the components are checked and the previous hash is copied in and out")
    void components() {
        var object = objectId();
        byte[] previous = hash();
        var statement = new AnchorStatement(object, TIME, 2, previous);
        previous[0] ^= 1;
        Objects.requireNonNull(statement.previous())[1] ^= 1;

        assertAll(
                () -> assertArrayEquals(statement.previous(), new AnchorStatement(object, TIME, 2,
                        statement.previous()).previous(), "copies"),
                () -> assertNotEquals(previous[0], Objects.requireNonNull(statement.previous())[0],
                        "the input array changed after construction changes nothing"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AnchorStatement(object, TIME.plusMillis(1), 1, null), "fractional seconds"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AnchorStatement(object, TIME, 0, null), "sequence 0"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AnchorStatement(object, TIME, 2, null), "no hash after 1"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AnchorStatement(object, TIME, 1, hash()), "a hash for 1"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new AnchorStatement(object, TIME, 2, new byte[31]), "a short hash"),
                () -> assertEquals("AnchorStatement[object=" + object.name() + ", time=" + TIME + ", sequence=2]",
                        statement.toString(), "the rendering"));
    }
}
