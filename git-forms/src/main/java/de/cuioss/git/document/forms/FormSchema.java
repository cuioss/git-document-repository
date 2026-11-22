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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The JSON Schema of a kind of document (GDR-FORMS-2, GDR-FORMS-3), in the dialect JSON Schema Draft 2020-12.
 * <p>
 * The schema is plain JSON Schema: a validator outside the library that supports the dialect uses it unchanged. Its
 * key {@code $schema} is absent or {@value #DIALECT}. Beyond the dialect the library reads two things from it: the
 * declaration order of the {@code properties} of every object, nested objects and the items of an array by their
 * sub-schema, which orders the keys of the YAML the {@link YamlDocumentCodec} writes; and the annotation keyword
 * {@value #SPLIT}, which a validator outside the library ignores as an unknown keyword: with the value {@code true} on a
 * top-level property of type {@code object} it stores that part as a file of its own ({@link DocumentLayout}).
 * <p>
 * A schema the library does not accept is the passed-on {@link StoreOutcome#MALFORMED}, its detail naming the schema
 * file and the problem, never content of the schema (GDR-NFR-3): text that is not UTF-8 or not JSON within the JSON
 * data model of ADR-0008 (with the line and column), a root that is not an object, another dialect, a {@code $ref} or
 * {@code $dynamicRef} that points outside the schema file, {@value #SPLIT} that is not a boolean or not on a top-level
 * property of type {@code object}, {@code required} that is not an array of unique strings, {@code required} in a
 * sub-schema of {@code oneOf}, {@code anyOf}, {@code not}, {@code if}, {@code then}, {@code else} or {@code contains}
 * (also in a schema such a sub-schema references), where deferring it to {@link SchemaValidator#required(FormSchema,
 * Map)} would change which alternative or condition holds, and a schema the validator {@code dev.harrel:json-schema}
 * refuses, for example one that does not conform to the meta-schema of the dialect. A schema is immutable and may serve
 * every thread.
 *
 * @since 0.1
 */
public final class FormSchema {

    /** The {@code $schema} URI of the dialect JSON Schema Draft 2020-12. */
    public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /** The annotation keyword that marks a top-level property of type {@code object} as a part of its own. */
    public static final String SPLIT = "gdr-split";

    private static final YamlReader YAML = new YamlReader();

    private static final String PROPERTIES = "properties";
    private static final String ITEMS = "items";
    private static final String PREFIX_ITEMS = "prefixItems";
    private static final String REQUIRED = "required";

    /** The keywords whose value maps names to sub-schemas. */
    private static final Set<String> SCHEMA_MAPS = Set.of(PROPERTIES, "patternProperties", "$defs", "definitions",
            "dependentSchemas");

    /** The keywords whose value is one sub-schema. */
    private static final Set<String> SCHEMA_VALUES = Set.of(ITEMS, "additionalProperties", "not", "if", "then", "else",
            "contains", "propertyNames", "unevaluatedItems", "unevaluatedProperties", "additionalItems",
            "contentSchema");

    /** The keywords whose value is a list of sub-schemas. */
    private static final Set<String> SCHEMA_LISTS = Set.of("allOf", "anyOf", "oneOf", PREFIX_ITEMS);

    /** The keywords that reference a schema; within one file only. */
    private static final Set<String> REFERENCES = Set.of("$ref", "$dynamicRef");

    /**
     * The keywords whose sub-schemas decide between alternatives or conditions; {@code required} in them cannot be
     * deferred without changing which alternative or condition holds.
     */
    private static final Set<String> CONDITIONAL = Set.of("oneOf", "anyOf", "not", "if", "then", "else", "contains");

    /** The keywords that only hold sub-schemas for references; not part of the schema they are declared in. */
    private static final Set<String> DEFINITIONS = Set.of("$defs", "definitions");

    /** The keywords that name a schema as the target of a reference by name. */
    private static final Set<String> ANCHORS = Set.of("$anchor", "$dynamicAnchor");

    private final String path;
    private final Map<String, @Nullable Object> withoutRequired;
    private final Map<String, @Nullable Object> schema;
    private final Shape shape;
    private final List<String> splitProperties;

    private FormSchema(String path, Map<String, @Nullable Object> schema, List<String> splitProperties) {
        this.path = path;
        this.schema = schema;
        this.withoutRequired = withoutRequired(schema);
        this.shape = Shape.of(schema);
        this.splitProperties = List.copyOf(splitProperties);
    }

    /**
     * Reads the schema of a kind.
     *
     * @param path    the repository-relative path of the schema file, as the kind references it; named in every
     *                detail
     * @param content the content of the schema file
     * @return {@link FormsOutcome#OK} with the schema, or the passed-on {@link StoreOutcome#MALFORMED} for a schema the
     *         library does not accept
     * @throws NullPointerException if an argument is {@code null}
     */
    public static FormsResult<FormSchema> parse(String path, byte[] content) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        String text = YamlReader.decodeUtf8(content);
        if (text == null) {
            return malformed(path, "not UTF-8");
        }
        FormsResult<Object> read = YAML.read(text);
        if (!read.isOk()) {
            return malformed(path, Objects.requireNonNull(read.detail()));
        }
        if (!(read.value() instanceof Map<?, ?> root)) {
            return malformed(path, "the root: not an object");
        }
        @SuppressWarnings("unchecked") Map<String, @Nullable Object> schema = (Map<String, @Nullable Object>) root;
        @Nullable Object dialect = schema.get("$schema");
        if (schema.containsKey("$schema") && !DIALECT.equals(dialect)) {
            return malformed(path, "$schema: not the dialect Draft 2020-12");
        }
        @Nullable String problem = checkSubSchemas(schema, schema, false, true);
        if (problem != null) {
            return malformed(path, problem);
        }
        var formSchema = new FormSchema(path, schema, splitProperties(schema));
        if (!SchemaValidator.accepts(formSchema)) {
            return malformed(path, "not a JSON Schema the validator accepts");
        }
        return FormsResult.ok(formSchema);
    }

    /**
     * Checks a schema and its sub-schemas: references within the file only, {@value #SPLIT} only as a boolean on a
     * top-level property of type {@code object}, {@code required} only as an array of unique strings and never in a
     * sub-schema of a {@linkplain #CONDITIONAL conditional keyword}, also not through a reference.
     *
     * @param document the whole schema, against which references are resolved
     * @return the problem, or {@code null} when there is none
     */
    private static @Nullable String checkSubSchemas(Map<?, ?> document, Map<?, ?> schema, boolean topLevelProperty,
            boolean root) {
        @Nullable String problem = checkKeywords(schema, topLevelProperty);
        if (problem != null) {
            return problem;
        }
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            Object keyword = entry.getKey();
            for (Map<?, ?> subSchema : subSchemas(keyword, entry.getValue())) {
                problem = checkSubSchemas(document, subSchema, root && PROPERTIES.equals(keyword), false);
                if (problem == null && CONDITIONAL.contains(keyword) && holdsRequired(document, subSchema,
                        identitySet())) {
                    problem = REQUIRED + ": not allowed under " + keyword + ", where it cannot be deferred";
                }
                if (problem != null) {
                    return problem;
                }
            }
        }
        return null;
    }

    /**
     * Tells whether a schema holds the keyword {@code required}: itself, in a sub-schema or in a schema it references
     * within the file. The definitions of a schema count only where they are referenced.
     *
     * @param visited the schemas already looked at, compared by identity, so that a reference cycle ends
     */
    private static boolean holdsRequired(Map<?, ?> document, Map<?, ?> schema, Set<Object> visited) {
        if (!visited.add(schema)) {
            return false;
        }
        if (schema.containsKey(REQUIRED)) {
            return true;
        }
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            if (!DEFINITIONS.contains(entry.getKey()) && subSchemas(entry.getKey(), entry.getValue()).stream()
                    .anyMatch(subSchema -> holdsRequired(document, subSchema, visited))) {
                return true;
            }
        }
        return targets(document, schema).stream()
                .anyMatch(target -> holdsRequired(document, target, visited));
    }

    /** The schemas within the file that the references of a schema resolve to. */
    private static List<Map<?, ?>> targets(Map<?, ?> document, Map<?, ?> schema) {
        List<Map<?, ?>> targets = new ArrayList<>();
        for (String reference : REFERENCES) {
            if (schema.get(reference) instanceof String target) {
                addSchema(targets, resolve(document, target));
            }
        }
        return targets;
    }

    /**
     * Resolves a reference within the file: {@code #} for the whole schema, a JSON Pointer fragment such as
     * {@code #/$defs/contact} (RFC 6901), or the name of an {@code $anchor} or {@code $dynamicAnchor}. A
     * {@code $dynamicRef} is resolved like a {@code $ref}, to the schema its fragment names; its dynamic scope is not
     * considered.
     *
     * @return the referenced value, or {@code null} when the reference leaves the file or finds nothing
     */
    private static @Nullable Object resolve(Map<?, ?> document, String reference) {
        if (!reference.startsWith("#")) {
            return null;
        }
        String fragment;
        try {
            fragment = URI.create(reference).getFragment();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (fragment == null || fragment.isEmpty()) {
            return document;
        }
        return fragment.startsWith("/") ? pointer(document, fragment) : anchor(document, fragment);
    }

    /**
     * The value at a JSON Pointer (RFC 6901) within a schema.
     *
     * @param pointer the pointer, empty or starting with {@code /}, its escapes not yet resolved
     * @return the value, or {@code null} when the pointer finds nothing
     */
    static @Nullable Object pointer(Map<?, ?> schema, String pointer) {
        @Nullable Object node = schema;
        if (pointer.isEmpty()) {
            return node;
        }
        for (String segment : pointer.substring(1).split("/", -1)) {
            String name = segment.replace("~1", "/").replace("~0", "~");
            node = switch (node) {
                case Map<?, ?> map -> map.get(name);
                case List<?> list -> index(list, name);
                case null, default -> null;
            };
        }
        return node;
    }

    private static @Nullable Object index(List<?> list, String segment) {
        try {
            int index = Integer.parseInt(segment);
            return index >= 0 && index < list.size() ? list.get(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The first schema, in declaration order, whose {@code $anchor} or {@code $dynamicAnchor} is the name. */
    private static @Nullable Map<?, ?> anchor(Map<?, ?> schema, String name) {
        for (String keyword : ANCHORS) {
            if (name.equals(schema.get(keyword))) {
                return schema;
            }
        }
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            for (Map<?, ?> subSchema : subSchemas(entry.getKey(), entry.getValue())) {
                @Nullable Map<?, ?> found = anchor(subSchema, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** A set that compares its elements by identity, for the schemas a walk has already looked at. */
    static Set<Object> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** Checks the keywords of one schema, not its sub-schemas; the problem, or {@code null}. */
    private static @Nullable String checkKeywords(Map<?, ?> schema, boolean topLevelProperty) {
        @Nullable String problem = checkReferences(schema);
        if (problem == null) {
            problem = checkSplit(schema, topLevelProperty);
        }
        if (problem == null) {
            problem = checkRequired(schema);
        }
        return problem;
    }

    private static @Nullable String checkReferences(Map<?, ?> schema) {
        for (String reference : REFERENCES) {
            if (schema.get(reference) instanceof String target && !target.startsWith("#")) {
                return reference + ": a reference outside the schema file";
            }
        }
        return null;
    }

    private static @Nullable String checkSplit(Map<?, ?> schema, boolean topLevelProperty) {
        if (!schema.containsKey(SPLIT)) {
            return null;
        }
        @Nullable Object split = schema.get(SPLIT);
        if (!(split instanceof Boolean)) {
            return SPLIT + ": not a boolean";
        }
        if (Boolean.TRUE.equals(split) && !(topLevelProperty && "object".equals(schema.get("type")))) {
            return SPLIT + ": only on a top-level property of type object";
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

    /** The sub-schemas that are objects in the value of a keyword; empty for a keyword that holds no schema. */
    private static List<Map<?, ?>> subSchemas(Object keyword, @Nullable Object value) {
        List<Map<?, ?>> found = new ArrayList<>();
        if (SCHEMA_MAPS.contains(keyword) && value instanceof Map<?, ?> named) {
            named.values().forEach(sub -> addSchema(found, sub));
        } else if (SCHEMA_VALUES.contains(keyword)) {
            addSchema(found, value);
        } else if (SCHEMA_LISTS.contains(keyword) && value instanceof List<?> list) {
            list.forEach(sub -> addSchema(found, sub));
        }
        return found;
    }

    private static void addSchema(List<Map<?, ?>> found, @Nullable Object value) {
        if (value instanceof Map<?, ?> map) {
            found.add(map);
        }
    }

    /** The top-level properties with {@value #SPLIT} {@code true}, in declaration order. */
    private static List<String> splitProperties(Map<String, @Nullable Object> schema) {
        List<String> split = new ArrayList<>();
        if (schema.get(PROPERTIES) instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> property && Boolean.TRUE.equals(property.get(SPLIT))) {
                    split.add((String) entry.getKey());
                }
            }
        }
        return split;
    }

    /** A copy of a schema without the keyword {@code required} in the schema and every sub-schema. */
    private static Map<String, @Nullable Object> withoutRequired(Map<?, ?> schema) {
        Map<String, @Nullable Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            String keyword = (String) entry.getKey();
            if (!REQUIRED.equals(keyword)) {
                copy.put(keyword, withoutRequired(keyword, entry.getValue()));
            }
        }
        return copy;
    }

    private static @Nullable Object withoutRequired(String keyword, @Nullable Object value) {
        if (SCHEMA_MAPS.contains(keyword) && value instanceof Map<?, ?> named) {
            Map<String, @Nullable Object> copy = new LinkedHashMap<>();
            named.forEach((name, sub) -> copy.put((String) name, withoutRequiredSchema(sub)));
            return copy;
        }
        if (SCHEMA_VALUES.contains(keyword)) {
            return withoutRequiredSchema(value);
        }
        if (SCHEMA_LISTS.contains(keyword) && value instanceof List<?> list) {
            return list.stream().map(FormSchema::withoutRequiredSchema).toList();
        }
        return value;
    }

    private static @Nullable Object withoutRequiredSchema(@Nullable Object value) {
        return value instanceof Map<?, ?> map ? withoutRequired(map) : value;
    }

    private static FormsResult<FormSchema> malformed(String path, String problem) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, path + ": " + problem));
    }

    /**
     * Returns the path of the schema file.
     *
     * @return the repository-relative path the schema was read from
     */
    public String path() {
        return path;
    }

    /**
     * Returns the top-level properties of the schema.
     *
     * @return the names of the top-level {@code properties}, in declaration order, unmodifiable
     */
    public List<String> properties() {
        return shape.order();
    }

    /**
     * Returns the top-level properties stored as parts of their own.
     *
     * @return the names of the top-level properties of type {@code object} with {@value #SPLIT} {@code true}, in
     *         declaration order, unmodifiable
     */
    public List<String> splitProperties() {
        return splitProperties;
    }

    /** The schema as read. */
    Map<String, @Nullable Object> schema() {
        return schema;
    }

    /** The schemas within this schema that the references of one of its sub-schemas resolve to. */
    List<Map<?, ?>> referencedSchemas(Map<?, ?> subSchema) {
        return targets(schema, subSchema);
    }

    /** The schema without the keyword {@code required}, which validation evaluates. */
    Map<String, @Nullable Object> withoutRequired() {
        return withoutRequired;
    }

    /**
     * The declaration order of the properties, nested objects and array items by their sub-schema: {@code prefixItems}
     * for the leading items, {@code items} for the others.
     */
    Shape shape() {
        return shape;
    }

    /**
     * The declaration order of the properties of an object schema.
     *
     * @param order       the names of the {@code properties}, in declaration order
     * @param properties  the shapes of the properties whose schema is an object
     * @param prefixItems the shapes of the leading items of an array by position, from {@code prefixItems}
     * @param items       the shape of the further items of an array, from {@code items}, or {@code null}
     */
    record Shape(List<String> order, Map<String, Shape> properties, List<Shape> prefixItems, @Nullable Shape items) {

        /** The shape of a schema that declares nothing. */
        static final Shape EMPTY = new Shape(List.of(), Map.of(), List.of(), null);

        /** Derives the shape of a schema; the shape of anything that is no object schema is empty. */
        static Shape of(@Nullable Object schema) {
            if (!(schema instanceof Map<?, ?> map)) {
                return EMPTY;
            }
            List<String> order = new ArrayList<>();
            Map<String, Shape> nested = new LinkedHashMap<>();
            if (map.get(PROPERTIES) instanceof Map<?, ?> properties) {
                for (Map.Entry<?, ?> entry : properties.entrySet()) {
                    String name = (String) entry.getKey();
                    order.add(name);
                    nested.put(name, of(entry.getValue()));
                }
            }
            List<Shape> prefix = map.get(PREFIX_ITEMS) instanceof List<?> prefixSchemas
                    ? prefixSchemas.stream().map(Shape::of).toList() : List.of();
            @Nullable Shape items = map.get(ITEMS) instanceof Map<?, ?> itemSchema ? of(itemSchema) : null;
            return new Shape(List.copyOf(order), Map.copyOf(nested), prefix, items);
        }

        /** The shape of a property, empty for a property the schema does not declare. */
        Shape property(String name) {
            return properties.getOrDefault(name, EMPTY);
        }

        /**
         * The shape of the item of an array at a position: from {@code prefixItems} for a leading item, otherwise from
         * {@code items}; empty when the schema declares neither.
         */
        Shape item(int index) {
            @Nullable Shape shape = byPosition(prefixItems, items, index);
            return shape == null ? EMPTY : shape;
        }
    }

    /**
     * The sub-schema of the item of an array at a position, by the rule of Draft 2020-12 that the codec and the check of
     * {@code required} share: the entry of {@code prefixItems} for a leading item, {@code items} for every item from the
     * number of entries of {@code prefixItems} onwards.
     *
     * @param <T>         the type of a sub-schema: a schema as read, or its {@link Shape}
     * @param prefixItems the entries of {@code prefixItems}, empty when the schema has none
     * @param items       the value of {@code items}, or {@code null}
     * @param index       the position of the item, from 0
     * @return the sub-schema of the item, or {@code null} when the schema declares none
     */
    static <T> @Nullable T byPosition(List<? extends T> prefixItems, @Nullable T items, int index) {
        return index < prefixItems.size() ? prefixItems.get(index) : items;
    }
}
