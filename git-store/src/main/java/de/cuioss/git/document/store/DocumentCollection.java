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
package de.cuioss.git.document.store;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A collection of documents of a repository and the way its documents are kept on branches (GDR-STORE-2).
 * <p>
 * The name is a segment of every reference of the collection, so it takes the alphabet of a repository alias,
 * {@code [a-z0-9-]+}, which contains no character git-check-ref-format(1) forbids, no dot and no slash. The names
 * {@value #MAIN_NAME} and {@value #CHECKPOINTS_NAME} are reserved: {@code refs/heads/main} is the released state, and
 * {@code refs/tags/checkpoints/<n>} are the checkpoints.
 *
 * @param name the name of the collection, of the form {@code [a-z0-9-]+} and neither {@value #MAIN_NAME} nor
 *             {@value #CHECKPOINTS_NAME}
 * @param kind the way the documents of the collection are kept
 * @since 0.1
 */
public record DocumentCollection(String name, Kind kind) {

    /** The reserved name of the branch of the released state. */
    public static final String MAIN_NAME = "main";

    /** The reserved name of the tag namespace of the checkpoints. */
    public static final String CHECKPOINTS_NAME = "checkpoints";

    private static final Set<String> RESERVED = Set.of(MAIN_NAME, CHECKPOINTS_NAME);

    /**
     * The way the documents of a collection are kept on branches.
     *
     * @since 0.1
     */
    public enum Kind {

        /**
         * One working branch per document, {@code refs/heads/<collection>/<id>}, the id being the document's ULID.
         */
        PER_DOCUMENT,

        /**
         * One collection branch, {@code refs/heads/<collection>}, holding every entry and the counter file; the id of
         * an entry is its readable number, such as {@code R-042}.
         */
        NUMBERED,

        /** Content without release, saved onto {@code refs/heads/main} itself. */
        MAIN
    }

    /**
     * Checks the name and the kind.
     *
     * @param name the name of the collection
     * @param kind the kind of the collection
     * @throws NullPointerException     if {@code name} or {@code kind} is {@code null}
     * @throws IllegalArgumentException if the name lies outside {@code [a-z0-9-]+} or is reserved
     */
    public DocumentCollection {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        refusal(name).ifPresent(detail -> {
            throw new IllegalArgumentException(detail);
        });
    }

    /**
     * Creates a collection, refusing an invalid or reserved name as a result.
     *
     * @param name the name of the collection
     * @param kind the kind of the collection
     * @return {@link StoreOutcome#OK} with the collection, or {@link StoreOutcome#MALFORMED} with the detail
     *         {@code collection: invalid name} for a name outside {@code [a-z0-9-]+} or
     *         {@code collection: reserved name} for {@value #MAIN_NAME} or {@value #CHECKPOINTS_NAME}; the detail
     *         never carries the name
     * @throws NullPointerException if {@code name} or {@code kind} is {@code null}
     */
    public static StoreResult<DocumentCollection> of(String name, Kind kind) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        return refusal(name).<StoreResult<DocumentCollection>>map(detail -> StoreResult.of(StoreOutcome.MALFORMED,
                detail)).orElseGet(() -> StoreResult.ok(new DocumentCollection(name, kind)));
    }

    /** Returns the detail refusing a name, or empty for a valid one. */
    private static Optional<String> refusal(String name) {
        if (!RepositoryRegistry.ALIAS.matcher(name).matches()) {
            return Optional.of("collection: invalid name");
        }
        return RESERVED.contains(name) ? Optional.of("collection: reserved name") : Optional.empty();
    }
}
