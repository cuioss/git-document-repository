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
 * number or not-a-number, a number whose exponent leaves the range of {@code int}, a quoted string that is not valid
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

    /** Builds the value of the one document of the event stream. */
    private static FormsResult<Object> build(Parser events) {
        Deque<Container> open = new ArrayDeque<>();
        List<@Nullable Object> roots = new ArrayList<>(1);
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
        return FormsResult.ok(Objects.requireNonNull(roots.getFirst()));
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
            return integer(text);
        }
        if (Tag.FLOAT.equals(tag)) {
            return decimal(text, event);
        }
        return text;
    }

    /** Converts a float of the core schema; an infinity, not-a-number and an exponent beyond int are refused. */
    private static BigDecimal decimal(String text, ScalarEvent event) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("inf") || lower.contains("nan")) {
            throw new Refusal(event, "a number outside the JSON data model");
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException e) {
            // the exponent leaves the range of int, for example 1e-2147483648
            throw new Refusal(event, "a number outside the range of the reader");
        }
    }

    /** Whether a text holds no lone surrogate. */
    private static boolean isValidUnicode(String text) {
        return text.codePoints().noneMatch(codePoint -> codePoint >= Character.MIN_SURROGATE
                && codePoint <= Character.MAX_SURROGATE);
    }

    /** Converts an integer of the core schema: decimal, octal with {@code 0o} or hexadecimal with {@code 0x}. */
    private static Object integer(String text) {
        BigInteger value;
        if (text.startsWith(OCTAL)) {
            value = new BigInteger(text.substring(OCTAL.length()), 8);
        } else if (text.startsWith(HEXADECIMAL)) {
            value = new BigInteger(text.substring(HEXADECIMAL.length()), 16);
        } else {
            value = new BigInteger(text);
        }
        return value.bitLength() < Long.SIZE ? (Object) value.longValueExact() : value;
    }

    /** Renders the position of a mark, counted from 1. */
    private static String position(Optional<Mark> mark) {
        return mark.map(found -> "line " + (found.getLine() + 1) + ", column " + (found.getColumn() + 1))
                .orElse(UNKNOWN_POSITION);
    }

    /** The passed-on {@link StoreOutcome#MALFORMED}. */
    private static FormsResult<Object> malformed(String detail) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, detail));
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
