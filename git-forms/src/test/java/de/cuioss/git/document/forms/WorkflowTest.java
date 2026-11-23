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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The workflow (GDR-FORMS-5): the default workflow of the specification, a workflow file with its own states, every
 * refusal at load as the passed-on {@link StoreOutcome#MALFORMED} naming the file, the key path and the rule, and
 * {@link FormsOutcome#TRANSITION_REFUSED} for a transition the workflow does not allow. No detail carries a configured
 * value (GDR-NFR-3).
 * <p>
 * The default workflow and the rules are literals, being the contract; the names of states and transitions of a
 * workflow file are generated from upper-case letters, which no fixed text of a detail contains.
 */
@EnableGeneratorController
@DisplayName("Workflow (GDR-FORMS-5)")
class WorkflowTest {

    static final String PATH = "workflows/approval.yaml";

    private static final TypedGenerator<String> NAMES =
            Generators.uniqueValues(Generators.strings("ABCDEFGHIJKLMNOPQRSTUVWXYZ", 12, 20));

    /** A run of upper-case letters as long as a generated name, which no fixed text of a detail holds. */
    private static final Pattern GENERATED = Pattern.compile("[A-Z]{12,}");

    static String name() {
        return NAMES.next();
    }

    private static String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static FormsResult<Workflow> parse(String text) {
        return Workflow.parse(PATH, text.getBytes(StandardCharsets.UTF_8));
    }

    static Workflow workflow(String text) {
        var result = parse(text);
        assertTrue(result.isOk(), () -> "the workflow is read: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the workflow");
    }

    @Nested
    @DisplayName("Default workflow")
    class DefaultWorkflow {

        @Test
        @DisplayName("GDR-FORMS-5: the default workflow has the states, transitions and mechanisms of the specification")
        void statesAndTransitions() {
            var workflow = Workflow.defaultWorkflow();

            assertAll(
                    () -> assertEquals(List.of("draft", "review", "approved", "superseded"), workflow.states(),
                            "the states"),
                    () -> assertEquals("draft", workflow.initialState(), "the first state is the initial state"),
                    () -> assertEquals(List.of(
                                    new Transition("edit", "draft", "draft", Mechanism.INTERMEDIATE_SAVE, false),
                                    new Transition("submit", "draft", "review", Mechanism.SAVE, false),
                                    new Transition("approve", "review", "approved", Mechanism.RELEASE, true),
                                    new Transition("rework", "review", "draft", Mechanism.SAVE, false),
                                    new Transition("revise", "approved", "draft", Mechanism.SAVE, false),
                                    new Transition("supersede", "approved", "superseded", Mechanism.RELEASE, true)),
                            workflow.transitions(), "the transitions with their mechanisms and step-up"));
        }

        @Test
        @DisplayName("GDR-FORMS-5: the default workflow keeps its two deadlines, unevaluated")
        void deadlines() {
            assertEquals(List.of(new Deadline("review", new Deadline.Within(Period.ofDays(14))),
                            new Deadline("approved", new Deadline.ReviewInterval(Period.ofYears(1)))),
                    Workflow.defaultWorkflow().deadlines(), "the deadlines");
        }

        @Test
        @DisplayName("GDR-FORMS-5: a transition is found in its from state and refused otherwise")
        void transitions() {
            var workflow = Workflow.defaultWorkflow();
            var unknown = name();

            assertAll(
                    () -> assertEquals(FormsResult.ok(new Transition("submit", "draft", "review", Mechanism.SAVE,
                            false)), workflow.transition("submit", "draft"), "allowed"),
                    () -> assertEquals(FormsResult.of(FormsOutcome.TRANSITION_REFUSED,
                                    "transition: not allowed in the current state"), workflow.transition("approve", "draft"),
                            "not from the current state"),
                    () -> assertEquals(FormsResult.of(FormsOutcome.TRANSITION_REFUSED,
                                    "transition: not in the workflow"), workflow.transition(unknown, "draft"),
                            "not in the workflow"));
        }
    }

    @Nested
    @DisplayName("Workflow file")
    class WorkflowFile {

        @Test
        @DisplayName("GDR-FORMS-5: a workflow file with states of its own, every rule of a deadline, no deadlines key")
        void ownStates() {
            var open = name();
            var closed = name();
            var close = name();
            var field = name();

            var workflow = workflow("""
                    states: [%1$s, %2$s]
                    transitions:
                      - { name: %3$s, from: %1$s, to: %2$s, mechanism: release }
                    deadlines:
                      - { state: %1$s, within: PT12H }
                      - { state: %2$s, field: %4$s }
                    """.formatted(open, closed, close, field));
            var withoutDeadlines = workflow("""
                    states: [%s]
                    transitions: []
                    """.formatted(open));

            assertAll(
                    () -> assertEquals(open, workflow.initialState(), "the initial state"),
                    () -> assertEquals(FormsResult.ok(new Transition(close, open, closed, Mechanism.RELEASE, true)),
                            workflow.transition(close, open), "the transition"),
                    () -> assertEquals(List.of(new Deadline(open, new Deadline.Within(Duration.ofHours(12))),
                                    new Deadline(closed, new Deadline.DateField(field))), workflow.deadlines(),
                            "a duration with a time part and a date field"),
                    () -> assertEquals(List.of(), withoutDeadlines.deadlines(), "no deadlines"),
                    () -> assertEquals(List.of(), withoutDeadlines.transitions(), "no transitions"));
        }

        static Stream<Arguments> refused() {
            String a = name();
            String b = name();
            String t = name();
            String transition = "{ name: " + t + ", from: " + a + ", to: " + b + ", mechanism: save }";
            String states = "states: [" + a + ", " + b + "]\n";
            return Stream.of(
                    Arguments.of("- " + a, "line 1, column 1: a workflow that is not a mapping"),
                    Arguments.of("states: [" + a + "\n", null),
                    Arguments.of("transitions: []", "states: missing key"),
                    Arguments.of(states, "transitions: missing key"),
                    Arguments.of(states + "transitions: []\n" + key() + ": 1", "the root: unknown key"),
                    Arguments.of("states: " + a + "\ntransitions: []", "states: not a list"),
                    Arguments.of("states: []\ntransitions: []", "states: an empty list"),
                    Arguments.of("states: [" + a + ", 1]\ntransitions: []", "states[1]: not a non-blank string"),
                    Arguments.of("states: [" + a + ", \" \"]\ntransitions: []", "states[1]: not a non-blank string"),
                    Arguments.of("states: [" + a + ", " + a + "]\ntransitions: []", "states[1]: duplicate state"),
                    Arguments.of(states + "transitions: " + a, "transitions: not a list"),
                    Arguments.of(states + "transitions: [" + a + "]", "transitions[0]: not a mapping"),
                    Arguments.of(states + "transitions: [{ name: " + t + ", from: " + a + ", to: " + b
                            + ", mechanism: save, " + key() + ": 1 }]", "transitions[0]: unknown key"),
                    Arguments.of(states + "transitions: [{ from: " + a + ", to: " + b + ", mechanism: save }]",
                            "transitions[0].name: missing key"),
                    Arguments.of(states + "transitions: [{ name: " + t + ", from: " + name() + ", to: " + b
                            + ", mechanism: save }]", "transitions[0].from: names no state of the workflow"),
                    Arguments.of(states + "transitions: [{ name: " + t + ", from: " + a + ", to: " + name()
                            + ", mechanism: save }]", "transitions[0].to: names no state of the workflow"),
                    Arguments.of(states + "transitions: [{ name: " + t + ", from: " + a + ", to: " + b
                            + ", mechanism: " + name() + " }]", "transitions[0].mechanism: unknown mechanism"),
                    Arguments.of(states + "transitions: [{ name: " + t + ", from: " + a + ", to: " + b
                            + ", mechanism: save, step-up: \"true\" }]", "transitions[0].step-up: not a boolean"),
                    Arguments.of(states + "transitions: [" + transition + ", " + transition + "]",
                            "transitions[1].name: duplicate transition name"),
                    Arguments.of(states + "transitions: [{ name: " + t + ", from: " + a + ", to: " + b
                            + ", mechanism: intermediate-save }]", "transitions[0].to: an intermediate-save keeps the state"),
                    Arguments.of(states + "transitions: []\ndeadlines: [{ state: " + name() + ", within: P1D }]",
                            "deadlines[0].state: names no state of the workflow"),
                    Arguments.of(states + "transitions: []\ndeadlines: [{ state: " + a + " }]",
                            "deadlines[0]: not exactly one of within, review-interval and field"),
                    Arguments.of(states + "transitions: []\ndeadlines: [{ state: " + a + ", within: P1D, field: "
                            + b + " }]", "deadlines[0]: not exactly one of within, review-interval and field"),
                    Arguments.of(states + "transitions: []\ndeadlines: [{ state: " + a + ", within: " + name() + " }]",
                            "deadlines[0].within: not a positive ISO 8601 duration"),
                    Arguments.of(states + "transitions: []\ndeadlines: [{ state: " + a + ", review-interval: P-1D }]",
                            "deadlines[0].review-interval: not a positive ISO 8601 duration"),
                    Arguments.of(states + "transitions: []\ndeadlines: [{ state: " + a + ", within: PT0S }]",
                            "deadlines[0].within: not a positive ISO 8601 duration"),
                    Arguments.of(states + "transitions: []\ndeadlines: [{ state: " + a + ", field: \"\" }]",
                            "deadlines[0].field: not a non-blank string"));
        }

        @ParameterizedTest(name = "{1}")
        @MethodSource("refused")
        @DisplayName("GDR-FORMS-5: a workflow the loader refuses is MALFORMED, naming the key path, never a value")
        void malformed(String text, @Nullable String problem) {
            var result = parse(text);

            assertAll(
                    () -> assertEquals(new FormsResult.PassedOn(StoreOutcome.MALFORMED), result.outcome(), "malformed"),
                    () -> assertTrue(Objects.requireNonNull(result.detail()).startsWith(PATH + ": "), "the file"),
                    () -> {
                        if (problem != null) {
                            assertEquals(PATH + ": " + problem, result.detail(), "the key path and the rule");
                        }
                    },
                    () -> assertFalse(GENERATED.matcher(Objects.requireNonNull(result.detail())).find(),
                            "no configured name in the detail"));
        }

        @Test
        @DisplayName("GDR-FORMS-5: a file that is not UTF-8 or too large is MALFORMED")
        void notUtf8OrTooLarge() {
            var notUtf8 = Workflow.parse(PATH, new byte[]{(byte) 0xC3, (byte) 0x28});
            var tooLarge = Workflow.parse(PATH, new byte[Workflow.MAX_FILE_BYTES + 1]);

            assertAll(
                    () -> assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, PATH + ": not UTF-8")),
                            notUtf8, "not UTF-8"),
                    () -> assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                            PATH + ": too large, more than 1048576 bytes")), tooLarge, "too large"));
        }
    }

    @Test
    @DisplayName("GDR-GEN-3: a null argument is refused")
    void nullArguments() {
        var workflow = Workflow.defaultWorkflow();
        String nullText = nullValue();
        byte[] nullContent = nullValue();

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> Workflow.parse(nullText, new byte[0])),
                () -> assertThrows(NullPointerException.class, () -> Workflow.parse(PATH, nullContent)),
                () -> assertThrows(NullPointerException.class, () -> workflow.transition(nullText, "draft")),
                () -> assertThrows(NullPointerException.class, () -> workflow.transition("edit", nullText)),
                () -> assertThrows(NullPointerException.class, () -> Mechanism.fromFileName(nullText)));
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> @Nullable T nullValue() {
        return null;
    }
}
