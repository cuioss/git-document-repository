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
import java.util.regex.Pattern;

/**
 * The key of one document or entry of a collection: the collection and the id within it (GDR-STORE-2).
 * <p>
 * The id of a document of a {@link DocumentCollection.Kind#PER_DOCUMENT} collection is its {@link Ulid}. The id of an
 * entry of any other collection matches {@code [A-Za-z0-9-]+}: a readable number such as {@code R-042}, or a ULID for
 * the draft of a new entry until its first save. Both forms contain no character git-check-ref-format(1) forbids, no
 * dot and no slash, so the id is a single valid segment of a reference name.
 *
 * @param collection the collection
 * @param id         the id within the collection
 * @since 0.1
 */
public record DocumentKey(DocumentCollection collection, String id) {

    /** The form of an id of a collection that is not per-document. */
    static final Pattern READABLE_ID = Pattern.compile("[A-Za-z0-9-]+");

    /** The detail of a refused id; it never carries the id. */
    private static final String INVALID_ID = "id: invalid";

    /**
     * Checks the collection and the id.
     *
     * @param collection the collection
     * @param id         the id
     * @throws NullPointerException     if {@code collection} or {@code id} is {@code null}
     * @throws IllegalArgumentException if the id does not fit the kind of the collection
     */
    public DocumentKey {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(id, "id");
        if (!isValid(collection, id)) {
            throw new IllegalArgumentException(INVALID_ID);
        }
    }

    /**
     * Creates a key, refusing an id that does not fit the kind of the collection as a result.
     *
     * @param collection the collection
     * @param id         the id within the collection
     * @return {@link StoreOutcome#OK} with the key, or {@link StoreOutcome#MALFORMED} with the detail
     *         {@code id: invalid} when a per-document id is no ULID or another id lies outside {@code [A-Za-z0-9-]+};
     *         the detail never carries the id
     * @throws NullPointerException if {@code collection} or {@code id} is {@code null}
     */
    public static StoreResult<DocumentKey> of(DocumentCollection collection, String id) {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(id, "id");
        return isValid(collection, id) ? StoreResult.ok(new DocumentKey(collection, id))
                : StoreResult.of(StoreOutcome.MALFORMED, INVALID_ID);
    }

    private static boolean isValid(DocumentCollection collection, String id) {
        return collection.kind() == DocumentCollection.Kind.PER_DOCUMENT ? Ulid.isValid(id)
                : READABLE_ID.matcher(id).matches();
    }
}
