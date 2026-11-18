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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.cuioss.test.generator.junit.EnableGeneratorController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Fixtures combined in one test")
@EnableGeneratorController
class CombinedFixturesTest {

    /** Directories handed to the test, asserted gone once the extensions' per-test cleanup has run. */
    private static final List<Path> RECEIVED_DIRECTORIES = new CopyOnWriteArrayList<>();

    @AfterAll
    static void everyDirectoryIsDeleted() {
        assertFalse(RECEIVED_DIRECTORIES.isEmpty(), "The test received at least one directory");
        assertAll("deleted directories", RECEIVED_DIRECTORIES.stream()
                .map(directory -> () -> assertFalse(Files.exists(directory),
                        "The directory is deleted after the test: " + directory)));
    }

    @Test
    @DisplayName("Should resolve all three fixtures in one test and push from local to remote")
    void shouldCombineFixtures(@LocalRepository Repository local, @InMemory Repository inMemory,
            @Remote LocalRemote remote) throws Exception {
        assertNotNull(local.getDirectory(), "The local repository has a directory");
        RECEIVED_DIRECTORIES.add(local.getDirectory().toPath());
        RECEIVED_DIRECTORIES.add(remote.repository().getDirectory().toPath());

        assertAll("three distinct repositories",
                () -> assertNotSame(local, inMemory, "The local and the in-memory repository are distinct"),
                () -> assertNotSame(local, remote.repository(), "The local repository and the remote are distinct"),
                () -> assertNotSame(inMemory, remote.repository(),
                        "The in-memory repository and the remote are distinct"),
                () -> assertNull(inMemory.getDirectory(), "The in-memory repository has no directory"));

        var commit = LocalRemoteExtensionTest.commitOnBranch(local);
        Iterable<PushResult> results;
        try (var git = Git.wrap(local)) {
            results = git.push().setRemote(remote.uri().toString())
                    .setRefSpecs(new RefSpec(LocalRemoteExtensionTest.BRANCH + ":" + LocalRemoteExtensionTest.BRANCH))
                    .call();
        }

        for (PushResult result : results) {
            assertEquals(RemoteRefUpdate.Status.OK,
                    result.getRemoteUpdate(LocalRemoteExtensionTest.BRANCH).getStatus(), "The push is accepted");
        }
        assertEquals(commit, remote.repository().resolve(LocalRemoteExtensionTest.BRANCH),
                "The remote holds the pushed commit");
    }
}
