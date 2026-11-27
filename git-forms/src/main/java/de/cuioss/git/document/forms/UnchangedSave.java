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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.forms.CloneReader.ReadFailure;
import de.cuioss.git.document.forms.DocumentTransitions.Result;
import de.cuioss.git.document.store.SaveResult;
import de.cuioss.git.document.store.SaveStep;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.StoreWriter;
import org.jspecify.annotations.Nullable;

/**
 * The verification of a save of {@link DocumentTransitions} that ran no step, because its draft changed nothing: the
 * checks of the step applied to the saved commit, and the rewrite of an output that is stale on that commit through a
 * second draft and save.
 */
final class UnchangedSave {

    private final StoreWriter writer;
    private final TransitionContext context;

    /**
     * Creates the verification of the unchanged saves of a kind.
     *
     * @param writer  the write path of the repository of the kind
     * @param context the kind and the reader of the writer's bare clone
     */
    UnchangedSave(StoreWriter writer, TransitionContext context) {
        this.writer = writer;
        this.context = context;
    }

    /**
     * Verifies the commit of a save that did not run the step, because its draft changed nothing (a self-loop such as
     * {@code approved} to {@code approved} writes identical content): the checks of the step are applied to the saved
     * commit through the view {@link CloneReader#savedCommit(String)}. A refusal ends the transition as the refusal of
     * a step would, before anything is released or written. When the step's edits equal the files of the saved commit,
     * the commit stands; otherwise its output is stale, and the edits are written through a second draft and save with a
     * fresh step, whose commit the transition continues with. That second draft changes the stale paths against the
     * saved commit, so its save always runs the step.
     *
     * @param call     the transition
     * @param commitId the full id of the saved commit
     * @return {@link Result.Performed} with the commit the transition continues with, or the result that ends it
     */
    Result verifySaved(TransitionCall call, String commitId) {
        SaveStep.Attempt view = context.reader().savedCommit(commitId);
        var check = context.step(call, Map.of());
        SaveStep.Outcome outcome;
        List<TreeEdit> stale = List.of();
        try {
            // the tip and the produced tree are both the saved commit, so the merge takes its file whatever the base
            outcome = check.check(view);
            if (outcome instanceof SaveStep.Edits(List<TreeEdit> edits)) {
                stale = stale(view, edits);
            }
        } catch (ReadFailure e) {
            return TransitionOutcomes.readFailed(e);
        }
        if (!(outcome instanceof SaveStep.Edits)) {
            return TransitionOutcomes.fromCapture(check);
        }
        if (stale.isEmpty()) {
            return new Result.Performed(commitId, call.key(), null);
        }
        return rewrite(call, view, stale);
    }

    /** Writes the regenerated edits of a stale saved commit through a second draft and save with a fresh step. */
    private Result rewrite(TransitionCall call, SaveStep.Attempt view, List<TreeEdit> stale) {
        StoreResult<Map<String, @Nullable Object>> saved;
        try {
            saved = context.codec().decode(view.content(call.documentPath()).orElseThrow());
        } catch (ReadFailure e) {
            return TransitionOutcomes.readFailed(e);
        }
        StoreResult<String> drafted = writer.draft(call.author(), call.key(), stale, call.change().message());
        if (!drafted.isOk()) {
            return TransitionOutcomes.passOn(drafted);
        }
        var step = context.step(call, Objects.requireNonNull(saved.value(), "the verification decoded the saved file"));
        SaveResult result = writer.save(call.author(), call.claims(), call.key(), Map.of(), call.change().message(),
                step);
        return switch (result) {
            case SaveResult.Saved(String commitId) -> new Result.Performed(commitId, call.key(), null);
            case SaveResult.Conflicting(List<String> paths) -> new Result.Conflicting(paths);
            case SaveResult.NotSaved notSaved -> TransitionOutcomes.refused(step, notSaved);
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
}
