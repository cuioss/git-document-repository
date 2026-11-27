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

import static de.cuioss.git.document.forms.DocumentTransitionsTest.bytes;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.data;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.directory;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.document;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.fields;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.value;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;


import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreIndex;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deadlines of GDR-FORMS-6 at the requirement level, over a real {@link StoreIndex} of a {@code git-testing} local
 * remote: each rule form, {@code within}, {@code review-interval} and {@code field}, exactly at, one millisecond before
 * and one millisecond after its due instant; a state left and entered again later restarts {@code within}; a save that
 * keeps the state keeps the instant it was entered; a {@code review-interval} is due at its second occurrence; and
 * {@link Deadlines#due} writes nothing to the clone.
 * <p>
 * The history of each document is written onto its working branch on the remote with fixed commit times, the instants
 * the requirement is about; the workflow and the instants are literals, being the contract, the keys generated ULIDs.
 */
@DisplayName("Deadlines at their boundaries over a real index (GDR-FORMS-6)")
class DeadlineBoundariesTest {

    /** The instant the documents of the tests enter their state. */
    private static final Instant T = Instant.parse("2026-03-10T08:30:00Z");

    /** The field of the {@code field} rule. */
    private static final String REVIEW_BY = "review-by";

    /** The value of the {@code field} rule, a date due at the start of its day in UTC. */
    private static final String REVIEW_DATE = "2026-12-01";

    /** The due instant of {@link #REVIEW_DATE}. */
    private static final Instant REVIEW_DUE = Instant.parse("2026-12-01T00:00:00Z");

    /** {@code review} due within 14 days, {@code approved} by its field and once a year. */
    private static final String WORKFLOW = """
            states: [draft, review, approved]
            transitions:
              - { name: edit,    from: draft,  to: draft,    mechanism: intermediate-save }
              - { name: submit,  from: draft,  to: review,   mechanism: save }
              - { name: rework,  from: review, to: draft,    mechanism: save }
              - { name: approve, from: review, to: approved, mechanism: release }
            deadlines:
              - { state: review,   within: P14D }
              - { state: approved, field: review-by }
              - { state: approved, review-interval: P1Y }
            """;

    @TempDir
    Path temp;

    private static Workflow workflow() {
        return Objects.requireNonNull(Workflow.parse("workflows/deadlines.yaml", bytes(WORKFLOW)).value());
    }

    private static DocumentTransitions.Definition definition() {
        return new DocumentTransitions.Definition(DocumentTransitionsTest.perDocument(),
                DocumentTransitionsTest.schema(), workflow(), DocumentTransitionsTest.template());
    }

    private static Deadline within() {
        return workflow().deadlines("review").getFirst();
    }

    private static Deadline field() {
        return workflow().deadlines("approved").getFirst();
    }

    private static Deadline interval() {
        return workflow().deadlines("approved").get(1);
    }

    /** One version of the file of a document: its content and the instant of its commit. */
    private record Version(byte[] content, Instant at) {
    }

    private static Version version(@Nullable String state, Instant at) {
        return new Version(data("Title", "owner", state), at);
    }

    /**
     * Writes the versions of the file of a document onto its working branch of the remote, one commit per version on
     * top of {@code main}, each authored and committed at its instant.
     */
    private static void history(LocalRemote remote, DocumentKey key, Version... versions) {
        ObjectId parent = DocumentTransitionsTest.seedMain(remote);
        for (Version version : versions) {
            parent = commitAt(remote.repository(), StoreReferences.target(key),
                    Map.of(directory(key) + DocumentLayout.DOCUMENT_FILE, version.content()), version.at(), parent);
        }
    }

    private static ObjectId commitAt(Repository repository, String ref, Map<String, byte[]> files, Instant at,
            ObjectId parent) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file.getValue()));
                builder.add(entry);
            }
            builder.finish();
            var ident = new PersonIdent("fixture", "", at, ZoneOffset.UTC);
            var commit = new CommitBuilder();
            commit.setTreeId(index.writeTree(inserter));
            commit.setParentIds(parent);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("fixture");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(ref);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            update.update();
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> refs(Repository repository) {
        try {
            return repository.getRefDatabase().getRefs().stream()
                    .collect(Collectors.toMap(Ref::getName, ref -> String.valueOf(ref.getObjectId()),
                            (first, second) -> first, TreeMap::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The due deadlines of the remote's documents at each instant, read over a fresh writer and its index. */
    private List<FormsResult<List<Deadlines.Due>>> dueAt(LocalRemote remote, Instant... instants) {
        var definition = definition();
        var deadlines = new Deadlines(definition);
        try (var fixture = DocumentTransitionsTest.open(temp, remote, definition,
                     new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE))) {
            value(fixture.writer().fetch());
            try (StoreIndex<Deadlines.Entry> index = value(StoreIndex.open(fixture.writer(),
                         Map.of(DocumentTransitionsTest.collection(definition.kind()), deadlines.contributor())))) {
                return Arrays.stream(instants).map(at -> deadlines.due(index, fixture.bareClone(), at))
                        .toList();
            }
        }
    }

    private static FormsResult<List<Deadlines.Due>> listed(DocumentKey key, String state, Deadline deadline,
            Instant due) {
        return FormsResult.ok(List.of(new Deadlines.Due(key, state, deadline, due)));
    }

    private static FormsResult<List<Deadlines.Due>> none() {
        return FormsResult.ok(List.of());
    }

    @Test
    @DisplayName("GDR-FORMS-6: within P14D is due exactly 14 days after entering review, not 1 ms before, still 1 ms after")
    void withinAtItsBoundary(@Remote LocalRemote remote) {
        var key = document();
        history(remote, key, version("draft", T.minus(Duration.ofDays(1))), version("review", T));
        var dueAt = T.plus(Duration.ofDays(14));

        var results = dueAt(remote, dueAt, dueAt.minusMillis(1), dueAt.plusMillis(1));

        assertAll(
                () -> assertEquals(listed(key, "review", within(), dueAt), results.getFirst(), "exactly at"),
                () -> assertEquals(none(), results.get(1), "one millisecond before"),
                () -> assertEquals(listed(key, "review", within(), dueAt), results.get(2), "one millisecond after"));
    }

    @Test
    @DisplayName("GDR-FORMS-6: a review-interval P1Y is due at its first occurrence, not 1 ms before, and at its second")
    void reviewIntervalAtItsOccurrences(@Remote LocalRemote remote) {
        var key = document();
        history(remote, key, version("review", T.minus(Duration.ofDays(1))), version("approved", T));
        var first = Instant.parse("2027-03-10T08:30:00Z");
        var second = Instant.parse("2028-03-10T08:30:00Z");

        var results = dueAt(remote, first, first.minusMillis(1), first.plusMillis(1), second,
                second.minusMillis(1), second.plusMillis(1));

        assertAll(
                () -> assertEquals(listed(key, "approved", interval(), first), results.getFirst(),
                        "exactly at the first occurrence"),
                () -> assertEquals(none(), results.get(1), "one millisecond before the first occurrence"),
                () -> assertEquals(listed(key, "approved", interval(), first), results.get(2),
                        "one millisecond after the first occurrence"),
                () -> assertEquals(listed(key, "approved", interval(), second), results.get(3),
                        "exactly at the second occurrence, due at it"),
                () -> assertEquals(listed(key, "approved", interval(), first), results.get(4),
                        "one millisecond before the second occurrence, the first is the latest"),
                () -> assertEquals(listed(key, "approved", interval(), second), results.get(5),
                        "one millisecond after the second occurrence"));
    }

    @Test
    @DisplayName("GDR-FORMS-6: a field rule is due at the start of its date in UTC, not 1 ms before, still 1 ms after")
    void fieldAtItsBoundary(@Remote LocalRemote remote) {
        var key = document();
        Map<String, @Nullable Object> approved = new LinkedHashMap<>();
        approved.put("title", "Title");
        approved.put("owner", "owner");
        approved.put("state", "approved");
        approved.put(REVIEW_BY, REVIEW_DATE);
        history(remote, key, version("review", T.minus(Duration.ofDays(1))), new Version(fields(approved), T));

        var results = dueAt(remote, REVIEW_DUE, REVIEW_DUE.minusMillis(1), REVIEW_DUE.plusMillis(1));

        assertAll(
                () -> assertEquals(listed(key, "approved", field(), REVIEW_DUE), results.getFirst(), "exactly at"),
                () -> assertEquals(none(), results.get(1), "one millisecond before"),
                () -> assertEquals(listed(key, "approved", field(), REVIEW_DUE), results.get(2),
                        "one millisecond after"));
    }

    @Test
    @DisplayName("GDR-FORMS-6: a state left and entered again later restarts within from the later entry")
    void reenteredStateRestartsWithin(@Remote LocalRemote remote) {
        var key = document();
        var reentered = T.plus(Duration.ofDays(10));
        history(remote, key, version("draft", T.minus(Duration.ofDays(1))), version("review", T),
                version("draft", T.plus(Duration.ofDays(3))), version("review", reentered));
        var firstDue = T.plus(Duration.ofDays(14));
        var dueAt = reentered.plus(Duration.ofDays(14));

        var results = dueAt(remote, firstDue, dueAt.minusMillis(1), dueAt);

        assertAll(
                () -> assertEquals(none(), results.getFirst(), "not due 14 days after the first entry"),
                () -> assertEquals(none(), results.get(1), "not due one millisecond before 14 days after re-entry"),
                () -> assertEquals(listed(key, "review", within(), dueAt), results.get(2),
                        "due 14 days after re-entry"));
    }

    @Test
    @DisplayName("GDR-FORMS-6: a self-loop save that keeps the state keeps the instant the state was entered")
    void selfLoopSaveKeepsTheEnteringInstant(@Remote LocalRemote remote) {
        var key = document();
        Map<String, @Nullable Object> changed = new LinkedHashMap<>();
        changed.put("title", "Changed");
        changed.put("owner", "owner");
        changed.put("state", "review");
        history(remote, key, version("draft", T.minus(Duration.ofDays(1))), version("review", T),
                new Version(fields(changed), T.plus(Duration.ofDays(5))));
        var dueAt = T.plus(Duration.ofDays(14));

        var results = dueAt(remote, dueAt.minusMillis(1), dueAt, T.plus(Duration.ofDays(19)));

        assertAll(
                () -> assertEquals(none(), results.getFirst(), "one millisecond before 14 days after entering"),
                () -> assertEquals(listed(key, "review", within(), dueAt), results.get(1),
                        "due 14 days after entering, not after the later save"),
                () -> assertEquals(listed(key, "review", within(), dueAt), results.get(2),
                        "14 days after the later save, still due from entering"));
    }

    @Test
    @DisplayName("GDR-FORMS-6: due writes nothing to the clone, no reference moves or appears")
    void dueWritesNothing(@Remote LocalRemote remote) {
        var key = document();
        history(remote, key, version("draft", T.minus(Duration.ofDays(1))), version("review", T));
        var definition = definition();
        var deadlines = new Deadlines(definition);
        try (var fixture = DocumentTransitionsTest.open(temp, remote, definition,
                     new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE))) {
            value(fixture.writer().fetch());
            try (StoreIndex<Deadlines.Entry> index = value(StoreIndex.open(fixture.writer(),
                         Map.of(DocumentTransitionsTest.collection(definition.kind()), deadlines.contributor())))) {
                var before = refs(fixture.bareClone());
                var remoteBefore = refs(remote.repository());

                var due = deadlines.due(index, fixture.bareClone(), T.plus(Duration.ofDays(14)));

                assertAll(
                        () -> assertEquals(listed(key, "review", within(), T.plus(Duration.ofDays(14))), due,
                                "the deadline is listed"),
                        () -> assertEquals(before, refs(fixture.bareClone()), "the references of the clone unchanged"),
                        () -> assertEquals(remoteBefore, refs(remote.repository()), "the remote unchanged"));
            }
        }
    }
}
