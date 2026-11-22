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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;


import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The keywords that {@link FormSchema} walks for sub-schemas are exactly the locations the meta-schema of Draft 2020-12
 * defines (GDR-FORMS-2), plus the two documented keywords of Draft-07 {@code definitions} and {@code additionalItems}.
 * <p>
 * The locations are derived from the meta-schema {@code dev.harrel:json-schema} ships as the class path resource
 * {@value #META_SCHEMA}, read through the class loader without network access: in the {@code properties} of every
 * vocabulary meta-schema the root references from {@code allOf}, a keyword whose schema is the dynamic reference
 * {@value #META} holds one sub-schema, an array of such items a list, an object with such {@code additionalProperties}
 * a map. A library or dialect change that adds a location therefore fails this build instead of leaving the location
 * unchecked, and a location removed from the lists of {@link FormSchema} fails it as well.
 */
@DisplayName("Sub-schema keyword locations of FormSchema follow the meta-schema (GDR-FORMS-2)")
class FormSchemaKeywordLocationsTest {

    /** The bundled meta-schema of Draft 2020-12 on the class path, the one native-image metadata lists. */
    private static final String META_SCHEMA = "draft2020-12.json";

    /** The dynamic reference by which a vocabulary meta-schema declares a sub-schema. */
    private static final String META = "#meta";

    /** The keyword of Draft-07 that the lists keep as a map of sub-schemas beyond the dialect. */
    private static final String DEFINITIONS = "definitions";

    /** The keyword of Draft-07 that the lists keep as one sub-schema beyond the dialect. */
    private static final String ADDITIONAL_ITEMS = "additionalItems";

    private static final String PROPERTIES = "properties";
    private static final String DEFS = "$defs";

    private static Map<?, ?> root;
    private static List<Map<?, ?>> vocabularies;
    private static Set<String> values;
    private static Set<String> lists;
    private static Set<String> maps;

    @BeforeAll
    static void deriveLocations() throws IOException {
        root = readMetaSchema();
        vocabularies = vocabularies(root);
        values = new TreeSet<>();
        lists = new TreeSet<>();
        maps = new TreeSet<>();
        for (Map<?, ?> vocabulary : vocabularies) {
            for (Map.Entry<?, ?> property : asMap(vocabulary.get(PROPERTIES)).entrySet()) {
                String keyword = (String) property.getKey();
                Map<?, ?> keywordSchema = resolveLocal(vocabulary, property.getValue());
                if (isSubSchema(keywordSchema)) {
                    values.add(keyword);
                } else if ("array".equals(keywordSchema.get("type")) && isSubSchema(keywordSchema.get("items"))) {
                    lists.add(keyword);
                } else if ("object".equals(keywordSchema.get("type"))
                        && isSubSchema(keywordSchema.get("additionalProperties"))) {
                    maps.add(keyword);
                }
            }
        }
    }

    @Test
    @DisplayName("Every vocabulary the root meta-schema references is read, and each holds keywords")
    void readsEveryVocabulary() {
        var references = (List<?>) root.get("allOf");

        assertAll("Vocabularies of " + META_SCHEMA,
                () -> assertEquals(references.size(), vocabularies.size(),
                        "Every $ref of allOf must resolve to a bundled vocabulary meta-schema"),
                () -> assertFalse(values.isEmpty(), "The meta-schema must define keywords of one sub-schema"),
                () -> assertFalse(lists.isEmpty(), "The meta-schema must define keywords of a list of sub-schemas"),
                () -> assertFalse(maps.isEmpty(), "The meta-schema must define keywords of a map of sub-schemas"));
    }

    @Test
    @DisplayName("SCHEMA_VALUES is the derived one-sub-schema keywords plus additionalItems")
    void schemaValuesMatchMetaSchema() {
        assertEquals(union(values, ADDITIONAL_ITEMS), new TreeSet<>(FormSchema.SCHEMA_VALUES),
                "FormSchema.SCHEMA_VALUES must hold every location of one sub-schema the meta-schema defines, and"
                        + " beyond them only " + ADDITIONAL_ITEMS);
    }

    @Test
    @DisplayName("SCHEMA_LISTS is the derived list-of-sub-schemas keywords")
    void schemaListsMatchMetaSchema() {
        assertEquals(lists, new TreeSet<>(FormSchema.SCHEMA_LISTS),
                "FormSchema.SCHEMA_LISTS must hold every location of a list of sub-schemas the meta-schema defines,"
                        + " and nothing beyond them");
    }

    @Test
    @DisplayName("SCHEMA_MAPS is the derived map-of-sub-schemas keywords plus definitions")
    void schemaMapsMatchMetaSchema() {
        assertEquals(union(maps, DEFINITIONS), new TreeSet<>(FormSchema.SCHEMA_MAPS),
                "FormSchema.SCHEMA_MAPS must hold every location of a map of sub-schemas the meta-schema defines, and"
                        + " beyond them only " + DEFINITIONS);
    }

    @Test
    @DisplayName("The documented extras are no keyword of any vocabulary of Draft 2020-12")
    void extrasAreNoVocabularyKeywords() {
        var vocabularyKeywords = new TreeSet<String>();
        vocabularies.forEach(vocabulary -> asMap(vocabulary.get(PROPERTIES)).keySet()
                .forEach(keyword -> vocabularyKeywords.add((String) keyword)));
        var retainedNames = asMap(root.get(PROPERTIES)).keySet();

        assertAll("Extras beyond the dialect",
                () -> assertFalse(vocabularyKeywords.contains(DEFINITIONS), DEFINITIONS + " must be no vocabulary keyword"),
                () -> assertTrue(retainedNames.contains(DEFINITIONS),
                        DEFINITIONS + " must be a name the root meta-schema retains"),
                () -> assertFalse(vocabularyKeywords.contains(ADDITIONAL_ITEMS),
                        ADDITIONAL_ITEMS + " must be no vocabulary keyword"),
                () -> assertFalse(retainedNames.contains(ADDITIONAL_ITEMS),
                        ADDITIONAL_ITEMS + " must be no name the root meta-schema retains"));
    }

    /** Reads the bundled meta-schema through the class loader and parses it within the data model of the module. */
    private static Map<?, ?> readMetaSchema() throws IOException {
        String text;
        try (InputStream in = FormSchemaKeywordLocationsTest.class.getClassLoader().getResourceAsStream(META_SCHEMA)) {
            assertNotNull(in, META_SCHEMA + " must be on the class path");
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        FormsResult<Object> read = new YamlReader().read(text);
        assertTrue(read.isOk(), () -> META_SCHEMA + " must parse: " + read.detail());
        return asMap(read.value());
    }

    /** The vocabulary meta-schemas the root references from {@code allOf}, each looked up under its {@code $id}. */
    private static List<Map<?, ?>> vocabularies(Map<?, ?> metaSchema) {
        String base = (String) metaSchema.get("$id");
        String directory = base.substring(0, base.lastIndexOf('/') + 1);
        Map<?, ?> bundled = asMap(metaSchema.get(DEFS));
        List<Map<?, ?>> found = new ArrayList<>();
        for (Object reference : (List<?>) metaSchema.get("allOf")) {
            String id = directory + asMap(reference).get("$ref");
            if (bundled.get(id) instanceof Map<?, ?> vocabulary) {
                found.add(vocabulary);
            }
        }
        return found;
    }

    /** Follows a reference of the form {@code #/$defs/<name>} within one vocabulary meta-schema. */
    private static Map<?, ?> resolveLocal(Map<?, ?> vocabulary, @Nullable Object schema) {
        if (!(schema instanceof Map<?, ?> map)) {
            return Map.of();
        }
        if (map.get("$ref") instanceof String reference && reference.startsWith("#/" + DEFS + "/")) {
            return asMap(asMap(vocabulary.get(DEFS)).get(reference.substring(("#/" + DEFS + "/").length())));
        }
        return map;
    }

    private static boolean isSubSchema(@Nullable Object schema) {
        return schema instanceof Map<?, ?> map && META.equals(map.get("$dynamicRef"));
    }

    private static Map<?, ?> asMap(@Nullable Object value) {
        return assertInstanceOf(Map.class, value, "Expected an object in " + META_SCHEMA);
    }

    private static Set<String> union(Set<String> derived, String extra) {
        var all = new TreeSet<>(derived);
        all.add(extra);
        return all;
    }
}
