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
import java.time.ZoneOffset;
import java.util.Objects;


import org.eclipse.jgit.lib.PersonIdent;
import org.jspecify.annotations.Nullable;

/**
 * The single place every commit identity of {@code git-store} is built: the author from an {@link Author}, the
 * committer from the {@link TechnicalUser}.
 * <p>
 * With {@code commit.email} {@code false}, the default, the e-mail field of both is empty and a supplied address is
 * ignored, so no address reaches the history (GDR-NFR-3). With {@code commit.email} {@code true} the supplied address
 * is written, and an absent one stays empty. Every time is written in UTC.
 */
final class CommitIdentities {

    /** The characters an address must not contain: they would break the identity line of a commit. */
    private static final String FORBIDDEN = "<>\r\n\0";

    private final boolean email;
    private final TechnicalUser user;

    /**
     * Creates the identities.
     *
     * @param commit the section {@code commit} of the configuration
     * @param user   the technical user, the committer
     * @throws NullPointerException if an argument is {@code null}
     */
    CommitIdentities(StoreConfiguration.Commit commit, TechnicalUser user) {
        this.email = Objects.requireNonNull(commit, "commit").email();
        this.user = Objects.requireNonNull(user, "user");
    }

    /**
     * Builds the author identity of a commit.
     *
     * @param author the author
     * @param when   the author time
     * @return the pseudonym as name, and the address or an empty one
     * @throws NullPointerException if an argument is {@code null}
     */
    PersonIdent author(Author author, Instant when) {
        Objects.requireNonNull(author, "author");
        return identity(author.pseudonym(), author.email(), when);
    }

    /**
     * Builds the committer identity of a commit.
     *
     * @param when the commit time
     * @return the name of the technical user, and its address or an empty one
     * @throws NullPointerException if {@code when} is {@code null}
     */
    PersonIdent committer(Instant when) {
        return identity(user.name(), user.email(), when);
    }

    private PersonIdent identity(String name, @Nullable String address, Instant when) {
        Objects.requireNonNull(when, "when");
        String written = email && address != null ? address : "";
        return new PersonIdent(name, written, when, ZoneOffset.UTC);
    }

    /**
     * Checks an address of an {@link Author} or a {@link TechnicalUser}.
     *
     * @param address the address, or {@code null} for none
     * @throws IllegalArgumentException if the address is blank or contains {@code <}, {@code >}, a carriage return, a
     *                                  line feed or a NUL character; the message names the component {@code email},
     *                                  never the value
     */
    static void checkAddress(@Nullable String address) {
        if (address == null) {
            return;
        }
        if (address.isBlank() || address.chars().anyMatch(character -> FORBIDDEN.indexOf(character) >= 0)) {
            throw new IllegalArgumentException("email: invalid");
        }
    }
}
