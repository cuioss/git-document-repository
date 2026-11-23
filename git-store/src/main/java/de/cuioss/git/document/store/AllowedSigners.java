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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;


import org.jspecify.annotations.Nullable;

/**
 * The commit-signing keys a signature verification trusts (GDR-STORE-14): an immutable parse of a file in Git's
 * {@code allowed_signers} format, the format of
 * <a href="https://man.openbsd.org/ssh-keygen.1#ALLOWED_SIGNERS">ssh-keygen(1), ALLOWED SIGNERS</a>.
 * <p>
 * Each line is {@code <principals> [<options>] <key type> <base64 key> [<comment>]}; blank lines and lines starting with
 * {@code #} are skipped, and a field may be quoted with {@code "}. The options are separated by {@code ,} and are
 * <ul>
 * <li>{@code valid-after="<timestamp>"} and {@code valid-before="<timestamp>"}: the key is valid at or after, and at or
 * before, the timestamp, which is {@code YYYYMMDD} (the start of the day) or {@code YYYYMMDDHHMM[SS]}, in UTC when it
 * ends with {@code Z} and in the system time zone otherwise, as {@code ssh-keygen} interprets it;</li>
 * <li>{@code namespaces="<namespace>,..."}: the key is used for commits only when the list contains {@code git}; a key
 * without the option is used for every namespace.</li>
 * </ul>
 * The store signs and verifies with Ed25519 only: a line with another key type, a line with {@code cert-authority} or
 * any other option, and a line that cannot be read are refused as {@link StoreOutcome#MALFORMED}. The detail names the
 * line number and the reason, never a principal, an option value or key text.
 * <p>
 * The file is read from the given path or from {@code signing.allowed-signers} of the configuration, never from the
 * repository whose commits are verified: an administrator who rewrites the repository cannot make a key of their own
 * trusted.
 *
 * @since 0.1
 */
public final class AllowedSigners {

    /** The detail of a configuration without {@code signing.allowed-signers}. */
    static final String MISSING = "signing.allowed-signers is missing";

    /** The detail of a file that cannot be read; it names no path. */
    static final String UNREADABLE = "signing.allowed-signers: not readable";

    private static final Pattern DATE = Pattern.compile("\\d{8}");
    private static final Pattern DATE_TIME = Pattern.compile("\\d{12}(\\d{2})?");
    private static final String VALID_AFTER = "valid-after=";
    private static final String VALID_BEFORE = "valid-before=";
    private static final String NAMESPACES = "namespaces=";

    private final List<Entry> entries;

    private AllowedSigners(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Reads an allowed-signers file.
     *
     * @param file the file
     * @return {@link StoreOutcome#OK} with the allowed signers; {@link StoreOutcome#MALFORMED} with
     *         {@code line <n>: <reason>} for a line that is refused; {@link StoreOutcome#UNAVAILABLE} for a file that
     *         cannot be read, without naming its path
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public static StoreResult<AllowedSigners> read(Path file) {
        Objects.requireNonNull(file, "file");
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException | SecurityException e) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, UNREADABLE);
        }
        return parse(lines);
    }

    /**
     * Reads the allowed-signers file named by {@code signing.allowed-signers}.
     *
     * @param configuration the configuration of the store
     * @return the results of {@link #read(Path)}, or {@link StoreOutcome#MALFORMED} with the detail
     *         {@value #MISSING} when the key is absent
     * @throws NullPointerException if {@code configuration} is {@code null}
     */
    public static StoreResult<AllowedSigners> from(StoreConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        @Nullable String file = configuration.signing().allowedSigners();
        if (file == null) {
            return StoreResult.of(StoreOutcome.MALFORMED, MISSING);
        }
        return read(Path.of(file));
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
     * Checks a signing key at a time.
     *
     * @param publicKeyBlob the SSH wire form of the public key that made the signature
     * @param time          the committer time of the signed commit
     * @return {@link Match#VALID} when an entry for commits lists the key with a validity that includes the time,
     *         {@link Match#OUTSIDE_VALIDITY} when such entries list the key but none is valid at the time, and
     *         {@link Match#UNKNOWN_KEY} otherwise
     */
    Match check(byte[] publicKeyBlob, Instant time) {
        Optional<byte[]> key = SshSignatures.ed25519Key(publicKeyBlob);
        if (key.isEmpty()) {
            return Match.UNKNOWN_KEY;
        }
        boolean listed = false;
        for (Entry entry : entries) {
            if (entry.forCommits() && Arrays.equals(entry.key(), key.get())) {
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
        /** A key not listed for commits. */
        UNKNOWN_KEY,
        /** A key listed for commits, but not valid at the time. */
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
                instanceof SshSignatures.KeyLine.Accepted accepted)) {
            return new Line.Refused("key malformed");
        }
        return options == null ? new Line.Parsed(new Entry(accepted.key(), null, null, null))
                : parseOptions(options, accepted.key());
    }

    /** Whether a field names a key type of the format, so it is not an options field. */
    private static boolean isKeyType(String field) {
        return field.startsWith("ssh-") || field.startsWith("ecdsa-") || field.startsWith("sk-");
    }

    private static Line parseOptions(String options, byte[] key) {
        @Nullable Instant validAfter = null;
        @Nullable Instant validBefore = null;
        @Nullable Set<String> namespaces = null;
        List<String> parts = split(options, ',');
        if (parts == null) {
            return new Line.Refused("quote not closed");
        }
        for (String option : parts) {
            String lower = option.toLowerCase(Locale.ROOT);
            if ("cert-authority".equals(lower)) {
                return new Line.Refused("cert-authority not supported");
            }
            if (lower.startsWith(VALID_AFTER) || lower.startsWith(VALID_BEFORE)) {
                boolean after = lower.startsWith(VALID_AFTER);
                @Nullable Instant time = timestamp(unquote(option.substring(
                        (after ? VALID_AFTER : VALID_BEFORE).length())));
                if (time == null) {
                    return new Line.Refused("timestamp malformed");
                }
                if (after) {
                    validAfter = time;
                } else {
                    validBefore = time;
                }
            } else if (lower.startsWith(NAMESPACES)) {
                namespaces = Set.of(unquote(option.substring(NAMESPACES.length())).split(","));
            } else {
                return new Line.Refused("option not supported");
            }
        }
        return new Line.Parsed(new Entry(key, validAfter, validBefore, namespaces));
    }

    /**
     * A timestamp of {@code valid-after} or {@code valid-before}.
     *
     * @return the instant, or {@code null} for a malformed timestamp
     */
    private static @Nullable Instant timestamp(String value) {
        boolean utc = value.endsWith("Z") || value.endsWith("z");
        String digits = utc ? value.substring(0, value.length() - 1) : value;
        ZoneId zone = utc ? ZoneOffset.UTC : ZoneId.systemDefault();
        try {
            if (DATE.matcher(digits).matches()) {
                return LocalDate.parse(digits, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant();
            }
            if (DATE_TIME.matcher(digits).matches()) {
                String pattern = digits.length() == 12 ? "uuuuMMddHHmm" : "uuuuMMddHHmmss";
                return LocalDateTime.parse(digits, DateTimeFormatter.ofPattern(pattern)).atZone(zone).toInstant();
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

        boolean forCommits() {
            return namespaces == null || namespaces.contains(SshSignatures.NAMESPACE_GIT);
        }

        boolean validAt(Instant time) {
            return (validAfter == null || !time.isBefore(validAfter))
                    && (validBefore == null || !time.isAfter(validBefore));
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof Entry entry && Arrays.equals(key, entry.key)
                    && Objects.equals(validAfter, entry.validAfter) && Objects.equals(validBefore, entry.validBefore)
                    && Objects.equals(namespaces, entry.namespaces);
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
