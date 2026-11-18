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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;


import de.cuioss.git.document.core.RepositoryOperations.CloneInput;
import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.core.RepositoryOperations.RefUpdate;
import de.cuioss.git.document.testing.GitCredential;
import de.cuioss.git.document.testing.GitGenerators;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * The repository operations on JGit: open, init, clone-when-missing, log, fetch and push against the
 * {@code git-testing} fixtures, without network access. Names, contents and credentials are generated; reference
 * prefixes, hook names and URL schemes are git's own vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("JGitRepositoryOperations")
class JGitRepositoryOperationsTest {

    /** The remote name the operations and the fixtures use: git's own default. */
    private static final String ORIGIN = "origin";

    /** An HTTPS host outside every policy of these tests; never contacted. */
    private static final String FOREIGN_HOST = "example.invalid";

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

    private static String branchRef() {
        return GitIdentity.branchRef(TestCommits.name());
    }

    private static ObjectId refId(Repository repository, String ref) throws IOException {
        var found = repository.exactRef(ref);
        assertNotNull(found, "the reference " + ref + " exists");
        return found.getObjectId();
    }

    /** The branch reference {@code HEAD} of a fixture repository points to, whatever the default branch name is. */
    private static String headTarget(Repository repository) throws IOException {
        var head = repository.exactRef(Constants.HEAD);
        assertNotNull(head, "the fixture repository has a HEAD");
        return head.getTarget().getName();
    }

    private static Repository openBare(Path gitDir) throws IOException {
        return new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build();
    }

    @Nested
    @DisplayName("open, init and log")
    class OpenInitLog {

        @Test
        @DisplayName("init creates a repository with a working tree on an unborn branch, and open reports its facts")
        void initAndOpen() {
            var branch = TestCommits.name();
            var root = temp.resolve(TestCommits.name());

            var created = operations.init(root, branch);
            var opened = operations.open(root);

            var info = Objects.requireNonNull(opened.value(), "open yields the repository facts");
            assertAll(
                    () -> assertEquals(GitOutcome.OK, created.outcome(), "init succeeds"),
                    () -> assertEquals(created.value(), info, "open reports what init reported"),
                    () -> assertEquals(root.toRealPath(), info.worktreeRoot(), "the working-tree root is the real path"),
                    () -> assertTrue(Files.isDirectory(root.resolve(Constants.DOT_GIT)), "the git directory is created"),
                    () -> assertEquals(branch, info.branch(), "HEAD points to the initial branch"),
                    () -> assertNull(info.head(), "the initial branch is unborn"),
                    () -> assertFalse(info.linked(), "the repository is no linked working tree"),
                    () -> assertEquals(info.gitDir(), info.commonDir(), "the git directory is the common one"));
        }

        @Test
        @DisplayName("open accepts the git directory of a bare repository, which has no working tree")
        void opensBare(@LocalRepository Repository bare) throws Exception {
            var headRef = headTarget(bare);
            var commit = TestCommits.commitOn(bare, headRef);

            var info = Objects.requireNonNull(operations.open(dir(bare)).value(), "a bare repository is opened");

            assertAll(
                    () -> assertNull(info.worktreeRoot(), "a bare repository has no working tree"),
                    () -> assertEquals(dir(bare).toRealPath(), info.gitDir(), "the git directory is the real path"),
                    () -> assertEquals(commit.name(), info.head(), "the head commit is reported"),
                    () -> assertEquals(Repository.shortenRefName(headRef), info.branch(),
                            "the branch HEAD points to is reported"));
        }

        @Test
        @DisplayName("a directory outside any repository is not a repository")
        void notARepository() throws Exception {
            Path plain = Files.createDirectories(temp.resolve(TestCommits.name()));

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_A_REPOSITORY, operations.open(plain).outcome(),
                            "open refuses a plain directory"),
                    () -> assertEquals(GitOutcome.NOT_A_REPOSITORY,
                            operations.log(plain, Constants.HEAD, 1).outcome(), "log refuses a plain directory"),
                    () -> assertEquals(GitOutcome.NOT_A_REPOSITORY, operations.fetch(FetchInput.builder()
                                    .root(plain).remote(ORIGIN).refSpecs(List.of()).build()).outcome(),
                            "fetch refuses a plain directory"));
        }

        @Test
        @DisplayName("log walks the history newest first, bounded by the count")
        void logsNewestFirst(@LocalRepository Repository repository) throws Exception {
            var ref = branchRef();
            var first = TestCommits.commitOn(repository, ref);
            var second = TestCommits.commitOn(repository, ref);
            var third = TestCommits.commitOn(repository, ref);
            var branch = Repository.shortenRefName(ref);

            var all = Objects.requireNonNull(operations.log(dir(repository), branch, 10).value(), "the history");
            var one = Objects.requireNonNull(operations.log(dir(repository), branch, 1).value(), "the newest");
            var atLeastOne = Objects.requireNonNull(operations.log(dir(repository), third.name(), 0).value(),
                    "a count below one");

            assertAll(
                    () -> assertEquals(List.of(third.name(), second.name(), first.name()),
                            all.stream().map(CommitInfo::id).toList(), "the newest commit comes first"),
                    () -> assertEquals(List.of(second.name()), all.getFirst().parents(),
                            "the parent of the newest commit is the one before it"),
                    () -> assertEquals(List.of(third.name()), one.stream().map(CommitInfo::id).toList(),
                            "a count of one yields the newest commit"),
                    () -> assertEquals(1, atLeastOne.size(), "a count below one is taken as one"));
        }

        @Test
        @DisplayName("GDR-CORE-11: an unknown or abbreviated revision is not found, an ambiguous one lists candidates")
        void refusedRevisions(@LocalRepository Repository repository) throws Exception {
            var ref = branchRef();
            var commit = TestCommits.commitOn(repository, ref);
            var duplicate = Repository.shortenRefName(ref);
            TestCommits.setRef(repository, GitIdentity.tagRef(duplicate), commit);
            var abbreviated = commit.name().substring(0, 8);

            var unknown = operations.log(dir(repository), TestCommits.name(), 1);
            var abbreviation = operations.log(dir(repository), abbreviated, 1);
            var ambiguous = operations.log(dir(repository), duplicate, 1);

            var ambiguousDetail = Objects.requireNonNull(ambiguous.detail(), "an ambiguous name has a detail");
            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, unknown.outcome(), "an unknown name is not found"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, abbreviation.outcome(),
                            "an abbreviated commit id is never resolved"),
                    () -> assertEquals("revision: abbreviated commit id", abbreviation.detail(),
                            "the detail names the field and the abbreviation, not the value"),
                    () -> assertEquals(GitOutcome.AMBIGUOUS, ambiguous.outcome(),
                            "a branch and a tag of one name are ambiguous"),
                    () -> assertTrue(ambiguousDetail.contains(ref), "the branch is a candidate in the detail"),
                    () -> assertTrue(ambiguousDetail.contains(GitIdentity.tagRef(duplicate)),
                            "the tag is a candidate in the detail"));
        }

        @Test
        @DisplayName("GDR-CORE-11: a short prefix and an expression on an abbreviated base are never resolved")
        void unsupportedRevisions(@LocalRepository Repository repository) throws Exception {
            var commit = TestCommits.commitOn(repository, branchRef());
            // JGit 7.8.0 resolves an abbreviation from two digits on and an expression on an abbreviated base
            var shortPrefix = commit.name().substring(0, 3);
            var expression = commit.name().substring(0, 8) + "~0";

            var prefix = operations.log(dir(repository), shortPrefix, 1);
            var onAbbreviation = operations.log(dir(repository), expression, 1);

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, prefix.outcome(), "a three-digit prefix is not found"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, onAbbreviation.outcome(),
                            "an expression on an abbreviated base is not found"),
                    () -> assertNull(prefix.value(), "no history is returned for the prefix"),
                    () -> assertNull(onAbbreviation.value(), "no history is returned for the expression"));
        }

        @Test
        @DisplayName("GDR-CORE-11: a full commit id absent from the repository is not found, not a failure")
        void absentFullCommitId(@LocalRepository Repository repository, @InMemory Repository other)
                throws Exception {
            TestCommits.commitOn(repository, branchRef());
            var absent = TestCommits.commit(other, null).name();

            var result = operations.log(dir(repository), absent, 1);

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "the absent id is not found"),
                    () -> assertEquals("revision: not found", result.detail(),
                            "the detail names the field, not the value"));
        }

        @Test
        @DisplayName("GDR-CORE-11: HEAD, a short and a full reference name and a full id start the history")
        void supportedRevisions(@LocalRepository Repository repository) throws Exception {
            var ref = branchRef();
            var commit = TestCommits.commitOn(repository, ref);
            repository.updateRef(Constants.HEAD).link(ref);

            assertAll(Stream.of(Constants.HEAD, Repository.shortenRefName(ref), ref, commit.name())
                    .<Executable>map(revision -> () -> assertEquals(List.of(commit.name()),
                            Objects.requireNonNull(operations.log(dir(repository), revision, 1).value(),
                                    revision + " yields a history").stream().map(CommitInfo::id).toList(),
                            revision + " starts at the commit")));
        }
    }

    @Nested
    @DisplayName("clone when missing (GDR-CORE-3)")
    class CloneIfMissing {

        private CloneInput input(Path gitDir, String url) {
            return CloneInput.builder().gitDir(gitDir).url(url).pushRefSpecs(List.of()).build();
        }

        @Test
        @DisplayName("GDR-CORE-3: a missing bare clone is created with the mirroring refspecs and every branch, tag and note")
        void clonesMissing(@Remote LocalRemote remote) throws Exception {
            var source = remote.repository();
            var ref = branchRef();
            var commit = TestCommits.commitOn(source, ref);
            var tag = GitIdentity.tagRef(TestCommits.name());
            TestCommits.setRef(source, tag, commit);
            var notes = TestCommits.note(source, commit);
            var pushSpec = ref + ":" + ref;
            var gitDir = temp.resolve(TestCommits.name() + ".git");

            var result = operations.cloneIfMissing(CloneInput.builder().gitDir(gitDir).url(remote.uri().toString())
                    .pushRefSpecs(List.of(pushSpec)).build());

            var info = Objects.requireNonNull(result.value(), "the clone is created");
            try (Repository clone = openBare(gitDir)) {
                var config = new RemoteConfig(clone.getConfig(), ORIGIN);
                assertAll(
                        () -> assertTrue(clone.isBare(), "the clone is bare"),
                        () -> assertNull(info.worktreeRoot(), "the clone has no working tree"),
                        () -> assertEquals(commit, refId(clone, ref), "the branch is cloned"),
                        () -> assertEquals(commit, refId(clone, tag), "the tag is cloned"),
                        () -> assertEquals(notes, refId(clone, TestCommits.NOTES_REF), "the notes are cloned"),
                        () -> assertEquals(JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS,
                                config.getFetchRefSpecs().stream().map(RefSpec::toString).toList(),
                                "origin mirrors branches, tags and notes"),
                        () -> assertEquals(List.of(pushSpec),
                                config.getPushRefSpecs().stream().map(RefSpec::toString).toList(),
                                "origin carries the caller's push refspecs"),
                        () -> assertEquals(List.of(remote.uri()), config.getURIs(), "origin is the remote's URL"));
            }
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    GitCoreLogMessages.INFO.CLONE_CREATED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-3: an existing bare clone is opened without contacting the remote")
        void reopensExisting(@LocalRepository Repository existing) throws Exception {
            var commit = TestCommits.commitOn(existing, headTarget(existing));
            // a URL the policy refuses: contacting it would end refused by policy, so OK proves no contact
            var refused = "https://" + FOREIGN_HOST + "/" + TestCommits.name() + ".git";

            var result = operations.cloneIfMissing(input(dir(existing), refused));

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the existing clone is opened"),
                    () -> assertEquals(commit.name(), Objects.requireNonNull(result.value()).head(),
                            "the existing clone's head is reported"));
        }

        @Test
        @DisplayName("GDR-CORE-3: every pushed branch, tag and note is restored by a new clone")
        void restoresPushedState(@Remote LocalRemote remote) throws Exception {
            var first = temp.resolve(TestCommits.name() + ".git");
            assertTrue(operations.cloneIfMissing(input(first, remote.uri().toString())).isOk(),
                    "the first clone of the empty remote is created");
            var branch = branchRef();
            var tag = GitIdentity.tagRef(TestCommits.name());
            ObjectId commit;
            ObjectId notes;
            try (Repository clone = openBare(first)) {
                commit = TestCommits.commitOn(clone, branch);
                TestCommits.setRef(clone, tag, commit);
                notes = TestCommits.note(clone, commit);
            }
            for (String ref : List.of(branch, tag, TestCommits.NOTES_REF)) {
                var pushed = operations.push(PushInput.builder().root(first).remote(ORIGIN).localRef(ref)
                        .remoteRef(ref).build());
                assertEquals(GitOutcome.OK, pushed.outcome(), "the reference " + ref + " is pushed");
            }
            var second = temp.resolve(TestCommits.name() + ".git");

            var restored = operations.cloneIfMissing(input(second, remote.uri().toString()));

            assertEquals(GitOutcome.OK, restored.outcome(), "the new clone is created");
            try (Repository clone = openBare(second)) {
                assertAll(
                        () -> assertEquals(commit, refId(clone, branch), "the pushed branch is restored"),
                        () -> assertEquals(commit, refId(clone, tag), "the pushed tag is restored"),
                        () -> assertEquals(notes, refId(clone, TestCommits.NOTES_REF), "the pushed notes are restored"));
            }
        }

        @Test
        @DisplayName("a non-empty directory that is no repository is not a repository")
        void notARepository() throws Exception {
            var occupied = Files.createDirectories(temp.resolve(TestCommits.name()));
            Files.writeString(occupied.resolve(TestCommits.name()), Generators.letterStrings(1, 40).next());

            var result = operations.cloneIfMissing(input(occupied, occupied.toUri().toString()));

            assertEquals(GitOutcome.NOT_A_REPOSITORY, result.outcome(), "an occupied directory is no clone");
        }

        @Test
        @DisplayName("GDR-CORE-2: a URL outside the policy is refused before anything is created or sent")
        void refusesRemote() {
            var gitDir = temp.resolve(TestCommits.name() + ".git");

            var result = operations.cloneIfMissing(
                    input(gitDir, "https://" + FOREIGN_HOST + "/" + TestCommits.name() + ".git"));

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the remote is refused"),
                    () -> assertFalse(Files.exists(gitDir), "no clone is created"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.REMOTE_REFUSED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-CORE-7: a remote that is gone is unreachable and leaves no clone behind")
        void unreachable() {
            var gitDir = temp.resolve(TestCommits.name() + ".git");
            var gone = temp.resolve(TestCommits.name());

            var result = operations.cloneIfMissing(input(gitDir, gone.toUri().toString()));

            assertAll(
                    () -> assertEquals(GitOutcome.UNREACHABLE, result.outcome(), "the gone remote is unreachable"),
                    () -> assertFalse(Files.exists(gitDir), "the incomplete clone is removed"));
        }
    }

    @Nested
    @DisplayName("fetch and push against a local remote")
    class Transport {

        private PushInput push(Repository client, String ref, @Nullable String expectedRemoteHead) {
            return PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(ref).remoteRef(ref)
                    .expectedRemoteHead(expectedRemoteHead).build();
        }

        private FetchInput fetch(Repository client) {
            return FetchInput.builder().root(dir(client)).remote(ORIGIN).refSpecs(List.of()).build();
        }

        @Test
        @DisplayName("a push is accepted and a fetch into another repository updates its tracking reference")
        void pushAndFetch(@Remote LocalRemote remote, @LocalRepository Repository pusher,
                @LocalRepository Repository fetcher) throws Exception {
            TestCommits.addRemote(pusher, ORIGIN, remote.uri().toString());
            TestCommits.addRemote(fetcher, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            var commit = TestCommits.commitOn(pusher, ref);

            var pushed = operations.push(push(pusher, ref, null));
            var fetched = operations.fetch(fetch(fetcher));

            var tracking = Constants.R_REMOTES + ORIGIN + "/" + Repository.shortenRefName(ref);
            var updates = Objects.requireNonNull(fetched.value(), "the fetch succeeds");
            assertAll(
                    () -> assertEquals(GitOutcome.OK, pushed.outcome(), "the push is accepted"),
                    () -> assertEquals(commit.name(), Objects.requireNonNull(pushed.value()).newId(),
                            "the push reports the new id"),
                    () -> assertEquals(commit, refId(remote.repository(), ref), "the remote holds the commit"),
                    () -> assertEquals(List.of(tracking), updates.stream().map(RefUpdate::ref).toList(),
                            "the tracking reference is updated"),
                    () -> assertEquals(commit.name(), updates.getFirst().newId(), "the tracking reference is the commit"),
                    () -> assertNull(updates.getFirst().oldId(), "the tracking reference is new"));
        }

        @Test
        @DisplayName("GDR-CORE-7: a push that is no fast-forward is rejected")
        void rejected(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            var remoteHead = TestCommits.commitOn(remote.repository(), ref);
            TestCommits.commitOn(client, ref);

            var result = operations.push(push(client, ref, null));

            assertAll(
                    () -> assertEquals(GitOutcome.REJECTED, result.outcome(), "an unrelated history is rejected"),
                    () -> assertTrue(Objects.requireNonNull(result.detail()).contains(ref), "the detail names the ref"),
                    () -> assertEquals(remoteHead, refId(remote.repository(), ref), "the remote is unchanged"));
        }

        @Test
        @DisplayName("GDR-CORE-7: a push whose lease no longer holds is refused, one whose lease holds is accepted")
        void lease(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            var remoteHead = TestCommits.commitOn(remote.repository(), ref);
            assertTrue(operations.fetch(fetch(client)).isOk(), "the client fetches the remote head");
            TestCommits.setRef(client, ref, remoteHead);
            var next = TestCommits.commitOn(client, ref);
            var stale = TestCommits.commit(client, null);

            var refused = operations.push(push(client, ref, stale.name()));
            var unchanged = refId(remote.repository(), ref);
            var accepted = operations.push(push(client, ref, remoteHead.name()));

            assertAll(
                    () -> assertEquals(GitOutcome.LEASE_REFUSED, refused.outcome(), "a stale lease is refused"),
                    () -> assertEquals(remoteHead, unchanged, "the refused push leaves the remote unchanged"),
                    () -> assertEquals(GitOutcome.OK, accepted.outcome(), "a holding lease is accepted"),
                    () -> assertEquals(next, refId(remote.repository(), ref), "the remote moves to the new commit"));
        }

        @Test
        @DisplayName("GDR-CORE-7: a push of the commit the remote already holds honours the lease and reports the old id")
        void upToDate(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            var head = TestCommits.commitOn(client, ref);
            assertTrue(operations.push(push(client, ref, null)).isOk(), "the remote receives the commit");
            var other = TestCommits.commit(client, null);

            var plain = operations.push(push(client, ref, null));
            var staleLease = operations.push(push(client, ref, other.name()));
            var holdingLease = operations.push(push(client, ref, head.name()));

            var plainUpdate = Objects.requireNonNull(plain.value(), "the plain push succeeds");
            assertAll(
                    () -> assertEquals("up_to_date", plainUpdate.status(), "the reference is up to date"),
                    () -> assertEquals(head.name(), plainUpdate.oldId(),
                            "the old id is the id the reference already had, not a creation"),
                    () -> assertEquals(GitOutcome.LEASE_REFUSED, staleLease.outcome(),
                            "a lease on another head is refused although the reference is up to date"),
                    () -> assertEquals(GitOutcome.OK, holdingLease.outcome(), "a holding lease is accepted"),
                    () -> assertEquals(head, refId(remote.repository(), ref), "the remote stays at the commit"));
        }

        @Test
        @DisplayName("a push to a remote with two push destinations is refused before either is contacted")
        void twoDestinations(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            var config = client.getConfig();
            var remoteConfig = new RemoteConfig(config, ORIGIN);
            remoteConfig.addURI(new URIish(remote.uri().toString()));
            remoteConfig.addPushURI(new URIish(remote.uri().toString()));
            remoteConfig.addPushURI(new URIish(temp.resolve(TestCommits.name()).toUri().toString()));
            remoteConfig.update(config);
            config.save();
            var ref = branchRef();
            TestCommits.commitOn(client, ref);

            var result = operations.push(push(client, ref, null));

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(),
                            "two destinations are refused"),
                    () -> assertNull(remote.repository().exactRef(ref), "the first destination is not contacted"));
        }

        @Test
        @DisplayName("GDR-CORE-7: a remote that is gone is unreachable for fetch and push")
        void unreachable(@LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, temp.resolve(TestCommits.name()).toUri().toString());
            var ref = branchRef();
            TestCommits.commitOn(client, ref);

            assertAll(
                    () -> assertEquals(GitOutcome.UNREACHABLE, operations.fetch(fetch(client)).outcome(),
                            "a fetch from a gone remote is unreachable"),
                    () -> assertEquals(GitOutcome.UNREACHABLE, operations.push(push(client, ref, null)).outcome(),
                            "a push to a gone remote is unreachable"));
        }

        @Test
        @DisplayName("GDR-CORE-2: a remote outside the policy is refused without contact")
        void refusedByPolicy(@LocalRepository Repository client) throws Exception {
            var httpsOnly = new JGitRepositoryOperations(
                    RemotePolicy.httpsOrigin(URI.create("https://" + FOREIGN_HOST)));
            // a gone local remote: contacting it would be unreachable, so refused by policy proves no contact
            TestCommits.addRemote(client, ORIGIN, temp.resolve(TestCommits.name()).toUri().toString());
            var ref = branchRef();
            TestCommits.commitOn(client, ref);

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, httpsOnly.fetch(fetch(client)).outcome(),
                            "a fetch outside the policy is refused"),
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, httpsOnly.push(push(client, ref, null)).outcome(),
                            "a push outside the policy is refused"));
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.REMOTE_REFUSED.resolveIdentifierString());
        }

        @Test
        @DisplayName("an unknown remote and an unknown local reference are not found")
        void notFound(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, operations.fetch(FetchInput.builder().root(dir(client))
                                    .remote(TestCommits.name()).refSpecs(List.of()).build()).outcome(),
                            "an unknown remote is not found"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, operations.push(push(client, branchRef(), null)).outcome(),
                            "an unknown local reference is not found"));
        }

        @Test
        @DisplayName("GDR-CORE-11: a push of an abbreviated commit id is not found and leaves the remote unchanged")
        void abbreviatedLocalRef(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            var commit = TestCommits.commitOn(client, ref);
            var abbreviated = commit.name().substring(0, 7);

            var result = operations.push(PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(abbreviated)
                    .remoteRef(ref).build());

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "the abbreviation is never resolved"),
                    () -> assertNull(remote.repository().exactRef(ref), "the remote is unchanged"));
        }

        @Test
        @DisplayName("GDR-CORE-1: a hook present in the pushing repository is refused and never run")
        void hookRefused(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            TestCommits.commitOn(client, ref);
            var marker = temp.resolve(TestCommits.name());
            // the hook name is git's own; the script would create the marker if it ran
            Path hook = dir(client).resolve("hooks/pre-push");
            Files.createDirectories(hook.getParent());
            Files.writeString(hook, "#!/bin/sh\ntouch " + marker + "\n");
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));

            var result = operations.push(push(client, ref, null));

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the present hook is refused"),
                    () -> assertEquals("repository_hook", result.detail(), "the detail names the refused capability"),
                    () -> assertNull(remote.repository().exactRef(ref), "nothing is pushed"),
                    () -> assertFalse(Files.exists(marker), "the hook is never run"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.CAPABILITY_REFUSED.resolveIdentifierString());
        }
    }

    @Nested
    @DisplayName("secrets (GDR-NFR-3)")
    class Secrets {

        private GitCredential credential;
        private String token;
        private String url;

        @BeforeEach
        void credential() {
            credential = GitGenerators.credentials().next();
            token = new String(credential.token());
            url = "https://" + credential.username() + ":" + token + "@" + FOREIGN_HOST + "/" + TestCommits.name()
                    + ".git";
        }

        private UsernamePasswordCredentialsProvider provider() {
            return new UsernamePasswordCredentialsProvider(credential.username(), credential.token());
        }

        /**
         * Fetches through a {@link RecordingTransportProtocol} while a process-wide credentials provider is set.
         *
         * @return the protocol, holding what the fetch's connection would have used
         */
        private RecordingTransportProtocol recordFetch(JGitRepositoryOperations httpsOnly, Repository client,
                String marker, @Nullable CredentialsProvider credentials) {
            CredentialsProvider previous = CredentialsProvider.getDefault();
            CredentialsProvider.setDefault(provider());
            var recording = new RecordingTransportProtocol(marker);
            try (recording) {
                httpsOnly.fetch(FetchInput.builder().root(dir(client)).remote(ORIGIN).refSpecs(List.of())
                        .credentials(credentials).build());
            } finally {
                CredentialsProvider.setDefault(previous);
            }
            return recording;
        }

        private void assertNoSecret(GitResult<?> result) {
            var detail = Objects.requireNonNull(result.detail(), "a non-success carries a detail");
            assertAll(
                    () -> assertFalse(detail.contains(token), "the detail carries no token"),
                    () -> assertFalse(detail.contains("@"), "the detail carries no user information"),
                    () -> assertFalse(TestLoggerFactory.getTestHandler().getRecordsAsString().contains(token),
                            "no captured log record carries the token"));
        }

        @Test
        @DisplayName("GDR-CORE-7: neither a refused fetch nor a refused push renders the token or the user")
        void refusedTransport(@LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, url);
            var ref = branchRef();
            TestCommits.commitOn(client, ref);

            var fetched = operations.fetch(FetchInput.builder().root(dir(client)).remote(ORIGIN).refSpecs(List.of())
                    .credentials(provider()).build());
            var pushed = operations.push(PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(ref)
                    .remoteRef(ref).credentials(provider()).build());

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, fetched.outcome(), "the fetch is refused"),
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, pushed.outcome(), "the push is refused"),
                    () -> assertNoSecret(fetched),
                    () -> assertNoSecret(pushed));
        }

        @Test
        @DisplayName("GDR-CORE-7: a refused clone renders neither the token nor the user")
        void refusedClone() {
            var input = CloneInput.builder().gitDir(temp.resolve(TestCommits.name() + ".git")).url(url)
                    .pushRefSpecs(List.of()).credentials(provider()).build();

            var result = operations.cloneIfMissing(input);

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the clone is refused"),
                    () -> assertNoSecret(result),
                    () -> assertFalse(input.toString().contains(token), "the clone input renders no token"));
        }

        @Test
        @DisplayName("GDR-CORE-7: credentials passed to a reachable remote and an unreachable one are never logged")
        void credentialsNotLogged(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            TestCommits.commitOn(client, ref);
            var gitDir = temp.resolve(TestCommits.name() + ".git");

            var pushed = operations.push(PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(ref)
                    .remoteRef(ref).credentials(provider()).build());
            var cloned = operations.cloneIfMissing(CloneInput.builder().gitDir(gitDir)
                    .url(temp.resolve(TestCommits.name()).toUri().toString()).pushRefSpecs(List.of())
                    .credentials(provider()).build());

            assertAll(
                    () -> assertEquals(GitOutcome.OK, pushed.outcome(), "the push with credentials succeeds"),
                    () -> assertEquals(GitOutcome.UNREACHABLE, cloned.outcome(), "the gone remote is unreachable"),
                    () -> assertNoSecret(cloned));
        }

        @Test
        @DisplayName("GDR-NFR-3: an unknown revision or local reference names its field, never the caller's value")
        void detailsCarryNoInput(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            TestCommits.commitOn(client, branchRef());
            var mail = TestCommits.name() + "@" + FOREIGN_HOST;

            var logged = operations.log(dir(client), mail, 1);
            var pushed = operations.push(PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(mail)
                    .remoteRef(branchRef()).build());

            assertAll(
                    () -> assertEquals("revision: not found", logged.detail(), "log names the revision field"),
                    () -> assertEquals("localRef: not found", pushed.detail(), "push names the local reference field"));
        }

        @Test
        @DisplayName("GDR-NFR-3: a refused URL renders neither its query nor its fragment")
        void refusedUrlRendersNoQuery(@LocalRepository Repository client) throws Exception {
            // upper case, so it cannot occur by chance in the lower-case host or path of the detail
            var query = TestCommits.name().toUpperCase(Locale.ROOT);
            TestCommits.addRemote(client, ORIGIN,
                    "https://" + FOREIGN_HOST + "/" + TestCommits.name() + ".git?access_token=" + query + "#" + query);

            var fetched = operations.fetch(FetchInput.builder().root(dir(client)).remote(ORIGIN).refSpecs(List.of())
                    .build());

            var detail = Objects.requireNonNull(fetched.detail(), "the refusal carries a detail");
            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, fetched.outcome(), "the remote is refused"),
                    () -> assertTrue(detail.contains(FOREIGN_HOST), "the detail names the host"),
                    () -> assertFalse(detail.contains(query), "the detail carries no query and no fragment"),
                    () -> assertFalse(TestLoggerFactory.getTestHandler().getRecordsAsString().contains(query),
                            "no captured log record carries the query"));
        }

        @Test
        @DisplayName("GDR-NFR-3: a fetch without credentials uses none, a fetch with credentials uses exactly them")
        void onlyCallerCredentials(@LocalRepository Repository client) throws Exception {
            var marker = TestCommits.name();
            TestCommits.addRemote(client, ORIGIN, "https://" + FOREIGN_HOST + "/" + marker + ".git");
            var httpsOnly = new JGitRepositoryOperations(RemotePolicy.httpsOrigin(URI.create("https://" + FOREIGN_HOST)));
            var caller = provider();
            var anonymous = recordFetch(httpsOnly, client, marker, null);
            var authenticated = recordFetch(httpsOnly, client, marker, caller);

            assertAll(
                    () -> assertTrue(anonymous.connected(), "the anonymous fetch reached the transport"),
                    () -> assertTrue(anonymous.credentials().isEmpty(),
                            "the process-wide provider is not used without caller credentials"),
                    () -> assertTrue(authenticated.connected(), "the authenticated fetch reached the transport"),
                    () -> assertEquals(Optional.of(caller), authenticated.credentials(),
                            "the caller's provider is used"));
        }
    }
}
