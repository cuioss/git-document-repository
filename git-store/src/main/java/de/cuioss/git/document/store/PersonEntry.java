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
import java.util.Set;

/**
 * The entry of a pseudonym in the person directory (GDR-STORE-19): a {@link Member} with name and memberships, or a
 * {@link FormerMember} with the memberships the provider still knows.
 * <p>
 * The memberships are copied immutably and may be empty. A name is personal data: no rendering of an entry carries it
 * (GDR-NFR-3).
 *
 * @since 0.1
 */
public sealed interface PersonEntry permits PersonEntry.Member, PersonEntry.FormerMember {

    /**
     * Returns the pseudonym of the entry.
     *
     * @return the pseudonym
     */
    String pseudonym();

    /**
     * Returns the memberships of the entry.
     *
     * @return the memberships, immutable and possibly empty
     */
    Set<Membership> memberships();

    /**
     * The entry of a current member: the pseudonym, the name and the memberships.
     *
     * @param pseudonym   the pseudonym
     * @param name        the name of the person
     * @param memberships the memberships, possibly empty
     * @since 0.1
     */
    record Member(String pseudonym, String name, Set<Membership> memberships) implements PersonEntry {

        /**
         * Checks the components and copies the memberships immutably.
         *
         * @param pseudonym   the pseudonym
         * @param name        the name
         * @param memberships the memberships
         * @throws NullPointerException if a component or a membership is {@code null}
         */
        public Member {
            Objects.requireNonNull(pseudonym, "pseudonym");
            Objects.requireNonNull(name, "name");
            memberships = Set.copyOf(Objects.requireNonNull(memberships, "memberships"));
        }

        /**
         * Renders the entry without the name (GDR-NFR-3).
         *
         * @return the rendering
         */
        @Override
        public String toString() {
            return "Member[pseudonym=" + pseudonym + ", memberships=" + memberships + "]";
        }
    }

    /**
     * The marker of a former member: the pseudonym of an erased or departed person, with the memberships the provider
     * still knows.
     *
     * @param pseudonym   the pseudonym
     * @param memberships the memberships the provider still knows, possibly none
     * @since 0.1
     */
    record FormerMember(String pseudonym, Set<Membership> memberships) implements PersonEntry {

        /**
         * Checks the components and copies the memberships immutably.
         *
         * @param pseudonym   the pseudonym
         * @param memberships the memberships
         * @throws NullPointerException if a component or a membership is {@code null}
         */
        public FormerMember {
            Objects.requireNonNull(pseudonym, "pseudonym");
            memberships = Set.copyOf(Objects.requireNonNull(memberships, "memberships"));
        }
    }
}
