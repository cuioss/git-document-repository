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

import java.util.Objects;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import org.jspecify.annotations.Nullable;

/**
 * The result of an operation of {@code git-store} (GDR-GEN-3), of the same shape as {@link GitResult}: its outcome,
 * the value of a success that yields one, and a detail for every non-success.
 * <p>
 * The outcome is either one of the closed list {@link StoreOutcome} or a non-success Git outcome the operation passes
 * on ({@link PassedOn}); only {@link StoreOutcome#OK} is a success.
 * <p>
 * A detail never carries anything GDR-NFR-3 forbids: no token, password, key material, session id, {@code sub},
 * e-mail address or person name, no configured value and no URL with user information. The result cannot check this:
 * whoever calls the canonical constructor or {@link #of(StoreOutcome, String)} must pass a detail that is already
 * safe, and {@link #passOn(GitResult)} keeps the detail of the Git result it passes on.
 *
 * @param outcome the outcome
 * @param value   the value of a success that yields one; {@code null} otherwise, and always for a non-success
 * @param detail  the detail, present for every non-success; {@code null} for {@link StoreOutcome#OK}
 * @param <V>     the value type
 * @since 0.1
 */
public record StoreResult<V>(Outcome outcome, @Nullable V value, @Nullable String detail) {

    /**
     * The outcome of a {@link StoreResult}: one of the closed list {@link StoreOutcome}, or a non-success Git outcome
     * passed on as {@link PassedOn}.
     *
     * @since 0.1
     */
    public sealed interface Outcome permits StoreOutcome, PassedOn {
    }

    /**
     * A non-success Git outcome an operation of {@code git-store} passes on, for example
     * {@link GitOutcome#REFUSED_BY_POLICY} for a registered remote the remote policy does not permit.
     *
     * @param git the Git outcome passed on, never {@link GitOutcome#OK}
     * @since 0.1
     */
    public record PassedOn(GitOutcome git) implements Outcome {

        /**
         * Checks that the Git outcome is a non-success.
         *
         * @param git the Git outcome passed on
         * @throws NullPointerException     if {@code git} is {@code null}
         * @throws IllegalArgumentException if {@code git} is {@link GitOutcome#OK}
         */
        public PassedOn {
            Objects.requireNonNull(git, "git");
            if (git == GitOutcome.OK) {
                throw new IllegalArgumentException("a success is not passed on");
            }
        }
    }

    /**
     * Checks the invariants of the result.
     *
     * @param outcome the outcome
     * @param value   the value, {@code null} unless the outcome is {@link StoreOutcome#OK}
     * @param detail  the detail, already free of anything GDR-NFR-3 forbids
     * @throws NullPointerException     if {@code outcome} is {@code null}
     * @throws IllegalArgumentException if a success carries a detail, or a non-success carries a value, no detail or a
     *                                  blank one
     */
    public StoreResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == StoreOutcome.OK) {
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
     * Returns the name of an outcome as the log records of the store name it: the constant of {@link StoreOutcome}, or
     * that of the passed-on {@link GitOutcome}.
     *
     * @param outcome the outcome
     * @return the name of the constant
     */
    static String name(Outcome outcome) {
        return switch (outcome) {
            case StoreOutcome store -> store.name();
            case PassedOn(GitOutcome git) -> git.name();
        };
    }

    /**
     * Creates a success carrying a value.
     *
     * @param value the value
     * @param <V>   the value type
     * @return an {@link StoreOutcome#OK} result with the value and no detail
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static <V> StoreResult<V> ok(V value) {
        return new StoreResult<>(StoreOutcome.OK, Objects.requireNonNull(value, "value"), null);
    }

    /**
     * Creates a non-success of the {@code git-store} list.
     *
     * @param outcome the non-success outcome
     * @param detail  the detail, not blank and already free of anything GDR-NFR-3 forbids
     * @param <V>     the value type
     * @return a result without value
     * @throws IllegalArgumentException if {@code outcome} is {@link StoreOutcome#OK} or {@code detail} is blank
     */
    public static <V> StoreResult<V> of(StoreOutcome outcome, String detail) {
        if (outcome == StoreOutcome.OK) {
            throw new IllegalArgumentException("use ok(value) for a success");
        }
        return new StoreResult<>(outcome, null, detail);
    }

    /**
     * Passes on the non-success of a Git result, keeping its outcome and its detail.
     *
     * @param result the non-success Git result
     * @param <V>    the value type
     * @return a result with the outcome {@link PassedOn} of the Git outcome, the Git detail and no value
     * @throws NullPointerException     if {@code result} is {@code null}
     * @throws IllegalArgumentException if {@code result} is a success
     */
    public static <V> StoreResult<V> passOn(GitResult<?> result) {
        Objects.requireNonNull(result, "result");
        if (result.isOk()) {
            throw new IllegalArgumentException("a success is not passed on");
        }
        return new StoreResult<>(new PassedOn(result.outcome()), null, result.detail());
    }

    /**
     * Tells whether the operation succeeded.
     *
     * @return whether the outcome is {@link StoreOutcome#OK}
     */
    public boolean isOk() {
        return outcome == StoreOutcome.OK;
    }
}
