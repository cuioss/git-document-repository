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
import org.jspecify.annotations.Nullable;

/**
 * The step-up setting of an application (GDR-FORMS-7), one setting for every transition that requires step-up: the
 * keys {@code step-up.enabled}, {@code step-up.amr}, {@code step-up.acr} and {@code step-up.max-age}. The application
 * passes it into {@code git-forms}; it is not read from a file.
 * <p>
 * Step-up is a renewed authentication whose methods the deployment chooses. When the policy is
 * {@linkplain #enabled() enabled}, claims satisfy step-up when their {@code amr} holds a value of {@link #amr()} or
 * their {@code acr} is a value of {@link #acr()}, and their {@code auth_time} is not older than {@link #maxAge()}. The
 * default {@code amr} value is {@value #MFA}, multi-factor authentication
 * (<a href="https://www.rfc-editor.org/rfc/rfc8176.html">RFC 8176</a>); a deployment may instead name a single method,
 * such as {@code pwd} for a renewed password check. When the policy is disabled ({@code step-up.enabled: false},
 * {@link #disabled()}), no transition requires step-up and the other keys are not required. The check reads the
 * claims only; it logs nothing and returns no claim value (GDR-NFR-3).
 *
 * @param enabled whether a transition that declares step-up requires it, {@code step-up.enabled}, by default
 *                {@code true}
 * @param amr     the {@code amr} values the deployment accepts as step-up
 * @param acr     the {@code acr} values the deployment accepts as step-up, possibly none
 * @param maxAge  the largest age of the authentication, positive; {@code null} only for a disabled policy
 * @since 0.1
 */
public record StepUpPolicy(boolean enabled, Set<String> amr, Set<String> acr, @Nullable Duration maxAge) {

    /** The {@code amr} value of multi-factor authentication (RFC 8176, section 2), the default of {@link #amr()}. */
    public static final String MFA = "mfa";

    /**
     * Checks the components and copies the sets immutably.
     *
     * @param enabled whether a transition that declares step-up requires it
     * @param amr     the {@code amr} values
     * @param acr     the {@code acr} values
     * @param maxAge  the largest age of the authentication, {@code null} only when not enabled
     * @throws NullPointerException     if a set, an element of a set or, for an enabled policy, {@code maxAge} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code maxAge} is given and not positive
     */
    public StepUpPolicy {
        amr = Set.copyOf(Objects.requireNonNull(amr, "amr"));
        acr = Set.copyOf(Objects.requireNonNull(acr, "acr"));
        if (enabled) {
            Objects.requireNonNull(maxAge, "maxAge");
        }
        if (maxAge != null && (maxAge.isNegative() || maxAge.isZero())) {
            throw new IllegalArgumentException("maxAge: not positive");
        }
    }

    /**
     * Creates the enabled policy with the default {@code amr} value {@value #MFA} and no {@code acr} value, the
     * setting when {@code step-up.enabled} is absent.
     *
     * @param maxAge the largest age of the authentication, the {@code step-up.max-age} an enabled policy requires
     * @return the policy
     * @throws NullPointerException     if {@code maxAge} is {@code null}
     * @throws IllegalArgumentException if {@code maxAge} is not positive
     */
    public static StepUpPolicy of(Duration maxAge) {
        return new StepUpPolicy(true, Set.of(MFA), Set.of(), maxAge);
    }

    /**
     * Creates the disabled policy, {@code step-up.enabled: false}: no transition requires step-up, and no
     * {@code amr}, {@code acr} or {@code max-age} is needed.
     *
     * @return the policy
     */
    public static StepUpPolicy disabled() {
        return new StepUpPolicy(false, Set.of(), Set.of(), null);
    }

    /**
     * Tells whether a transition requires step-up under this policy.
     *
     * @param transition the transition
     * @return whether the policy is enabled and the transition declares step-up
     * @throws NullPointerException if {@code transition} is {@code null}
     */
    public boolean requires(Transition transition) {
        Objects.requireNonNull(transition, "transition");
        return enabled && transition.stepUp();
    }

    /**
     * Tells whether claims satisfy step-up at an instant.
     *
     * @param claims the claims of the caller's authentication
     * @param now    the instant of the check
     * @return {@code true} for a disabled policy; otherwise whether the claims hold an {@code amr} or {@code acr}
     *         value of the policy and their {@code auth_time} is at most {@link #maxAge()} before {@code now}; an
     *         {@code auth_time} after {@code now} counts as not older
     * @throws NullPointerException if an argument is {@code null}
     */
    public boolean holds(IdentityClaims claims, Instant now) {
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(now, "now");
        Duration bound = maxAge;
        if (!enabled || bound == null) {
            return true;
        }
        String claimedAcr = claims.acr();
        boolean accepted = claims.amr().stream().anyMatch(amr::contains)
                || claimedAcr != null && acr.contains(claimedAcr);
        // The age is compared rather than added to the instant, which could leave the range of Instant for a large
        // maxAge; the difference of two instants always fits a Duration.
        return accepted && Duration.between(claims.authTime(), now).compareTo(bound) <= 0;
    }
}
