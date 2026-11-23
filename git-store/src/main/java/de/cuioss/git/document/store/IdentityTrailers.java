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

import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


import org.jspecify.annotations.Nullable;

/**
 * Composes the identity trailers of a save or release commit (GDR-STORE-13): the caller's message, its trailing line
 * breaks stripped, a blank line, and the trailers
 *
 * <pre>
 * Actor: &lt;pseudonym&gt;
 * Actor-Issuer: &lt;iss&gt;
 * Auth-Time: &lt;auth_time, ISO 8601 in UTC, whole seconds&gt;
 * Auth-Context: acr=&lt;acr&gt;; amr=&lt;amr&gt;,&lt;amr&gt;
 * </pre>
 *
 * in the trailer form of {@code git interpret-trailers} ({@code <token>: <value>}, one per line, after a blank line).
 * A part of {@code Auth-Context} is left out when its claim is absent, and the trailer is left out when both are. The
 * trailers carry no {@code sub}, no {@code sid} and no address (GDR-NFR-3).
 */
final class IdentityTrailers {

    /** The token of the pseudonym trailer. */
    static final String ACTOR = "Actor";

    /** The token of the issuer trailer. */
    static final String ACTOR_ISSUER = "Actor-Issuer";

    /** The token of the authentication-time trailer. */
    static final String AUTH_TIME = "Auth-Time";

    /** The token of the authentication-context trailer. */
    static final String AUTH_CONTEXT = "Auth-Context";

    private IdentityTrailers() {
    }

    /**
     * Appends the identity trailers to a commit message.
     *
     * @param message   the message of the commit
     * @param pseudonym the pseudonym of the author, of the form {@code [A-Za-z0-9-]+}
     * @param claims    the claims of the authentication
     * @return {@link StoreOutcome#OK} with the message and its trailers, or {@link StoreOutcome#MALFORMED} with the
     *         detail {@code pseudonym: invalid} for a pseudonym of another form; the detail never carries it
     * @throws NullPointerException if an argument is {@code null}
     */
    static StoreResult<String> append(String message, String pseudonym, IdentityClaims claims) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(pseudonym, "pseudonym");
        Objects.requireNonNull(claims, "claims");
        if (!StoreReferences.isPseudonym(pseudonym)) {
            return StoreResult.of(StoreOutcome.MALFORMED, StoreReferences.INVALID_PSEUDONYM);
        }
        var composed = new StringBuilder(message.replaceFirst("[\\r\\n]+\\z", "")).append("\n\n");
        trailer(composed, ACTOR, pseudonym);
        trailer(composed, ACTOR_ISSUER, claims.issuer());
        trailer(composed, AUTH_TIME,
                DateTimeFormatter.ISO_INSTANT.format(claims.authTime().truncatedTo(ChronoUnit.SECONDS)));
        @Nullable String context = context(claims);
        if (context != null) {
            trailer(composed, AUTH_CONTEXT, context);
        }
        return StoreResult.ok(composed.toString());
    }

    /** The value of {@code Auth-Context}; {@code null} when neither {@code acr} nor {@code amr} is present. */
    private static @Nullable String context(IdentityClaims claims) {
        List<String> parts = new ArrayList<>(2);
        if (claims.acr() != null) {
            parts.add("acr=" + claims.acr());
        }
        if (!claims.amr().isEmpty()) {
            parts.add("amr=" + String.join(",", claims.amr()));
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    private static void trailer(StringBuilder composed, String token, String value) {
        composed.append(token).append(": ").append(value).append('\n');
    }
}
