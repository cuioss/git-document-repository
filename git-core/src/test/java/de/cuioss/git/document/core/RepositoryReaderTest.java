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

import static de.cuioss.git.document.core.InCoreMergerTest.annotatedTag;
import static de.cuioss.git.document.core.InCoreMergerTest.commit;
import static de.cuioss.git.document.core.InCoreMergerTest.executable;
import static de.cuioss.git.document.core.InCoreMergerTest.file;
import static de.cuioss.git.document.core.InCoreMergerTest.nestedPath;
import static de.cuioss.git.document.core.InCoreMergerTest.with;
import static de.cuioss.git.document.core.InCoreWriterTest.blobId;
import static de.cuioss.git.document.core.InCoreWriterTest.content;
import static de.cuioss.git.document.core.InCoreWriterTest.parse;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import de.cuioss.git.document.core.InCoreMergerTest.Entry;
import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import de.cuioss.git.document.core.RepositoryReader.ChangeType;
import de.cuioss.git.document.core.RepositoryReader.FileKind;
import de.cuioss.git.document.core.RepositoryReader.PathChange;
import de.cuioss.git.document.core.RepositoryReader.SnapshotFile;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Reading without a working tree (GDR-CORE-9) against the in-memory fixture of {@code git-testing}: the snapshot of a
 * branch, a lightweight and an annotated tag and a full commit id, the refusal of an ambiguous, abbreviated or unknown
 * revision; the history of a path newest first, empty for a path never changed and bounded by {@code maxCount}; the
 * paths added, modified in content or mode and deleted between two commits, nested paths, identical trees and ids that
 * are no commit.
 */
@EnableGeneratorController
@DisplayName("RepositoryReader (GDR-CORE-9)")
class RepositoryReaderTest {

    private final RepositoryReader reader = new RepositoryReader();

    private static SnapshotFile snapshotFile(String path, Entry entry) {
        var kind = FileMode.EXECUTABLE_FILE.equals(entry.mode()) ? FileKind.EXECUTABLE : FileKind.REGULAR;
        return new SnapshotFile(path, kind, blobId(entry.content()).name());
    }

    /** The regular and executable files of a fixture tree as the snapshot reports them, in path order. */
    private static List<SnapshotFile> expectedFiles(Map<String, Entry> files) {
        return new TreeMap<>(files).entrySet().stream()
                .filter(file -> !FileMode.SYMLINK.equals(file.getValue().mode()))
                .map(file -> snapshotFile(file.getKey(), file.getValue()))
                .toList();
    }

    private static Map<String, ChangeType> byPath(List<PathChange> changes) {
        return changes.stream().collect(Collectors.toMap(PathChange::path, PathChange::type));
    }

    @Nested
    @DisplayName("snapshot")
    class Snapshot {

        @Test
        @DisplayName("GDR-CORE-9: a branch by its short and full name yields the commit, its tree and every file")
        void branch(@InMemory Repository repository) throws Exception {
            var files = Map.of(TestCommits.name(), file(), nestedPath(), executable(content()),
                    TestCommits.name(), new Entry(FileMode.SYMLINK, TestCommits.name()));
            var commit = commit(repository, files);
            var branch = TestCommits.name();
            TestCommits.setRef(repository, GitIdentity.branchRef(branch), commit);

            var byShortName = reader.snapshot(repository, branch);
            var byFullName = reader.snapshot(repository, GitIdentity.branchRef(branch));

            var snapshot = Objects.requireNonNull(byShortName.value(), "the snapshot is read");
            assertAll(
                    () -> assertEquals(GitOutcome.OK, byShortName.outcome(), "the branch is read"),
                    () -> assertEquals(commit.name(), snapshot.commitId(), "the commit is the branch's"),
                    () -> assertEquals(parse(repository, commit.name()).getTree().name(), snapshot.treeId(),
                            "the tree is the commit's"),
                    () -> assertEquals(expectedFiles(files), snapshot.files(),
                            "every regular and executable file, no symbolic link"),
                    () -> assertEquals(byShortName, byFullName, "the full name reads the same snapshot"));
        }

        @Test
        @DisplayName("GDR-CORE-9: a lightweight and an annotated tag are read as the tagged commit")
        void tags(@InMemory Repository repository) throws Exception {
            var files = Map.of(TestCommits.name(), file());
            var commit = commit(repository, files);
            var lightweight = TestCommits.name();
            var annotated = TestCommits.name();
            TestCommits.setRef(repository, Constants.R_TAGS + lightweight, commit);
            TestCommits.setRef(repository, Constants.R_TAGS + annotated, annotatedTag(repository, commit));

            assertAll(Stream.of(lightweight, annotated).<Executable>map(tag -> () -> {
                var result = reader.snapshot(repository, tag);
                var snapshot = Objects.requireNonNull(result.value(), "the tag is read");
                assertAll(
                        () -> assertEquals(commit.name(), snapshot.commitId(), "the tag is peeled to the commit"),
                        () -> assertEquals(expectedFiles(files), snapshot.files(), "the commit's files"));
            }));
        }

        @Test
        @DisplayName("GDR-CORE-9: a full commit id is read as itself")
        void fullCommitId(@InMemory Repository repository) throws Exception {
            var files = Map.of(TestCommits.name(), file(), TestCommits.name(), file());
            var commit = commit(repository, files);

            var result = reader.snapshot(repository, commit.name());

            var snapshot = Objects.requireNonNull(result.value(), "the commit is read");
            assertAll(
                    () -> assertEquals(commit.name(), snapshot.commitId(), "the commit is the one named"),
                    () -> assertEquals(expectedFiles(files), snapshot.files(), "the commit's files"));
        }

        @Test
        @DisplayName("GDR-CORE-11: an ambiguous, abbreviated, unknown or expression revision is refused")
        void refusedRevisions(@InMemory Repository repository) throws Exception {
            var commit = commit(repository, Map.of(TestCommits.name(), file()));
            var ambiguous = TestCommits.name();
            TestCommits.setRef(repository, GitIdentity.branchRef(ambiguous), commit);
            TestCommits.setRef(repository, Constants.R_TAGS + ambiguous, commit);
            var branch = TestCommits.name();
            TestCommits.setRef(repository, GitIdentity.branchRef(branch), commit);

            var ambiguity = reader.snapshot(repository, ambiguous);
            assertAll(
                    () -> assertEquals(GitOutcome.AMBIGUOUS, ambiguity.outcome(), "an ambiguous revision"),
                    () -> assertTrue(Objects.requireNonNull(ambiguity.detail()).startsWith("revision names "),
                            "the detail names the field and the candidates"),
                    () -> assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "revision: abbreviated commit id"),
                            reader.snapshot(repository, commit.abbreviate(12).name()), "an abbreviated id"),
                    () -> assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "revision: not found"),
                            reader.snapshot(repository, TestCommits.name()), "an unknown reference"),
                    () -> assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "revision: not found"),
                            reader.snapshot(repository, ObjectId.zeroId().name()), "an unknown commit id"),
                    () -> assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "revision: not found"),
                            reader.snapshot(repository, branch + "~1"), "a revision expression"));
        }

        @Test
        @DisplayName("GDR-CORE-11: the full id of an annotated tag is a pin that reaches another commit")
        void pinMismatch(@InMemory Repository repository) throws Exception {
            var commit = commit(repository, Map.of(TestCommits.name(), file()));
            var tag = annotatedTag(repository, commit);

            var result = reader.snapshot(repository, tag.name());

            assertEquals(GitResult.of(GitOutcome.IDENTITY_MISMATCH, "the pin " + tag.name() + " reached "
                    + commit.name()), result, "the pin does not hold");
        }
    }

    @Nested
    @DisplayName("history")
    class History {

        private final InCoreWriter writer = new InCoreWriter();

        /** Generates an identity at the given time. */
        private static PersonIdent person(Instant when) {
            return new PersonIdent(TestCommits.name(), TestCommits.name() + "@example.invalid", when, ZoneOffset.UTC);
        }

        /**
         * Writes four commits in a line; the first and the third change the path, the others another file. Each commit
         * is one hour after its parent, so newest first is well defined.
         *
         * @return the ids of the commits, oldest first
         */
        private List<String> fourCommits(Repository repository, String path, String other) {
            List<List<TreeEdit>> edits = List.of(
                    List.of(InCoreWriterTest.put(path, content()), InCoreWriterTest.put(other, content())),
                    List.of(InCoreWriterTest.put(other, content())),
                    List.of(InCoreWriterTest.put(path, content())),
                    List.of(InCoreWriterTest.put(other, content())));
            var start = Instant.ofEpochSecond(Generators.integers(0, Integer.MAX_VALUE / 2).next());
            List<String> commits = new ArrayList<>();
            for (List<TreeEdit> edit : edits) {
                var when = start.plusSeconds(3600L * commits.size());
                List<String> parents = commits.isEmpty() ? List.of() : List.of(commits.getLast());
                var result = writer.write(repository, parents,
                        new CommitMetadata(person(when), person(when), InCoreWriterTest.message(), null), edit);
                commits.add(Objects.requireNonNull(result.value(), "the fixture commit is written"));
            }
            return commits;
        }

        @Test
        @DisplayName("GDR-CORE-9: the commits that changed a path, newest first, with author, committer, times, message")
        void changedPath(@InMemory Repository fixture) throws Exception {
            var path = TestCommits.name() + "/" + TestCommits.name();
            var commits = fourCommits(fixture, path, TestCommits.name());
            var branch = TestCommits.name();
            TestCommits.setRef(fixture, GitIdentity.branchRef(branch), ObjectId.fromString(commits.getLast()));

            var result = reader.history(fixture, branch, path, 10);

            var history = Objects.requireNonNull(result.value(), "the history is read");
            var third = parse(fixture, commits.get(2));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the history is read"),
                    () -> assertEquals(List.of(commits.get(2), commits.getFirst()),
                            history.stream().map(CommitInfo::id).toList(),
                            "the commits that changed the path, newest first"),
                    () -> assertEquals(JGitRepositoryOperations.toInfo(third), history.getFirst(),
                            "the newest commit is described completely"),
                    () -> assertInfo(third, history.getFirst()),
                    () -> assertEquals(List.of(commits.get(2), commits.getFirst()),
                            Objects.requireNonNull(reader.history(fixture, branch,
                                    path.substring(0, path.indexOf('/')), 10).value()).stream()
                                    .map(CommitInfo::id).toList(),
                            "a directory changes when a path below it changes"));
        }

        @Test
        @DisplayName("GDR-CORE-9: a path never changed has an empty history, maxCount bounds the commits")
        void emptyAndBounded(@InMemory Repository fixture) throws Exception {
            var path = TestCommits.name();
            var commits = fourCommits(fixture, path, TestCommits.name());
            var tip = commits.getLast();

            assertAll(
                    () -> assertEquals(GitResult.ok(List.of()), reader.history(fixture, tip, TestCommits.name(), 10),
                            "a path never changed"),
                    () -> assertEquals(List.of(commits.get(2)),
                            Objects.requireNonNull(reader.history(fixture, tip, path, 1).value()).stream()
                                    .map(CommitInfo::id).toList(),
                            "maxCount 1 returns the newest commit only"),
                    () -> assertEquals(1, Objects.requireNonNull(reader.history(fixture, tip, path, 0).value()).size(),
                            "maxCount below 1 counts as 1"));
        }

        @Test
        @DisplayName("GDR-CORE-9: an invalid path and an unknown revision are not found")
        void notFound(@InMemory Repository fixture) throws Exception {
            var commits = fourCommits(fixture, TestCommits.name(), TestCommits.name());
            var tip = commits.getLast();
            var name = TestCommits.name();

            assertAll(Stream.concat(
                    Stream.of("", " ", "/" + name, name + "/", name + "//" + name).<Executable>map(path -> () ->
                            assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "path: not a valid path"),
                                    reader.history(fixture, tip, path, 10), "an invalid path")),
                    Stream.<Executable>of(() -> assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "revision: not found"),
                            reader.history(fixture, TestCommits.name(), name, 10), "an unknown revision"))));
        }

        private static void assertInfo(RevCommit commit, CommitInfo info) {
            assertAll(
                    () -> assertEquals(commit.name(), info.id(), "the id"),
                    () -> assertEquals(List.of(commit.getParent(0).name()), info.parents(), "the parents"),
                    () -> assertEquals(commit.getAuthorIdent().getName(), info.author().name(), "the author"),
                    () -> assertEquals(commit.getAuthorIdent().getEmailAddress(), info.author().email(),
                            "the author's e-mail address"),
                    () -> assertEquals(commit.getAuthorIdent().getWhenAsInstant(), info.authoredAt(),
                            "the author time"),
                    () -> assertEquals(commit.getCommitterIdent().getName(), info.committer().name(), "the committer"),
                    () -> assertEquals(commit.getCommitterIdent().getWhenAsInstant(), info.committedAt(),
                            "the commit time"),
                    () -> assertEquals(commit.getFullMessage(), info.message(), "the message"));
        }
    }

    @Nested
    @DisplayName("changes")
    class Changes {

        @Test
        @DisplayName("GDR-CORE-9: added, modified in content and in mode, and deleted paths, nested included")
        void changedPaths(@InMemory Repository repository) throws Exception {
            var kept = TestCommits.name();
            var modified = nestedPath();
            var modeChanged = TestCommits.name();
            var deleted = nestedPath();
            var added = nestedPath();
            var modeContent = content();
            var files = Map.of(kept, file(), modified, file(), modeChanged, file(modeContent), deleted, file());
            var from = commit(repository, files);
            var to = commit(repository, with(files, Map.of(modified, file(), modeChanged, executable(modeContent),
                    added, file()), deleted), from);

            var result = reader.changes(repository, from.name(), to.name());

            var changes = Objects.requireNonNull(result.value(), "the changes are read");
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the changes are read"),
                    () -> assertEquals(Map.of(modified, ChangeType.MODIFIED, modeChanged, ChangeType.MODIFIED,
                                    deleted, ChangeType.DELETED, added, ChangeType.ADDED), byPath(changes),
                            "exactly the changed paths, a mode change as a modification"),
                    () -> assertEquals(4, changes.size(), "each path once"),
                    () -> assertEquals(Map.of(modified, ChangeType.MODIFIED, modeChanged, ChangeType.MODIFIED,
                                    deleted, ChangeType.ADDED, added, ChangeType.DELETED),
                            byPath(Objects.requireNonNull(reader.changes(repository, to.name(), from.name()).value())),
                            "the reverse comparison swaps added and deleted"));
        }

        @Test
        @DisplayName("GDR-CORE-9: a file replaced by a directory is the file deleted and the files below it added")
        void fileToDirectory(@InMemory Repository repository) throws Exception {
            var path = TestCommits.name();
            var below = path + "/" + TestCommits.name();
            var files = Map.of(path, file(), TestCommits.name(), file());
            var from = commit(repository, files);
            var to = commit(repository, with(files, Map.of(below, file()), path), from);

            var result = reader.changes(repository, from.name(), to.name());

            assertEquals(Map.of(path, ChangeType.DELETED, below, ChangeType.ADDED),
                    byPath(Objects.requireNonNull(result.value(), "the changes are read")), "deleted and added");
        }

        @Test
        @DisplayName("GDR-CORE-9: two commits with the same tree have no changes")
        void identicalTrees(@InMemory Repository repository) throws Exception {
            var files = Map.of(TestCommits.name(), file(), nestedPath(), file());
            var from = commit(repository, files);
            var to = commit(repository, files, from);

            assertAll(
                    () -> assertEquals(GitResult.ok(List.of()), reader.changes(repository, from.name(), to.name()),
                            "the same tree"),
                    () -> assertEquals(GitResult.ok(List.of()), reader.changes(repository, from.name(), from.name()),
                            "the same commit"));
        }

        @Test
        @DisplayName("GDR-CORE-9: an abbreviated, unknown, tree or tag id is not found and names the field")
        void notACommit(@InMemory Repository repository) throws Exception {
            var commit = commit(repository, Map.of(TestCommits.name(), file()));
            var id = commit.name();
            var tree = parse(repository, id).getTree().name();
            var tag = annotatedTag(repository, commit).name();
            var abbreviated = commit.abbreviate(12).name();

            assertAll(
                    () -> assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "from: not a full commit id"),
                            reader.changes(repository, abbreviated, id), "an abbreviated from"),
                    () -> assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "to: not a full commit id"),
                            reader.changes(repository, id, abbreviated), "an abbreviated to"),
                    () -> assertAll(Stream.of(ObjectId.zeroId().name(), tree, tag).<Executable>map(other -> () ->
                            assertAll(
                                    () -> assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "from: not found"),
                                            reader.changes(repository, other, id), "from is no commit"),
                                    () -> assertEquals(GitResult.of(GitOutcome.NOT_FOUND, "to: not found"),
                                            reader.changes(repository, id, other), "to is no commit")))));
        }
    }

    @Test
    @DisplayName("a null argument is rejected")
    void nullArguments(@InMemory Repository repository) throws Exception {
        var path = TestCommits.name();
        var commit = commit(repository, Map.of(path, file())).name();

        assertAll(Stream.<Executable>of(
                () -> reader.snapshot(nullValue(), commit),
                () -> reader.snapshot(repository, nullValue()),
                () -> reader.history(nullValue(), commit, path, 1),
                () -> reader.history(repository, nullValue(), path, 1),
                () -> reader.history(repository, commit, nullValue(), 1),
                () -> reader.changes(nullValue(), commit, commit),
                () -> reader.changes(repository, nullValue(), commit),
                () -> reader.changes(repository, commit, nullValue()))
                .<Executable>map(call -> () -> assertThrows(NullPointerException.class, call, "null is rejected")));
    }

    @Test
    @DisplayName("a snapshot rejects a null component and copies its files")
    void snapshotRecord() {
        List<SnapshotFile> files = new ArrayList<>();
        files.add(new SnapshotFile(TestCommits.name(), FileKind.REGULAR, blobId(content()).name()));
        var id = blobId(content()).name();

        var snapshot = new RepositoryReader.Snapshot(id, id, files);
        files.clear();

        assertAll(
                () -> assertEquals(1, snapshot.files().size(), "a later change of the list changes nothing"),
                () -> assertThrows(UnsupportedOperationException.class, () -> snapshot.files().clear(),
                        "the files are unmodifiable"),
                () -> assertThrows(NullPointerException.class,
                        () -> new RepositoryReader.Snapshot(nullValue(), id, List.of()), "a null commit id"),
                () -> assertThrows(NullPointerException.class,
                        () -> new PathChange(TestCommits.name(), nullValue()), "a null change type"),
                () -> assertEquals(id, snapshot.commitId(), "the commit id is kept"));
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    @SuppressWarnings("unchecked")
    private static <T> T nullValue() {
        return (T) null;
    }
}
