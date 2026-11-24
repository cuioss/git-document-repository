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
package de.cuioss.git.document.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The native-image metadata of {@code git-core} has the shape GraalVM reads and lists exactly what the code paths of
 * {@code git-core} reach of JGit: {@code reflect-config.json} is an array of objects, {@code resource-config.json} an
 * object, and they hold the one class {@code JGitText} with its public fields and its no-argument constructor and its
 * one translation bundle. Every listed class loads and every listed bundle resolves on the class path. The files are
 * read by a minimal structural JSON reader of this test, without a library, so a malformed file, a removed entry and a
 * JGit upgrade that moves or renames a listed class or bundle fail this build instead of the native image of a
 * consumer.
 */
@DisplayName("Native-image metadata of git-core")
class NativeImageMetadataTest {

    /** The directory of the metadata files on the class path. */
    private static final String METADATA_DIRECTORY = "META-INF/native-image/de.cuioss/git-core/";

    /** The file listing the types reached reflectively. */
    private static final String REFLECT_CONFIG = METADATA_DIRECTORY + "reflect-config.json";

    /** The file listing the resource bundles. */
    private static final String RESOURCE_CONFIG = METADATA_DIRECTORY + "resource-config.json";

    /** The one JGit translation class the code paths of git-core reach, and the name of its resource bundle. */
    private static final String JGIT_TEXT = "org.eclipse.jgit.internal.JGitText";

    private static final ClassLoader LOADER = NativeImageMetadataTest.class.getClassLoader();

    @Test
    @DisplayName("Should ship reflect-config.json as an array holding exactly the JGitText entry")
    void shouldShipExpectedReflectConfig() throws Exception {
        var parsed = read(REFLECT_CONFIG);

        var entries = assertInstanceOf(List.class, parsed, REFLECT_CONFIG + " is a JSON array");
        assertAll("entries of " + REFLECT_CONFIG,
                () -> assertAll(entries.stream().map(entry -> (Executable) () -> assertInstanceOf(Map.class, entry,
                        "Every entry of " + REFLECT_CONFIG + " is a JSON object: " + entry))),
                () -> assertEquals(List.of(Map.of(
                                "name", JGIT_TEXT,
                                "allPublicFields", true,
                                "methods", List.of(Map.of("name", "<init>", "parameterTypes", List.of())))), entries,
                        REFLECT_CONFIG + " lists JGitText with its public fields and its no-argument constructor,"
                                + " and nothing else"));
    }

    @Test
    @DisplayName("Should ship resource-config.json as an object holding exactly the JGitText bundle")
    void shouldShipExpectedResourceConfig() throws Exception {
        var parsed = read(RESOURCE_CONFIG);

        var object = assertInstanceOf(Map.class, parsed, RESOURCE_CONFIG + " is a JSON object");
        assertEquals(Map.of("bundles", List.of(Map.of("name", JGIT_TEXT))), object,
                RESOURCE_CONFIG + " lists the bundle of JGitText, and nothing else");
    }

    @Test
    @DisplayName("Should load every class listed in reflect-config.json")
    void shouldLoadEveryListedClass() throws Exception {
        var classNames = ((List<?>) read(REFLECT_CONFIG)).stream()
                .map(entry -> (String) ((Map<?, ?>) entry).get("name"))
                .toList();

        assertEquals(List.of(JGIT_TEXT), classNames, REFLECT_CONFIG + " names the expected class");
        assertAll("Classes of " + REFLECT_CONFIG, classNames.stream()
                .map(name -> (Executable) () -> assertDoesNotThrow(() -> Class.forName(name, false, LOADER),
                        "Listed class must exist: " + name)));
    }

    @Test
    @DisplayName("Should resolve every resource bundle listed in resource-config.json")
    void shouldResolveEveryListedBundle() throws Exception {
        var bundleNames = ((List<?>) ((Map<?, ?>) read(RESOURCE_CONFIG)).get("bundles")).stream()
                .map(entry -> (String) ((Map<?, ?>) entry).get("name"))
                .toList();

        assertEquals(List.of(JGIT_TEXT), bundleNames, RESOURCE_CONFIG + " names the expected bundle");
        assertAll("Bundles of " + RESOURCE_CONFIG, bundleNames.stream()
                .map(name -> (Executable) () -> assertDoesNotThrow(
                        () -> ResourceBundle.getBundle(name, Locale.ROOT, LOADER),
                        "Listed resource bundle must resolve: " + name)));
    }

    /**
     * Reads a metadata file from the class path and parses it.
     *
     * @param resource the class-path location of the file
     * @return the parsed top-level value
     */
    private static Object read(String resource) throws IOException {
        String content;
        try (InputStream in = LOADER.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the class path");
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return MinimalJson.parse(content);
    }

    /** The structural reader refuses what is not one well-formed JSON value, so a malformed file fails the tests above. */
    @Nested
    @DisplayName("Minimal structural JSON reader")
    class Reader {

        @Test
        @DisplayName("Should read objects, arrays, strings with escapes, literals and numbers")
        void shouldReadEveryValueKind() {
            var parsed = MinimalJson.parse(
                    " { \"a\" : [ true , false , 12 , -1.5e2 , \"x\\\"y\\\\z\\u0041\\n\" ] , \"b\" : { } , \"c\" : [ ] } ");

            assertEquals(Map.of("a", List.of(true, false, new BigDecimal("12"), new BigDecimal("-1.5e2"), "x\"y\\zA\n"),
                    "b", Map.of(), "c", List.of()), parsed, "Every value kind is read structurally");
        }

        @ParameterizedTest(name = "refuses {0}")
        @ValueSource(strings = {"", "[", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "{a:1}", "[1 2]", "\"open",
                "[\"a\"] trailing", "{\"a\":1}{}", "tru", "[01]", "{\"a\":1,\"a\":2}", "\"bad\\q\"", "null",
                "\"\\u+06f\"", "\"\\u-06f\"", "\"\\u00g1\"", "\"\\u06f\""})
        @DisplayName("Should refuse malformed input")
        void shouldRefuseMalformedInput(String input) {
            assertThrows(IllegalArgumentException.class, () -> MinimalJson.parse(input),
                    "Malformed input is refused: " + input);
        }

        @Test
        @DisplayName("Should read a unicode escape of four hexadecimal digits of either case")
        void shouldReadUnicodeEscape() {
            assertAll(
                    () -> assertEquals("o", MinimalJson.parse("\"\\u006f\""), "lower-case digits"),
                    () -> assertEquals("O", MinimalJson.parse("\"\\u004F\""), "upper-case digits"));
        }
    }

    /**
     * A recursive-descent reader of exactly one JSON value (RFC 8259) into {@link Map} (insertion order, duplicate keys
     * refused), {@link List}, {@link String}, {@link Boolean} and {@link BigDecimal}. {@code null} is refused: the
     * metadata files hold none, and a {@code null} cannot stand in an expected {@link Map#of} value.
     */
    static final class MinimalJson {

        private final String text;

        private int position;

        private MinimalJson(String text) {
            this.text = text;
        }

        /**
         * Parses one JSON value that fills the whole text, whitespace aside.
         *
         * @param text the text to parse
         * @return the parsed value
         * @throws IllegalArgumentException if the text is not one well-formed JSON value
         */
        static Object parse(String text) {
            var reader = new MinimalJson(text);
            var value = reader.value();
            reader.skipWhitespace();
            if (reader.position != text.length()) {
                throw reader.failure("text after the value");
            }
            return value;
        }

        private Object value() {
            skipWhitespace();
            if (position >= text.length()) {
                throw failure("a value expected");
            }
            return switch (text.charAt(position)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                default -> number();
            };
        }

        private Map<String, Object> object() {
            position++;
            var members = new LinkedHashMap<String, Object>();
            skipWhitespace();
            if (consume('}')) {
                return members;
            }
            do {
                skipWhitespace();
                if (position >= text.length() || text.charAt(position) != '"') {
                    throw failure("a member name expected");
                }
                var name = string();
                skipWhitespace();
                expect(':');
                if (members.put(name, value()) != null) {
                    throw failure("duplicate member " + name);
                }
                skipWhitespace();
            } while (consume(','));
            expect('}');
            return members;
        }

        private List<Object> array() {
            position++;
            var elements = new ArrayList<>();
            skipWhitespace();
            if (consume(']')) {
                return elements;
            }
            do {
                elements.add(value());
                skipWhitespace();
            } while (consume(','));
            expect(']');
            return elements;
        }

        private String string() {
            position++;
            var result = new StringBuilder();
            while (position < text.length()) {
                var c = text.charAt(position++);
                if (c == '"') {
                    return result.toString();
                }
                if (c < 0x20) {
                    throw failure("a control character in a string");
                }
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (position >= text.length()) {
                    break;
                }
                var escaped = text.charAt(position++);
                switch (escaped) {
                    case '"', '\\', '/' -> result.append(escaped);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (position + 4 > text.length()) {
                            throw failure("an incomplete unicode escape");
                        }
                        var digits = text.substring(position, position + 4);
                        // JSON requires exactly four hexadecimal digits; Integer.parseInt would accept a sign
                        if (!digits.chars().allMatch(MinimalJson::isHexDigit)) {
                            throw failure("an invalid unicode escape");
                        }
                        result.append((char) Integer.parseInt(digits, 16));
                        position += 4;
                    }
                    default -> throw failure("an invalid escape \\" + escaped);
                }
            }
            throw failure("an unterminated string");
        }

        /** Whether a character is an ASCII hexadecimal digit: {@code 0-9}, {@code a-f} or {@code A-F}. */
        private static boolean isHexDigit(int c) {
            return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
        }

        private Boolean literal(String word, Boolean value) {
            if (!text.startsWith(word, position)) {
                throw failure("the literal " + word + " expected");
            }
            position += word.length();
            return value;
        }

        private BigDecimal number() {
            var start = position;
            consume('-');
            // a leading zero stands alone, any other integer part is one or more digits
            if (!consume('0') && !digits()) {
                throw failure("a value expected");
            }
            if (consume('.') && !digits()) {
                throw failure("digits after the decimal point expected");
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                if (!digits()) {
                    throw failure("digits of the exponent expected");
                }
            }
            return new BigDecimal(text.substring(start, position));
        }

        private boolean digits() {
            var start = position;
            while (position < text.length() && Character.isDigit(text.charAt(position))
                    && text.charAt(position) < 0x80) {
                position++;
            }
            return position > start;
        }

        private void skipWhitespace() {
            while (position < text.length() && " \t\n\r".indexOf(text.charAt(position)) >= 0) {
                position++;
            }
        }

        private boolean consume(char expected) {
            if (position < text.length() && text.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void expect(char expected) {
            if (!consume(expected)) {
                throw failure("'" + expected + "' expected");
            }
        }

        private IllegalArgumentException failure(String reason) {
            return new IllegalArgumentException("Malformed JSON at offset " + position + ": " + reason);
        }
    }
}
