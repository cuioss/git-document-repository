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

import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.IdentityClaims;
import de.cuioss.git.document.store.Release;
import de.cuioss.git.document.store.SaveResult;
import de.cuioss.git.document.store.SaveStep;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.StoreWriter;
import de.cuioss.git.document.forms.CloneReader.ReadFailure;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The transitions of the workflow of one kind of document over the mechanisms of the store (GDR-FORMS-4, GDR-FORMS-5,
 * GDR-FORMS-7).
 * <p>
 * {@link #perform} looks the transition up in the current state of the document, the field {@value TransitionStep#STATE}
 * of {@value DocumentLayout#DOCUMENT_FILE} or the entry file on the local target branch (else {@code main}); a missing
 * file or field is the initial state of the workflow. A transition the workflow does not have, or whose {@code from} is
 * not the current state, is {@link FormsOutcome#TRANSITION_REFUSED}. A transition that requires step-up is checked
 * against the {@link StepUpPolicy} at the instant of the clock before anything is written, so a refusal
 * {@link FormsOutcome#STEP_UP_REQUIRED} leaves the draft, the working or collection branch and {@code main} unchanged.
 * Then the mechanism runs:
 * <ul>
 * <li>{@code intermediate-save} writes the caller's edits as a draft through {@link StoreWriter#draft}; the state stays,
 * because such a transition never changes it.</li>
 * <li>{@code save} writes the caller's edits together with the file of the document or entry carrying the transition's
 * {@code to} as {@value TransitionStep#STATE} as a draft, so the save always has a change, and saves that draft through
 * {@link StoreWriter#save(Author, IdentityClaims, DocumentKey, Map, String, de.cuioss.git.document.store.SaveStep)}
 * with a {@link TransitionStep}. On every attempt the step checks the state on the remote tip, writes the file of the
 * document or entry as the merge of {@link StateMerge} (the tip's file with the keys the draft changed against the file
 * it started from, and the state), validates the data, checks {@code required} when the transition leaves the initial
 * state or releases (GDR-FORMS-2), and regenerates the output from the data of that attempt (GDR-FORMS-4). The file
 * the draft started from is read before the save: the file at the merge base of the draft and the branch of the key,
 * else the file on that branch. When the caller edits that file in the call, its content is explicit whole-file intent
 * and is the draft's file of the merge; a caller who needs a concurrent change of it detected passes
 * {@link Change#expectedBlobIds()} (GDR-STORE-4).</li>
 * <li>{@code release} is the same single save, whose commit carries the state and the regenerated output, followed by
 * {@link StoreWriter#release(Author, IdentityClaims, DocumentKey, String)}, pinned to the commit the transition saved,
 * so the release publishes exactly that commit. A release that fails after the save is passed on and leaves the saved
 * state in place; a branch that moved after the save refuses the release with the passed-on outcome of the store, the
 * saved state stays in place, and no compensating write is made.</li>
 * </ul>
 * A save whose draft changes nothing, such as a self-loop from {@code approved} to {@code approved} that writes identical
 * content, runs no step in the store. Its saved commit is then verified before anything is released: the checks of the
 * step are applied to the files of that commit, a refusal ends the transition as a refusal of the step would, and an
 * output that is stale on that commit is rewritten through a second draft and save, whose commit is the one released.
 * The draft written before a save stays when the save does not succeed, as every draft of the user does.
 * <p>
 * The current state is read through the bare clone of the writer, which the caller opens on the git directory it
 * passed to {@link StoreWriter#open}; this class only reads it, through the package-private {@link CloneReader}. A
 * detail names a path of the document, a reference or
 * the problem, never a data value, a pseudonym or a claim; violations and the JSON Pointers of missing fields are data
 * of {@link Result.Refused}, conflicting paths data of {@link Result.Conflicting} (GDR-NFR-3). Every result is logged
 * as {@code GIT_FORMS-2} or {@code GIT_FORMS-3} with the mechanism and the commit id or the name of the outcome only.
 *
 * @since 0.1
 */
public final class DocumentTransitions {

    /** The detail of a transition whose {@code from} is not the current state of the document. */
    static final String NOT_IN_CURRENT_STATE = "transition: not allowed in the current state";

    /** The detail of a transition that requires step-up the claims do not hold. */
    static final String STEP_UP_DETAIL = "step-up: the claims do not hold step-up";

    private static final CuiLogger LOGGER = new CuiLogger(DocumentTransitions.class);

    /** The mechanism named in the log when no transition was found. */
    private static final String NO_MECHANISM = "none";

    private final StoreWriter writer;
    private final CloneReader clone;
    private final Definition definition;
    private final StepUpPolicy policy;
    private final Clock clock;
    private final DocumentLayout layout;
    private final YamlDocumentCodec codec;
    private final OutputCheck output;

    /**
     * Creates the transitions of a kind.
     *
     * @param writer     the write path of the repository of the kind
     * @param repository the bare clone of the writer, read for the current state and the draft
     * @param definition the kind, its schema, its workflow and its template
     * @param policy     the step-up setting of the application
     * @param clock      the clock of the step-up check
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the layout of the kind cannot be created ({@link DocumentLayout})
     */
    public DocumentTransitions(StoreWriter writer, Repository repository, Definition definition, StepUpPolicy policy,
            Clock clock) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.clone = new CloneReader(repository);
        this.definition = Objects.requireNonNull(definition, "definition");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.layout = new DocumentLayout(definition.kind(), definition.schema());
        this.codec = new YamlDocumentCodec(definition.schema());
        this.output = new OutputCheck(definition.kind(), definition.schema(), definition.template());
    }

    /**
     * Performs a transition of the workflow on a document or entry.
     *
     * @param author     the author: the owner of the draft and the author of the commits
     * @param claims     the claims of the author's authentication, checked for step-up and written as identity
     *                   trailers of a save or release
     * @param key        the document or entry, of the collection of the kind
     * @param transition the name of the transition
     * @param change     the edits, the expected blob ids and the message of the call
     * @return {@link Result.Performed} with the commit the transition wrote; {@link Result.Refused} with the findings
     *         for {@link FormsOutcome#INVALID} or {@link FormsOutcome#MANDATORY_FIELDS_MISSING};
     *         {@link Result.Conflicting} with the conflicting paths; or {@link Result.NotPerformed} with
     *         {@link FormsOutcome#TRANSITION_REFUSED}, {@link FormsOutcome#STEP_UP_REQUIRED}, or the passed-on outcome of
     *         the store or of Git, for example {@link StoreOutcome#MALFORMED} for a file of the document that cannot be
     *         read or {@link GitOutcome#REJECTED} after the last attempt of the save
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the key is not of the collection of the kind
     */
    public Result perform(Author author, IdentityClaims claims, DocumentKey key, String transition, Change change) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(change, "change");
        requireCollection(key);
        String documentPath = documentPath(key);
        FormsResult<String> current;
        try {
            current = TransitionStep.readState(codec, clone.read(branchOf(key), documentPath), documentPath,
                    initialState());
        } catch (ReadFailure e) {
            return report(NO_MECHANISM, readFailed(e));
        }
        if (!current.isOk()) {
            return report(NO_MECHANISM, notPerformed(current));
        }
        FormsResult<Transition> found = definition.workflow().transition(transition,
                Objects.requireNonNull(current.value()));
        if (!found.isOk()) {
            return report(NO_MECHANISM, notPerformed(found));
        }
        Transition allowed = Objects.requireNonNull(found.value());
        String mechanism = allowed.mechanism().fileName();
        if (allowed.stepUp() && !policy.holds(claims, clock.instant())) {
            return report(mechanism, new Result.NotPerformed(FormsOutcome.STEP_UP_REQUIRED, STEP_UP_DETAIL));
        }
        if (allowed.mechanism() == Mechanism.INTERMEDIATE_SAVE) {
            StoreResult<String> drafted = writer.draft(author, key, change.edits(), change.message());
            return report(mechanism, drafted.isOk() ? new Result.Performed(Objects.requireNonNull(drafted.value()), null)
                    : passOn(drafted));
        }
        return report(mechanism, save(new Call(author, claims, key, allowed, change, documentPath)));
    }

    /**
     * The save of a transition on {@code save} or {@code release}: the base of the merge, the draft with the state,
     * the save with the step, the verification of an unchanged save, and for {@code release} the release after it.
     */
    private Result save(Call call) {
        StoreResult<String> draftRef = StoreReferences.draft(call.author().pseudonym(), call.key());
        if (!draftRef.isOk()) {
            return passOn(draftRef);
        }
        String draft = Objects.requireNonNull(draftRef.value());
        FormsResult<Map<String, @Nullable Object>> base;
        try {
            base = base(draft, call.key(), call.documentPath());
        } catch (ReadFailure e) {
            return readFailed(e);
        }
        if (!base.isOk()) {
            return notPerformed(base);
        }
        Result drafted = draftWithState(call, draft);
        if (drafted != null) {
            return drafted;
        }
        var step = step(call, Objects.requireNonNull(base.value()));
        SaveResult saved = writer.save(call.author(), call.claims(), call.key(), call.change().expectedBlobIds(),
                call.change().message(), step);
        return switch (saved) {
            case SaveResult.Saved(String commitId) -> afterSave(call, step, commitId);
            case SaveResult.Conflicting(List<String> paths) -> new Result.Conflicting(paths);
            case SaveResult.NotSaved notSaved -> refused(step, notSaved);
        };
    }

    /**
     * Continues after a save: a save that did not run the step was unchanged and is verified first, then the
     * transition is performed with the saved commit, released for {@code release}.
     */
    private Result afterSave(Call call, TransitionStep step, String commitId) {
        String saved = commitId;
        if (!step.ran()) {
            Result verified = verifySaved(call, commitId);
            if (!(verified instanceof Result.Performed performed)) {
                return verified;
            }
            saved = performed.commitId();
        }
        return call.transition().mechanism() == Mechanism.RELEASE ? release(call, saved)
                : new Result.Performed(saved, null);
    }

    /**
     * Verifies the commit of a save that did not run the step, because its draft changed nothing (a self-loop such as
     * {@code approved} to {@code approved} writes identical content): the checks of the step are applied to the saved
     * commit through the view {@link CloneReader#savedCommit(String)}. A refusal ends the transition as the refusal of a step would, before
     * anything is released or written. When the step's edits equal the files of the saved commit, the commit stands;
     * otherwise its output is stale, and the edits are written through a second draft and save with a fresh step, whose
     * commit the transition continues with. That second draft changes the stale paths against the saved commit, so its
     * save always runs the step.
     *
     * @return {@link Result.Performed} with the commit the transition continues with, or the result that ends it
     */
    private Result verifySaved(Call call, String commitId) {
        SaveStep.Attempt view = clone.savedCommit(commitId);
        var check = step(call, Map.of());
        SaveStep.Outcome outcome;
        List<TreeEdit> stale = new ArrayList<>();
        try {
            // the tip and the produced tree are both the saved commit, so the merge takes its file whatever the base
            outcome = check.check(view);
            if (outcome instanceof SaveStep.Edits(List<TreeEdit> edits)) {
                stale.addAll(stale(view, edits));
            }
        } catch (ReadFailure e) {
            return readFailed(e);
        }
        if (!(outcome instanceof SaveStep.Edits)) {
            return fromCapture(check);
        }
        if (stale.isEmpty()) {
            return new Result.Performed(commitId, null);
        }
        return rewrite(call, view, stale);
    }

    /** Writes the regenerated edits of a stale saved commit through a second draft and save with a fresh step. */
    private Result rewrite(Call call, SaveStep.Attempt view, List<TreeEdit> stale) {
        StoreResult<Map<String, @Nullable Object>> saved;
        try {
            saved = codec.decode(view.content(call.documentPath()).orElseThrow());
        } catch (ReadFailure e) {
            return readFailed(e);
        }
        StoreResult<String> drafted = writer.draft(call.author(), call.key(), stale, call.change().message());
        if (!drafted.isOk()) {
            return passOn(drafted);
        }
        var step = step(call, Objects.requireNonNull(saved.value(), "the verification decoded the saved file"));
        SaveResult result = writer.save(call.author(), call.claims(), call.key(), Map.of(), call.change().message(),
                step);
        return switch (result) {
            case SaveResult.Saved(String commitId) -> new Result.Performed(commitId, null);
            case SaveResult.Conflicting(List<String> paths) -> new Result.Conflicting(paths);
            case SaveResult.NotSaved notSaved -> refused(step, notSaved);
        };
    }

    /** The edits whose content differs from the file the saved commit holds at their path. */
    private static List<TreeEdit> stale(SaveStep.Attempt view, List<TreeEdit> edits) {
        List<TreeEdit> stale = new ArrayList<>();
        for (TreeEdit edit : edits) {
            if (edit instanceof TreeEdit.Put put
                    && !Arrays.equals(view.content(put.path()).orElse(null), put.content())) {
                stale.add(put);
            }
        }
        return stale;
    }

    /** The step of a save of the call, merging the file against a base. */
    private TransitionStep step(Call call, Map<String, @Nullable Object> base) {
        return new TransitionStep(call.transition(), initialState(), definition.schema(), output, call.key().id(),
                call.documentPath(), base);
    }

    /**
     * The decoded file the draft started from, the base of the merge of {@link StateMerge}: the file at the merge base
     * of the draft and the branch of the key when the draft exists and they have a common commit, else the file on that
     * branch ({@link CloneReader#startedFrom(String, String, String)}), so that a draft that does not exist yet has
     * changed nothing; empty when there is no file.
     *
     * @throws ReadFailure for an I/O failure while reading the clone
     */
    private FormsResult<Map<String, @Nullable Object>> base(String draft, DocumentKey key, String documentPath) {
        Optional<byte[]> content = clone.startedFrom(draft, branchOf(key), documentPath);
        if (content.isEmpty()) {
            return FormsResult.ok(Map.of());
        }
        StoreResult<Map<String, @Nullable Object>> decoded = codec.decode(content.get());
        if (!decoded.isOk()) {
            return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, documentPath + ": " + decoded.detail()));
        }
        return FormsResult.ok(Objects.requireNonNull(decoded.value()));
    }

    /**
     * Writes the caller's edits and the file of the document or entry with the state {@code to} as a draft, so the
     * draft always has a change; the step makes the final content of that file on every attempt.
     *
     * @return {@code null} when the draft was written, otherwise the result that ends the transition
     */
    private @Nullable Result draftWithState(Call call, String draftRef) {
        String documentPath = call.documentPath();
        EditSplit split = EditSplit.of(call.change().edits(), documentPath);
        Optional<byte[]> content;
        try {
            content = split.edited ? split.content : draftContent(draftRef, call.key(), documentPath);
        } catch (ReadFailure e) {
            return readFailed(e);
        }
        List<TreeEdit> edits = new ArrayList<>(split.others);
        Map<String, @Nullable Object> document = new LinkedHashMap<>();
        if (content.isPresent()) {
            StoreResult<Map<String, @Nullable Object>> decoded = codec.decode(content.get());
            if (!decoded.isOk()) {
                return new Result.NotPerformed(new FormsResult.PassedOn(StoreOutcome.MALFORMED),
                        documentPath + ": " + decoded.detail());
            }
            document.putAll(Objects.requireNonNull(decoded.value()));
        }
        document.put(TransitionStep.STATE, call.transition().to());
        edits.add(new TreeEdit.Put(documentPath, codec.encode(document)));
        StoreResult<String> drafted = writer.draft(call.author(), call.key(), edits, call.change().message());
        return drafted.isOk() ? null : passOn(drafted);
    }

    /**
     * Reads the file of the document or entry from the draft of the author, else from the branch of the key.
     *
     * @throws ReadFailure for an I/O failure while reading the clone
     */
    private Optional<byte[]> draftContent(String draft, DocumentKey key, String documentPath) {
        return clone.read(clone.exists(draft) ? draft : branchOf(key), documentPath);
    }

    /**
     * The caller's edits split at the file of the document or entry: the edits of every other path in the caller's
     * order, whether the caller edited that file, and the content of the caller's last edit of it (empty for a
     * deletion or when not edited).
     */
    private static final class EditSplit {

        private final List<TreeEdit> others = new ArrayList<>();
        private boolean edited;
        private Optional<byte[]> content = Optional.empty();

        static EditSplit of(List<TreeEdit> edits, String documentPath) {
            var split = new EditSplit();
            for (TreeEdit edit : edits) {
                if (edit.path().equals(documentPath)) {
                    split.edited = true;
                    split.content = edit instanceof TreeEdit.Put put ? Optional.of(put.content()) : Optional.empty();
                } else {
                    split.others.add(edit);
                }
            }
            return split;
        }
    }

    /**
     * The release after the save of a transition on {@code release}, pinned to the commit the transition saved, so it
     * publishes exactly that commit. A failure leaves the saved state in place, and so does a branch that moved after
     * the save, which the store refuses with the passed-on outcome it returns; no compensating write is made.
     */
    private Result release(Call call, String savedCommitId) {
        StoreResult<Release> released = writer.release(call.author(), call.claims(), call.key(), savedCommitId);
        if (!released.isOk()) {
            return passOn(released);
        }
        Release release = Objects.requireNonNull(released.value());
        return new Result.Performed(release.commitId(), release);
    }

    /** Maps a save that did not succeed: a refusal of the step to what the step captured, anything else passed on. */
    private static Result refused(TransitionStep step, SaveResult.NotSaved notSaved) {
        if (notSaved.outcome() != StoreOutcome.REFUSED || step.captured().isEmpty()) {
            return new Result.NotPerformed(new FormsResult.PassedOn(notSaved.outcome()), notSaved.reason());
        }
        return fromCapture(step);
    }

    /** The result of a refusal the step captured: the findings of a validation, or its outcome and detail. */
    private static Result fromCapture(TransitionStep step) {
        TransitionStep.Captured refusal = step.captured().orElseThrow();
        ValidationResult findings = refusal.findings();
        if (findings != null) {
            return new Result.Refused(findings);
        }
        return new Result.NotPerformed(refusal.outcome(), refusal.detail());
    }

    /**
     * The input of one transition on {@code save} or {@code release}.
     *
     * @param author       the author
     * @param claims       the claims of the author's authentication
     * @param key          the document or entry
     * @param transition   the transition
     * @param change       the caller's edits, expected blob ids and message
     * @param documentPath the path of the file of the document or entry
     */
    private record Call(Author author, IdentityClaims claims, DocumentKey key, Transition transition, Change change,
    String documentPath) {
    }

    private void requireCollection(DocumentKey key) {
        Kind kind = definition.kind();
        DocumentCollection collection = key.collection();
        DocumentCollection.Kind expected = kind.isNumbered() ? DocumentCollection.Kind.NUMBERED
                : DocumentCollection.Kind.PER_DOCUMENT;
        if (!collection.name().equals(kind.collection()) || collection.kind() != expected) {
            throw new IllegalArgumentException("key: not of the collection of the kind");
        }
    }

    private String documentPath(DocumentKey key) {
        FormsResult<String> path = definition.kind().isNumbered() ? layout.entry(key.id()) : layout.document(key.id());
        return Objects.requireNonNull(path.value(), "a key holds a valid name segment");
    }

    private String initialState() {
        return definition.workflow().initialState();
    }

    /** The branch the current state is read from: the local target branch of the key, else {@code main}. */
    private String branchOf(DocumentKey key) {
        String target = StoreReferences.target(key);
        return clone.exists(target) ? target : StoreReferences.MAIN;
    }

    private static Result readFailed(ReadFailure failure) {
        return new Result.NotPerformed(new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.FAILED)),
                "read: " + failure.getCause().getClass().getSimpleName());
    }

    private static Result notPerformed(FormsResult<?> result) {
        return new Result.NotPerformed(result.outcome(), Objects.requireNonNull(result.detail()));
    }

    private static Result passOn(StoreResult<?> result) {
        return new Result.NotPerformed(new FormsResult.PassedOn(result.outcome()),
                Objects.requireNonNull(result.detail()));
    }

    /** Logs the result of a transition, naming the mechanism and the commit id or the outcome only, and returns it. */
    private static Result report(String mechanism, Result result) {
        if (result instanceof Result.Performed performed) {
            LOGGER.info(GitFormsLogMessages.INFO.TRANSITION_PERFORMED, mechanism, performed.commitId());
        } else {
            LOGGER.info(GitFormsLogMessages.INFO.TRANSITION_NOT_PERFORMED, mechanism, name(result.outcome()));
        }
        return result;
    }

    /** The name of an outcome: the constant of {@link FormsOutcome}, of the store or of Git. */
    static String name(FormsResult.Outcome outcome) {
        return switch (outcome) {
            case FormsOutcome forms -> forms.name();
            case FormsResult.PassedOn(StoreOutcome store) -> store.name();
            case FormsResult.PassedOn(StoreResult.PassedOn(GitOutcome git)) -> git.name();
        };
    }

    /**
     * What a kind brings to its transitions: the kind, its schema, its workflow and its template.
     *
     * @param kind     the kind of document
     * @param schema   the schema of the kind
     * @param workflow the workflow of the kind
     * @param template the template of the kind, {@code null} exactly when the kind declares none
     * @since 0.1
     */
    public record Definition(Kind kind, FormSchema schema, Workflow workflow, @Nullable DocumentTemplate template) {

        /**
         * Checks the components.
         *
         * @param kind     the kind
         * @param schema   the schema
         * @param workflow the workflow
         * @param template the template, or {@code null}
         * @throws NullPointerException     if a component other than {@code template} is {@code null}
         * @throws IllegalArgumentException if a template is given for a kind without one or none for a kind with one
         */
        public Definition {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(schema, "schema");
            Objects.requireNonNull(workflow, "workflow");
            if ((kind.template() == null) != (template == null)) {
                throw new IllegalArgumentException("template: given exactly when the kind declares one");
            }
        }
    }

    /**
     * The input of a transition besides its name: the caller's edits, the blob ids the caller loaded and the message.
     *
     * @param edits           the edits of the document; for {@code intermediate-save} the draft's edits, for
     *                        {@code save} and {@code release} written as a draft before the save, together with the
     *                        state; an edit of the file of the document or entry is whole-file intent, merged onto
     *                        the tip key by key against the file the draft started from, so a concurrent change of that
     *                        file is detected only through {@code expectedBlobIds}
     * @param expectedBlobIds the full blob id the caller loaded per repository-relative path, checked by a save
     *                        (GDR-STORE-4); the zero id expects the path to be absent; empty for no check
     * @param message         the message of the draft and the save commit
     * @since 0.1
     */
    public record Change(List<TreeEdit> edits, Map<String, String> expectedBlobIds, String message) {

        /**
         * Checks and copies the components.
         *
         * @param edits           the edits
         * @param expectedBlobIds the expected blob ids
         * @param message         the message
         * @throws NullPointerException if a component, an edit, a path or a blob id is {@code null}
         */
        public Change {
            edits = List.copyOf(Objects.requireNonNull(edits, "edits"));
            expectedBlobIds = Map.copyOf(Objects.requireNonNull(expectedBlobIds, "expectedBlobIds"));
            Objects.requireNonNull(message, "message");
        }

        /**
         * Creates the input of a transition without expected blob ids.
         *
         * @param edits   the edits
         * @param message the message
         * @return the input
         * @throws NullPointerException if an argument or an edit is {@code null}
         */
        public static Change of(List<TreeEdit> edits, String message) {
            return new Change(edits, Map.of(), message);
        }
    }

    /**
     * The result of a transition (GDR-GEN-3), which carries its findings as data.
     * <p>
     * A result has the shape GDR-GEN-3 prescribes, an {@linkplain #outcome() outcome} and a {@linkplain #detail()
     * detail} for every non-success, plus the data of its variant, as {@link SaveResult} and {@link ValidationResult}
     * do: a non-success that names its violations, its missing fields or its conflicting paths would break the
     * invariant of a {@link FormsResult} without value. A caller reads the findings from the variant and never parses
     * a detail.
     *
     * @since 0.1
     */
    public sealed interface Result
            permits Result.Performed, Result.Refused, Result.Conflicting, Result.NotPerformed {

        /**
         * The outcome of the transition.
         *
         * @return {@link FormsOutcome#OK} for {@link Performed}, the outcome of the findings for {@link Refused}, the
         *         passed-on {@link GitOutcome#CONFLICT} for {@link Conflicting}, the recorded outcome for
         *         {@link NotPerformed}
         */
        FormsResult.Outcome outcome();

        /**
         * The detail of a non-success.
         *
         * @return the detail, present exactly when the transition was not performed
         */
        Optional<String> detail();

        /**
         * Converts the result into the plain GDR-GEN-3 shape.
         *
         * @return {@link FormsOutcome#OK} with the commit id for {@link Performed}; otherwise the outcome and the
         *         detail, without the findings
         */
        default FormsResult<String> toResult() {
            if (this instanceof Performed performed) {
                return FormsResult.ok(performed.commitId());
            }
            return new FormsResult<>(outcome(), null, detail().orElseThrow());
        }

        /**
         * A performed transition.
         *
         * @param commitId the full id of the commit the transition wrote: the draft commit of an
         *                 {@code intermediate-save}, the save commit of a {@code save} (or the unchanged tip, when
         *                 the transition changed nothing), the release commit of a {@code release}
         * @param release  the release of a {@code release}; {@code null} otherwise
         * @since 0.1
         */
        record Performed(String commitId, @Nullable Release release) implements Result {

            /**
             * Checks the commit id.
             *
             * @param commitId the commit id
             * @param release  the release, or {@code null}
             * @throws NullPointerException if {@code commitId} is {@code null}
             */
            public Performed {
                Objects.requireNonNull(commitId, "commitId");
            }

            /**
             * The outcome of a performed transition.
             *
             * @return {@link FormsOutcome#OK}
             */
            @Override
            public FormsResult.Outcome outcome() {
                return FormsOutcome.OK;
            }

            /**
             * A success carries no detail.
             *
             * @return an empty detail
             */
            @Override
            public Optional<String> detail() {
                return Optional.empty();
            }
        }

        /**
         * A transition refused by its validation (GDR-FORMS-2): nothing was saved.
         *
         * @param findings the findings, {@link ValidationResult.Invalid} or
         *                 {@link ValidationResult.MandatoryFieldsMissing}
         * @since 0.1
         */
        record Refused(ValidationResult findings) implements Result {

            /**
             * Checks the findings.
             *
             * @param findings the findings
             * @throws NullPointerException     if {@code findings} is {@code null}
             * @throws IllegalArgumentException if {@code findings} is {@link ValidationResult.Valid}
             */
            public Refused {
                Objects.requireNonNull(findings, "findings");
                if (findings instanceof ValidationResult.Valid) {
                    throw new IllegalArgumentException("findings: a valid result refuses nothing");
                }
            }

            /**
             * The outcome of the findings.
             *
             * @return {@link FormsOutcome#INVALID} or {@link FormsOutcome#MANDATORY_FIELDS_MISSING}
             */
            @Override
            public FormsResult.Outcome outcome() {
                return findings.outcome();
            }

            /**
             * The detail of the findings, naming their number only.
             *
             * @return the detail
             */
            @Override
            public Optional<String> detail() {
                return findings.detail();
            }
        }

        /**
         * A save that conflicts on at least one path (GDR-STORE-4, GDR-STORE-5): nothing was saved.
         *
         * @param paths the conflicting paths, repository-relative, sorted and distinct; never empty
         * @since 0.1
         */
        record Conflicting(List<String> paths) implements Result {

            /**
             * Sorts and copies the paths.
             *
             * @param paths the conflicting paths
             * @throws NullPointerException     if {@code paths} or one of its elements is {@code null}
             * @throws IllegalArgumentException if {@code paths} is empty
             */
            public Conflicting {
                paths = List.copyOf(new TreeSet<>(Objects.requireNonNull(paths, "paths")));
                if (paths.isEmpty()) {
                    throw new IllegalArgumentException("paths: a conflict names at least one path");
                }
            }

            /**
             * The outcome of a conflicting save.
             *
             * @return the passed-on {@link GitOutcome#CONFLICT}
             */
            @Override
            public FormsResult.Outcome outcome() {
                return new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.CONFLICT));
            }

            /**
             * The detail of the conflict, naming the number of conflicting paths only.
             *
             * @return {@code "N conflicting paths"}
             */
            @Override
            public Optional<String> detail() {
                return Optional.of(paths.size() + " conflicting paths");
            }
        }

        /**
         * Every other transition that was not performed.
         *
         * @param outcome the outcome, never {@link FormsOutcome#OK}
         * @param reason  the detail, not blank and free of anything GDR-NFR-3 forbids
         * @since 0.1
         */
        record NotPerformed(FormsResult.Outcome outcome, String reason) implements Result {

            /**
             * Checks the outcome and the reason.
             *
             * @param outcome the outcome
             * @param reason  the detail
             * @throws NullPointerException     if an argument is {@code null}
             * @throws IllegalArgumentException if {@code outcome} is {@link FormsOutcome#OK} or {@code reason} is blank
             */
            public NotPerformed {
                Objects.requireNonNull(outcome, "outcome");
                Objects.requireNonNull(reason, "reason");
                if (outcome == FormsOutcome.OK) {
                    throw new IllegalArgumentException("a transition that was not performed is no OK");
                }
                if (reason.isBlank()) {
                    throw new IllegalArgumentException("a transition that was not performed needs a detail");
                }
            }

            /**
             * The detail of the non-success.
             *
             * @return the reason
             */
            @Override
            public Optional<String> detail() {
                return Optional.of(reason);
            }
        }
    }
}
