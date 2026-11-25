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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The read bound of every blob of the store ({@link ExplicitSave#readBlob}, GDR-STORE-3): the size is asked before the
 * blob is opened, so a blob above {@link ExplicitSave#BLOB_LIMIT} is refused before any of its content is loaded. A
 * reader that counts its {@code open} calls observes it (JGit 7.8.0 {@link ObjectReader.Filter}).
 */
@EnableGeneratorController
@DisplayName("Blob read bound (GDR-STORE-3)")
class ExplicitSaveBlobBoundTest {

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    /** A reader that delegates and counts its opens. */
    private static final class CountingReader extends ObjectReader.Filter {

        private final ObjectReader delegate;
        private final AtomicInteger opens = new AtomicInteger();

        CountingReader(ObjectReader delegate) {
            this.delegate = delegate;
        }

        @Override
        protected ObjectReader delegate() {
            return delegate;
        }

        @Override
        public ObjectLoader open(AnyObjectId objectId, int typeHint) throws IOException {
            opens.incrementAndGet();
            return super.open(objectId, typeHint);
        }
    }

    private static ObjectId insert(Repository repository, byte[] content) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId id = inserter.insert(Constants.OBJ_BLOB, content);
            inserter.flush();
            return id;
        }
    }

    @Test
    @DisplayName("GDR-STORE-3: a blob above the bound is refused by its size and never opened")
    void blobAboveTheBoundIsNeverOpened() throws Exception {
        try (Repository repository = new InMemoryRepository(new DfsRepositoryDescription(word()))) {
            ObjectId large = insert(repository, new byte[ExplicitSave.BLOB_LIMIT + 1]);

            try (var reader = new CountingReader(repository.newObjectReader())) {
                assertThrows(ExplicitSave.BlobTooLargeException.class, () -> ExplicitSave.readBlob(reader, large),
                        "the blob above the bound is refused");
                assertEquals(0, reader.opens.get(), "its content was never opened");
            }
        }
    }

    @Test
    @DisplayName("GDR-STORE-3: a blob within the bound is read unchanged")
    void blobWithinTheBoundIsRead() throws Exception {
        try (Repository repository = new InMemoryRepository(new DfsRepositoryDescription(word()))) {
            byte[] content = word().getBytes(StandardCharsets.UTF_8);
            ObjectId small = insert(repository, content);

            try (var reader = new CountingReader(repository.newObjectReader())) {
                byte[] read = ExplicitSave.readBlob(reader, small);

                assertAll(
                        () -> assertArrayEquals(content, read, "the content"),
                        () -> assertEquals(1, reader.opens.get(), "opened once"));
            }
        }
    }
}
