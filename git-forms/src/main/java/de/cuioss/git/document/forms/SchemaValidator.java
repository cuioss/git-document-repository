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
import java.util.List;
import java.util.Map;
import java.util.Objects;


import dev.harrel.jsonschema.Error;
import dev.harrel.jsonschema.MessageProvider;
import dev.harrel.jsonschema.Validator;
import dev.harrel.jsonschema.ValidatorFactory;
import org.jspecify.annotations.Nullable;

/**
 * Validates data against the JSON Schema of its kind (GDR-FORMS-2), with {@code dev.harrel:json-schema} 1.9.1 in its
 * Draft 2020-12 dialect.
 * <p>
 * {@link #validate(FormSchema, Map)} evaluates every keyword except {@code required}, so that a document with
 * mandatory fields missing can be saved; {@link #required(FormSchema, Map)} checks {@code required} alone, for a
 * transition that leaves the first state of its workflow and for a release. The data is a value of the JSON data model
 * of ADR-0008 as {@link YamlReader} produces it; it reaches the validator through the library's own node type, so no
 * JSON provider is needed.
 * <p>
 * A violation names the JSON Pointer of the failing value (RFC 6901) and a message composed by this class from the
 * failing keyword and the constraint of the schema: a number, a type name or a format name, nothing else. The
 * validator's own messages are never used, because they may quote the value of the instance (GDR-NFR-3). The validator
 * holds no state; one instance may serve every thread.
 *
 * @since 0.1
 */
public final class SchemaValidator {

    /** The message provider of the library, reduced to the message key: the library's texts are never shown. */
    private static final MessageProvider KEY_ONLY = (key, arguments) -> key;

    private static final String AT_LEAST = "must have at least ";
    private static final String AT_MOST = "must have at most ";

    /**
     * Validates data against a schema, the keyword {@code required} not evaluated.
     *
     * @param schema the schema of the kind
     * @param data   the data of the document
     * @return {@link ValidationResult.Valid}, or {@link ValidationResult.Invalid} with every violation
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the data holds a value outside the JSON data model
     */
    public ValidationResult validate(FormSchema schema, Map<String, ?> data) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(data, "data");
        Prepared prepared = prepare(schema);
        Validator.Result result = prepared.validator().validate(prepared.uri(), DataNode.root(data));
        if (result.isValid()) {
            return new ValidationResult.Valid();
        }
        List<Violation> violations = result.getErrors().stream()
                .map(error -> violation(schema, error))
                .toList();
        return new ValidationResult.Invalid(
                violations.isEmpty() ? List.of(new Violation("", "does not match the schema")) : violations);
    }

    /**
     * Checks the keyword {@code required} of a schema, applying the sub-schemas as JSON Schema Draft 2020-12 applies
     * them: in the schema itself; for every property the data holds, in the sub-schema of {@code properties} that names
     * it, of every pattern of {@code patternProperties} that matches its name (not anchored, as the validator matches
     * it) and of {@code additionalProperties} when neither selects it; for every item of an array, in the entry of
     * {@code prefixItems} at its position and in {@code items} for the items after those of {@code prefixItems}; in every
     * sub-schema of {@code allOf}; in the schema of every entry of {@code dependentSchemas} whose property the data
     * holds; in every schema a {@code $ref} or {@code $dynamicRef} references within the schema file, for example
     * {@code #/$defs/contact}; and in the sub-schema of {@code unevaluatedProperties} and {@code unevaluatedItems} for
     * exactly the properties and items that Draft 2020-12 treats as unevaluated at its location, where an applied schema
     * evaluates only if the value satisfies it as {@link #validate(FormSchema, Map)} evaluates it. A schema applied a
     * second time to the same value is not walked again; a schema whose references apply a schema to the same value
     * without end is refused by {@link FormSchema#parse(String, byte[])}. A schema with
     * {@code required} under a keyword that decides between alternatives or conditions ({@code oneOf}, {@code anyOf},
     * {@code not}, {@code if}, {@code then}, {@code else}, {@code contains}) is refused by
     * {@link FormSchema#parse(String, byte[])}.
     *
     * @param schema the schema of the kind
     * @param data   the data of the document
     * @return {@link ValidationResult.Valid} when no mandatory field is missing, otherwise
     *         {@link ValidationResult.MandatoryFieldsMissing} with the JSON Pointers of the missing fields
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if a value the check of an applied schema passes to the validator is outside the
     *                                  JSON data model
     */
    public ValidationResult required(FormSchema schema, Map<String, ?> data) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(data, "data");
        List<String> missing = MandatoryFields.missing(schema, data);
        return missing.isEmpty() ? new ValidationResult.Valid() : new ValidationResult.MandatoryFieldsMissing(missing);
    }

    /** Tells whether the validator accepts a schema; the check of {@link FormSchema#parse(String, byte[])}. */
    static boolean accepts(FormSchema schema) {
        try {
            prepare(schema);
            return true;
        }
        /*TODO: Catch specific not RuntimeException. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
        catch (RuntimeException e) {
            // the library signals a schema it refuses by an exception whose message may quote the schema
            return false;
        }
    }

    /** A fresh validator with the schema without {@code required} registered. */
    static Prepared prepare(FormSchema schema) {
        Validator validator = new ValidatorFactory()
                .withJsonNodeFactory(DataNode.FACTORY)
                .withMessageProvider(KEY_ONLY)
                .createValidator();
        URI uri = validator.registerSchema(SchemaResources.BASE, DataNode.root(schema.withoutRequired()));
        return new Prepared(validator, uri);
    }

    /** The violation of an error of the library: its instance location and a message of this class. */
    private static Violation violation(FormSchema schema, Error error) {
        @Nullable String keyword = error.getKeyword();
        @Nullable Object constraint = keyword == null ? null
                : constraint(schema.withoutRequired(), error.getSchemaLocation(), keyword);
        return new Violation(error.getInstanceLocation(), message(keyword, constraint));
    }

    /** The value of the keyword in the schema at the location, or {@code null} when it cannot be found. */
    private static @Nullable Object constraint(Map<String, @Nullable Object> schema, String location,
            String keyword) {
        String fragment;
        try {
            fragment = URI.create(location).getFragment();
        } catch (IllegalArgumentException e) {
            return null;
        }
        @Nullable Object node = SchemaResources.pointer(schema, fragment == null ? "" : fragment);
        return node instanceof Map<?, ?> map ? map.get(keyword) : null;
    }

    /**
     * Composes the message of a failing keyword. Only a number, a type name or a format name of the schema is quoted;
     * nothing of the instance.
     */
    static String message(@Nullable String keyword, @Nullable Object constraint) {
        if (keyword == null) {
            return "is not allowed by the schema";
        }
        String number = constraint instanceof Number ? constraint.toString() : "the limit of the schema";
        @Nullable String bound = boundMessage(keyword, number);
        return bound != null ? bound : constraintMessage(keyword, constraint);
    }

    /** The message of a keyword that bounds a number, a length or a count, or {@code null} for any other keyword. */
    private static @Nullable String boundMessage(String keyword, String number) {
        return switch (keyword) {
            case "minimum" -> "must be at least " + number;
            case "maximum" -> "must be at most " + number;
            case "exclusiveMinimum" -> "must be greater than " + number;
            case "exclusiveMaximum" -> "must be less than " + number;
            case "multipleOf" -> "must be a multiple of " + number;
            case "minLength" -> AT_LEAST + number + " characters";
            case "maxLength" -> AT_MOST + number + " characters";
            case "minItems" -> AT_LEAST + number + " items";
            case "maxItems" -> AT_MOST + number + " items";
            case "minProperties" -> AT_LEAST + number + " properties";
            case "maxProperties" -> AT_MOST + number + " properties";
            case "minContains" -> "must contain at least " + number + " matching items";
            case "maxContains" -> "must contain at most " + number + " matching items";
            default -> null;
        };
    }

    /** The message of a keyword that bounds nothing countable. */
    private static String constraintMessage(String keyword, @Nullable Object constraint) {
        return switch (keyword) {
            case "type" -> "must be of type " + typeNames(constraint);
            case "pattern" -> "must match the pattern of the schema";
            case "format" -> "must be of the format " + formatName(constraint);
            case "enum" -> "must be one of the values the schema lists";
            case "const" -> "must be the value the schema prescribes";
            case "uniqueItems" -> "must not contain duplicate items";
            case "additionalProperties", "unevaluatedProperties" ->
                "must not have properties the schema does not declare";
            case "additionalItems", "unevaluatedItems", "items" ->
                "must not have items the schema does not declare";
            case "dependentRequired" -> "must have the properties the schema requires together";
            case "contains" -> "must contain an item that matches the schema of contains";
            case "propertyNames" -> "must have property names that match the schema of propertyNames";
            case "not" -> "must not match the schema of not";
            case "anyOf" -> "must match at least one schema of anyOf";
            case "oneOf" -> "must match exactly one schema of oneOf";
            case "allOf" -> "must match every schema of allOf";
            case "then", "else" -> "must match the schema of " + keyword;
            case "$ref", "$dynamicRef" -> "must match the referenced schema";
            default -> "must satisfy the keyword " + keyword;
        };
    }

    /** The type names of the keyword {@code type}: one name or a list of names. */
    private static String typeNames(@Nullable Object constraint) {
        List<String> names = new ArrayList<>();
        if (constraint instanceof String name) {
            names.add(name);
        } else if (constraint instanceof List<?> list) {
            list.stream().filter(String.class::isInstance).map(String.class::cast).forEach(names::add);
        }
        return names.isEmpty() ? "the schema" : String.join(" or ", names);
    }

    private static String formatName(@Nullable Object constraint) {
        return constraint instanceof String name ? name : "the schema";
    }

    /** A validator with the schema registered under its URI. */
    record Prepared(Validator validator, URI uri) {
    }
}
