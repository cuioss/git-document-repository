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
 * the {@code from} of the transition. It writes that file as the merge of {@link StateMerge}: the file on the tip, with
 * the value of the draft for every top-level key the draft changed against the file it started from, the base the step
 * is created with, and {@value #STATE} set to the {@code to} of the transition; so a transition that changes only the
 * state writes the tip's content with the new state and never restores an untouched field from the draft's copy. The
 * merge is computed per attempt, so a re-applied attempt on a new tip merges again. The step then joins the data with
 * its parts, validates them without {@code required}, checks every link field of the data through {@link LinkTargets}
 * (GDR-FORMS-8), and checks {@code required} when the transition leaves the initial state or has the mechanism
 * {@code release}. A link field whose text is of neither link form, or whose target does not exist, refuses the
 * attempt as {@link ValidationResult.Invalid} with a {@link Violation} at the JSON Pointer of the field, the message
 * {@value #NO_SUCH_TARGET} for a missing target; the link text is data and appears in no detail or log. A resolution
 * that fails refuses the attempt with its outcome passed on. The links are checked on every attempt, so a re-applied
 * attempt sees the index as it is then. Data that, joined with their parts, are nested deeper than the nesting limit of
 * the data model refuse the attempt as the passed-on {@link StoreOutcome#MALFORMED} ({@link DocumentLayout#join}), so
 * the save ends with that result and never with a failure of the step. On success it returns the edit of the file
 * and, for a kind with a template, the output regenerated from exactly these data and the fragments of the attempt:
 * the output ({@value DocumentLayout#OUTPUT_FILE} of a document, {@code <collection>/<number>.adoc} of an entry) is the
 * one file of the layout that is never merged, so stale or foreign content at its path is replaced by every save. On a
 * refusal it captures the outcome and the findings for {@link DocumentTransitions}, because a
 * {@link SaveStep.Refusal} carries no text; each attempt starts with a fresh capture, so a re-applied attempt never
 * reports the findings of an earlier one.
 * <p>
 * For a new entry of a numbered kind, whose key is the ULID of its draft, the step allocates the running number on
 * every attempt (GDR-FORMS-9): it reads the counter file of the attempt's tip ({@link CounterCodec}, an absent file
 * counting 0), takes the next number ({@link RunningNumbers#number(Numbering, long)}), writes the merged file as the
 * entry file of that number, deletes the file of the ULID key, writes the advanced counter and, with a template, the
 * output of the number, all in the one save commit, and records the number for {@link DocumentTransitions}. The commit
 * is pushed with the compare-and-swap lease of the collection branch, so of two creators that read the same counter one
 * loses the push, and its re-applied attempt reads the winner's counter and takes the next number. A counter that
 * cannot be read, is exhausted, needs more digits than the numbering has, or names an entry the tip already holds
 * refuses the attempt as the passed-on {@link StoreOutcome#MALFORMED}.
 * <p>
 * The step records whether the store ran it: a save whose draft changes nothing calls no step, and
 * {@link DocumentTransitions} then applies the same checks through {@link #check(Attempt)} to the saved commit before
 * it releases anything. A step serves one save and is called on the saving thread only.
 */
final class TransitionStep implements SaveStep {

    /** The field of the current state, in {@value DocumentLayout#DOCUMENT_FILE} or the entry file. */
    static final String STATE = "state";

    /** The message of the violation of a link field whose target does not exist (GDR-FORMS-8). */
    static final String NO_SUCH_TARGET = "link: no such target";

    private static final CounterCodec COUNTER = new CounterCodec();

    private final Transition transition;
    private final String initialState;
    private final FormSchema schema;
    private final SchemaValidator validator;
    private final YamlDocumentCodec codec;
    private final OutputCheck output;
    private final LinkTargets links;
    private final String unit;
    private final String documentPath;
    private final Map<String, @Nullable Object> base;
    private final @Nullable Allocation allocation;

    private @Nullable Captured captured;
    private @Nullable String allocated;
    private boolean ran;

    /**
     * Creates the step of one save.
     *
     * @param transition   the transition
     * @param initialState the initial state of the workflow
     * @param schema       the schema of the kind
     * @param output       the output of the kind, which reads the data and regenerates the output
     * @param links        the resolution of the targets of the link fields
     * @param unit         the ULID of the document, the running number of the entry, or the ULID of the draft of a new
     *                     entry
     * @param documentPath the path of {@value DocumentLayout#DOCUMENT_FILE} or of the entry file of {@code unit}
     * @param base         the decoded file the draft started from, empty when there was none (see {@link StateMerge})
     * @param allocation   the layout and numbering of a numbered kind when {@code unit} is the ULID of a new entry,
     *                     which receives its running number on every attempt; {@code null} otherwise
     */
    TransitionStep(Transition transition, String initialState, FormSchema schema, OutputCheck output,
            LinkTargets links, String unit, String documentPath, Map<String, @Nullable Object> base,
            @Nullable Allocation allocation) {
        this.transition = transition;
        this.initialState = initialState;
        this.schema = schema;
        this.validator = new SchemaValidator();
        this.codec = new YamlDocumentCodec(schema);
        this.output = output;
        this.links = links;
        this.unit = unit;
        this.documentPath = documentPath;
        this.base = base;
        this.allocation = allocation;
    }

    @Override
    public Outcome apply(Attempt attempt) {
        ran = true;
        return check(attempt);
    }

    /**
     * Applies the checks of the step to a view of files, the attempt of a save or a saved commit: the state, the merge
     * of the file, the validation, {@code required} and the regenerated output.
     *
     * @param attempt the files; for a saved commit the tip and the produced tree are both that commit
     * @return the edits the step makes, or a refusal whose outcome and findings {@link #captured()} returns
     */
    Outcome check(Attempt attempt) {
        captured = null;
        allocated = null;
        Optional<byte[]> tipContent = attempt.tipContent(documentPath);
        FormsResult<String> current = readState(codec, tipContent, documentPath, initialState);
        if (!current.isOk()) {
            return refuse(current.outcome(), Objects.requireNonNull(current.detail()), null);
        }
        if (!transition.from().equals(current.value())) {
            return refuse(FormsOutcome.TRANSITION_REFUSED, DocumentTransitions.NOT_IN_CURRENT_STATE, null);
        }
        FormsResult<byte[]> written = merged(tipContent, attempt.content(documentPath));
        if (!written.isOk()) {
            return refuse(written.outcome(), Objects.requireNonNull(written.detail()), null);
        }
        byte[] content = Objects.requireNonNull(written.value());
        @Nullable Allocation numbering = allocation;
        if (numbering == null) {
            return checkedEdits(new Overlay(attempt, documentPath, content), content, unit, documentPath, List.of());
        }
        return allocate(attempt, numbering, content);
    }

    /**
     * Allocates the running number of a new entry on this attempt (GDR-FORMS-9): the counter of the attempt's tip, absent
     * read as 0, advanced by one and formatted; the merged file is written as the entry file of that number, the file of
     * the ULID draft key is deleted, and the advanced counter is written beside it, all in the one save commit. A
     * re-applied attempt reads the counter of its new tip again.
     */
    private Outcome allocate(Attempt attempt, Allocation numbering, byte[] content) {
        String counterPath = numbering.layout().counter();
        long counter = 0;
        Optional<byte[]> counterContent = attempt.tipContent(counterPath);
        if (counterContent.isPresent()) {
            StoreResult<Long> decoded = COUNTER.decode(counterContent.get());
            if (!decoded.isOk()) {
                return refuse(new FormsResult.PassedOn(StoreOutcome.MALFORMED),
                        Objects.requireNonNull(decoded.detail()), null);
            }
            counter = Objects.requireNonNull(decoded.value());
        }
        if (counter == Long.MAX_VALUE) {
            return refuse(new FormsResult.PassedOn(StoreOutcome.MALFORMED), counterPath + ": exhausted", null);
        }
        long next = counter + 1;
        FormsResult<String> number = RunningNumbers.number(numbering.numbering(), next);
        if (!number.isOk()) {
            return refuse(number.outcome(), Objects.requireNonNull(number.detail()), null);
        }
        String assigned = Objects.requireNonNull(number.value());
        String entryPath = Objects.requireNonNull(numbering.layout().entry(assigned).value(),
                "a running number is a name segment");
        if (attempt.tipContent(entryPath).isPresent()) {
            return refuse(new FormsResult.PassedOn(StoreOutcome.MALFORMED),
                    counterPath + ": behind the entries of the collection", null);
        }
        Outcome outcome = checkedEdits(new Overlay(attempt, entryPath, content), content, assigned, entryPath,
                List.of(new TreeEdit.Delete(documentPath), new TreeEdit.Put(counterPath, COUNTER.encode(next))));
        if (outcome instanceof Edits) {
            allocated = assigned;
        }
        return outcome;
    }

    /**
     * Whether the store ran the step during its save.
     *
     * @return {@code true} once {@link #apply(Attempt)} was called
     */
    boolean ran() {
        return ran;
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

    /**
     * Returns the running number the last attempt allocated, for the facade.
     *
     * @return the number, or empty when the step allocates none or the last attempt was refused
     */
    Optional<String> allocated() {
        return Optional.ofNullable(allocated);
    }

    /** The file the attempt writes: the merge of the tip's file, the draft's file and the base, with the state. */
    private FormsResult<byte[]> merged(Optional<byte[]> tipContent, Optional<byte[]> draftContent) {
        if (draftContent.isEmpty()) {
            return malformed(documentPath + ": missing file");
        }
        StoreResult<Map<String, @Nullable Object>> draft = codec.decode(draftContent.get());
        if (!draft.isOk()) {
            return malformed(documentPath + ": " + draft.detail());
        }
        @Nullable Map<String, @Nullable Object> tip = null;
        if (tipContent.isPresent()) {
            StoreResult<Map<String, @Nullable Object>> decoded = codec.decode(tipContent.get());
            if (!decoded.isOk()) {
                return malformed(documentPath + ": " + decoded.detail());
            }
            tip = decoded.value();
        }
        return FormsResult.ok(codec.encode(StateMerge.merge(tip, Objects.requireNonNull(draft.value()), base,
                transition.to())));
    }

    /**
     * Validates the data of the files and returns the edits of the file, the regenerated output and the further edits.
     *
     * @param files   the files of the attempt, the file of the document or entry replaced by the content written
     * @param written the content of the file of the document or entry
     * @param name    the ULID of the document or the running number of the entry the edits are written under
     * @param path    the path of the file of the document or entry the edits write
     * @param further the further edits of an allocation, none otherwise
     */
    private Outcome checkedEdits(OutputCheck.DocumentFiles files, byte[] written, String name, String path,
            List<TreeEdit> further) {
        FormsResult<Map<String, @Nullable Object>> data = output.data(files, name);
        if (!data.isOk()) {
            return refuse(data.outcome(), Objects.requireNonNull(data.detail()), null);
        }
        Map<String, @Nullable Object> values = Objects.requireNonNull(data.value());
        SchemaValidator.Evaluation evaluation = validator.evaluate(schema, values);
        ValidationResult validation = evaluation.validation();
        if (!(validation instanceof ValidationResult.Valid)) {
            return refuse(validation.outcome(), validation.detail().orElseThrow(), validation);
        }
        Optional<Refusal> brokenLink = checkLinks(values, evaluation.formats());
        if (brokenLink.isPresent()) {
            return brokenLink.get();
        }
        if (checksRequired()) {
            ValidationResult required = validator.required(schema, values);
            if (!(required instanceof ValidationResult.Valid)) {
                return refuse(required.outcome(), required.detail().orElseThrow(), required);
            }
        }
        List<TreeEdit> edits = new ArrayList<>();
        edits.add(new TreeEdit.Put(path, written));
        if (output.generates()) {
            FormsResult<byte[]> generated = output.generate(files, name);
            if (!generated.isOk()) {
                return refuse(generated.outcome(), Objects.requireNonNull(generated.detail()), null);
            }
            String outputPath = Objects.requireNonNull(output.outputPath(name).value());
            edits.add(new TreeEdit.Put(outputPath, Objects.requireNonNull(generated.value())));
        }
        edits.addAll(further);
        return new Edits(edits);
    }

    /**
     * Checks every link field of valid data (GDR-FORMS-8): a text of neither link form, or a link whose target does not
     * exist, is a {@link Violation} at the pointer of its field; a resolution that fails ends the check with its
     * outcome passed on.
     *
     * @return the refusal, or empty when every link resolves
     */
    private Optional<Refusal> checkLinks(Map<String, @Nullable Object> values,
            List<SchemaValidator.FormatAnnotation> formats) {
        List<Violation> violations = new ArrayList<>();
        for (LinkFields.Field field : LinkFields.collect(values, formats)) {
            FormsResult<TypedLink> parsed = TypedLink.parse(field.text());
            if (!parsed.isOk()) {
                violations.add(new Violation(field.pointer(), Objects.requireNonNull(parsed.detail())));
                continue;
            }
            FormsResult<Boolean> exists = links.exists(Objects.requireNonNull(parsed.value()));
            if (!exists.isOk()) {
                return Optional.of(refuse(exists.outcome(), Objects.requireNonNull(exists.detail()), null));
            }
            if (!Boolean.TRUE.equals(exists.value())) {
                violations.add(new Violation(field.pointer(), NO_SUCH_TARGET));
            }
        }
        if (violations.isEmpty()) {
            return Optional.empty();
        }
        ValidationResult.Invalid invalid = new ValidationResult.Invalid(violations);
        return Optional.of(refuse(invalid.outcome(), invalid.detail().orElseThrow(), invalid));
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

    private static <T> FormsResult<T> malformed(String detail) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, detail));
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
    static FormsResult<String> readState(YamlDocumentCodec codec, Optional<byte[]> content, String path,
            String initialState) {
        if (content.isEmpty()) {
            return FormsResult.ok(initialState);
        }
        StoreResult<Map<String, @Nullable Object>> decoded = codec.decode(content.get());
        if (!decoded.isOk()) {
            return malformed(path + ": " + decoded.detail());
        }
        Map<String, @Nullable Object> data = Objects.requireNonNull(decoded.value());
        if (!data.containsKey(STATE)) {
            return FormsResult.ok(initialState);
        }
        if (!(data.get(STATE) instanceof String state)) {
            return malformed(path + ": " + STATE + ": not a string");
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

    /**
     * What the allocation of a running number needs (GDR-FORMS-9).
     *
     * @param layout    the layout of the numbered kind: the counter file, the entry files and the outputs
     * @param numbering the numbering of the kind
     */
    record Allocation(DocumentLayout layout, Numbering numbering) {
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
