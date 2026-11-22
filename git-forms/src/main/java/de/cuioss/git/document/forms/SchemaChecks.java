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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


import org.jspecify.annotations.Nullable;

/**
 * The checks of {@link FormSchema#parse(String, byte[])} that refuse a schema before the validator sees it: a cycle of
 * schemas applied to the same value ({@link InPlaceCycles}), a reference that leaves the file or selects no schema,
 * {@value FormSchema#SPLIT} that is not a boolean on a top-level property of type {@code object}, {@code required} that
 * is not an array of unique strings, and {@code required} under a keyword that decides between alternatives or
 * conditions. The checks reach every schema of the file a validator may apply, those that only a reference selects
 * (also under a keyword the dialect does not define) included. Every reason is fixed and quotes nothing of the schema.
 */
final class SchemaChecks {

    private static final String REQUIRED = "required";

    /** The reason of a schema that applies itself to the same value without end. */
    static final String IN_PLACE_CYCLE =
            "a reference cycle that returns to the same value without descending into a child value";

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
     * Checks a schema file: first for a cycle of schemas applied to the same value, then the root and its sub-schemas,
     * then the schemas only a reference selects.
     *
     * @param resources the resources of the file, against which references are resolved
     * @param root      the root of the schema file
     * @param schemas   the schema objects of the file, {@link SchemaResources#schemaObjects()}
     * @return the problem, or {@code null} when there is none
     */
    static @Nullable String problem(SchemaResources resources, Map<?, ?> root, List<Map<?, ?>> schemas) {
        if (InPlaceCycles.present(resources, schemas)) {
            return IN_PLACE_CYCLE;
        }
        @Nullable String problem = checkSubSchemas(resources, root, false, true);
        for (int index = 0; problem == null && index < schemas.size(); index++) {
            problem = checkReferenced(resources, schemas.get(index));
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
                problem = checkSubSchemas(resources, subSchema, root && "properties".equals(keyword), false);
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
     * references, its {@code required} and the {@code required} of its conditional sub-schemas. A schema that only a
     * reference selects is reached this way; {@value FormSchema#SPLIT} applies to the top-level properties only and is
     * not checked here.
     */
    private static @Nullable String checkReferenced(SchemaResources resources, Map<?, ?> schema) {
        @Nullable String problem = checkReferences(resources, schema);
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
     *
     * @param visited the schemas already looked at, compared by identity, so that a reference cycle ends
     */
    static boolean holdsRequired(SchemaResources resources, Map<?, ?> schema, Set<Object> visited) {
        if (!visited.add(schema)) {
            return false;
        }
        if (schema.containsKey(REQUIRED)) {
            return true;
        }
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            if (!DEFINITIONS.contains(entry.getKey()) && FormSchema.subSchemas(entry.getKey(), entry.getValue())
                    .stream().anyMatch(subSchema -> holdsRequired(resources, subSchema, visited))) {
                return true;
            }
        }
        return FormSchema.targets(resources, schema).stream()
                .anyMatch(target -> holdsRequired(resources, target, visited));
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
