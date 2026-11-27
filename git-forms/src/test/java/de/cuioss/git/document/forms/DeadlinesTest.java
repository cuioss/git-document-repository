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
import static de.cuioss.git.document.forms.DocumentTransitionsTest.NOW;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.PASSWORD;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.bytes;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.directory;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.document;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.value;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;


import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.IndexContributor;
import de.cuioss.git.document.store.StoreIndex;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.Ulid;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The deadlines of a workflow (GDR-FORMS-6): the arithmetic of each rule form ({@code within} and
 * {@code review-interval} with a {@link Period} across a month end and a {@link Duration} with a time part,
 * {@code field} with a date and a date-time), the inclusive bound, the contributor that keeps only the state and the
 * named fields, and {@link Deadlines#due} over a real index of a local remote, which lists a document in
 * {@code review} with {@code within: P14D} entered at T for T plus 14 days and not one millisecond before.
 * <p>
 * The instants, rules and workflows are literals, being the contract; the keys are generated ULIDs.
 */
@DisplayName("Deadlines (GDR-FORMS-6)")
class DeadlinesTest {

    /** The instant a document entered its state in the arithmetic tests. */
    private static final Instant T = Instant.parse("2026-03-10T08:30:00Z");

    /** The field of the {@code field} rule of the test workflow. */
    private static final String REVIEW_BY = "review-by";

    /** The workflow of the tests: {@code review} due within 14 days, {@code approved} by its field and periodically. */
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
        return new DocumentTransitions.Definition(DocumentTransitionsTest.perDocument(), DocumentTransitionsTest.schema(),
                workflow(), DocumentTransitionsTest.template());
    }

    private static Optional<Instant> due(Deadline.Rule rule, @Nullable Instant entered, Instant at) {
        return Deadlines.dueInstant(rule, entered, Map.of(), at);
    }

    private static Optional<Instant> dueByField(@Nullable String value) {
        Map<String, String> fields = value == null ? Map.of() : Map.of(REVIEW_BY, value);
        return Deadlines.dueInstant(new Deadline.DateField(REVIEW_BY), null, fields, T);
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("GDR-FORMS-6: within P14D is due 14 days after entering, the bound inclusive")
        void withinPeriod() {
            var rule = new Deadline.Within(Period.ofDays(14));
            var dueAt = T.plus(Duration.ofDays(14));

            assertAll(
                    () -> assertEquals(Optional.of(dueAt), due(rule, T, dueAt), "due at T + 14 days"),
                    () -> assertEquals(Optional.of(dueAt), due(rule, T, dueAt.minusMillis(1)),
                            "the due instant does not depend on the instant asked about"),
                    () -> assertTrue(dueAt.minusMillis(1).isBefore(dueAt),
                            "one millisecond before, the due instant lies after the instant, so it is not listed"));
        }

        @Test
        @DisplayName("GDR-FORMS-6: a Period with months and years is added in UTC across a month end")
        void periodAcrossMonthEnd() {
            var januaryEnd = Instant.parse("2026-01-31T10:00:00Z");
            var leapDay = Instant.parse("2028-02-29T10:00:00Z");

            assertAll(
                    () -> assertEquals(Optional.of(Instant.parse("2026-02-28T10:00:00Z")),
                            due(new Deadline.Within(Period.ofMonths(1)), januaryEnd, januaryEnd),
                            "P1M from 31 January ends on the last day of February"),
                    () -> assertEquals(Optional.of(Instant.parse("2029-02-28T10:00:00Z")),
                            due(new Deadline.Within(Period.ofYears(1)), leapDay, leapDay),
                            "P1Y from a leap day ends on 28 February"),
                    () -> assertEquals(Optional.of(Instant.parse("2027-03-31T10:00:00Z")),
                            due(new Deadline.Within(Period.parse("P1Y2M")), januaryEnd, januaryEnd),
                            "P1Y2M from 31 January ends on 31 March of the next year"));
        }

        @Test
        @DisplayName("GDR-FORMS-6: a Duration with a time part is added to the instant")
        void durationWithTimePart() {
            assertAll(
                    () -> assertEquals(Optional.of(T.plus(Duration.ofHours(36))),
                            due(new Deadline.Within(Duration.parse("PT36H")), T, T), "PT36H"),
                    () -> assertEquals(Optional.of(T.plus(Duration.ofMinutes(90))),
                            due(new Deadline.Within(Duration.parse("PT1H30M")), T, T), "PT1H30M"));
        }

        @Test
        @DisplayName("GDR-FORMS-6: review-interval is due at its latest occurrence at or before the instant")
        void reviewInterval() {
            var weekly = new Deadline.ReviewInterval(Period.ofDays(7));
            var hourly = new Deadline.ReviewInterval(Duration.ofHours(1));
            var monthly = new Deadline.ReviewInterval(Period.ofMonths(1));
            var januaryEnd = Instant.parse("2026-01-31T10:00:00Z");

            assertAll(
                    () -> assertEquals(Optional.of(T.plus(Duration.ofDays(7))), due(weekly, T, T.plus(Duration.ofDays(6))),
                            "before the first occurrence the first one, not yet due"),
                    () -> assertEquals(Optional.of(T.plus(Duration.ofDays(7))), due(weekly, T, T.plus(Duration.ofDays(7))),
                            "exactly at the first occurrence"),
                    () -> assertEquals(Optional.of(T.plus(Duration.ofDays(14))),
                            due(weekly, T, T.plus(Duration.ofDays(20))), "the second occurrence"),
                    () -> assertEquals(Optional.of(T.plus(Duration.ofDays(21))),
                            due(weekly, T, T.plus(Duration.ofDays(21))), "the third occurrence, inclusive"),
                    () -> assertEquals(Optional.of(T.plus(Duration.ofHours(5))),
                            due(hourly, T, T.plus(Duration.ofMinutes(5 * 60 + 59))), "a Duration interval"),
                    () -> assertEquals(Optional.of(Instant.parse("2026-04-30T10:00:00Z")),
                            due(monthly, januaryEnd, Instant.parse("2026-05-01T00:00:00Z")),
                            "entered + 3 * P1M from 31 January, across month ends"));
        }

        @Test
        @DisplayName("GDR-FORMS-6: within and review-interval have no deadline without an entering instant")
        void noEnteringInstant() {
            assertAll(
                    () -> assertEquals(Optional.empty(), due(new Deadline.Within(Period.ofDays(1)), null, T), "within"),
                    () -> assertEquals(Optional.empty(), due(new Deadline.ReviewInterval(Period.ofDays(1)), null, T),
                            "review-interval"));
        }

        @Test
        @DisplayName("GDR-FORMS-6: an amount beyond the range of Instant is due never")
        void beyondTheRange() {
            assertEquals(Optional.empty(), due(new Deadline.Within(Period.ofYears(999_999_999)), T, T),
                    "no exception, no deadline");
        }

        @Test
        @DisplayName("GDR-FORMS-6: field is due at an ISO date in UTC or a date-time with offset")
        void fieldForms() {
            assertAll(
                    () -> assertEquals(Optional.of(Instant.parse("2026-12-01T00:00:00Z")), dueByField("2026-12-01"),
                            "a date, at the start of its day in UTC"),
                    () -> assertEquals(Optional.of(Instant.parse("2026-12-01T08:00:00Z")),
                            dueByField("2026-12-01T09:00:00+01:00"), "a date-time with an offset"));
        }

        @Test
        @DisplayName("GDR-FORMS-6: an unreadable field value and a missing field give no deadline")
        void fieldWithoutDate() {
            assertAll(
                    () -> assertEquals(Optional.empty(), dueByField("next spring"), "not a date"),
                    () -> assertEquals(Optional.empty(), dueByField("2026-12-01T09:00:00"),
                            "a date-time without an offset"),
                    () -> assertEquals(Optional.empty(), dueByField("2026-02-30"), "no such day"),
                    () -> assertEquals(Optional.empty(), dueByField(null), "a document without the field"));
        }
    }

    @Nested
    @DisplayName("contributor")
    class Contributor {

        private final Deadlines deadlines = new Deadlines(definition());

        private IndexContributor.Document file(DocumentKey key, byte[] content) {
            return new IndexContributor.Document(key, StoreReferences.target(key),
                    Map.of(directory(key) + DocumentLayout.DOCUMENT_FILE, content));
        }

        private byte[] fields(Map<String, @Nullable Object> fields) {
            return new YamlDocumentCodec(DocumentTransitionsTest.schema()).encode(new LinkedHashMap<>(fields));
        }

        @Test
        @DisplayName("GDR-FORMS-6: the contributor keeps the state and the named string fields, nothing else")
        void keepsStateAndNamedFields() {
            var key = document();
            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            data.put("title", "A title");
            data.put("owner", "p-owner");
            data.put("state", "approved");
            data.put(REVIEW_BY, "2026-12-01");

            var entry = deadlines.contributor().contribute(file(key, fields(data)));

            var kept = Objects.requireNonNull(entry);
            assertAll(
                    () -> assertEquals(new Deadlines.Entry("approved", Map.of(REVIEW_BY, "2026-12-01")), kept,
                            "the state and the field of the field rule"),
                    () -> assertFalse(kept.toString().contains("2026-12-01"), "the rendering names no value"));
        }

        @Test
        @DisplayName("GDR-FORMS-6: a document without the field, or with a non-string value, keeps no field")
        void withoutTheField() {
            var key = document();
            Map<String, @Nullable Object> withNumber = new LinkedHashMap<>();
            withNumber.put("state", "approved");
            withNumber.put(REVIEW_BY, 20261201L);

            assertAll(
                    () -> assertEquals(new Deadlines.Entry("approved", Map.of()),
                            deadlines.contributor().contribute(file(key, fields(Map.of("state", "approved")))),
                            "no field"),
                    () -> assertEquals(new Deadlines.Entry("approved", Map.of()),
                            deadlines.contributor().contribute(file(key, fields(withNumber))), "a number"));
        }

        @Test
        @DisplayName("GDR-FORMS-6: a file without state is in the initial state, an undecodable file is unreadable")
        void stateAndUnreadable() {
            var key = document();
            Map<String, @Nullable Object> numberState = new LinkedHashMap<>();
            numberState.put("state", 3L);

            assertAll(
                    () -> assertEquals(new Deadlines.Entry("draft", Map.of()),
                            deadlines.contributor().contribute(file(key, fields(Map.of("title", "x")))),
                            "the initial state"),
                    () -> assertEquals(Deadlines.Entry.unreadable(), deadlines.contributor().contribute(
                            file(key, "a: [".getBytes(StandardCharsets.UTF_8))), "not YAML"),
                    () -> assertEquals(Deadlines.Entry.unreadable(),
                            deadlines.contributor().contribute(file(key, fields(numberState))), "a state no string"),
                    () -> assertNull(deadlines.contributor().contribute(new IndexContributor.Document(key,
                                    StoreReferences.target(key), Map.of(directory(key) + "narrative/a.adoc", bytes("a")))),
                            "no file of the document, left out"));
        }
    }

    @Nested
    @DisplayName("due")
    class OverTheIndex {

        private Map<String, String> refs(Repository repository) {
            try {
                return repository.getRefDatabase().getRefs().stream()
                        .collect(Collectors.toMap(Ref::getName, ref -> String.valueOf(ref.getObjectId()),
                                (first, second) -> first, TreeMap::new));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Test
        @DisplayName("GDR-FORMS-6: a document in review with within P14D entered at T is due at T + 14 days, inclusive")
        void withinOverTheIndex(@Remote LocalRemote remote) {
            DocumentTransitionsTest.seedMain(remote);
            var key = document();
            var definition = definition();
            var deadlines = new Deadlines(definition);
            var collection = value(DocumentCollection.of(COLLECTION, DocumentCollection.Kind.PER_DOCUMENT));
            try (var fixture = DocumentTransitionsTest.open(temp, remote, definition,
                         new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE));
                 StoreIndex<Deadlines.Entry> index = value(StoreIndex.open(fixture.writer(),
                         Map.of(collection, deadlines.contributor())))) {
                var author = Author.of("p-author");
                var edits = List.<TreeEdit>of(
                        new TreeEdit.Put(directory(key) + DocumentLayout.DOCUMENT_FILE,
                                DocumentTransitionsTest.data("First", "owner", null)),
                        new TreeEdit.Put(directory(key) + "narrative/summary.adoc", bytes("A summary.\n")));
                assertInstanceOf(DocumentTransitions.Result.Performed.class,
                        fixture.perform(author, PASSWORD, key, "submit", edits), "submitted into review");
                var dueAt = NOW.plus(Duration.ofDays(14));
                var before = refs(fixture.bareClone());

                var atDue = deadlines.due(index, fixture.bareClone(), dueAt);
                var justBefore = deadlines.due(index, fixture.bareClone(), dueAt.minusMillis(1));

                var deadline = workflow().deadlines("review").getFirst();
                assertAll(
                        () -> assertEquals(FormsResult.ok(List.of(new Deadlines.Due(key, "review", deadline, dueAt))),
                                atDue, "listed at T + 14 days"),
                        () -> assertEquals(FormsResult.ok(List.of()), justBefore,
                                "not listed one millisecond before"),
                        () -> assertEquals(before, refs(fixture.bareClone()), "nothing written to the clone"));
            }
        }

        @Test
        @DisplayName("GDR-FORMS-6: a document whose indexed file cannot be read ends the call as MALFORMED, naming the key")
        void unreadableDocument(@Remote LocalRemote remote) {
            var main = DocumentTransitionsTest.seedMain(remote);
            var key = new DocumentKey(value(DocumentCollection.of(COLLECTION, DocumentCollection.Kind.PER_DOCUMENT)),
                    Ulid.next());
            DocumentTransitionsTest.commit(remote.repository(), StoreReferences.target(key),
                    Map.of(directory(key) + DocumentLayout.DOCUMENT_FILE, bytes("a: [\n")), main);
            var definition = definition();
            var deadlines = new Deadlines(definition);
            var collection = key.collection();
            try (var fixture = DocumentTransitionsTest.open(temp, remote, definition,
                         new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE))) {
                value(fixture.writer().fetch());
                try (StoreIndex<Deadlines.Entry> index = value(StoreIndex.open(fixture.writer(),
                             Map.of(collection, deadlines.contributor())))) {

                    var result = deadlines.due(index, fixture.bareClone(), NOW);

                    assertAll(
                            () -> assertEquals(new FormsResult.PassedOn(StoreOutcome.MALFORMED), result.outcome(),
                                    "passed on, no exception"),
                            () -> assertEquals("deadlines: " + key.id() + ": the file cannot be read", result.detail(),
                                    "the key and the problem"));
                }
            }
        }
    }

    @Test
    @DisplayName("GDR-FORMS-6: the workflow lists the deadlines of one state in file order")
    void deadlinesOfAState() {
        var workflow = workflow();

        assertAll(
                () -> assertEquals(List.of(new Deadline("review", new Deadline.Within(Period.ofDays(14)))),
                        workflow.deadlines("review"), "review"),
                () -> assertEquals(List.of(new Deadline("approved", new Deadline.DateField(REVIEW_BY)),
                                new Deadline("approved", new Deadline.ReviewInterval(Period.ofYears(1)))),
                        workflow.deadlines("approved"), "approved, in file order"),
                () -> assertEquals(List.of(), workflow.deadlines("draft"), "a state without deadline"));
    }
}
