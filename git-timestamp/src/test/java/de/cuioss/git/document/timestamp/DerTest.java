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
package de.cuioss.git.document.timestamp;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * GDR-TSA-1 and GDR-TSA-2: the DER reader and writer of {@code git-timestamp} round-trip the types RFC 3161 needs, and
 * refuse a malformed input with the checked {@link Der.DerException}, never an unchecked exception.
 */
@DisplayName("GDR-TSA-1: DER reader and writer")
class DerTest {

    private static final HexFormat HEX = HexFormat.of();

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 127, 128, 255, 256, -1, -128, -129, Long.MAX_VALUE, Long.MIN_VALUE})
    @DisplayName("Should round-trip an INTEGER in its minimal encoding")
    void shouldRoundTripInteger(long value) throws Exception {
        byte[] encoded = Der.integer(value);

        assertEquals(BigInteger.valueOf(value), Der.parse(encoded).integer(), "value");
    }

    @Test
    @DisplayName("Should encode known values as X.690 states them")
    void shouldEncodeKnownValues() {
        assertAll(
                () -> assertEquals("020100", HEX.formatHex(Der.integer(0)), "INTEGER 0"),
                () -> assertEquals("02020080", HEX.formatHex(Der.integer(128)), "INTEGER 128"),
                () -> assertEquals("0101ff", HEX.formatHex(Der.bool(true)), "BOOLEAN TRUE"),
                () -> assertEquals("0500", HEX.formatHex(Der.nullValue()), "NULL"),
                () -> assertEquals("0609608648016503040201", HEX.formatHex(Der.oid(TimeStampRequests.SHA_256)),
                        "OID of SHA-256"),
                () -> assertEquals("170d3236313132343132303030305a",
                        HEX.formatHex(Der.utcTime(Instant.parse("2026-11-24T12:00:00Z"))), "UTCTime"),
                () -> assertEquals("180f32303236313132343132303030305a",
                        HEX.formatHex(Der.generalizedTime(Instant.parse("2026-11-24T12:00:00Z"))),
                        "GeneralizedTime"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2.16.840.1.101.3.4.2.1", "1.2.840.113549.1.9.16.1.4", "0.0", "2.999.1",
            "1.3.6.1.5.5.7.3.8"})
    @DisplayName("Should round-trip an OBJECT IDENTIFIER")
    void shouldRoundTripOid(String oid) throws Exception {
        assertEquals(oid, Der.parse(Der.oid(oid)).oid(), "dotted form");
    }

    @Test
    @DisplayName("Should round-trip long contents with lengths of two and three octets")
    void shouldRoundTripLongLengths() throws Exception {
        var random = new SecureRandom();
        for (int size : new int[]{127, 128, 255, 256, 65_535, 65_536}) {
            byte[] content = new byte[size];
            random.nextBytes(content);

            assertArrayEquals(content, Der.parse(Der.octetString(content)).octets(), () -> "size " + size);
        }
    }

    @Test
    @DisplayName("Should round-trip a nested sequence, an explicit tag and a time")
    void shouldRoundTripStructure() throws Exception {
        Instant time = Instant.parse("2026-11-24T08:46:33Z");
        byte[] encoded = Der.sequence(Der.integer(1), Der.explicit(0, Der.octetString(new byte[]{9})),
                Der.generalizedTime(time), Der.bitString(new byte[]{1, 2}));

        var children = Der.parse(encoded).children(Der.SEQUENCE);

        assertAll(
                () -> assertEquals(4, children.size(), "children"),
                () -> assertEquals(BigInteger.ONE, children.getFirst().integer(), "integer"),
                () -> assertArrayEquals(new byte[]{9}, children.get(1).inner().octets(), "explicit"),
                () -> assertEquals(time, children.get(2).generalizedTime(), "time"),
                () -> assertArrayEquals(new byte[]{1, 2}, children.get(3).bits(), "bits"),
                () -> assertArrayEquals(encoded, Der.parse(encoded).encoded(), "encoding"));
    }

    @Test
    @DisplayName("Should sort the elements of a SET OF by their encodings")
    void shouldSortSetOf() throws Exception {
        var set = Der.parse(Der.setOf(Der.integer(300), Der.integer(2), Der.integer(1))).children(Der.SET);

        assertAll(
                () -> assertEquals(BigInteger.ONE, set.getFirst().integer(), "first"),
                () -> assertEquals(BigInteger.TWO, set.get(1).integer(), "second"),
                () -> assertEquals(BigInteger.valueOf(300), set.get(2).integer(), "third"));
    }

    @Test
    @DisplayName("Should read a GeneralizedTime with a fraction of seconds")
    void shouldReadFraction() throws Exception {
        byte[] encoded = Der.tlv(Der.GENERALIZED_TIME, "20261124084633.25Z".getBytes(US_ASCII));

        assertEquals(Instant.parse("2026-11-24T08:46:33.250Z"), Der.parse(encoded).generalizedTime(), "time");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",               // nothing
            "30",             // no length
            "3005020101",     // content beyond the input
            "3080020101 0000",// indefinite length
            "308101",         // length out of bounds
            "30810100",       // long form for a short length
            "3082000100",     // leading zero length octet
            "3085000000000100", // five length octets
            "1f0100",         // multi-octet tag
            "02010100",       // octets after the element
            "020100ff"        // octets after the element
    })
    @DisplayName("Should refuse a malformed structure with DerException")
    void shouldRefuseMalformedStructure(String hex) {
        byte[] input = HEX.parseHex(hex.replace(" ", ""));

        assertThrows(Der.DerException.class, () -> Der.parse(input).children());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0200", "02020001", "0202ff80"})
    @DisplayName("Should refuse an empty or non-minimal INTEGER")
    void shouldRefuseMalformedInteger(String hex) {
        assertThrows(Der.DerException.class, () -> Der.parse(HEX.parseHex(hex)).integer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0600", "060180", "06028001", "060188"})
    @DisplayName("Should refuse an empty, non-minimal or truncated OBJECT IDENTIFIER")
    void shouldRefuseMalformedOid(String hex) {
        assertThrows(Der.DerException.class, () -> Der.parse(HEX.parseHex(hex)).oid());
    }

    @ParameterizedTest
    @ValueSource(strings = {"20261124084633", "20261124084633+0100", "20261324084633Z", "2026112408463aZ",
            "20261124084633.Z", "20261124084633.50Z", "20261124084633,5Z"})
    @DisplayName("Should refuse a GeneralizedTime that is not DER in UTC")
    void shouldRefuseMalformedTime(String text) {
        byte[] encoded = Der.tlv(Der.GENERALIZED_TIME, text.getBytes(US_ASCII));

        assertThrows(Der.DerException.class, () -> Der.parse(encoded).generalizedTime());
    }

    @Test
    @DisplayName("Should refuse a wrong tag, a non-canonical BOOLEAN and children of a primitive")
    void shouldRefuseWrongTypes() throws Exception {
        var integer = Der.parse(Der.integer(1));

        assertAll(
                () -> assertThrows(Der.DerException.class, integer::octets, "wrong tag"),
                () -> assertThrows(Der.DerException.class, integer::children, "primitive"),
                () -> assertThrows(Der.DerException.class, () -> Der.parse(HEX.parseHex("010101")).bool(),
                        "boolean 01"),
                () -> assertThrows(Der.DerException.class, () -> Der.parse(HEX.parseHex("03020701")).bits(),
                        "unused bits"),
                () -> assertThrows(Der.DerException.class,
                        () -> Der.parse(Der.explicit(0, Der.concat(Der.integer(1), Der.integer(2)))).inner(),
                        "two elements under an explicit tag"));
    }

    @Test
    @DisplayName("Should never throw an unchecked exception on random input")
    void shouldSurviveRandomInput() {
        var random = new SecureRandom();
        assertDoesNotThrow(() -> {
            for (int run = 0; run < 2_000; run++) {
                byte[] input = new byte[random.nextInt(64)];
                random.nextBytes(input);
                try {
                    walk(Der.parse(input), 0);
                } catch (Der.DerException e) {
                    assertFalse(e.getMessage().isBlank(), "the refusal names a rule");
                }
            }
        }, "no unchecked exception escapes the reader");
    }

    private static void walk(Der.Element element, int depth) throws Der.DerException {
        if (depth > 64) {
            return;
        }
        if ((element.tag() & 0x20) != 0) {
            for (Der.Element child : element.children()) {
                walk(child, depth + 1);
            }
        }
    }

    @Test
    @DisplayName("Should render an element without its content")
    void shouldRenderWithoutContent() throws Exception {
        var element = Der.parse(Der.octetString(new byte[]{0x41, 0x42}));

        assertEquals("Der.Element[tag=0x4, length=2]", element.toString(), "rendering");
    }
}
