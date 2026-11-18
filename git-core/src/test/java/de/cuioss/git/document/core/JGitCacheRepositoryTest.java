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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.stream.Stream;


import de.cuioss.git.document.core.RepositoryOperations.FetchCommitInput;
import de.cuioss.git.document.core.RepositoryOperations.ReadFileInput;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The cache repository operations (GDR-CORE-8) against a bare source repository of the {@code git-testing} fixtures,
 * reached over {@code file://} and configured to serve a commit by its id: a bare cache is created once, the pinned
 * commit is fetched by id, and its files are read raw, an LFS pointer as the pointer; an unserved commit, a missing
 * path and a remote outside the policy end in their outcomes.
 */
@EnableGeneratorController
@DisplayName("JGitRepositoryOperations cache repository (GDR-CORE-8)")
class JGitCacheRepositoryTest {

    /** The directory of the fixture files of a source repository. */
    static final String DATA = "data";
    /** The file name of the plain file of a source repository. */
    static final String CSV = "test.csv";
    /** The file name of the LFS pointer of a source repository. */
    static final String POINTER = "big.jsonl";
    /** The length of an abbreviated commit id: a boundary of the identity rule. */
    private static final int ABBREVIATED = 12;

    @TempDir
    Path temp;

    private JGitRepositoryOperations operations;
    private Path source;
    private Path cache;
    private String commit;
    private String csv;
    private String pointer;

    /**
     * Generates a full commit id.
     *
     * @return 40 lower-case hexadecimal digits
     */
    static String commitId() {
        return Generators.strings("0123456789abcdef", 40, 40).next();
    }

    /**
     * Generates one line of text.
     *
     * @return generated letters ended by a line feed
     */
    static String line() {
        return Generators.letterStrings(1, 40).next() + "\n";
    }

    /**
     * Generates an LFS pointer; its three keys and their order are the wire format of the Git LFS specification, the
     * object id and the size are generated.
     *
     * @return the pointer text
     */
    static String lfsPointer() {
        return "version https://git-lfs.github.com/spec/v1\noid sha256:"
                + Generators.strings("0123456789abcdef", 64, 64).next() + "\nsize "
                + Generators.integers(1, 100_000).next() + "\n";
    }

    /**
     * Fills a source repository with one commit holding a plain file and an LFS pointer below {@value #DATA}, and
     * configures it to serve any commit by its id.
     *
     * @param repository the source repository
     * @param csv        the content of the plain file
     * @param pointer    the content of the LFS pointer
     * @return the id of the commit
     * @throws IOException if the objects or the configuration cannot be written
     */
    static String fillSource(Repository repository, String csv, String pointer) throws IOException {
        ObjectId commitId;
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var data = new TreeFormatter();
            data.append(POINTER, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, pointer.getBytes(UTF_8)));
            data.append(CSV, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, csv.getBytes(UTF_8)));
            var root = new TreeFormatter();
            root.append(DATA, FileMode.TREE, inserter.insert(data));
            var ident = new PersonIdent(TestCommits.name(), TestCommits.name() + "@example.invalid");
            var builder = new CommitBuilder();
            builder.setTreeId(inserter.insert(root));
            builder.setAuthor(ident);
            builder.setCommitter(ident);
            builder.setMessage(Generators.letterStrings(3, 30).next());
            commitId = inserter.insert(builder);
            inserter.flush();
        }
        TestCommits.setRef(repository, GitIdentity.branchRef(TestCommits.name()), commitId);
        var config = repository.getConfig();
        // the configuration key by which a remote serves any commit by its id
        config.setBoolean("uploadpack", null, "allowAnySHA1InWant", true);
        config.save();
        return commitId.name();
    }

    @BeforeEach
    void repositories(@LocalRepository Repository sourceRepository) throws IOException {
        operations = new JGitRepositoryOperations(RemotePolicy.localOnly());
        csv = line() + line();
        pointer = lfsPointer();
        source = sourceRepository.getDirectory().toPath();
        commit = fillSource(sourceRepository, csv, pointer);
        cache = temp.resolve(TestCommits.name()).resolve(TestCommits.name() + ".git");
    }

    private String url() {
        return source.toUri().toString();
    }

    private String goneUrl() {
        return temp.resolve(TestCommits.name()).toUri().toString();
    }

    private GitResult<String> fetch(String url, String id, RemotePolicy policy) {
        return operations.fetchCommit(new FetchCommitInput(cache, url, id, policy));
    }

    private GitResult<byte[]> read(String id, String path) {
        return operations.readFile(new ReadFileInput(cache, id, path));
    }

    @Nested
    @DisplayName("initCache")
    class InitCache {

        @Test
        @DisplayName("GDR-CORE-8: creates a bare repository, and opens it again unchanged")
        void idempotent() {
            var first = operations.initCache(cache);
            var second = operations.initCache(cache);

            assertAll(
                    () -> assertEquals(GitOutcome.OK, first.outcome(), "the first initCache creates the cache"),
                    () -> assertEquals(first, second, "a second initCache returns the same result"),
                    () -> assertEquals(cache.toRealPath(), first.value(), "the result is the canonical cache path"),
                    () -> assertTrue(Files.isDirectory(cache.resolve("objects")),
                            "the bare cache holds an objects directory"),
                    () -> assertFalse(Files.exists(cache.resolve(Constants.DOT_GIT)),
                            "a bare cache holds no .git directory"));
        }

        @Test
        @DisplayName("refuses a directory that holds anything but a repository")
        void notARepository() throws Exception {
            Files.createDirectories(cache);
            Files.writeString(cache.resolve(TestCommits.name() + ".txt"), line());

            assertEquals(GitOutcome.NOT_A_REPOSITORY, operations.initCache(cache).outcome(),
                    "a directory of plain files is no cache");
        }

        @Test
        @DisplayName("refuses the git directory of a working tree, which is no bare repository")
        void workingTree() {
            var root = temp.resolve(TestCommits.name());
            assertTrue(operations.init(root, TestCommits.name()).isOk(), "the working-tree repository is created");

            assertEquals(GitOutcome.NOT_A_REPOSITORY, operations.initCache(root.resolve(Constants.DOT_GIT)).outcome(),
                    "the git directory of a working tree is no cache");
        }

        @Test
        @DisplayName("a cache that cannot be created, below a regular file, is a failure")
        void cannotCreate() throws Exception {
            var file = Files.writeString(temp.resolve(TestCommits.name()), line());

            var result = operations.initCache(file.resolve(TestCommits.name() + ".git"));

            assertEquals(GitOutcome.FAILED, result.outcome(), "a cache below a regular file cannot be made");
        }
    }

    @Nested
    @DisplayName("fetchCommit and readFile")
    class FetchAndRead {

        @BeforeEach
        void initCache() {
            assertTrue(operations.initCache(cache).isOk(), "the cache repository is initialised");
        }

        @Test
        @DisplayName("GDR-CORE-8: fetches exactly the pinned commit by its id and reads a plain file and an LFS pointer raw")
        void fetchesAndReads() throws Exception {
            var fetched = fetch(url(), commit, RemotePolicy.localOnly());

            var file = read(commit, DATA + "/" + CSV);
            var lfs = read(commit, DATA + "/" + POINTER);

            try (Repository repository = new FileRepositoryBuilder().setGitDir(cache.toFile()).setBare().build()) {
                var refs = repository.getRefDatabase().getRefs();
                assertAll(
                        () -> assertEquals(GitOutcome.OK, fetched.outcome(), "the pinned commit is fetched by its id"),
                        () -> assertEquals(commit, fetched.value(), "the fetch returns the pinned commit id"),
                        () -> assertEquals(1, refs.size(), "the cache holds exactly one reference"),
                        () -> assertEquals(JGitRepositoryOperations.PINNED_REFS + commit, refs.getFirst().getName(),
                                "the one reference pins the fetched commit"),
                        () -> assertArrayEquals(csv.getBytes(UTF_8), file.value(),
                                "the plain file is read as it is stored"),
                        () -> assertArrayEquals(pointer.getBytes(UTF_8), lfs.value(),
                                "the LFS file is read as its pointer"));
            }
        }

        @Test
        @DisplayName("GDR-CORE-8: a commit already in the cache is served without contacting the remote")
        void cachedCommit() {
            assertTrue(fetch(url(), commit, RemotePolicy.localOnly()).isOk(), "the first fetch fills the cache");

            // a gone remote: contacting it would be unreachable, so OK proves no contact
            var again = fetch(goneUrl(), commit, RemotePolicy.localOnly());

            assertAll(
                    () -> assertEquals(GitOutcome.OK, again.outcome(), "a cached commit needs no remote"),
                    () -> assertTrue(read(commit, DATA + "/" + CSV).isOk(), "the cached commit's file is readable"));
        }

        @Test
        @DisplayName("GDR-CORE-8: a commit the remote does not serve is unserved")
        void unservedCommit() {
            var fetched = fetch(url(), commitId(), RemotePolicy.localOnly());

            assertEquals(GitOutcome.UNSERVED, fetched.outcome(), "a commit the remote does not serve is unserved");
        }

        @Test
        @DisplayName("GDR-CORE-11: an abbreviated commit id is not found, nothing is fetched")
        void abbreviatedCommit() {
            var fetched = fetch(url(), commit.substring(0, ABBREVIATED), RemotePolicy.localOnly());

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, fetched.outcome(), "an abbreviated id is not found"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, read(commit, DATA + "/" + CSV).outcome(),
                            "nothing was fetched into the cache"));
        }

        @Test
        @DisplayName("GDR-CORE-8: a remote that does not answer is unreachable")
        void missingRemote() {
            var fetched = fetch(goneUrl(), commit, RemotePolicy.localOnly());

            assertEquals(GitOutcome.UNREACHABLE, fetched.outcome(), "a remote that does not answer is unreachable");
        }

        @Test
        @DisplayName("GDR-CORE-8: a commit not fetched into the cache is not found to readFile")
        void readUnfetchedCommit() {
            assertEquals(GitOutcome.NOT_FOUND, read(commit, DATA + "/" + CSV).outcome(),
                    "an unfetched commit is not found to readFile");
        }

        @Test
        @DisplayName("GDR-CORE-8: a path missing at the commit, or a directory, is not found")
        void missingPath() {
            assertTrue(fetch(url(), commit, RemotePolicy.localOnly()).isOk(), "the pinned commit is fetched");

            // the extension of no fixture file, so a generated name never matches a fixture file
            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND,
                            read(commit, DATA + "/" + TestCommits.name() + ".absent").outcome(),
                            "a path absent at the commit is not found"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, read(commit, DATA).outcome(),
                            "a directory is no file and is not found"));
        }

        @Test
        @DisplayName("a file whose object the cache lost is a failure to readFile, not a missing path")
        void lostObject() throws Exception {
            var fileName = TestCommits.name() + ".csv";
            ObjectId blob;
            ObjectId cached;
            try (Repository repository = new FileRepositoryBuilder().setGitDir(cache.toFile()).setBare().build();
                 ObjectInserter inserter = repository.newObjectInserter()) {
                blob = inserter.insert(Constants.OBJ_BLOB, csv.getBytes(UTF_8));
                var tree = new TreeFormatter();
                tree.append(fileName, FileMode.REGULAR_FILE, blob);
                var commitBuilder = new CommitBuilder();
                commitBuilder.setTreeId(inserter.insert(tree));
                var ident = new PersonIdent(TestCommits.name(), TestCommits.name() + "@example.invalid");
                commitBuilder.setAuthor(ident);
                commitBuilder.setCommitter(ident);
                commitBuilder.setMessage(Generators.letterStrings(3, 30).next());
                cached = inserter.insert(commitBuilder);
                inserter.flush();
            }
            // the loose object layout of git: a directory of the first two hex digits, a file of the rest
            Files.delete(cache.resolve("objects").resolve(blob.name().substring(0, 2))
                    .resolve(blob.name().substring(2)));

            var result = read(cached.name(), fileName);

            assertEquals(GitOutcome.FAILED, result.outcome(), "a lost object is a failure, not a missing path");
        }

        @Test
        @DisplayName("a cache that is no repository is refused")
        void cacheIsNoRepository() throws Exception {
            var plain = Files.createDirectories(temp.resolve(TestCommits.name()));

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_A_REPOSITORY, operations.fetchCommit(
                                    new FetchCommitInput(plain, url(), commit, RemotePolicy.localOnly())).outcome(),
                            "fetchCommit refuses a cache that is no repository"),
                    () -> assertEquals(GitOutcome.NOT_A_REPOSITORY,
                            operations.readFile(new ReadFileInput(plain, commit, DATA + "/" + CSV)).outcome(),
                            "readFile refuses a cache that is no repository"));
        }
    }

    /**
     * The remotes outside a policy; the URLs and their schemes are the contract of the remote-policy grammar.
     *
     * @return the name of the case, the URL and the policy
     */
    static Stream<Arguments> refusedRemotes() {
        return Stream.of(
                Arguments.of("plain http under the https policy", "http://github.com/example/alpha",
                        RemotePolicy.httpsOrigin(URI.create("https://github.com"))),
                Arguments.of("a foreign https origin", "https://gitlab.example.com/example/alpha",
                        RemotePolicy.httpsOrigin(URI.create("https://github.com"))),
                Arguments.of("a network remote under the local policy", "https://github.com/example/alpha",
                        RemotePolicy.localOnly()),
                Arguments.of("an ssh remote under the local policy", "ssh://git@github.com/example/alpha",
                        RemotePolicy.localOnly()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedRemotes")
    @DisplayName("GDR-CORE-2: a remote the policy does not admit is refused before anything is sent")
    void remoteOutsidePolicy(String name, String url, RemotePolicy policy) {
        assertTrue(operations.initCache(cache).isOk(), "the cache repository is initialised");

        var fetched = fetch(url, commit, policy);

        assertAll(
                () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, fetched.outcome(),
                        "a remote outside the policy is refused"),
                () -> assertFalse(Objects.requireNonNull(fetched.detail()).contains("git@"),
                        "the detail renders no user information"),
                () -> assertEquals(GitOutcome.NOT_FOUND, read(commit, DATA + "/" + CSV).outcome(),
                        "nothing was fetched into the cache"));
    }
}
