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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The link fields of data (GDR-FORMS-8): every value at an instance location where a schema the validator applied
 * declares {@code "format": "gdr-link"}, with its JSON Pointer.
 * <p>
 * The locations are the {@code format} annotations of the one evaluation of the schema without {@code required}
 * ({@link SchemaValidator#evaluate(FormSchema, Map)}), as the prepared validator of {@code dev.harrel:json-schema} 1.9.1
 * reports them, checked against its source ({@code AbstractEvaluatorFactory.AnnotationEvaluator},
 * {@code EvaluationContext.validateAgainstSchema}, {@code Validator.Result.getAnnotations}). So a link field is found
 * exactly where the validator applies the schema that declares it: under {@code properties}, {@code items} and the other
 * keywords that descend, through a {@code $ref} or {@code $dynamicRef}, in every member of {@code allOf}, and in a branch
 * of {@code anyOf} or {@code oneOf} only when the value satisfies that branch, because the validator discards the
 * annotations of a schema that fails. The annotations are complete only for valid data, so the fields are collected
 * after a successful validation.
 * <p>
 * {@code format} applies to strings: a value at such a location that is no string is not a link and is left out; its
 * type is the business of the schema. A location reported twice, by two schemas applied to the same value, is one field.
 * The pointer and the value are data of the document and never logged (GDR-NFR-3).
 */
final class LinkFields {

    /** The format that marks a link field. */
    static final String LINK_FORMAT = "gdr-link";

    private LinkFields() {
    }

    /**
     * Collects the link fields of valid data.
     *
     * @param data    the data the schema was evaluated on
     * @param formats the {@code format} annotations of that evaluation
     * @return the field of every distinct location annotated with {@value #LINK_FORMAT} whose value is a string, in
     *         the order of the annotations
     */
    static List<Field> collect(Map<String, ?> data, List<SchemaValidator.FormatAnnotation> formats) {
        List<Field> fields = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SchemaValidator.FormatAnnotation annotation : formats) {
            String pointer = annotation.instanceLocation();
            if (LINK_FORMAT.equals(annotation.format()) && seen.add(pointer)
                    && SchemaResources.pointer(data, pointer) instanceof String text) {
                fields.add(new Field(pointer, text));
            }
        }
        return fields;
    }

    /**
     * A link field.
     *
     * @param pointer the JSON Pointer (RFC 6901) of the field, as the validator reports it
     * @param text    the text of the link, not yet parsed
     */
    record Field(String pointer, String text) {

        /**
         * Renders the type only, never the pointer or the text, which are data of the document.
         *
         * @return the rendering
         */
        @Override
        public String toString() {
            return "LinkFields.Field";
        }
    }
}
