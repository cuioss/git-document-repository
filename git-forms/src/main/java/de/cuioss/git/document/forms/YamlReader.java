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
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.CollectionEndEvent;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.DocumentStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.MappingStartEvent;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.parser.Parser;
import org.snakeyaml.engine.v2.parser.ParserImpl;
import org.snakeyaml.engine.v2.resolver.CoreScalarResolver;
import org.snakeyaml.engine.v2.resolver.ScalarResolver;
import org.snakeyaml.engine.v2.scanner.StreamReader;

/**
 * Reads YAML into the JSON data model of ADR-0008: a mapping becomes a {@link LinkedHashMap} of {@link String} keys in
 * the order of the source, a sequence an {@link ArrayList}, a scalar a {@link String}, a {@link Long} or
 * {@link BigInteger}, a {@link BigDecimal}, a {@link Boolean} or {@code null}.
 * <p>
 * The reader is built on the event parser of snakeyaml-engine 3.2 ({@code ParserImpl} over a {@code StreamReader},
 * the two its {@code Parse} combines; {@code Parse} itself lies in a package the module does not export; checked
 * against its source and {@code module-info.class}) and
 * resolves a plain scalar with its {@code CoreScalarResolver}, the YAML 1.2 core schema; a quoted or block scalar is a
 * string. It refuses, as {@link StoreOutcome#MALFORMED} passed on in a {@link FormsResult}, everything outside the
 * JSON data model: an anchor, an alias, an explicit tag, a key that is not a string, a duplicate key, an infinite
 * number or not-a-number, a number with more than {@value #MAX_NUMBER_DIGITS} digits (leading zeros not counted) or
 * whose exponent exceeds {@value #MAX_EXPONENT} in magnitude, both refused before the number is parsed and the bound
 * named in the detail, a quoted string that is not valid
 * Unicode (a lone surrogate from an escape), more than {@value DataNode#MAX_DEPTH} nested mappings and sequences (the
 * nesting limit {@link DataNode#MAX_DEPTH}, which the detail names), more than one document, and a document without a
 * value. Text that is not
 * well-formed YAML, or longer than {@link #CODE_POINT_LIMIT} code points, is refused the same way.
 * <p>
 * A detail names the position (line and column, counted from 1) and the problem, never the content (GDR-NFR-3). The
 * reader holds no state; one instance may serve every thread.
 */
final class YamlReader {

    /** The plain-scalar resolution of the YAML 1.2 core schema, without the merge key. */
    private static final ScalarResolver RESOLVER = new CoreScalarResolver(false);

    /** The prefix of an octal integer of the core schema. */
    private static final String OCTAL = "0o";

    /** The prefix of a hexadecimal integer of the core schema. */
    private static final String HEXADECIMAL = "0x";

    /** The detail of a refusal whose position is unknown. */
    private static final String UNKNOWN_POSITION = "unknown position";

    /**
     * The most code points a text may hold, set as the code point limit of snakeyaml-engine, so that a writer can
     * refuse what this reader would refuse.
     */
    static final int CODE_POINT_LIMIT = 3 * 1024 * 1024;

    /**
     * The most significant digits a number may have in the text, leading zeros not counted; equal to the digit limit
     * of an integer of {@link DataNode}. Parsing a digit string, and the {@link BigDecimal#remainder(BigDecimal)} the
     * validator computes for {@code multipleOf}, cost time that grows with the digits.
     */
    static final int MAX_NUMBER_DIGITS = DataNode.MAX_INTEGER_DIGITS;

    /**
     * The largest magnitude of the adjusted exponent of a float, the exponent of its first significant digit: the
     * working precision of {@link BigDecimal#remainder(BigDecimal)} under {@code multipleOf} grows with the difference
     * of the exponents. The bound keeps {@code 1e-400} readable.
     */
    static final int MAX_EXPONENT = 1000;

    /**
     * The magnitude beyond which the exponent text is no longer accumulated: no fraction a text within the code point
     * limit can hold brings an exponent beyond it back within {@link #MAX_EXPONENT}.
     */
    private static final long EXPONENT_CEILING = (long) CODE_POINT_LIMIT + MAX_EXPONENT;

    /** The problem of a number with too many digits. */
    private static final String TOO_MANY_DIGITS = "a number with more than " + MAX_NUMBER_DIGITS + " digits";

    /** The problem of a number whose exponent is too large in magnitude. */
    private static final String TOO_LARGE_EXPONENT = "a number whose exponent exceeds " + MAX_EXPONENT
            + " in magnitude";

    /** The load settings: comments not parsed, marks kept for the details, the limit of the text. */
    private final LoadSettings settings = LoadSettings.builder().setUseMarks(true)
            .setCodePointLimit(CODE_POINT_LIMIT).build();

    /**
     * Reads one YAML document.
     *
     * @param yaml the text
     * @return {@link FormsOutcome#OK} with the value of the document, or the passed-on {@link StoreOutcome#MALFORMED}
     *         whose detail names the position and the problem
     * @throws NullPointerException if {@code yaml} is {@code null}
     */
    FormsResult<Object> read(String yaml) {
        FormsResult<Root> root = root(yaml);
        if (!root.isOk()) {
            return new FormsResult<>(root.outcome(), null, root.detail());
        }
        return FormsResult.ok(Objects.requireNonNull(root.value()).value());
    }

    /**
     * Reads one YAML document whose root must be a mapping, typed as the reader builds it, so that a caller needs no
     * cast of the root.
     *
     * @param yaml       the text
     * @param notMapping the detail of the refusal of a document whose root is not a mapping
     * @return {@link FormsOutcome#OK} with the root mapping, or the passed-on {@link StoreOutcome#MALFORMED} whose detail
     *         names the position and the problem, or is {@code notMapping}
     * @throws NullPointerException if an argument is {@code null}
     */
    FormsResult<Map<String, @Nullable Object>> readMapping(String yaml, String notMapping) {
        Objects.requireNonNull(notMapping, "notMapping");
        FormsResult<Root> root = root(yaml);
        if (!root.isOk()) {
            return new FormsResult<>(root.outcome(), null, root.detail());
        }
        @Nullable MappingContainer mapping = Objects.requireNonNull(root.value()).mapping();
        return mapping == null ? malformed(notMapping) : FormsResult.ok(mapping.entries);
    }

    /** Reads the one document of a text, keeping the mapping that built its root. */
    private FormsResult<Root> root(String yaml) {
        Objects.requireNonNull(yaml, "yaml");
        try {
            return build(new ParserImpl(settings, new StreamReader(settings, yaml)));
        } catch (Refusal refusal) {
            return malformed(refusal.getMessage());
        } catch (MarkedYamlEngineException e) {
            Optional<Mark> mark = e.getProblemMark().or(e::getContextMark);
            return malformed(position(mark) + ": not well-formed YAML");
        } catch (YamlEngineException e) {
            return malformed(UNKNOWN_POSITION + ": not well-formed YAML");
        }
    }

    /**
     * Decodes content as strict UTF-8, the form every file read through this reader has.
     *
     * @param content the content
     * @return the text, or {@code null} when the content is not UTF-8
     */
    static @Nullable String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    /** Builds the value of the one document of the event stream, with the mapping that built it, if it is one. */
    private static FormsResult<Root> build(Parser events) {
        Deque<Container> open = new ArrayDeque<>();
        List<@Nullable Object> roots = new ArrayList<>(1);
        @Nullable MappingContainer rootMapping = null;
        int documents = 0;
        while (events.hasNext()) {
            switch (events.next()) {
                case DocumentStartEvent start -> {
                    documents++;
                    if (documents > 1) {
                        throw new Refusal(start, "more than one document");
                    }
                }
                case AliasEvent alias -> throw new Refusal(alias, "an alias");
                case NodeEvent node -> node(open, roots, node);
                case CollectionEndEvent end -> {
                    Container closed = open.pop();
                    if (open.isEmpty() && closed instanceof MappingContainer mapping) {
                        rootMapping = mapping;
                    }
                    add(open, roots, closed.value(), closed.start());
                }
                default -> {
                    // the start and end of the stream and the end of a document carry no value
                }
            }
        }
        if (roots.isEmpty() || roots.getFirst() == null) {
            return malformed("line 1, column 1: a document without a value");
        }
        return FormsResult.ok(new Root(Objects.requireNonNull(roots.getFirst()), rootMapping));
    }

    /** Reads a scalar into the innermost container, or opens a mapping or a sequence. */
    private static void node(Deque<Container> open, List<@Nullable Object> roots, NodeEvent node) {
        checkNode(node);
        if (node instanceof ScalarEvent scalar) {
            add(open, roots, scalar(scalar), scalar);
        } else {
            expectNoKey(open, node);
            if (open.size() == DataNode.MAX_DEPTH) {
                throw new Refusal(node, "a nesting deeper than " + DataNode.MAX_DEPTH + " levels");
            }
            open.push(node instanceof MappingStartEvent ? new MappingContainer(node) : new SequenceContainer(node));
        }
    }

    /** Refuses an anchor and an explicit tag on a node. */
    private static void checkNode(NodeEvent node) {
        if (node.getAnchor().isPresent()) {
            throw new Refusal(node, "an anchor");
        }
        Optional<String> tag = node instanceof ScalarEvent scalar ? scalar.getTag()
                : ((CollectionStartEvent) node).getTag();
        if (tag.isPresent()) {
            throw new Refusal(node, "an explicit tag");
        }
    }

    /** Refuses a mapping or a sequence where a mapping expects its next key. */
    private static void expectNoKey(Deque<Container> open, Event event) {
        if (open.peek() instanceof MappingContainer mapping && mapping.expectsKey()) {
            throw new Refusal(event, "a key that is not a string");
        }
    }

    /** Adds a value to the innermost open container, or keeps it as the value of the document. */
    private static void add(Deque<Container> open, List<@Nullable Object> roots, @Nullable Object value,
            Event event) {
        Container innermost = open.peek();
        if (innermost == null) {
            roots.add(value);
        } else {
            innermost.add(value, event);
        }
    }

    /** Converts a scalar into the JSON data model. */
    private static @Nullable Object scalar(ScalarEvent event) {
        String text = event.getValue();
        if (!event.isPlain()) {
            if (!isValidUnicode(text)) {
                // an escape of a double-quoted scalar can produce a lone surrogate, which no writer can encode
                throw new Refusal(event, "a string that is not valid Unicode");
            }
            return text;
        }
        Tag tag = RESOLVER.resolve(text, Boolean.TRUE);
        if (Tag.NULL.equals(tag)) {
            return null;
        }
        if (Tag.BOOL.equals(tag)) {
            return Boolean.valueOf(text.toLowerCase(Locale.ROOT));
        }
        if (Tag.INT.equals(tag)) {
            return integer(text, event);
        }
        if (Tag.FLOAT.equals(tag)) {
            return decimal(text, event);
        }
        return text;
    }

    /**
     * Tells which bound of this reader the plain scalar of a number exceeds: more than {@value #MAX_NUMBER_DIGITS}
     * significant digits, or an adjusted exponent of a magnitude above {@value #MAX_EXPONENT}. The one source of the
     * number bounds: the reader refuses with it, and {@link YamlDocumentCodec#encode(Map)} refuses with it what the
     * reader would refuse, so that the two cannot drift.
     *
     * @param text the plain scalar, as {@link YamlWriter} writes a number
     * @return the problem, naming the bound and never the number, or empty when the text is within the bounds or no
     *         number of the core schema
     */
    static Optional<String> exceededNumberBound(String text) {
        Tag tag = RESOLVER.resolve(text, Boolean.TRUE);
        if (Tag.INT.equals(tag)) {
            return integerBound(text);
        }
        if (Tag.FLOAT.equals(tag)) {
            return floatBound(text);
        }
        return Optional.empty();
    }

    /**
     * Converts a float of the core schema. An infinity and not-a-number are refused, and so is, before the text is
     * parsed, a text beyond the bounds of {@link #floatBound(String)}.
     */
    private static BigDecimal decimal(String text, ScalarEvent event) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("inf") || lower.contains("nan")) {
            throw new Refusal(event, "a number outside the JSON data model");
        }
        Optional<String> exceeded = floatBound(text);
        if (exceeded.isPresent()) {
            throw new Refusal(event, exceeded.get());
        }
        return new BigDecimal(text);
    }

    /**
     * The bound a finite float of the core schema exceeds: more than {@value #MAX_NUMBER_DIGITS} significant digits, or
     * an adjusted exponent (that of the first significant digit, as {@link BigDecimal} counts it) of a magnitude above
     * {@value #MAX_EXPONENT}; computed from the text, without parsing it.
     */
    private static Optional<String> floatBound(String text) {
        int marker = text.toLowerCase(Locale.ROOT).indexOf('e');
        String mantissa = marker < 0 ? text : text.substring(0, marker);
        int point = mantissa.indexOf('.');
        int fractionDigits = point < 0 ? 0 : mantissa.length() - point - 1;
        String digitText = point < 0 ? mantissa : mantissa.substring(0, point) + mantissa.substring(point + 1);
        int digits = significantDigits(digitText);
        if (digits > MAX_NUMBER_DIGITS) {
            return Optional.of(TOO_MANY_DIGITS);
        }
        long exponent = marker < 0 ? 0 : boundedExponent(text.substring(marker + 1));
        long adjusted = exponent - fractionDigits + Math.max(digits, 1) - 1;
        if (Math.abs(adjusted) > MAX_EXPONENT) {
            return Optional.of(TOO_LARGE_EXPONENT);
        }
        return Optional.empty();
    }

    /** The number of digits of a digit string after its sign and its leading zeros. */
    private static int significantDigits(String digits) {
        int start = 0;
        if (start < digits.length() && (digits.charAt(start) == '+' || digits.charAt(start) == '-')) {
            start++;
        }
        while (start < digits.length() && digits.charAt(start) == '0') {
            start++;
        }
        return digits.length() - start;
    }

    /**
     * The value of the exponent of a float, accumulated so that it cannot overflow: once its magnitude passes any
     * value a text within the code point limit could bring back into the bound, it stays there.
     */
    private static long boundedExponent(String exponent) {
        boolean negative = exponent.startsWith("-");
        int start = negative || exponent.startsWith("+") ? 1 : 0;
        long value = 0;
        for (int index = start; index < exponent.length() && value <= EXPONENT_CEILING; index++) {
            value = value * 10 + (exponent.charAt(index) - '0');
        }
        return negative ? -value : value;
    }

    /** Whether a text holds no lone surrogate. */
    private static boolean isValidUnicode(String text) {
        return text.codePoints().noneMatch(codePoint -> codePoint >= Character.MIN_SURROGATE
                && codePoint <= Character.MAX_SURROGATE);
    }

    /**
     * Converts an integer of the core schema: decimal, octal with {@code 0o} or hexadecimal with {@code 0x}; more than
     * {@value #MAX_NUMBER_DIGITS} digits in the text, leading zeros not counted, are refused before it is parsed.
     */
    private static Object integer(String text, ScalarEvent event) {
        Optional<String> exceeded = integerBound(text);
        if (exceeded.isPresent()) {
            throw new Refusal(event, exceeded.get());
        }
        BigInteger value = new BigInteger(integerDigits(text), radix(text));
        return value.bitLength() < Long.SIZE ? (Object) value.longValueExact() : value;
    }

    /** The bound an integer of the core schema exceeds: more than {@value #MAX_NUMBER_DIGITS} significant digits. */
    private static Optional<String> integerBound(String text) {
        return significantDigits(integerDigits(text)) > MAX_NUMBER_DIGITS ? Optional.of(TOO_MANY_DIGITS)
                : Optional.empty();
    }

    /** The radix of an integer of the core schema, given by its prefix. */
    private static int radix(String text) {
        if (text.startsWith(OCTAL)) {
            return 8;
        }
        return text.startsWith(HEXADECIMAL) ? 16 : 10;
    }

    /** The digits of an integer of the core schema, with its sign and without its prefix. */
    private static String integerDigits(String text) {
        if (text.startsWith(OCTAL)) {
            return text.substring(OCTAL.length());
        }
        return text.startsWith(HEXADECIMAL) ? text.substring(HEXADECIMAL.length()) : text;
    }

    /** Renders the position of a mark, counted from 1. */
    private static String position(Optional<Mark> mark) {
        return mark.map(found -> "line " + (found.getLine() + 1) + ", column " + (found.getColumn() + 1))
                .orElse(UNKNOWN_POSITION);
    }

    /** The passed-on {@link StoreOutcome#MALFORMED}. */
    private static <V> FormsResult<V> malformed(String detail) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, detail));
    }

    /**
     * The value of a document, and the mapping that built it when its root is a mapping.
     *
     * @param value   the value of the document
     * @param mapping the mapping of the root, or {@code null} when the root is a sequence or a scalar
     */
    private record Root(Object value, @Nullable MappingContainer mapping) {
    }

    /** A refusal of the reader, carrying the detail: the position and the problem, never the content. */
    private static final class Refusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Refusal(Event event, String problem) {
            super(position(event.getStartMark()) + ": " + problem, null, false, false);
        }
    }

    /** An open mapping or sequence. */
    private interface Container {

        /** Adds the next value read inside the container. */
        void add(@Nullable Object value, Event event);

        /** The value the container built. */
        Object value();

        /** The event that opened the container. */
        Event start();
    }

    /** An open mapping: keys and values alternate, each key a string and not a duplicate. */
    private static final class MappingContainer implements Container {

        private final Event start;
        private final Map<String, @Nullable Object> entries = new LinkedHashMap<>();
        private @Nullable String key;

        MappingContainer(Event start) {
            this.start = start;
        }

        boolean expectsKey() {
            return key == null;
        }

        @Override
        public void add(@Nullable Object value, Event event) {
            String pending = key;
            if (pending == null) {
                if (!(value instanceof String text)) {
                    throw new Refusal(event, "a key that is not a string");
                }
                if (entries.containsKey(text)) {
                    throw new Refusal(event, "a duplicate key");
                }
                key = text;
            } else {
                entries.put(pending, value);
                key = null;
            }
        }

        @Override
        public Object value() {
            return entries;
        }

        @Override
        public Event start() {
            return start;
        }
    }

    /** An open sequence. */
    private static final class SequenceContainer implements Container {

        private final Event start;
        private final List<@Nullable Object> items = new ArrayList<>();

        SequenceContainer(Event start) {
            this.start = start;
        }

        @Override
        public void add(@Nullable Object value, Event event) {
            items.add(value);
        }

        @Override
        public Object value() {
            return items;
        }

        @Override
        public Event start() {
            return start;
        }
    }
}
