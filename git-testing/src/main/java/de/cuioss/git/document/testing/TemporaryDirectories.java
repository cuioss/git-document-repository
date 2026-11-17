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

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import org.jspecify.annotations.Nullable;

/**
 * Creates the temporary directories of the file-based fixtures and deletes them again.
 */
final class TemporaryDirectories {

    private static final String PREFIX = "git-testing-";

    private TemporaryDirectories() {
        // static helpers only
    }

    /**
     * Creates a new, empty directory in the default temporary-file directory.
     *
     * @return the created directory
     * @throws IOException if the directory cannot be created
     */
    static Path create() throws IOException {
        return Files.createTempDirectory(PREFIX);
    }

    /**
     * Deletes a directory and everything below it. A directory that does not exist is left as it is.
     * <p>
     * An entry that disappears while the tree is walked, for example a lock file JGit removes concurrently, counts
     * as already deleted: a {@link NoSuchFileException} for it is ignored. Every other failure propagates.
     *
     * @param directory the root of the tree to delete
     * @throws IOException if an entry of the tree cannot be deleted
     */
    static void delete(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) throws IOException {
                if (failure instanceof NoSuchFileException) {
                    return FileVisitResult.CONTINUE;
                }
                throw failure;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException failure) throws IOException {
                if (failure != null && !(failure instanceof NoSuchFileException)) {
                    throw failure;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
