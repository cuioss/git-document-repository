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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Temporary directories")
class TemporaryDirectoriesTest {

    @Test
    @DisplayName("Should create the directory with owner-only permissions on a POSIX file system")
    void shouldCreateOwnerOnlyDirectoryOnPosix() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "The default file system supports the posix file attribute view");

        var directory = TemporaryDirectories.create();
        try {
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory),
                    "Only the owner may read, write and enter the created directory");
        } finally {
            TemporaryDirectories.delete(directory);
        }
        assertFalse(Files.exists(directory), "The created directory is deleted again");
    }
}
