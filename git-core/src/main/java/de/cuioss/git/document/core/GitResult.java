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
package de.cuioss.git.document.core;

import java.util.Objects;


import org.jspecify.annotations.Nullable;

/**
 * The result of an operation (GDR-GEN-3): its outcome, the value of a success that yields one, and a detail for every
 * non-success (the failing step, the refused remote or capability, the rejected reference).
 * <p>
 * A detail never carries anything GDR-NFR-3 forbids: no token, password, key material, session id, {@code sub},
 * e-mail address or person name, and no URL with user information.
 *
 * @param outcome the outcome
 * @param value   the value of a success that yields one; {@code null} otherwise
 * @param detail  the detail, present for every non-success; {@code null} for {@link GitOutcome#OK}
 * @param <V>     the value type
 * @since 0.1
 */
public record GitResult<V>(GitOutcome outcome, @Nullable V value, @Nullable String detail) {

    /**
     * Checks the invariants of the result.
     *
     * @param outcome the outcome
     * @param value   the value
     * @param detail  the detail
     * @throws NullPointerException     if {@code outcome} is {@code null}
     * @throws IllegalArgumentException if a non-success carries no detail or a blank one
     */
    public GitResult {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome != GitOutcome.OK && (detail == null || detail.isBlank())) {
            throw new IllegalArgumentException("a non-success needs a detail: " + outcome);
        }
    }

    /**
     * Creates a success carrying a value.
     *
     * @param value the value
     * @param <V>   the value type
     * @return an {@link GitOutcome#OK} result with the value and no detail
     * @throws NullPointerException if {@code value} is {@code null}
     */
    public static <V> GitResult<V> ok(V value) {
        return new GitResult<>(GitOutcome.OK, Objects.requireNonNull(value, "value"), null);
    }

    /**
     * Creates a non-success.
     *
     * @param outcome the non-success outcome
     * @param detail  the detail, not blank
     * @param <V>     the value type
     * @return a result without value
     * @throws IllegalArgumentException if {@code outcome} is {@link GitOutcome#OK} or {@code detail} is blank
     */
    public static <V> GitResult<V> of(GitOutcome outcome, String detail) {
        if (outcome == GitOutcome.OK) {
            throw new IllegalArgumentException("use ok(value) for a success");
        }
        return new GitResult<>(outcome, null, detail);
    }

    /**
     * Tells whether the operation succeeded.
     *
     * @return whether the outcome is {@link GitOutcome#OK}
     */
    public boolean isOk() {
        return outcome == GitOutcome.OK;
    }
}
