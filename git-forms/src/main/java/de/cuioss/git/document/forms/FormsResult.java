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

import java.util.Objects;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The result of an operation of {@code git-forms} (GDR-GEN-3), of the same shape as {@link StoreResult}: its outcome,
 * the value of a success that yields one, and a detail for every non-success.
 * <p>
 * The outcome is either one of the closed list {@link FormsOutcome} or a non-success outcome of the store the
 * operation passes on ({@link PassedOn}), which may itself be a Git outcome the store passed on; only
 * {@link FormsOutcome#OK} is a success.
 * <p>
 * A detail never carries anything GDR-NFR-3 forbids: no token, password, key material, session id, {@code sub},
 * e-mail address or person name, and no configured or entered value. The result cannot check this: whoever calls the
 * canonical constructor or {@link #of(FormsOutcome, String)} must pass a detail that is already safe, and
 * {@link #passOn(StoreResult)} keeps the detail of the store result it passes on.
 *
 * @param outcome the outcome
 * @param value   the value of a success that yields one; {@code null} otherwise, and always for a non-success
 * @param detail  the detail, present for every non-success; {@code null} for {@link FormsOutcome#OK}
 * @param <V>     the value type
 * @since 0.1
 */
public record FormsResult<V>(Outcome outcome, @Nullable V value, @Nullable String detail) {

    /**
     * The outcome of a {@link FormsResult}: one of the closed list {@link FormsOutcome}, or a non-success outcome of
     * the store passed on as {@link PassedOn}.
     *
     * @since 0.1
     */
    public sealed interface Outcome permits FormsOutcome, PassedOn {
    }

    /**
     * A non-success outcome of the store an operation of {@code git-forms} passes on, for example
     * {@link StoreOutcome#MALFORMED} for a configuration it cannot read.
     *
     * @param store the outcome of the store passed on, never {@link StoreOutcome#OK}
     * @since 0.1
     */
    public record PassedOn(StoreResult.Outcome store) implements Outcome {

        /**
         * Checks that the outcome of the store is a non-success.
         *
         * @param store the outcome of the store passed on
         * @throws NullPointerException     if {@code store} is {@code null}
         * @throws IllegalArgumentException if {@code store} is {@link StoreOutcome#OK}
         */
        public PassedOn {
            Objects.requireNonNull(store, "store");
            if (store == StoreOutcome.OK) {
                throw new IllegalArgumentException("a success is not passed on");
            }
        }
    }

    /**
     * Checks the invariants of the result.
     *
     * @param outcome the outcome
     * @param value   the value, {@code null} unless the outcome is {@link FormsOutcome#OK}
     * @param detail  the detail, already free of anything GDR-NFR-3 forbids
     * @throws NullPointerException     if {@code outcome} is {@code null}
     * @throws IllegalArgumentException if a success carries a detail, or a non-success carries a value, no detail or a
     *                                  blank one
     */
    public FormsResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == FormsOutcome.OK) {
            if (detail != null) {
                throw new IllegalArgumentException("a success carries no detail");
            }
        } else {
            if (detail == null || detail.isBlank()) {
                throw new IllegalArgumentException("a non-success needs a detail: " + outcome);
            }
            if (value != null) {
                throw new IllegalArgumentException("a non-success carries no value: " + outcome);
            }
        }
    }

    /**
     * Creates a success carrying a value.
     *
     * @param value the value
     * @param <V>   the value type
     * @return an {@link FormsOutcome#OK} result with the value and no detail
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static <V> FormsResult<V> ok(V value) {
        return new FormsResult<>(FormsOutcome.OK, Objects.requireNonNull(value, "value"), null);
    }

    /**
     * Creates a non-success of the {@code git-forms} list.
     *
     * @param outcome the non-success outcome
     * @param detail  the detail, not blank and already free of anything GDR-NFR-3 forbids
     * @param <V>     the value type
     * @return a result without value
     * @throws IllegalArgumentException if {@code outcome} is {@link FormsOutcome#OK} or {@code detail} is blank
     */
    public static <V> FormsResult<V> of(FormsOutcome outcome, String detail) {
        if (outcome == FormsOutcome.OK) {
            throw new IllegalArgumentException("use ok(value) for a success");
        }
        return new FormsResult<>(outcome, null, detail);
    }

    /**
     * Passes on the non-success of a store result, keeping its outcome and its detail.
     *
     * @param result the non-success store result
     * @param <V>    the value type
     * @return a result with the outcome {@link PassedOn} of the store outcome, the store detail and no value
     * @throws NullPointerException     if {@code result} is {@code null}
     * @throws IllegalArgumentException if {@code result} is a success
     */
    public static <V> FormsResult<V> passOn(StoreResult<?> result) {
        Objects.requireNonNull(result, "result");
        if (result.isOk()) {
            throw new IllegalArgumentException("a success is not passed on");
        }
        return new FormsResult<>(new PassedOn(result.outcome()), null, result.detail());
    }

    /**
     * Tells whether the operation succeeded.
     *
     * @return whether the outcome is {@link FormsOutcome#OK}
     */
    public boolean isOk() {
        return outcome == FormsOutcome.OK;
    }
}
