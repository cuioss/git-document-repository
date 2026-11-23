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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;


import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;

/**
 * The statement of the anchor provider {@code signed} (GDR-STORE-18) and its codec: five lines in UTF-8, each ended by
 * LF.
 * <pre>
 * anchor signed 1
 * object &lt;object id, lowercase hex&gt;
 * time &lt;UTC, ISO 8601 with seconds, for example 2026-11-16T09:30:00Z&gt;
 * sequence &lt;n, 1 for the first proof&gt;
 * previous &lt;SHA-256 of the previous proof, lowercase hex, or "none" for sequence 1&gt;
 * </pre>
 * The parse is strict: any other first line, line order or line count, an extra line, a CR, a byte that is not UTF-8,
 * an upper-case hex digit, a time with fractional seconds, an offset other than {@code Z} or a date the calendar
 * lacks, a sequence below 1 or with a leading zero, {@code previous none} for a sequence other than 1 and a hash for
 * the sequence 1 are refused, so one statement has exactly one encoding. A proof is the statement followed by an
 * armored SSH signature over it; {@link #split(byte[])} separates the two.
 *
 * @param object   the object id of the subject
 * @param time     the time of the statement, in whole seconds
 * @param sequence the sequence number, 1 for the first proof
 * @param previous the SHA-256 of the previous proof, 32 bytes; {@code null} for the sequence 1
 */
record AnchorStatement(ObjectId object, Instant time, long sequence, byte @Nullable [] previous) {

    /** The first line of every statement. */
    static final String HEADER = "anchor signed 1";

    /** The value of {@code previous} for the first proof. */
    static final String NONE = "none";

    /** The number of lines of a statement. */
    private static final int LINES = 5;

    /** The length of a SHA-256 hash. */
    static final int HASH_LENGTH = 32;

    /** The key of the line of the object id, with its separating space. */
    private static final String OBJECT_KEY = "object ";

    /** The key of the line of the time, with its separating space. */
    private static final String TIME_KEY = "time ";

    /** The key of the line of the sequence number, with its separating space. */
    private static final String SEQUENCE_KEY = "sequence ";

    /** The key of the line of the previous hash, with its separating space. */
    private static final String PREVIOUS_KEY = "previous ";

    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern NUMBER = Pattern.compile("[1-9]\\d{0,18}");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'")
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Checks the components: the time in whole seconds, a sequence of at least 1, and a previous hash of 32 bytes
     * exactly when the sequence is not 1.
     *
     * @param object   the object id of the subject
     * @param time     the time
     * @param sequence the sequence number
     * @param previous the SHA-256 of the previous proof, or {@code null} for the sequence 1
     * @throws NullPointerException     if {@code object} or {@code time} is {@code null}
     * @throws IllegalArgumentException if a component violates the format
     */
    AnchorStatement {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(time, "time");
        if (time.getNano() != 0) {
            throw new IllegalArgumentException("time must be in whole seconds");
        }
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be at least 1");
        }
        if ((sequence == 1) != (previous == null)) {
            throw new IllegalArgumentException("previous must be absent exactly for the sequence 1");
        }
        if (previous != null) {
            if (previous.length != HASH_LENGTH) {
                throw new IllegalArgumentException("previous must be a SHA-256 hash");
            }
            previous = previous.clone();
        }
    }

    /**
     * Composes the statement that follows a previous proof, or the first one.
     *
     * @param object   the object id of the subject
     * @param time     the time; truncated to whole seconds
     * @param previous the previous statement and the bytes of its proof, or {@code null} for the first proof
     * @return the statement with the next sequence number and the SHA-256 of the previous proof
     * @throws IllegalArgumentException if the sequence of the previous statement is the largest one
     */
    static AnchorStatement next(ObjectId object, Instant time, @Nullable Proof previous) {
        Instant seconds = time.truncatedTo(ChronoUnit.SECONDS);
        if (previous == null) {
            return new AnchorStatement(object, seconds, 1, null);
        }
        long sequence = previous.statement().sequence();
        if (sequence == Long.MAX_VALUE) {
            throw new IllegalArgumentException("sequence exhausted");
        }
        return new AnchorStatement(object, seconds, sequence + 1, sha256(previous.bytes()));
    }

    /**
     * Returns the SHA-256 of the previous proof.
     *
     * @return a copy of the 32 bytes, or {@code null} for the sequence 1
     */
    @Override
    public byte @Nullable [] previous() {
        return previous == null ? null : previous.clone();
    }

    /**
     * Encodes the statement: its five lines in UTF-8, each ended by LF.
     *
     * @return the bytes the signature covers
     */
    byte[] encode() {
        var hex = HexFormat.of();
        String text = HEADER + '\n'
                + OBJECT_KEY + object.name() + '\n'
                + TIME_KEY + TIME_FORMAT.format(LocalDateTime.ofInstant(time, ZoneOffset.UTC)) + '\n'
                + SEQUENCE_KEY + sequence + '\n'
                + PREVIOUS_KEY + (previous == null ? NONE : hex.formatHex(previous)) + '\n';
        return text.getBytes(UTF_8);
    }

    /**
     * Parses a statement strictly.
     *
     * @param bytes the encoded statement
     * @return the statement, or empty for any form {@link #encode()} does not produce
     */
    static Optional<AnchorStatement> parse(byte[] bytes) {
        String text = strict(UTF_8, bytes);
        if (text == null || text.indexOf('\r') >= 0 || !text.endsWith("\n")) {
            return Optional.empty();
        }
        String[] lines = text.substring(0, text.length() - 1).split("\n", -1);
        if (lines.length != LINES || !HEADER.equals(lines[0])) {
            return Optional.empty();
        }
        String object = value(lines[1], OBJECT_KEY, OBJECT_ID);
        String time = value(lines[2], TIME_KEY, TIMESTAMP);
        String sequence = value(lines[3], SEQUENCE_KEY, NUMBER);
        if (object == null || time == null || sequence == null) {
            return Optional.empty();
        }
        Instant instant;
        long number;
        try {
            instant = LocalDateTime.parse(time, TIME_FORMAT).toInstant(ZoneOffset.UTC);
            number = Long.parseLong(sequence);
        } catch (DateTimeException | NumberFormatException e) {
            return Optional.empty();
        }
        if (!lines[4].startsWith(PREVIOUS_KEY)) {
            return Optional.empty();
        }
        String previous = lines[4].substring(PREVIOUS_KEY.length());
        if (number == 1) {
            return NONE.equals(previous)
                    ? Optional.of(new AnchorStatement(ObjectId.fromString(object), instant, number, null))
                    : Optional.empty();
        }
        if (!HASH.matcher(previous).matches()) {
            return Optional.empty();
        }
        return Optional.of(new AnchorStatement(ObjectId.fromString(object), instant, number,
                HexFormat.of().parseHex(previous)));
    }

    /**
     * Splits a proof into its statement and its armored signature.
     * <p>
     * The statement is the bytes up to and including the fifth LF; the rest is the armored signature, which must start
     * with {@value SshSignatures#BEGIN} and be ASCII. Whether the signature decodes and verifies is not checked here.
     *
     * @param proof the bytes of a proof
     * @return the split proof, or empty when the statement does not parse or no armored signature follows it
     */
    static Optional<Proof> split(byte[] proof) {
        int end = 0;
        for (int line = 0; line < LINES; line++) {
            int lineFeed = indexOf(proof, end);
            if (lineFeed < 0) {
                return Optional.empty();
            }
            end = lineFeed + 1;
        }
        byte[] statementBytes = Arrays.copyOfRange(proof, 0, end);
        byte[] signatureBytes = Arrays.copyOfRange(proof, end, proof.length);
        String signature = strict(US_ASCII, signatureBytes);
        if (signature == null || !signature.startsWith(SshSignatures.BEGIN)) {
            return Optional.empty();
        }
        return parse(statementBytes).map(statement -> new Proof(statement, statementBytes, signature, proof));
    }

    /**
     * Returns the SHA-256 of bytes.
     *
     * @param bytes the bytes, a proof
     * @return the 32 bytes of the hash
     */
    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }

    /**
     * Compares every component, the previous hash by content.
     *
     * @param other the object to compare with
     * @return whether {@code other} is an equal statement
     */
    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof AnchorStatement(var otherObject, var otherTime, var otherSequence, var otherPrevious)
                && object.equals(otherObject) && time.equals(otherTime) && sequence == otherSequence
                && Arrays.equals(previous, otherPrevious);
    }

    /**
     * Hashes every component, the previous hash by content.
     *
     * @return the hash
     */
    @Override
    public int hashCode() {
        return Objects.hash(object, time, sequence, Arrays.hashCode(previous));
    }

    /**
     * Renders the statement for diagnostics.
     *
     * @return {@code AnchorStatement[object=..., time=..., sequence=...]}
     */
    @Override
    public String toString() {
        return "AnchorStatement[object=" + object.name() + ", time=" + time + ", sequence=" + sequence + "]";
    }

    private static @Nullable String value(String line, String key, Pattern format) {
        if (!line.startsWith(key)) {
            return null;
        }
        String value = line.substring(key.length());
        return format.matcher(value).matches() ? value : null;
    }

    private static int indexOf(byte[] bytes, int from) {
        for (int index = from; index < bytes.length; index++) {
            if (bytes[index] == '\n') {
                return index;
            }
        }
        return -1;
    }

    /** Decodes bytes in the charset, {@code null} for a byte that is malformed or unmappable in it. */
    private static @Nullable String strict(Charset charset, byte[] bytes) {
        try {
            return charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /**
     * A proof of the provider {@code signed}, split into its parts. The byte arrays are copied on the way in and on
     * the way out.
     *
     * @param statement      the parsed statement
     * @param statementBytes the bytes of the statement, which the signature covers
     * @param signature      the armored SSH signature
     * @param bytes          the bytes of the whole proof, whose SHA-256 the next statement names
     */
    record Proof(AnchorStatement statement, byte[] statementBytes, String signature, byte[] bytes) {

        Proof {
            Objects.requireNonNull(statement, "statement");
            Objects.requireNonNull(signature, "signature");
            statementBytes = statementBytes.clone();
            bytes = bytes.clone();
        }

        /**
         * Returns the bytes of the statement.
         *
         * @return a copy of the bytes
         */
        @Override
        public byte[] statementBytes() {
            return statementBytes.clone();
        }

        /**
         * Returns the bytes of the whole proof.
         *
         * @return a copy of the bytes
         */
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        /**
         * Compares the bytes of the proof, which determine every other component.
         *
         * @param other the object to compare with
         * @return whether {@code other} is a proof with the same bytes
         */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Proof proof && Arrays.equals(bytes, proof.bytes);
        }

        /**
         * Hashes the bytes of the proof.
         *
         * @return the hash
         */
        @Override
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }

        /**
         * Renders the statement only, never the signature.
         *
         * @return {@code Proof[statement=...]}
         */
        @Override
        public String toString() {
            return "Proof[statement=" + statement + "]";
        }
    }
}
