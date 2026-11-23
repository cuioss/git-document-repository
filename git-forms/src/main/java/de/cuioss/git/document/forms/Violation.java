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

import java.util.Objects;

/**
 * A violation of the schema by a value of a document (GDR-FORMS-2).
 *
 * The pointer is data of the document: it carries the property names of the data, which a schema with
 * {@code patternProperties} or {@code additionalProperties} lets the user choose, so a caller treats it as data and
 * never logs it. The message never carries it.
 *
 * @param pointer the JSON Pointer (RFC 6901) of the failing value: the empty string for the document itself, otherwise
 *                starting with {@code /}; it carries property names of the data
 * @param message the message, composed from the failing keyword and the constraint of the schema, never from the value
 *                or the pointer (GDR-NFR-3)
 * @since 0.1
 */
public record Violation(String pointer, String message) {

    /**
     * Checks the components.
     *
     * @param pointer the JSON Pointer
     * @param message the message
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code pointer} is not empty and does not start with {@code /}, or
     *                                  {@code message} is blank
     */
    public Violation {
        Objects.requireNonNull(pointer, "pointer");
        Objects.requireNonNull(message, "message");
        if (!pointer.isEmpty() && !pointer.startsWith("/")) {
            throw new IllegalArgumentException("pointer: not a JSON Pointer");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message: blank");
        }
    }
}
