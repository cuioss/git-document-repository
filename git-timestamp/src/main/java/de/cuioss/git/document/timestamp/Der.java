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
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.Serial;
import java.math.BigInteger;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A minimal reader and writer of the Distinguished Encoding Rules (DER) of ASN.1, as far as RFC 3161 and the CMS
 * structures of its token need them: tags of one octet, definite lengths, INTEGER, OBJECT IDENTIFIER, OCTET STRING,
 * BIT STRING, BOOLEAN, NULL, UTF8String, UTCTime, GeneralizedTime, SEQUENCE, SET and context-specific tags
 * (<a href="https://www.itu.int/rec/T-REC-X.690">ITU-T X.690</a>, clauses 8, 10 and 11).
 * <p>
 * The reader is bounded: every element must lie inside its enclosing element, a length takes at most four octets and
 * must be minimal, the indefinite length and the multi-octet tag form are refused, and an INTEGER must be minimal. A
 * malformed input raises the checked {@link DerException}, never an unchecked exception; the classes of this package
 * turn it into a non-success result.
 */
final class Der {

    /** The tag of BOOLEAN. */
    static final int BOOLEAN = 0x01;
    /** The tag of INTEGER. */
    static final int INTEGER = 0x02;
    /** The tag of BIT STRING. */
    static final int BIT_STRING = 0x03;
    /** The tag of OCTET STRING. */
    static final int OCTET_STRING = 0x04;
    /** The tag of NULL. */
    static final int NULL = 0x05;
    /** The tag of OBJECT IDENTIFIER. */
    static final int OID = 0x06;
    /** The tag of UTF8String. */
    static final int UTF8_STRING = 0x0C;
    /** The tag of UTCTime. */
    static final int UTC_TIME = 0x17;
    /** The tag of GeneralizedTime. */
    static final int GENERALIZED_TIME = 0x18;
    /** The tag of SEQUENCE and SEQUENCE OF, constructed. */
    static final int SEQUENCE = 0x30;
    /** The tag of SET and SET OF, constructed. */
    static final int SET = 0x31;

    /** The constructed bit of a tag. */
    private static final int CONSTRUCTED = 0x20;
    /** The class bits of a context-specific tag. */
    private static final int CONTEXT = 0x80;
    /** The low tag bits that announce the multi-octet tag form. */
    private static final int HIGH_TAG = 0x1F;
    /** The largest number of length octets accepted in the long form. */
    private static final int MAX_LENGTH_OCTETS = 4;
    /** The largest number of digits of a fraction of seconds in a GeneralizedTime. */
    private static final int MAX_FRACTION_DIGITS = 9;
    /** The largest value of an arc of an object identifier this reader accepts, so the arithmetic cannot overflow. */
    private static final long MAX_ARC = Long.MAX_VALUE >> 7;

    private static final DateTimeFormatter GENERALIZED = DateTimeFormatter.ofPattern("uuuuMMddHHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuMMddHHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    private Der() {
        // static helpers only
    }

    /**
     * Returns the tag of a constructed context-specific element, {@code [n]} of an explicit tag or of an implicitly
     * tagged SEQUENCE or SET.
     *
     * @param number the tag number, 0 to 30
     * @return the tag
     */
    static int contextConstructed(int number) {
        return CONTEXT | CONSTRUCTED | checkedTagNumber(number);
    }

    /**
     * Returns the tag of a primitive context-specific element, {@code [n]} of an implicitly tagged primitive.
     *
     * @param number the tag number, 0 to 30
     * @return the tag
     */
    static int contextPrimitive(int number) {
        return CONTEXT | checkedTagNumber(number);
    }

    private static int checkedTagNumber(int number) {
        if (number < 0 || number >= HIGH_TAG) {
            throw new IllegalArgumentException("tag number out of range: " + number);
        }
        return number;
    }

    // ---------------------------------------------------------------- writer

    /**
     * Encodes an element of a tag and its content.
     *
     * @param tag     the tag, one octet
     * @param content the content octets
     * @return the encoding
     */
    static byte[] tlv(int tag, byte[] content) {
        var out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int octets = (Integer.SIZE - Integer.numberOfLeadingZeros(length) + 7) / 8;
            out.write(0x80 | octets);
            for (int shift = (octets - 1) * 8; shift >= 0; shift -= 8) {
                out.write(length >>> shift);
            }
        }
        out.writeBytes(content);
        return out.toByteArray();
    }

    /**
     * Concatenates encodings.
     *
     * @param parts the encodings
     * @return the octets of all parts in order
     */
    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /**
     * Encodes a SEQUENCE of elements in the given order.
     *
     * @param elements the encoded elements
     * @return the encoding
     */
    static byte[] sequence(byte[]... elements) {
        return tlv(SEQUENCE, concat(elements));
    }

    /**
     * Encodes a SET OF, its elements sorted by their encodings as DER requires (X.690, 11.6).
     *
     * @param elements the encoded elements
     * @return the encoding
     */
    static byte[] setOf(byte[]... elements) {
        return taggedSetOf(SET, elements);
    }

    /**
     * Concatenates elements sorted by their encodings, for a SET OF under any tag, such as the implicitly tagged
     * signed attributes of CMS (RFC 5652, 5.3 and 5.4).
     *
     * @param tag      the tag of the set
     * @param elements the encoded elements
     * @return the encoding of the set
     */
    static byte[] taggedSetOf(int tag, byte[]... elements) {
        byte[][] sorted = elements.clone();
        Arrays.sort(sorted, Arrays::compareUnsigned);
        return tlv(tag, concat(sorted));
    }

    /**
     * Encodes an INTEGER.
     *
     * @param value the value
     * @return the encoding, minimal in two's complement
     */
    static byte[] integer(BigInteger value) {
        return tlv(INTEGER, value.toByteArray());
    }

    /**
     * Encodes an INTEGER.
     *
     * @param value the value
     * @return the encoding, minimal in two's complement
     */
    static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    /**
     * Encodes a BOOLEAN, {@code TRUE} as the octet {@code FF} (X.690, 11.1).
     *
     * @param value the value
     * @return the encoding
     */
    static byte[] bool(boolean value) {
        return tlv(BOOLEAN, new byte[]{(byte) (value ? 0xFF : 0x00)});
    }

    /**
     * Encodes a NULL.
     *
     * @return the encoding
     */
    static byte[] nullValue() {
        return tlv(NULL, new byte[0]);
    }

    /**
     * Encodes an OCTET STRING.
     *
     * @param value the octets
     * @return the encoding
     */
    static byte[] octetString(byte[] value) {
        return tlv(OCTET_STRING, value);
    }

    /**
     * Encodes a BIT STRING of whole octets.
     *
     * @param value the octets
     * @return the encoding, with zero unused bits
     */
    static byte[] bitString(byte[] value) {
        return tlv(BIT_STRING, concat(new byte[]{0}, value));
    }

    /**
     * Encodes a UTF8String.
     *
     * @param value the text
     * @return the encoding
     */
    static byte[] utf8String(String value) {
        return tlv(UTF8_STRING, value.getBytes(UTF_8));
    }

    /**
     * Encodes a GeneralizedTime in whole seconds of UTC, {@code YYYYMMDDHHMMSSZ} (X.690, 11.7).
     *
     * @param time the time; a fraction of a second is dropped
     * @return the encoding
     */
    static byte[] generalizedTime(Instant time) {
        return tlv(GENERALIZED_TIME, GENERALIZED.format(time).getBytes(US_ASCII));
    }

    /**
     * Encodes a UTCTime in whole seconds, {@code YYMMDDHHMMSSZ} (X.690, 11.8), for the years 1950 to 2049.
     *
     * @param time the time; a fraction of a second is dropped
     * @return the encoding
     */
    static byte[] utcTime(Instant time) {
        return tlv(UTC_TIME, UTC.format(time).getBytes(US_ASCII));
    }

    /**
     * Encodes an explicitly tagged element {@code [n] EXPLICIT}.
     *
     * @param number  the tag number
     * @param element the encoded inner element
     * @return the encoding
     */
    static byte[] explicit(int number, byte[] element) {
        return tlv(contextConstructed(number), element);
    }

    /**
     * Encodes an OBJECT IDENTIFIER from its dotted form.
     *
     * @param dotted the dotted form, at least two arcs
     * @return the encoding
     * @throws IllegalArgumentException if the form is not an object identifier
     */
    static byte[] oid(String dotted) {
        String[] arcs = dotted.split("\\.");
        if (arcs.length < 2) {
            throw new IllegalArgumentException("an object identifier needs two arcs: " + dotted);
        }
        long first = Long.parseLong(arcs[0]);
        long second = Long.parseLong(arcs[1]);
        if (first > 2 || first < 0 || second < 0 || first < 2 && second >= 40) {
            throw new IllegalArgumentException("not an object identifier: " + dotted);
        }
        var out = new ByteArrayOutputStream();
        writeArc(out, first * 40 + second);
        for (int index = 2; index < arcs.length; index++) {
            long arc = Long.parseLong(arcs[index]);
            if (arc < 0) {
                throw new IllegalArgumentException("not an object identifier: " + dotted);
            }
            writeArc(out, arc);
        }
        return tlv(OID, out.toByteArray());
    }

    private static void writeArc(ByteArrayOutputStream out, long arc) {
        int groups = Math.max(1, (Long.SIZE - Long.numberOfLeadingZeros(arc) + 6) / 7);
        for (int group = groups - 1; group >= 0; group--) {
            int bits = (int) (arc >>> (group * 7)) & 0x7F;
            out.write(group == 0 ? bits : bits | 0x80);
        }
    }

    // ---------------------------------------------------------------- reader

    /**
     * Reads the single element that spans the input.
     *
     * @param data the encoding; the element keeps a reference to it, so the caller passes a copy it does not change
     * @return the element
     * @throws DerException if the input is not exactly one well-formed element
     */
    static Element parse(byte[] data) throws DerException {
        Objects.requireNonNull(data, "data");
        var element = read(data, 0, data.length);
        if (element.end != data.length) {
            throw new DerException("octets after the element");
        }
        return element;
    }

    private static Element read(byte[] data, int offset, int limit) throws DerException {
        if (offset >= limit) {
            throw new DerException("element expected");
        }
        int tag = data[offset] & 0xFF;
        if ((tag & HIGH_TAG) == HIGH_TAG) {
            throw new DerException("multi-octet tag");
        }
        int position = offset + 1;
        if (position >= limit) {
            throw new DerException("length expected");
        }
        int first = data[position++] & 0xFF;
        long length;
        if (first < 0x80) {
            length = first;
        } else {
            int octets = first & 0x7F;
            if (octets == 0) {
                throw new DerException("indefinite length");
            }
            if (octets > MAX_LENGTH_OCTETS || position + octets > limit) {
                throw new DerException("length out of bounds");
            }
            length = 0;
            for (int index = 0; index < octets; index++) {
                length = (length << 8) | (data[position++] & 0xFF);
            }
            if (length < 0x80 || length >> ((octets - 1) * 8) == 0) {
                throw new DerException("length not minimal");
            }
        }
        if (length > limit - position) {
            throw new DerException("content out of bounds");
        }
        return new Element(tag, data, offset, position, position + (int) length);
    }

    /**
     * An element read from an encoding: its tag and the bounds of its content inside the encoding.
     */
    static final class Element {

        private final int tag;
        private final byte[] data;
        private final int start;
        private final int contentStart;
        private final int end;

        private Element(int tag, byte[] data, int start, int contentStart, int end) {
            this.tag = tag;
            this.data = data;
            this.start = start;
            this.contentStart = contentStart;
            this.end = end;
        }

        /**
         * Returns the tag.
         *
         * @return the tag octet
         */
        int tag() {
            return tag;
        }

        /**
         * Returns a copy of the content octets.
         *
         * @return the content
         */
        byte[] content() {
            return Arrays.copyOfRange(data, contentStart, end);
        }

        /**
         * Returns a copy of the whole encoding of the element, tag and length included.
         *
         * @return the encoding
         */
        byte[] encoded() {
            return Arrays.copyOfRange(data, start, end);
        }

        /**
         * Checks the tag.
         *
         * @param expected the expected tag
         * @return this element
         * @throws DerException if the tag differs
         */
        Element expect(int expected) throws DerException {
            if (tag != expected) {
                throw new DerException("unexpected tag");
            }
            return this;
        }

        /**
         * Reads the elements of a constructed element.
         *
         * @return the elements in order, possibly empty
         * @throws DerException if the element is primitive or its content is not a sequence of elements
         */
        List<Element> children() throws DerException {
            if ((tag & CONSTRUCTED) == 0) {
                throw new DerException("primitive element has no children");
            }
            List<Element> children = new ArrayList<>();
            int position = contentStart;
            while (position < end) {
                var child = read(data, position, end);
                children.add(child);
                position = child.end;
            }
            return children;
        }

        /**
         * Reads the elements of a constructed element of the given tag.
         *
         * @param expected the expected tag
         * @return the elements in order
         * @throws DerException if the tag differs or the content is malformed
         */
        List<Element> children(int expected) throws DerException {
            return expect(expected).children();
        }

        /**
         * Reads the only element inside an explicitly tagged element.
         *
         * @return the inner element
         * @throws DerException if the element does not hold exactly one element
         */
        Element inner() throws DerException {
            var children = children();
            if (children.size() != 1) {
                throw new DerException("explicit tag must hold one element");
            }
            return children.getFirst();
        }

        /**
         * Reads an INTEGER.
         *
         * @return the value
         * @throws DerException if the element is no INTEGER or is not minimal
         */
        BigInteger integer() throws DerException {
            expect(INTEGER);
            int length = end - contentStart;
            if (length == 0) {
                throw new DerException("empty integer");
            }
            if (length > 1) {
                int lead = data[contentStart];
                int next = data[contentStart + 1];
                if (lead == 0 && next >= 0 || lead == -1 && next < 0) {
                    throw new DerException("integer not minimal");
                }
            }
            return new BigInteger(content());
        }

        /**
         * Reads a BOOLEAN.
         *
         * @return the value
         * @throws DerException if the element is no BOOLEAN of the octet {@code 00} or {@code FF}
         */
        boolean bool() throws DerException {
            expect(BOOLEAN);
            if (end - contentStart != 1) {
                throw new DerException("boolean of wrong length");
            }
            int value = data[contentStart] & 0xFF;
            if (value != 0x00 && value != 0xFF) {
                throw new DerException("boolean not canonical");
            }
            return value == 0xFF;
        }

        /**
         * Reads the octets of an OCTET STRING.
         *
         * @return a copy of the octets
         * @throws DerException if the element is no OCTET STRING
         */
        byte[] octets() throws DerException {
            return expect(OCTET_STRING).content();
        }

        /**
         * Reads a BIT STRING of whole octets.
         *
         * @return a copy of the octets
         * @throws DerException if the element is no BIT STRING or has unused bits
         */
        byte[] bits() throws DerException {
            expect(BIT_STRING);
            if (end == contentStart || data[contentStart] != 0) {
                throw new DerException("bit string with unused bits");
            }
            return Arrays.copyOfRange(data, contentStart + 1, end);
        }

        /**
         * Reads an OBJECT IDENTIFIER into its dotted form.
         *
         * @return the dotted form
         * @throws DerException if the element is no well-formed object identifier
         */
        String oid() throws DerException {
            expect(OID);
            if (end == contentStart) {
                throw new DerException("empty object identifier");
            }
            var dotted = new StringBuilder();
            long arc = 0;
            boolean firstArc = true;
            boolean arcStart = true;
            for (int index = contentStart; index < end; index++) {
                int octet = data[index] & 0xFF;
                if (arcStart && octet == 0x80) {
                    throw new DerException("object identifier arc not minimal");
                }
                if (arc > MAX_ARC) {
                    throw new DerException("object identifier arc too large");
                }
                arc = (arc << 7) | (octet & 0x7F);
                arcStart = (octet & 0x80) == 0;
                if (arcStart) {
                    if (firstArc) {
                        long top = Math.min(arc / 40, 2);
                        dotted.append(top).append('.').append(arc - top * 40);
                        firstArc = false;
                    } else {
                        dotted.append('.').append(arc);
                    }
                    arc = 0;
                }
            }
            if (!arcStart) {
                throw new DerException("object identifier truncated");
            }
            return dotted.toString();
        }

        /**
         * Reads a GeneralizedTime of DER, {@code YYYYMMDDHHMMSS[.f+]Z} in UTC (X.690, 11.7).
         *
         * @return the time
         * @throws DerException if the element is no GeneralizedTime of that form
         */
        Instant generalizedTime() throws DerException {
            expect(GENERALIZED_TIME);
            String text = new String(content(), US_ASCII);
            if (text.length() < 15 || text.charAt(text.length() - 1) != 'Z') {
                throw new DerException("generalized time not in UTC");
            }
            String whole = text.substring(0, 14);
            String fraction = text.substring(14, text.length() - 1);
            if (!digits(whole) || !fraction.isEmpty() && !validFraction(fraction)) {
                throw new DerException("generalized time malformed");
            }
            try {
                var dateTime = LocalDateTime.of(Integer.parseInt(whole.substring(0, 4)),
                        Integer.parseInt(whole.substring(4, 6)), Integer.parseInt(whole.substring(6, 8)),
                        Integer.parseInt(whole.substring(8, 10)), Integer.parseInt(whole.substring(10, 12)),
                        Integer.parseInt(whole.substring(12, 14)));
                int nanos = fraction.isEmpty() ? 0
                        : Integer.parseInt((fraction.substring(1) + "000000000").substring(0, MAX_FRACTION_DIGITS));
                return dateTime.withNano(nanos).toInstant(ZoneOffset.UTC);
            } catch (DateTimeException e) {
                throw new DerException("generalized time out of range");
            }
        }

        private static boolean validFraction(String fraction) {
            String digits = fraction.substring(1);
            return fraction.charAt(0) == '.' && !digits.isEmpty() && digits.length() <= MAX_FRACTION_DIGITS
                    && digits(digits) && digits.charAt(digits.length() - 1) != '0';
        }

        private static boolean digits(String text) {
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (character < '0' || character > '9') {
                    return false;
                }
            }
            return true;
        }

        /**
         * Renders the element by its tag and length, never its content.
         *
         * @return {@code Der.Element[tag=0x30, length=12]}
         */
        @Override
        public String toString() {
            return "Der.Element[tag=0x" + Integer.toHexString(tag) + ", length=" + (end - contentStart) + "]";
        }
    }

    /**
     * A malformed encoding. The message names the rule the input breaks, never an octet of it.
     */
    static final class DerException extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param message the rule the input breaks
         */
        DerException(String message) {
            super(message);
        }
    }
}
