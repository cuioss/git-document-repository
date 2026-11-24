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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import org.jspecify.annotations.Nullable;

/**
 * The merge of the file of a document or entry that a transition writes onto the tip of an attempt (GDR-FORMS-5).
 * <p>
 * The inputs are three decoded documents: TIP, the file on the tip the attempt commits on; DRAFT, the file the draft
 * stages; and BASE, the file the draft started from. A top-level key the draft <em>changed</em> is one whose value in
 * DRAFT differs from BASE (compared deeply), one DRAFT added, or one DRAFT deleted; the key {@value TransitionStep#STATE}
 * is never compared. The result is TIP, with the value of DRAFT for every key the draft changed (or without the key when
 * DRAFT deleted it), every other key keeping its TIP value, and finally {@value TransitionStep#STATE} set to the state
 * the transition writes. So a transition that changes only the state writes exactly TIP with the new state, and a
 * concurrent change of another writer on the tip is never undone by the draft's copy of an untouched field. Without a
 * file on the tip the result is DRAFT with the state.
 */
final class StateMerge {

    private StateMerge() {
    }

    /**
     * Merges the file a transition writes.
     *
     * @param tip   the file on the tip of the attempt, decoded; {@code null} when the tip holds no file
     * @param draft the file the draft stages, decoded
     * @param base  the file the draft started from, decoded; empty when there was none
     * @param state the state the transition writes
     * @return the merged document, a new mutable map
     */
    static Map<String, @Nullable Object> merge(@Nullable Map<String, @Nullable Object> tip,
            Map<String, @Nullable Object> draft, Map<String, @Nullable Object> base, String state) {
        Map<String, @Nullable Object> result = new LinkedHashMap<>(tip == null ? draft : tip);
        if (tip != null) {
            Set<String> keys = new LinkedHashSet<>(draft.keySet());
            keys.addAll(base.keySet());
            keys.remove(TransitionStep.STATE);
            for (String key : keys) {
                if (changed(draft, base, key)) {
                    take(result, draft, key);
                }
            }
        }
        result.put(TransitionStep.STATE, state);
        return result;
    }

    /** Whether the draft changed a key: added, deleted, or a value that differs from the base. */
    private static boolean changed(Map<String, @Nullable Object> draft, Map<String, @Nullable Object> base,
            String key) {
        boolean inDraft = draft.containsKey(key);
        if (inDraft != base.containsKey(key)) {
            return true;
        }
        return inDraft && !Objects.equals(draft.get(key), base.get(key));
    }

    /** Takes the draft's value of a key into the result, or removes the key the draft deleted. */
    private static void take(Map<String, @Nullable Object> result, Map<String, @Nullable Object> draft, String key) {
        if (draft.containsKey(key)) {
            result.put(key, draft.get(key));
        } else {
            result.remove(key);
        }
    }
}
