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


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.forms.CloneReader.ReadFailure;
import de.cuioss.git.document.forms.DocumentTransitions.Result;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.SaveResult;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.StoreResult;

/**
 * The results of {@link DocumentTransitions} that end a transition without performing it, and the name of an outcome
 * in the log. A detail names a path, a reference or the problem, never a data value, a pseudonym or a claim
 * (GDR-NFR-3).
 */
final class TransitionOutcomes {

    private TransitionOutcomes() {
    }

    /**
     * The result of an I/O failure while reading the clone: the passed-on {@link GitOutcome#FAILED}, its detail naming
     * the kind of failure only.
     *
     * @param failure the failure
     * @return the result
     */
    static Result readFailed(ReadFailure failure) {
        return new Result.NotPerformed(new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.FAILED)),
                "read: " + failure.getCause().getClass().getSimpleName());
    }

    /**
     * The result of a {@link RuntimeException} thrown by the checks of a step that ran outside the store's step
     * isolation, the verification of an unchanged save: the passed-on {@link GitOutcome#FAILED}, its detail naming the
     * target branch and the class of the exception by {@link Class#getName()} only, as the store names a step that
     * throws; never its message.
     *
     * @param key       the document
     * @param exception the exception
     * @return the result
     */
    static Result stepFailed(DocumentKey key, RuntimeException exception) {
        return new Result.NotPerformed(new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.FAILED)),
                StoreReferences.target(key) + ": step: " + exception.getClass().getName());
    }

    /**
     * The result of a refused step-up check (GDR-FORMS-7).
     *
     * @return {@link FormsOutcome#STEP_UP_REQUIRED} with {@link DocumentTransitions#STEP_UP_DETAIL}
     */
    static Result stepUpRequired() {
        return new Result.NotPerformed(FormsOutcome.STEP_UP_REQUIRED, DocumentTransitions.STEP_UP_DETAIL);
    }

    /**
     * The result of a release that completed only an earlier, incomplete release of an older state, the branch having
     * moved on since ({@link de.cuioss.git.document.store.Release#branchMovedOn()}): the state the transition saved is
     * not released (GDR-FORMS-5).
     *
     * @return {@link FormsOutcome#SAVED_NOT_RELEASED} with {@link DocumentTransitions#SAVED_NOT_RELEASED_DETAIL}
     */
    static Result savedNotReleased() {
        return new Result.NotPerformed(FormsOutcome.SAVED_NOT_RELEASED, DocumentTransitions.SAVED_NOT_RELEASED_DETAIL);
    }

    /**
     * The result of a non-success of the forms.
     *
     * @param result the non-success
     * @return its outcome and detail
     */
    static Result notPerformed(FormsResult<?> result) {
        return new Result.NotPerformed(result.outcome(), Objects.requireNonNull(result.detail()));
    }

    /**
     * The result of a non-success of the store, passed on.
     *
     * @param result the non-success
     * @return its outcome, passed on, and its detail
     */
    static Result passOn(StoreResult<?> result) {
        return new Result.NotPerformed(new FormsResult.PassedOn(result.outcome()),
                Objects.requireNonNull(result.detail()));
    }

    /**
     * Maps a save that did not succeed: a refusal of the step to what the step captured, anything else passed on.
     *
     * @param step     the step of the save
     * @param notSaved the save that did not succeed
     * @return the result
     */
    static Result refused(TransitionStep step, SaveResult.NotSaved notSaved) {
        if (notSaved.outcome() != StoreOutcome.REFUSED || step.captured().isEmpty()) {
            return new Result.NotPerformed(new FormsResult.PassedOn(notSaved.outcome()), notSaved.reason());
        }
        return fromCapture(step);
    }

    /**
     * The result of a refusal the step captured: the findings of a validation, or its outcome and detail.
     *
     * @param step the step, which captured a refusal
     * @return the result
     */
    static Result fromCapture(TransitionStep step) {
        TransitionStep.Captured refusal = step.captured().orElseThrow();
        ValidationResult findings = refusal.findings();
        if (findings != null) {
            return new Result.Refused(findings);
        }
        return new Result.NotPerformed(refusal.outcome(), refusal.detail());
    }

    /**
     * The name of an outcome: the constant of {@link FormsOutcome}, of the store or of Git, so
     * {@link FormsOutcome#SAVED_NOT_RELEASED} is logged as {@code SAVED_NOT_RELEASED}.
     *
     * @param outcome the outcome
     * @return the name of its constant
     */
    static String name(FormsResult.Outcome outcome) {
        return switch (outcome) {
            case FormsOutcome forms -> forms.name();
            case FormsResult.PassedOn(StoreOutcome store) -> store.name();
            case FormsResult.PassedOn(StoreResult.PassedOn(GitOutcome git)) -> git.name();
        };
    }
}
