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

import java.util.ArrayList;
import java.util.Collections;
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
 * {@code $dynamicRef} that points outside the schema file or whose fragment selects no object or boolean schema in it
 * (an array index of a JSON Pointer only in its canonical form, {@code 0} or without leading zeros), or that selects an
 * object inside the literal value of {@code const}, {@code enum}, {@code default} or {@code examples}, a cycle of
 * schemas applied to the same value without descending into a child value (for example
 * {@code {"allOf": [{"$ref": "#"}]}}), whose evaluation would never end, {@value #SPLIT} that is not a boolean or not
 * on a top-level property of type {@code object} (also in a schema only a reference selects), {@code required} that is
 * not an array of unique strings, {@code required} in a sub-schema of {@code oneOf}, {@code anyOf}, {@code not}, {@code if}, {@code then},
 * {@code else} or {@code contains} (also in a schema such a sub-schema references), where deferring it to
 * {@link SchemaValidator#required(FormSchema, Map)} would change which alternative or condition holds, and a schema the
 * validator {@code dev.harrel:json-schema} refuses, for example one that does not conform to the meta-schema of the
 * dialect. These rules hold for every schema of the file a validator may apply, a schema that only a reference selects
 * (under an unknown keyword such as {@code x-contact}) included, and {@code required} is removed from each of them for
 * {@link SchemaValidator#validate(FormSchema, Map)}. A schema is immutable and may serve every thread.
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

    /**
     * The keywords whose value maps names to sub-schemas.
     * <p>
     * The three lists of keywords that hold sub-schemas ({@link #SCHEMA_MAPS}, {@link #SCHEMA_VALUES},
     * {@link #SCHEMA_LISTS}) are derived from the meta-schema of Draft 2020-12 that {@code dev.harrel:json-schema}
     * 1.9.1 ships as the class path resource {@code draft2020-12.json}, with its vocabulary meta-schemas bundled under
     * {@code $defs}. In the {@code properties} of every vocabulary meta-schema, a keyword whose schema is the dynamic
     * reference {@code #meta} holds one sub-schema, a keyword whose schema is an array of such items holds a list, and
     * a keyword whose schema is an object with such {@code additionalProperties} holds a map. Beyond that derivation
     * the lists hold two keywords of Draft-07: {@code definitions} here, which the root meta-schema retains as a name
     * but not as a keyword and which schemas still use as a target of references, and {@code additionalItems} in
     * {@link #SCHEMA_VALUES}, which Draft 2020-12 no longer defines. The root meta-schema retains {@code dependencies}
     * as a name as well; no vocabulary of the dialect defines it, the validator does not evaluate it under Draft
     * 2020-12, and it is not walked. {@code FormSchemaKeywordLocationsTest} repeats the derivation, so a change of the
     * library or the dialect that adds a location fails the build instead of leaving the location unchecked.
     */
    static final Set<String> SCHEMA_MAPS = Set.of(PROPERTIES, "patternProperties", "$defs", "definitions",
            "dependentSchemas");

    /**
     * The keywords whose value is one sub-schema; derived from the meta-schema as {@link #SCHEMA_MAPS} describes, plus
     * {@code additionalItems}.
     */
    static final Set<String> SCHEMA_VALUES = Set.of(ITEMS, "additionalProperties", "not", "if", "then", "else",
            "contains", "propertyNames", "unevaluatedItems", "unevaluatedProperties", "additionalItems",
            "contentSchema");

    /**
     * The keywords whose value is a list of sub-schemas; derived from the meta-schema as {@link #SCHEMA_MAPS}
     * describes.
     */
    static final Set<String> SCHEMA_LISTS = Set.of("allOf", "anyOf", "oneOf", PREFIX_ITEMS);

    /** The keywords that reference a schema; within one file only. */
    static final Set<String> REFERENCES = Set.of("$ref", "$dynamicRef");

    private final String path;
    private final Map<String, @Nullable Object> withoutRequired;
    private final Map<String, @Nullable Object> schema;
    private final Shape shape;
    private final List<String> splitProperties;
    private final SchemaResources resources;

    private FormSchema(String path, Map<String, @Nullable Object> schema, SchemaResources resources,
            List<Map<?, ?>> schemas, List<String> splitProperties) {
        this.path = path;
        this.schema = schema;
        this.resources = resources;
        Set<Object> schemaObjects = identitySet();
        schemaObjects.addAll(schemas);
        this.withoutRequired = withoutRequired(schema, schemaObjects);
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
     *         library does not accept; a cycle of schemas applied to the same value is refused before any other check
     *         of the sub-schemas and before the validator sees the schema
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
        SchemaResources resources = SchemaResources.of(schema);
        List<Map<?, ?>> schemas = resources.schemaObjects();
        @Nullable String problem = SchemaChecks.problem(resources, schema, schemas);
        if (problem != null) {
            return malformed(path, problem);
        }
        var formSchema = new FormSchema(path, schema, resources, schemas, splitProperties(schema));
        if (!SchemaValidator.accepts(formSchema)) {
            return malformed(path, "not a JSON Schema the validator accepts");
        }
        return FormsResult.ok(formSchema);
    }

    /**
     * The schemas within the file that the references of a schema resolve to, each against the base URI of the resource
     * that contains the schema ({@link SchemaResources}).
     */
    static List<Map<?, ?>> targets(SchemaResources resources, Map<?, ?> schema) {
        List<Map<?, ?>> targets = new ArrayList<>();
        for (String reference : REFERENCES) {
            if (schema.get(reference) instanceof String target) {
                addSchema(targets, resources.resolve(schema, target));
            }
        }
        return targets;
    }

    /** A set that compares its elements by identity, for the schemas a walk has already looked at. */
    static Set<Object> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /** The sub-schemas that are objects in the value of a keyword; empty for a keyword that holds no schema. */
    static List<Map<?, ?>> subSchemas(Object keyword, @Nullable Object value) {
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

    /** Adds a value to a list of sub-schemas when it is an object; a boolean schema and any other value are skipped. */
    static void addSchema(List<Map<?, ?>> found, @Nullable Object value) {
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

    /**
     * A copy of the schema file without the keyword {@code required} in every schema object a validator may apply
     * ({@link SchemaResources#schemaObjects()}): the root, every sub-schema, and every object a reference selects,
     * wherever it sits, also under a keyword the dialect does not define. Any other object keeps a key named
     * {@code required}; a value under {@code const}, {@code enum}, {@code default} or {@code examples} is never
     * rewritten, because {@link #parse(String, byte[])} refuses a reference that selects an object inside it, so no
     * such object is a schema object and validation compares the data with the literal as written.
     *
     * @param schemas the schema objects, compared by identity
     */
    private static Map<String, @Nullable Object> withoutRequired(Map<?, ?> schema, Set<Object> schemas) {
        Map<String, @Nullable Object> copy = new LinkedHashMap<>();
        boolean isSchema = schemas.contains(schema);
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            String key = (String) entry.getKey();
            if (!(isSchema && REQUIRED.equals(key))) {
                copy.put(key, withoutRequiredValue(entry.getValue(), schemas));
            }
        }
        return copy;
    }

    private static @Nullable Object withoutRequiredValue(@Nullable Object value, Set<Object> schemas) {
        if (value instanceof Map<?, ?> map) {
            return withoutRequired(map, schemas);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> withoutRequiredValue(item, schemas)).toList();
        }
        return value;
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
        return targets(resources, subSchema);
    }

    /**
     * Tells whether one of its sub-schemas holds the keyword {@code required}: itself, in a sub-schema or in a schema it
     * references within this schema.
     */
    boolean holdsRequired(Map<?, ?> subSchema) {
        return SchemaChecks.holdsRequired(resources, subSchema, identitySet());
    }

    /** The resources of the schema file, against which references are resolved. */
    SchemaResources resources() {
        return resources;
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
