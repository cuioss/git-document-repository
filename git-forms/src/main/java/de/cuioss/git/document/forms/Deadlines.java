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

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import de.cuioss.git.document.core.RepositoryReader;
import de.cuioss.git.document.forms.CloneReader.ReadFailure;
import de.cuioss.git.document.forms.DocumentTransitions.Definition;
import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.IndexContributor;
import de.cuioss.git.document.store.StoreIndex;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.StoreResult;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The deadlines of one kind of document (GDR-FORMS-6): which documents have a deadline of their workflow due at or
 * before an instant, computed from the in-memory index of {@code git-store} and never stored.
 * <p>
 * The application registers {@link #contributor()} for the collection of the kind when it opens its
 * {@link StoreIndex}; the contributor keeps of every document or entry only its state and the values of the top-level
 * fields the {@code field} rules of the workflow name, never other data. {@link #due(StoreIndex, Repository, Instant)}
 * then reads, per document, the current state from the indexed target branch of its key, else from {@code main}, as
 * {@link DocumentTransitions} does, and evaluates the deadlines of that state ({@link Workflow#deadlines(String)}) by
 * the rules of {@link Deadline}. The instant a document entered its current state is the commit time of the oldest
 * commit of the trailing run of commits that changed its file and whose file holds that state, read with
 * {@link RepositoryReader#history(Repository, String, String, int)} of {@code git-core} from the indexed commit of the
 * source; a save that keeps the state continues the run, and a state left and entered again starts a new one. It is
 * computed on every call and read only for a state with a {@code within} or {@code review-interval} rule; the walk ends
 * at the first commit whose file holds another state, or no file, and reads the history in pages of
 * {@value #FIRST_PAGE} commits that double while the run goes on.
 * <p>
 * Nothing is stored and nothing is written: no file, reference or object. A document whose file cannot be decoded, or
 * whose history cannot be read, ends the call with the passed-on outcome, never with an exception; a detail names the
 * key and the problem, never a data value or a pseudonym (GDR-NFR-3). An instance holds no state of its own beyond the
 * definition and may serve every thread; {@code due} reads the clone and the index only.
 *
 * @since 0.1
 */
public final class Deadlines {

    /** The commits of the first page of a history walk; each further page doubles it. */
    static final int FIRST_PAGE = 16;

    private final Definition definition;
    private final DocumentCollection collection;
    private final DocumentLayout layout;
    private final YamlDocumentCodec codec;
    private final Set<String> fields;
    private final RepositoryReader reader = new RepositoryReader();

    /**
     * Creates the deadlines of a kind.
     *
     * @param definition the kind, its schema and its workflow
     * @throws NullPointerException     if {@code definition} is {@code null}
     * @throws IllegalArgumentException if the collection or the layout of the kind cannot be created
     */
    public Deadlines(Definition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
        Kind kind = definition.kind();
        StoreResult<DocumentCollection> named = DocumentCollection.of(kind.collection(),
                kind.isNumbered() ? DocumentCollection.Kind.NUMBERED : DocumentCollection.Kind.PER_DOCUMENT);
        if (!named.isOk()) {
            throw new IllegalArgumentException("collection: " + named.detail());
        }
        this.collection = Objects.requireNonNull(named.value());
        this.layout = new DocumentLayout(kind, definition.schema());
        this.codec = new YamlDocumentCodec(definition.schema());
        Set<String> dateFields = new LinkedHashSet<>();
        for (Deadline deadline : definition.workflow().deadlines()) {
            if (deadline.rule() instanceof Deadline.DateField(String name)) {
                dateFields.add(name);
            }
        }
        this.fields = Set.copyOf(dateFields);
    }

    /**
     * The contributor to register for the collection of the kind: it decodes the file of the document or entry and
     * keeps its state and the values of the fields the {@code field} rules name, each only when it is a string. A
     * document without its file is left out; a file that cannot be decoded, or whose {@code state} is no string, is
     * kept as {@link Entry#unreadable()}, so that {@link #due} reports it.
     *
     * @return the contributor
     */
    public IndexContributor<Entry> contributor() {
        return document -> {
            DocumentKey key = document.key();
            if (!key.collection().equals(collection)) {
                return null;
            }
            String path = documentPath(key);
            byte[] content = document.files().get(path);
            if (content == null) {
                return null;
            }
            StoreResult<Map<String, @Nullable Object>> decoded = codec.decode(content);
            if (!decoded.isOk()) {
                return Entry.unreadable();
            }
            Map<String, @Nullable Object> data = Objects.requireNonNull(decoded.value());
            @Nullable Object state = data.containsKey(TransitionStep.STATE) ? data.get(TransitionStep.STATE)
                    : definition.workflow().initialState();
            if (!(state instanceof String current)) {
                return Entry.unreadable();
            }
            Map<String, String> kept = new LinkedHashMap<>();
            for (String field : fields) {
                if (data.get(field) instanceof String value) {
                    kept.put(field, value);
                }
            }
            return new Entry(current, kept);
        };
    }

    /**
     * Lists every document or entry of the kind whose deadline in its current state is due at or before an instant.
     *
     * @param index the index of the repository of the kind, opened with {@link #contributor()} for its collection
     * @param clone the bare clone of the writer the index reads, only read, for the history of a document file
     * @param at    the instant
     * @return {@link FormsOutcome#OK} with every due deadline, sorted by due instant and then by key; the passed-on
     *         {@link StoreOutcome#MALFORMED} for a document whose file cannot be read, or the Git outcome of a history
     *         that cannot be read passed on; the detail names the key and the problem, never a value
     * @throws NullPointerException if an argument is {@code null}
     */
    public FormsResult<List<Due>> due(StoreIndex<Entry> index, Repository clone, Instant at) {
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(clone, "clone");
        Objects.requireNonNull(at, "at");
        CloneReader files = new CloneReader(clone);
        List<Due> due = new ArrayList<>();
        for (Map.Entry<DocumentKey, Indexed> current : current(index).entrySet()) {
            FormsResult<List<Due>> found = due(clone, files, current.getKey(), current.getValue(), at);
            if (!found.isOk()) {
                return found;
            }
            due.addAll(Objects.requireNonNull(found.value()));
        }
        due.sort(Comparator.comparing(Due::due).thenComparing(found -> found.key().id()));
        return FormsResult.ok(List.copyOf(due));
    }

    /**
     * The current entry of every document: the one of the indexed target branch of its key, else the one of
     * {@code main}, with the commit of the source it was read from. The commit and the entries of a source are read
     * from one published index ({@link StoreIndex#sourceEntries}), so a refresh in between cannot pair them wrongly.
     */
    private Map<DocumentKey, Indexed> current(StoreIndex<Entry> index) {
        Map<DocumentKey, Indexed> current = new LinkedHashMap<>();
        for (String source : index.sources()) {
            boolean main = StoreReferences.MAIN.equals(source);
            Optional<StoreIndex.SourceEntries<Entry>> read = index.sourceEntries(source, collection);
            if (read.isEmpty()) {
                continue;
            }
            String commit = read.get().commitId();
            read.get().entries().forEach((key, entry) -> {
                if (main ? !current.containsKey(key) : source.equals(StoreReferences.target(key))) {
                    current.put(key, new Indexed(commit, entry));
                }
            });
        }
        return current;
    }

    /** The due deadlines of one document. */
    private FormsResult<List<Due>> due(Repository clone, CloneReader files, DocumentKey key, Indexed indexed,
            Instant at) {
        Entry entry = indexed.entry();
        String state = entry.state();
        if (state == null) {
            return malformed(key, "the file cannot be read");
        }
        List<Deadline> deadlines = definition.workflow().deadlines(state);
        if (deadlines.isEmpty()) {
            return FormsResult.ok(List.of());
        }
        @Nullable Instant entered = null;
        if (deadlines.stream().anyMatch(deadline -> !(deadline.rule() instanceof Deadline.DateField))) {
            FormsResult<Optional<Instant>> read = entered(clone, files, key, indexed.commitId(), state);
            if (!read.isOk()) {
                return new FormsResult<>(read.outcome(), null, read.detail());
            }
            entered = Objects.requireNonNull(read.value()).orElse(null);
        }
        List<Due> due = new ArrayList<>();
        for (Deadline deadline : deadlines) {
            dueInstant(deadline.rule(), entered, entry.fields(), at)
                    .filter(instant -> !instant.isAfter(at))
                    .ifPresent(instant -> due.add(new Due(key, state, deadline, instant)));
        }
        return FormsResult.ok(due);
    }

    /**
     * The instant a document entered its current state: the commit time of the oldest commit of the trailing run of
     * commits that changed its file and whose file holds the state. Empty when no commit of the source holds the file.
     * The run ends at a commit without the file or whose file holds another state; a version of the file that cannot
     * be read, or whose {@code state} is no string, ends the call with the result of
     * {@link TransitionStep#readState}, the passed-on {@link StoreOutcome#MALFORMED}.
     */
    private FormsResult<Optional<Instant>> entered(Repository clone, CloneReader files, DocumentKey key,
            String commitId, String state) {
        String path = documentPath(key);
        int limit = FIRST_PAGE;
        while (true) {
            GitResult<List<CommitInfo>> history = reader.history(clone, commitId, path, limit);
            if (!history.isOk()) {
                return FormsResult.passOn(StoreResult.passOn(history));
            }
            List<CommitInfo> commits = Objects.requireNonNull(history.value());
            @Nullable Instant entered = null;
            for (CommitInfo changed : commits) {
                FormsResult<Boolean> holds = holdsState(files, key, changed, path, state);
                if (!holds.isOk()) {
                    return new FormsResult<>(holds.outcome(), null, holds.detail());
                }
                if (!Boolean.TRUE.equals(holds.value())) {
                    return FormsResult.ok(Optional.ofNullable(entered));
                }
                entered = changed.committedAt();
            }
            if (commits.size() < limit || limit > Integer.MAX_VALUE / 2) {
                return FormsResult.ok(Optional.ofNullable(entered));
            }
            limit *= 2;
        }
    }

    /**
     * Whether the file of a commit holds the state: {@code true} when it does, {@code false} when the commit has no
     * file or its file holds another state. A version of the file that cannot be read ends the call with the passed-on
     * failure, never as if the file held another state.
     */
    private FormsResult<Boolean> holdsState(CloneReader files, DocumentKey key, CommitInfo changed, String path,
            String state) {
        Optional<byte[]> content;
        try {
            content = files.readAt(changed.id(), path);
        } catch (ReadFailure e) {
            return FormsResult.passOn(StoreResult.passOn(GitResult.of(GitOutcome.FAILED,
                    "deadlines: " + key.id() + ": " + e.getCause().getClass().getSimpleName())));
        }
        if (content.isEmpty()) {
            return FormsResult.ok(false);
        }
        FormsResult<String> earlier = TransitionStep.readState(codec, content, path,
                definition.workflow().initialState());
        if (!earlier.isOk()) {
            return new FormsResult<>(earlier.outcome(), null, earlier.detail());
        }
        return FormsResult.ok(state.equals(earlier.value()));
    }

    /**
     * The due instant of a rule, or empty when it has none: a missing entering instant for {@code within} and
     * {@code review-interval}, a {@code field} without a readable date, or an instant beyond the range of
     * {@link Instant}.
     */
    static Optional<Instant> dueInstant(Deadline.Rule rule, @Nullable Instant entered, Map<String, String> fields,
            Instant at) {
        try {
            return switch (rule) {
                case Deadline.Within(var amount) -> entered == null ? Optional.empty()
                        : Optional.of(plus(entered, amount, 1));
                case Deadline.ReviewInterval(var amount) -> entered == null ? Optional.empty()
                        : Optional.of(latestOccurrence(entered, amount, at));
                case Deadline.DateField(var name) -> date(fields.get(name));
            };
        } catch (DateTimeException | ArithmeticException e) {
            // an amount that leaves the range of Instant is due never
            return Optional.empty();
        }
    }

    /**
     * The latest {@code entered + k * amount}, {@code k} at least 1, at or before the instant; the first occurrence
     * when it lies after the instant, which is then not due.
     */
    private static Instant latestOccurrence(Instant entered, TemporalAmount amount, Instant at) {
        Instant first = plus(entered, amount, 1);
        if (first.isAfter(at)) {
            return first;
        }
        if (amount instanceof Duration duration) {
            long k = Duration.between(entered, at).dividedBy(duration);
            return plus(entered, duration, Math.max(1, k));
        }
        // a Period: the occurrences grow with k, so the latest one at or before the instant is found by bisection
        long low = 1;
        long high = 2;
        while (!plus(entered, amount, high).isAfter(at)) {
            low = high;
            high = Math.multiplyExact(high, 2);
        }
        while (high - low > 1) {
            long middle = low + (high - low) / 2;
            if (plus(entered, amount, middle).isAfter(at)) {
                high = middle;
            } else {
                low = middle;
            }
        }
        return plus(entered, amount, low);
    }

    /** An instant plus a multiple of an amount: a {@link Period} in UTC, a {@link Duration} on the instant. */
    private static Instant plus(Instant instant, TemporalAmount amount, long times) {
        if (amount instanceof Period period) {
            Period multiple = period.multipliedBy(Math.toIntExact(times));
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC).plus(multiple).toInstant();
        }
        Duration duration = (Duration) amount;
        return instant.plus(duration.multipliedBy(times));
    }

    /** The instant of a field value: an ISO 8601 date at the start of its day in UTC, or a date-time with offset. */
    private static Optional<Instant> date(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(value).atStartOfDay(ZoneOffset.UTC).toInstant());
        } catch (DateTimeParseException notADate) {
            try {
                return Optional.of(OffsetDateTime.parse(value).toInstant());
            } catch (DateTimeParseException notADateTime) {
                return Optional.empty();
            }
        }
    }

    /** The path of the file of a document or entry of the kind. */
    private String documentPath(DocumentKey key) {
        FormsResult<String> path = definition.kind().isNumbered() ? layout.entry(key.id()) : layout.document(key.id());
        return Objects.requireNonNull(path.value(), "a key holds a valid name segment");
    }

    private static <T> FormsResult<T> malformed(DocumentKey key, String problem) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, "deadlines: " + key.id() + ": " + problem));
    }

    /** The entry of a document and the indexed commit of the source it was read from. */
    private record Indexed(String commitId, Entry entry) {
    }

    /**
     * The value the index keeps for a document or entry of the kind: its state and the values of the fields the
     * {@code field} rules of the workflow name, nothing else.
     *
     * @param state  the current state, or {@code null} when the file cannot be decoded or its {@code state} is no string
     * @param fields the string value of every field a {@code field} rule names that the document holds as a string
     * @since 0.1
     */
    public record Entry(@Nullable String state, Map<String, String> fields) {

        /**
         * Copies the fields.
         *
         * @param state  the state
         * @param fields the fields
         * @throws NullPointerException if {@code fields}, a name or a value is {@code null}
         */
        public Entry {
            fields = Map.copyOf(Objects.requireNonNull(fields, "fields"));
        }

        /**
         * The entry of a document whose file cannot be read.
         *
         * @return the entry, without state and fields
         */
        public static Entry unreadable() {
            return new Entry(null, Map.of());
        }

        /**
         * Renders the state and the names of the fields only, never a value (GDR-NFR-3).
         *
         * @return the rendering
         */
        @Override
        public String toString() {
            return "Entry[state=" + state + ", fields=" + fields.keySet() + "]";
        }
    }

    /**
     * A deadline that is due.
     *
     * @param key      the document or entry
     * @param state    its current state
     * @param deadline the deadline of that state
     * @param due      the instant the deadline is due at, at or before the instant asked about
     * @since 0.1
     */
    public record Due(DocumentKey key, String state, Deadline deadline, Instant due) {

        /**
         * Checks the components.
         *
         * @param key      the key
         * @param state    the state
         * @param deadline the deadline
         * @param due      the due instant
         * @throws NullPointerException if a component is {@code null}
         */
        public Due {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(due, "due");
        }
    }
}
