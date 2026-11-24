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
import de.cuioss.git.document.forms.CloneReader.ReadFailure;
import de.cuioss.git.document.forms.DocumentTransitions.Result;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.StoreWriter;
import org.jspecify.annotations.Nullable;

/**
 * The draft of a transition on {@code save} or {@code release} before its save: the file the draft started from, the
 * base of the merge of {@link StateMerge}, and the draft of the caller's edits with the file of the document or entry
 * carrying the state the transition goes to.
 */
final class DraftStaging {

    private final StoreWriter writer;
    private final TransitionContext context;

    /**
     * Creates the staging of the drafts of a kind.
     *
     * @param writer  the write path of the repository of the kind
     * @param context the kind and the reader of the writer's bare clone
     */
    DraftStaging(StoreWriter writer, TransitionContext context) {
        this.writer = writer;
        this.context = context;
    }

    /**
     * The decoded file the draft started from, the base of the merge of {@link StateMerge}: the file at the merge base
     * of the draft and the branch of the key when the draft exists and they have a common commit, else the file on that
     * branch ({@link CloneReader#startedFrom(String, String, String)}), so that a draft that does not exist yet has
     * changed nothing; empty when there is no file.
     *
     * @param draft        the full name of the draft reference
     * @param key          the document or entry
     * @param documentPath the path of its file
     * @return the decoded file, or the passed-on {@link StoreOutcome#MALFORMED} for a file that cannot be decoded
     * @throws ReadFailure for an I/O failure while reading the clone
     */
    FormsResult<Map<String, @Nullable Object>> base(String draft, DocumentKey key, String documentPath) {
        Optional<byte[]> content = context.reader().startedFrom(draft, context.branchOf(key), documentPath);
        if (content.isEmpty()) {
            return FormsResult.ok(Map.of());
        }
        StoreResult<Map<String, @Nullable Object>> decoded = context.codec().decode(content.get());
        if (!decoded.isOk()) {
            return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, documentPath + ": " + decoded.detail()));
        }
        return FormsResult.ok(Objects.requireNonNull(decoded.value()));
    }

    /**
     * Writes the caller's edits and the file of the document or entry with the state {@code to} as a draft, so the
     * draft always has a change; the step makes the final content of that file on every attempt.
     *
     * @param call     the transition
     * @param draftRef the full name of the draft reference
     * @return {@code null} when the draft was written, otherwise the result that ends the transition
     */
    @Nullable
    Result draftWithState(TransitionCall call, String draftRef) {
        String documentPath = call.documentPath();
        EditSplit split = EditSplit.of(call.change().edits(), documentPath);
        Optional<byte[]> content;
        try {
            content = split.edited ? split.content : draftContent(draftRef, call.key(), documentPath);
        } catch (ReadFailure e) {
            return TransitionOutcomes.readFailed(e);
        }
        List<TreeEdit> edits = new ArrayList<>(split.others);
        Map<String, @Nullable Object> document = new LinkedHashMap<>();
        if (content.isPresent()) {
            StoreResult<Map<String, @Nullable Object>> decoded = context.codec().decode(content.get());
            if (!decoded.isOk()) {
                return new Result.NotPerformed(new FormsResult.PassedOn(StoreOutcome.MALFORMED),
                        documentPath + ": " + decoded.detail());
            }
            document.putAll(Objects.requireNonNull(decoded.value()));
        }
        document.put(TransitionStep.STATE, call.transition().to());
        edits.add(new TreeEdit.Put(documentPath, context.codec().encode(document)));
        StoreResult<String> drafted = writer.draft(call.author(), call.key(), edits, call.change().message());
        return drafted.isOk() ? null : TransitionOutcomes.passOn(drafted);
    }

    /**
     * Reads the file of the document or entry from the draft of the author, else from the branch of the key.
     *
     * @throws ReadFailure for an I/O failure while reading the clone
     */
    private Optional<byte[]> draftContent(String draft, DocumentKey key, String documentPath) {
        CloneReader clone = context.reader();
        return clone.read(clone.exists(draft) ? draft : context.branchOf(key), documentPath);
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
}
