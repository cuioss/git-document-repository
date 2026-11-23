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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The workflow of a kind of document (GDR-FORMS-5, GDR-FORMS-7): its states, the allowed transitions between them,
 * each on a mechanism of the store, and its deadlines.
 * <p>
 * The workflow file holds the keys {@code states} (a list of state names, the first being the initial state),
 * {@code transitions} (a list of transitions with the keys {@code name}, {@code from}, {@code to}, {@code mechanism}
 * and the optional boolean {@code step-up}) and the optional {@code deadlines} (a list of entries with the key
 * {@code state} and exactly one of {@code within}, {@code review-interval} and {@code field}). The mechanisms are
 * {@code intermediate-save}, {@code save} and {@code release}; without {@code step-up} a transition on
 * {@code release} requires step-up and every other does not. The deadlines are read and kept, not evaluated
 * (GDR-FORMS-6 is planned). {@link #defaultWorkflow()} is the default workflow of the specification.
 * <p>
 * The file is read as YAML within the JSON data model of ADR-0008 through the reader of the other files of the
 * configuration, with its bounds, and at most {@value #MAX_FILE_BYTES} bytes. A workflow the loader does not accept is
 * the passed-on {@link StoreOutcome#MALFORMED}: a file too large, not UTF-8 or not YAML of the data model, a root that
 * is not a mapping, a missing or unknown key, a value of the wrong type, an empty list of states, a blank or duplicate
 * state, a transition whose {@code from} or {@code to} names no state, a duplicate transition name, an unknown
 * mechanism, a {@code step-up} that is not a boolean, a transition on {@code intermediate-save} that declares
 * {@code step-up: true} (GDR-FORMS-7) or changes the state, a deadline whose state names no state, a deadline without
 * or with more than one rule, a duration that is not a positive ISO 8601 duration, and a {@code field} that is blank.
 * The detail names the file, the key path, positions counted from 0, and the rule
 * ({@code workflows/approval.yaml: transitions[1].mechanism: unknown mechanism}), or the line and column of the text,
 * never a configured value (GDR-NFR-3). A workflow is immutable and may serve every thread.
 *
 * @since 0.1
 */
public final class Workflow {

    /** The largest workflow file the loader reads, in bytes (1 MiB), the bound of the other files of the configuration. */
    public static final int MAX_FILE_BYTES = KindConfiguration.MAX_FILE_BYTES;

    private static final String STATES = "states";
    private static final String TRANSITIONS = "transitions";
    private static final String DEADLINES = "deadlines";
    private static final String NAME = "name";
    private static final String FROM = "from";
    private static final String TO = "to";
    private static final String MECHANISM = "mechanism";
    private static final String STEP_UP = "step-up";
    private static final String STATE = "state";
    private static final String WITHIN = "within";
    private static final String REVIEW_INTERVAL = "review-interval";
    private static final String FIELD = "field";

    private static final Set<String> WORKFLOW_KEYS = Set.of(STATES, TRANSITIONS, DEADLINES);
    private static final Set<String> TRANSITION_KEYS = Set.of(NAME, FROM, TO, MECHANISM, STEP_UP);
    private static final Set<String> DEADLINE_KEYS = Set.of(STATE, WITHIN, REVIEW_INTERVAL, FIELD);

    /** The path the default workflow is read under. */
    private static final String DEFAULT_PATH = "default workflow";

    /** The default workflow of GDR-FORMS-5. */
    private static final String DEFAULT_TEXT = """
            states: [draft, review, approved, superseded]
            transitions:
              - { name: edit,      from: draft,    to: draft,      mechanism: intermediate-save }
              - { name: submit,    from: draft,    to: review,     mechanism: save }
              - { name: approve,   from: review,   to: approved,   mechanism: release }
              - { name: rework,    from: review,   to: draft,      mechanism: save }
              - { name: revise,    from: approved, to: draft,      mechanism: save }
              - { name: supersede, from: approved, to: superseded, mechanism: release }
            deadlines:
              - { state: review,   within: P14D }
              - { state: approved, review-interval: P1Y }
            """;

    private static final YamlReader YAML = new YamlReader();

    private static final Workflow DEFAULT = Objects.requireNonNull(
            parse(DEFAULT_PATH, DEFAULT_TEXT.getBytes(StandardCharsets.UTF_8)).value(), "the default workflow");

    private final List<String> states;
    private final Map<String, Transition> transitions;
    private final List<Deadline> deadlines;

    private Workflow(List<String> states, Map<String, Transition> transitions, List<Deadline> deadlines) {
        this.states = List.copyOf(states);
        this.transitions = transitions;
        this.deadlines = List.copyOf(deadlines);
    }

    /**
     * The default workflow of the specification: the states {@code draft}, {@code review}, {@code approved} and
     * {@code superseded}; the transitions {@code edit} (intermediate-save), {@code submit}, {@code rework} and
     * {@code revise} (save), {@code approve} and {@code supersede} (release, with step-up); the deadlines
     * {@code within: P14D} in {@code review} and {@code review-interval: P1Y} in {@code approved}.
     *
     * @return the default workflow
     */
    public static Workflow defaultWorkflow() {
        return DEFAULT;
    }

    /**
     * Reads a workflow file.
     *
     * @param path    the repository-relative path of the file, named in the detail of a refusal
     * @param content the content of the file
     * @return {@link FormsOutcome#OK} with the workflow, or the passed-on {@link StoreOutcome#MALFORMED} whose detail
     *         names the file, the key path and the rule
     * @throws NullPointerException if an argument is {@code null}
     */
    public static FormsResult<Workflow> parse(String path, byte[] content) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(content, "content");
        if (content.length > MAX_FILE_BYTES) {
            return malformed(path, "too large, more than " + MAX_FILE_BYTES + " bytes");
        }
        String text = YamlReader.decodeUtf8(content);
        if (text == null) {
            return malformed(path, "not UTF-8");
        }
        FormsResult<Map<String, @Nullable Object>> root = YAML.readMapping(text,
                "line 1, column 1: a workflow that is not a mapping");
        if (!root.isOk()) {
            return malformed(path, Objects.requireNonNull(root.detail()));
        }
        try {
            return FormsResult.ok(workflow(Objects.requireNonNull(root.value())));
        } catch (Malformed e) {
            return malformed(path, Objects.requireNonNull(e.getMessage()));
        }
    }

    private static Workflow workflow(Map<String, @Nullable Object> root) {
        Map<String, @Nullable Object> keys = mapping(root, "the root", WORKFLOW_KEYS);
        List<String> states = states(list(require(keys, STATES, STATES), STATES));
        Map<String, Transition> transitions = new LinkedHashMap<>();
        List<?> declared = list(require(keys, TRANSITIONS, TRANSITIONS), TRANSITIONS);
        for (int index = 0; index < declared.size(); index++) {
            String path = TRANSITIONS + "[" + index + "]";
            Transition transition = transition(declared.get(index), path, states);
            if (transitions.putIfAbsent(transition.name(), transition) != null) {
                throw new Malformed(path + "." + NAME + ": duplicate transition name");
            }
        }
        List<Deadline> deadlines = new ArrayList<>();
        if (keys.containsKey(DEADLINES)) {
            List<?> entries = list(require(keys, DEADLINES, DEADLINES), DEADLINES);
            for (int index = 0; index < entries.size(); index++) {
                deadlines.add(deadline(entries.get(index), DEADLINES + "[" + index + "]", states));
            }
        }
        return new Workflow(states, transitions, deadlines);
    }

    private static List<String> states(List<?> list) {
        if (list.isEmpty()) {
            throw new Malformed(STATES + ": an empty list");
        }
        List<String> states = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < list.size(); index++) {
            String path = STATES + "[" + index + "]";
            if (!(list.get(index) instanceof String state) || state.isBlank()) {
                throw new Malformed(path + ": not a non-blank string");
            }
            if (!seen.add(state)) {
                throw new Malformed(path + ": duplicate state");
            }
            states.add(state);
        }
        return states;
    }

    private static Transition transition(@Nullable Object entry, String path, List<String> states) {
        Map<String, @Nullable Object> keys = mapping(entry, path, TRANSITION_KEYS);
        String name = string(keys, NAME, path);
        String from = state(keys, FROM, path, states);
        String to = state(keys, TO, path, states);
        Mechanism mechanism = Mechanism.fromFileName(string(keys, MECHANISM, path))
                .orElseThrow(() -> new Malformed(path + "." + MECHANISM + ": unknown mechanism"));
        boolean stepUp = Transition.defaultStepUp(mechanism);
        if (keys.containsKey(STEP_UP)) {
            if (!(keys.get(STEP_UP) instanceof Boolean declared)) {
                throw new Malformed(path + "." + STEP_UP + ": not a boolean");
            }
            stepUp = declared;
        }
        if (mechanism == Mechanism.INTERMEDIATE_SAVE && stepUp) {
            throw new Malformed(path + "." + STEP_UP + ": not allowed for the mechanism intermediate-save");
        }
        if (mechanism == Mechanism.INTERMEDIATE_SAVE && !from.equals(to)) {
            throw new Malformed(path + "." + TO + ": an intermediate-save keeps the state");
        }
        return new Transition(name, from, to, mechanism, stepUp);
    }

    private static Deadline deadline(@Nullable Object entry, String path, List<String> states) {
        Map<String, @Nullable Object> keys = mapping(entry, path, DEADLINE_KEYS);
        String state = state(keys, STATE, path, states);
        List<String> rules = DEADLINE_KEYS.stream().filter(key -> !STATE.equals(key) && keys.containsKey(key))
                .toList();
        if (rules.size() != 1) {
            throw new Malformed(path + ": not exactly one of within, review-interval and field");
        }
        Deadline.Rule rule = switch (rules.getFirst()) {
            case WITHIN -> new Deadline.Within(duration(keys, WITHIN, path));
            case REVIEW_INTERVAL -> new Deadline.ReviewInterval(duration(keys, REVIEW_INTERVAL, path));
            default -> new Deadline.DateField(string(keys, FIELD, path));
        };
        return new Deadline(state, rule);
    }

    /** A positive ISO 8601 duration, a {@link Period} or, with a time part, a {@link Duration}. */
    private static TemporalAmount duration(Map<String, @Nullable Object> keys, String key, String path) {
        String text = string(keys, key, path);
        String problem = path + "." + key + ": not a positive ISO 8601 duration";
        try {
            Period period = Period.parse(text);
            if (period.isNegative() || period.isZero()) {
                throw new Malformed(problem);
            }
            return period;
        } catch (DateTimeParseException notAPeriod) {
            try {
                Duration duration = Duration.parse(text);
                if (duration.isNegative() || duration.isZero()) {
                    throw new Malformed(problem);
                }
                return duration;
            } catch (DateTimeParseException notADuration) {
                throw new Malformed(problem);
            }
        }
    }

    private static String state(Map<String, @Nullable Object> keys, String key, String path, List<String> states) {
        String state = string(keys, key, path);
        if (!states.contains(state)) {
            throw new Malformed(path + "." + key + ": names no state of the workflow");
        }
        return state;
    }

    private static Map<String, @Nullable Object> mapping(@Nullable Object value, String path, Set<String> allowed) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new Malformed(path + ": not a mapping");
        }
        Map<String, @Nullable Object> keys = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !allowed.contains(key)) {
                throw new Malformed(path + ": unknown key");
            }
            keys.put(key, entry.getValue());
        }
        return keys;
    }

    private static List<?> list(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new Malformed(path + ": not a list");
        }
        return list;
    }

    /** The value of a required key; a key with the value {@code null} counts as missing. */
    private static Object require(Map<String, @Nullable Object> keys, String key, String path) {
        Object value = keys.get(key);
        if (value == null) {
            throw new Malformed(path + ": missing key");
        }
        return value;
    }

    private static String string(Map<String, @Nullable Object> keys, String key, String path) {
        String keyPath = path + "." + key;
        if (!(require(keys, key, keyPath) instanceof String text) || text.isBlank()) {
            throw new Malformed(keyPath + ": not a non-blank string");
        }
        return text;
    }

    private static <T> FormsResult<T> malformed(String path, String problem) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, path + ": " + problem));
    }

    /**
     * The states of the workflow.
     *
     * @return the state names in file order, unmodifiable
     */
    public List<String> states() {
        return states;
    }

    /**
     * The initial state, the first state of the file: the state of a document whose data hold no {@code state}.
     *
     * @return the first state
     */
    public String initialState() {
        return states.getFirst();
    }

    /**
     * The transitions of the workflow.
     *
     * @return the transitions in file order, unmodifiable
     */
    public List<Transition> transitions() {
        return List.copyOf(transitions.values());
    }

    /**
     * The deadlines of the workflow, read and not evaluated.
     *
     * @return the deadlines in file order, unmodifiable
     */
    public List<Deadline> deadlines() {
        return deadlines;
    }

    /**
     * Finds a transition the workflow allows in a state.
     *
     * @param name         the name of the transition
     * @param currentState the current state of the document
     * @return {@link FormsOutcome#OK} with the transition, or {@link FormsOutcome#TRANSITION_REFUSED} for a name the
     *         workflow does not have or a transition whose {@code from} is not the current state; the detail names
     *         neither the transition nor the state
     * @throws NullPointerException if an argument is {@code null}
     */
    public FormsResult<Transition> transition(String name, String currentState) {
        Objects.requireNonNull(name, NAME);
        Objects.requireNonNull(currentState, "currentState");
        Optional<Transition> transition = Optional.ofNullable(transitions.get(name));
        if (transition.isEmpty()) {
            return FormsResult.of(FormsOutcome.TRANSITION_REFUSED, "transition: not in the workflow");
        }
        if (!transition.get().from().equals(currentState)) {
            return FormsResult.of(FormsOutcome.TRANSITION_REFUSED, DocumentTransitions.NOT_IN_CURRENT_STATE);
        }
        return FormsResult.ok(transition.get());
    }

    /** A workflow the loader does not accept, carrying the detail: the key path and the rule. */
    private static final class Malformed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Malformed(String detail) {
            super(detail, null, false, false);
        }
    }
}
