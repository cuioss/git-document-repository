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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


import org.jspecify.annotations.Nullable;

/**
 * The checks of {@link FormSchema#parse(String, byte[])} that refuse a schema before the validator sees it: a cycle of
 * schemas applied to the same value and a chain of more than {@value InPlaceCycles#MAX_IN_PLACE_CHAIN} of them
 * ({@link InPlaceCycles}), a reference that leaves the file or selects no schema,
 * a reference to an object that is no schema (inside the literal value of {@code const}, {@code enum}, {@code default}
 * or {@code examples}, the value of {@code dependentRequired} or {@code $vocabulary}, or a map of named sub-schemas),
 * {@value FormSchema#SPLIT} that is not a boolean on a top-level property of type {@code object}, {@code required} that
 * is not an array of unique strings, and {@code required} under a keyword that decides between alternatives or
 * conditions. The checks reach every schema of the file a validator may apply, those that only a reference selects
 * (also under a keyword the dialect does not define) included. Every reason is fixed and quotes nothing of the schema.
 */
final class SchemaChecks {

    private static final String REQUIRED = "required";
    private static final String PROPERTIES = "properties";

    /** The keywords whose value is a literal JSON value, never a schema. */
    private static final List<String> LITERALS = List.of("const", "enum", "default", "examples");

    /**
     * The keywords whose value is an object of names to values that are no schema: to arrays of property names
     * ({@code dependentRequired}) and to booleans ({@code $vocabulary}). Among the keywords of Draft 2020-12 that
     * {@code dev.harrel:json-schema} 1.9.1 defines ({@code Keyword}), these and {@link #LITERALS} are the ones whose
     * value may be an object that is no schema; every other one holds a schema, a map or list of schemas, a string, a
     * number, a boolean or an array of strings. {@code dependencies} of Draft-07 is not evaluated under Draft 2020-12
     * and counts as a keyword the dialect does not define.
     */
    private static final List<String> VALUE_MAPS = List.of("dependentRequired", "$vocabulary");

    /** The reason of a schema that applies itself to the same value without end. */
    static final String IN_PLACE_CYCLE =
            "a reference cycle that returns to the same value without descending into a child value";

    /** The reason of a schema whose chain of schemas applied to the same value exceeds the bound. */
    static final String IN_PLACE_CHAIN = "a chain of schemas applied to the same value longer than "
            + InPlaceCycles.MAX_IN_PLACE_CHAIN;

    /**
     * The keywords whose sub-schemas decide between alternatives or conditions; {@code required} in them cannot be
     * deferred without changing which alternative or condition holds.
     */
    private static final Set<String> CONDITIONAL = Set.of("oneOf", "anyOf", "not", "if", "then", "else", "contains");

    /** The keywords that only hold sub-schemas for references; not part of the schema they are declared in. */
    private static final Set<String> DEFINITIONS = Set.of("$defs", "definitions");

    private SchemaChecks() {
    }

    /**
     * Checks a schema file: first for a cycle of schemas applied to the same value and for a chain of them longer than
     * {@value InPlaceCycles#MAX_IN_PLACE_CHAIN}, then the root and its sub-schemas, then for a reference to an object
     * that is no schema, then the schemas only a reference selects.
     *
     * @param resources the resources of the file, against which references are resolved
     * @param root      the root of the schema file
     * @param schemas   the schema objects of the file, {@link SchemaResources#schemaObjects()}
     * @return the problem, or {@code null} when there is none
     */
    static @Nullable String problem(SchemaResources resources, Map<?, ?> root, List<Map<?, ?>> schemas) {
        switch (InPlaceCycles.find(resources, schemas)) {
            case CYCLE -> {
                return IN_PLACE_CYCLE;
            }
            case CHAIN_TOO_LONG -> {
                return IN_PLACE_CHAIN;
            }
            case NONE -> {
                // no cycle and no chain beyond the bound: the other checks follow
            }
        }
        @Nullable String problem = checkSubSchemas(resources, root, false, true);
        if (problem == null) {
            problem = checkValueTargets(resources, schemas);
        }
        Set<Object> topLevelProperties = FormSchema.identitySet();
        topLevelProperties.addAll(FormSchema.subSchemas(PROPERTIES, root.get(PROPERTIES)));
        for (int index = 0; problem == null && index < schemas.size(); index++) {
            Map<?, ?> schema = schemas.get(index);
            problem = checkReferenced(resources, schema, topLevelProperties.contains(schema));
        }
        return problem;
    }

    /**
     * Checks a schema and its sub-schemas: references within the file only and selecting a schema,
     * {@value FormSchema#SPLIT} only as a boolean on a top-level property of type {@code object}, {@code required}
     * only as an array of unique strings and never in a sub-schema of a {@linkplain #CONDITIONAL conditional keyword},
     * also not through a reference.
     */
    private static @Nullable String checkSubSchemas(SchemaResources resources, Map<?, ?> schema,
            boolean topLevelProperty, boolean root) {
        @Nullable String problem = checkReferences(resources, schema);
        if (problem == null) {
            problem = checkSplit(schema, topLevelProperty);
        }
        if (problem == null) {
            problem = checkRequired(schema);
        }
        if (problem != null) {
            return problem;
        }
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            Object keyword = entry.getKey();
            for (Map<?, ?> subSchema : FormSchema.subSchemas(keyword, entry.getValue())) {
                problem = checkSubSchemas(resources, subSchema, root && PROPERTIES.equals(keyword), false);
                if (problem == null) {
                    problem = checkConditional(resources, keyword, subSchema);
                }
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    /**
     * Checks one schema object of the file as {@link #checkSubSchemas} checks a sub-schema, without descending: its
     * references, {@value FormSchema#SPLIT}, its {@code required} and the {@code required} of its conditional
     * sub-schemas. A schema that only a reference selects is reached this way, also one under a keyword the dialect
     * does not define, so that {@value FormSchema#SPLIT} cannot escape its check there.
     *
     * @param topLevelProperty whether the schema is the schema of a top-level property, where {@value FormSchema#SPLIT}
     *                         {@code true} is allowed
     */
    private static @Nullable String checkReferenced(SchemaResources resources, Map<?, ?> schema,
            boolean topLevelProperty) {
        @Nullable String problem = checkReferences(resources, schema);
        if (problem == null) {
            problem = checkSplit(schema, topLevelProperty);
        }
        if (problem == null) {
            problem = checkRequired(schema);
        }
        for (String keyword : CONDITIONAL) {
            for (Map<?, ?> subSchema : FormSchema.subSchemas(keyword, schema.get(keyword))) {
                if (problem == null) {
                    problem = checkConditional(resources, keyword, subSchema);
                }
            }
        }
        return problem;
    }

    private static @Nullable String checkConditional(SchemaResources resources, Object keyword, Map<?, ?> subSchema) {
        if (CONDITIONAL.contains(keyword) && holdsRequired(resources, subSchema, FormSchema.identitySet())) {
            return REQUIRED + ": not allowed under " + keyword + ", where it cannot be deferred";
        }
        return null;
    }

    /**
     * Tells whether a schema holds the keyword {@code required}: itself, in a sub-schema or in a schema it references
     * within the file. The definitions of a schema count only where they are referenced.
     * <p>
     * The walk runs over an explicit stack, not the thread stack: a chain of sub-schemas and references is as long as
     * the file makes it, for example 55,000 flat {@code $defs} each reaching the next through
     * {@code properties} and {@code $ref}, and each schema is looked at once, so the walk is linear in the schemas of
     * the file and ends in an answer, never in a {@link StackOverflowError}.
     *
     * @param visited the schemas already looked at, compared by identity, so that a reference cycle ends
     */
    static boolean holdsRequired(SchemaResources resources, Map<?, ?> schema, Set<Object> visited) {
        Deque<Map<?, ?>> pending = new ArrayDeque<>();
        pending.push(schema);
        while (!pending.isEmpty()) {
            Map<?, ?> current = pending.pop();
            if (!visited.add(current)) {
                continue;
            }
            if (current.containsKey(REQUIRED)) {
                return true;
            }
            for (Map.Entry<?, ?> entry : current.entrySet()) {
                if (!DEFINITIONS.contains(entry.getKey())) {
                    FormSchema.subSchemas(entry.getKey(), entry.getValue()).forEach(pending::push);
                }
            }
            FormSchema.targets(resources, current).forEach(pending::push);
        }
        return false;
    }

    /**
     * Refuses a reference that selects no resource of the file, one whose fragment holds a {@code +}
     * ({@link SchemaResources#fragmentHoldsPlus(String)}), and one whose fragment selects no object or boolean schema
     * within it, such as {@code #/$defs/missing} or a pointer to a string.
     */
    private static @Nullable String checkReferences(SchemaResources resources, Map<?, ?> schema) {
        for (String reference : FormSchema.REFERENCES) {
            if (!(schema.get(reference) instanceof String target)) {
                continue;
            }
            if (!resources.withinFile(schema, target)) {
                return reference + ": a reference outside the schema file";
            }
            if (SchemaResources.fragmentHoldsPlus(target)) {
                return reference + ": a fragment with a plus sign, which the validator decodes as a space";
            }
            @Nullable Object selected = resources.resolve(schema, target);
            if (!(selected instanceof Map<?, ?>) && !(selected instanceof Boolean)) {
                return reference + ": a reference that selects no schema of the file";
            }
        }
        return null;
    }

    /**
     * Refuses a reference that may select an object that is no schema in the file as written: an object inside the
     * value of {@code const}, {@code enum}, {@code default} or {@code examples} ({@link #LITERALS}), the value of
     * {@code dependentRequired} or {@code $vocabulary} or an object inside it ({@link #VALUE_MAPS}), or the map of a
     * keyword of {@link FormSchema#SCHEMA_MAPS} itself, whose values are schemas but which is none. Each target of
     * {@link SchemaResources#targets(Map, String)} counts, those of the dynamic scope of a {@code $dynamicRef} included.
     * The target would become a schema object, from which {@code required} is removed for
     * {@link SchemaValidator#validate(FormSchema, Map)}, so validation would read a literal, the names a property
     * requires, a vocabulary or the sub-schemas of a keyword other than as written. Refusing it keeps each as written;
     * a reference to the schema that holds the keyword stays valid. Every array and string such a reference could
     * select instead is already refused as no schema by {@link #checkReferences}.
     */
    private static @Nullable String checkValueTargets(SchemaResources resources, List<Map<?, ?>> schemas) {
        Set<Object> literals = valueObjects(schemas, LITERALS);
        Set<Object> keywordValues = valueObjects(schemas, VALUE_MAPS);
        schemas.forEach(schema -> addSchemaMaps(keywordValues, schema));
        for (Map<?, ?> schema : schemas) {
            @Nullable String refused = valueTargetReason(resources, schema, literals, keywordValues);
            if (refused != null) {
                return refused;
            }
        }
        return null;
    }

    /** Adds the map of each keyword of {@link FormSchema#SCHEMA_MAPS} that the schema holds. */
    private static void addSchemaMaps(Set<Object> keywordValues, Map<?, ?> schema) {
        for (String keyword : FormSchema.SCHEMA_MAPS) {
            if (schema.get(keyword) instanceof Map<?, ?> named) {
                keywordValues.add(named);
            }
        }
    }

    /** The reason to refuse a reference of the schema that selects a literal or a keyword value, or {@code null}. */
    private static @Nullable String valueTargetReason(SchemaResources resources, Map<?, ?> schema,
            Set<Object> literals, Set<Object> keywordValues) {
        for (String reference : FormSchema.REFERENCES) {
            for (Map<?, ?> target : resources.targets(schema, reference)) {
                if (literals.contains(target)) {
                    return reference + ": a reference into the value of const, enum, default or examples";
                }
                if (keywordValues.contains(target)) {
                    return reference + ": a reference into the value of dependentRequired or $vocabulary, "
                            + "or to a map of named sub-schemas";
                }
            }
        }
        return null;
    }

    /**
     * Every object within the value of one of the keywords of a schema, compared by identity; walked with an explicit
     * stack.
     */
    private static Set<Object> valueObjects(List<Map<?, ?>> schemas, List<String> keywords) {
        Set<Object> found = FormSchema.identitySet();
        Deque<Object> pending = new ArrayDeque<>();
        for (Map<?, ?> schema : schemas) {
            keywords.forEach(keyword -> pushContainer(pending, schema.get(keyword)));
        }
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            if (value instanceof Map<?, ?> map && found.add(map)) {
                map.values().forEach(child -> pushContainer(pending, child));
            } else if (value instanceof List<?> list) {
                list.forEach(child -> pushContainer(pending, child));
            }
        }
        return found;
    }

    /** Pushes a value that is an object or an array; a scalar and {@code null} hold no object. */
    private static void pushContainer(Deque<Object> pending, @Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            pending.push(map);
        } else if (value instanceof List<?> list) {
            pending.push(list);
        }
    }

    private static @Nullable String checkSplit(Map<?, ?> schema, boolean topLevelProperty) {
        if (!schema.containsKey(FormSchema.SPLIT)) {
            return null;
        }
        @Nullable Object split = schema.get(FormSchema.SPLIT);
        if (!(split instanceof Boolean)) {
            return FormSchema.SPLIT + ": not a boolean";
        }
        if (Boolean.TRUE.equals(split) && !(topLevelProperty && "object".equals(schema.get("type")))) {
            return FormSchema.SPLIT + ": only on a top-level property of type object";
        }
        return null;
    }

    /**
     * Checks the keyword {@code required}, which the validator never sees, because validation evaluates the schema
     * without it: it must be an array of unique strings. The reason quotes nothing of the schema.
     */
    private static @Nullable String checkRequired(Map<?, ?> schema) {
        if (!schema.containsKey(REQUIRED)) {
            return null;
        }
        if (schema.get(REQUIRED) instanceof List<?> names && names.stream().allMatch(String.class::isInstance)
                && new HashSet<>(names).size() == names.size()) {
            return null;
        }
        return REQUIRED + ": not an array of unique strings";
    }
}
