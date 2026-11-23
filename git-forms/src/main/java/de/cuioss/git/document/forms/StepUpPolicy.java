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
package de.cuioss.git.document.forms;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;


import de.cuioss.git.document.store.IdentityClaims;

/**
 * The step-up setting of an application (GDR-FORMS-7), one setting for every transition that requires step-up: the
 * keys {@code step-up.amr}, {@code step-up.acr} and {@code step-up.max-age}. The application passes it into
 * {@code git-forms}; it is not read from a file.
 * <p>
 * Claims satisfy step-up when their {@code amr} holds a value of {@link #amr()} or their {@code acr} is a value of
 * {@link #acr()}, and their {@code auth_time} is not older than {@link #maxAge()}. The default {@code amr} value is
 * {@value #MFA}: a single method such as {@code otp} or {@code hwk} is no multi-factor authentication
 * (<a href="https://www.rfc-editor.org/rfc/rfc8176.html">RFC 8176</a>). The check reads the claims only; it logs
 * nothing and returns no claim value (GDR-NFR-3).
 *
 * @param amr    the {@code amr} values that denote multi-factor authentication
 * @param acr    the {@code acr} values that denote multi-factor authentication, possibly none
 * @param maxAge the largest age of the authentication, positive
 * @since 0.1
 */
public record StepUpPolicy(Set<String> amr, Set<String> acr, Duration maxAge) {

    /** The {@code amr} value of multi-factor authentication (RFC 8176, section 2), the default of {@link #amr()}. */
    public static final String MFA = "mfa";

    /**
     * Checks the components and copies the sets immutably.
     *
     * @param amr    the {@code amr} values
     * @param acr    the {@code acr} values
     * @param maxAge the largest age of the authentication
     * @throws NullPointerException     if a component or an element of a set is {@code null}
     * @throws IllegalArgumentException if {@code maxAge} is not positive
     */
    public StepUpPolicy {
        amr = Set.copyOf(Objects.requireNonNull(amr, "amr"));
        acr = Set.copyOf(Objects.requireNonNull(acr, "acr"));
        Objects.requireNonNull(maxAge, "maxAge");
        if (maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("maxAge: not positive");
        }
    }

    /**
     * Creates the policy with the default {@code amr} value {@value #MFA} and no {@code acr} value.
     *
     * @param maxAge the largest age of the authentication, the required {@code step-up.max-age}
     * @return the policy
     * @throws NullPointerException     if {@code maxAge} is {@code null}
     * @throws IllegalArgumentException if {@code maxAge} is not positive
     */
    public static StepUpPolicy of(Duration maxAge) {
        return new StepUpPolicy(Set.of(MFA), Set.of(), maxAge);
    }

    /**
     * Tells whether claims satisfy step-up at an instant.
     *
     * @param claims the claims of the caller's authentication
     * @param now    the instant of the check
     * @return whether the claims hold a multi-factor {@code amr} or {@code acr} value of the policy and their
     *         {@code auth_time} is at most {@link #maxAge()} before {@code now}; an {@code auth_time} after {@code now}
     *         counts as not older
     * @throws NullPointerException if an argument is {@code null}
     */
    public boolean holds(IdentityClaims claims, Instant now) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(now, "now");
        String claimedAcr = claims.acr();
        boolean multiFactor = claims.amr().stream().anyMatch(amr::contains)
                || claimedAcr != null && acr.contains(claimedAcr);
        return multiFactor && !claims.authTime().plus(maxAge).isBefore(now);
    }
}
