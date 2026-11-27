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

import java.time.temporal.TemporalAmount;
import java.util.Objects;

/**
 * A deadline of a workflow per state (GDR-FORMS-6), as the workflow file declares it under {@code deadlines}.
 * <p>
 * A duration is an ISO 8601 duration, read as a {@link java.time.Period} ({@code P14D}, {@code P1Y}) or, when it has a
 * time part, as a {@link java.time.Duration} ({@code PT12H}). {@link Deadlines} evaluates a deadline, computed and
 * never stored, against the instant at which the document entered its current state:
 * <ul>
 * <li>{@link Within} is due at the entering instant plus the amount;</li>
 * <li>{@link ReviewInterval} is due at the latest {@code entered + k * amount}, {@code k} at least 1, that is at or
 * before the instant asked about, so it is due again at every multiple of the interval while the state lasts;</li>
 * <li>{@link DateField} is due at the value of the top-level field it names: an ISO 8601 date ({@code 2026-12-01}),
 * due at the start of that day in UTC, or a date-time with an offset ({@code 2026-12-01T09:00:00+01:00}); a missing
 * field, a value that is no string or a string of neither form gives no deadline.</li>
 * </ul>
 * A {@link java.time.Period} is added in UTC, as calendar months and days of an {@link java.time.OffsetDateTime} at
 * {@link java.time.ZoneOffset#UTC} (so {@code P1M} from 31 January ends on the last day of February); a
 * {@link java.time.Duration} is added to the instant. A deadline is due at an instant when its due instant is at or
 * before it: the bound is inclusive.
 *
 * @param state the state the deadline belongs to, a state of its workflow
 * @param rule  the rule of the deadline
 * @since 0.1
 */
public record Deadline(String state, Rule rule) {

    /**
     * Checks the components.
     *
     * @param state the state
     * @param rule  the rule
     * @throws NullPointerException if a component is {@code null}
     */
    public Deadline {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rule, "rule");
    }

    /**
     * The rule of a deadline: exactly one of the keys {@code within}, {@code review-interval} and {@code field}.
     *
     * @since 0.1
     */
    public sealed interface Rule permits Within, ReviewInterval, DateField {
    }

    /**
     * Due after entering the state: the key {@code within}.
     *
     * @param amount the positive duration after entering the state
     * @since 0.1
     */
    public record Within(TemporalAmount amount) implements Rule {

        /**
         * Checks the amount.
         *
         * @param amount the amount
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public Within {
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * Due periodically while in the state: the key {@code review-interval}.
     *
     * @param amount the positive interval
     * @since 0.1
     */
    public record ReviewInterval(TemporalAmount amount) implements Rule {

        /**
         * Checks the amount.
         *
         * @param amount the amount
         * @throws NullPointerException if {@code amount} is {@code null}
         */
        public ReviewInterval {
            Objects.requireNonNull(amount, "amount");
        }
    }

    /**
     * Due at the date of a data field: the key {@code field}.
     *
     * @param field the name of the field
     * @since 0.1
     */
    public record DateField(String field) implements Rule {

        /**
         * Checks the field.
         *
         * @param field the field
         * @throws NullPointerException if {@code field} is {@code null}
         */
        public DateField {
            Objects.requireNonNull(field, "field");
        }
    }
}
