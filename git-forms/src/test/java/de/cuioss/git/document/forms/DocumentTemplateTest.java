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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The template of a kind (GDR-FORMS-4): data and fragments joined into one AsciiDoc file by the own template language
 * of {@code git-forms}, equal input giving byte-identical output whatever the order of the maps, and every refusal of
 * a template as the passed-on {@link StoreOutcome#MALFORMED} naming the file and the line, never content.
 * <p>
 * The templates are literals, being the contract of the language; the strings of the data and the fragments are
 * generated.
 */
@EnableGeneratorController
@DisplayName("DocumentTemplate (GDR-FORMS-4)")
class DocumentTemplateTest {

    private static final String PATH = "templates/item.adoc.tpl";

    static DocumentTemplate template(String text) {
        var result = DocumentTemplate.parse(PATH, text.getBytes(StandardCharsets.UTF_8));
        assertTrue(result.isOk(), () -> "the template is read: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the template");
    }

    private static String word() {
        return Generators.letterStrings(4, 12).next();
    }

    private static String render(String template, Map<String, ?> data) {
        return rendered(template(template), data, Map.of());
    }

    /** Renders and asserts that the rendering succeeded; the output. */
    static String rendered(DocumentTemplate template, Map<String, ?> data, Map<String, String> fragments) {
        var result = template.render(data, fragments);
        assertTrue(result.isOk(), () -> "the template renders: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the output");
    }

    @Nested
    @DisplayName("Rendering")
    class Rendering {

        @Test
        @DisplayName("GDR-FORMS-4: data and fragments are joined into one AsciiDoc file")
        void joinsDataAndFragments() {
            var title = word();
            var summary = word();
            var template = template("""
                    = {{title}}

                    {{>summary}}
                    """);

            var output = rendered(template, Map.of("title", title), Map.of("summary", summary + "\n\n"));

            assertEquals("= " + title + "\n\n" + summary + "\n", output,
                    "the value and the fragment, without the trailing line feeds of the fragment");
        }

        @Test
        @DisplayName("GDR-FORMS-4: a dotted name, numbers, booleans and an absent value")
        void scalars() {
            var team = word();
            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            data.put("owner", Map.of("team", team));
            data.put("count", 42L);
            data.put("big", new BigInteger("123456789012345678901234567890"));
            data.put("ratio", new BigDecimal("1.50"));
            data.put("flag", true);
            data.put("none", null);
            data.put("list", List.of(word()));

            var output = render("{{owner.team}}|{{count}}|{{big}}|{{ratio}}|{{flag}}|{{none}}|{{missing}}|{{list}}"
                    + "|{{owner}}|{{owner.missing.deeper}}", data);

            assertEquals(team + "|42|123456789012345678901234567890|1.50|true|||||\n", output,
                    "each value in its written form; absent, null, a sequence and a mapping insert nothing");
        }

        @Test
        @DisplayName("GDR-FORMS-4: a section repeats per item and looks names up outward")
        void sections() {
            var heading = word();
            var first = word();
            var second = word();
            Map<String, Object> data = Map.of("heading", heading, "items",
                    List.of(Map.of("name", first), Map.of("name", second)), "tags", List.of("a", "b"));

            var output = render("""
                    {{#items}}
                    * {{name}} ({{heading}})
                    {{/items}}
                    {{#tags}}[{{.}}]{{/tags}}
                    """, data);

            assertEquals("* " + first + " (" + heading + ")\n* " + second + " (" + heading + ")\n[a][b]\n", output,
                    "the standalone section lines leave no blank line");
        }

        @Test
        @DisplayName("GDR-FORMS-4: a section renders for a present value, an inverted section for an absent one")
        void presence() {
            var name = word();
            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            data.put("yes", true);
            data.put("no", false);
            data.put("empty", List.of());
            data.put("none", null);
            data.put("text", name);
            data.put("contact", Map.of("name", name));

            var output = render("{{#yes}}Y{{/yes}}{{#no}}N{{/no}}{{#empty}}E{{/empty}}{{#none}}0{{/none}}"
                    + "{{#missing}}M{{/missing}}{{#text}}{{.}}{{/text}}{{#contact}}<{{name}}>{{/contact}}"
                    + "|{{^no}}n{{/no}}{{^empty}}e{{/empty}}{{^none}}0{{/none}}{{^missing}}m{{/missing}}"
                    + "{{^yes}}y{{/yes}}", data);

            assertEquals("Y" + name + "<" + name + ">|ne0m\n", output, "present and absent values");
        }

        @Test
        @DisplayName("GDR-FORMS-4: a comment inserts nothing, nothing is escaped, an absent fragment is empty")
        void commentsAndEscaping() {
            var markup = "*" + word() + "* <b>&amp;</b>";

            var output = rendered(template("""
                    {{! a comment line }}
                    {{text}}{{>absent}}
                    """), Map.of("text", markup), Map.of());

            assertEquals(markup + "\n", output, "the value is inserted as it is");
        }

        @Test
        @DisplayName("GDR-FORMS-4: line ends become line feeds and the output ends in exactly one line feed")
        void lineEnds() {
            var value = word();
            var template = DocumentTemplate.parse(PATH, "a\r\nb\r{{v}}\n\n\n".getBytes(StandardCharsets.UTF_8));
            var parsed = Objects.requireNonNull(template.value());

            var output = rendered(parsed, Map.of("v", value + "\r\nx"), Map.of());
            var empty = rendered(template(""), Map.of(), Map.of());

            assertAll(
                    () -> assertEquals("a\nb\n" + value + "\nx\n", output, "line feeds only, one at the end"),
                    () -> assertEquals("\n", empty, "an empty template yields one line feed"));
        }
    }

    @Nested
    @DisplayName("Determinism")
    class Determinism {

        private static final String TEMPLATE = """
                = {{title}}
                {{#tags}}
                * {{name}}: {{score}}
                {{/tags}}
                {{>summary}}
                {{>detail}}
                """;

        @Test
        @DisplayName("GDR-FORMS-4: equal input gives byte-identical output, rendered twice")
        void renderedTwice() {
            var template = template(TEMPLATE);
            Map<String, Object> data = Map.of("title", word(), "tags", List.of(Map.of("name", word(), "score", 3L)));
            Map<String, String> fragments = Map.of("summary", word(), "detail", word());

            var first = rendered(template, data, fragments).getBytes(StandardCharsets.UTF_8);
            var second = rendered(template(TEMPLATE), data, fragments).getBytes(StandardCharsets.UTF_8);

            assertArrayEquals(first, second, "the same bytes");
        }

        @Test
        @DisplayName("GDR-FORMS-4: the insertion order of the data and the fragments does not change the output")
        void insertionOrder() {
            var template = template(TEMPLATE);
            List<Map.Entry<String, Object>> entries = new ArrayList<>(List.of(Map.entry("title", word()),
                    Map.entry("tags", List.of(Map.of("name", word(), "score", 1L))), Map.entry("other", word())));
            List<Map.Entry<String, String>> fragmentEntries = new ArrayList<>(
                    List.of(Map.entry("summary", word()), Map.entry("detail", word())));

            var forward = rendered(template, ordered(entries), ordered(fragmentEntries));
            Collections.reverse(entries);
            Collections.reverse(fragmentEntries);
            var reversed = rendered(template, ordered(entries), ordered(fragmentEntries));

            assertEquals(forward, reversed, "the same output");
        }

        private static <V> Map<String, V> ordered(List<Map.Entry<String, V>> entries) {
            Map<String, V> map = new LinkedHashMap<>();
            entries.forEach(entry -> map.put(entry.getKey(), entry.getValue()));
            return map;
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        static Stream<Arguments> refused() {
            String secret = "s3cret-content";
            return Stream.of(
                    Arguments.of(secret + "\n{{}}", "line 2: an empty tag"),
                    Arguments.of(secret + " {{title", "line 1: a tag that is not closed"),
                    Arguments.of("{{{" + secret + "}}}", "line 1: a tag the template language does not support"),
                    Arguments.of("{{&" + secret + "}}", "line 1: a tag the template language does not support"),
                    Arguments.of("{{=<% %>=}}" + secret, "line 1: a tag the template language does not support"),
                    Arguments.of("{{" + secret + " x}}",
                            "line 1: a name that is not of the form [A-Za-z0-9_-]+, separated by dots"),
                    Arguments.of("{{#a..b}}{{/a..b}}",
                            "line 1: a name that is not of the form [A-Za-z0-9_-]+, separated by dots"),
                    Arguments.of("{{>a.b}}", "line 1: a fragment name that is not of the form [A-Za-z0-9_-]+"),
                    Arguments.of("{{#a}}\n" + secret + "\n{{/b}}", "line 3: a closing tag that does not match its section"),
                    Arguments.of("\n{{/a}}", "line 2: a closing tag without a section"),
                    Arguments.of("{{#a}}\n" + secret, "line 1: a section that is not closed"),
                    Arguments.of("{{#a}}{{/a}}" + "{{#a}}".repeat(DocumentTemplate.MAX_NESTING + 1)
                            + "{{/a}}".repeat(DocumentTemplate.MAX_NESTING + 1),
                            "line 1: sections nested deeper than 128 levels"));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("refused")
        @DisplayName("GDR-FORMS-4: a template the language refuses is MALFORMED, naming the line, never content")
        void malformed(String text, String problem) {
            var result = DocumentTemplate.parse(PATH, text.getBytes(StandardCharsets.UTF_8));

            assertAll(
                    () -> assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                            PATH + ": " + problem)), result, "the file, the line and the problem"),
                    () -> assertFalse(Objects.requireNonNull(result.detail()).contains("s3cret"),
                            "no content of the template"));
        }

        @ParameterizedTest(name = "refused name [{0}]")
        @MethodSource("refusedNames")
        @DisplayName("GDR-FORMS-4: a section name with an empty segment or a leading or trailing dot is MALFORMED")
        void refusedName(String name) {
            var result = DocumentTemplate.parse(PATH,
                    ("{{#" + name + "}}{{/" + name + "}}").getBytes(StandardCharsets.UTF_8));

            assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                            PATH + ": line 1: a name that is not of the form [A-Za-z0-9_-]+, separated by dots")), result,
                    "the name is refused");
        }

        static Stream<String> refusedNames() {
            return Stream.of("", ".", "a.", ".a", "a..b", "a.b.", "a b", "a.b!");
        }

        @ParameterizedTest(name = "accepted name [{0}]")
        @MethodSource("acceptedNames")
        @DisplayName("GDR-FORMS-4: a name of one or more segments of [A-Za-z0-9_-] separated by single dots is read")
        void acceptedName(String name) {
            var output = rendered(template("{{#" + name + "}}x{{/" + name + "}}"), Map.of(), Map.of());

            assertEquals("\n", output, "an absent section renders nothing");
        }

        static Stream<String> acceptedNames() {
            return Stream.of("a", "a.b", "a-b", "a_b", "A9.b-c_d.0", "-._");
        }

        @Test
        @DisplayName("GDR-FORMS-4: the deepest allowed nesting is read")
        void deepestNesting() {
            var text = "{{#a}}".repeat(DocumentTemplate.MAX_NESTING) + "x" + "{{/a}}".repeat(DocumentTemplate.MAX_NESTING);

            assertEquals("\n", rendered(template(text), Map.of(), Map.of()), "an absent section renders nothing");
        }
    }

    @Nested
    @DisplayName("Limits of a rendering")
    class Limits {

        private static final int LEVELS = 40;

        private static String nested(String body) {
            return "{{#items}}".repeat(LEVELS) + body + "{{/items}}".repeat(LEVELS);
        }

        @ParameterizedTest(name = "innermost body [{0}]")
        @MethodSource("bodies")
        @DisplayName("GDR-FORMS-4: sections nested over the same two items stop at the operation limit, never content")
        void exponentialNesting(String body) {
            var template = template(nested(body));

            var result = template.render(Map.of("items", List.of(0L, 1L)), Map.of());

            assertAll(
                    () -> assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                            "template: rendering stopped, more than " + DocumentTemplate.MAX_RENDER_OPERATIONS
                                    + " operations")), result, "the limit is named"),
                    () -> assertFalse(Objects.requireNonNull(result.detail()).contains("items"),
                            "no content of the template or the data"));
        }

        static Stream<String> bodies() {
            return Stream.of("", "{{.}}");
        }

        @Test
        @DisplayName("GDR-FORMS-4: a deep nesting over a single item still renders")
        void deepNestingOverOneItem() {
            var output = rendered(template(nested("{{.}}")), Map.of("items", List.of(7L)), Map.of());

            assertEquals("7\n", output, "one combination");
        }

        @Test
        @DisplayName("GDR-FORMS-4: a list of ordinary size still renders")
        void ordinaryList() {
            List<Long> items = new ArrayList<>();
            for (long index = 0; index < 10_000; index++) {
                items.add(index);
            }

            var output = rendered(template("{{#items}}{{.}},{{/items}}"), Map.of("items", items), Map.of());

            assertTrue(output.startsWith("0,1,2,") && output.endsWith("9999,\n"), "every item");
        }

        @Test
        @DisplayName("GDR-FORMS-4: an output larger than the character limit stops, naming the limit")
        void outputLimit() {
            var fragment = "x".repeat(1024 * 1024);
            var template = template("{{#items}}{{>big}}{{/items}}");
            List<Long> items = new ArrayList<>();
            for (long index = 0; index <= DocumentTemplate.MAX_OUTPUT_CHARS / fragment.length(); index++) {
                items.add(index);
            }

            var result = template.render(Map.of("items", items), Map.of("big", fragment));

            assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                    "template: rendering stopped, output larger than " + DocumentTemplate.MAX_OUTPUT_CHARS
                            + " characters")), result, "the limit is named");
        }

        @Test
        @DisplayName("GDR-FORMS-4: a template that is not UTF-8 or too large is MALFORMED")
        void notUtf8OrTooLarge() {
            var notUtf8 = DocumentTemplate.parse(PATH, new byte[]{(byte) 0xC3, (byte) 0x28});
            var tooLarge = DocumentTemplate.parse(PATH, new byte[DocumentTemplate.MAX_TEMPLATE_BYTES + 1]);
            var largest = DocumentTemplate.parse(PATH, "a".repeat(DocumentTemplate.MAX_TEMPLATE_BYTES)
                    .getBytes(StandardCharsets.UTF_8));

            assertAll(
                    () -> assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, PATH + ": not UTF-8")),
                            notUtf8, "not UTF-8"),
                    () -> assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                            PATH + ": too large, more than 1048576 bytes")), tooLarge, "too large"),
                    () -> assertTrue(largest.isOk(), "the bound itself is read"));
        }
    }

    @Test
    @DisplayName("GDR-GEN-3: a null argument and data outside the data model are refused")
    void preconditions() {
        var template = template("{{a}}");
        Map<String, Object> data = Map.of();
        Map<String, String> fragments = Map.of();
        Map<String, Object> outside = Map.of("a", 1.5d);
        String nullPath = nullValue();
        byte[] nullContent = nullValue();
        Map<String, Object> nullData = nullValue();
        Map<String, String> nullFragments = nullValue();

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> DocumentTemplate.parse(nullPath, new byte[0])),
                () -> assertThrows(NullPointerException.class, () -> DocumentTemplate.parse(PATH, nullContent)),
                () -> assertThrows(NullPointerException.class, () -> template.render(nullData, fragments)),
                () -> assertThrows(NullPointerException.class, () -> template.render(data, nullFragments)),
                () -> assertThrows(IllegalArgumentException.class, () -> template.render(outside, fragments)));
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> @Nullable T nullValue() {
        return null;
    }
}
