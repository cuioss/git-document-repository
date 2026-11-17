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
package de.cuioss.git.document.testing;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

@DisplayName("In-memory repository fixture")
@EnableGeneratorController
class InMemoryRepositoryExtensionTest {

    /** Objects inserted by earlier repetitions, which a fresh repository must not hold. */
    private static final List<ObjectId> EARLIER_OBJECTS = new CopyOnWriteArrayList<>();

    private long directoriesCreatedBefore;

    @BeforeEach
    void captureTemporaryDirectories() {
        // runs before the test method's parameters are resolved
        directoriesCreatedBefore = TemporaryDirectories.createdCount();
    }

    @Test
    @DisplayName("Should provide a Repository that stores and reads back an object")
    void shouldStoreAndReadBackObject(@InMemory Repository repository) throws Exception {
        var content = Generators.letterStrings(1, 64).next().getBytes(UTF_8);

        ObjectId id;
        try (var inserter = repository.newObjectInserter()) {
            id = inserter.insert(Constants.OBJ_BLOB, content);
            inserter.flush();
        }
        byte[] readBack;
        try (var reader = repository.newObjectReader()) {
            readBack = reader.open(id, Constants.OBJ_BLOB).getBytes();
        }

        assertArrayEquals(content, readBack, "The object read back is the object inserted");
    }

    @RepeatedTest(3)
    @DisplayName("Should provide a fresh repository per test")
    void shouldProvideFreshRepository(@InMemory Repository repository) throws Exception {
        try (var reader = repository.newObjectReader()) {
            for (ObjectId earlier : EARLIER_OBJECTS) {
                assertFalse(reader.has(earlier), "A fresh repository holds no object of an earlier test");
            }
        }
        try (var inserter = repository.newObjectInserter()) {
            EARLIER_OBJECTS.add(inserter.insert(Constants.OBJ_BLOB,
                    Generators.letterStrings(8, 64).next().getBytes(UTF_8)));
            inserter.flush();
        }

        assertEquals(List.of(), repository.getRefDatabase().getRefs(), "A fresh repository has no references");
    }

    @Test
    @DisplayName("Should create no temporary directory")
    void shouldCreateNoDirectory(@InMemory Repository repository) {
        assertAll("no directory",
                () -> assertNull(repository.getDirectory(), "The in-memory repository has no directory"),
                () -> assertEquals(directoriesCreatedBefore, TemporaryDirectories.createdCount(),
                        "Resolving the parameter created no temporary directory"));
    }
}
