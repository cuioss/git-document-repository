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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The data layout (GDR-FORMS-3): the paths of a per-document kind (document, fragment, split part) and of a numbered
 * kind (entry, counter), the refusal of a name segment that is empty, holds a {@code /} or starts with {@code .}, the
 * split of the data by {@value FormSchema#SPLIT} and the join back.
 * <p>
 * Collections, ULIDs, names and numbers are generated; the file names {@code document.yaml}, {@code narrative},
 * {@code .counter} and the refused segments are the contract and kept as literals. The schema is that of
 * {@link SchemaValidatorTest}, whose property {@code details} is split.
 */
@EnableGeneratorController
@DisplayName("DocumentLayout (GDR-FORMS-3)")
class DocumentLayoutTest {

    private static final TypedGenerator<String> NAMES =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz0123456789", 8, 20));

    /** The segments every check refuses. */
    private static final List<String> REFUSED = List.of("", "a/b", ".hidden", ".", "..", "/");

    private static String name() {
        return NAMES.next();
    }

    private static Kind perDocument(String collection) {
        return new Kind(name(), name(), collection, name(), name(), name(), null);
    }

    private static Kind numbered(String collection) {
        return new Kind(name(), name(), collection, name(), null, name(), new Numbering("R-", 3));
    }

    private static DocumentLayout layout(Kind kind) {
        return new DocumentLayout(kind, SchemaValidatorTest.schema(SchemaValidatorTest.SCHEMA));
    }

    private static <T> void assertInvalid(String argument, FormsResult<T> result) {
        assertEquals(FormsResult.of(FormsOutcome.INVALID, argument + ": not a name segment"), result,
                "the " + argument + " is refused, naming the argument");
    }

    @Nested
    @DisplayName("Paths")
    class Paths {

        @Test
        @DisplayName("GDR-FORMS-3: a per-document kind stores data, fragments and parts under <collection>/<ulid>/")
        void perDocumentPaths() {
            var collection = name();
            var ulid = name();
            var fragment = name();
            var layout = layout(perDocument(collection));

            assertAll(
                    () -> assertEquals(FormsResult.ok(collection + "/" + ulid + "/document.yaml"), layout.document(ulid),
                            "the structured fields"),
                    () -> assertEquals(FormsResult.ok(collection + "/" + ulid + "/narrative/" + fragment + ".adoc"),
                            layout.narrative(ulid, fragment), "a free-text fragment"),
                    () -> assertEquals(FormsResult.ok(collection + "/" + ulid + "/details.yaml"),
                            layout.part(ulid, "details"), "a split part"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: a numbered kind stores <collection>/<number>.yaml and <collection>/.counter")
        void numberedPaths() {
            var collection = name();
            var number = "R-" + Generators.integers(100, 999).next();
            var layout = layout(numbered(collection));

            assertAll(
                    () -> assertEquals(FormsResult.ok(collection + "/" + number + ".yaml"), layout.entry(number),
                            "the entry file"),
                    () -> assertEquals(collection + "/.counter", layout.counter(), "the counter file"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: a segment that is empty, holds a slash or starts with a dot is INVALID")
        void refusedSegments() {
            var perDocument = layout(perDocument(name()));
            var numbered = layout(numbered(name()));
            var valid = name();

            assertAll(REFUSED.stream().<Executable>map(segment -> () -> assertAll("segment '" + segment + "'",
                    () -> assertInvalid("ulid", perDocument.document(segment)),
                    () -> assertInvalid("ulid", perDocument.narrative(segment, valid)),
                    () -> assertInvalid("name", perDocument.narrative(valid, segment)),
                    () -> assertInvalid("ulid", perDocument.part(segment, "details")),
                    () -> assertInvalid("number", numbered.entry(segment)))));
        }

        @Test
        @DisplayName("GDR-FORMS-3: a part of a property that is not split is INVALID")
        void notSplitProperty() {
            var layout = layout(perDocument(name()));

            assertAll(Stream.of("title", "tags", name()).<Executable>map(
                    property -> () -> assertInvalid("property", layout.part(name(), property))));
        }

        @Test
        @DisplayName("GDR-FORMS-3: per-document paths on a numbered kind and entry paths on a per-document kind are refused")
        void wrongKind() {
            var perDocument = layout(perDocument(name()));
            var numbered = layout(numbered(name()));
            var ulid = name();
            var fragment = name();
            var number = name();

            assertAll(
                    () -> assertThrows(IllegalStateException.class, () -> numbered.document(ulid)),
                    () -> assertThrows(IllegalStateException.class, () -> numbered.narrative(ulid, fragment)),
                    () -> assertThrows(IllegalStateException.class, () -> numbered.part(ulid, "details")),
                    () -> assertThrows(IllegalStateException.class, () -> perDocument.entry(number)),
                    () -> assertThrows(IllegalStateException.class, perDocument::counter));
        }

        @Test
        @DisplayName("GDR-FORMS-3: a collection that is no name segment is refused")
        void refusedCollection() {
            var schema = SchemaValidatorTest.schema(SchemaValidatorTest.SCHEMA);

            assertAll(REFUSED.stream().<Executable>map(collection -> {
                var kind = perDocument(collection);
                return () -> assertThrows(IllegalArgumentException.class, () -> new DocumentLayout(kind, schema),
                        "collection '" + collection + "'");
            }));
        }

        @Test
        @DisplayName("GDR-FORMS-3: a split property stored as document.yaml is refused, an ordinary part is not")
        void splitPropertyNamedDocument() {
            var kind = perDocument(name());
            var clashing = SchemaValidatorTest.schema("""
                    { "properties": { "document": { "type": "object", "gdr-split": true } } }
                    """);
            var ordinary = SchemaValidatorTest.schema(SchemaValidatorTest.SCHEMA);
            var ulid = name();

            var refused = assertThrows(IllegalArgumentException.class, () -> new DocumentLayout(kind, clashing),
                    "the part would overwrite the document");
            assertAll(
                    () -> assertEquals("gdr-split: a split property stored as document.yaml", refused.getMessage(),
                            "the reason names no value"),
                    () -> assertEquals(FormsResult.ok(kind.collection() + "/" + ulid + "/details.yaml"),
                            new DocumentLayout(kind, ordinary).part(ulid, "details"), "details stays a valid part"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: a split property that is no name segment is refused")
        void splitPropertyNoSegment() {
            var kind = perDocument(name());
            var schema = SchemaValidatorTest.schema("""
                    { "properties": { "details/summary": { "type": "object", "gdr-split": true } } }
                    """);

            var refused = assertThrows(IllegalArgumentException.class, () -> new DocumentLayout(kind, schema),
                    "the part has no path");
            assertEquals("gdr-split: a split property that is no name segment", refused.getMessage(),
                    "the reason names no value");
        }
    }

    @Nested
    @DisplayName("Split and join")
    class SplitAndJoin {

        private Map<String, @Nullable Object> data() {
            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            data.put("title", name());
            data.put("details", new LinkedHashMap<>(Map.of("reason", name())));
            data.put("owner", name());
            return data;
        }

        @Test
        @DisplayName("GDR-FORMS-3: a property marked gdr-split becomes a part, and the join restores the data")
        void splitAndJoin() {
            var layout = layout(perDocument(name()));
            var data = data();

            var split = layout.split(data);
            var joined = layout.join(split.document(), split.parts());

            assertAll(
                    () -> assertEquals(List.of("title", "owner"), List.copyOf(split.document().keySet()),
                            "the document keeps every other property in order"),
                    () -> assertEquals(Map.of("details", data.get("details")), split.parts(), "the split part"),
                    () -> assertEquals(FormsResult.ok(data), joined, "the join restores the data"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: a split property that is no object, or absent, stays in the document")
        void notAnObject() {
            var layout = layout(perDocument(name()));
            var data = data();
            data.put("details", name());

            var split = layout.split(data);
            var withoutPart = layout.split(Map.of("title", name()));

            assertAll(
                    () -> assertEquals(data, split.document(), "the value stays in the document"),
                    () -> assertEquals(Map.of(), split.parts(), "no part"),
                    () -> assertEquals(Map.of(), withoutPart.parts(), "no part for an absent property"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: a document that also holds a split part is MALFORMED, a foreign part is refused")
        void refusedJoin() {
            var layout = layout(perDocument(name()));
            Map<String, Map<String, ?>> part = Map.of("details", Map.of("reason", name()));
            Map<String, Map<String, ?>> foreign = Map.of("title", Map.of());
            Map<String, @Nullable Object> empty = Map.of();

            assertAll(
                    () -> assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                                    "document.yaml: holds a property that is stored as a part")),
                            layout.join(Map.of("details", Map.of()), part), "the part is held twice"),
                    () -> assertThrows(IllegalArgumentException.class, () -> layout.join(empty, foreign),
                            "a part of a property that is not split"));
        }

        @Test
        @DisplayName("GDR-FORMS-3: the split is a copy, unaffected by a later change of the data")
        void copy() {
            var layout = layout(perDocument(name()));
            var data = data();
            var split = layout.split(data);
            var document = split.document();
            var key = name();
            var value = name();

            data.clear();

            assertFalse(document.isEmpty(), "the split keeps its document");
            assertThrows(UnsupportedOperationException.class, () -> document.put(key, value),
                    "the document of a split is unmodifiable");
        }
    }

    @Test
    @DisplayName("GDR-GEN-3: a null argument is refused")
    void nullArguments() {
        var kind = perDocument(name());
        var layout = layout(kind);
        var schema = SchemaValidatorTest.schema(SchemaValidatorTest.SCHEMA);
        var ulid = name();
        Map<String, @Nullable Object> document = Map.of();
        Map<String, Map<String, ?>> parts = Map.of();
        Kind nullKind = nullValue();
        FormSchema nullSchema = nullValue();
        String nullText = nullValue();
        Map<String, @Nullable Object> nullDocument = nullValue();
        Map<String, Map<String, ?>> nullParts = nullValue();

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> new DocumentLayout(nullKind, schema)),
                () -> assertThrows(NullPointerException.class, () -> new DocumentLayout(kind, nullSchema)),
                () -> assertThrows(NullPointerException.class, () -> layout.document(nullText)),
                () -> assertThrows(NullPointerException.class, () -> layout.narrative(ulid, nullText)),
                () -> assertThrows(NullPointerException.class, () -> layout.part(ulid, nullText)),
                () -> assertThrows(NullPointerException.class, () -> layout.split(nullDocument)),
                () -> assertThrows(NullPointerException.class, () -> layout.join(nullDocument, parts)),
                () -> assertThrows(NullPointerException.class, () -> layout.join(document, nullParts)));
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    @SuppressWarnings("unchecked")
    private static <T> T nullValue() {
        return (T) null;
    }
}
