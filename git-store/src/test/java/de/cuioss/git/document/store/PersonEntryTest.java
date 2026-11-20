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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The entries of the person directory (GDR-STORE-19): memberships of team and role, both not blank; a member and a
 * former member with immutably copied, possibly empty memberships; and GDR-NFR-3: the rendering of a member carries no
 * name.
 */
@EnableGeneratorController
@DisplayName("PersonEntry and Membership (GDR-STORE-19)")
class PersonEntryTest {

    private static String word() {
        return Generators.letterStrings(8, 20).next();
    }

    private static Membership membership() {
        return new Membership(word(), word());
    }

    @Nested
    @DisplayName("Membership")
    class Memberships {

        @Test
        @DisplayName("keeps team and role")
        void keepsTeamAndRole() {
            var team = word();
            var role = word();

            var membership = new Membership(team, role);

            assertAll(
                    () -> assertEquals(team, membership.team(), "team"),
                    () -> assertEquals(role, membership.role(), "role"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("refuses a blank team or role")
        void refusesBlank(String blank) {
            var word = word();

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> new Membership(blank, word)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new Membership(word, blank)));
        }

        @Test
        @DisplayName("refuses a missing team or role")
        void refusesNull() {
            var word = word();

            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> new Membership(null, word)),
                    () -> assertThrows(NullPointerException.class, () -> new Membership(word, null)));
        }
    }

    @Nested
    @DisplayName("Member")
    class Members {

        @Test
        @DisplayName("copies the memberships immutably")
        void copiesMembershipsImmutably() {
            Set<Membership> memberships = new HashSet<>(Set.of(membership(), membership()));
            var expected = Set.copyOf(memberships);

            var member = new PersonEntry.Member(word(), word(), memberships);
            memberships.add(membership());
            var copied = member.memberships();
            var another = membership();

            assertAll(
                    () -> assertEquals(expected, copied, "later changes of the source do not leak"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> copied.add(another),
                            "the memberships are immutable"));
        }

        @Test
        @DisplayName("accepts no memberships")
        void acceptsNoMemberships() {
            var member = new PersonEntry.Member(word(), word(), Set.of());

            assertTrue(member.memberships().isEmpty(), "a member may hold no membership");
        }

        @Test
        @DisplayName("renders the pseudonym and the memberships, never the name (GDR-NFR-3)")
        void rendersNoName() {
            var pseudonym = word();
            var name = word();
            var membership = membership();

            var rendering = new PersonEntry.Member(pseudonym, name, Set.of(membership)).toString();

            assertAll(
                    () -> assertFalse(rendering.contains(name), "the rendering carries no name"),
                    () -> assertTrue(rendering.contains(pseudonym), "the rendering names the pseudonym"),
                    () -> assertTrue(rendering.contains(membership.team()), "the rendering names the team"));
        }

        @Test
        @DisplayName("refuses missing components")
        void refusesNull() {
            var word = word();
            Set<Membership> none = Set.of();

            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> new PersonEntry.Member(null, word, none)),
                    () -> assertThrows(NullPointerException.class, () -> new PersonEntry.Member(word, null, none)),
                    () -> assertThrows(NullPointerException.class, () -> new PersonEntry.Member(word, word, null)));
        }
    }

    @Nested
    @DisplayName("FormerMember")
    class FormerMembers {

        @Test
        @DisplayName("copies the memberships the provider still knows immutably")
        void copiesMembershipsImmutably() {
            Set<Membership> memberships = new HashSet<>(Set.of(membership()));
            var expected = Set.copyOf(memberships);

            var former = new PersonEntry.FormerMember(word(), memberships);
            memberships.add(membership());
            var copied = former.memberships();
            var another = membership();

            assertAll(
                    () -> assertEquals(expected, copied, "later changes of the source do not leak"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> copied.add(another),
                            "the memberships are immutable"));
        }

        @Test
        @DisplayName("accepts no memberships")
        void acceptsNoMemberships() {
            var pseudonym = word();

            PersonEntry entry = new PersonEntry.FormerMember(pseudonym, Set.of());

            assertAll(
                    () -> assertEquals(pseudonym, entry.pseudonym(), "pseudonym"),
                    () -> assertTrue(entry.memberships().isEmpty(), "a former member may keep no membership"));
        }

        @Test
        @DisplayName("refuses missing components")
        void refusesNull() {
            var word = word();
            Set<Membership> none = Set.of();

            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> new PersonEntry.FormerMember(null, none)),
                    () -> assertThrows(NullPointerException.class, () -> new PersonEntry.FormerMember(word, null)));
        }
    }
}
