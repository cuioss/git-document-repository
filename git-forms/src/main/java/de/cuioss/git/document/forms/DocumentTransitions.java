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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.IdentityClaims;
import de.cuioss.git.document.store.Release;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.StoreWriter;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The transitions of the workflow of one kind of document over the mechanisms of the store (GDR-FORMS-4, GDR-FORMS-5,
 * GDR-FORMS-7, GDR-FORMS-8).
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
 * it started from, and the state), validates the data, checks that the target of every link field exists through the
 * {@link LinkTargets} of the application (GDR-FORMS-8), checks {@code required} when the transition leaves the initial
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
 * passed to {@link StoreWriter#open}; this class only reads it, through the package-private {@link CloneReader}. This
 * class is the facade of the package-private {@link TransitionRunner}, which runs a transition over
 * {@link TransitionContext}, {@link TransitionSave}, {@link DraftStaging}, {@link UnchangedSave} and
 * {@link TransitionOutcomes}. A detail names a path of the document, a reference or
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

    private final TransitionRunner runner;

    /**
     * Creates the transitions of a kind.
     *
     * @param writer     the write path of the repository of the kind
     * @param repository the bare clone of the writer, read for the current state and the draft
     * @param definition the kind, its schema, its workflow and its template
     * @param links      the resolution of the targets of the link fields (GDR-FORMS-8)
     * @param policy     the step-up setting of the application
     * @param clock      the clock of the step-up check
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the layout of the kind cannot be created ({@link DocumentLayout})
     */
    public DocumentTransitions(StoreWriter writer, Repository repository, Definition definition, LinkTargets links,
            StepUpPolicy policy, Clock clock) {
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(links, "links");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(clock, "clock");
        this.runner = new TransitionRunner(writer, repository, definition, links, policy, clock);
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
     *         for {@link FormsOutcome#INVALID} or {@link FormsOutcome#MANDATORY_FIELDS_MISSING}, a link field of the
     *         data whose text is of neither link form or whose target does not exist being a {@link Violation} at its
     *         JSON Pointer with the message {@code link: no such target} for a missing target (GDR-FORMS-8);
     *         {@link Result.Conflicting} with the conflicting paths; or {@link Result.NotPerformed} with
     *         {@link FormsOutcome#TRANSITION_REFUSED}, {@link FormsOutcome#STEP_UP_REQUIRED}, or the passed-on outcome of
     *         the store or of Git, for example {@link de.cuioss.git.document.store.StoreOutcome#MALFORMED} for a file of
     *         the document that cannot be read, the outcome of a failed resolution of a link target, or
     *         {@link GitOutcome#REJECTED} after the last attempt of the save
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the key is not of the collection of the kind
     */
    public Result perform(Author author, IdentityClaims claims, DocumentKey key, String transition, Change change) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(change, "change");
        return runner.perform(author, claims, key, transition, change);
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
     * detail} for every non-success, plus the data of its variant, as {@link de.cuioss.git.document.store.SaveResult}
     * and {@link ValidationResult} do: a non-success that names its violations, its missing fields or its conflicting
     * paths would break the invariant of a {@link FormsResult} without value. A caller reads the findings from the
     * variant and never parses a detail.
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
