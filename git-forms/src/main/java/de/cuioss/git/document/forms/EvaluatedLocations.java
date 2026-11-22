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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


import org.jspecify.annotations.Nullable;

/**
 * The properties and items of a value that the schemas applied to it at one location evaluate, as JSON Schema Draft
 * 2020-12 defines them for {@code unevaluatedProperties} and {@code unevaluatedItems} (Core, section 11), so that
 * {@link SchemaValidator#required(FormSchema, Map)} applies the sub-schema of these keywords to exactly the remainder.
 * <p>
 * A property is evaluated by {@code properties}, {@code patternProperties} and {@code additionalProperties} of the schema
 * itself, and by those keywords and {@code unevaluatedProperties} of every schema applied in place to the same value
 * that the value satisfies: a sub-schema of {@code allOf}, {@code anyOf} and {@code oneOf}, {@code if} and the branch
 * {@code then} or {@code else} it selects, the schema of an entry of {@code dependentSchemas} whose property the value
 * holds, and the target of a {@code $ref} or {@code $dynamicRef}. An item is evaluated likewise by {@code prefixItems}
 * (the leading items), {@code items} (all), {@code contains} (each item it matches) and {@code unevaluatedItems} of an
 * applied schema. The annotations of {@code not} are never collected. Whether a value satisfies an applied schema is
 * decided by {@code dev.harrel:json-schema} 1.9.1 with the schema without {@code required}, as
 * {@link SchemaValidator#validate(FormSchema, Map)} evaluates it; the validator is prepared on first use only.
 * <p>
 * One instance serves one check and is not shared between threads.
 */
final class EvaluatedLocations {

    private static final String PROPERTIES = "properties";
    private static final String PATTERN_PROPERTIES = "patternProperties";
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String UNEVALUATED_PROPERTIES = "unevaluatedProperties";
    private static final String PREFIX_ITEMS = "prefixItems";
    private static final String ITEMS = "items";
    private static final String UNEVALUATED_ITEMS = "unevaluatedItems";
    private static final String CONTAINS = "contains";
    private static final String IF = "if";

    /** The keywords whose list of sub-schemas is applied in place. */
    private static final List<String> IN_PLACE_LISTS = List.of("allOf", "anyOf", "oneOf");

    /** The base of the URIs under which the reference to an applied schema is registered with the validator. */
    private static final String APPLIED = "https://gdr.invalid/applied/";

    private final FormSchema schema;
    private final Map<String, URI> references = new HashMap<>();
    private final Map<Object, Map<Object, Boolean>> satisfied = new IdentityHashMap<>();
    private SchemaValidator.@Nullable Prepared prepared;

    /**
     * Creates the evaluation of one check.
     *
     * @param schema the schema of the kind
     */
    EvaluatedLocations(FormSchema schema) {
        this.schema = schema;
    }

    /**
     * The names of the properties of an object that a schema and the schemas it applies in place evaluate, its own
     * {@code unevaluatedProperties} excepted.
     *
     * @param keywords the schema at the location of {@code unevaluatedProperties}
     * @param object   the value at that location
     * @return the evaluated names
     */
    Set<String> properties(Map<?, ?> keywords, Map<?, ?> object) {
        Set<String> names = new HashSet<>();
        addProperties(keywords, object, names, FormSchema.identitySet(), false);
        return names;
    }

    /**
     * The positions of the items of an array that a schema and the schemas it applies in place evaluate, its own
     * {@code unevaluatedItems} excepted.
     *
     * @param keywords the schema at the location of {@code unevaluatedItems}
     * @param array    the value at that location
     * @return the evaluated positions, from 0
     */
    Set<Integer> items(Map<?, ?> keywords, List<?> array) {
        Set<Integer> positions = new HashSet<>();
        addItems(keywords, array, positions, FormSchema.identitySet(), false);
        return positions;
    }

    private void addProperties(Map<?, ?> keywords, Map<?, ?> object, Set<String> names, Set<Object> visited,
            boolean inPlace) {
        if (!visited.add(keywords)) {
            return;
        }
        Map<?, ?> named = keywords.get(PROPERTIES) instanceof Map<?, ?> properties ? properties : Map.of();
        List<Pattern> patterns = patterns(keywords);
        boolean all = keywords.containsKey(ADDITIONAL_PROPERTIES)
                || inPlace && keywords.containsKey(UNEVALUATED_PROPERTIES);
        for (Object key : object.keySet()) {
            if (key instanceof String name && (all || named.containsKey(name)
                    || patterns.stream().anyMatch(pattern -> pattern.matcher(name).find()))) {
                names.add(name);
            }
        }
        for (Map<?, ?> applied : appliedSchemas(keywords, object)) {
            addProperties(applied, object, names, visited, true);
        }
    }

    private void addItems(Map<?, ?> keywords, List<?> array, Set<Integer> positions, Set<Object> visited,
            boolean inPlace) {
        if (!visited.add(keywords)) {
            return;
        }
        int leading = keywords.get(PREFIX_ITEMS) instanceof List<?> prefixItems ? prefixItems.size() : 0;
        boolean all = keywords.containsKey(ITEMS) || inPlace && keywords.containsKey(UNEVALUATED_ITEMS);
        @Nullable Object contains = keywords.get(CONTAINS);
        for (int index = 0; index < array.size(); index++) {
            if (all || index < leading || contains != null && satisfies(contains, array.get(index))) {
                positions.add(index);
            }
        }
        for (Map<?, ?> applied : appliedSchemas(keywords, array)) {
            addItems(applied, array, positions, visited, true);
        }
    }

    /**
     * The schemas a schema applies in place to a value that the value satisfies: the sub-schemas of {@code allOf},
     * {@code anyOf} and {@code oneOf}, {@code if} and the branch it selects, the schemas of {@code dependentSchemas}
     * whose property an object holds, and the targets of the references.
     */
    private List<Map<?, ?>> appliedSchemas(Map<?, ?> keywords, @Nullable Object value) {
        List<Map<?, ?>> candidates = new ArrayList<>();
        for (String keyword : IN_PLACE_LISTS) {
            if (keywords.get(keyword) instanceof List<?> members) {
                members.forEach(member -> addSchema(candidates, member));
            }
        }
        if (keywords.containsKey(IF)) {
            @Nullable Object condition = keywords.get(IF);
            addSchema(candidates, condition);
            addSchema(candidates, keywords.get(satisfies(condition, value) ? "then" : "else"));
        }
        if (value instanceof Map<?, ?> object && keywords.get("dependentSchemas") instanceof Map<?, ?> dependent) {
            dependent.forEach((name, dependentSchema) -> {
                if (object.containsKey(name)) {
                    addSchema(candidates, dependentSchema);
                }
            });
        }
        candidates.addAll(schema.referencedSchemas(keywords));
        return candidates.stream().filter(candidate -> satisfies(candidate, value)).toList();
    }

    private static void addSchema(List<Map<?, ?>> candidates, @Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            candidates.add(map);
        }
    }

    /**
     * Tells whether a value satisfies a sub-schema, as the validator evaluates the schema without {@code required}: a
     * boolean schema by its value, an object schema through a reference to its location, which the validator resolves
     * within the registered schema.
     */
    private boolean satisfies(@Nullable Object subSchema, @Nullable Object value) {
        if (subSchema instanceof Boolean bool) {
            return bool;
        }
        if (!(subSchema instanceof Map<?, ?> map)) {
            return false;
        }
        @Nullable String pointer = schema.resources().pointerOf(map);
        if (pointer == null) {
            return false;
        }
        Map<Object, Boolean> byValue = satisfied.computeIfAbsent(subSchema, key -> new IdentityHashMap<>());
        Boolean known = byValue.get(value);
        if (known == null) {
            SchemaValidator.Prepared validator = validator();
            URI reference = references.computeIfAbsent(pointer, this::register);
            known = validator.validator().validate(reference, DataNode.root(value)).isValid();
            byValue.put(value, known);
        }
        return known;
    }

    /**
     * Registers a schema that references the sub-schema at a JSON Pointer of the registered schema; the fragment is
     * URL-encoded, because the validator decodes the fragment of a reference so.
     */
    private URI register(String pointer) {
        SchemaValidator.Prepared validator = validator();
        String reference = validator.uri() + "#" + URLEncoder.encode(pointer, StandardCharsets.UTF_8);
        return validator.validator().registerSchema(URI.create(APPLIED + references.size()),
                DataNode.root(Map.of("$ref", reference)));
    }

    private SchemaValidator.Prepared validator() {
        if (prepared == null) {
            prepared = SchemaValidator.prepare(schema);
        }
        return prepared;
    }

    /**
     * The patterns of {@code patternProperties}, compiled as the validator compiles them; a pattern that does not
     * compile is left out, and the validator refuses such a schema anyway.
     */
    static List<Pattern> patterns(Map<?, ?> keywords) {
        List<Pattern> patterns = new ArrayList<>();
        if (keywords.get(PATTERN_PROPERTIES) instanceof Map<?, ?> byPattern) {
            for (Object pattern : byPattern.keySet()) {
                try {
                    patterns.add(Pattern.compile((String) pattern));
                } catch (PatternSyntaxException e) {
                    // left out; the pattern is part of the schema and is not quoted
                }
            }
        }
        return patterns;
    }
}
