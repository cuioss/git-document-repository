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
 * Every component is checked so that it fits on one trailer line. The claims come from a token, so the caller builds
 * them with {@link #of}, which returns a violation as {@link StoreOutcome#MALFORMED} naming the component, never its
 * value; the canonical constructor, for values the caller already checked, throws an {@link IllegalArgumentException}
 * with the same message instead.
 *
 * @param issuer   the issuer ({@code iss}), not blank, without whitespace or control characters
 * @param authTime the time of the authentication ({@code auth_time})
 * @param acr      the authentication context class reference ({@code acr}), not blank, without whitespace, control
 *                 characters, {@code ,} and {@code ;}; {@code null} when the token carries none
 * @param amr      the authentication methods ({@code amr}), each not blank, without whitespace, control characters,
 *                 {@code ,} and {@code ;}; empty when the token carries none
 * @since 0.1
 */
public record IdentityClaims(String issuer, Instant authTime, @Nullable String acr, List<String> amr) {

    /** The message after the component of a component that violates its form. */
    private static final String INVALID = ": invalid";

    /** The name of the component {@code issuer}, in a null check and a violation. */
    private static final String ISSUER = "issuer";

    /**
     * Checks the components and copies {@code amr} immutably.
     *
     * @param issuer   the issuer
     * @param authTime the time of the authentication
     * @param acr      the authentication context class reference, or {@code null}
     * @param amr      the authentication methods
     * @throws NullPointerException     if {@code issuer}, {@code authTime}, {@code amr} or an element of it is
     *                                  {@code null}
     * @throws IllegalArgumentException if a component violates its form; the message {@code <component>: invalid}
     *                                  names the component ({@code issuer}, {@code acr} or {@code amr}), never the
     *                                  value
     */
    public IdentityClaims {
        Objects.requireNonNull(issuer, ISSUER);
        Objects.requireNonNull(authTime, "authTime");
        amr = List.copyOf(Objects.requireNonNull(amr, "amr"));
        @Nullable String violated = violated(issuer, acr, amr);
        if (violated != null) {
            throw new IllegalArgumentException(violated + INVALID);
        }
    }

    /**
     * Builds the claims from the values of a validated ID Token, returning a value that does not fit on a trailer line
     * as a result instead of throwing.
     *
     * @param issuer   the issuer ({@code iss})
     * @param authTime the time of the authentication ({@code auth_time})
     * @param acr      the authentication context class reference ({@code acr}), or {@code null}
     * @param amr      the authentication methods ({@code amr})
     * @return {@link StoreOutcome#OK} with the claims; {@link StoreOutcome#MALFORMED} with the detail
     *         {@code issuer: invalid}, {@code acr: invalid} or {@code amr: invalid} for a blank issuer or a component
     *         with a separator ({@code ,} or {@code ;}), whitespace where none is allowed or a control character; the
     *         detail never names the value
     * @throws NullPointerException if {@code issuer}, {@code authTime}, {@code amr} or an element of it is {@code null}
     */
    public static StoreResult<IdentityClaims> of(String issuer, Instant authTime, @Nullable String acr,
            List<String> amr) {
        Objects.requireNonNull(issuer, ISSUER);
        Objects.requireNonNull(authTime, "authTime");
        List<String> methods = List.copyOf(Objects.requireNonNull(amr, "amr"));
        @Nullable String violated = violated(issuer, acr, methods);
        if (violated != null) {
            return StoreResult.of(StoreOutcome.MALFORMED, violated + INVALID);
        }
        return StoreResult.ok(new IdentityClaims(issuer, authTime, acr, methods));
    }

    /** The first component that violates its form, or {@code null} when every one fits on a trailer line. */
    private static @Nullable String violated(String issuer, @Nullable String acr, List<String> amr) {
        if (issuer.isBlank() || issuer.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            return ISSUER;
        }
        if (acr != null && !isToken(acr)) {
            return "acr";
        }
        for (String method : amr) {
            if (!isToken(method)) {
                return "amr";
            }
        }
        return null;
    }

    /** Whether a value is not blank and holds no whitespace, control character, {@code ,} or {@code ;}. */
    private static boolean isToken(String value) {
        return !value.isBlank() && value.chars().noneMatch(
                c -> Character.isWhitespace(c) || Character.isISOControl(c) || c == ',' || c == ';');
    }
}
