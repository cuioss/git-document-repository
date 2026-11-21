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

/**
 * The closed list of outcomes of {@code git-forms} (GDR-GEN-3).
 * <p>
 * An operation of {@code git-forms} reports either one of these constants or a non-success outcome of the store it
 * passes on ({@link FormsResult.PassedOn}), always in a {@link FormsResult}; an expected non-success is never
 * signalled by an exception. Only {@link #OK} is a success. The list carries no domain term.
 *
 * @since 0.1
 */
public enum FormsOutcome implements FormsResult.Outcome {

    /** Success: the operation completed. */
    OK,

    /** Invalid: raised by schema validation, by typed links and by a person field without a known pseudonym. */
    INVALID,

    /** Mandatory fields missing: raised by a transition that checks {@code required} (GDR-FORMS-2). */
    MANDATORY_FIELDS_MISSING,

    /** Mismatch: raised by the check of the generated output against its data. */
    MISMATCH,

    /** Transition refused: raised by the workflow for a transition it does not allow. */
    TRANSITION_REFUSED,

    /** Step-up required: raised by the workflow for a transition that requires step-up the caller does not hold. */
    STEP_UP_REQUIRED
}
