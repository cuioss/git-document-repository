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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Serial;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;


import de.cuioss.git.document.core.RepositoryOperations.CloneInput;
import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.core.RepositoryOperations.ReadFileInput;
import de.cuioss.git.document.core.RepositoryOperations.RefUpdate;
import de.cuioss.git.document.testing.GitCredential;
import de.cuioss.git.document.testing.GitGenerators;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.easymock.EasyMock;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.HttpConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.TrackingRefUpdate;
import org.eclipse.jgit.transport.TransportHttp;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.http.HttpConnection;
import org.eclipse.jgit.transport.http.HttpConnectionFactory;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    /** The values asserted absent from a detail, a log or a file: distinct by construction, found nowhere by chance. */
    private final TypedGenerator<String> distinctTexts = GitGenerators.distinctTexts();

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

    /** The sorted names of the entries of a directory. */
    private static List<String> entries(Path directory) throws IOException {
        try (var list = Files.list(directory)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
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
        @DisplayName("GDR-CORE-11: log reads nothing past the parents of the listed commits; a missing parent fails")
        void logBoundedByLimit(@LocalRepository Repository repository) throws Exception {
            var ref = branchRef();
            var lost = TestCommits.commitOn(repository, ref);
            TestCommits.commitOn(repository, ref);
            var newest = TestCommits.commitOn(repository, ref);
            var name = lost.name();
            // the commit is written as a loose object; deleting it is a repository that lost it
            Files.delete(dir(repository).resolve("objects").resolve(name.substring(0, 2)).resolve(name.substring(2)));

            var limited = operations.log(dir(repository), newest.name(), 1);
            var reaching = operations.log(dir(repository), newest.name(), 2);

            assertAll(
                    () -> assertEquals(GitOutcome.OK, limited.outcome(),
                            "a commit missing behind the parent of the last listed commit is never read"),
                    () -> assertEquals(List.of(newest.name()),
                            Objects.requireNonNull(limited.value()).stream().map(CommitInfo::id).toList(),
                            "the limit lists the newest commit only"),
                    () -> assertEquals(GitOutcome.FAILED, reaching.outcome(),
                            "a missing parent of a listed commit fails the log"),
                    () -> assertEquals("MissingObjectException", reaching.detail(),
                            "the detail names the failure class"));
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
        @DisplayName("open reports the branch and the commit of one HEAD observation")
        void headOfBranch(@LocalRepository Repository repository) throws Exception {
            var target = headTarget(repository);
            var commit = TestCommits.commitOn(repository, target);

            var info = Objects.requireNonNull(operations.open(dir(repository)).value(), "the repository opens");

            assertAll(
                    () -> assertEquals(Repository.shortenRefName(target), info.branch(), "the branch HEAD points to"),
                    () -> assertEquals(commit.name(), info.head(), "the commit of that branch"));
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
    @DisplayName("HTTP connections (GDR-CORE-2)")
    class HttpConnections {

        /** The host the policy of these tests admits; nothing connects to it. */
        private static final String ADMITTED_HOST = "admitted.example.invalid";

        @Test
        @DisplayName("GDR-CORE-2: every HTTP connection of a guarded transport is checked, also with redirects enabled")
        void everyConnectionChecked(@LocalRepository Repository repository) throws Exception {
            var config = repository.getConfig();
            // repository configuration is attacker-influenced state: enabling every redirect must change nothing
            config.setString(HttpConfig.HTTP, null, HttpConfig.FOLLOW_REDIRECTS_KEY, "true");
            config.save();
            var policy = RemotePolicy.httpsOrigin(URI.create("https://" + ADMITTED_HOST));
            var path = "/" + TestCommits.name() + ".git/info/refs";

            // fully qualified: the nested test class Transport shadows JGit's Transport here
            try (var transport = org.eclipse.jgit.transport.Transport.open(repository,
                         new URIish("https://" + ADMITTED_HOST + "/" + TestCommits.name() + ".git"))) {
                JGitRepositoryOperations.guard(transport, policy);
                var factory = ((TransportHttp) transport).getHttpConnectionFactory();
                var redirect = URI.create("https://" + FOREIGN_HOST + path).toURL();
                var admitted = URI.create("https://" + ADMITTED_HOST + path).toURL();

                var refusal = assertThrows(JGitRepositoryOperations.RemoteRefusedException.class,
                        () -> factory.create(redirect, Proxy.NO_PROXY), "a connection to another host is refused");
                assertAll(
                        () -> assertInstanceOf(JGitRepositoryOperations.PolicyCheckedConnectionFactory.class, factory,
                                "the transport connects through the policy-checked factory"),
                        () -> assertEquals(FOREIGN_HOST, refusal.uri().getHost(), "the refused host is named"),
                        () -> assertTrue(refusal.redirect(), "the refusal is one of a further connection"),
                        () -> assertNotNull(factory.create(admitted, Proxy.NO_PROXY),
                                "a connection to the admitted host is created"),
                        () -> assertNotNull(factory.create(admitted), "also without a proxy argument"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-2: a transport whose own URL is outside the policy is refused before any connection")
        void transportUrlChecked(@LocalRepository Repository repository) throws Exception {
            var policy = RemotePolicy.httpsOrigin(URI.create("https://" + ADMITTED_HOST));

            try (var transport = org.eclipse.jgit.transport.Transport.open(repository,
                         new URIish("https://" + FOREIGN_HOST + "/" + TestCommits.name() + ".git"))) {
                var refusal = assertThrows(JGitRepositoryOperations.RemoteRefusedException.class,
                        () -> JGitRepositoryOperations.guard(transport, policy), "the transport is refused");

                assertFalse(refusal.redirect(), "the refusal is one of the transport's own URL");
            }
        }
    }

    @Nested
    @DisplayName("process-wide configuration (GDR-CORE-1)")
    class ProcessWideConfiguration {

        @Test
        @DisplayName("GDR-CORE-1: open, init and clone leave the process-wide SystemReader as they found it")
        void systemReaderUntouched(@Remote LocalRemote remote) {
            var before = SystemReader.getInstance();

            var created = operations.init(temp.resolve(TestCommits.name()), TestCommits.name());
            var cloned = operations.cloneIfMissing(CloneInput.builder().gitDir(temp.resolve(TestCommits.name() + ".git"))
                    .url(remote.uri().toString()).pushRefSpecs(List.of()).build());

            assertAll(
                    () -> assertTrue(created.isOk(), "init succeeds"),
                    () -> assertTrue(cloned.isOk(), "the clone succeeds"),
                    () -> assertSame(before, SystemReader.getInstance(), "the SystemReader is the same object"));
        }

        @Test
        @DisplayName("GDR-CORE-1: with the hardened reader installed, configuration is located without any process")
        void hardenedReader(@Remote LocalRemote remote) throws Exception {
            var ref = branchRef();
            var commit = TestCommits.commitOn(remote.repository(), ref);
            var previous = SystemReader.getInstance();
            List<FS> located = new ArrayList<>();
            var recording = new SystemReader.Delegate(previous) {
                @Override
                public FileBasedConfig openSystemConfig(Config parent, FS fs) {
                    located.add(fs);
                    return super.openSystemConfig(parent, fs);
                }
            };
            SystemReader.setInstance(RefusingFS.systemReader(recording));
            try {
                var root = temp.resolve(TestCommits.name());
                var created = operations.init(root, TestCommits.name());
                var opened = operations.open(root);
                var gitDir = temp.resolve(TestCommits.name() + ".git");
                var cloned = operations.cloneIfMissing(CloneInput.builder().gitDir(gitDir)
                        .url(remote.uri().toString()).pushRefSpecs(List.of()).build());

                assertAll(
                        () -> assertTrue(created.isOk(), "init succeeds"),
                        () -> assertTrue(opened.isOk(), "open succeeds"),
                        () -> assertTrue(cloned.isOk(), "the clone succeeds"),
                        () -> assertFalse(located.isEmpty(), "the system configuration was located"),
                        () -> assertTrue(located.stream().allMatch(RefusingFS.class::isInstance),
                                "it was located through a RefusingFS, which starts no process"),
                        () -> assertTrue(located.stream().allMatch(fs -> fs.getGitSystemConfig() == null),
                                "no system configuration is found without running git"));
                try (Repository clone = openBare(gitDir)) {
                    assertEquals(commit, refId(clone, ref), "the clone fetched the remote");
                }
            } finally {
                SystemReader.setInstance(previous);
            }
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
                    () -> assertFalse(Files.exists(gitDir), "the incomplete clone is removed"),
                    () -> assertEquals(List.of(), entries(temp), "no staging directory is left behind"));
        }

        @Test
        @DisplayName("GDR-CORE-3: a failed clone into a prepared empty directory leaves it empty, a clone fills it")
        void preparedDirectory(@Remote LocalRemote remote) throws Exception {
            var parent = Files.createDirectories(temp.resolve(TestCommits.name()));
            var failing = Files.createDirectories(parent.resolve(TestCommits.name() + ".git"));
            var cloning = Files.createDirectories(parent.resolve(TestCommits.name() + ".git"));
            var commit = TestCommits.commitOn(remote.repository(), branchRef());

            var failed = operations.cloneIfMissing(input(failing, temp.resolve(TestCommits.name()).toUri().toString()));
            var cloned = operations.cloneIfMissing(input(cloning, remote.uri().toString()));

            assertAll(
                    () -> assertEquals(GitOutcome.UNREACHABLE, failed.outcome(), "the gone remote is unreachable"),
                    () -> assertEquals(List.of(), entries(failing), "the prepared directory stays empty"),
                    () -> assertEquals(GitOutcome.OK, cloned.outcome(), "the prepared directory receives the clone"),
                    () -> assertEquals(List.of(failing.getFileName().toString(), cloning.getFileName().toString())
                            .stream().sorted().toList(), entries(parent), "no staging directory is left behind"));
            try (Repository clone = openBare(cloning)) {
                assertTrue(clone.getObjectDatabase().has(commit), "the published clone holds the fetched commit");
            }
        }

        @Test
        @DisplayName("GDR-CORE-3: source-only wildcard push refspecs are configured as pushes to the same names")
        void sourceOnlyPushRefSpecs(@Remote LocalRemote remote) throws Exception {
            var ref = branchRef();
            var commit = TestCommits.commitOn(remote.repository(), ref);
            var gitDir = temp.resolve(TestCommits.name() + ".git");
            // the push refspecs git-store configures, by GDR-CORE-3
            var specs = List.of("refs/heads/*", "refs/tags/*", "refs/notes/*");

            var result = operations.cloneIfMissing(CloneInput.builder().gitDir(gitDir).url(remote.uri().toString())
                    .pushRefSpecs(specs).build());

            assertEquals(GitOutcome.OK, result.outcome(), "the clone is created");
            try (Repository clone = openBare(gitDir)) {
                var config = new RemoteConfig(clone.getConfig(), ORIGIN);
                assertAll(
                        () -> assertEquals(List.of("refs/heads/*:refs/heads/*", "refs/tags/*:refs/tags/*",
                                        "refs/notes/*:refs/notes/*"),
                                config.getPushRefSpecs().stream().map(RefSpec::toString).toList(),
                                "each source-only spec pushes to the same names"),
                        () -> assertEquals(commit, refId(clone, ref), "the remote is fetched"));
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"refs/releases/*", "refs/heads/*:refs/releases/*", ":refs/heads/main", ":",
                "refs/heads/topic:refs/heads/topic.lock", "refs/heads/*:refs/heads/*.lock"})
        @DisplayName("GDR-CORE-3: a push refspec the clone cannot restore is refused before anything is created")
        void refusedPushRefSpec(String spec) {
            var gitDir = temp.resolve(TestCommits.name() + ".git");

            var result = operations.cloneIfMissing(CloneInput.builder().gitDir(gitDir)
                    .url(temp.resolve(TestCommits.name()).toUri().toString()).pushRefSpecs(List.of(spec)).build());

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the refspec is refused"),
                    () -> assertFalse(Files.exists(gitDir), "no clone is created"));
        }

        @Test
        @DisplayName("GDR-CORE-2: a port beyond the int range is refused like any other URL that cannot be parsed")
        void refusesPortBeyondIntRange() {
            var gitDir = temp.resolve(TestCommits.name() + ".git");

            var result = operations.cloneIfMissing(
                    input(gitDir, "https://" + FOREIGN_HOST + ":2147483648/" + TestCommits.name() + ".git"));

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the URL is refused"),
                    () -> assertFalse(Files.exists(gitDir), "no clone is created"));
        }

        @Test
        @DisplayName("GDR-CORE-2, GDR-CORE-3: an explicit port 0 is refused, never stored as the default port")
        void refusesExplicitPortZero() {
            var marker = TestCommits.name();
            var gitDir = temp.resolve(TestCommits.name() + ".git");
            var httpsOnly = new JGitRepositoryOperations(RemotePolicy.httpsOrigin(URI.create("https://" + FOREIGN_HOST)));

            try (var recording = new RecordingTransportProtocol(marker)) {
                var result = httpsOnly.cloneIfMissing(input(gitDir, "https://" + FOREIGN_HOST + ":0/" + marker + ".git"));

                assertAll(
                        () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "port 0 is refused"),
                        () -> assertEquals("not a URL", result.detail(), "the detail carries nothing of the URL"),
                        () -> assertFalse(recording.connected(), "the remote is not contacted"),
                        () -> assertFalse(Files.exists(gitDir), "no clone is created"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-2, GDR-CORE-3: an omitted port is admitted and stored without a port")
        void admitsOmittedPort() {
            var marker = TestCommits.name();
            var httpsOnly = new JGitRepositoryOperations(RemotePolicy.httpsOrigin(URI.create("https://" + FOREIGN_HOST)));

            try (var recording = new RecordingTransportProtocol(marker)) {
                httpsOnly.cloneIfMissing(input(temp.resolve(TestCommits.name() + ".git"),
                        "https://" + FOREIGN_HOST + "/" + marker + ".git"));

                // the clone's fetch opens its transport from the stored origin, so its URL is the stored one
                var stored = recording.uri().orElseThrow();
                assertAll(
                        () -> assertEquals(FOREIGN_HOST, stored.getHost(), "the stored origin keeps the host"),
                        () -> assertEquals(-1, stored.getPort(), "the stored origin carries no port"));
            }
        }

        @ParameterizedTest(name = "a {0} in the path")
        @ValueSource(strings = {"#", "?", "%20"})
        @DisplayName("GDR-CORE-3: a local path without a scheme is stored and cloned literally")
        void localPathStoredLiterally(String character) throws Exception {
            var source = temp.resolve(distinctTexts.next() + character + distinctTexts.next() + ".git");
            var ref = branchRef();
            ObjectId commit;
            try (Git git = Git.init().setBare(true).setGitDir(source.toFile()).call()) {
                commit = TestCommits.commitOn(git.getRepository(), ref);
            }
            var gitDir = temp.resolve(TestCommits.name() + ".git");

            var result = operations.cloneIfMissing(input(gitDir, source.toString()));

            try (Repository clone = openBare(gitDir)) {
                assertAll(
                        () -> assertEquals(GitOutcome.OK, result.outcome(), "the literal path is cloned"),
                        () -> assertEquals(source.toString(),
                                clone.getConfig().getString(ConfigConstants.CONFIG_REMOTE_SECTION, ORIGIN,
                                        ConfigConstants.CONFIG_KEY_URL),
                                "the stored origin is the path as given"),
                        () -> assertEquals(commit, refId(clone, ref), "the clone mirrors the literal path"));
            }
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
        @DisplayName("GDR-CORE-7: only a pruning fetch deletes the tracking reference of a deleted remote branch")
        void pruningFetch(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            TestCommits.commitOn(remote.repository(), ref);
            assertTrue(operations.fetch(fetch(client)).isOk(), "the client fetches the branch");
            var tracking = Constants.R_REMOTES + ORIGIN + "/" + Repository.shortenRefName(ref);
            var outside = "refs/drafts/" + TestCommits.name();
            var outsideId = TestCommits.commit(client, null);
            TestCommits.setRef(client, outside, outsideId);
            org.eclipse.jgit.lib.RefUpdate delete = remote.repository().updateRef(ref);
            delete.setForceUpdate(true);
            assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.FORCED, delete.delete(), "the remote branch is deleted");

            var plain = operations.fetch(fetch(client));
            var keptByPlain = client.exactRef(tracking);
            var pruned = operations.fetch(
                    FetchInput.builder().root(dir(client)).remote(ORIGIN).refSpecs(List.of()).prune(true).build());

            assertAll(
                    () -> assertEquals(GitOutcome.OK, plain.outcome(), "the plain fetch succeeds"),
                    () -> assertNotNull(keptByPlain, "a plain fetch keeps the stale tracking reference"),
                    () -> assertEquals(GitOutcome.OK, pruned.outcome(), "the pruning fetch succeeds"),
                    () -> assertNull(client.exactRef(tracking), "the pruning fetch deletes it"),
                    () -> assertEquals(outsideId, refId(client, outside),
                            "a reference outside every destination is kept"));
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
        @DisplayName("GDR-CORE-7: a leased push the remote cannot lock lost a race and is LEASE_REFUSED, a plain one REJECTED")
        void lostRaceOnRemote(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            var remoteHead = TestCommits.commitOn(remote.repository(), ref);
            assertTrue(operations.fetch(fetch(client)).isOk(), "the client fetches the remote head");
            TestCommits.setRef(client, ref, remoteHead);
            var next = TestCommits.commitOn(client, ref);
            // another writer holds the lock of the remote reference: JGit 7.8.0 ReceivePack answers the update of the
            // advertised id with "failed to lock", as it does when the reference moved after the advertisement
            Path lock = dir(remote.repository()).resolve(ref + ".lock");
            Files.createFile(lock);

            var leased = operations.push(push(client, ref, remoteHead.name()));
            var plain = operations.push(push(client, ref, null));
            Files.delete(lock);
            var retried = operations.push(push(client, ref, remoteHead.name()));

            assertAll(
                    () -> assertEquals(GitOutcome.LEASE_REFUSED, leased.outcome(),
                            "a leased push that lost the race on the remote reference is a refused lease"),
                    () -> assertEquals(ref, leased.detail(), "the detail names the reference"),
                    () -> assertEquals(GitOutcome.REJECTED, plain.outcome(),
                            "a plain push carries no lease and stays rejected"),
                    () -> assertEquals(GitOutcome.OK, retried.outcome(), "the retried leased push is accepted"),
                    () -> assertEquals(next, refId(remote.repository(), ref), "the remote moves to the new commit"));
        }

        @Test
        @DisplayName("GDR-CORE-2: a fetch into a working tree never fetches a submodule from a remote outside the check")
        void submodulesNotFetched(@Remote LocalRemote remote) throws Exception {
            var marker = TestCommits.name();
            var submoduleUrl = "https://" + FOREIGN_HOST + "/" + marker + ".git";
            var submodulePath = TestCommits.name();
            // the .gitmodules format of git: section, path and url keys
            var modules = "[submodule \"" + submodulePath + "\"]\n\tpath = " + submodulePath + "\n\turl = "
                    + submoduleUrl + "\n";
            var source = remote.repository();
            ObjectId commit;
            try (ObjectInserter inserter = source.newObjectInserter()) {
                var tree = new TreeFormatter();
                tree.append(Constants.DOT_GIT_MODULES, FileMode.REGULAR_FILE,
                        inserter.insert(Constants.OBJ_BLOB, modules.getBytes(StandardCharsets.UTF_8)));
                tree.append(submodulePath, FileMode.GITLINK, TestCommits.commit(source, null));
                var ident = new PersonIdent(TestCommits.name(), TestCommits.name() + "@example.invalid");
                var builder = new CommitBuilder();
                builder.setTreeId(inserter.insert(tree));
                builder.setAuthor(ident);
                builder.setCommitter(ident);
                builder.setMessage(Generators.letterStrings(3, 30).next());
                commit = inserter.insert(builder);
                inserter.flush();
            }
            TestCommits.setRef(source, branchRef(), commit);
            var root = temp.resolve(TestCommits.name());
            assertTrue(operations.init(root, TestCommits.name()).isOk(), "the working tree is created");
            Files.writeString(root.resolve(Constants.DOT_GIT_MODULES), modules);
            try (Repository parent = new FileRepositoryBuilder().setWorkTree(root.toFile()).build();
                 Git submodule = Git.init().setDirectory(root.resolve(submodulePath).toFile()).call()) {
                TestCommits.addRemote(parent, ORIGIN, remote.uri().toString());
                var config = parent.getConfig();
                config.setString(ConfigConstants.CONFIG_SUBMODULE_SECTION, submodulePath, ConfigConstants.CONFIG_KEY_URL,
                        submoduleUrl);
                config.save();
                TestCommits.addRemote(submodule.getRepository(), ORIGIN, submoduleUrl);
            }

            try (var recording = new RecordingTransportProtocol(marker)) {
                var fetched = operations.fetch(FetchInput.builder().root(root).remote(ORIGIN).refSpecs(List.of())
                        .build());

                assertAll(
                        () -> assertEquals(GitOutcome.OK, fetched.outcome(), "the parent is fetched"),
                        () -> assertFalse(recording.connected(), "the submodule's remote is never contacted"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-2: a transport whose destination is not the checked URL is refused before it connects")
        void destinationChangedAfterCheck(@LocalRepository Repository client) throws Exception {
            var marker = TestCommits.name();
            // the checked URL is local and admitted; the transport JGit opens for it connects elsewhere, as a
            // configuration saved between the check and the use would make it
            TestCommits.addRemote(client, ORIGIN, temp.resolve(marker).toUri().toString());
            var ref = branchRef();
            TestCommits.commitOn(client, ref);
            var elsewhere = new URIish("https://" + FOREIGN_HOST + "/" + marker + ".git");

            try (var recording = new RecordingTransportProtocol(marker, elsewhere)) {
                var fetched = operations.fetch(fetch(client));
                var pushed = operations.push(push(client, ref, null));

                assertAll(
                        () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, fetched.outcome(),
                                "the fetch to the other destination is refused"),
                        () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, pushed.outcome(),
                                "the push to the other destination is refused"),
                        () -> assertTrue(Objects.requireNonNull(fetched.detail()).contains(FOREIGN_HOST),
                                "the refusal names the destination actually used"),
                        () -> assertFalse(recording.connected(), "nothing is contacted"));
            }
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
        @DisplayName("GDR-CORE-7: a lease that is no full object id is refused, not pushed without a lease")
        void invalidLease(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            TestCommits.commitOn(client, ref);

            var result = operations.push(push(client, ref, TestCommits.name()));

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "the lease is refused"),
                    () -> assertEquals("expectedRemoteHead: not a full commit id", result.detail(),
                            "the detail names the field and the reason"),
                    () -> assertNull(remote.repository().exactRef(ref), "nothing is pushed"));
        }

        @Test
        @DisplayName("GDR-CORE-11: a push of the full id of a blob is not found, only a commit id is accepted")
        void blobIdIsNoCommit(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            ObjectId blob;
            try (ObjectInserter inserter = client.newObjectInserter()) {
                blob = inserter.insert(Constants.OBJ_BLOB,
                        Generators.letterStrings(1, 40).next().getBytes(StandardCharsets.UTF_8));
                inserter.flush();
            }
            var tag = GitIdentity.tagRef(TestCommits.name());

            var result = operations.push(PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(blob.name())
                    .remoteRef(tag).build());

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "a blob is no commit"),
                    () -> assertNull(remote.repository().exactRef(tag), "nothing is pushed"));
        }

        @Test
        @DisplayName("GDR-CORE-11: a reference is pushed by its name, not rebound to a reference named like its id")
        void referenceNamedLikeAnId(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            var selected = TestCommits.commitOn(client, ref);
            var other = TestCommits.commit(client, null);
            TestCommits.setRef(client, Constants.R_HEADS + selected.name(), other);

            var result = operations.push(push(client, ref, null));

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the push is accepted"),
                    () -> assertEquals(selected, refId(remote.repository(), ref),
                            "the remote holds the commit the reference selected"));
        }

        @Test
        @DisplayName("GDR-CORE-1: a hook present in the pushing repository is refused and never run")
        void hookRefused(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                    "The default file system supports the posix file attribute view");
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            TestCommits.commitOn(client, ref);
            var marker = temp.resolve(TestCommits.name());
            // the hook name is git's own; the script would create the marker if it ran
            Path hook = dir(client).resolve("hooks/pre-push");
            Files.createDirectories(hook.getParent());
            Files.writeString(hook, "#!/bin/sh\ntouch " + marker + "\n");
            Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
            // a repository-local hooks path, so the developer's core.hooksPath does not decide which hook is found
            var config = client.getConfig();
            config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_HOOKS_PATH,
                    hook.getParent().toAbsolutePath().toString());
            config.save();

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
            // a distinct token: it occurs in a detail or a log record only if the operation put it there
            token = distinctTexts.next();
            url = "https://" + credential.username() + ":" + token + "@" + FOREIGN_HOST + "/" + TestCommits.name()
                    + ".git";
        }

        private UsernamePasswordCredentialsProvider provider() {
            return new UsernamePasswordCredentialsProvider(credential.username(), token.toCharArray());
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
            // a distinct value: it cannot occur by chance in the host or path of the detail
            var query = distinctTexts.next();
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

    @Nested
    @DisplayName("PLAN-02 hardening")
    class Hardening {

        @Test
        @DisplayName("PLAN-02 (b), GDR-CORE-7: a fetch whose tracking reference update meets a held lock is not OK")
        void fetchUpdateNotWritten(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            TestCommits.commitOn(remote.repository(), ref);
            var tracking = Constants.R_REMOTES + ORIGIN + "/" + Repository.shortenRefName(ref);
            var lock = dir(client).resolve(tracking + ".lock");
            Files.createDirectories(lock.getParent());
            Files.createFile(lock);

            var fetched = operations.fetch(FetchInput.builder().root(dir(client)).remote(ORIGIN).refSpecs(List.of())
                    .build());

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, fetched.outcome(), "the unwritten update fails the fetch"),
                    () -> assertEquals(tracking + ": LOCK_FAILURE", fetched.detail(),
                            "the detail names the local reference and the result"),
                    () -> assertNull(client.exactRef(tracking), "the tracking reference is not written"));
        }

        @Test
        @DisplayName("PLAN-02 (c), GDR-CORE-7: the clone's fetch, a fetch and a push run with the transport timeout")
        void transportTimeout(@LocalRepository Repository client) throws Exception {
            var marker = TestCommits.name();
            var url = temp.resolve(marker).toUri().toString();
            TestCommits.addRemote(client, ORIGIN, url);
            var ref = branchRef();
            TestCommits.commitOn(client, ref);
            var clone = CloneInput.builder().gitDir(temp.resolve(TestCommits.name() + ".git")).url(url)
                    .pushRefSpecs(List.of()).build();
            var fetch = FetchInput.builder().root(dir(client)).remote(ORIGIN).refSpecs(List.of()).build();
            var push = PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(ref).remoteRef(ref).build();
            var expected = Optional.of(JGitRepositoryOperations.TRANSPORT_TIMEOUT_SECONDS);

            List<Optional<Integer>> timeouts = new ArrayList<>();
            for (Runnable operation : List.<Runnable>of(() -> operations.cloneIfMissing(clone),
                    () -> operations.fetch(fetch), () -> operations.push(push))) {
                try (var recording = new RecordingTransportProtocol(marker)) {
                    operation.run();
                    timeouts.add(recording.timeout());
                }
            }

            assertEquals(List.of(expected, expected, expected), timeouts,
                    "the clone's fetch, the fetch and the push each set the transport timeout");
        }

        @Test
        @DisplayName("PLAN-02 (e), GDR-NFR-3: the stored origin of a clone carries no user information of the URL")
        void cloneStoresNoUserInformation() {
            var credential = GitGenerators.credentials().next();
            var marker = TestCommits.name();
            var url = "https://" + credential.username() + ":" + new String(credential.token()) + "@" + FOREIGN_HOST
                    + "/" + marker + ".git";
            var httpsOnly = new JGitRepositoryOperations(RemotePolicy.httpsOrigin(URI.create("https://" + FOREIGN_HOST)));
            var input = CloneInput.builder().gitDir(temp.resolve(TestCommits.name() + ".git")).url(url)
                    .pushRefSpecs(List.of()).build();

            try (var recording = new RecordingTransportProtocol(marker)) {
                httpsOnly.cloneIfMissing(input);

                // the clone's fetch opens its transport from the stored origin, so its URL is the stored one
                var stored = recording.uri().orElseThrow();
                assertAll(
                        () -> assertEquals(FOREIGN_HOST, stored.getHost(), "the stored origin keeps the host"),
                        () -> assertNull(stored.getUser(), "the stored origin carries no user"),
                        () -> assertNull(stored.getPass(), "the stored origin carries no password"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-3, GDR-NFR-3: the stored origin of a clone carries no query data and no fragment")
        void cloneStoresNoQueryData(@Remote LocalRemote remote) throws Exception {
            var ref = branchRef();
            var commit = TestCommits.commitOn(remote.repository(), ref);
            // a distinct value: it occurs in the configuration text only if the query or fragment was stored
            var token = distinctTexts.next();
            var gitDir = temp.resolve(TestCommits.name() + ".git");
            var url = remote.uri() + "?access_token=" + token + "#" + token;

            var result = operations.cloneIfMissing(CloneInput.builder().gitDir(gitDir).url(url)
                    .pushRefSpecs(List.of()).build());

            var config = Files.readString(gitDir.resolve(Constants.CONFIG));
            try (Repository clone = openBare(gitDir)) {
                assertAll(
                        () -> assertEquals(GitOutcome.OK, result.outcome(), "the clone fetches from the stored origin"),
                        () -> assertEquals(commit, refId(clone, ref), "the clone mirrors the remote"),
                        () -> assertFalse(config.contains(token),
                                "the configuration file holds neither the query nor the fragment"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-3, GDR-NFR-3: a URL with an upper-case scheme, read as a local path, is refused unstored")
        void upperCaseSchemeRefused() throws Exception {
            var credential = GitGenerators.credentials().next();
            var parent = Files.createDirectories(temp.resolve(TestCommits.name()));
            var gitDir = parent.resolve(TestCommits.name() + ".git");
            // JGit 7.8.0 URIish matches its scheme in lower case only, so this URL is a local path holding the secret
            var url = "HTTPS://" + credential.username() + ":" + new String(credential.token()) + "@" + FOREIGN_HOST
                    + "/" + TestCommits.name() + ".git";

            var result = operations.cloneIfMissing(CloneInput.builder().gitDir(gitDir).url(url)
                    .pushRefSpecs(List.of()).build());

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(),
                            "a URL whose user information cannot be separated is refused"),
                    () -> assertEquals("not a URL", result.detail(), "the detail carries nothing of the URL"),
                    () -> assertEquals(List.of(), entries(parent), "neither a clone nor a staging directory is made"));
        }

        @Test
        @DisplayName("GDR-CORE-7: a fetch whose reference update is no fast-forward is REJECTED, the reference unchanged")
        void fetchUpdateRejected(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            TestCommits.commitOn(remote.repository(), ref);
            // an unrelated history under the same name: the update without force is no fast-forward
            var local = TestCommits.commitOn(client, ref);

            var fetched = operations.fetch(FetchInput.builder().root(dir(client)).remote(ORIGIN)
                    .refSpecs(List.of(ref + ":" + ref)).build());

            assertAll(
                    () -> assertEquals(GitOutcome.REJECTED, fetched.outcome(), "the rejected update rejects the fetch"),
                    () -> assertEquals(ref + ": REJECTED", fetched.detail(),
                            "the detail names the local reference and the result"),
                    () -> assertEquals(local, refId(client, ref), "the local reference is unchanged"));
        }

        @Test
        @DisplayName("GDR-CORE-7: a reference update without a result is FAILED, never taken as written")
        void updateWithoutResult() {
            var localName = branchRef();
            // JGit 7.8.0 TrackingRefUpdate holds no result until its command was applied
            TrackingRefUpdate update = EasyMock.mock(TrackingRefUpdate.class);
            EasyMock.expect(update.getResult()).andStubReturn(null);
            EasyMock.expect(update.getLocalName()).andStubReturn(localName);
            EasyMock.replay(update);

            Optional<GitResult<Void>> result = JGitRepositoryOperations.notWritten(update);

            var notWritten = result.orElseThrow();
            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, notWritten.outcome(), "an update without result fails"),
                    () -> assertEquals(localName + ": no result", notWritten.detail(),
                            "the detail names the local reference"));
        }

        @Test
        @DisplayName("PLAN-02 (g): an anonymous exception class is named by its binary name, never a blank detail")
        void anonymousFailureNamed() {
            var anonymous = new IOException() {

                @Serial
                private static final long serialVersionUID = 1L;
            };

            var result = assertDoesNotThrow(() -> JGitRepositoryOperations.failure(anonymous),
                    "no blank detail is refused by GitResult");

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the failure is reported"),
                    () -> assertEquals(anonymous.getClass().getName(), result.detail(),
                            "the detail is the binary name of the anonymous class"));
        }

        @Test
        @DisplayName("PLAN-02 (g): an empty path is rendered non-blank in the detail of an operation")
        void emptyPathNamed() {
            var input = new ReadFileInput(Path.of(""), ObjectId.zeroId().name(), TestCommits.name());

            var result = assertDoesNotThrow(() -> operations.readFile(input),
                    "no blank detail is refused by GitResult");

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_A_REPOSITORY, result.outcome(), "the empty path is no cache"),
                    () -> assertEquals(JGitRepositoryOperations.BLANK_PATH, result.detail(),
                            "the empty path is rendered by its fixed rendering"),
                    () -> assertEquals(JGitRepositoryOperations.BLANK_PATH,
                            JGitRepositoryOperations.shown(Path.of("")), "Path.of(\"\") renders blank"));
        }
    }

    @Nested
    @DisplayName("PLAN-02 residuals (c), (e), (f)")
    class Residuals {

        /** The host the policy of the HTTP test admits; nothing connects to it. */
        private static final String ADMITTED_HOST = "admitted.example.invalid";

        private static void assumePosix() {
            assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                    "The default file system supports the posix file attribute view");
        }

        /** Writes an executable shell script that creates the marker when it runs. */
        private static void markerScript(Path script, Path marker) throws IOException {
            Files.createDirectories(script.getParent());
            Files.writeString(script, "#!/bin/sh\ntouch " + marker + "\n");
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        /** A URL of a local path whose last segment is the marker an intercepting protocol handles. */
        private String markerUrl(String marker) {
            return temp.resolve(marker).toUri().toString();
        }

        @Test
        @DisplayName("PLAN-02 (c), GDR-CORE-1: a native pre-receive hook of the remote is never run by a push")
        void remotePreReceiveHookNeverRun(@Remote LocalRemote remote, @LocalRepository Repository client)
                throws Exception {
            // JGit 7.8.0 runs native hooks only through GitHook (FS.runHookIfPresent), whose subclasses are the
            // client-side pre-commit, commit-msg, post-commit and pre-push hooks; ReceivePack runs Java hooks only
            assumePosix();
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            var commit = TestCommits.commitOn(client, ref);
            var marker = temp.resolve(TestCommits.name());
            Path hook = dir(remote.repository()).resolve("hooks/pre-receive");
            markerScript(hook, marker);
            var config = remote.repository().getConfig();
            config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_HOOKS_PATH,
                    hook.getParent().toAbsolutePath().toString());
            config.save();

            var result = operations.push(PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(ref)
                    .remoteRef(ref).build());

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the push is accepted"),
                    () -> assertEquals(commit, refId(remote.repository(), ref), "the remote holds the commit"),
                    () -> assertFalse(Files.exists(marker), "the remote's pre-receive hook is never run"));
        }

        @Test
        @DisplayName("PLAN-02 (c), GDR-CORE-1: a configured receive-pack program of a local remote is never run")
        void receivePackProgramNeverRun(@Remote LocalRemote remote, @LocalRepository Repository client)
                throws Exception {
            // JGit 7.8.0 PushCommand keeps its own receivePack, RemoteConfig.DEFAULT_RECEIVE_PACK unless set, and
            // passes it to Transport.setOptionReceivePack, which replaces remote.<name>.receivepack; TransportLocal
            // therefore pushes through its in-process ReceivePack and forks nothing
            assumePosix();
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            var commit = TestCommits.commitOn(client, ref);
            var marker = temp.resolve(TestCommits.name());
            var program = temp.resolve(TestCommits.name());
            markerScript(program, marker);
            var config = client.getConfig();
            config.setString("remote", ORIGIN, "receivepack", program.toAbsolutePath().toString());
            config.save();

            var result = operations.push(PushInput.builder().root(dir(client)).remote(ORIGIN).localRef(ref)
                    .remoteRef(ref).build());

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the push runs in process"),
                    () -> assertEquals(commit, refId(remote.repository(), ref), "the remote holds the commit"),
                    () -> assertFalse(Files.exists(marker), "the configured program is never run"));
        }

        @Test
        @DisplayName("PLAN-02 (c), GDR-CORE-1: a configured upload-pack program of a local remote is refused, never run")
        void uploadPackProgramRefused(@Remote LocalRemote remote, @LocalRepository Repository client)
                throws Exception {
            // JGit 7.8.0 FetchCommand keeps remote.<name>.uploadpack (Transport.applyConfig), and TransportLocal.openFetch
            // forks it through local.getFS().runInShell when it is anything but git-upload-pack; RefusingFS refuses that
            assumePosix();
            TestCommits.addRemote(client, ORIGIN, remote.uri().toString());
            var ref = branchRef();
            TestCommits.commitOn(remote.repository(), ref);
            var marker = temp.resolve(TestCommits.name());
            var program = temp.resolve(TestCommits.name());
            markerScript(program, marker);
            var config = client.getConfig();
            config.setString("remote", ORIGIN, "uploadpack", program.toAbsolutePath().toString());
            config.save();

            var result = operations.fetch(FetchInput.builder().root(dir(client)).remote(ORIGIN).refSpecs(List.of())
                    .build());

            assertAll(
                    () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, result.outcome(), "the program is refused"),
                    () -> assertNull(client.exactRef(Constants.R_REMOTES + ORIGIN + "/" + Repository.shortenRefName(ref)),
                            "nothing is fetched"),
                    () -> assertFalse(Files.exists(marker), "the program is never run"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    GitCoreLogMessages.WARN.CAPABILITY_REFUSED.resolveIdentifierString());
        }

        @Test
        @DisplayName("PLAN-02 (e): http.extraHeader and http.cookieFile of the repository configuration reach the remote")
        void extraHeaderAndCookieFileReachRemote(@LocalRepository Repository repository) throws Exception {
            // pins JGit 7.8.0 TransportHttp: every request carries http.getExtraHeaders() and the cookies of
            // http.cookieFile that match the URL; the library neither strips nor refuses them
            var headerName = "X-" + TestCommits.name();
            var headerValue = TestCommits.name();
            var cookieName = TestCommits.name();
            var cookieValue = TestCommits.name();
            var cookies = temp.resolve(TestCommits.name());
            long expires = Instant.now().plus(Duration.ofDays(365)).getEpochSecond();
            Files.writeString(cookies, String.join("\t", ADMITTED_HOST, "FALSE", "/", "TRUE",
                    Long.toString(expires), cookieName, cookieValue) + "\n");
            var config = repository.getConfig();
            config.setString(HttpConfig.HTTP, null, HttpConfig.EXTRA_HEADER, headerName + ": " + headerValue);
            config.setString(HttpConfig.HTTP, null, HttpConfig.COOKIE_FILE_KEY, cookies.toAbsolutePath().toString());
            config.save();
            var policy = RemotePolicy.httpsOrigin(URI.create("https://" + ADMITTED_HOST));
            List<String> sent = new ArrayList<>();

            try (var transport = org.eclipse.jgit.transport.Transport.open(repository,
                         new URIish("https://" + ADMITTED_HOST + "/" + TestCommits.name() + ".git"))) {
                ((TransportHttp) transport).setHttpConnectionFactory(new RecordingConnectionFactory(sent));
                JGitRepositoryOperations.guard(transport, policy);

                assertThrows(TransportException.class, transport::openFetch,
                        "the recording connection answers nothing");
            }

            assertAll(
                    () -> assertTrue(sent.contains(headerName + ": " + headerValue),
                            "the configured extra header is sent"),
                    () -> assertTrue(sent.stream().anyMatch(header -> header.startsWith("Cookie: ")
                                    && header.contains(cookieName) && header.contains(cookieValue)),
                            "the cookie of the configured cookie file is sent"));
        }

        @Test
        @DisplayName("PLAN-02 (f), GDR-CORE-3: a clone published concurrently is opened and the staging is removed")
        void publishLosesRace(@Remote LocalRemote remote) throws Exception {
            var ref = branchRef();
            TestCommits.commitOn(remote.repository(), ref);
            // the marker occurs in the delegate's own path, the case that once recursed into a StackOverflowError:
            // the intercepting protocol must not select itself for the fetch from its delegate
            var marker = Path.of(remote.uri().getPath()).getFileName().toString();
            var parent = Files.createDirectories(temp.resolve(TestCommits.name()));
            var gitDir = parent.resolve(TestCommits.name() + ".git");
            var concurrentRef = branchRef();
            var concurrentCommit = new AtomicReference<ObjectId>();

            try (var intercepting = new InterceptingTransportProtocol(marker, remote.uri(), local -> {
                     try (Git concurrent = Git.init().setBare(true).setGitDir(gitDir.toFile()).call()) {
                         concurrentCommit.set(TestCommits.commitOn(concurrent.getRepository(), concurrentRef));
                     } catch (GitAPIException e) {
                         throw new IOException(e);
                     }
                 })) {
                var result = operations.cloneIfMissing(CloneInput.builder().gitDir(gitDir).url(markerUrl(marker))
                        .pushRefSpecs(List.of()).build());

                try (Repository published = openBare(gitDir)) {
                    assertAll(
                            () -> assertEquals(GitOutcome.OK, result.outcome(), "the published clone is opened"),
                            () -> assertEquals(gitDir.toRealPath(), Objects.requireNonNull(result.value()).gitDir(),
                                    "the result is the clone's place"),
                            () -> assertEquals(concurrentCommit.get(), refId(published, concurrentRef),
                                    "the clone's place holds the concurrently published clone"),
                            () -> assertNull(published.exactRef(ref), "this attempt's clone was not published"),
                            () -> assertEquals(List.of(gitDir.getFileName().toString()), entries(parent),
                                    "the staging directory is removed"));
                }
            }
            assertFalse(TestLoggerFactory.getTestHandler().getRecordsAsString()
                            .contains(GitCoreLogMessages.INFO.CLONE_CREATED.resolveIdentifierString()),
                    "the losing attempt logs no created clone");
        }

        @Test
        @DisplayName("PLAN-02 (f), GDR-CORE-3: a clone whose mirrored references are not written is not published")
        void mirroredRefsNotWritten(@Remote LocalRemote remote) throws Exception {
            var ref = branchRef();
            TestCommits.commitOn(remote.repository(), ref);
            var marker = TestCommits.name();
            var parent = Files.createDirectories(temp.resolve(TestCommits.name()));
            var gitDir = parent.resolve(TestCommits.name() + ".git");

            // a held lock of the one mirrored reference makes its update end LOCK_FAILURE (JGit 7.8.0
            // PackedBatchRefUpdate updates a single reference one by one, RefDirectoryUpdate), while FetchProcess
            // still returns its FetchResult
            try (var intercepting = new InterceptingTransportProtocol(marker, remote.uri(), local -> {
                     var lock = local.getDirectory().toPath().resolve(ref + ".lock");
                     Files.createDirectories(lock.getParent());
                     Files.createFile(lock);
                 })) {
                var result = operations.cloneIfMissing(CloneInput.builder().gitDir(gitDir).url(markerUrl(marker))
                        .pushRefSpecs(List.of()).build());

                assertAll(
                        () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the incomplete clone fails"),
                        () -> assertTrue(Objects.requireNonNull(result.detail()).endsWith(": IOException"),
                                "the detail names the failure class"),
                        () -> assertFalse(Files.exists(gitDir), "no clone is published"),
                        () -> assertEquals(List.of(), entries(parent), "the staging directory is removed"));
            }
        }

        @Test
        @DisplayName("PLAN-02 (f), GDR-NFR-3: a refused redirect is logged REMOTE_REFUSED by its host only")
        void refusedRedirectLogged(@Remote LocalRemote remote, @LocalRepository Repository client) throws Exception {
            // distinct values: found in no log text by chance, so the absence assertions below hold for every seed
            var user = distinctTexts.next();
            var token = distinctTexts.next();
            var redirect = new URIish("https://" + user + ":" + token + "@" + FOREIGN_HOST + "/"
                    + TestCommits.name() + ".git");
            var marker = TestCommits.name();
            TestCommits.addRemote(client, ORIGIN, markerUrl(marker));

            try (var intercepting = new InterceptingTransportProtocol(marker, remote.uri(), local -> {
                     // what PolicyCheckedConnectionFactory throws for a redirect to a host outside the policy
                     throw new JGitRepositoryOperations.RemoteRefusedException(redirect, true);
                 })) {
                var result = operations.fetch(FetchInput.builder().root(dir(client)).remote(ORIGIN)
                        .refSpecs(List.of()).build());

                var records = TestLoggerFactory.getTestHandler().getRecordsAsString();
                assertAll(
                        () -> assertEquals(GitResult.of(GitOutcome.REFUSED_BY_POLICY,
                                        "redirect to " + FOREIGN_HOST + " is outside the remote policy"), result,
                                "the redirect is refused by its host"),
                        () -> assertFalse(records.contains(token), "no log record carries the token"),
                        () -> assertFalse(records.contains(user), "no log record carries the user"));
                LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                        GitCoreLogMessages.WARN.REMOTE_REFUSED.resolveIdentifierString());
                LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, FOREIGN_HOST);
            }
        }
    }

    /**
     * An HTTP connection factory for tests that connects nowhere: each connection records every request header as
     * {@code name: value} and fails as soon as the request would be sent.
     */
    private static final class RecordingConnectionFactory implements HttpConnectionFactory {

        private final List<String> sent;

        RecordingConnectionFactory(List<String> sent) {
            this.sent = sent;
        }

        @Override
        public HttpConnection create(URL url) throws IOException {
            return create(url, Proxy.NO_PROXY);
        }

        /**
         * A nice EasyMock double of the connection: every call it does not record answers the type's default, as a
         * connection that was never opened.
         */
        @Override
        public HttpConnection create(URL url, @Nullable Proxy proxy) throws IOException {
            HttpConnection connection = EasyMock.niceMock(HttpConnection.class);
            connection.setRequestProperty(EasyMock.anyString(), EasyMock.anyString());
            EasyMock.expectLastCall().andStubAnswer(this::recordHeader);
            EasyMock.expect(connection.getURL()).andStubReturn(url);
            var nowhere = new IOException("the recording connection connects nowhere");
            connection.connect();
            EasyMock.expectLastCall().andStubThrow(nowhere);
            EasyMock.expect(connection.getResponseCode()).andStubThrow(nowhere);
            EasyMock.expect(connection.getInputStream()).andStubThrow(nowhere);
            EasyMock.expect(connection.getOutputStream()).andStubThrow(nowhere);
            EasyMock.replay(connection);
            return connection;
        }

        /** Records the header of the current call to the double as {@code name: value}. */
        private @Nullable Object recordHeader() {
            Object[] header = EasyMock.getCurrentArguments();
            sent.add(header[0] + ": " + header[1]);
            return null;
        }
    }
}
