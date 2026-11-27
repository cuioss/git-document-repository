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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The step-up declaration of a transition (GDR-FORMS-7): without a declaration a transition on {@code release}
 * requires step-up and one on {@code save} or {@code intermediate-save} does not; an explicit {@code step-up} is
 * honoured in both directions, as the deviating example of the specification shows; and a workflow that declares
 * step-up on an {@code intermediate-save} transition is refused when it is loaded.
 * <p>
 * The workflows are literals, being the rows of the specification.
 */
@DisplayName("Workflow step-up declaration (GDR-FORMS-7)")
class WorkflowStepUpTest {

    private static final String STATES = "states: [draft, review, approved]\n";

    private static Transition only(String transitions) {
        return WorkflowTest.workflow(STATES + "transitions:\n" + transitions).transitions().getFirst();
    }

    @Test
    @DisplayName("GDR-FORMS-7: without a declaration, release requires step-up, save and intermediate-save do not")
    void defaults() {
        assertAll(
                () -> assertTrue(only("  - { name: a, from: review, to: approved, mechanism: release }\n")
                        .stepUp(), "release"),
                () -> assertFalse(only("  - { name: a, from: draft, to: review, mechanism: save }\n")
                        .stepUp(), "save"),
                () -> assertFalse(only("  - { name: a, from: draft, to: draft, mechanism: intermediate-save }\n")
                        .stepUp(), "intermediate-save"),
                () -> assertTrue(Transition.defaultStepUp(Mechanism.RELEASE), "the default of release"),
                () -> assertFalse(Transition.defaultStepUp(Mechanism.SAVE), "the default of save"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: an explicit step-up: true on save and step-up: false on release are honoured")
    void explicit() {
        assertAll(
                () -> assertTrue(only("  - { name: a, from: draft, to: review, mechanism: save, step-up: true }\n")
                        .stepUp(), "save with step-up"),
                () -> assertFalse(only(
                                "  - { name: a, from: review, to: approved, mechanism: release, step-up: false }\n").stepUp(),
                        "release without step-up"),
                () -> assertFalse(only(
                        "  - { name: a, from: draft, to: draft, mechanism: intermediate-save, step-up: false }\n")
                        .stepUp(), "an intermediate-save may declare false"));
    }

    @Test
    @DisplayName("GDR-FORMS-7: the deviating example of the specification: submit requires step-up, approve does not")
    void deviatingExample() {
        var workflow = WorkflowTest.workflow(STATES + """
                transitions:
                  - { name: submit,  from: draft,  to: review,   mechanism: save,    step-up: true }
                  - { name: approve, from: review, to: approved, mechanism: release, step-up: false }
                """);

        assertEquals(List.of(new Transition("submit", "draft", "review", Mechanism.SAVE, true),
                        new Transition("approve", "review", "approved", Mechanism.RELEASE, false)), workflow.transitions(),
                "both directions");
    }

    @Test
    @DisplayName("GDR-FORMS-7: a workflow that declares step-up on an intermediate-save transition is refused at load")
    void intermediateSaveRefused() {
        var result = WorkflowTest.parse(STATES + """
                transitions:
                  - { name: edit, from: draft, to: draft, mechanism: intermediate-save, step-up: true }
                """);

        assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, WorkflowTest.PATH
                + ": transitions[0].step-up: not allowed for the mechanism intermediate-save")), result, "refused");
    }

    @Test
    @DisplayName("GDR-FORMS-7: an intermediate-save declaring step-up is refused at load whatever step-up.enabled says")
    void intermediateSaveRefusedWhateverTheSwitch() {
        var refusal = FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, WorkflowTest.PATH
                + ": transitions[0].step-up: not allowed for the mechanism intermediate-save"));
        var edit = only("  - { name: edit, from: draft, to: draft, mechanism: intermediate-save }\n");

        for (var policy : List.of(StepUpPolicy.of(Duration.parse("PT5M")), StepUpPolicy.disabled())) {
            var result = WorkflowTest.parse(STATES + """
                    transitions:
                      - { name: edit, from: draft, to: draft, mechanism: intermediate-save, step-up: true }
                    """);

            assertAll("enabled: " + policy.enabled(),
                    () -> assertEquals(refusal, result, "refused at load, the workflow knows no policy"),
                    () -> assertFalse(policy.requires(edit), "an intermediate save never requires step-up"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-7: a transition record on intermediate-save with step-up is a programming error")
    void recordRefusesIntermediateSaveStepUp() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Transition("edit", "draft", "draft", Mechanism.INTERMEDIATE_SAVE, true)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Transition("edit", "draft", "review", Mechanism.INTERMEDIATE_SAVE, false)));
    }
}
