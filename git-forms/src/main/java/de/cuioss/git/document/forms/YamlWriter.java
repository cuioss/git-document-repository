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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;


import org.jspecify.annotations.Nullable;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.resolver.CoreScalarResolver;
import org.snakeyaml.engine.v2.resolver.ScalarResolver;

/**
 * The canonical YAML emitter of {@code git-forms} (GDR-STORE-11): equal values yield byte-identical text, and the text
 * reads back through {@link YamlReader} into a value that is written as the same text again.
 * <p>
 * The form is fixed: block style with an indentation of two spaces; a sequence item starts with {@code - } at the
 * indentation of its parent key plus two, a mapping inside it on the same line; an empty mapping is {@code {}}, an empty
 * sequence {@code []}; line feed line ends and one trailing line feed. A mapping is written in the order of its
 * entries, which the caller decides. A string is plain when it starts with a letter, a digit, {@code _}, {@code /} or
 * {@code (}, holds only letters, digits, spaces and {@code _./()+-,;=~@$%&'!?}, does not end in a space and reads back
 * as a string under the YAML 1.2 core schema; every other string is double-quoted, with {@code \\}, {@code "}, line
 * breaks, tabs and every character outside the printable set of YAML escaped. {@code null}, booleans and numbers are
 * plain; a {@link BigDecimal} is written as {@link BigDecimal#toString()}.
 */
final class YamlWriter {

    /** The plain-scalar resolution of the YAML 1.2 core schema, as {@link YamlReader} reads it. */
    private static final ScalarResolver RESOLVER = new CoreScalarResolver(false);

    /** The strings written plain, if they also resolve as strings. */
    private static final Pattern PLAIN = Pattern.compile("[\\p{L}\\p{N}_/(][\\p{L}\\p{N} _./()+\\-,;=~@$%&'!?]*");

    private static final String INDENT = "  ";
    private static final String ITEM = "- ";

    /**
     * Writes a value of the JSON data model.
     *
     * @param value the value; a mapping is written in the order of its entries
     * @return the canonical text, ending in a line feed
     * @throws IllegalArgumentException if the value holds anything outside the JSON data model, or a string that is not
     *                                  valid Unicode
     */
    String write(@Nullable Object value) {
        var text = new StringBuilder();
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            mapping(text, map, 0);
        } else if (value instanceof List<?> list && !list.isEmpty()) {
            sequence(text, list, 0);
        } else {
            text.append(inline(value)).append('\n');
        }
        return text.toString();
    }

    private static void mapping(StringBuilder text, Map<?, ?> map, int indent) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("not of the JSON data model: a key that is not a string");
            }
            text.append(INDENT.repeat(indent)).append(scalar(key)).append(':');
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                text.append('\n');
                mapping(text, nested, indent + 1);
            } else if (value instanceof List<?> list && !list.isEmpty()) {
                text.append('\n');
                sequence(text, list, indent + 1);
            } else {
                text.append(' ').append(inline(value)).append('\n');
            }
        }
    }

    private static void sequence(StringBuilder text, List<?> list, int indent) {
        String prefix = INDENT.repeat(indent) + ITEM;
        for (Object item : list) {
            if (item instanceof Map<?, ?> map && !map.isEmpty()) {
                text.append(sameLine(prefix, nested -> mapping(nested, map, indent + 1)));
            } else if (item instanceof List<?> items && !items.isEmpty()) {
                text.append(sameLine(prefix, nested -> sequence(nested, items, indent + 1)));
            } else {
                text.append(prefix).append(inline(item)).append('\n');
            }
        }
    }

    /** A block written one level deeper, its first line starting with the prefix instead of the indentation. */
    private static String sameLine(String prefix, Consumer<StringBuilder> block) {
        var nested = new StringBuilder();
        block.accept(nested);
        return prefix + nested.substring(prefix.length());
    }

    /** An empty container or a scalar. */
    private static String inline(@Nullable Object value) {
        return switch (value) {
            case null -> "null";
            case Map<?, ?> map -> "{}";
            case List<?> list -> "[]";
            case Boolean bool -> bool.toString();
            case Long number -> number.toString();
            case Integer number -> number.toString();
            case BigInteger number -> number.toString();
            case BigDecimal number -> number.toString();
            case String string -> scalar(string);
            default -> throw new IllegalArgumentException("not of the JSON data model: " + value.getClass().getName());
        };
    }

    /** A string, plain or double-quoted. */
    private static String scalar(String text) {
        if (PLAIN.matcher(text).matches() && !text.endsWith(" ") && Tag.STR.equals(RESOLVER.resolve(text, true))) {
            return text;
        }
        var quoted = new StringBuilder(text.length() + 2).append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character) && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1))) {
                quoted.append(character).append(text.charAt(++index));
            } else if (Character.isSurrogate(character)) {
                throw new IllegalArgumentException("not of the JSON data model: a string that is not valid Unicode");
            } else {
                appendEscaped(quoted, character);
            }
        }
        return quoted.append('"').toString();
    }

    private static void appendEscaped(StringBuilder quoted, char character) {
        switch (character) {
            case '\\' -> quoted.append("\\\\");
            case '"' -> quoted.append("\\\"");
            case '\n' -> quoted.append("\\n");
            case '\t' -> quoted.append("\\t");
            case '\r' -> quoted.append("\\r");
            default -> {
                if (isPrintable(character)) {
                    quoted.append(character);
                } else {
                    quoted.append(String.format(Locale.ROOT, "\\u%04X", (int) character));
                }
            }
        }
    }

    /** The printable characters of YAML 1.2 below the supplementary planes, without the line separators. */
    private static boolean isPrintable(char character) {
        return character >= 0x20 && character <= 0x7E
                || character >= 0xA0 && character <= 0xD7FF && character != 0x2028 && character != 0x2029
                || character >= 0xE000 && character <= 0xFFFD && character != 0xFEFF;
    }
}
