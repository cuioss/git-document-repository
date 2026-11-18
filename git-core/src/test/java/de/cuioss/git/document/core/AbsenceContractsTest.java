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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;


import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.core.RepositoryOperations.RefUpdate;
import de.cuioss.git.document.core.RepositoryOperations.RepositoryInfo;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The absent case of the repository components: a {@code null} component is an absence the accessor returns
 * unchanged. Every value is generated.
 */
@EnableGeneratorController
@DisplayName("Absence contracts of the repository components")
class AbsenceContractsTest {

    private static String text() {
        return Generators.letterStrings(1, 20).next();
    }

    @Test
    @DisplayName("a failed result carries no value")
    void failedResultWithoutValue() {
        var result = GitResult.<String>of(GitOutcome.NOT_FOUND, text());

        assertNull(result.value(), "a failed result carries no value");
    }

    @Test
    @DisplayName("a local-only remote policy has no origin")
    void localOnlyRemotePolicyHasNoOrigin() {
        assertNull(RemotePolicy.localOnly().origin(), "a local-only policy has no origin");
    }

    @Test
    @DisplayName("an unborn, detached, bare repository has no head, no branch and no working tree")
    void repositoryInfoWithoutHeadBranchOrWorktree() {
        var info = new RepositoryInfo(null, Path.of(text()), Path.of(text()), null, null, false);

        assertAll(
                () -> assertNull(info.worktreeRoot(), "a bare repository has no working tree"),
                () -> assertNull(info.head(), "an unborn repository has no head"),
                () -> assertNull(info.branch(), "a detached repository has no branch"));
    }

    @Test
    @DisplayName("a created or deleted reference has an absent old or new id")
    void refUpdateWithoutOldOrNewId() {
        assertAll(
                () -> assertNull(new RefUpdate(text(), null, text(), text()).oldId(), "a created ref has no old id"),
                () -> assertNull(new RefUpdate(text(), text(), null, text()).newId(), "a deleted ref has no new id"));
    }

    @Test
    @DisplayName("a plain push has no lease, and a transport call without credentials has none")
    void transportInputsWithoutLeaseOrCredentials() {
        var push = new PushInput(Path.of(text()), text(), text(), text(), null, null);
        var fetch = new FetchInput(Path.of(text()), text(), List.of(), null);

        assertAll(
                () -> assertNull(push.expectedRemoteHead(), "a plain push has no lease"),
                () -> assertNull(push.credentials(), "a push without credentials has none"),
                () -> assertNull(fetch.credentials(), "a fetch without credentials has none"));
    }
}
