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


import org.jspecify.annotations.Nullable;

/**
 * A kind of document, defined by configuration only (GDR-FORMS-1).
 * <p>
 * A kind with {@link #numbering()} is a numbered collection, one without it a per-document collection
 * (GDR-STORE-2). The schema, the template and the workflow are repository-relative references to files of the
 * configuration; the kind does not read them.
 *
 * @param id         the id of the kind, used in links ({@code <alias>:<id>/<document>}); lower-case letters, digits and
 *                   hyphens
 * @param repository the alias of the repository in the repository registry that holds the documents of the kind
 * @param collection the collection of the kind, which names its branches; lower-case letters, digits and hyphens
 * @param schema     the reference to the JSON Schema of the kind
 * @param template   the reference to the template, present for every per-document kind, optional for a numbered kind
 * @param workflow   the reference to the workflow
 * @param numbering  the running numbers of a numbered kind; {@code null} for a per-document kind
 * @since 0.1
 */
public record Kind(String id, String repository, String collection, String schema, @Nullable String template,
String workflow, @Nullable Numbering numbering) {

    /**
     * Checks the components.
     *
     * @param id         the id
     * @param repository the repository alias
     * @param collection the collection
     * @param schema     the schema reference
     * @param template   the template reference, or {@code null}
     * @param workflow   the workflow reference
     * @param numbering  the numbering, or {@code null}
     * @throws NullPointerException     if a component other than {@code template} and {@code numbering} is
     *                                  {@code null}
     * @throws IllegalArgumentException if a per-document kind has no template
     */
    public Kind {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(workflow, "workflow");
        if (numbering == null && template == null) {
            throw new IllegalArgumentException("a per-document kind needs a template");
        }
    }

    /**
     * Tells whether the kind is a numbered collection.
     *
     * @return whether the kind has running numbers
     */
    public boolean isNumbered() {
        return numbering != null;
    }
}
