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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Local remote fixture")
@EnableGeneratorController
class LocalRemoteExtensionTest {

    static final String BRANCH = "refs/heads/main";

    /** Directories handed to the tests, asserted gone once the extension's per-test cleanup has run. */
    private static final List<Path> RECEIVED_DIRECTORIES = new CopyOnWriteArrayList<>();

    @AfterAll
    static void everyDirectoryIsDeleted() {
        assertFalse(RECEIVED_DIRECTORIES.isEmpty(), "The tests received at least one directory");
        assertAll("deleted directories", RECEIVED_DIRECTORIES.stream()
                .map(directory -> () -> assertFalse(Files.exists(directory),
                        "The directory of the remote is deleted after the test: " + directory)));
    }

    @Test
    @DisplayName("Should address the bare repository by a file: URL")
    void shouldAddressBareRepositoryByFileUrl(@Remote LocalRemote remote) {
        var directory = remote.repository().getDirectory().toPath();
        RECEIVED_DIRECTORIES.add(directory);

        assertAll("local remote",
                () -> assertEquals("file", remote.uri().getScheme(), "The URL has the scheme file"),
                () -> assertEquals(directory, Path.of(remote.uri().getPath()),
                        "The URL points at the directory of the bare repository"),
                () -> assertTrue(remote.repository().isBare(), "The remote repository is bare"));
    }

    @Test
    @DisplayName("Should serve a fetch into a local repository")
    void shouldServeFetch(@Remote LocalRemote remote, @LocalRepository Repository local)
            throws Exception {
        RECEIVED_DIRECTORIES.add(remote.repository().getDirectory().toPath());
        RECEIVED_DIRECTORIES.add(local.getDirectory().toPath());
        var commit = commitOnBranch(remote.repository());

        try (var git = Git.wrap(local)) {
            git.fetch().setRemote(remote.uri().toString()).setRefSpecs(new RefSpec(BRANCH + ":" + BRANCH)).call();
        }

        assertEquals(commit, local.resolve(BRANCH), "The fetch brought the remote's commit into the local repository");
    }

    /**
     * Writes a commit with one generated document to {@link #BRANCH} of a repository without a working tree.
     *
     * @param repository the repository to write to; the branch must not exist yet
     * @return the id of the commit
     * @throws IOException if the objects or the reference cannot be written
     */
    static ObjectId commitOnBranch(Repository repository) throws IOException {
        try (var inserter = repository.newObjectInserter()) {
            var blob = inserter.insert(Constants.OBJ_BLOB, Generators.letterStrings(1, 64).next().getBytes(UTF_8));
            var tree = new TreeFormatter();
            tree.append("document.txt", FileMode.REGULAR_FILE, blob);
            var treeId = inserter.insert(tree);
            var ident = new PersonIdent("Fixture", "fixture@example.invalid");
            var commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("fixture commit");
            var commitId = inserter.insert(commit);
            inserter.flush();

            var update = repository.updateRef(BRANCH);
            update.setNewObjectId(commitId);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            assertEquals(RefUpdate.Result.NEW, update.update(), "The branch is created");
            return commitId;
        }
    }
}
