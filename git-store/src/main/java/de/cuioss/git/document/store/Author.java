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


import org.jspecify.annotations.Nullable;

/**
 * The author of a draft or a save: the user, named in the history by the pseudonym only.
 * <p>
 * {@link #toString()} renders the pseudonym only, never the address (GDR-NFR-3).
 *
 * @param pseudonym the pseudonym of the user, the author name of every commit; also a segment of the draft reference,
 *                  so it must match {@code [A-Za-z0-9-]+} for a draft (see {@link StoreReferences#draft})
 * @param email     the author address; {@code null} when none is known. It is written only when {@code commit.email}
 *                  is {@code true} (see {@link StoreConfiguration.Commit})
 * @since 0.1
 */
public record Author(String pseudonym, @Nullable String email) {

    /**
     * Checks the components.
     *
     * @param pseudonym the pseudonym
     * @param email     the address, or {@code null}
     * @throws NullPointerException     if {@code pseudonym} is {@code null}
     * @throws IllegalArgumentException if the address is blank or contains {@code <}, {@code >}, a carriage return, a
     *                                  line feed or a NUL character; the message names the component, never its value
     */
    public Author {
        Objects.requireNonNull(pseudonym, "pseudonym");
        CommitIdentities.checkAddress(email);
    }

    /**
     * Creates an author without address.
     *
     * @param pseudonym the pseudonym
     * @return the author
     * @throws NullPointerException if {@code pseudonym} is {@code null}
     */
    public static Author of(String pseudonym) {
        return new Author(pseudonym, null);
    }

    /**
     * Renders the pseudonym only, never the address (GDR-NFR-3).
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "Author[pseudonym=" + pseudonym + "]";
    }
}
