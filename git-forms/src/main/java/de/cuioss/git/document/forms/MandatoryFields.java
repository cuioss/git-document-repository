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

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


import dev.harrel.jsonschema.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * The check of the keyword {@code required} of {@link SchemaValidator#required(FormSchema, Map)}: collects the JSON
 * Pointers of the missing mandatory fields of the data, walking the schema and every schema it references within the
 * file, and applying the sub-schemas of {@code unevaluatedProperties} and {@code unevaluatedItems} to the properties and
 * items {@link EvaluatedLocations} leaves unevaluated. One instance serves one check.
 */
final class MandatoryFields {

    private final FormSchema schema;
    private final EvaluatedLocations evaluated;
    private final Set<String> missing = new LinkedHashSet<>();

    private MandatoryFields(FormSchema schema) {
        this.schema = schema;
        this.evaluated = new EvaluatedLocations(schema);
    }

    /**
     * Collects the missing mandatory fields of the data.
     *
     * @param schema the schema of the kind
     * @param data   the data of the document
     * @return the JSON Pointers of the missing fields, in the order found, each once
     */
    static List<String> missing(FormSchema schema, Map<String, ?> data) {
        var fields = new MandatoryFields(schema);
        fields.collect(schema.schema(), data, "", FormSchema.identitySet());
        return List.copyOf(fields.missing);
    }

    /**
     * Collects the missing fields of a value by one of its sub-schemas.
     *
     * @param entered the sub-schemas already applied to this value, compared by identity, so that a reference cycle
     *                ends; a new value starts a new set
     */
    private void collect(@Nullable Object subSchema, @Nullable Object data, String pointer, Set<Object> entered) {
        if (!(subSchema instanceof Map<?, ?> keywords) || !entered.add(keywords)) {
            return;
        }
        if (data instanceof Map<?, ?> object) {
            collectRequired(keywords, object, pointer);
            collectProperties(keywords, object, pointer);
            collectDependentSchemas(keywords, object, pointer, entered);
            collectUnevaluatedProperties(keywords, object, pointer);
        }
        if (data instanceof List<?> items) {
            collectItems(keywords, items, pointer);
            collectUnevaluatedItems(keywords, items, pointer);
        }
        if (keywords.get("allOf") instanceof List<?> all) {
            for (Object member : all) {
                collect(member, data, pointer, entered);
            }
        }
        for (Map<?, ?> target : schema.referencedSchemas(keywords)) {
            collect(target, data, pointer, entered);
        }
    }

    private static String child(String pointer, String key) {
        return pointer + "/" + JsonNode.encodeJsonPointer(key);
    }

    /** The names of the keyword {@code required} of a schema that the object does not hold. */
    private void collectRequired(Map<?, ?> keywords, Map<?, ?> object, String pointer) {
        if (keywords.get("required") instanceof List<?> names) {
            for (Object name : names) {
                if (name instanceof String key && !object.containsKey(key)) {
                    missing.add(child(pointer, key));
                }
            }
        }
    }

    /**
     * The missing fields within the properties the object holds: by the sub-schema of {@code properties} that names the
     * property, of every pattern of {@code patternProperties} that matches its name, and of
     * {@code additionalProperties} when neither selects it. All sub-schemas applied to one property share its set of
     * entered schemas.
     */
    private void collectProperties(Map<?, ?> keywords, Map<?, ?> object, String pointer) {
        Map<?, ?> properties = keywords.get("properties") instanceof Map<?, ?> named ? named : Map.of();
        Map<String, Set<Object>> entered = new HashMap<>();
        for (Map.Entry<?, ?> property : properties.entrySet()) {
            String key = (String) property.getKey();
            if (object.containsKey(key)) {
                collect(property.getValue(), object.get(key), child(pointer, key),
                        entered.computeIfAbsent(key, name -> FormSchema.identitySet()));
            }
        }
        Map<?, ?> byPattern = keywords.get("patternProperties") instanceof Map<?, ?> map ? map : Map.of();
        List<Pattern> patterns = EvaluatedLocations.patterns(keywords);
        @Nullable Object additional = keywords.get("additionalProperties");
        for (Map.Entry<?, ?> property : object.entrySet()) {
            if (!(property.getKey() instanceof String key)) {
                continue;
            }
            Set<Object> enteredByKey = entered.computeIfAbsent(key, name -> FormSchema.identitySet());
            boolean selected = properties.containsKey(key);
            for (Pattern pattern : patterns) {
                if (pattern.matcher(key).find()) {
                    selected = true;
                    collect(byPattern.get(pattern.pattern()), property.getValue(), child(pointer, key), enteredByKey);
                }
            }
            if (!selected) {
                collect(additional, property.getValue(), child(pointer, key), enteredByKey);
            }
        }
    }

    /**
     * The missing fields of the object by the schema of every entry of {@code dependentSchemas} whose property the
     * object holds; the schema applies to the object itself, so it shares the object's set of entered schemas.
     */
    private void collectDependentSchemas(Map<?, ?> keywords, Map<?, ?> object, String pointer, Set<Object> entered) {
        if (keywords.get("dependentSchemas") instanceof Map<?, ?> dependent) {
            for (Map.Entry<?, ?> entry : dependent.entrySet()) {
                if (object.containsKey(entry.getKey())) {
                    collect(entry.getValue(), object, pointer, entered);
                }
            }
        }
    }

    /**
     * The missing fields within the properties of the object that the schema leaves unevaluated, by the sub-schema of
     * {@code unevaluatedProperties}; the evaluation is computed only when that sub-schema holds {@code required}.
     */
    private void collectUnevaluatedProperties(Map<?, ?> keywords, Map<?, ?> object, String pointer) {
        if (!(keywords.get("unevaluatedProperties") instanceof Map<?, ?> rest) || !schema.holdsRequired(rest)) {
            return;
        }
        Set<String> evaluatedNames = evaluated.properties(keywords, object);
        for (Map.Entry<?, ?> property : object.entrySet()) {
            if (property.getKey() instanceof String key && !evaluatedNames.contains(key)) {
                collect(rest, property.getValue(), child(pointer, key), FormSchema.identitySet());
            }
        }
    }

    /**
     * The missing fields within the items of an array, by {@link FormSchema#byPosition(List, Object, int)}: the entry of
     * {@code prefixItems} for a leading item, {@code items} for every item after them.
     */
    private void collectItems(Map<?, ?> keywords, List<?> items, String pointer) {
        List<?> prefixItems = keywords.get("prefixItems") instanceof List<?> leading ? leading : List.of();
        @Nullable Object rest = keywords.get("items");
        for (int index = 0; index < items.size(); index++) {
            collect(FormSchema.byPosition(prefixItems, rest, index), items.get(index), pointer + "/" + index,
                    FormSchema.identitySet());
        }
    }

    /**
     * The missing fields within the items of the array that the schema leaves unevaluated, by the sub-schema of
     * {@code unevaluatedItems}; the evaluation is computed only when that sub-schema holds {@code required}.
     */
    private void collectUnevaluatedItems(Map<?, ?> keywords, List<?> items, String pointer) {
        if (!(keywords.get("unevaluatedItems") instanceof Map<?, ?> rest) || !schema.holdsRequired(rest)) {
            return;
        }
        Set<Integer> evaluatedPositions = evaluated.items(keywords, items);
        for (int index = 0; index < items.size(); index++) {
            if (!evaluatedPositions.contains(index)) {
                collect(rest, items.get(index), pointer + "/" + index, FormSchema.identitySet());
            }
        }
    }
}
