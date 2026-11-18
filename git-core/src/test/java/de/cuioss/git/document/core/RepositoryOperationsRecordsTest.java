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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;


import de.cuioss.git.document.core.RepositoryOperations.CloneInput;
import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.git.document.core.RepositoryOperations.Identity;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.core.RepositoryOperations.RefUpdate;
import de.cuioss.git.document.core.RepositoryOperations.RepositoryInfo;
import de.cuioss.git.document.testing.GitGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The builders of the value records of {@link RepositoryOperations} build the value the canonical constructor builds,
 * the records copy their lists, and the constructors refuse a missing required component. Every value is generated;
 * the component names the refusal tests assert are contract vocabulary rather than test data.
 */
@EnableGeneratorController
@DisplayName("RepositoryOperations value records")
class RepositoryOperationsRecordsTest {

    private static String text() {
        return Generators.letterStrings(1, 20).next();
    }

    private static Path path() {
        return Path.of(text());
    }

    private static Instant instant() {
        return Instant.ofEpochSecond(Generators.longs(0L, 1_900_000_000L).next());
    }

    private static Identity identity() {
        return new Identity(text(), text());
    }

    private static CredentialsProvider credentials() {
        var credential = GitGenerators.credentials().next();
        return new UsernamePasswordCredentialsProvider(credential.username(), credential.token());
    }

    @Test
    @DisplayName("the repository info builder builds the constructor value")
    void repositoryInfo() {
        var worktreeRoot = path();
        var gitDir = path();
        var commonDir = path();
        var head = text();
        var branch = text();
        var linked = Generators.booleans().next();

        var built = RepositoryInfo.builder().worktreeRoot(worktreeRoot).gitDir(gitDir).commonDir(commonDir).head(head)
                .branch(branch).linked(linked).build();

        assertEquals(new RepositoryInfo(worktreeRoot, gitDir, commonDir, head, branch, linked), built,
                "the builder builds the same repository facts");
    }

    @Test
    @DisplayName("the commit info builder builds the constructor value and copies the parents")
    void commitInfo() {
        var id = text();
        var parents = new ArrayList<>(List.of(text()));
        var author = identity();
        var authoredAt = instant();
        var committer = identity();
        var committedAt = instant();
        var message = text();

        var built = CommitInfo.builder().id(id).parents(parents).author(author).authoredAt(authoredAt)
                .committer(committer).committedAt(committedAt).message(message).build();
        var constructed = new CommitInfo(id, parents, author, authoredAt, committer, committedAt, message);
        parents.add(text());

        assertAll(
                () -> assertEquals(constructed, built, "the builder builds the same commit facts"),
                () -> assertEquals(1, built.parents().size(), "a later change of the list does not reach the record"));
    }

    @Test
    @DisplayName("the fetch input builder builds the constructor value")
    void fetchInput() {
        var root = path();
        var remote = text();
        var refSpecs = List.of(text());
        var credentials = credentials();

        var built = FetchInput.builder().root(root).remote(remote).refSpecs(refSpecs).credentials(credentials).build();

        assertEquals(new FetchInput(root, remote, refSpecs, credentials), built, "the builder builds the same input");
    }

    @Test
    @DisplayName("the push input builder builds the constructor value")
    void pushInput() {
        var root = path();
        var remote = text();
        var localRef = text();
        var remoteRef = text();
        var expectedRemoteHead = text();
        var credentials = credentials();

        var built = PushInput.builder().root(root).remote(remote).localRef(localRef).remoteRef(remoteRef)
                .expectedRemoteHead(expectedRemoteHead).credentials(credentials).build();

        assertEquals(new PushInput(root, remote, localRef, remoteRef, expectedRemoteHead, credentials), built,
                "the builder builds the same input");
    }

    @Test
    @DisplayName("the clone input builder builds the constructor value and renders no URL")
    void cloneInput() {
        var gitDir = path();
        var url = "https://" + text() + "@" + text() + ".example.invalid/" + text() + ".git";
        var pushRefSpecs = List.of(text());
        var credentials = credentials();

        var built = CloneInput.builder().gitDir(gitDir).url(url).pushRefSpecs(pushRefSpecs).credentials(credentials)
                .build();

        assertAll(
                () -> assertEquals(new CloneInput(gitDir, url, pushRefSpecs, credentials), built,
                        "the builder builds the same input"),
                () -> assertFalse(built.toString().contains(url), "the rendering carries no URL"));
    }

    @Test
    @DisplayName("the ref update builder builds the constructor value")
    void refUpdate() {
        var ref = text();
        var oldId = text();
        var newId = text();
        var status = text();

        var built = RefUpdate.builder().ref(ref).oldId(oldId).newId(newId).status(status).build();

        assertEquals(new RefUpdate(ref, oldId, newId, status), built, "the builder builds the same ref update");
    }

    @Test
    @DisplayName("a missing required component is refused by name")
    void refusesMissingComponents() {
        var fetch = FetchInput.builder().remote(text()).refSpecs(List.of());
        var push = PushInput.builder().root(path()).remote(text()).remoteRef(text());
        var clone = CloneInput.builder().gitDir(path()).url(text());
        var info = RepositoryInfo.builder().gitDir(path());

        assertAll(
                () -> assertEquals("root", assertThrows(NullPointerException.class, fetch::build,
                        "a fetch without repository is refused").getMessage(), "the refusal names the root"),
                () -> assertEquals("localRef", assertThrows(NullPointerException.class, push::build,
                        "a push without local reference is refused").getMessage(), "the refusal names the local ref"),
                () -> assertEquals("pushRefSpecs", assertThrows(NullPointerException.class, clone::build,
                                "a clone without push refspecs is refused").getMessage(),
                        "the refusal names the push refspecs"),
                () -> assertEquals("commonDir", assertThrows(NullPointerException.class, info::build,
                                "repository facts without common directory are refused").getMessage(),
                        "the refusal names the common directory"));
    }
}
