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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The result of a schema check (GDR-FORMS-2), which carries its findings as data.
 * <p>
 * A validation result has the shape GDR-GEN-3 prescribes, an {@linkplain #outcome() outcome} from the closed
 * {@link FormsOutcome} list and a {@linkplain #detail() detail} for every non-success, plus the data of its variant:
 * the violations of {@link Invalid}, the JSON Pointers of {@link MandatoryFieldsMissing}. It composes that shape
 * instead of extending {@link FormsResult}, as {@code MergeResult} of {@code git-core} does, because a non-success that
 * names its findings would break the invariant of a result without value. The detail names the number of findings
 * only; a caller reads the findings from the variant and never parses a detail. A caller prevents the save on every
 * result that is not {@link Valid}.
 *
 * @since 0.1
 */
public sealed interface ValidationResult
        permits ValidationResult.Valid, ValidationResult.Invalid, ValidationResult.MandatoryFieldsMissing {

    /**
     * The outcome of the check.
     *
     * @return {@link FormsOutcome#OK} for {@link Valid}, {@link FormsOutcome#INVALID} for {@link Invalid},
     *         {@link FormsOutcome#MANDATORY_FIELDS_MISSING} for {@link MandatoryFieldsMissing}
     */
    FormsOutcome outcome();

    /**
     * The detail of a non-success.
     *
     * @return the number of findings, present exactly when the check did not succeed
     */
    Optional<String> detail();

    /**
     * The data satisfies the schema.
     *
     * @since 0.1
     */
    record Valid() implements ValidationResult {

        /**
         * The outcome of a passed check.
         *
         * @return {@link FormsOutcome#OK}
         */
        @Override
        public FormsOutcome outcome() {
            return FormsOutcome.OK;
        }

        /**
         * A success carries no detail.
         *
         * @return an empty detail
         */
        @Override
        public Optional<String> detail() {
            return Optional.empty();
        }
    }

    /**
     * The data violates the schema.
     *
     * @param violations every violation, distinct, in the order the validator reported them; never empty
     * @since 0.1
     */
    record Invalid(List<Violation> violations) implements ValidationResult {

        /**
         * Removes duplicates and copies the violations.
         *
         * @param violations the violations
         * @throws NullPointerException     if {@code violations} or one of its elements is {@code null}
         * @throws IllegalArgumentException if {@code violations} is empty
         */
        public Invalid {
            Objects.requireNonNull(violations, "violations");
            violations.forEach(violation -> Objects.requireNonNull(violation, "violations"));
            if (violations.isEmpty()) {
                throw new IllegalArgumentException("violations: an invalid result names at least one violation");
            }
            violations = List.copyOf(new LinkedHashSet<>(violations));
        }

        /**
         * The outcome of a violated schema.
         *
         * @return {@link FormsOutcome#INVALID}
         */
        @Override
        public FormsOutcome outcome() {
            return FormsOutcome.INVALID;
        }

        /**
         * The detail, naming the number of violations only.
         *
         * @return {@code "N violations"}
         */
        @Override
        public Optional<String> detail() {
            return Optional.of(violations.size() + " violations");
        }
    }

    /**
     * Mandatory fields of the schema are missing in the data.
     *
     * @param pointers the JSON Pointers (RFC 6901) of the missing fields, distinct; never empty
     * @since 0.1
     */
    record MandatoryFieldsMissing(List<String> pointers) implements ValidationResult {

        /**
         * Removes duplicates and copies the pointers.
         *
         * @param pointers the JSON Pointers
         * @throws NullPointerException     if {@code pointers} or one of its elements is {@code null}
         * @throws IllegalArgumentException if {@code pointers} is empty or a pointer does not start with {@code /}
         */
        public MandatoryFieldsMissing {
            Objects.requireNonNull(pointers, "pointers");
            for (String pointer : pointers) {
                Objects.requireNonNull(pointer, "pointers");
                if (!pointer.startsWith("/")) {
                    throw new IllegalArgumentException("pointers: not the JSON Pointer of a field");
                }
            }
            if (pointers.isEmpty()) {
                throw new IllegalArgumentException("pointers: a result names at least one missing field");
            }
            pointers = List.copyOf(new LinkedHashSet<>(pointers));
        }

        /**
         * The outcome of missing mandatory fields.
         *
         * @return {@link FormsOutcome#MANDATORY_FIELDS_MISSING}
         */
        @Override
        public FormsOutcome outcome() {
            return FormsOutcome.MANDATORY_FIELDS_MISSING;
        }

        /**
         * The detail, naming the number of missing fields only.
         *
         * @return {@code "N mandatory fields missing"}
         */
        @Override
        public Optional<String> detail() {
            return Optional.of(pointers.size() + " mandatory fields missing");
        }
    }
}
