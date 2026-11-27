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

import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.forms.CloneReader.ReadFailure;
import de.cuioss.git.document.forms.DocumentTransitions.Result;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.Release;
import de.cuioss.git.document.store.SaveResult;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.StoreWriter;
import org.jspecify.annotations.Nullable;

/**
 * The mechanism of a transition of {@link DocumentTransitions} whose step-up check passed: {@code intermediate-save}
 * writes the caller's edits as a draft; {@code save} and {@code release} write the draft with the state through
 * {@link DraftStaging}, save it with a {@link TransitionStep}, verify a save that ran no step through
 * {@link UnchangedSave}, and for {@code release} release the saved commit.
 */
final class TransitionSave {

    private final StoreWriter writer;
    private final TransitionContext context;
    private final DraftStaging staging;
    private final UnchangedSave unchanged;

    /**
     * Creates the mechanisms of a kind.
     *
     * @param writer  the write path of the repository of the kind
     * @param context the kind and the reader of the writer's bare clone
     */
    TransitionSave(StoreWriter writer, TransitionContext context) {
        this.writer = writer;
        this.context = context;
        this.staging = new DraftStaging(writer, context);
        this.unchanged = new UnchangedSave(writer, context);
    }

    /**
     * Runs the mechanism of a transition.
     *
     * @param call the transition, whose step-up check passed
     * @return the result of the transition
     */
    Result perform(TransitionCall call) {
        if (call.transition().mechanism() == Mechanism.INTERMEDIATE_SAVE) {
            StoreResult<String> drafted = writer.draft(call.author(), call.key(), call.change().edits(),
                    call.change().message());
            return drafted.isOk() ? new Result.Performed(Objects.requireNonNull(drafted.value()), call.key(), null)
                    : TransitionOutcomes.passOn(drafted);
        }
        return save(call);
    }

    /**
     * The save of a transition on {@code save} or {@code release}: the base of the merge, the draft with the state,
     * the save with the step, the verification of an unchanged save, and for {@code release} the release after it.
     */
    private Result save(TransitionCall call) {
        StoreResult<String> draftRef = StoreReferences.draft(call.author().pseudonym(), call.key());
        if (!draftRef.isOk()) {
            return TransitionOutcomes.passOn(draftRef);
        }
        String draft = Objects.requireNonNull(draftRef.value());
        FormsResult<Map<String, @Nullable Object>> base;
        try {
            base = staging.base(draft, call.key(), call.documentPath());
        } catch (ReadFailure e) {
            return TransitionOutcomes.readFailed(e);
        }
        if (!base.isOk()) {
            return TransitionOutcomes.notPerformed(base);
        }
        Result drafted = staging.draftWithState(call, draft);
        if (drafted != null) {
            return drafted;
        }
        var step = context.step(call, Objects.requireNonNull(base.value()));
        SaveResult saved = writer.save(call.author(), call.claims(), call.key(), call.change().expectedBlobIds(),
                call.change().message(), step);
        return switch (saved) {
            case SaveResult.Saved(String commitId) -> afterSave(call, step, commitId);
            case SaveResult.Conflicting(List<String> paths) -> new Result.Conflicting(paths);
            case SaveResult.NotSaved notSaved -> TransitionOutcomes.refused(step, notSaved);
        };
    }

    /**
     * Continues after a save: a save that did not run the step was unchanged and is verified first, then the
     * transition is performed with the saved commit, released for {@code release}. A save whose step allocated a
     * running number (GDR-FORMS-9) continues with the key of that number: the release releases the entry under it, and
     * {@link Result.Performed#key()} reports it.
     */
    private Result afterSave(TransitionCall call, TransitionStep step, String commitId) {
        String saved = commitId;
        if (!step.ran()) {
            Result verified = unchanged.verifySaved(call, commitId);
            if (!(verified instanceof Result.Performed performed)) {
                return verified;
            }
            saved = performed.commitId();
        }
        DocumentKey key = step.allocated().map(number -> new DocumentKey(call.key().collection(), number))
                .orElse(call.key());
        return call.transition().mechanism() == Mechanism.RELEASE ? release(call, key, saved)
                : new Result.Performed(saved, key, null);
    }

    /**
     * The release after the save of a transition on {@code release}, pinned to the commit the transition saved, so it
     * publishes exactly that commit. A failure leaves the saved state in place, and so does a branch that moved after
     * the save, which the store refuses with the passed-on outcome it returns; no compensating write is made.
     */
    private Result release(TransitionCall call, DocumentKey key, String savedCommitId) {
        StoreResult<Release> released = writer.release(call.author(), call.claims(), key, savedCommitId);
        if (!released.isOk()) {
            return TransitionOutcomes.passOn(released);
        }
        Release release = Objects.requireNonNull(released.value());
        return new Result.Performed(release.commitId(), key, release);
    }
}
