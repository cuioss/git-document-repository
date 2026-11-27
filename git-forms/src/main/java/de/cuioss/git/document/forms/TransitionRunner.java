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
import java.util.Objects;
import java.util.Optional;


import de.cuioss.git.document.forms.CloneReader.ReadFailure;
import de.cuioss.git.document.forms.DocumentTransitions.Change;
import de.cuioss.git.document.forms.DocumentTransitions.Definition;
import de.cuioss.git.document.forms.DocumentTransitions.Result;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.IdentityClaims;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.StoreWriter;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The run of one transition of {@link DocumentTransitions}: the collection check of the key, the current state, the
 * lookup of the transition in that state, the resumption of a release transition whose release did not complete (a
 * transition on {@code release} of that name whose {@code to} is the current state, checked after a fetch of the
 * writer, so the clone mirrors the remote's release tags, and only when no release tag holds the saved file), the
 * step-up check against the {@link StepUpPolicy} at the instant of the clock before anything is written, and the
 * mechanism through {@link TransitionSave}. Every result is logged as
 * {@code GIT_FORMS-2} or {@code GIT_FORMS-3} with the mechanism and the commit id or the name of the outcome only,
 * under the logger of {@link DocumentTransitions}.
 */
final class TransitionRunner {

    private static final CuiLogger LOGGER = new CuiLogger(DocumentTransitions.class);

    /** The mechanism named in the log when no transition was found. */
    private static final String NO_MECHANISM = "none";

    private final StoreWriter writer;
    private final TransitionContext context;
    private final TransitionSave save;
    private final StepUpPolicy policy;
    private final Clock clock;

    /**
     * Creates the run of the transitions of a kind.
     *
     * @param writer     the write path of the repository of the kind
     * @param repository the bare clone of the writer, read for the current state and the draft
     * @param definition the kind, its schema, its workflow and its template
     * @param links      the resolution of the targets of the link fields
     * @param policy     the step-up setting of the application
     * @param clock      the clock of the step-up check
     * @throws IllegalArgumentException if the layout of the kind cannot be created ({@link DocumentLayout})
     */
    TransitionRunner(StoreWriter writer, Repository repository, Definition definition, LinkTargets links,
            StepUpPolicy policy, Clock clock) {
        this.writer = writer;
        this.context = new TransitionContext(repository, definition, links);
        this.save = new TransitionSave(writer, context);
        this.policy = policy;
        this.clock = clock;
    }

    /**
     * Performs a transition of the workflow on a document or entry, see {@link DocumentTransitions#perform}.
     *
     * @param author     the author
     * @param claims     the claims of the author's authentication
     * @param key        the document or entry
     * @param transition the name of the transition
     * @param change     the edits, the expected blob ids and the message of the call
     * @return the result of the transition, logged
     * @throws IllegalArgumentException if the key is not of the collection of the kind
     */
    Result perform(Author author, IdentityClaims claims, DocumentKey key, String transition, Change change) {
        context.requireCollection(key);
        String documentPath = context.documentPath(key);
        FormsResult<String> current;
        try {
            current = context.currentState(key, documentPath);
        } catch (ReadFailure e) {
            return report(NO_MECHANISM, TransitionOutcomes.readFailed(e));
        }
        if (!current.isOk()) {
            return report(NO_MECHANISM, TransitionOutcomes.notPerformed(current));
        }
        String state = Objects.requireNonNull(current.value());
        FormsResult<Transition> found = context.transition(transition, state);
        Transition allowed;
        @Nullable String unreleased = null;
        if (found.isOk()) {
            allowed = Objects.requireNonNull(found.value());
        } else {
            Optional<Transition> release = context.resumable(transition, state);
            if (release.isEmpty()) {
                return report(NO_MECHANISM, TransitionOutcomes.notPerformed(found));
            }
            StoreResult<List<String>> fetched = writer.fetch();
            if (!fetched.isOk()) {
                return report(NO_MECHANISM, TransitionOutcomes.passOn(fetched));
            }
            Optional<String> tip;
            try {
                tip = context.unreleasedTip(key, documentPath, release.get());
            } catch (ReadFailure e) {
                return report(NO_MECHANISM, TransitionOutcomes.readFailed(e));
            }
            if (tip.isEmpty()) {
                return report(NO_MECHANISM, TransitionOutcomes.notPerformed(found));
            }
            allowed = release.get();
            unreleased = tip.get();
        }
        String mechanism = allowed.mechanism().fileName();
        if (policy.requires(allowed) && !policy.holds(claims, clock.instant())) {
            return report(mechanism, TransitionOutcomes.stepUpRequired());
        }
        var call = new TransitionCall(author, claims, key, allowed, change, documentPath);
        return report(mechanism, unreleased == null ? save.perform(call) : save.resumeRelease(call, unreleased));
    }


    /** Logs the result of a transition, naming the mechanism and the commit id or the outcome only, and returns it. */
    private static Result report(String mechanism, Result result) {
        if (result instanceof Result.Performed performed) {
            LOGGER.info(GitFormsLogMessages.INFO.TRANSITION_PERFORMED, mechanism, performed.commitId());
        } else {
            LOGGER.info(GitFormsLogMessages.INFO.TRANSITION_NOT_PERFORMED, mechanism,
                    TransitionOutcomes.name(result.outcome()));
        }
        return result;
    }
}
