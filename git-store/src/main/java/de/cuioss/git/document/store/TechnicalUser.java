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


import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * The technical user of a repository: the committer of every commit the store writes, and the holder of the
 * credentials of its transport.
 * <p>
 * {@link #toString()} renders the name only, never the address or the credentials (GDR-NFR-3).
 *
 * @param name        the committer name, not blank
 * @param email       the committer address; {@code null} when none is configured. It is written only when
 *                    {@code commit.email} is {@code true} (see {@link StoreConfiguration.Commit})
 * @param credentials the credentials of every fetch and push of the repository
 * @since 0.1
 */
public record TechnicalUser(String name, @Nullable String email, CredentialsProvider credentials) {

    /**
     * Checks the components.
     *
     * @param name        the committer name
     * @param email       the committer address, or {@code null}
     * @param credentials the credentials
     * @throws NullPointerException     if {@code name} or {@code credentials} is {@code null}
     * @throws IllegalArgumentException if the name is blank, or the address is blank or contains {@code <}, {@code >},
     *                                  a carriage return, a line feed or a NUL character; the message names the
     *                                  component, never its value
     */
    public TechnicalUser {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(credentials, "credentials");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name: blank");
        }
        CommitIdentities.checkAddress(email);
    }

    /**
     * Renders the name only, never the address or the credentials (GDR-NFR-3).
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "TechnicalUser[name=" + name + "]";
    }
}
