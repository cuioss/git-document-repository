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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.stream.Stream;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The invariants and factories of {@link StoreResult} (GDR-GEN-3): a success carries a value and no detail, a
 * non-success a non-blank detail and no value; {@link StoreResult.PassedOn} carries a non-success Git outcome only, and
 * {@link StoreResult#passOn(GitResult)} keeps the outcome and the detail of the Git result it passes on.
 */
@EnableGeneratorController
@DisplayName("StoreResult (GDR-GEN-3)")
class StoreResultTest {

    /** Generates a detail text, never blank. */
    private static String detail() {
        return Generators.letterStrings(1, 40).next();
    }

    /** Every outcome of {@code git-store} except {@link StoreOutcome#OK}. */
    private static Stream<StoreOutcome> storeNonSuccesses() {
        return Arrays.stream(StoreOutcome.values()).filter(outcome -> outcome != StoreOutcome.OK);
    }

    /** Every Git outcome except {@link GitOutcome#OK}. */
    private static Stream<GitOutcome> gitNonSuccesses() {
        return Arrays.stream(GitOutcome.values()).filter(outcome -> outcome != GitOutcome.OK);
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> T nullValue() {
        // the null literal converts to every reference type, so no unchecked cast is needed
        return null;
    }

    @Nested
    @DisplayName("Success")
    class Success {

        @Test
        @DisplayName("GDR-GEN-3: ok carries the value, outcome OK and no detail")
        void okCarriesValue() {
            var value = detail();

            var result = StoreResult.ok(value);

            assertAll(
                    () -> assertEquals(StoreOutcome.OK, result.outcome(), "the outcome is OK"),
                    () -> assertEquals(value, result.value(), "the value is kept"),
                    () -> assertNull(result.detail(), "a success carries no detail"),
                    () -> assertTrue(result.isOk(), "a success is ok"));
        }

        @Test
        @DisplayName("GDR-GEN-3: a success without a value is allowed")
        void successWithoutValue() {
            var result = new StoreResult<String>(StoreOutcome.OK, null, null);

            assertAll(
                    () -> assertTrue(result.isOk(), "a success is ok"),
                    () -> assertNull(result.value(), "no value"));
        }

        @Test
        @DisplayName("GDR-GEN-3: a success refuses a detail, ok refuses a null value")
        void refusesDetailAndNullValue() {
            var value = detail();
            var text = detail();
            String nullText = nullValue();

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new StoreResult<>(StoreOutcome.OK, value, text), "a success with a detail"),
                    () -> assertThrows(NullPointerException.class, () -> StoreResult.ok(nullText),
                            "ok with a null value"));
        }
    }

    @Nested
    @DisplayName("Non-success")
    class NonSuccess {

        @Test
        @DisplayName("GDR-GEN-3: of carries every non-success outcome with its detail and without value")
        void ofCarriesOutcomeAndDetail() {
            var text = detail();

            assertAll(storeNonSuccesses().<Executable>map(outcome -> () -> {
                StoreResult<String> result = StoreResult.of(outcome, text);
                assertAll(
                        () -> assertEquals(outcome, result.outcome(), "the outcome is kept"),
                        () -> assertEquals(text, result.detail(), "the detail is kept"),
                        () -> assertNull(result.value(), "a non-success carries no value"),
                        () -> assertFalse(result.isOk(), "a non-success is not ok"));
            }));
        }

        @Test
        @DisplayName("GDR-GEN-3: a non-success refuses no detail, a blank detail and a value")
        void refusesMissingDetailAndValue() {
            var value = detail();
            var text = detail();

            assertAll(Stream.<Executable>of(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new StoreResult<String>(StoreOutcome.MALFORMED, null, null), "no detail"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new StoreResult<String>(StoreOutcome.MALFORMED, null, " "), "a blank detail"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new StoreResult<String>(StoreOutcome.MALFORMED, null, ""), "an empty detail"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new StoreResult<>(StoreOutcome.MALFORMED, value, text), "a value"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> StoreResult.of(StoreOutcome.MALFORMED, " "), "of with a blank detail")));
        }

        @Test
        @DisplayName("GDR-GEN-3: of refuses OK, the canonical constructor a null outcome")
        void refusesOkAndNullOutcome() {
            var text = detail();
            StoreResult.Outcome nullOutcome = nullValue();

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> StoreResult.of(StoreOutcome.OK, text),
                            "of with OK"),
                    () -> assertThrows(NullPointerException.class,
                            () -> new StoreResult<String>(nullOutcome, null, text), "a null outcome"));
        }
    }

    @Nested
    @DisplayName("PassedOn")
    class PassedOn {

        @Test
        @DisplayName("GDR-GEN-3: PassedOn carries every non-success Git outcome")
        void carriesGitOutcome() {
            assertAll(gitNonSuccesses().<Executable>map(
                    outcome -> () -> assertEquals(outcome, new StoreResult.PassedOn(outcome).git(),
                            "the Git outcome is kept")));
        }

        @Test
        @DisplayName("GDR-GEN-3: PassedOn refuses OK and null")
        void refusesOkAndNull() {
            GitOutcome nullOutcome = nullValue();

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> new StoreResult.PassedOn(GitOutcome.OK),
                            "a success is not passed on"),
                    () -> assertThrows(NullPointerException.class, () -> new StoreResult.PassedOn(nullOutcome),
                            "a null outcome"));
        }

        @Test
        @DisplayName("GDR-GEN-3: passOn keeps the outcome and the detail of the Git result, without value")
        void passOnKeepsOutcomeAndDetail() {
            var text = detail();

            assertAll(gitNonSuccesses().<Executable>map(outcome -> () -> {
                StoreResult<String> result = StoreResult.passOn(GitResult.of(outcome, text));
                assertAll(
                        () -> assertEquals(new StoreResult.PassedOn(outcome), result.outcome(),
                                "the Git outcome is passed on"),
                        () -> assertEquals(text, result.detail(), "the Git detail is kept"),
                        () -> assertNull(result.value(), "a passed-on non-success carries no value"),
                        () -> assertFalse(result.isOk(), "a passed-on non-success is not ok"));
            }));
        }

        @Test
        @DisplayName("GDR-GEN-3: passOn refuses a success and null")
        void passOnRefusesSuccessAndNull() {
            var success = GitResult.ok(detail());
            GitResult<String> nullResult = nullValue();

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> StoreResult.passOn(success),
                            "a success is not passed on"),
                    () -> assertThrows(NullPointerException.class, () -> StoreResult.passOn(nullResult),
                            "a null result"));
        }
    }
}
