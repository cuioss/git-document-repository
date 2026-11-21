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
package de.cuioss.git.document.store;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

/**
 * The memory footprint of the in-memory index (GDR-NFR-1): 500 documents of 90 fields each, generated into an
 * in-memory repository of {@code git-testing}, indexed with a contributor that keeps a field map per document, retain
 * less than 16 MB.
 * <p>
 * The size is measured with JOL ({@code org.openjdk.jol:jol-core} 0.17, the owner's decision of 2026-11-21):
 * {@code GraphLayout.parseInstance(Object...)} walks the object graph reachable from the index and
 * {@code GraphLayout.totalSize()} sums the sizes of its objects, so the bound is the size of what the index retains and
 * not a noisy heap delta. The API was checked against {@code jol-core/src/main/java/org/openjdk/jol/info/GraphLayout.java}
 * at the tag {@code 0.17} of {@code openjdk/jol}. The walked graph includes the repository the index reads, which it
 * references but does not own, so the measured size is an upper bound of the entries alone. JOL 0.17 cannot read the
 * field offsets of record classes through {@code Unsafe.objectFieldOffset} on Java 21 or 25, so the Surefire
 * configuration of {@code git-store} sets {@code jol.magicFieldOffset=true}.
 */
@EnableGeneratorController
@DisplayName("Index footprint (GDR-NFR-1)")
class IndexFootprintTest {

    /** The number of documents of the measured repository. */
    private static final int DOCUMENTS = 500;

    /** The number of fields of every document. */
    private static final int FIELDS = 90;

    /** The bound of GDR-NFR-1: 16 MB. */
    private static final long BOUND = 16L * 1024 * 1024;

    /** A contributor that keeps the fields of a document, {@code <name>: <value>} per line, as a map. */
    private static final IndexContributor<Map<String, String>> FIELD_MAP = document -> {
        Map<String, String> fields = new HashMap<>();
        for (byte[] content : document.files().values()) {
            for (String line : new String(content, StandardCharsets.UTF_8).split("\n")) {
                int colon = line.indexOf(": ");
                if (colon > 0) {
                    fields.put(line.substring(0, colon), line.substring(colon + 2));
                }
            }
        }
        return Map.copyOf(fields);
    };

    /** Writes one commit of every document onto {@code main}. */
    private static void writeDocuments(Repository repository, DocumentCollection collection) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (int document = 0; document < DOCUMENTS; document++) {
                var text = new StringBuilder();
                for (int field = 0; field < FIELDS; field++) {
                    text.append("field-").append(field).append(": ").append(Generators.letterStrings(5, 20).next())
                            .append('\n');
                }
                var entry = new DirCacheEntry(collection.name() + "/" + Ulid.next() + ".yaml");
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, text.toString().getBytes(StandardCharsets.UTF_8)));
                builder.add(entry);
            }
            builder.finish();
            var commit = new CommitBuilder();
            var ident = new PersonIdent(Generators.letterStrings(8, 16).next(), "");
            commit.setTreeId(index.writeTree(inserter));
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(Generators.letterStrings(8, 16).next());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(StoreReferences.MAIN);
            update.setNewObjectId(id);
            assertEquals(RefUpdate.Result.NEW, update.update(), "main is written");
        }
    }

    @Test
    @DisplayName("GDR-NFR-1: the index of 500 documents of 90 fields retains less than 16 MB")
    void indexRetainsLessThanSixteenMegabytes(@InMemory Repository repository) throws Exception {
        var collection = DocumentCollection.of("c" + Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next(),
                Kind.PER_DOCUMENT).value();
        Objects.requireNonNull(collection, "a valid collection");
        writeDocuments(repository, collection);
        Map<DocumentCollection, IndexContributor<Map<String, String>>> contributors = new LinkedHashMap<>();
        contributors.put(collection, FIELD_MAP);

        var opened = StoreIndex.open("footprint", repository, new StoreEvents(), contributors);
        assertTrue(opened.isOk(), () -> "the index is built: " + opened.detail());
        try (var index = Objects.requireNonNull(opened.value())) {
            var entries = index.entries(StoreReferences.MAIN, collection);
            long retained = GraphLayout.parseInstance(index).totalSize();

            assertAll(
                    () -> assertEquals(DOCUMENTS, entries.size(), "every document is indexed"),
                    () -> assertTrue(entries.values().stream().allMatch(fields -> fields.size() == FIELDS),
                            "every document with all its fields"),
                    () -> assertTrue(retained < BOUND,
                            () -> "the index retains less than 16 MB, measured: " + retained + " bytes"));
        }
    }
}
