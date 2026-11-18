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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Optional;


import de.cuioss.git.document.core.GitIdentity.Resolution;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The identity rules of commits and references (GDR-CORE-11): full ids are recognised, abbreviations are produced but
 * never resolved as an identity, branch, tag and remote-branch names resolve, and a name that resolves as two
 * references, or as a reference and a full id, is ambiguous with its candidates listed.
 */
@EnableGeneratorController
@DisplayName("GitIdentity (GDR-CORE-11)")
class GitIdentityTest {

    /** The length of an abbreviated commit id used below: a boundary of the identity rule. */
    private static final int ABBREVIATED = 8;

    /** The name of the remote whose tracking branch is resolved: part of git's remote-ref layout. */
    private static final String ORIGIN = "origin";

    // names from letters outside the hex digits, so no name reads as an abbreviated commit id
    private final TypedGenerator<String> names =
            Generators.uniqueValues(Generators.strings("ghijklmnopqrstuvwxyz", 4, 12));

    @Test
    @DisplayName("GDR-CORE-11: the reference spellings are the full ones (ref prefixes are the contract)")
    void refSpellings() {
        var branch = names.next();
        var tag = names.next();
        var remote = names.next();

        assertAll(
                () -> assertEquals("refs/heads/" + branch, GitIdentity.branchRef(branch),
                        "a short branch name gains the heads prefix"),
                () -> assertEquals("refs/heads/" + branch, GitIdentity.branchRef("refs/heads/" + branch),
                        "a full branch ref is kept as it is"),
                () -> assertEquals("refs/tags/" + tag, GitIdentity.tagRef(tag), "a short tag name gains the tags prefix"),
                () -> assertEquals("refs/tags/" + tag, GitIdentity.tagRef("refs/tags/" + tag),
                        "a full tag ref is kept as it is"),
                () -> assertEquals("refs/remotes/" + remote + "/" + branch, GitIdentity.remoteBranchRef(remote, branch),
                        "a remote branch lives below refs/remotes"));
    }

    @Test
    @DisplayName("GDR-CORE-11: a full commit id is recognised in either case, an abbreviation is no full id")
    void commitIdSyntax(@InMemory Repository repository) throws Exception {
        var id = commit(repository).name();
        var abbreviated = id.substring(0, ABBREVIATED);

        assertAll(
                () -> assertTrue(GitIdentity.isFullCommitId(id), "a full lower-case id is a full id"),
                () -> assertTrue(GitIdentity.isFullCommitId(id.toUpperCase(Locale.ROOT)),
                        "a full upper-case id is a full id"),
                () -> assertFalse(GitIdentity.isFullCommitId(abbreviated), "an abbreviated id is no full id"),
                () -> assertTrue(GitIdentity.isAbbreviatedCommitId(abbreviated),
                        "an abbreviated id is recognised as such"),
                () -> assertTrue(GitIdentity.isAbbreviatedCommitId(abbreviated.toUpperCase(Locale.ROOT)),
                        "an upper-case abbreviated id is recognised"),
                () -> assertFalse(GitIdentity.isAbbreviatedCommitId(id), "a full id is no abbreviation"),
                () -> assertFalse(GitIdentity.isFullCommitId(null), "no id is no full commit id"),
                () -> assertFalse(GitIdentity.isAbbreviatedCommitId(null), "no id is no abbreviated commit id"));
    }

    @Test
    @DisplayName("GDR-CORE-11: a full commit id resolves as an identity and is its own candidate")
    void fullCommitId(@InMemory Repository repository) throws Exception {
        var id = commit(repository).name();

        var resolution = GitIdentity.resolution(repository, id);

        assertAll(
                () -> assertEquals(Resolution.FULL_COMMIT_ID, resolution, "a full id is an identity"),
                () -> assertFalse(GitIdentity.isAmbiguous(resolution), "a full id alone is not ambiguous"),
                () -> assertEquals(List.of(id), GitIdentity.candidates(repository, id), "the id is the one candidate"));
    }

    @Test
    @DisplayName("GDR-CORE-11: branch, tag and remote-branch names resolve as one reference each")
    void namesResolve(@InMemory Repository repository) throws Exception {
        var commit = commit(repository);
        var branch = names.next();
        var tag = names.next();
        var remoteBranch = names.next();
        createRef(repository, GitIdentity.branchRef(branch), commit);
        createRef(repository, GitIdentity.tagRef(tag), commit);
        createRef(repository, GitIdentity.remoteBranchRef(ORIGIN, remoteBranch), commit);
        var remoteName = ORIGIN + "/" + remoteBranch;

        assertAll(
                () -> assertEquals(Resolution.REF, GitIdentity.resolution(repository, branch),
                        "a branch name resolves as one reference"),
                () -> assertEquals(Resolution.REF, GitIdentity.resolution(repository, tag),
                        "a tag name resolves as one reference"),
                () -> assertEquals(Resolution.REF, GitIdentity.resolution(repository, remoteName),
                        "a remote-branch name resolves as one reference"),
                () -> assertEquals(List.of(GitIdentity.branchRef(branch)), GitIdentity.candidates(repository, branch),
                        "the branch's candidate is its full reference"),
                () -> assertEquals(List.of(GitIdentity.tagRef(tag)), GitIdentity.candidates(repository, tag),
                        "the tag's candidate is its full reference"),
                () -> assertEquals(List.of(GitIdentity.remoteBranchRef(ORIGIN, remoteBranch)),
                        GitIdentity.candidates(repository, remoteName),
                        "the remote branch's candidate is its full reference"));
    }

    @Test
    @DisplayName("GDR-CORE-11: an abbreviated commit id is classified as such, never as an identity")
    void abbreviatedIsNoIdentity(@InMemory Repository repository) throws Exception {
        var id = commit(repository).name();
        var abbreviated = id.substring(0, ABBREVIATED);

        var resolution = GitIdentity.resolution(repository, abbreviated);

        assertAll(
                () -> assertEquals(Resolution.ABBREVIATED_COMMIT_ID, resolution,
                        "a prefix of a commit id is an abbreviation"),
                () -> assertNotEquals(Resolution.FULL_COMMIT_ID, resolution, "an abbreviation is never an identity"),
                () -> assertTrue(GitIdentity.candidates(repository, abbreviated).isEmpty(),
                        "an abbreviation names no candidate"));
    }

    @Test
    @DisplayName("GDR-CORE-11: an abbreviation is produced as a unique prefix that resolves back to the one commit")
    void abbreviate(@InMemory Repository repository) throws Exception {
        var first = commit(repository);
        var second = commit(repository);

        var abbreviation = GitIdentity.abbreviate(repository, first);

        assertAll(
                () -> assertTrue(first.name().startsWith(abbreviation), "the abbreviation is a prefix of the id"),
                () -> assertTrue(GitIdentity.isAbbreviatedCommitId(abbreviation),
                        "the abbreviation is shorter than a full id"),
                () -> assertTrue(abbreviation.length() >= 7, "the abbreviation has at least seven digits"),
                () -> assertEquals(first, repository.resolve(abbreviation),
                        "the abbreviation resolves to exactly the one commit"),
                () -> assertFalse(second.name().startsWith(abbreviation),
                        "the abbreviation does not match the other commit"));
    }

    @Test
    @DisplayName("GDR-CORE-11: a branch named like a full commit id is ambiguous, with both candidates listed")
    void refAndCommitId(@InMemory Repository repository) throws Exception {
        var commit = commit(repository);
        var id = commit.name();
        createRef(repository, GitIdentity.branchRef(id), commit);

        var resolution = GitIdentity.resolution(repository, id);

        assertAll(
                () -> assertEquals(Resolution.REF_AND_COMMIT_ID, resolution, "the name is a reference and an id"),
                () -> assertTrue(GitIdentity.isAmbiguous(resolution), "a reference and an id is ambiguous"),
                () -> assertEquals(List.of(GitIdentity.branchRef(id), id), GitIdentity.candidates(repository, id),
                        "the candidates are the branch and the id"));
    }

    @Test
    @DisplayName("GDR-CORE-11: a name of a branch and a tag is ambiguous, with both references listed")
    void twoRefs(@InMemory Repository repository) throws Exception {
        var commit = commit(repository);
        var duplicate = names.next();
        createRef(repository, GitIdentity.branchRef(duplicate), commit);
        createRef(repository, GitIdentity.tagRef(duplicate), commit);

        var resolution = GitIdentity.resolution(repository, duplicate);
        var candidates = GitIdentity.candidates(repository, duplicate);

        assertAll(
                () -> assertEquals(Resolution.TWO_REFS, resolution, "the name is two references"),
                () -> assertTrue(GitIdentity.isAmbiguous(resolution), "two references are ambiguous"),
                () -> assertEquals(2, candidates.size(), "both references are candidates"),
                () -> assertTrue(candidates.contains(GitIdentity.branchRef(duplicate)), "the branch is a candidate"),
                () -> assertTrue(candidates.contains(GitIdentity.tagRef(duplicate)), "the tag is a candidate"));
    }

    @Test
    @DisplayName("GDR-CORE-11: a name that is neither a reference nor an object is unknown and names no candidate")
    void unknown(@InMemory Repository repository) throws Exception {
        commit(repository);
        var name = names.next();

        var resolution = GitIdentity.resolution(repository, name);

        assertAll(
                () -> assertEquals(Resolution.UNKNOWN, resolution, "an unknown name is unknown"),
                () -> assertFalse(GitIdentity.isAmbiguous(resolution), "an unknown name is not ambiguous"),
                () -> assertTrue(GitIdentity.candidates(repository, name).isEmpty(), "an unknown name has no candidate"));
    }

    @Test
    @DisplayName("GDR-CORE-11: a full reference name and HEAD name one reference each and are supported identities")
    void fullRefNameAndHead(@InMemory Repository repository) throws Exception {
        var commit = commit(repository);
        var branch = GitIdentity.branchRef(names.next());
        createRef(repository, branch, commit);
        repository.updateRef(Constants.HEAD).link(branch);

        var byFullName = GitIdentity.lookup(repository, branch);
        var head = GitIdentity.lookup(repository, Constants.HEAD);

        assertAll(
                () -> assertEquals(Resolution.REF, byFullName.resolution(), "a full reference name is one reference"),
                () -> assertEquals(List.of(branch), byFullName.candidates(), "the full name is its own candidate"),
                () -> assertEquals(Optional.of(branch), byFullName.target(), "the full name is resolved by itself"),
                () -> assertEquals(Resolution.REF, head.resolution(), "HEAD is one reference"),
                () -> assertTrue(head.isSupported(), "HEAD is a supported identity"));
    }

    @Test
    @DisplayName("GDR-CORE-11: one lookup returns the resolution together with its candidates")
    void lookupCarriesCandidates(@InMemory Repository repository) throws Exception {
        var commit = commit(repository);
        var duplicate = names.next();
        createRef(repository, GitIdentity.branchRef(duplicate), commit);
        createRef(repository, GitIdentity.tagRef(duplicate), commit);
        var id = commit.name();

        var twoRefs = GitIdentity.lookup(repository, duplicate);
        var fullId = GitIdentity.lookup(repository, id);

        assertAll(
                () -> assertEquals(Resolution.TWO_REFS, twoRefs.resolution(), "the name is two references"),
                () -> assertEquals(List.of(GitIdentity.branchRef(duplicate), GitIdentity.tagRef(duplicate)),
                        twoRefs.candidates(), "both references come with the same lookup"),
                () -> assertFalse(twoRefs.isSupported(), "an ambiguous name is no supported identity"),
                () -> assertTrue(twoRefs.target().isEmpty(), "an ambiguous name has nothing to resolve"),
                () -> assertEquals(Resolution.FULL_COMMIT_ID, fullId.resolution(), "a full id is an identity"),
                () -> assertEquals(Optional.of(id), fullId.target(), "a full id is resolved by itself"));
    }

    @Test
    @DisplayName("GDR-CORE-11: a short prefix and an expression on an abbreviation are no supported identity")
    void unsupportedForms(@InMemory Repository repository) throws Exception {
        var id = commit(repository).name();

        var abbreviated = GitIdentity.lookup(repository, id.substring(0, ABBREVIATED));
        var shortPrefix = GitIdentity.lookup(repository, id.substring(0, 3));
        var expression = GitIdentity.lookup(repository, id.substring(0, ABBREVIATED) + "~0");

        assertAll(
                () -> assertFalse(abbreviated.isSupported(), "an abbreviated id is never resolved"),
                () -> assertEquals(Resolution.UNKNOWN, shortPrefix.resolution(), "a three-digit prefix is unknown"),
                () -> assertFalse(shortPrefix.isSupported(), "a three-digit prefix is never resolved"),
                () -> assertEquals(Resolution.UNKNOWN, expression.resolution(), "an expression is unknown"),
                () -> assertFalse(expression.isSupported(), "an expression on an abbreviation is never resolved"),
                () -> assertTrue(expression.target().isEmpty(), "an expression has nothing to resolve"));
    }

    /**
     * Writes a commit with one generated file through the repository's object inserter, without a working tree.
     *
     * @param repository the repository receiving the commit
     * @return the commit id
     * @throws IOException if the objects cannot be inserted
     */
    private ObjectId commit(Repository repository) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var content = Generators.letterStrings(1, 40).next().getBytes(StandardCharsets.UTF_8);
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content);
            var tree = new TreeFormatter();
            tree.append(names.next(), FileMode.REGULAR_FILE, blob);
            ObjectId treeId = inserter.insert(tree);
            var ident = new PersonIdent(names.next(), names.next() + "@example.invalid");
            var commit = new CommitBuilder();
            commit.setTreeId(treeId);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(Generators.letterStrings(3, 30).next());
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            return commitId;
        }
    }

    private static void createRef(Repository repository, String refName, ObjectId target) throws IOException {
        RefUpdate update = repository.updateRef(refName);
        update.setNewObjectId(target);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        var result = update.update();
        assertEquals(RefUpdate.Result.NEW, result, "the fixture reference " + refName + " is created");
    }
}
