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

import static de.cuioss.git.document.forms.DocumentTransitionsTest.COLLECTION;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.MFA;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.data;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.files;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.numbered;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.perDocument;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.schema;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.seedMain;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.tip;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.Ulid;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The running numbers of GDR-FORMS-9: the ULID keys of new documents and drafts of new entries, the formatting of a
 * running number with its padding and its limit, the tell of an assigned number, and the allocation on the first save of
 * a numbered entry, which writes the entry under its number, the advanced counter and no file of the ULID key in one
 * commit.
 * <p>
 * The numberings are literals, being the contract; the ids of keys are generated ULIDs.
 */
@DisplayName("RunningNumbers (GDR-FORMS-9)")
class RunningNumbersTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("GDR-FORMS-9: newKey gives a document and the draft of an entry a fresh ULID in the collection")
    void newKey() {
        DocumentKey document = RunningNumbers.newKey(perDocument());
        DocumentKey entry = RunningNumbers.newKey(numbered());

        assertAll("the keys",
                () -> assertEquals(new DocumentCollection(COLLECTION, DocumentCollection.Kind.PER_DOCUMENT),
                        document.collection(), "per-document collection"),
                () -> assertEquals(new DocumentCollection(COLLECTION, DocumentCollection.Kind.NUMBERED),
                        entry.collection(), "numbered collection"),
                () -> assertTrue(Ulid.isValid(document.id()), "a ULID for the document"),
                () -> assertTrue(Ulid.isValid(entry.id()), "a ULID for the draft of the entry"),
                () -> assertNotEquals(document.id(), RunningNumbers.newKey(perDocument()).id(), "fresh per call"),
                () -> assertFalse(RunningNumbers.isAssigned(numbered(), entry), "a draft key carries no number"));
    }

    @Test
    @DisplayName("GDR-FORMS-9: number pads the counter to the digits of the numbering after its prefix")
    void numberPadded() {
        var numbering = new Numbering("R-", 3);

        assertAll("the numbers",
                () -> assertEquals(FormsResult.ok("R-001"), RunningNumbers.number(numbering, 1), "padded"),
                () -> assertEquals(FormsResult.ok("R-042"), RunningNumbers.number(numbering, 42), "padded"),
                () -> assertEquals(FormsResult.ok("R-999"), RunningNumbers.number(numbering, 999),
                        "the largest counter of three digits"),
                () -> assertEquals(FormsResult.ok("0007"), RunningNumbers.number(new Numbering("", 4), 7),
                        "an empty prefix"),
                () -> assertEquals(FormsResult.ok(Long.toString(Long.MAX_VALUE)),
                        RunningNumbers.number(new Numbering("", 19), Long.MAX_VALUE), "the largest long"));
    }

    @Test
    @DisplayName("GDR-FORMS-9: a counter that needs more digits than the numbering has is MALFORMED naming the limit")
    void numberOverflowRefused() {
        FormsResult<String> refused = RunningNumbers.number(new Numbering("R-", 3), 1000);

        assertAll("refused",
                () -> assertEquals(new FormsResult.PassedOn(StoreOutcome.MALFORMED), refused.outcome(), "malformed"),
                () -> assertEquals(".counter: a counter of more than 3 digits, the limit of the numbering",
                        refused.detail(), "the limit"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> RunningNumbers.number(new Numbering("R-", 3), 0), "a number starts at 1"));
    }

    @Test
    @DisplayName("GDR-FORMS-9: isAssigned tells a running number of the kind from a ULID and from other ids")
    void isAssigned() {
        var collection = new DocumentCollection(COLLECTION, DocumentCollection.Kind.NUMBERED);

        assertAll("assigned",
                () -> assertTrue(RunningNumbers.isAssigned(numbered(), new DocumentKey(collection, "R-042")),
                        "a running number"),
                () -> assertFalse(RunningNumbers.isAssigned(numbered(), new DocumentKey(collection, Ulid.next())),
                        "a ULID"),
                () -> assertFalse(RunningNumbers.isAssigned(numbered(), new DocumentKey(collection, "R-42")),
                        "too few digits"),
                () -> assertFalse(RunningNumbers.isAssigned(numbered(), new DocumentKey(collection, "Q-042")),
                        "another prefix"),
                () -> assertFalse(RunningNumbers.isAssigned(numbered(), new DocumentKey(collection, "R-04A")),
                        "not digits"),
                () -> assertThrows(IllegalArgumentException.class, () -> RunningNumbers.isAssigned(perDocument(),
                        RunningNumbers.newKey(perDocument())), "a per-document kind has no numbers"));
    }

    @Test
    @DisplayName("GDR-FORMS-9: the first save of an entry commits its number, the counter and no ULID file at once")
    void firstSaveAllocates(@Remote LocalRemote remote) throws Exception {
        ObjectId main = seedMain(remote);
        var definition = new DocumentTransitions.Definition(numbered(), schema(), Workflow.defaultWorkflow(), null);
        var first = RunningNumbers.newKey(numbered());
        var second = RunningNumbers.newKey(numbered());
        var author = Author.of("p-author");
        try (var fixture = DocumentTransitionsTest.open(temp, remote, definition,
                     new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE))) {
            var saved = assertInstanceOf(DocumentTransitions.Result.Performed.class, fixture.perform(author, MFA,
                    first, "submit", List.of(new TreeEdit.Put(COLLECTION + "/" + first.id() + ".yaml",
                            data("First", "owner", null)))));
            var target = StoreReferences.target(first);
            var afterFirst = files(remote.repository(), target);
            ObjectId firstCommit = Objects.requireNonNull(tip(remote.repository(), target));

            var next = assertInstanceOf(DocumentTransitions.Result.Performed.class, fixture.perform(author, MFA,
                    second, "submit", List.of(new TreeEdit.Put(COLLECTION + "/" + second.id() + ".yaml",
                            data("Second", "owner", null)))));

            var afterSecond = files(remote.repository(), target);
            assertAll("the allocation",
                    () -> assertEquals(new DocumentKey(first.collection(), "R-001"), saved.key(), "the number"),
                    () -> assertEquals(firstCommit.name(), saved.commitId(), "the save commit"),
                    () -> assertTrue(afterFirst.containsKey(COLLECTION + "/R-001.yaml"), "the entry file"),
                    () -> assertEquals("1\n", afterFirst.get(COLLECTION + "/.counter"), "the advanced counter"),
                    () -> assertFalse(afterFirst.containsKey(COLLECTION + "/" + first.id() + ".yaml"),
                            "no file of the ULID key"),
                    () -> assertEquals(List.of(main), parents(remote, firstCommit), "one commit on main"),
                    () -> assertEquals(new DocumentKey(second.collection(), "R-002"), next.key(), "the next number"),
                    () -> assertEquals("2\n", afterSecond.get(COLLECTION + "/.counter"), "the counter again"),
                    () -> assertEquals(List.of(firstCommit), parents(remote,
                            Objects.requireNonNull(tip(remote.repository(), target))), "one commit on the first"));
        }
    }

    private static List<ObjectId> parents(LocalRemote remote, ObjectId commit) throws IOException {
        try (RevWalk walk = new RevWalk(remote.repository())) {
            return Arrays.stream(walk.parseCommit(commit).getParents()).map(parent -> parent.toObjectId()).toList();
        }
    }
}
