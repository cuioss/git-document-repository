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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Temporary directories")
@EnableGeneratorController
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

    @Test
    @DisplayName("Should delete a tree of directories and files, a read-only file included")
    void shouldDeleteTreeWithReadOnlyFile() throws Exception {
        var directory = TemporaryDirectories.create();
        var nested = Files.createDirectories(directory.resolve("objects").resolve("pack"));
        var readOnly = Files.writeString(nested.resolve("pack.idx"), Generators.letterStrings(1, 32).next());
        assertTrue(readOnly.toFile().setWritable(false), "The file is made read-only");

        TemporaryDirectories.delete(directory);

        assertFalse(Files.exists(directory), "The tree is deleted together with its read-only file");
    }

    /**
     * A dangling symbolic link is deleted like any other entry: JGit's {@code FileUtils#delete} with
     * {@code SKIP_MISSING} returns early only for a path its existence check reports missing, and that check does not
     * follow links (JGit 7.8.0, {@code FileUtils#exists} with {@code NOFOLLOW_LINKS}). The test is skipped only where
     * the platform refuses to create a symbolic link.
     */
    @Test
    @DisplayName("Should delete a tree holding a dangling symbolic link")
    void shouldDeleteTreeWithDanglingSymbolicLink() throws Exception {
        var directory = TemporaryDirectories.create();
        var nested = Files.createDirectories(directory.resolve("refs").resolve("heads"));
        var link = nested.resolve(Generators.letterStrings(1, 16).next());
        try {
            Files.createSymbolicLink(link, directory.resolve("missing-target"));
        } catch (UnsupportedOperationException | FileSystemException refused) {
            TemporaryDirectories.delete(directory);
            abort("The platform refuses to create a symbolic link: " + refused);
        }
        assertAll("dangling link",
                () -> assertTrue(Files.isSymbolicLink(link), "The entry is a symbolic link"),
                () -> assertFalse(Files.exists(link), "The target of the link does not exist"));

        TemporaryDirectories.delete(directory);

        assertAll("deleted tree",
                () -> assertFalse(Files.exists(link, LinkOption.NOFOLLOW_LINKS), "The dangling link is deleted"),
                () -> assertFalse(Files.exists(directory, LinkOption.NOFOLLOW_LINKS),
                        "The tree holding the dangling link is deleted"));
    }

    @Test
    @DisplayName("Should leave a directory that does not exist as it is")
    void shouldIgnoreMissingDirectory() throws Exception {
        var directory = TemporaryDirectories.create();
        TemporaryDirectories.delete(directory);

        assertDoesNotThrow(() -> TemporaryDirectories.delete(directory),
                "Deleting a directory that does not exist is no failure");
        assertFalse(Files.exists(directory), "The missing directory stays missing");
    }

    @Test
    @DisplayName("Should propagate the failure to read an entry of the tree")
    void shouldPropagateUnreadableEntry() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "The default file system supports the posix file attribute view");
        var directory = TemporaryDirectories.create();
        var locked = Files.createDirectory(directory.resolve("locked"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(locked), "The user cannot bypass the permissions, as root could");

            assertThrows(AccessDeniedException.class, () -> TemporaryDirectories.delete(directory),
                    "A directory that cannot be opened is a failure, not a missing entry");
            assertTrue(Files.exists(locked), "The entry that could not be read is left in place");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
            TemporaryDirectories.delete(directory);
        }
    }

    @Test
    @DisplayName("Should delete a directory whose entries disappear while it is listed")
    void shouldDeleteDirectoryWhoseEntriesDisappear() throws Exception {
        var fileSystem = new FailingListingFileSystem(new NoSuchFileException("vanished-lock-file"));

        TemporaryDirectories.delete(fileSystem.directory());

        assertTrue(fileSystem.deleted(), "An entry that vanished counts as deleted and the directory is deleted");
    }

    @Test
    @DisplayName("Should propagate a failure to list a directory and keep the directory")
    void shouldPropagateListingFailure() {
        var failure = new IOException("The entries cannot be read");
        var fileSystem = new FailingListingFileSystem(failure);

        var thrown = assertThrows(IOException.class, () -> TemporaryDirectories.delete(fileSystem.directory()),
                "A failure to list the directory propagates");

        assertAll("listing failure",
                () -> assertSame(failure, thrown, "The listing failure itself propagates"),
                () -> assertFalse(fileSystem.deleted(), "The directory that could not be listed is kept"));
    }
}
