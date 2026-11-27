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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import java.util.stream.Stream;


import de.cuioss.git.document.store.Ulid;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The parsing of a typed link (GDR-FORMS-8): the document link {@code <alias>:<kind>/<id>[#<fragment>]} and the catalog
 * link {@code <catalog>:<id>}, the fragment kept as written, and every malformed form refused as
 * {@link FormsOutcome#INVALID} with a detail that names the rule, never the text.
 * <p>
 * The link forms and the rules are the contract and therefore literals; the ids of documents are generated ULIDs.
 */
@DisplayName("TypedLink (GDR-FORMS-8)")
class TypedLinkTest {

    private static final String ALIAS_RULE = "link: alias: not of the form [a-z0-9-]+";
    private static final String KIND_RULE = "link: kind: not of the form [a-z0-9-]+";
    private static final String CATALOG_RULE = "link: catalog: not of the form [a-z0-9-]+";
    private static final String ID_RULE = "link: id: not of the form [A-Za-z0-9_-]+";

    private static TypedLink parsed(String text) {
        FormsResult<TypedLink> result = TypedLink.parse(text);
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value());
    }

    @Test
    @DisplayName("GDR-FORMS-8: a document link names the alias, the kind and the id, without a fragment")
    void documentLink() {
        String id = Ulid.next();

        var link = assertInstanceOf(TypedLink.DocumentLink.class, parsed("register:assessment/" + id));

        assertAll("the components",
                () -> assertEquals("register", link.alias(), "alias"),
                () -> assertEquals("assessment", link.kind(), "kind"),
                () -> assertEquals(id, link.id(), "id"),
                () -> assertNull(link.fragment(), "no fragment"));
    }

    @Test
    @DisplayName("GDR-FORMS-8: a document link of a numbered entry takes its running number as the id")
    void documentLinkToARunningNumber() {
        var link = assertInstanceOf(TypedLink.DocumentLink.class, parsed("register:risk/R_-042"));

        assertEquals(new TypedLink.DocumentLink("register", "risk", "R_-042", null), link, "the running number");
    }

    @Test
    @DisplayName("GDR-FORMS-8: the fragment is kept as written, everything after the first #, and never interpreted")
    void fragmentKept() {
        var link = assertInstanceOf(TypedLink.DocumentLink.class,
                parsed("register:risk/R-042#narrative/summary#part:2 ü"));

        assertAll("the fragment",
                () -> assertEquals("R-042", link.id(), "the id ends at the first #"),
                () -> assertEquals("narrative/summary#part:2 ü", link.fragment(), "kept as written"));
    }

    @Test
    @DisplayName("GDR-FORMS-8: a catalog link names the catalog and the id")
    void catalogLink() {
        assertEquals(new TypedLink.CatalogLink("team", "payments"), parsed("team:payments"), "the catalog entry");
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                Arguments.of("", TypedLink.NEITHER_FORM),
                Arguments.of("payments", TypedLink.NEITHER_FORM),
                Arguments.of(":payments", CATALOG_RULE),
                Arguments.of("Team:payments", CATALOG_RULE),
                Arguments.of("te am:payments", CATALOG_RULE),
                Arguments.of("team:", ID_RULE),
                Arguments.of("team:pay ments", ID_RULE),
                Arguments.of("team:payments#part", ID_RULE),
                Arguments.of("team:pay.ments", ID_RULE),
                Arguments.of("team:pay:ments", ID_RULE),
                Arguments.of("team:pay\u0007ments", ID_RULE),
                Arguments.of(":risk/R-042", ALIAS_RULE),
                Arguments.of("Register:risk/R-042", ALIAS_RULE),
                Arguments.of("register:/R-042", KIND_RULE),
                Arguments.of("register:Risk/R-042", KIND_RULE),
                Arguments.of("register:ri.sk/R-042", KIND_RULE),
                Arguments.of("register:risk/", ID_RULE),
                Arguments.of("register:risk/#part", ID_RULE),
                Arguments.of("register:risk/R-042/v1", ID_RULE),
                Arguments.of("register:risk//R-042", ID_RULE),
                Arguments.of("register:risk/R\t042", ID_RULE),
                Arguments.of("register:risk/R-042#", "link: fragment: empty"),
                Arguments.of("register:risk/R-042#line\nbreak", "link: fragment: a control character"),
                Arguments.of("register:risk/R-042#tab\tbed", "link: fragment: a control character"));
    }

    @ParameterizedTest(name = "rule: {1}")
    @MethodSource("malformed")
    @DisplayName("GDR-FORMS-8: a text of neither form is INVALID, the detail naming the rule and never the text")
    void malformedRefused(String text, String rule) {
        FormsResult<TypedLink> result = TypedLink.parse(text);

        assertAll("refused",
                () -> assertEquals(FormsOutcome.INVALID, result.outcome(), "invalid"),
                () -> assertEquals(rule, result.detail(), "the rule"),
                () -> assertNull(result.value(), "no link"));
        if (!text.isEmpty()) {
            assertFalse(Objects.requireNonNull(result.detail()).contains(text), "the text is not in the detail");
        }
    }

    @Test
    @DisplayName("GDR-FORMS-8: a link built directly checks its components, naming the rule only")
    void constructorsCheckComponents() {
        var document = assertThrows(IllegalArgumentException.class,
                () -> new TypedLink.DocumentLink("register", "risk", "R/042", null));
        var catalog = assertThrows(IllegalArgumentException.class, () -> new TypedLink.CatalogLink("team", ""));
        var fragment = assertThrows(IllegalArgumentException.class,
                () -> new TypedLink.DocumentLink("register", "risk", "R-042", ""));

        assertAll("the rules",
                () -> assertEquals(ID_RULE, document.getMessage(), "document id"),
                () -> assertEquals(ID_RULE, catalog.getMessage(), "catalog id"),
                () -> assertEquals("link: fragment: empty", fragment.getMessage(), "fragment"));
    }

    @Test
    @DisplayName("GDR-FORMS-8: parse refuses a null text")
    void nullRefused() {
        String text = nothing();

        assertThrows(NullPointerException.class, () -> TypedLink.parse(text));
    }

    /** A {@code null} typed as a non-null text, without a cast. */
    private static <T> @Nullable T nothing() {
        return null;
    }
}
