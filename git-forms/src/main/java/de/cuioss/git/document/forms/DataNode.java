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
package de.cuioss.git.document.forms;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import dev.harrel.jsonschema.JsonNode;
import dev.harrel.jsonschema.JsonNodeFactory;
import dev.harrel.jsonschema.SimpleType;
import org.jspecify.annotations.Nullable;

/**
 * A node of the JSON data model of ADR-0008, as {@code dev.harrel:json-schema} 1.9.1 reads it through its public
 * interface {@link JsonNode}: so the validator needs no JSON provider and no further artifact.
 * <p>
 * The value is one of the types {@link YamlReader} produces: a {@link Map} with {@link String} keys, a {@link List}, a
 * {@link String}, a {@link Long}, {@link Integer} or {@link BigInteger}, a {@link BigDecimal}, a {@link Boolean} or
 * {@code null}. A number without fraction is an integer, as in the providers of the validator, if it has at most
 * {@value #MAX_INTEGER_DIGITS} digits; a longer one is of the type {@code number}.
 * <p>
 * A number is held in its canonical form: the unscaled value stripped of its trailing zeros, as far as the scale stays
 * within the range of {@code int}. Equal numbers have the same canonical form whatever their notation ({@code 10},
 * {@code 1e1} and {@code 10.0}), so equality ignores the JSON Pointer and compares numbers by value without ever
 * expanding a number by its exponent.
 * <p>
 * Checked against the source of {@code dev.harrel:json-schema} 1.9.1: because this class is not one of the library's
 * own node types, its keywords {@code const}, {@code enum} and {@code uniqueItems} compare two nodes through
 * {@code JsonNodeUtil.equals}, which reads {@link #asInteger()} of two integers and {@link #asNumber()} of two numbers,
 * and {@code enum} also collects its values in a hash set through {@link #equals(Object)} and {@link #hashCode()}. The
 * digit limit of an integer keeps {@link #asInteger()} cheap, the canonical form of {@link #asNumber()} makes the
 * library's scale-sensitive {@link BigDecimal#equals(Object)} compare by value, and {@code minimum}, {@code maximum} and
 * their exclusive forms compare by {@link BigDecimal#compareTo(BigDecimal)}, which expands nothing. Only
 * {@code multipleOf} is left: the library computes {@link BigDecimal#remainder(BigDecimal)}, whose working precision
 * grows with the difference of the exponents, and no node can bound that. For data read from text it stays bounded
 * because {@link YamlReader} refuses a number of more than {@value YamlReader#MAX_NUMBER_DIGITS} digits or with an
 * exponent beyond {@value YamlReader#MAX_EXPONENT} in magnitude before it is parsed. A {@link BigDecimal} or
 * {@link BigInteger} a caller builds in memory is not bounded: with a large exponent under {@code multipleOf} it can
 * still stall the validation. The library then compares the remainder as a {@code double} with zero
 * ({@code MultipleOfEvaluator} in {@code Evaluators.java} of {@code dev.harrel:json-schema} 1.9.1:
 * {@code remainder(factor).doubleValue() == 0}); a remainder of a magnitude below the smallest positive {@code double}
 * (about {@code 4.9e-324}; below about {@code 1e-308} it already loses precision) underflows to zero, so
 * {@code multipleOf} accepts such a number, for example {@code 1e-400} under {@code "multipleOf": 1}, which the reader
 * still reads. This underflow of the library is accepted and not checked again.
 */
final class DataNode implements JsonNode {

    /** The factory of nodes for schemas and data: {@code create} reads JSON (as YAML) through {@link YamlReader}. */
    static final JsonNodeFactory FACTORY = new Factory();

    /** The most digits a number without fraction may have to be an integer; a longer one is of the type number. */
    static final int MAX_INTEGER_DIGITS = 1000;

    /**
     * The most nested mappings and sequences a value of the data model may hold, the outermost container counting 1
     * and a scalar root counting 0: the one compiled nesting limit of git-forms. {@link YamlReader} applies it to text,
     * {@link #requireDataModel(Object)} to a value in memory.
     */
    static final int MAX_DEPTH = 128;

    /** log10(2), rounded up, so that a digit count derived from a bit length is never too small. */
    private static final double LOG10_OF_2 = 0.30103;

    private final @Nullable Object value;
    /** The node this one is a child of, or {@code null} for a root; its JSON Pointer is the prefix of this one. */
    private final @Nullable DataNode parent;
    /** The encoded reference token of this node in its parent, empty for a root. */
    private final String segment;
    private final @Nullable BigDecimal number;
    private final SimpleType type;
    private @Nullable String pointer;
    private @Nullable List<JsonNode> array;
    private @Nullable Map<String, JsonNode> object;

    private DataNode(@Nullable Object value, @Nullable DataNode parent, String segment) {
        this.value = value;
        this.parent = parent;
        this.segment = segment;
        this.pointer = parent == null ? "" : null;
        this.number = canonicalNumber(value);
        this.type = typeOf(value, number);
    }

    /**
     * Wraps a value of the data model as a root node.
     *
     * @param value the value
     * @return the node, whose JSON Pointer is the empty string
     * @throws IllegalArgumentException if the value or a value inside it is outside the data model, or if it is nested
     *                                  deeper than {@value #MAX_DEPTH} levels
     */
    static DataNode root(@Nullable Object value) {
        requireDataModel(value);
        return new DataNode(value, null, "");
    }

    /**
     * Checks a value and every value inside it against the data model, at the boundary: the validator descends only
     * where a keyword of the schema leads it, so a value it never reaches would otherwise pass unchecked.
     * <p>
     * The value may hold at most {@value #MAX_DEPTH} nested mappings and sequences, the outermost container counting 1
     * and a scalar counting 0. The walk runs over an explicit stack, so a value nested deeper than that, or one that
     * contains itself, is refused instead of overflowing the thread stack. A container shared by several parents, such
     * as {@code x} in {@code List.of(x, x)}, is walked again only when it is reached deeper than every time before:
     * the depth bound applies to its deepest occurrence, and a shared subtree costs at most {@value #MAX_DEPTH} walks
     * of each container instead of one walk per path to it. A refusal names the limit or the type that is outside the
     * data model, never a value or a key (GDR-NFR-3).
     * <p>
     * A value built in memory outside the data model is a programming error of the caller and refused with an
     * exception; data read from a repository is read through {@link YamlReader}, which bounds it the same way and
     * returns a result.
     *
     * @param value the value
     * @throws IllegalArgumentException if the value or a value inside it is outside the data model, or if it is nested
     *                                  deeper than {@value #MAX_DEPTH} levels
     */
    static void requireDataModel(@Nullable Object value) {
        Map<Object, Integer> deepest = new IdentityHashMap<>();
        Deque<Pending> pending = new ArrayDeque<>();
        pending.push(new Pending(value, 0));
        while (!pending.isEmpty()) {
            Pending next = pending.pop();
            Object current = next.value();
            if (current == null) {
                continue;
            }
            if (!isOfDataModel(current)) {
                throw new IllegalArgumentException("not of the JSON data model: " + current.getClass().getName());
            }
            if (current instanceof List<?> || current instanceof Map<?, ?>) {
                int depth = next.depth() + 1;
                if (depth > MAX_DEPTH) {
                    throw new TooDeep();
                }
                Integer seen = deepest.get(current);
                if (seen == null || seen < depth) {
                    deepest.put(current, depth);
                    pushInside(pending, current, depth);
                }
            }
        }
    }

    /**
     * Whether a value is nested deeper than {@value #MAX_DEPTH} levels, by the walk of {@link #requireDataModel}: for
     * data assembled from files that were each read within the bound, such as a document joined with its parts, whose
     * caller returns a result instead of the exception.
     *
     * @param value the value
     * @return whether the value, or a value inside it, is nested deeper than the limit or contains itself
     * @throws IllegalArgumentException if the value or a value inside it is outside the data model for another reason
     */
    static boolean nestedTooDeep(@Nullable Object value) {
        try {
            requireDataModel(value);
            return false;
        } catch (TooDeep e) {
            return true;
        }
    }

    /** Pushes every value inside a container onto the stack of the walk, checking the keys of a mapping. */
    private static void pushInside(Deque<Pending> pending, Object container, int depth) {
        if (container instanceof List<?> list) {
            for (Object item : list) {
                pending.push(new Pending(item, depth));
            }
        } else if (container instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("not of the JSON data model: a key that is not a string");
                }
                pending.push(new Pending(entry.getValue(), depth));
            }
        }
    }

    private static boolean isOfDataModel(Object value) {
        return value instanceof Boolean || value instanceof String || value instanceof Long
                || value instanceof Integer || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof List || value instanceof Map;
    }

    /** The type of a value of the data model; {@code number} is its canonical form, or {@code null} if it is none. */
    private static SimpleType typeOf(@Nullable Object value, @Nullable BigDecimal number) {
        if (number != null) {
            return isInteger(number) ? SimpleType.INTEGER : SimpleType.NUMBER;
        }
        return switch (value) {
            case null -> SimpleType.NULL;
            case Boolean ignored -> SimpleType.BOOLEAN;
            case String ignored -> SimpleType.STRING;
            case List<?> ignored -> SimpleType.ARRAY;
            case Map<?, ?> ignored -> SimpleType.OBJECT;
            default -> throw new IllegalArgumentException("not of the JSON data model: " + value.getClass().getName());
        };
    }

    /**
     * Tells whether a canonical number is an integer: no fraction and at most {@value #MAX_INTEGER_DIGITS} digits. The
     * canonical form has no trailing zeros left to strip, so it has a fraction exactly when its scale is positive; the
     * digits are counted from precision and scale in {@code long}, never by expanding the number.
     */
    private static boolean isInteger(BigDecimal number) {
        return number.scale() <= 0
                && (number.signum() == 0 || (long) number.precision() - number.scale() <= MAX_INTEGER_DIGITS);
    }

    /** The canonical form of a number of the data model, or {@code null} for any other value. */
    private static @Nullable BigDecimal canonicalNumber(@Nullable Object value) {
        return switch (value) {
            case Long integer -> canonical(BigDecimal.valueOf(integer));
            case Integer integer -> canonical(BigDecimal.valueOf(integer));
            case BigInteger integer -> canonical(new BigDecimal(integer));
            case BigDecimal decimal -> canonical(decimal);
            case null, default -> null;
        };
    }

    /**
     * Strips the trailing zeros of a number, as far as its scale stays within the range of {@code int}, so that every
     * notation of a value yields the same form; unlike {@link BigDecimal#stripTrailingZeros()}, which throws when the
     * stripped scale leaves that range ({@code 100e2147483647}). Zeros are removed by powers of ten that double and then
     * halve, so the number of divisions grows with the logarithm of the number of zeros, not with the zeros themselves.
     */
    private static BigDecimal canonical(BigDecimal number) {
        if (number.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigInteger unscaled = number.unscaledValue();
        // 10 = 2 * 5: a number has no more trailing decimal zeros than trailing binary zeros, nor more than its digits
        long digits = (long) ((unscaled.bitLength() - 1) * LOG10_OF_2) + 1;
        long removable = Math.min(Math.min(unscaled.getLowestSetBit(), digits),
                (long) number.scale() - Integer.MIN_VALUE);
        // powers.get(i) is 10^(2^i); together they can remove every zero there is to remove
        List<BigInteger> powers = new ArrayList<>();
        for (long step = 1; step <= removable; step <<= 1) {
            powers.add(powers.isEmpty() ? BigInteger.TEN : powers.getLast().multiply(powers.getLast()));
        }
        long stripped = 0;
        for (int exponent = powers.size() - 1; exponent >= 0; exponent--) {
            long step = 1L << exponent;
            if (stripped + step <= removable) {
                BigInteger[] division = unscaled.divideAndRemainder(powers.get(exponent));
                if (division[1].signum() == 0) {
                    unscaled = division[0];
                    stripped += step;
                }
            }
        }
        return stripped == 0 ? number : new BigDecimal(unscaled, (int) (number.scale() - stripped));
    }

    /**
     * {@inheritDoc}
     * <p>
     * A child shares its parent instead of a copy of the parent's pointer, so building the children of a wide mapping
     * under a long path copies no prefix; the pointer is joined on the first request, from the nearest ancestor whose
     * pointer is known, and kept.
     */
    // every pointer of the chain is kept on its node, so each step needs its own String and a StringBuilder would only
    // be copied out again; java:S1643 does not see that the intermediate values are used
    @SuppressWarnings("java:S1643")
    @Override
    public String getJsonPointer() {
        String known = pointer;
        if (known != null) {
            return known;
        }
        Deque<DataNode> unresolved = new ArrayDeque<>();
        DataNode node = this;
        while (node.pointer == null) {
            unresolved.push(node);
            node = Objects.requireNonNull(node.parent, "a root knows its pointer");
        }
        String prefix = Objects.requireNonNull(node.pointer);
        while (!unresolved.isEmpty()) {
            DataNode child = unresolved.pop();
            prefix = prefix + "/" + child.segment;
            child.pointer = prefix;
        }
        return prefix;
    }

    @Override
    public SimpleType getNodeType() {
        return type;
    }

    @Override
    public boolean asBoolean() {
        return Boolean.TRUE.equals(value);
    }

    @Override
    public String asString() {
        return String.valueOf(value);
    }

    /**
     * {@inheritDoc}
     * <p>
     * An integer has at most {@value #MAX_INTEGER_DIGITS} digits, so its expansion is cheap. Of a number with a
     * fraction the integer part is returned; the library reads this only of integers.
     *
     * @throws ArithmeticException for a number without fraction of more than {@value #MAX_INTEGER_DIGITS} digits held
     *                             with a negative scale, which would have to be expanded by its exponent
     */
    @Override
    public BigInteger asInteger() {
        if (value instanceof BigInteger integer) {
            return integer;
        }
        if (number == null) {
            return BigInteger.ZERO;
        }
        if (type != SimpleType.INTEGER && number.scale() < 0) {
            throw new ArithmeticException("a number of more than " + MAX_INTEGER_DIGITS + " digits is not expanded");
        }
        return number.toBigInteger();
    }

    /**
     * {@inheritDoc}
     * <p>
     * The number is returned in its canonical form, so that {@link BigDecimal#equals(Object)} compares it by value.
     */
    @Override
    public BigDecimal asNumber() {
        return number == null ? BigDecimal.ZERO : number;
    }

    /** {@inheritDoc} A number is printed in its canonical form, never expanded by its exponent. */
    @Override
    public String toPrintableString() {
        return number == null ? JsonNode.super.toPrintableString() : number.toString();
    }

    @Override
    public List<JsonNode> asArray() {
        List<JsonNode> items = array;
        if (items == null) {
            List<JsonNode> built = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (int index = 0; index < list.size(); index++) {
                    built.add(new DataNode(list.get(index), this, Integer.toString(index)));
                }
            }
            items = Collections.unmodifiableList(built);
            array = items;
        }
        return items;
    }

    @Override
    public Map<String, JsonNode> asObject() {
        Map<String, JsonNode> entries = object;
        if (entries == null) {
            Map<String, JsonNode> built = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    // every key is a string: the root checked the whole value
                    String key = (String) entry.getKey();
                    built.put(key, new DataNode(entry.getValue(), this, JsonNode.encodeJsonPointer(key)));
                }
            }
            entries = Collections.unmodifiableMap(built);
            object = entries;
        }
        return entries;
    }

    /** The comparable form of the value: numbers by their canonical form, containers by their nodes. */
    private @Nullable Object comparable() {
        return switch (type) {
            case INTEGER, NUMBER -> number;
            case ARRAY -> asArray();
            case OBJECT -> asObject();
            default -> value;
        };
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DataNode node && type == node.type && Objects.equals(comparable(), node.comparable());
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, comparable());
    }

    /** A value still to be checked, with the number of containers around it. */
    private record Pending(@Nullable Object value, int depth) {
    }

    /** The refusal of a value nested deeper than {@value #MAX_DEPTH} levels; its message names the limit only. */
    private static final class TooDeep extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        TooDeep() {
            super("not of the JSON data model: nested deeper than " + MAX_DEPTH + " levels");
        }
    }

    /** The factory of nodes for schemas and data. */
    private static final class Factory implements JsonNodeFactory {

        private static final YamlReader YAML = new YamlReader();

        @Override
        public JsonNode wrap(Object node) {
            return switch (node) {
                case DataNode data -> new DataNode(data.value, null, "");
                case JsonNode foreign -> throw new IllegalArgumentException(
                        "not a node of git-forms: " + foreign.getClass().getName());
                default -> root(node);
            };
        }

        @Override
        public JsonNode create(String rawJson) {
            FormsResult<Object> read = YAML.read(rawJson);
            if (!read.isOk()) {
                throw new IllegalArgumentException("not well-formed JSON: " + read.detail());
            }
            return root(read.value());
        }
    }
}
