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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;


import org.jspecify.annotations.Nullable;

/**
 * The signing keys a signature verification trusts: the commit-signing keys of {@code signing.allowed-signers}
 * (GDR-STORE-14) or the anchor keys of {@code anchor.signed.allowed-signers} (GDR-STORE-18), as an immutable parse of a
 * file in Git's {@code allowed_signers} format, the format of
 * <a href="https://man.openbsd.org/ssh-keygen.1#ALLOWED_SIGNERS">ssh-keygen(1), ALLOWED SIGNERS</a>.
 * <p>
 * Each line is {@code <principals> [<options>] <key type> <base64 key> [<comment>]}; blank lines and lines starting with
 * {@code #} are skipped, and a field may be quoted with {@code "}. The options are separated by {@code ,} and are
 * <ul>
 * <li>{@code valid-after="<timestamp>"} and {@code valid-before="<timestamp>"}: the key is valid at or after, and at or
 * before, the timestamp, which is {@code YYYYMMDD} (the start of the day) or {@code YYYYMMDDHHMM[SS]}, in UTC when it
 * ends with {@code Z} or {@code z} and in the system time zone otherwise, as {@code ssh-keygen} interprets it (OpenSSH
 * {@code misc.c parse_absolute_time} compares the suffix case-insensitively); a timestamp that
 * names no calendar date, such as {@code 20260230}, is refused;</li>
 * <li>{@code namespaces="<namespace>,..."}: the key is used for a namespace only when the list contains it, for commits
 * {@code git} and for anchor proofs {@code git-document-anchor}; a key without the option is used for every namespace. OpenSSH reads the list as a pattern list, in which a negated entry
 * ({@code !git}) overrides a matching positive one; the store does not evaluate patterns and refuses a list with an
 * entry that contains {@code !}, {@code *} or {@code ?}, so it never accepts a key for commits that OpenSSH would
 * reject. An entry listed twice is read once.</li>
 * </ul>
 * Each option may be given once; a line that repeats {@code valid-after}, {@code valid-before} or {@code namespaces},
 * and a line whose {@code valid-before} is not after its {@code valid-after}, is refused, so no later clause silently
 * widens what an earlier one restricts.
 * The store signs and verifies with Ed25519 only: a line with another key type, a line with {@code cert-authority} or
 * any other option, and a line that cannot be read are refused as {@link StoreOutcome#MALFORMED}, and with it the
 * whole file: a file that also lists an {@code ssh-rsa} key, which OpenSSH would accept, is refused at its line rather
 * than trusted in part, so an administrator learns that the store ignores no line. The detail names the line number
 * and the reason, never a principal, an option value or key text.
 * <p>
 * The file is read bounded: a regular file only, never a FIFO or a device, of at most {@value #MAX_FILE_SIZE} bytes and
 * {@value #MAX_LINES} lines, in valid UTF-8; every other file and a configured value that is no path of the file system
 * end the read as a result, never as an exception.
 * <p>
 * The file is read from the given path, from {@code signing.allowed-signers} or from
 * {@code anchor.signed.allowed-signers} of the configuration, never from the repository whose commits or proofs are
 * verified: an administrator who rewrites the repository cannot make a key of their own trusted.
 *
 * @since 0.1
 */
public final class AllowedSigners {

    /** The largest allowed-signers file read, 64 KiB, the bound of a key file of {@link FileSigningKeySource}. */
    static final int MAX_FILE_SIZE = 64 * 1024;

    /** The most lines an allowed-signers file may have. */
    static final int MAX_LINES = 1024;

    private static final String SIGNING_KEY = "signing.allowed-signers";
    private static final String ANCHOR_KEY = "anchor.signed.allowed-signers";

    /** The detail, after the configuration key, of a file that cannot be read; it names no path. */
    static final String NOT_READABLE = ": not readable";

    /** The detail, after the configuration key, of a path that is no regular file, for example a FIFO. */
    static final String NOT_REGULAR = ": not a regular file";

    /** The detail, after the configuration key, of a file above {@link #MAX_FILE_SIZE} or {@link #MAX_LINES}. */
    static final String TOO_LARGE = ": too large";

    /** The detail, after the configuration key, of a file that is not valid UTF-8. */
    static final String NOT_UTF_8 = ": not UTF-8";

    /** The detail, after the configuration key, of a value that is no path, for example one with a NUL character. */
    static final String NOT_A_PATH = ": not a path";

    /** The detail of a configuration without {@code signing.allowed-signers}. */
    static final String MISSING = SIGNING_KEY + " is missing";

    /** The detail of a file that cannot be read; it names no path. */
    static final String UNREADABLE = SIGNING_KEY + NOT_READABLE;

    /** The detail of a configuration without {@code anchor.signed.allowed-signers}. */
    static final String ANCHOR_MISSING = ANCHOR_KEY + " is missing";

    /** The detail of an anchor allowed-signers file that cannot be read; it names no path. */
    static final String ANCHOR_UNREADABLE = ANCHOR_KEY + NOT_READABLE;

    private static final Pattern DATE = Pattern.compile("\\d{8}");
    private static final Pattern DATE_TIME = Pattern.compile("\\d{12}(\\d{2})?");
    private static final String VALID_AFTER = "valid-after=";
    private static final String VALID_BEFORE = "valid-before=";
    private static final String NAMESPACES = "namespaces=";
    private static final String TIMESTAMP_MALFORMED = "timestamp malformed";
    private static final String OPTION_REPEATED = "option repeated";
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[!*?]");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("uuuuMMdd")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("uuuuMMddHHmm")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter SECOND = DateTimeFormatter.ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);

    private final List<Entry> entries;

    private AllowedSigners(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Reads an allowed-signers file.
     *
     * @param file the file
     * @return {@link StoreOutcome#OK} with the allowed signers; {@link StoreOutcome#MALFORMED} with
     *         {@code line <n>: <reason>} for a line that is refused or with {@code signing.allowed-signers: not UTF-8}
     *         for content that is not valid UTF-8; {@link StoreOutcome#REFUSED} with
     *         {@code signing.allowed-signers: not a regular file} for a directory, a FIFO or a device, or with
     *         {@code signing.allowed-signers: too large} for a file above {@value #MAX_FILE_SIZE} bytes or
     *         {@value #MAX_LINES} lines; {@link StoreOutcome#UNAVAILABLE} with {@value #UNREADABLE} for a file that
     *         does not exist or cannot be read; no detail names the path
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static StoreResult<AllowedSigners> read(Path file) {
        return read(file, SIGNING_KEY);
    }

    private static StoreResult<AllowedSigners> read(Path file, String key) {
        Objects.requireNonNull(file, "file");
        byte[] content;
        try {
            if (!Files.isRegularFile(file)) {
                // a directory, a FIFO or a device is no allowed-signers file; a FIFO would block the read
                return Files.exists(file) ? StoreResult.of(StoreOutcome.REFUSED, key + NOT_REGULAR)
                        : StoreResult.of(StoreOutcome.UNAVAILABLE, key + NOT_READABLE);
            }
            try (InputStream in = Files.newInputStream(file)) {
                content = in.readNBytes(MAX_FILE_SIZE + 1);
            }
        } catch (IOException | SecurityException e) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, key + NOT_READABLE);
        }
        if (content.length > MAX_FILE_SIZE) {
            return StoreResult.of(StoreOutcome.REFUSED, key + TOO_LARGE);
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            return StoreResult.of(StoreOutcome.MALFORMED, key + NOT_UTF_8);
        }
        List<String> lines = text.lines().toList();
        if (lines.size() > MAX_LINES) {
            return StoreResult.of(StoreOutcome.REFUSED, key + TOO_LARGE);
        }
        return parse(lines);
    }

    /**
     * The path a configuration key names, or the refusal of a value that is no path of the file system, for example one
     * that holds a NUL character.
     */
    private static StoreResult<AllowedSigners> read(String file, String key) {
        Path path;
        try {
            path = Path.of(file);
        } catch (InvalidPathException e) {
            return StoreResult.of(StoreOutcome.MALFORMED, key + NOT_A_PATH);
        }
        return read(path, key);
    }

    /**
     * Reads the allowed-signers file named by {@code signing.allowed-signers}.
     *
     * @param configuration the configuration of the store
     * @return the results of {@link #read(Path)}, or {@link StoreOutcome#MALFORMED} with the detail
     *         {@value #MISSING} when the key is absent, or with {@code signing.allowed-signers: not a path} for a
     *         value that is no path of the file system, for example one with a NUL character
     * @throws NullPointerException if {@code configuration} is {@code null}
     */
    public static StoreResult<AllowedSigners> from(StoreConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        @Nullable String file = configuration.signing().allowedSigners();
        if (file == null) {
            return StoreResult.of(StoreOutcome.MALFORMED, MISSING);
        }
        return read(file, SIGNING_KEY);
    }

    /**
     * Reads the allowed-signers file of the anchor keys named by {@code anchor.signed.allowed-signers}, against which
     * the provider {@code signed} verifies its proofs (GDR-STORE-18).
     *
     * @param configuration the configuration of the store
     * @return the results of {@link #read(Path)} with the details naming {@code anchor.signed.allowed-signers}:
     *         {@link StoreOutcome#OK} with the allowed signers; {@link StoreOutcome#MALFORMED} with the detail
     *         {@value #ANCHOR_MISSING} when the key is absent, with {@code anchor.signed.allowed-signers: not a path}
     *         for a value that is no path, or with {@code line <n>: <reason>} for a line that is refused;
     *         {@link StoreOutcome#UNAVAILABLE} with {@value #ANCHOR_UNREADABLE} for a file that cannot be read,
     *         without naming its path
     * @throws NullPointerException if {@code configuration} is {@code null}
     */
    public static StoreResult<AllowedSigners> fromAnchor(StoreConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        @Nullable String file = configuration.anchor().signed().allowedSigners();
        if (file == null) {
            return StoreResult.of(StoreOutcome.MALFORMED, ANCHOR_MISSING);
        }
        return read(file, ANCHOR_KEY);
    }

    /**
     * Returns the number of keys listed.
     *
     * @return the number of entries, one per line that lists a key
     */
    public int size() {
        return entries.size();
    }

    /**
     * Checks a commit-signing key at a time: the check of {@link #check(byte[], Instant, String)} in the namespace
     * {@value SshSignatures#NAMESPACE_GIT}.
     *
     * @param publicKeyBlob the SSH wire form of the public key that made the signature
     * @param time          the committer time of the signed commit
     * @return {@link Match#VALID} when an entry for commits lists the key with a validity that includes the time,
     *         {@link Match#OUTSIDE_VALIDITY} when such entries list the key but none is valid at the time, and
     *         {@link Match#UNKNOWN_KEY} otherwise
     */
    Match check(byte[] publicKeyBlob, Instant time) {
        return check(publicKeyBlob, time, SshSignatures.NAMESPACE_GIT);
    }

    /**
     * Checks a signing key at a time for a namespace. An entry is used for the namespace when it has no
     * {@code namespaces} option or its list contains the namespace.
     *
     * @param publicKeyBlob the SSH wire form of the public key that made the signature
     * @param time          the time the signature claims, the committer time of a commit or the time of a statement
     * @param namespace     the namespace of the signature, {@value SshSignatures#NAMESPACE_GIT} for commits
     * @return {@link Match#VALID} when an entry for the namespace lists the key with a validity that includes the
     *         time, {@link Match#OUTSIDE_VALIDITY} when such entries list the key but none is valid at the time, and
     *         {@link Match#UNKNOWN_KEY} otherwise
     */
    Match check(byte[] publicKeyBlob, Instant time, String namespace) {
        Optional<byte[]> key = SshSignatures.ed25519Key(publicKeyBlob);
        if (key.isEmpty()) {
            return Match.UNKNOWN_KEY;
        }
        boolean listed = false;
        for (Entry entry : entries) {
            if (entry.usedFor(namespace) && Arrays.equals(entry.key(), key.get())) {
                listed = true;
                if (entry.validAt(time)) {
                    return Match.VALID;
                }
            }
        }
        return listed ? Match.OUTSIDE_VALIDITY : Match.UNKNOWN_KEY;
    }

    @Override
    public String toString() {
        return "AllowedSigners[entries=" + entries.size() + "]";
    }

    /** The result of {@link #check}. */
    enum Match {
        /** A listed key, valid at the time. */
        VALID,
        /** A key not listed for the namespace. */
        UNKNOWN_KEY,
        /** A key listed for the namespace, but not valid at the time. */
        OUTSIDE_VALIDITY
    }

    private static StoreResult<AllowedSigners> parse(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            var parsed = parseLine(line);
            if (parsed instanceof Line.Refused(String reason)) {
                return StoreResult.of(StoreOutcome.MALFORMED, "line " + (index + 1) + ": " + reason);
            }
            entries.add(((Line.Parsed) parsed).entry());
        }
        return StoreResult.ok(new AllowedSigners(entries));
    }

    private static Line parseLine(String line) {
        List<String> fields = fields(line);
        if (fields == null) {
            return new Line.Refused("quote not closed");
        }
        if (fields.size() < 3) {
            return new Line.Refused("key missing");
        }
        int keyType = 1;
        @Nullable String options = null;
        if (!isKeyType(fields.get(1))) {
            options = fields.get(1);
            keyType = 2;
        }
        if (fields.size() <= keyType + 1) {
            return new Line.Refused("key missing");
        }
        if (!SshSignatures.KEY_TYPE.equals(fields.get(keyType))) {
            return new Line.Refused("key type not supported");
        }
        if (!(SshSignatures.parsePublicKeyLine(fields.get(keyType) + " " + fields.get(keyType + 1))
                instanceof SshSignatures.AcceptedKey(var key))) {
            return new Line.Refused(SshSignatures.KEY_MALFORMED);
        }
        return options == null ? new Line.Parsed(new Entry(key, null, null, null))
                : parseOptions(options, key);
    }

    /** Whether a field names a key type of the format, so it is not an options field. */
    private static boolean isKeyType(String field) {
        return field.startsWith("ssh-") || field.startsWith("ecdsa-") || field.startsWith("sk-");
    }

    private static Line parseOptions(String options, byte[] key) {
        List<String> parts = split(options, ',');
        if (parts == null) {
            return new Line.Refused("quote not closed");
        }
        var read = new Options();
        for (String option : parts) {
            @Nullable String refusal = read.apply(option);
            if (refusal != null) {
                return new Line.Refused(refusal);
            }
        }
        @Nullable String inconsistent = read.complete();
        if (inconsistent != null) {
            return new Line.Refused(inconsistent);
        }
        return new Line.Parsed(new Entry(key, read.validAfter, read.validBefore, read.namespaces));
    }

    /**
     * The value of {@code namespaces}: its entries, each once.
     *
     * @return the namespaces, or {@code null} when an entry is a pattern, which the store does not evaluate
     */
    private static @Nullable Set<String> namespaces(String value) {
        Set<String> names = new HashSet<>();
        for (String name : value.split(",")) {
            if (NAMESPACE_PATTERN.matcher(name).find()) {
                return null;
            }
            names.add(name);
        }
        return Set.copyOf(names);
    }

    /** The options of one line, read one at a time. */
    private static final class Options {

        private @Nullable Instant validAfter;
        private @Nullable Instant validBefore;
        private @Nullable Set<String> namespaces;

        /**
         * A timestamp of {@code valid-after} or {@code valid-before}, resolved strictly, so that a date the calendar
         * lacks is malformed.
         *
         * @return the instant, or {@code null} for a malformed timestamp
         */
        private static @Nullable Instant timestamp(String value) {
            boolean utc = value.endsWith("Z") || value.endsWith("z");
            String digits = utc ? value.substring(0, value.length() - 1) : value;
            ZoneId zone = utc ? ZoneOffset.UTC : ZoneId.systemDefault();
            try {
                if (DATE.matcher(digits).matches()) {
                    return LocalDate.parse(digits, DAY).atStartOfDay(zone).toInstant();
                }
                if (DATE_TIME.matcher(digits).matches()) {
                    var format = digits.length() == 12 ? MINUTE : SECOND;
                    return LocalDateTime.parse(digits, format).atZone(zone).toInstant();
                }
            } catch (DateTimeException e) {
                return null;
            }
            return null;
        }

        private static String unquote(String value) {
            return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                    ? value.substring(1, value.length() - 1)
                    : value;
        }

        /**
         * Reads one option.
         *
         * @return {@code null} when the option is read, otherwise the reason of the refusal
         */
        @Nullable
        String apply(String option) {
            String lower = option.toLowerCase(Locale.ROOT);
            if ("cert-authority".equals(lower)) {
                return "cert-authority not supported";
            }
            if (lower.startsWith(VALID_AFTER)) {
                return readValidAfter(option.substring(VALID_AFTER.length()));
            }
            if (lower.startsWith(VALID_BEFORE)) {
                return readValidBefore(option.substring(VALID_BEFORE.length()));
            }
            if (lower.startsWith(NAMESPACES)) {
                return readNamespaces(option.substring(NAMESPACES.length()));
            }
            return "option not supported";
        }

        private @Nullable String readValidAfter(String value) {
            if (validAfter != null) {
                return OPTION_REPEATED;
            }
            validAfter = timestamp(unquote(value));
            return validAfter == null ? TIMESTAMP_MALFORMED : null;
        }

        private @Nullable String readValidBefore(String value) {
            if (validBefore != null) {
                return OPTION_REPEATED;
            }
            validBefore = timestamp(unquote(value));
            return validBefore == null ? TIMESTAMP_MALFORMED : null;
        }

        private @Nullable String readNamespaces(String value) {
            if (namespaces != null) {
                return OPTION_REPEATED;
            }
            namespaces = AllowedSigners.namespaces(unquote(value));
            return namespaces == null ? "namespace pattern not supported" : null;
        }

        /**
         * Checks the options together once every option was read.
         *
         * @return {@code null} when they are consistent, otherwise the reason of the refusal
         */
        @Nullable
        String complete() {
            if (validAfter != null && validBefore != null && !validBefore.isAfter(validAfter)) {
                return "validity interval empty";
            }
            return null;
        }
    }

    /** The whitespace-separated fields of a line, a quoted part kept in one field; {@code null} for an open quote. */
    private static @Nullable List<String> fields(String line) {
        List<String> fields = new ArrayList<>();
        var field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (c == '"') {
                quoted = !quoted;
            }
            if (!quoted && Character.isWhitespace(c)) {
                if (!field.isEmpty()) {
                    fields.add(field.toString());
                    field.setLength(0);
                }
            } else {
                field.append(c);
            }
        }
        if (quoted) {
            return null;
        }
        if (!field.isEmpty()) {
            fields.add(field.toString());
        }
        return fields;
    }

    /** The parts of a field separated by a separator outside quotes; {@code null} for an open quote. */
    private static @Nullable List<String> split(String value, char separator) {
        List<String> parts = new ArrayList<>();
        var part = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '"') {
                quoted = !quoted;
            }
            if (!quoted && c == separator) {
                parts.add(part.toString());
                part.setLength(0);
            } else {
                part.append(c);
            }
        }
        if (quoted) {
            return null;
        }
        parts.add(part.toString());
        return parts;
    }

    /** The parse of one line. */
    private sealed interface Line {

        /** A line that lists a key. */
        record Parsed(Entry entry) implements Line {
        }

        /** A refused line, with a reason that carries no text of the line. */
        record Refused(String reason) implements Line {
        }
    }

    /**
     * One listed key.
     *
     * @param key         the 32 bytes of the Ed25519 public key
     * @param validAfter  the start of the validity; {@code null} for none
     * @param validBefore the end of the validity; {@code null} for none
     * @param namespaces  the namespaces the key may sign in; {@code null} for every namespace
     */
    private record Entry(byte[] key, @Nullable Instant validAfter, @Nullable Instant validBefore,
    @Nullable Set<String> namespaces) {

        boolean usedFor(String namespace) {
            return namespaces == null || namespaces.contains(namespace);
        }

        boolean validAt(Instant time) {
            return (validAfter == null || !time.isBefore(validAfter))
                    && (validBefore == null || !time.isAfter(validBefore));
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Entry(var otherKey, var otherAfter, var otherBefore, var otherNamespaces)
                    && Arrays.equals(key, otherKey) && Objects.equals(validAfter, otherAfter)
                    && Objects.equals(validBefore, otherBefore) && Objects.equals(namespaces, otherNamespaces);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(key), validAfter, validBefore, namespaces);
        }

        @Override
        public String toString() {
            return "Entry[validAfter=" + validAfter + ", validBefore=" + validBefore + ", namespaces=" + namespaces
                    + "]";
        }
    }
}
