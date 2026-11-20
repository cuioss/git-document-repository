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

import java.time.Instant;
import java.util.Objects;

/**
 * One draft of a user, as listed from {@code refs/drafts/<pseudonym>/<collection>/<id>} (GDR-STORE-2).
 *
 * @param collection the name of the collection
 * @param id         the id of the document or entry within the collection
 * @param commitId   the full id of the draft's tip commit
 * @param changedAt  the committer time of the draft's tip commit, the time of its last intermediate save
 * @since 0.1
 */
public record Draft(String collection, String id, String commitId, Instant changedAt) {

    /**
     * Checks that every component is present.
     *
     * @param collection the collection name
     * @param id         the id
     * @param commitId   the tip commit id
     * @param changedAt  the time of the last intermediate save
     * @throws NullPointerException if a component is {@code null}
     */
    public Draft {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(commitId, "commitId");
        Objects.requireNonNull(changedAt, "changedAt");
    }
}
