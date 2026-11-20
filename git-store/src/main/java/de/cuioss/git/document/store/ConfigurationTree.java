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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A typed reader over an already-parsed configuration tree: nested maps as a YAML or JSON parser delivers them, keys
 * nested by dot segment ({@code anchor.signed.key} is the key {@code key} of the map {@code signed} of the map
 * {@code anchor}).
 * <p>
 * Every lookup returns a {@link StoreResult}: a success carrying the typed value, a success without value when the key
 * is omitted, or {@link StoreOutcome#MALFORMED} with a detail naming the dotted key, never the value (GDR-NFR-3). A key
 * present with a {@code null} value is malformed, not absent.
 * <p>
 * An integer is a {@link Byte}, {@link Short}, {@link Integer}, {@link Long}, {@link BigInteger} or
 * {@link BigDecimal} whose value is integral and fits an {@code int}, converted exactly: {@code new BigDecimal("3.00")}
 * reads as 3, {@code new BigDecimal("3.01")} and a value beyond the range of {@code int} are malformed. A
 * {@link Float} or {@link Double} is malformed, because binary floating point is no exact representation of an
 * integer. Booleans are {@link Boolean}s; durations and periods are ISO 8601 strings parsed with
 * {@link Duration#parse(CharSequence)} and {@link Period#parse(CharSequence)}, and must be positive; a text whose
 * value overflows on parsing is malformed.
 */
final class ConfigurationTree {

    /** The separator of the segments of a dotted key. */
    private static final String SEPARATOR = "\\.";

    /** The root map of this tree. */
    private final Map<?, ?> root;

    /** The prefix prepended to every key named in a detail, e.g. {@code repositories[2].}; empty at the root. */
    private final String prefix;

    /**
     * Creates a reader over the root of a configuration tree.
     *
     * @param root the root map
     */
    ConfigurationTree(Map<String, ?> root) {
        this(root, "");
    }

    private ConfigurationTree(Map<?, ?> root, String prefix) {
        this.root = Objects.requireNonNull(root, "root");
        this.prefix = prefix;
    }

    /** Returns the name of a key as a detail names it: the dotted key with the prefix of this tree. */
    private String name(String key) {
        return prefix + key;
    }

    /**
     * Creates the refusal for a missing key.
     *
     * @param key the dotted key relative to this tree
     * @param <V> the value type
     * @return a {@link StoreOutcome#MALFORMED} result naming the key
     */
    <V> StoreResult<V> missing(String key) {
        return StoreResult.of(StoreOutcome.MALFORMED, name(key) + " is missing");
    }

    /**
     * Creates the refusal for a malformed value.
     *
     * @param key the dotted key relative to this tree
     * @param <V> the value type
     * @return a {@link StoreOutcome#MALFORMED} result naming the key, never the value
     */
    <V> StoreResult<V> malformed(String key) {
        return StoreResult.of(StoreOutcome.MALFORMED, name(key) + " is malformed");
    }

    /**
     * Reads an integer.
     * <p>
     * Every accepted type is converted exactly ({@link Math#toIntExact(long)}, {@link BigInteger#intValueExact()},
     * {@link BigDecimal#intValueExact()}); a lost fraction or a value beyond the range of {@code int} is malformed.
     *
     * @param key the dotted key
     * @return the integer, a success without value when absent, or {@link StoreOutcome#MALFORMED} for another type, a
     *         value with a fraction or a value beyond the range of {@code int}
     */
    StoreResult<Integer> integer(String key) {
        var raw = find(key);
        if (!raw.isOk() || raw.value() == null) {
            return retype(raw);
        }
        Object value = raw.value();
        try {
            return switch (value) {
                case Integer exact -> StoreResult.ok(exact);
                case Short exact -> StoreResult.ok(exact.intValue());
                case Byte exact -> StoreResult.ok(exact.intValue());
                case Long wide -> StoreResult.ok(Math.toIntExact(wide));
                case BigInteger big -> StoreResult.ok(big.intValueExact());
                case BigDecimal decimal -> StoreResult.ok(decimal.intValueExact());
                default -> malformed(key);
            };
        } catch (ArithmeticException e) {
            return malformed(key);
        }
    }

    /**
     * Reads a boolean.
     *
     * @param key the dotted key
     * @return the boolean, a success without value when absent, or {@link StoreOutcome#MALFORMED}
     */
    StoreResult<Boolean> bool(String key) {
        var raw = find(key);
        if (!raw.isOk() || raw.value() == null) {
            return retype(raw);
        }
        return raw.value() instanceof Boolean value ? StoreResult.ok(value) : malformed(key);
    }

    /**
     * Reads a string that is not blank.
     *
     * @param key the dotted key
     * @return the string, a success without value when absent, or {@link StoreOutcome#MALFORMED} for another type or a
     *         blank string
     */
    StoreResult<String> string(String key) {
        var raw = find(key);
        if (!raw.isOk() || raw.value() == null) {
            return retype(raw);
        }
        return raw.value() instanceof String value && !value.isBlank() ? StoreResult.ok(value) : malformed(key);
    }

    /**
     * Reads a positive ISO 8601 duration.
     *
     * @param key the dotted key
     * @return the duration, a success without value when absent, or {@link StoreOutcome#MALFORMED} for another type,
     *         an unparsable text, zero or a negative duration
     */
    StoreResult<Duration> duration(String key) {
        var text = string(key);
        if (!text.isOk() || text.value() == null) {
            return retype(text);
        }
        try {
            var duration = Duration.parse(text.value());
            return duration.isZero() || duration.isNegative() ? malformed(key) : StoreResult.ok(duration);
        } catch (DateTimeParseException e) {
            // Duration.parse wraps the ArithmeticException of an overflowing value into a DateTimeParseException
            // (checked in the source of OpenJDK 25.0.3, java.time.Duration#parse and #parseNumber), so this catch
            // covers an oversized value as well
            return malformed(key);
        }
    }

    /**
     * Reads a positive ISO 8601 period.
     *
     * @param key the dotted key
     * @return the period, a success without value when absent, or {@link StoreOutcome#MALFORMED} for another type, an
     *         unparsable text, a value that overflows on parsing, zero or a period with a negative part
     */
    StoreResult<Period> period(String key) {
        var text = string(key);
        if (!text.isOk() || text.value() == null) {
            return retype(text);
        }
        try {
            var period = Period.parse(text.value());
            boolean negativePart = period.getYears() < 0 || period.getMonths() < 0 || period.getDays() < 0;
            return period.isZero() || negativePart ? malformed(key) : StoreResult.ok(period);
        } catch (DateTimeParseException | ArithmeticException e) {
            // Period.parse converts weeks to days with Math.multiplyExact and Math.addExact outside its own catch,
            // so an overflow such as P306783379W leaves it as an ArithmeticException (OpenJDK 25.0.3,
            // java.time.Period#parse)
            return malformed(key);
        }
    }

    /**
     * Reads a list of sections, each a map, as trees whose keys are named {@code key[index].}.
     *
     * @param key the dotted key
     * @return the sections in list order, a success without value when absent, or {@link StoreOutcome#MALFORMED} when
     *         the value is no list or an element is no map
     */
    StoreResult<List<ConfigurationTree>> sections(String key) {
        var raw = find(key);
        if (!raw.isOk() || raw.value() == null) {
            return retype(raw);
        }
        if (!(raw.value() instanceof List<?> list)) {
            return malformed(key);
        }
        List<ConfigurationTree> sections = new ArrayList<>(list.size());
        int index = 0;
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> section)) {
                return malformed(key + "[" + index + "]");
            }
            sections.add(new ConfigurationTree(section, name(key) + "[" + index + "]."));
            index++;
        }
        return StoreResult.ok(sections);
    }

    /**
     * Finds the raw value of a dotted key.
     * <p>
     * Only an omitted key is absent, so that its default applies; a key that is present with a {@code null} value, as
     * a leaf or as a section on the way, is malformed.
     *
     * @param key the dotted key
     * @return the raw value, a success without value when absent, or {@link StoreOutcome#MALFORMED} naming the dotted
     *         key when a segment on the way is no map or a present key holds {@code null}
     */
    private StoreResult<Object> find(String key) {
        String[] segments = key.split(SEPARATOR);
        Map<?, ?> current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            if (!current.containsKey(segments[index])) {
                return absent();
            }
            if (!(current.get(segments[index]) instanceof Map<?, ?> section)) {
                return malformed(key);
            }
            current = section;
        }
        String last = segments[segments.length - 1];
        if (!current.containsKey(last)) {
            return absent();
        }
        Object value = current.get(last);
        return value == null ? malformed(key) : StoreResult.ok(value);
    }

    /** Returns the success without value of an absent key. */
    private static <V> StoreResult<V> absent() {
        return new StoreResult<>(StoreOutcome.OK, null, null);
    }

    /**
     * Carries an absent value or a refusal over to another value type.
     *
     * @param result a success without value or a non-success
     * @param <V>    the target value type
     * @return the same outcome and detail, without value
     */
    static <V> StoreResult<V> retype(StoreResult<?> result) {
        return new StoreResult<>(result.outcome(), null, result.detail());
    }
}
