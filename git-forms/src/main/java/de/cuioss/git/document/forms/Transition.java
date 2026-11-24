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

/**
 * A transition of a workflow (GDR-FORMS-5, GDR-FORMS-7): from one state to another on a mechanism of the store.
 * <p>
 * Whether the transition requires step-up is resolved when the workflow is loaded: from the declaration
 * {@code step-up}, or by default, which requires it for the mechanism {@link Mechanism#RELEASE} only. A transition on
 * {@link Mechanism#INTERMEDIATE_SAVE} keeps its state and never requires step-up.
 *
 * @param name      the name of the transition, unique within its workflow
 * @param from      the state the transition leaves
 * @param to        the state the transition enters
 * @param mechanism the mechanism of the store
 * @param stepUp    whether the transition requires step-up
 * @since 0.1
 */
public record Transition(String name, String from, String to, Mechanism mechanism, boolean stepUp) {

    /**
     * Checks the components.
     *
     * @param name      the name
     * @param from      the state left
     * @param to        the state entered
     * @param mechanism the mechanism
     * @param stepUp    whether step-up is required
     * @throws NullPointerException     if a component is {@code null}
     * @throws IllegalArgumentException if a transition on {@link Mechanism#INTERMEDIATE_SAVE} changes the state or
     *                                  requires step-up
     */
    public Transition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(mechanism, "mechanism");
        if (mechanism == Mechanism.INTERMEDIATE_SAVE && (stepUp || !from.equals(to))) {
            throw new IllegalArgumentException("an intermediate-save keeps its state and requires no step-up");
        }
    }

    /**
     * The step-up a transition requires without a declaration (GDR-FORMS-7).
     *
     * @param mechanism the mechanism of the transition
     * @return {@code true} for {@link Mechanism#RELEASE}, {@code false} otherwise
     * @throws NullPointerException if {@code mechanism} is {@code null}
     */
    public static boolean defaultStepUp(Mechanism mechanism) {
        return Objects.requireNonNull(mechanism, "mechanism") == Mechanism.RELEASE;
    }
}
