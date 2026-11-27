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
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;


import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.Ulid;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The running numbers of GDR-FORMS-9 under concurrency: several threads, each with its own writer and bare clone of one
 * {@code git-testing} local remote, create entries of one numbered kind at the same time; every created entry receives
 * a distinct number, the numbers run from {@code R-001} without a gap, the counter file equals the number of entries,
 * and every key {@link RunningNumbers#newKey(Kind)} gives a document is a fresh ULID.
 * <p>
 * A save that loses the race for the collection branch more often than the store retries ends unperformed and nothing
 * is pushed; the thread then performs the same creation again, as an application would. The numbering is a literal,
 * being the contract; the keys are generated ULIDs.
 */
@DisplayName("Running numbers under concurrent creators (GDR-FORMS-9)")
class ConcurrentNumberingTest {

    /** The threads that create entries at once, each with its own writer and clone. */
    private static final int THREADS = 3;

    /** The entries each thread creates. */
    private static final int ENTRIES_PER_THREAD = 3;

    /** The performances of one creation a thread tries before the test fails. */
    private static final int TRIES = 20;

    @TempDir
    Path temp;

    /** What one thread created: the keys of its entries and the per-document keys it drew. */
    private record Created(List<DocumentKey> entries, List<DocumentKey> documents) {
    }

    /** Creates the entries of one thread, each by {@code submit} of the draft of a new entry under its ULID key. */
    private static Created create(DocumentTransitionsTest.Fixture fixture, CyclicBarrier start) throws Exception {
        start.await(1, TimeUnit.MINUTES);
        List<DocumentKey> entries = new ArrayList<>();
        List<DocumentKey> documents = new ArrayList<>();
        var author = Author.of("p-author");
        for (int entry = 0; entry < ENTRIES_PER_THREAD; entry++) {
            documents.add(RunningNumbers.newKey(perDocument()));
            DocumentKey draft = RunningNumbers.newKey(numbered());
            List<TreeEdit> edits = List.of(new TreeEdit.Put(COLLECTION + "/" + draft.id() + ".yaml",
                    data("Entry " + draft.id(), "owner", null)));
            DocumentTransitions.@Nullable Result result = null;
            for (int attempt = 0; attempt < TRIES; attempt++) {
                result = fixture.perform(author, MFA, draft, "submit", edits);
                if (result instanceof DocumentTransitions.Result.Performed) {
                    break;
                }
            }
            if (!(result instanceof DocumentTransitions.Result.Performed performed)) {
                throw new AssertionError("not created after " + TRIES + " tries: "
                        + (result == null ? "none" : result.outcome() + " " + result.detail()));
            }
            entries.add(performed.key());
        }
        return new Created(entries, documents);
    }

    @Test
    @DisplayName("GDR-FORMS-9: concurrent creators of one numbered kind receive distinct numbers without a gap")
    void concurrentCreatorsReceiveDistinctNumbers(@Remote LocalRemote remote) throws Exception {
        DocumentTransitionsTest.seedMain(remote);
        var definition = new DocumentTransitions.Definition(numbered(), schema(), Workflow.defaultWorkflow(), null);
        List<DocumentTransitionsTest.Fixture> fixtures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        try {
            for (int thread = 0; thread < THREADS; thread++) {
                fixtures.add(DocumentTransitionsTest.open(Files.createDirectories(temp.resolve("writer-" + thread)),
                        remote, definition, new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE)));
            }
            var start = new CyclicBarrier(THREADS);
            List<Future<Created>> running = new ArrayList<>();
            for (DocumentTransitionsTest.Fixture fixture : fixtures) {
                running.add(executor.submit(() -> create(fixture, start)));
            }
            List<DocumentKey> entries = new ArrayList<>();
            List<DocumentKey> documents = new ArrayList<>();
            for (Future<Created> created : running) {
                Created done = created.get(5, TimeUnit.MINUTES);
                entries.addAll(done.entries());
                documents.addAll(done.documents());
            }

            int total = THREADS * ENTRIES_PER_THREAD;
            var collection = DocumentTransitionsTest.collection(numbered());
            var numbering = Objects.requireNonNull(numbered().numbering());
            Set<String> expected = IntStream.rangeClosed(1, total).mapToObj(counter -> Objects.requireNonNull(
                    RunningNumbers.number(numbering, counter).value())).collect(Collectors.toCollection(TreeSet::new));
            var onRemote = files(remote.repository(), StoreReferences.target(new DocumentKey(collection, "R-001")));
            Set<String> entryFiles = onRemote.keySet().stream()
                    .filter(path -> path.startsWith(COLLECTION + "/") && path.endsWith(".yaml"))
                    .map(path -> path.substring(COLLECTION.length() + 1, path.length() - ".yaml".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> numbers = entries.stream().map(DocumentKey::id).collect(Collectors.toCollection(TreeSet::new));
            assertAll(
                    () -> assertEquals(total, entries.size(), "every creation performed"),
                    () -> assertEquals(total, new HashSet<>(entries).size(), "no number given twice"),
                    () -> assertEquals(expected, numbers, "the numbers run from R-001 without a gap"),
                    () -> assertEquals(expected, entryFiles, "the collection branch holds exactly these entries"),
                    () -> assertEquals(total + "\n", onRemote.get(COLLECTION + "/" + DocumentLayout.COUNTER_FILE),
                            "the counter equals the number of entries"),
                    () -> assertTrue(entries.stream().allMatch(key -> RunningNumbers.isAssigned(numbered(), key)),
                            "every key carries its running number"),
                    () -> assertTrue(documents.stream().allMatch(key -> Ulid.isValid(key.id())),
                            "every per-document key is a ULID"),
                    () -> assertEquals(documents.size(), documents.stream().map(DocumentKey::id).distinct().count(),
                            "no ULID drawn twice"));
        } finally {
            executor.shutdownNow();
            fixtures.forEach(DocumentTransitionsTest.Fixture::close);
        }
    }
}
