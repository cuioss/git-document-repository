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
import java.util.List;
import java.util.Objects;


import org.jspecify.annotations.Nullable;

/**
 * The claims of the authentication behind a save or a release that the store records as identity trailers of the
 * commit (GDR-STORE-13): the claims {@code iss}, {@code auth_time}, {@code acr} and {@code amr} of the ID Token
 * (<a href="https://openid.net/specs/openid-connect-core-1_0.html#IDToken">OpenID Connect Core 1.0, section 2</a>).
 * <p>
 * The caller takes them from the ID Token it validated; the store never receives the access token or the ID Token
 * itself. There is deliberately no component for {@code sub} or {@code sid}: the commit names the user by the
 * pseudonym only (GDR-NFR-3).
 * <p>
 * Every component is checked so that it fits on one trailer line: a violation is an {@link IllegalArgumentException}
 * whose message names the component, never its value.
 *
 * @param issuer   the issuer ({@code iss}), not blank, without whitespace or control characters
 * @param authTime the time of the authentication ({@code auth_time})
 * @param acr      the authentication context class reference ({@code acr}), without control characters and
 *                 {@code ;}, not blank; {@code null} when the token carries none
 * @param amr      the authentication methods ({@code amr}), each not blank, without whitespace, control characters,
 *                 {@code ,} and {@code ;}; empty when the token carries none
 * @since 0.1
 */
public record IdentityClaims(String issuer, Instant authTime, @Nullable String acr, List<String> amr) {

    /**
     * Checks the components and copies {@code amr} immutably.
     *
     * @param issuer   the issuer
     * @param authTime the time of the authentication
     * @param acr      the authentication context class reference, or {@code null}
     * @param amr      the authentication methods
     * @throws NullPointerException     if {@code issuer}, {@code authTime}, {@code amr} or an element of it is
     *                                  {@code null}
     * @throws IllegalArgumentException if a component violates its form; the message names the component
     *                                  ({@code issuer}, {@code acr} or {@code amr}), never the value
     */
    public IdentityClaims {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(authTime, "authTime");
        Objects.requireNonNull(amr, "amr");
        if (issuer.isBlank() || issuer.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("issuer: invalid");
        }
        if (acr != null && (acr.isBlank() || acr.chars().anyMatch(c -> Character.isISOControl(c) || c == ';'))) {
            throw new IllegalArgumentException("acr: invalid");
        }
        amr = List.copyOf(amr);
        for (String method : amr) {
            if (method.isBlank() || method.chars().anyMatch(
                    c -> Character.isWhitespace(c) || Character.isISOControl(c) || c == ',' || c == ';')) {
                throw new IllegalArgumentException("amr: invalid");
            }
        }
    }
}
