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

/**
 * A membership of a person entry (GDR-STORE-19): a pair of team and role.
 *
 * @param team the team, not blank
 * @param role the role within the team, not blank
 * @since 0.1
 */
public record Membership(String team, String role) {

    /**
     * Checks that team and role are present and not blank.
     *
     * @param team the team
     * @param role the role
     * @throws NullPointerException     if {@code team} or {@code role} is {@code null}
     * @throws IllegalArgumentException if {@code team} or {@code role} is blank
     */
    public Membership {
        Objects.requireNonNull(team, "team");
        Objects.requireNonNull(role, "role");
        if (team.isBlank()) {
            throw new IllegalArgumentException("team must not be blank");
        }
        if (role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }
}
