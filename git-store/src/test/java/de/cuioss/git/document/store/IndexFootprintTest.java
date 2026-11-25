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
import java.util.List;
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
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openjdk.jol.info.GraphLayout;

/**
 * The memory footprint of the in-memory index (GDR-NFR-1): 500 documents of 90 fields each, generated into an
 * in-memory repository of {@code git-testing}, indexed with a contributor that keeps a field map per document, retain
 * less than 16 MB.
 * <p>
 * The size is measured with JOL ({@code org.openjdk.jol:jol-core} 0.17, the owner's decision of 2026-11-21):
 * {@code GraphLayout.parseInstance(Object...)} walks the object graph reachable from its roots and
 * {@code GraphLayout.totalSize()} sums the sizes of its objects, so the bound is the size of what the index retains and
 * not a noisy heap delta. The API was checked against {@code jol-core/src/main/java/org/openjdk/jol/info/GraphLayout.java}
 * at the tag {@code 0.17} of {@code openjdk/jol}. The roots are the entry maps of the two indexed sources, {@code main}
 * and one working branch, so the walk covers the entry graph only: the repository the index reads, with its
 * {@code DfsBlockCache}, is shared with the writer and not retained by the entries. The figure is relative to the value
 * type of the contributor, here a field map per document. A lower bound, two strings per field, checks that JOL reached
 * the entries at all. JOL 0.17 cannot read the field offsets of record classes through
 * {@code Unsafe.objectFieldOffset} on Java 21 or 25, so the Surefire configuration of {@code git-store} sets
 * {@code jol.magicFieldOffset=true}.
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

    /**
     * The smallest size the entry graph can have: two strings, a field name and a value, per field of every document,
     * each at least an object header with its array reference and a byte array of its own (24 bytes counted).
     */
    private static final long LOWER_BOUND = 2L * DOCUMENTS * FIELDS * 24;

    /** The content of one document: {@value #FIELDS} fields, {@code <name>: <value>} per line. */
    private static byte[] document() {
        var text = new StringBuilder();
        for (int field = 0; field < FIELDS; field++) {
            text.append("field-").append(field).append(": ").append(Generators.letterStrings(5, 20).next())
                    .append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Writes one commit of every document onto {@code main}, then a working branch of one more document on top of it.
     *
     * @return the key of the document of the working branch
     */
    private static DocumentKey writeDocuments(Repository repository, DocumentCollection collection)
            throws IOException {
        var working = Objects.requireNonNull(DocumentKey.of(collection, Ulid.next()).value(), "a valid key");
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (int document = 0; document < DOCUMENTS; document++) {
                var entry = new DirCacheEntry(collection.name() + "/" + Ulid.next() + ".yaml");
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, document()));
                builder.add(entry);
            }
            builder.finish();
            var main = write(repository, inserter, index.writeTree(inserter), null, StoreReferences.MAIN);
            DirCache withWorking = DirCache.newInCore();
            DirCacheBuilder workingBuilder = withWorking.builder();
            for (int position = 0; position < index.getEntryCount(); position++) {
                workingBuilder.add(index.getEntry(position));
            }
            var entry = new DirCacheEntry(collection.name() + "/" + working.id() + ".yaml");
            entry.setFileMode(FileMode.REGULAR_FILE);
            entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, document()));
            workingBuilder.add(entry);
            workingBuilder.finish();
            write(repository, inserter, withWorking.writeTree(inserter), main, StoreReferences.target(working));
        }
        return working;
    }

    /** Writes a commit of a tree onto a new reference. */
    private static ObjectId write(Repository repository, ObjectInserter inserter, ObjectId tree,
            @Nullable ObjectId parent, String ref) throws IOException {
        var commit = new CommitBuilder();
        var ident = new PersonIdent(Generators.letterStrings(8, 16).next(), "");
        commit.setTreeId(tree);
        if (parent != null) {
            commit.setParentId(parent);
        }
        commit.setAuthor(ident);
        commit.setCommitter(ident);
        commit.setMessage(Generators.letterStrings(8, 16).next());
        var id = inserter.insert(commit);
        inserter.flush();
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(id);
        assertEquals(RefUpdate.Result.NEW, update.update(), "the reference is written");
        return id;
    }

    @Test
    @DisplayName("GDR-NFR-1: the index of 500 documents of 90 fields retains less than 16 MB")
    void indexRetainsLessThanSixteenMegabytes(@InMemory Repository repository) throws Exception {
        var collection = DocumentCollection.of("c" + Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16).next(),
                Kind.PER_DOCUMENT).value();
        Objects.requireNonNull(collection, "a valid collection");
        var working = writeDocuments(repository, collection);
        Map<DocumentCollection, IndexContributor<Map<String, String>>> contributors = new LinkedHashMap<>();
        contributors.put(collection, FIELD_MAP);

        var opened = StoreIndex.open("footprint", repository, new StoreEvents(updates -> {
            // no listener: the measured index is opened without a writer
        }), contributors);
        assertTrue(opened.isOk(), () -> "the index is built: " + opened.detail());
        try (var index = Objects.requireNonNull(opened.value())) {
            var entries = index.entries(StoreReferences.MAIN, collection);
            var workingEntries = index.entries(StoreReferences.target(working), collection);
            long retained = GraphLayout.parseInstance(entries, workingEntries).totalSize();

            assertAll(
                    () -> assertEquals(DOCUMENTS, entries.size(), "every document of main is indexed"),
                    () -> assertEquals(List.of(working), List.copyOf(workingEntries.keySet()),
                            "the working branch indexes its one document"),
                    () -> assertTrue(entries.values().stream().allMatch(fields -> fields.size() == FIELDS),
                            "every document with all its fields"),
                    () -> assertTrue(retained > LOWER_BOUND,
                            () -> "JOL reached the entries, measured: " + retained + " bytes"),
                    () -> assertTrue(retained < BOUND,
                            () -> "the entries retain less than 16 MB, measured: " + retained + " bytes"));
        }
    }
}
