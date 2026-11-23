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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * sub-schema, also where a branch of {@code allOf} or a {@code $ref} within the file declares them (but not a
 * {@code $dynamicRef}, {@code anyOf}, {@code oneOf} or a conditional, which apply depending on the data), which orders
 * the keys of the YAML the {@link YamlDocumentCodec} writes; and the annotation keyword
 * {@value #SPLIT}, which a validator outside the library ignores as an unknown keyword: with the value {@code true} on a
 * top-level property of type {@code object} it stores that part as a file of its own ({@link DocumentLayout}).
 * <p>
 * A schema the library does not accept is the passed-on {@link StoreOutcome#MALFORMED}, its detail naming the schema
 * file and the problem, never content of the schema (GDR-NFR-3): text that is not UTF-8 or not JSON within the JSON
 * data model of ADR-0008 (with the line and column), a schema file nested deeper than 128 mappings and sequences (the
 * nesting limit of the reader, named in the detail with the line and column), a root that is not an object, another
 * dialect, a {@code $ref} or
 * {@code $dynamicRef} that points outside the schema file or whose fragment selects no object or boolean schema in it
 * (an array index of a JSON Pointer only in its canonical form, {@code 0} or without leading zeros), or that selects an
 * object inside the literal value of {@code const}, {@code enum}, {@code default} or {@code examples}, the value of
 * {@code dependentRequired} or {@code $vocabulary} or an object inside it, or the map of {@code properties},
 * {@code patternProperties}, {@code $defs}, {@code definitions} or {@code dependentSchemas} itself, a cycle of
 * schemas applied to the same value without descending into a child value (for example
 * {@code {"allOf": [{"$ref": "#"}]}}), whose evaluation would never end, a chain of more than 128 schemas applied to
 * the same value (for example a {@code $ref} to a schema whose {@code $ref} leads on, link after link), whose check
 * would recurse once per link, {@value #SPLIT} that is not a boolean or not
 * on a top-level property of type {@code object} (also in a schema only a reference selects), {@code required} that is
 * not an array of unique strings, {@code required} in a sub-schema of {@code oneOf}, {@code anyOf}, {@code not},
 * {@code if}, {@code then}, {@code else} or {@code contains} (also in a schema such a sub-schema references), where
 * deferring it to
 * {@link SchemaValidator#required(FormSchema, Map)} would change which alternative or condition holds, and a schema the
 * validator {@code dev.harrel:json-schema} refuses, for example one that does not conform to the meta-schema of the
 * dialect. These rules hold for every schema of the file a validator may apply, a schema that only a reference selects
 * (under an unknown keyword such as {@code x-contact}) included, and {@code required} is removed from each of them for
 * {@link SchemaValidator#validate(FormSchema, Map)}.
 * <p>
 * A schema is immutable and may serve every thread. It holds the validator of {@code dev.harrel:json-schema} 1.9.1 that
 * {@link #parse(String, byte[])} prepared, once per schema, in a final field; every later use only reads it, which the
 * library's {@code Validator.validate} allows concurrently once all schemas are registered (see
 * {@link SchemaValidator}).
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
    private static final String ALL_OF = "allOf";
    private static final String REF = "$ref";

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
    private final SchemaValidator.Prepared prepared;

    private FormSchema(String path, Map<String, @Nullable Object> schema, Map<String, @Nullable Object> withoutRequired,
            SchemaResources resources, SchemaValidator.Prepared prepared, List<String> splitProperties) {
        this.path = path;
        this.schema = schema;
        this.withoutRequired = withoutRequired;
        this.resources = resources;
        this.prepared = prepared;
        this.shape = Shape.of(resources, schema);
        this.splitProperties = List.copyOf(splitProperties);
    }

    /**
     * Reads the schema of a kind.
     *
     * @param path    the repository-relative path of the schema file, as the kind references it; named in every
     *                detail
     * @param content the content of the schema file
     * @return {@link FormsOutcome#OK} with the schema, or the passed-on {@link StoreOutcome#MALFORMED} for a schema the
     *         library does not accept, among them a schema file nested deeper than 128 mappings and sequences, refused
     *         by the reader with the limit and the position named; a cycle of schemas applied to the same value, and a
     *         chain of more than 128 of them, is refused before any other check of the sub-schemas and before the
     *         validator sees the schema
     * @throws NullPointerException if an argument is {@code null}
     */
    public static FormsResult<FormSchema> parse(String path, byte[] content) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        String text = YamlReader.decodeUtf8(content);
        if (text == null) {
            return malformed(path, "not UTF-8");
        }
        FormsResult<Map<String, @Nullable Object>> read = YAML.readMapping(text, "the root: not an object");
        if (!read.isOk()) {
            return malformed(path, Objects.requireNonNull(read.detail()));
        }
        Map<String, @Nullable Object> schema = Objects.requireNonNull(read.value());
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
        Set<Object> schemaObjects = identitySet();
        schemaObjects.addAll(schemas);
        Map<String, @Nullable Object> withoutRequired = withoutRequired(schema, schemaObjects);
        Optional<SchemaValidator.Prepared> prepared = SchemaValidator.prepare(withoutRequired, resources, schemas);
        if (prepared.isEmpty()) {
            return malformed(path, "not a JSON Schema the validator accepts");
        }
        return FormsResult.ok(
                new FormSchema(path, schema, withoutRequired, resources, prepared.get(), splitProperties(schema)));
    }

    /**
     * The schemas within the file that the references of a schema may select, {@link SchemaResources#targets(Map,
     * String)} of {@code $ref} and {@code $dynamicRef} together: for a {@code $dynamicRef} also every object its dynamic
     * scope may bind it to, so that every walk over the schema follows each candidate the validator may apply.
     */
    static List<Map<?, ?>> targets(SchemaResources resources, Map<?, ?> schema) {
        List<Map<?, ?>> targets = new ArrayList<>();
        for (String reference : REFERENCES) {
            targets.addAll(resources.targets(schema, reference));
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
     * ({@link SchemaResources#schemaObjects()}): the root, every sub-schema, and every object a reference selects, also
     * through the dynamic scope of a {@code $dynamicRef}, wherever it sits, also under a keyword the dialect does not
     * define. Any other object keeps a key named {@code required}; a value under {@code const}, {@code enum},
     * {@code default}, {@code examples}, {@code dependentRequired} or {@code $vocabulary} and a map of named
     * sub-schemas are never rewritten, because {@link #parse(String, byte[])} refuses a reference that selects such an
     * object, so none of them is a schema object and validation reads each as written.
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
     * Returns the top-level properties of the schema: those of its own {@code properties} and of every schema it
     * applies to the same value through {@code allOf} and a {@code $ref} within the file, merged in the declaration
     * order of its keywords, each name at its first position.
     *
     * @return the names of the top-level properties, in that order, unmodifiable
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

    /** The schema without the keyword {@code required}, which validation evaluates. */
    Map<String, @Nullable Object> withoutRequired() {
        return withoutRequired;
    }

    /**
     * The validator of the library, prepared once by {@link #parse(String, byte[])}, which {@code validate} and
     * {@code required} of {@link SchemaValidator} share.
     */
    SchemaValidator.Prepared prepared() {
        return prepared;
    }

    /**
     * The declaration order of the properties, nested objects and array items by their sub-schema ({@code prefixItems}
     * for the leading items, {@code items} for the others), through {@code allOf} and a {@code $ref} within the file.
     */
    Shape shape() {
        return shape;
    }

    /**
     * The declaration order of the properties of one value, resolved from the schema objects that apply to it, one
     * level at a time.
     * <p>
     * The schemas that apply to the value are found by walking the keywords of each schema object in their declaration
     * order: {@code properties} contributes its names, every branch of {@code allOf} contributes in its declaration
     * order, and a {@code $ref} within the file contributes its target; a name takes the first position at which it is
     * found. {@code $dynamicRef}, {@code anyOf}, {@code oneOf} and the conditional keywords are not followed, because
     * whether they apply depends on the data. A schema applied twice to the same value is walked once. The shape of a
     * property, or of the item of an array, is resolved only when it is asked for, from every applying schema that
     * declares it, so a recursive schema such as {@code {"items": {"$ref": "#"}}} is resolved only as deep as the data
     * goes, bounded by the nesting limit of {@link DataNode#MAX_DEPTH} levels. A shape is immutable.
     */
    static final class Shape {

        /** The shape of a value no schema declares. */
        static final Shape EMPTY = new Shape(null, List.of());

        private final @Nullable SchemaResources resources;
        private final List<Map<?, ?>> applying;
        private final List<String> order;

        private Shape(@Nullable SchemaResources resources, List<Map<?, ?>> seeds) {
            this.resources = resources;
            List<Map<?, ?>> found = new ArrayList<>();
            Set<String> names = new LinkedHashSet<>();
            Set<Object> seen = identitySet();
            for (Map<?, ?> seed : seeds) {
                walk(seed, seen, found, names);
            }
            this.applying = List.copyOf(found);
            this.order = List.copyOf(names);
        }

        /** The shape of the root of a schema file. */
        static Shape of(SchemaResources resources, Map<?, ?> root) {
            return new Shape(resources, List.of(root));
        }

        /**
         * Adds a schema and the schemas it applies in place, through {@code allOf} and a {@code $ref} within the file,
         * in the declaration order of its keywords, collecting the names of {@code properties} on the way. The
         * recursion follows only schemas applied to the same value, so it is bounded by the in-place chain of
         * {@link InPlaceCycles#MAX_IN_PLACE_CHAIN} schemas that {@link FormSchema#parse(String, byte[])} enforces.
         */
        private void walk(Map<?, ?> schema, Set<Object> seen, List<Map<?, ?>> found, Set<String> names) {
            if (!seen.add(schema)) {
                return;
            }
            found.add(schema);
            for (Map.Entry<?, ?> entry : schema.entrySet()) {
                Object keyword = entry.getKey();
                if (PROPERTIES.equals(keyword) && entry.getValue() instanceof Map<?, ?> properties) {
                    // a set keeps the first position of a name
                    properties.keySet().forEach(name -> names.add((String) name));
                } else if (ALL_OF.equals(keyword) && entry.getValue() instanceof List<?> branches) {
                    for (Object branch : branches) {
                        if (branch instanceof Map<?, ?> branchSchema) {
                            walk(branchSchema, seen, found, names);
                        }
                    }
                } else if (REF.equals(keyword) && entry.getValue() instanceof String reference && resources != null
                        && resources.resolve(schema, reference) instanceof Map<?, ?> target) {
                    walk(target, seen, found, names);
                }
            }
        }

        /** The names of the properties, in the order of their first declaration, unmodifiable. */
        List<String> order() {
            return order;
        }

        /** The shape of a property, from every applying schema that declares it; empty when none does. */
        Shape property(String name) {
            List<Map<?, ?>> seeds = new ArrayList<>();
            for (Map<?, ?> schema : applying) {
                if (schema.get(PROPERTIES) instanceof Map<?, ?> properties) {
                    addSchema(seeds, properties.get(name));
                }
            }
            return seeds.isEmpty() ? EMPTY : new Shape(resources, seeds);
        }

        /**
         * The shape of the item of an array at a position, from every applying schema: its {@code prefixItems} entry
         * for a leading item, otherwise its {@code items}; empty when no applying schema declares either.
         */
        Shape item(int index) {
            List<Map<?, ?>> seeds = new ArrayList<>();
            for (Map<?, ?> schema : applying) {
                List<?> prefixItems = schema.get(PREFIX_ITEMS) instanceof List<?> list ? list : List.of();
                addSchema(seeds, byPosition(prefixItems, schema.get(ITEMS), index));
            }
            return seeds.isEmpty() ? EMPTY : new Shape(resources, seeds);
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
