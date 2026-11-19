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
package de.cuioss.git.document.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Objects;


import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The absent components of the repository results, each produced by a real operation on the {@code git-testing}
 * fixtures and never passed in by the test: a bare repository has no working-tree root, an unborn branch no head, a
 * detached head no branch, a push that creates the remote reference no old id, a failed operation no value, and a
 * local-only policy no origin.
 */
@EnableGeneratorController
@DisplayName("Absence contracts of the repository results")
class AbsenceContractsTest {

    /** The remote name of the push: git's own default. */
    private static final String ORIGIN = Constants.DEFAULT_REMOTE_NAME;

    @TempDir
    Path temp;

    private JGitRepositoryOperations operations;

    @BeforeEach
    void createOperations() {
        operations = new JGitRepositoryOperations(RemotePolicy.localOnly());
    }

    private static Path dir(Repository repository) {
        return repository.getDirectory().toPath();
    }

    @Test
    @DisplayName("opening a bare repository yields no working-tree root")
    void bareRepositoryHasNoWorktreeRoot(@LocalRepository Repository bare) throws Exception {
        TestCommits.commitOn(bare, GitIdentity.branchRef(TestCommits.name()));

        var info = Objects.requireNonNull(operations.open(dir(bare)).value(), "the bare repository is opened");

        assertNull(info.worktreeRoot(), "a bare repository has no working tree");
    }

    @Test
    @DisplayName("init yields an unborn branch, which has no head")
    void unbornBranchHasNoHead() {
        var branch = TestCommits.name();

        var info = Objects.requireNonNull(operations.init(temp.resolve(TestCommits.name()), branch).value(),
                "the repository is created");

        assertAll(
                () -> assertEquals(branch, info.branch(), "HEAD points to the initial branch"),
                () -> assertNull(info.head(), "the unborn branch has no head"));
    }

    @Test
    @DisplayName("a detached HEAD yields no branch")
    void detachedHeadHasNoBranch(@LocalRepository Repository repository) throws Exception {
        var commit = TestCommits.commitOn(repository, GitIdentity.branchRef(TestCommits.name()));
        RefUpdate detach = repository.updateRef(Constants.HEAD, true);
        detach.setNewObjectId(commit);
        detach.setForceUpdate(true);
        detach.update();

        var info = Objects.requireNonNull(operations.open(dir(repository)).value(), "the repository is opened");

        assertAll(
                () -> assertEquals(commit.name(), info.head(), "HEAD is the commit"),
                () -> assertNull(info.branch(), "a detached HEAD has no branch"));
    }

    @Test
    @DisplayName("a push that creates the remote reference yields no old id")
    void createdReferenceHasNoOldId(@Remote LocalRemote remote, @LocalRepository Repository client)
            throws Exception {
        TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
        var ref = GitIdentity.branchRef(TestCommits.name());
        var commit = TestCommits.commitOn(client, ref);

        var result = operations.push(PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(ref).remoteRef(ref)
                .build());

        var update = Objects.requireNonNull(result.value(), "the push is accepted");
        assertAll(
                () -> assertEquals(commit.name(), update.newId(), "the new id is the pushed commit"),
                () -> assertNull(update.oldId(), "a created reference has no old id"));
    }

    @Test
    @DisplayName("a failed operation yields no value")
    void failedOperationHasNoValue(@LocalRepository Repository repository) {
        var result = operations.log(dir(repository), TestCommits.name(), 1);

        assertAll(
                () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "an unknown revision is not found"),
                () -> assertNull(result.value(), "a failed result carries no value"));
    }

    @Test
    @DisplayName("a local-only remote policy has no origin")
    void localOnlyRemotePolicyHasNoOrigin() {
        assertNull(RemotePolicy.localOnly().origin(), "a local-only policy has no origin");
    }
}
