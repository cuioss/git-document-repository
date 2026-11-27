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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


import de.cuioss.git.document.store.IdentityClaims;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The step-up setting and its check of the claims (GDR-FORMS-7), one test per row of the table of the specification:
 * the switch {@code step-up.enabled} with its default {@code true}, {@code step-up.amr} with its default {@code mfa},
 * the refusal of a single method the policy does not name and the acceptance of one it names (RFC 8176),
 * {@code step-up.acr}, and the positive {@code step-up.max-age}, required when enabled, with its boundary.
 * <p>
 * The values are literals, being the values the specification names; the instant of the check is fixed, no test reads
 * the wall clock.
 */
@DisplayName("Step-up policy and its claims check (GDR-FORMS-7)")
class StepUpPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
    private static final Duration MAX_AGE = Duration.parse("PT5M");
    private static final String ISSUER = "https://issuer.example.invalid";
    private static final String HIGH = "urn:example:loa:high";

    private static IdentityClaims claims(@Nullable String acr, List<String> amr) {
        return claims(NOW.minusSeconds(60), acr, amr);
    }

    private static IdentityClaims claims(Instant authTime, @Nullable String acr, List<String> amr) {
        return new IdentityClaims(ISSUER, authTime, acr, amr);
    }

    private static Transition transition(Mechanism mechanism, boolean stepUp) {
        return new Transition("approve", "review", "approved", mechanism, stepUp);
    }

    @Test
    @DisplayName("GDR-FORMS-7: of(maxAge) is enabled, the default when step-up.enabled is absent")
    void ofIsEnabled() {
        var policy = StepUpPolicy.of(MAX_AGE);

        assertAll(
                () -> assertTrue(policy.enabled(), "enabled"),
                () -> assertTrue(policy.requires(transition(Mechanism.RELEASE, true)),
                        "a transition that declares step-up requires it"),
                () -> assertFalse(policy.requires(transition(Mechanism.SAVE, false)),
                        "a transition that does not declare step-up does not"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: a disabled policy without max-age is accepted and requires step-up for no transition")
    void disabledWithoutMaxAge() {
        var policy = StepUpPolicy.disabled();
        Duration noMaxAge = nullValue();
        var explicit = new StepUpPolicy(false, Set.of(), Set.of(), noMaxAge);

        assertAll(
                () -> assertFalse(policy.enabled(), "disabled"),
                () -> assertNull(policy.maxAge(), "no max-age"),
                () -> assertEquals(Set.of(), policy.amr(), "no amr"),
                () -> assertEquals(Set.of(), policy.acr(), "no acr"),
                () -> assertEquals(policy, explicit, "the canonical constructor accepts a null max-age when disabled"),
                () -> assertFalse(policy.requires(transition(Mechanism.RELEASE, true)),
                        "a release that declares step-up"),
                () -> assertFalse(policy.requires(transition(Mechanism.SAVE, true)), "a save that declares step-up"),
                () -> assertTrue(policy.holds(claims(Instant.EPOCH, null, List.of()), NOW),
                        "any claims hold a disabled policy"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: step-up.amr [pwd] holds for a fresh pwd and not for a stale Auth-Time")
    void singleMethodPwd() {
        var policy = new StepUpPolicy(true, Set.of("pwd"), Set.of(), MAX_AGE);
        var pwd = List.of("pwd");

        assertAll(
                () -> assertTrue(policy.holds(claims(NOW.minusSeconds(60), null, pwd), NOW), "a fresh pwd"),
                () -> assertTrue(policy.holds(claims(NOW.minus(MAX_AGE), null, pwd), NOW), "pwd exactly max-age old"),
                () -> assertFalse(policy.holds(claims(NOW.minus(MAX_AGE).minusSeconds(1), null, pwd), NOW),
                        "a stale Auth-Time"),
                () -> assertFalse(policy.holds(claims(null, List.of("otp")), NOW), "a method the policy does not name"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: step-up.amr defaults to mfa, step-up.acr to none, and claims with amr mfa hold")
    void defaultAmrAccepted() {
        var policy = StepUpPolicy.of(MAX_AGE);

        assertAll(
                () -> assertEquals(Set.of(StepUpPolicy.MFA), policy.amr(), "the default amr"),
                () -> assertEquals("mfa", StepUpPolicy.MFA, "the RFC 8176 value"),
                () -> assertEquals(Set.of(), policy.acr(), "no acr by default"),
                () -> assertEquals(MAX_AGE, policy.maxAge(), "the max-age passed in"),
                () -> assertTrue(policy.holds(claims(null, List.of("mfa")), NOW), "mfa alone"),
                () -> assertTrue(policy.holds(claims(null, List.of("pwd", "otp", "mfa")), NOW),
                        "mfa among the methods"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: under the default mfa a single method such as otp or hwk does not hold (RFC 8176)")
    void singleMethodRefused() {
        var policy = StepUpPolicy.of(MAX_AGE);

        assertAll(
                () -> assertFalse(policy.holds(claims(null, List.of("otp")), NOW), "otp alone"),
                () -> assertFalse(policy.holds(claims(null, List.of("hwk")), NOW), "hwk alone"),
                () -> assertFalse(policy.holds(claims(null, List.of("pwd", "otp")), NOW),
                        "two methods without the value mfa"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: an amr value of step-up.amr other than the default is honoured")
    void configuredAmrHonoured() {
        var policy = new StepUpPolicy(true, Set.of("hwk"), Set.of(), MAX_AGE);

        assertAll(
                () -> assertTrue(policy.holds(claims(null, List.of("hwk")), NOW), "the configured value"),
                () -> assertFalse(policy.holds(claims(null, List.of("mfa")), NOW),
                        "mfa is only the default, not added to a configured set"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: an acr value of step-up.acr holds without mfa, another acr value does not")
    void acrAccepted() {
        var policy = new StepUpPolicy(true, Set.of(StepUpPolicy.MFA), Set.of(HIGH), MAX_AGE);

        assertAll(
                () -> assertTrue(policy.holds(claims(HIGH, List.of("pwd")), NOW), "acr of the policy, amr pwd"),
                () -> assertTrue(policy.holds(claims(HIGH, List.of()), NOW), "acr of the policy, no amr"),
                () -> assertFalse(policy.holds(claims("urn:example:loa:low", List.of("pwd")), NOW),
                        "an acr value the policy does not name"),
                () -> assertFalse(StepUpPolicy.of(MAX_AGE).holds(claims(HIGH, List.of("pwd")), NOW),
                        "no acr value without step-up.acr"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: claims without acr and with empty amr never hold")
    void emptyClaimsRefused() {
        var policy = new StepUpPolicy(true, Set.of(StepUpPolicy.MFA), Set.of(HIGH), MAX_AGE);

        assertFalse(policy.holds(claims(null, List.of()), NOW), "no acr, no amr");
    }

    @Test
    @DisplayName("GDR-FORMS-7: step-up.max-age is required when enabled and positive when given")
    void maxAgeRequiredAndPositive() {
        Set<String> amr = Set.of(StepUpPolicy.MFA);
        Set<String> acr = Set.of();
        Duration nullMaxAge = nullValue();
        Duration negative = Duration.ofSeconds(-1);

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> StepUpPolicy.of(nullMaxAge)),
                () -> assertThrows(NullPointerException.class, () -> new StepUpPolicy(true, amr, acr, nullMaxAge)),
                () -> assertThrows(IllegalArgumentException.class, () -> StepUpPolicy.of(Duration.ZERO)),
                () -> assertThrows(IllegalArgumentException.class, () -> StepUpPolicy.of(negative)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new StepUpPolicy(false, amr, acr, Duration.ZERO), "a given max-age of a disabled policy"),
                () -> assertEquals(Duration.ofNanos(1), StepUpPolicy.of(Duration.ofNanos(1)).maxAge(),
                        "the smallest positive duration"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: an auth_time exactly step-up.max-age old holds, one moment older does not")
    void maxAgeBoundary() {
        var policy = StepUpPolicy.of(MAX_AGE);
        var amr = List.of(StepUpPolicy.MFA);

        assertAll(
                () -> assertTrue(policy.holds(claims(NOW.minus(MAX_AGE), null, amr), NOW), "exactly max-age"),
                () -> assertFalse(policy.holds(claims(NOW.minus(MAX_AGE).minusNanos(1), null, amr), NOW),
                        "one nanosecond older"),
                () -> assertTrue(policy.holds(claims(NOW, null, amr), NOW), "authenticated at the instant"),
                () -> assertTrue(policy.holds(claims(NOW.plusSeconds(1), null, amr), NOW),
                        "an auth_time after the instant counts as not older"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: the largest step-up.max-age is compared without leaving the range of Instant")
    void largestMaxAge() {
        var policy = StepUpPolicy.of(Duration.ofSeconds(Long.MAX_VALUE));

        assertTrue(policy.holds(claims(Instant.EPOCH, null, List.of(StepUpPolicy.MFA)), Instant.EPOCH),
                "holds instead of throwing");
    }

    @Test
    @DisplayName("GDR-FORMS-7: a recent auth_time does not make up for a missing multi-factor value")
    void recentWithoutMultiFactorRefused() {
        assertFalse(StepUpPolicy.of(MAX_AGE).holds(claims(NOW, null, List.of("pwd")), NOW), "pwd at the instant");
    }

    @Test
    @DisplayName("GDR-FORMS-7: the sets are copied immutably and null components or arguments are refused")
    void componentsChecked() {
        var amr = new HashSet<>(Set.of(StepUpPolicy.MFA));
        var policy = new StepUpPolicy(true, amr, Set.of(), MAX_AGE);
        amr.add("otp");
        Set<String> none = Set.of();
        Set<String> copied = policy.amr();
        Set<String> nullSet = nullValue();
        IdentityClaims nullClaims = nullValue();
        IdentityClaims mfa = claims(null, List.of(StepUpPolicy.MFA));
        Instant nullInstant = nullValue();
        Transition nullTransition = nullValue();

        assertAll(
                () -> assertEquals(Set.of(StepUpPolicy.MFA), copied, "a later change of the caller's set"),
                () -> assertThrows(UnsupportedOperationException.class, () -> copied.add("otp")),
                () -> assertThrows(NullPointerException.class, () -> new StepUpPolicy(true, nullSet, none, MAX_AGE)),
                () -> assertThrows(NullPointerException.class, () -> new StepUpPolicy(true, none, nullSet, MAX_AGE)),
                () -> assertThrows(NullPointerException.class, () -> policy.holds(nullClaims, NOW)),
                () -> assertThrows(NullPointerException.class, () -> policy.holds(mfa, nullInstant)),
                () -> assertThrows(NullPointerException.class, () -> policy.requires(nullTransition)));
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> @Nullable T nullValue() {
        return null;
    }
}
