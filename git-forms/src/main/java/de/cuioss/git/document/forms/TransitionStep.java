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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.SaveStep;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The per-attempt step of a transition on {@code save} or {@code release} (GDR-FORMS-2, GDR-FORMS-4, GDR-FORMS-5),
 * run by the store on every attempt of the save, the first and each re-application onto a new remote tip.
 * <p>
 * The step reads the current state from the file of the document or entry on the tip the attempt commits on (a missing
 * file or a file without {@value #STATE} is in the initial state of the workflow) and refuses the attempt when it is not
 * the {@code from} of the transition. It writes {@value #STATE} into that file of the attempt's tree, joins the data
 * with its parts, validates them without {@code required}, and checks {@code required} when the transition leaves the
 * initial state or has the mechanism {@code release}. On success it returns the edit of the file and, for a kind with a
 * template, the output regenerated from exactly these data and the fragments of the attempt, so the output is never
 * merged. On a refusal it captures the outcome and the findings for {@link DocumentTransitions}, because a
 * {@link SaveStep.Refusal} carries no text; each attempt starts with a fresh capture, so a re-applied attempt never
 * reports the findings of an earlier one.
 * <p>
 * A step serves one save and is called on the saving thread only.
 */
final class TransitionStep implements SaveStep {

    /** The field of the current state, in {@value DocumentLayout#DOCUMENT_FILE} or the entry file. */
    static final String STATE = "state";

    private final Transition transition;
    private final String initialState;
    private final FormSchema schema;
    private final SchemaValidator validator;
    private final YamlDocumentCodec codec;
    private final OutputCheck output;
    private final boolean hasTemplate;
    private final String unit;
    private final String documentPath;

    private @Nullable Captured captured;

    /**
     * Creates the step of one save.
     *
     * @param transition   the transition
     * @param initialState the initial state of the workflow
     * @param schema       the schema of the kind
     * @param output       the output of the kind, which reads the data and regenerates the output
     * @param hasTemplate  whether the kind has a template
     * @param unit         the ULID of the document or the running number of the entry
     * @param documentPath the path of {@value DocumentLayout#DOCUMENT_FILE} or of the entry file
     */
    TransitionStep(Transition transition, String initialState, FormSchema schema, OutputCheck output,
            boolean hasTemplate, String unit, String documentPath) {
        this.transition = transition;
        this.initialState = initialState;
        this.schema = schema;
        this.validator = new SchemaValidator();
        this.codec = new YamlDocumentCodec(schema);
        this.output = output;
        this.hasTemplate = hasTemplate;
        this.unit = unit;
        this.documentPath = documentPath;
    }

    @Override
    public Outcome apply(Attempt attempt) {
        captured = null;
        FormsResult<String> current = state(codec, attempt.tipContent(documentPath), documentPath, initialState);
        if (!current.isOk()) {
            return refuse(current.outcome(), Objects.requireNonNull(current.detail()), null);
        }
        if (!transition.from().equals(current.value())) {
            return refuse(FormsOutcome.TRANSITION_REFUSED, DocumentTransitions.NOT_IN_CURRENT_STATE, null);
        }
        Optional<byte[]> content = attempt.content(documentPath);
        if (content.isEmpty()) {
            return refuse(passedOnMalformed(), documentPath + ": missing file", null);
        }
        StoreResult<Map<String, @Nullable Object>> decoded = codec.decode(content.get());
        if (!decoded.isOk()) {
            return refuse(passedOnMalformed(), documentPath + ": " + decoded.detail(), null);
        }
        Map<String, @Nullable Object> document = new LinkedHashMap<>(Objects.requireNonNull(decoded.value()));
        document.put(STATE, transition.to());
        byte[] written = codec.encode(document);
        OutputCheck.DocumentFiles files = new Overlay(attempt, documentPath, written);
        FormsResult<Map<String, @Nullable Object>> data = output.data(files, unit);
        if (!data.isOk()) {
            return refuse(data.outcome(), Objects.requireNonNull(data.detail()), null);
        }
        Map<String, @Nullable Object> values = Objects.requireNonNull(data.value());
        ValidationResult validation = validator.validate(schema, values);
        if (!(validation instanceof ValidationResult.Valid)) {
            return refuse(validation.outcome(), validation.detail().orElseThrow(), validation);
        }
        if (checksRequired()) {
            ValidationResult required = validator.required(schema, values);
            if (!(required instanceof ValidationResult.Valid)) {
                return refuse(required.outcome(), required.detail().orElseThrow(), required);
            }
        }
        List<TreeEdit> edits = new ArrayList<>();
        edits.add(new TreeEdit.Put(documentPath, written));
        if (hasTemplate) {
            FormsResult<byte[]> generated = output.generate(files, unit);
            if (!generated.isOk()) {
                return refuse(generated.outcome(), Objects.requireNonNull(generated.detail()), null);
            }
            String outputPath = Objects.requireNonNull(output.outputPath(unit).value());
            edits.add(new TreeEdit.Put(outputPath, Objects.requireNonNull(generated.value())));
        }
        return new Edits(edits);
    }

    /**
     * Returns the refusal the last attempt captured.
     *
     * @return the outcome, the detail and the findings of the refusal, or empty when the last attempt was not refused
     *         or the step never ran
     */
    Optional<Captured> captured() {
        return Optional.ofNullable(captured);
    }

    /** Whether the transition checks {@code required}: it leaves the initial state, or it releases (GDR-FORMS-2). */
    private boolean checksRequired() {
        boolean leavesInitial = transition.from().equals(initialState) && !transition.to().equals(initialState);
        return leavesInitial || transition.mechanism() == Mechanism.RELEASE;
    }

    private Refusal refuse(FormsResult.Outcome outcome, String detail, @Nullable ValidationResult findings) {
        captured = new Captured(outcome, detail, findings);
        return new Refusal();
    }

    private static FormsResult.Outcome passedOnMalformed() {
        return new FormsResult.PassedOn(StoreOutcome.MALFORMED);
    }

    /**
     * Reads the current state from the content of the file of a document or entry.
     *
     * @param codec        the codec of the file
     * @param content      the content, or empty when there is no file
     * @param path         the path of the file, named in the detail of a refusal
     * @param initialState the initial state of the workflow
     * @return {@link FormsOutcome#OK} with the value of {@value #STATE}, the initial state for a missing file or a file
     *         without it; the passed-on {@link StoreOutcome#MALFORMED} for a file that cannot be read or whose
     *         {@value #STATE} is no string; the detail names the path, never a value
     */
    static FormsResult<String> state(YamlDocumentCodec codec, Optional<byte[]> content, String path,
            String initialState) {
        if (content.isEmpty()) {
            return FormsResult.ok(initialState);
        }
        StoreResult<Map<String, @Nullable Object>> decoded = codec.decode(content.get());
        if (!decoded.isOk()) {
            return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, path + ": " + decoded.detail()));
        }
        Map<String, @Nullable Object> data = Objects.requireNonNull(decoded.value());
        if (!data.containsKey(STATE)) {
            return FormsResult.ok(initialState);
        }
        if (!(data.get(STATE) instanceof String state)) {
            return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, path + ": " + STATE + ": not a string"));
        }
        return FormsResult.ok(state);
    }

    /**
     * The refusal of an attempt, captured for {@link DocumentTransitions}.
     *
     * @param outcome  the outcome of the refusal, never {@link FormsOutcome#OK}
     * @param detail   the detail, free of anything GDR-NFR-3 forbids
     * @param findings the findings of a refused validation or {@code required} check; {@code null} otherwise
     */
    record Captured(FormsResult.Outcome outcome, String detail, @Nullable ValidationResult findings) {
    }

    /** The files of an attempt, with the file of the document or entry replaced by the content the step writes. */
    private static final class Overlay implements OutputCheck.DocumentFiles {

        private final Attempt attempt;
        private final String path;
        private final byte[] content;

        Overlay(Attempt attempt, String path, byte[] content) {
            this.attempt = attempt;
            this.path = path;
            this.content = content;
        }

        @Override
        public Optional<byte[]> content(String requested) {
            return path.equals(requested) ? Optional.of(content.clone()) : attempt.content(requested);
        }

        @Override
        public List<String> paths(String directory) {
            return attempt.paths(directory);
        }
    }
}
