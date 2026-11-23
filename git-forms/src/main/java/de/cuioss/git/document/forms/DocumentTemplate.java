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
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The template of a kind of document (GDR-FORMS-4): it joins the data of a document and its AsciiDoc fragments into one
 * AsciiDoc output file.
 * <p>
 * The template language is the own minimal language of {@code git-forms} (owner's decision, 2026-11-23), a strict
 * subset of the Mustache syntax without lambdas and without partials loaded at run time. A tag is enclosed in double
 * braces:
 * <ul>
 * <li>{@code {{name}}} inserts the value at {@code name}, a key of the data, or a path of keys separated by dots
 * ({@code {{owner.team}}}); {@code {{.}}} inserts the current value of a section. A string is inserted as it is, a
 * boolean as {@code true} or {@code false}, a number in the form the YAML of the document writes it
 * ({@link BigDecimal#toString()} for a decimal); an absent value, {@code null}, a mapping and a sequence insert
 * nothing. Nothing is escaped: the output is AsciiDoc, not HTML.</li>
 * <li>{@code {{#name}}...{{/name}}} renders its content once per item of a non-empty sequence, each item becoming the
 * current value, once for any other value that is present and not {@code false} (a mapping becoming the current
 * value), and not at all for an absent value, {@code null}, {@code false} or an empty sequence.</li>
 * <li>{@code {{^name}}...{{/name}}} renders its content exactly when {@code {{#name}}} would not.</li>
 * <li>{@code {{>name}}} inserts the narrative fragment of that name, as it is: a fragment is never interpreted as a
 * template and never loaded by the template; its line ends become line feeds and its trailing line feeds are removed,
 * so that the line feed after the tag ends it. An absent fragment inserts nothing.</li>
 * <li>{@code {{! comment}}} inserts nothing.</li>
 * </ul>
 * A name is looked up as Mustache looks it up: its first key in the current value, then in the values of the enclosing
 * sections, outward to the data, and every further key in the value found. A name is made of segments of
 * {@code [A-Za-z0-9_-]}, a fragment name is one such segment. A line that holds nothing but one section, inverted
 * section, closing or comment tag and spaces or tabs is removed whole, with its line feed, so that these tags leave no
 * blank line. Triple braces, the ampersand, a change of delimiters and lambdas are not part of the language.
 * <p>
 * {@link #parse(String, byte[])} refuses, as the passed-on {@link StoreOutcome#MALFORMED}, a template larger than
 * {@value #MAX_TEMPLATE_BYTES} bytes, one that is not UTF-8, an empty tag, a tag that is not closed, a tag the language
 * does not support, a name outside its form, a closing tag that does not match its section, a section that is not
 * closed and sections nested deeper than {@value #MAX_NESTING} levels. The detail names the file, the line, counted
 * from 1, and the problem, never content of the template (GDR-NFR-3).
 * <p>
 * {@link #render(Map, Map)} is deterministic: equal data and fragments give byte-identical output, whatever the
 * insertion order of the maps, because a value is found by its key and a sequence keeps its order; nothing depends on
 * the locale, the time zone or the platform. The output has line feed line ends only and ends in exactly one line feed.
 * A template is immutable and may serve every thread.
 *
 * @since 0.1
 */
public final class DocumentTemplate {

    /** The largest template the parser reads, in bytes (1 MiB). */
    public static final int MAX_TEMPLATE_BYTES = 1_048_576;

    /** The deepest nesting of sections a template may hold: the nesting limit of the data model. */
    public static final int MAX_NESTING = DataNode.MAX_DEPTH;

    private static final String OPEN = "{{";
    private static final String CLOSE = "}}";
    private static final String CURRENT = ".";
    private static final String SEPARATOR = "\\.";

    /** The form of a name: segments of letters, digits, {@code _} and {@code -}, separated by dots. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");

    /** The form of a fragment name: one segment. */
    private static final Pattern FRAGMENT = Pattern.compile("[A-Za-z0-9_-]+");

    private final List<Node> nodes;

    private DocumentTemplate(List<Node> nodes) {
        this.nodes = nodes;
    }

    /**
     * Reads a template.
     *
     * @param path    the repository-relative path of the template file, named in the detail of a refusal
     * @param content the content of the file
     * @return {@link FormsOutcome#OK} with the template, or the passed-on {@link StoreOutcome#MALFORMED} whose detail
     *         names the file, the line and the problem
     * @throws NullPointerException if an argument is {@code null}
     */
    public static FormsResult<DocumentTemplate> parse(String path, byte[] content) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        if (content.length > MAX_TEMPLATE_BYTES) {
            return malformed(path, "too large, more than " + MAX_TEMPLATE_BYTES + " bytes");
        }
        String text = YamlReader.decodeUtf8(content);
        if (text == null) {
            return malformed(path, "not UTF-8");
        }
        try {
            return FormsResult.ok(new DocumentTemplate(new Parser(lineFeeds(text)).parse()));
        } catch (Refusal refusal) {
            return malformed(path, Objects.requireNonNull(refusal.getMessage()));
        }
    }

    /**
     * Joins data and fragments into the AsciiDoc output.
     *
     * @param data      the data of the document, a mapping of the JSON data model
     * @param fragments the narrative fragments by name
     * @return the output, with line feed line ends and exactly one trailing line feed
     * @throws NullPointerException     if an argument, a fragment name or a fragment is {@code null}
     * @throws IllegalArgumentException if the data holds a value outside the JSON data model or is nested deeper than
     *                                  its nesting limit
     */
    public String render(Map<String, ?> data, Map<String, String> fragments) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(fragments, "fragments");
        fragments.forEach((name, fragment) -> {
            Objects.requireNonNull(name, "fragment name");
            Objects.requireNonNull(fragment, "fragment");
        });
        DataNode.requireDataModel(data);
        var out = new StringBuilder();
        List<@Nullable Object> context = new ArrayList<>();
        context.add(data);
        render(nodes, context, fragments, out);
        String rendered = lineFeeds(out.toString());
        return withoutTrailingLineFeeds(rendered) + "\n";
    }

    private static void render(List<Node> nodes, List<@Nullable Object> context, Map<String, String> fragments,
            StringBuilder out) {
        for (Node node : nodes) {
            switch (node) {
                case Text text -> out.append(text.text());
                case Variable variable -> out.append(scalar(lookup(variable.name(), context)));
                case Fragment fragment -> {
                    String content = fragments.get(fragment.name());
                    if (content != null) {
                        out.append(withoutTrailingLineFeeds(lineFeeds(content)));
                    }
                }
                case Section section -> section(section, context, fragments, out);
            }
        }
    }

    private static void section(Section section, List<@Nullable Object> context, Map<String, String> fragments,
            StringBuilder out) {
        @Nullable Object value = lookup(section.name(), context);
        boolean present = isPresent(value);
        if (section.inverted()) {
            if (!present) {
                render(section.children(), context, fragments, out);
            }
            return;
        }
        if (!present) {
            return;
        }
        if (value instanceof List<?> items) {
            for (Object item : items) {
                within(section, item, context, fragments, out);
            }
        } else {
            within(section, value, context, fragments, out);
        }
    }

    private static void within(Section section, @Nullable Object value, List<@Nullable Object> context,
            Map<String, String> fragments, StringBuilder out) {
        context.add(value);
        render(section.children(), context, fragments, out);
        context.removeLast();
    }

    /** Whether a section renders for a value: present, not {@code false} and not an empty sequence. */
    private static boolean isPresent(@Nullable Object value) {
        return switch (value) {
            case null -> false;
            case Boolean bool -> bool;
            case List<?> list -> !list.isEmpty();
            default -> true;
        };
    }

    /** Looks a name up in the context, innermost value first; {@code null} for an absent value. */
    private static @Nullable Object lookup(String name, List<@Nullable Object> context) {
        if (CURRENT.equals(name)) {
            return context.getLast();
        }
        String[] keys = name.split(SEPARATOR);
        @Nullable Object value = null;
        boolean found = false;
        for (int index = context.size() - 1; index >= 0 && !found; index--) {
            if (context.get(index) instanceof Map<?, ?> map && map.containsKey(keys[0])) {
                value = map.get(keys[0]);
                found = true;
            }
        }
        for (int index = 1; index < keys.length && value != null; index++) {
            value = value instanceof Map<?, ?> map ? map.get(keys[index]) : null;
        }
        return value;
    }

    /** The text a variable inserts for a value. */
    private static String scalar(@Nullable Object value) {
        return switch (value) {
            case String string -> string;
            case Boolean bool -> bool.toString();
            case Long number -> number.toString();
            case Integer number -> number.toString();
            case BigInteger number -> number.toString();
            case BigDecimal number -> number.toString();
            case null, default -> "";
        };
    }

    /** Turns every line end into a line feed. */
    private static String lineFeeds(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String withoutTrailingLineFeeds(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '\n') {
            end--;
        }
        return text.substring(0, end);
    }

    private static <T> FormsResult<T> malformed(String path, String problem) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, path + ": " + problem));
    }

    /** A node of a parsed template. */
    private sealed interface Node permits Text, Variable, Fragment, Section {
    }

    /** Literal text. */
    private record Text(String text) implements Node {
    }

    /** A variable tag. */
    private record Variable(String name) implements Node {
    }

    /** A fragment tag. */
    private record Fragment(String name) implements Node {
    }

    /** A section or an inverted section with its content. */
    private record Section(String name, boolean inverted, List<Node> children) implements Node {
    }

    /** The kinds of tags. */
    private enum TagType {
        VARIABLE, SECTION, INVERTED, CLOSING, FRAGMENT, COMMENT;

        /** Whether a tag of this type alone on its line removes the line. */
        boolean standalone() {
            return this == SECTION || this == INVERTED || this == CLOSING || this == COMMENT;
        }
    }

    /** A tag as read. */
    private record Tag(TagType type, String name) {
    }

    /** An open section while parsing: its tag, the nodes it adds to when closed, and its content. */
    private record OpenSection(String name, boolean inverted, int line, List<Node> parent, List<Node> children) {
    }

    /** A refusal of the template, its message naming the line and the problem, never content. */
    private static final class Refusal extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Refusal(int line, String problem) {
            super("line " + line + ": " + problem, null, false, false);
        }
    }

    /** The parser of one template; used once. */
    private static final class Parser {

        private final String template;
        private final StringBuilder text = new StringBuilder();
        private final Deque<OpenSection> open = new ArrayDeque<>();
        private List<Node> current = new ArrayList<>();
        private int position;
        private int line = 1;

        /** Whether the current line holds anything but spaces and tabs before the position. */
        private boolean lineHasContent;

        Parser(String template) {
            this.template = template;
        }

        List<Node> parse() {
            int start = template.indexOf(OPEN, position);
            while (start >= 0) {
                appendText(template.substring(position, start));
                int end = template.indexOf(CLOSE, start + OPEN.length());
                if (end < 0) {
                    throw new Refusal(line, "a tag that is not closed");
                }
                int tagLine = line;
                String raw = template.substring(start + OPEN.length(), end);
                Tag tag = tag(raw, tagLine);
                line += (int) raw.chars().filter(c -> c == '\n').count();
                position = end + CLOSE.length();
                if (tag.type().standalone() && !lineHasContent && restOfLineIsBlank()) {
                    dropIndentation();
                    skipRestOfLine();
                    emit(tag, tagLine);
                    lineHasContent = false;
                } else {
                    emit(tag, tagLine);
                    lineHasContent = true;
                }
                start = template.indexOf(OPEN, position);
            }
            appendText(template.substring(position));
            flush();
            if (!open.isEmpty()) {
                throw new Refusal(open.peek().line(), "a section that is not closed");
            }
            return List.copyOf(current);
        }

        private Tag tag(String raw, int tagLine) {
            String content = raw.strip();
            if (content.isEmpty()) {
                throw new Refusal(tagLine, "an empty tag");
            }
            char sigil = content.charAt(0);
            return switch (sigil) {
                case '!' -> new Tag(TagType.COMMENT, "");
                case '{', '&', '=' -> throw new Refusal(tagLine, "a tag the template language does not support");
                case '#' -> new Tag(TagType.SECTION, name(content.substring(1), tagLine));
                case '^' -> new Tag(TagType.INVERTED, name(content.substring(1), tagLine));
                case '/' -> new Tag(TagType.CLOSING, name(content.substring(1), tagLine));
                case '>' -> {
                    String fragment = content.substring(1).strip();
                    if (!FRAGMENT.matcher(fragment).matches()) {
                        throw new Refusal(tagLine, "a fragment name that is not of the form [A-Za-z0-9_-]+");
                    }
                    yield new Tag(TagType.FRAGMENT, fragment);
                }
                default -> new Tag(TagType.VARIABLE, CURRENT.equals(content) ? CURRENT : name(content, tagLine));
            };
        }

        private static String name(String raw, int tagLine) {
            String name = raw.strip();
            if (!NAME.matcher(name).matches()) {
                throw new Refusal(tagLine, "a name that is not of the form [A-Za-z0-9_-]+, separated by dots");
            }
            return name;
        }

        private void emit(Tag tag, int tagLine) {
            flush();
            switch (tag.type()) {
                case VARIABLE -> current.add(new Variable(tag.name()));
                case FRAGMENT -> current.add(new Fragment(tag.name()));
                case COMMENT -> {
                    // a comment inserts nothing
                }
                case SECTION, INVERTED -> {
                    if (open.size() >= MAX_NESTING) {
                        throw new Refusal(tagLine, "sections nested deeper than " + MAX_NESTING + " levels");
                    }
                    List<Node> children = new ArrayList<>();
                    open.push(new OpenSection(tag.name(), tag.type() == TagType.INVERTED, tagLine, current,
                            children));
                    current = children;
                }
                case CLOSING -> {
                    OpenSection section = open.poll();
                    if (section == null) {
                        throw new Refusal(tagLine, "a closing tag without a section");
                    }
                    if (!section.name().equals(tag.name())) {
                        throw new Refusal(tagLine, "a closing tag that does not match its section");
                    }
                    current = section.parent();
                    current.add(new Section(section.name(), section.inverted(), List.copyOf(section.children())));
                }
            }
        }

        private void appendText(String part) {
            for (int index = 0; index < part.length(); index++) {
                char c = part.charAt(index);
                text.append(c);
                if (c == '\n') {
                    line++;
                    lineHasContent = false;
                } else if (c != ' ' && c != '\t') {
                    lineHasContent = true;
                }
            }
        }

        private boolean restOfLineIsBlank() {
            for (int index = position; index < template.length() && template.charAt(index) != '\n'; index++) {
                char c = template.charAt(index);
                if (c != ' ' && c != '\t') {
                    return false;
                }
            }
            return true;
        }

        private void skipRestOfLine() {
            int end = template.indexOf('\n', position);
            if (end < 0) {
                position = template.length();
            } else {
                position = end + 1;
                line++;
            }
        }

        /** Removes the spaces and tabs that precede a standalone tag on its line. */
        private void dropIndentation() {
            int end = text.length();
            while (end > 0 && (text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
                end--;
            }
            text.setLength(end);
        }

        private void flush() {
            if (!text.isEmpty()) {
                current.add(new Text(text.toString()));
                text.setLength(0);
            }
        }
    }
}
