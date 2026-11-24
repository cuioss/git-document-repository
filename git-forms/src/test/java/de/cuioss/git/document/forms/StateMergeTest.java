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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The merge of the file a transition writes onto the tip (GDR-FORMS-5): the tip's file, with the draft's value of every
 * key the draft changed against its base, and the new state. The documents are literals, being the rule.
 */
@DisplayName("StateMerge (GDR-FORMS-5)")
class StateMergeTest {

    private static final String STATE = TransitionStep.STATE;

    @Test
    @DisplayName("GDR-FORMS-5: a state-only transition writes the tip with the new state")
    void stateOnly() {
        var base = Map.<String, Object>of("title", "First", STATE, "review");
        var draft = Map.<String, Object>of("title", "First", STATE, "draft");
        var tip = Map.<String, Object>of("title", "Changed", STATE, "review");

        assertEquals(Map.of("title", "Changed", STATE, "draft"), StateMerge.merge(tip, draft, base, "draft"),
                "the tip's title kept");
    }

    @Test
    @DisplayName("GDR-FORMS-5: a changed key takes the draft's value, an untouched one the tip's, compared deeply")
    void changedAndUntouchedKeys() {
        var base = Map.<String, Object>of("title", "First", "owner", "a", "tags", List.of("x"));
        var draft = Map.<String, Object>of("title", "First", "owner", "b", "tags", List.of("x"));
        var tip = Map.<String, Object>of("title", "Theirs", "owner", "c", "tags", List.of("y"));

        assertEquals(Map.of("title", "Theirs", "owner", "b", "tags", List.of("y"), STATE, "review"),
                StateMerge.merge(tip, draft, base, "review"), "the draft wins only where it changed");
    }

    @Test
    @DisplayName("GDR-FORMS-5: a key the draft added is taken, a key it deleted is removed")
    void addedAndDeletedKeys() {
        var base = Map.<String, Object>of("title", "First", "note", "old");
        var draft = Map.<String, Object>of("title", "First", "extra", "new");
        var tip = Map.<String, Object>of("title", "First", "note", "old", "other", "kept");

        assertEquals(Map.of("title", "First", "extra", "new", "other", "kept", STATE, "review"),
                StateMerge.merge(tip, draft, base, "review"), "added taken, deleted removed, the tip's own kept");
    }

    @Test
    @DisplayName("GDR-FORMS-5: without a file on the tip the draft is written with the state")
    void noTipFile() {
        var draft = Map.<String, Object>of("title", "New", STATE, "old");

        assertEquals(Map.of("title", "New", STATE, "review"), StateMerge.merge(null, draft, Map.of(), "review"),
                "the draft with the state");
    }
}
