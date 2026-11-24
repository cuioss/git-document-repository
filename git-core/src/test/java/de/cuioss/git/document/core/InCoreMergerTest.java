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

import static de.cuioss.git.document.core.InCoreWriterTest.content;
import static de.cuioss.git.document.core.InCoreWriterTest.parse;
import static de.cuioss.git.document.core.InCoreWriterTest.person;
import static de.cuioss.git.document.core.InCoreWriterTest.refs;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.DeflaterOutputStream;


import de.cuioss.git.document.testing.GitGenerators;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * In-core merge at path level (GDR-CORE-6) against the in-memory and the local-repository fixtures of
 * {@code git-testing}: one-sided changes, the same change on both sides, a deletion on one side only and nested paths
 * merge clean; a content, file-mode, delete-modify, add-add and file-directory conflict is {@link
 * MergeResult.Conflicting} with exactly the expected paths; no merge writes a commit or moves a reference; an unknown,
 * abbreviated or tag id is not found, and more than one merge base is a conflict without paths.
 */
@EnableGeneratorController
@DisplayName("InCoreMerger (GDR-CORE-6)")
class InCoreMergerTest {

    /** The length of a loose object's file name: the object id without its two-digit directory. */
    private static final int LOOSE_NAME_LENGTH = Constants.OBJECT_ID_STRING_LENGTH - 2;

    /** The side of the first tip of a conflict fixture. */
    private static final String OURS = "ours";

    /** The side of the second tip of a conflict fixture. */
    private static final String THEIRS = "theirs";

    /** Contents that must differ from each other and from generated base contents: distinct by construction. */
    private static final TypedGenerator<String> DISTINCT_CONTENTS = GitGenerators.distinctTexts();

    private final InCoreMerger merger = new InCoreMerger();

    /** A file of a fixture tree: its mode and its content. */
    record Entry(FileMode mode, String content) {
    }

    /** A regular file with generated content. */
    static Entry file() {
        return new Entry(FileMode.REGULAR_FILE, content());
    }

    static Entry file(String content) {
        return new Entry(FileMode.REGULAR_FILE, content);
    }

    /**
     * A regular file whose content is the name of its side and a distinct value: two sides never receive the same
     * content, and never the letters-only content a base file is generated with, however the generator draws.
     */
    static Entry sideFile(String side) {
        return file(side + "-" + DISTINCT_CONTENTS.next());
    }

    static Entry executable(String content) {
        return new Entry(FileMode.EXECUTABLE_FILE, content);
    }

    /** A generated nested path of three segments. */
    static String nestedPath() {
        return TestCommits.name() + "/" + TestCommits.name() + "/" + TestCommits.name();
    }

    /** A copy of the files with the changes applied and the deleted paths removed. */
    static Map<String, Entry> with(Map<String, Entry> files, Map<String, Entry> changes, String... deleted) {
        Map<String, Entry> result = new HashMap<>(files);
        result.putAll(changes);
        for (String path : deleted) {
            result.remove(path);
        }
        return result;
    }

    /**
     * Writes a commit whose tree holds exactly the files, through JGit's in-core {@link DirCache}, without moving any
     * reference.
     */
    static ObjectId commit(Repository repository, Map<String, Entry> files, ObjectId... parents)
            throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (var file : new TreeMap<>(files).entrySet()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(file.getValue().mode());
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file.getValue().content().getBytes(UTF_8)));
                builder.add(entry);
            }
            builder.finish();
            var commit = new CommitBuilder();
            commit.setTreeId(index.writeTree(inserter));
            commit.setParentIds(parents);
            commit.setAuthor(person());
            commit.setCommitter(person());
            commit.setMessage(InCoreWriterTest.message());
            var id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }

    /** Reads every file of a tree with its mode and content. */
    private static Map<String, Entry> files(Repository repository, String treeId) throws IOException {
        Map<String, Entry> files = new HashMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(ObjectId.fromString(treeId));
            walk.setRecursive(true);
            while (walk.next()) {
                files.put(walk.getPathString(), new Entry(walk.getFileMode(0),
                        new String(repository.open(walk.getObjectId(0)).getBytes(), UTF_8)));
            }
        }
        return files;
    }

    private MergeResult merge(Repository repository, ObjectId ours, ObjectId theirs) {
        return merger.merge(repository, ours.name(), theirs.name());
    }

    /** Merges and asserts a clean merge whose tree holds exactly the expected files. */
    private void assertMerged(Repository repository, ObjectId ours, ObjectId theirs, Map<String, Entry> expected) {
        var result = merge(repository, ours, theirs);
        var merged = assertInstanceOf(MergeResult.Merged.class, result, "the merge is clean");
        assertAll(
                () -> assertEquals(GitOutcome.OK, merged.outcome(), "a clean merge is OK"),
                () -> assertEquals(Optional.empty(), merged.detail(), "a clean merge carries no detail"),
                () -> assertTrue(repository.getObjectDatabase().has(ObjectId.fromString(merged.treeId())),
                        "the merged tree is written"),
                () -> assertEquals(expected, files(repository, merged.treeId()),
                        "the merged tree holds exactly the expected files"));
    }

    /** Merges and asserts a conflict naming exactly the expected paths. */
    private void assertConflicting(Repository repository, ObjectId ours, ObjectId theirs, List<String> expected) {
        var result = merge(repository, ours, theirs);
        var conflicting = assertInstanceOf(MergeResult.Conflicting.class, result, "the merge conflicts");
        assertAll(
                () -> assertEquals(GitOutcome.CONFLICT, conflicting.outcome(), "a conflict is CONFLICT"),
                () -> assertEquals(expected.stream().sorted().toList(), conflicting.paths(),
                        "the conflicting paths are exactly the expected ones"));
    }

    @Nested
    @DisplayName("clean merges")
    class Clean {

        @Test
        @DisplayName("GDR-CORE-6: a change on each side is taken from that side")
        void oneSidedChanges(@InMemory Repository repository) throws Exception {
            var left = TestCommits.name();
            var right = TestCommits.name();
            var files = Map.of(left, file(), right, file());
            var base = commit(repository, files);
            var oursChange = Map.of(left, file());
            var theirsChange = Map.of(right, file());
            var ours = commit(repository, with(files, oursChange), base);
            var theirs = commit(repository, with(files, theirsChange), base);

            assertMerged(repository, ours, theirs, Map.of(left, oursChange.get(left), right, theirsChange.get(right)));
        }

        @Test
        @DisplayName("GDR-CORE-6: the same change on both sides merges clean")
        void sameChangeOnBothSides(@InMemory Repository repository) throws Exception {
            var path = TestCommits.name();
            var files = Map.of(path, file(), TestCommits.name(), file());
            var base = commit(repository, files);
            var change = Map.of(path, file());
            var ours = commit(repository, with(files, change), base);
            var theirs = commit(repository, with(files, change), base);

            assertMerged(repository, ours, theirs, with(files, change));
        }

        @Test
        @DisplayName("GDR-CORE-6: a delete on one side only merges clean, the path is absent")
        void deleteOnOneSide(@InMemory Repository repository) throws Exception {
            var deleted = TestCommits.name();
            var changed = TestCommits.name();
            var files = Map.of(deleted, file(), changed, file());
            var base = commit(repository, files);
            var theirsChange = Map.of(changed, file());
            var ours = commit(repository, with(files, Map.of(), deleted), base);
            var theirs = commit(repository, with(files, theirsChange), base);

            assertMerged(repository, ours, theirs, theirsChange);
        }

        @Test
        @DisplayName("GDR-CORE-6: changes in nested directories on each side merge clean")
        void nestedPaths(@InMemory Repository repository) throws Exception {
            var directory = TestCommits.name() + "/" + TestCommits.name();
            var left = directory + "/" + TestCommits.name();
            var right = directory + "/" + TestCommits.name();
            var files = Map.of(left, file(), right, file(), nestedPath(), file());
            var base = commit(repository, files);
            var oursChange = Map.of(left, file(), nestedPath(), file());
            var theirsChange = Map.of(right, file());
            var ours = commit(repository, with(files, oursChange), base);
            var theirs = commit(repository, with(files, theirsChange), base);

            assertMerged(repository, ours, theirs, with(with(files, oursChange), theirsChange));
        }

        @Test
        @DisplayName("GDR-CORE-6: two commits without a common ancestor merge against the empty tree")
        void noCommonAncestor(@InMemory Repository repository) throws Exception {
            var oursFiles = Map.of(TestCommits.name(), file());
            var theirsFiles = Map.of(TestCommits.name(), file());
            var ours = commit(repository, oursFiles);
            var theirs = commit(repository, theirsFiles);

            assertMerged(repository, ours, theirs, with(oursFiles, theirsFiles));
        }
    }

    @Nested
    @DisplayName("conflicts")
    class Conflicts {

        @Test
        @DisplayName("GDR-CORE-6: different content on both sides is a content conflict")
        void contentConflict(@InMemory Repository repository) throws Exception {
            var path = TestCommits.name();
            var files = Map.of(path, file(), TestCommits.name(), file());
            var base = commit(repository, files);
            var ours = commit(repository, with(files, Map.of(path, sideFile(OURS))), base);
            var theirs = commit(repository, with(files, Map.of(path, sideFile(THEIRS))), base);

            assertConflicting(repository, ours, theirs, List.of(path));
        }

        @Test
        @DisplayName("GDR-CORE-6: a conflict on a file whose name is whitespace only names that path")
        void whitespaceOnlyNameConflict(@InMemory Repository repository) throws Exception {
            var path = " ";
            var files = Map.of(path, file(), TestCommits.name(), file());
            var base = commit(repository, files);
            var ours = commit(repository, with(files, Map.of(path, sideFile(OURS))), base);
            var theirs = commit(repository, with(files, Map.of(path, sideFile(THEIRS))), base);

            assertConflicting(repository, ours, theirs, List.of(path));
        }

        @Test
        @DisplayName("GDR-CORE-6: the same content added as a regular and as an executable file is a mode conflict")
        void fileModeConflict(@InMemory Repository repository) throws Exception {
            var path = TestCommits.name();
            var content = content();
            var files = Map.of(TestCommits.name(), file());
            var base = commit(repository, files);
            var ours = commit(repository, with(files, Map.of(path, file(content))), base);
            var theirs = commit(repository, with(files, Map.of(path, executable(content))), base);

            assertConflicting(repository, ours, theirs, List.of(path));
        }

        @Test
        @DisplayName("GDR-CORE-6: a delete on one side and a modification on the other is a conflict")
        void deleteModifyConflict(@InMemory Repository repository) throws Exception {
            var path = TestCommits.name();
            var files = Map.of(path, file(), TestCommits.name(), file());
            var base = commit(repository, files);
            var ours = commit(repository, with(files, Map.of(), path), base);
            var theirs = commit(repository, with(files, Map.of(path, sideFile(THEIRS))), base);

            assertAll(
                    () -> assertConflicting(repository, ours, theirs, List.of(path)),
                    () -> assertConflicting(repository, theirs, ours, List.of(path)));
        }

        @Test
        @DisplayName("GDR-CORE-6: the same path added with different content on both sides is a conflict")
        void addAddConflict(@InMemory Repository repository) throws Exception {
            var path = nestedPath();
            var files = Map.of(TestCommits.name(), file());
            var base = commit(repository, files);
            var ours = commit(repository, with(files, Map.of(path, sideFile(OURS))), base);
            var theirs = commit(repository, with(files, Map.of(path, sideFile(THEIRS))), base);

            assertConflicting(repository, ours, theirs, List.of(path));
        }

        @Test
        @DisplayName("GDR-CORE-6: a path that is a file on one side and a directory on the other is a conflict")
        void fileDirectoryConflict(@InMemory Repository repository) throws Exception {
            var path = TestCommits.name();
            var files = Map.of(TestCommits.name(), file());
            var base = commit(repository, files);
            var asFile = commit(repository, with(files, Map.of(path, file())), base);
            var asDirectory = commit(repository, with(files, Map.of(path + "/" + TestCommits.name(), file())), base);

            assertAll(
                    () -> assertConflicting(repository, asFile, asDirectory, List.of(path)),
                    () -> assertConflicting(repository, asDirectory, asFile, List.of(path)));
        }

        @Test
        @DisplayName("GDR-CORE-6: conflicts in nested paths beside clean changes name exactly the conflicting paths")
        void nestedConflicts(@InMemory Repository repository) throws Exception {
            var contentConflict = nestedPath();
            var deleteConflict = nestedPath();
            var oursOnly = nestedPath();
            var theirsOnly = nestedPath();
            var files = Map.of(contentConflict, file(), deleteConflict, file(), oursOnly, file(), theirsOnly, file());
            var base = commit(repository, files);
            var ours = commit(repository,
                    with(files, Map.of(contentConflict, sideFile(OURS), oursOnly, file()), deleteConflict), base);
            var theirs = commit(repository, with(files,
                    Map.of(contentConflict, sideFile(THEIRS), deleteConflict, sideFile(THEIRS), theirsOnly, file())),
                    base);

            assertConflicting(repository, ours, theirs, List.of(contentConflict, deleteConflict));
        }
    }

    @Nested
    @DisplayName("no side effects")
    class NoSideEffects {

        @Test
        @DisplayName("GDR-CORE-6: neither a clean nor a conflicting merge writes a commit or moves a reference")
        void writesNoCommit(@LocalRepository Repository repository) throws Exception {
            var path = TestCommits.name();
            var other = TestCommits.name();
            var files = Map.of(path, file(), other, file());
            var base = commit(repository, files);
            var ours = commit(repository, with(files, Map.of(path, sideFile(OURS))), base);
            var theirs = commit(repository, with(files, Map.of(path, sideFile(THEIRS))), base);
            var clean = commit(repository, with(files, Map.of(other, file())), base);
            TestCommits.setRef(repository, GitIdentity.branchRef(TestCommits.name()), ours);
            TestCommits.setRef(repository, GitIdentity.branchRef(TestCommits.name()), theirs);
            var refsBefore = refs(repository);
            var commitsBefore = commitCount(repository);

            var conflicting = merge(repository, ours, theirs);
            var merged = merge(repository, ours, clean);

            assertAll(
                    () -> assertInstanceOf(MergeResult.Conflicting.class, conflicting, "the first merge conflicts"),
                    () -> assertInstanceOf(MergeResult.Merged.class, merged, "the second merge is clean"),
                    () -> assertEquals(commitsBefore, commitCount(repository), "no commit is written"),
                    () -> assertEquals(refsBefore, refs(repository), "no reference moved"));
        }

        /** Counts the commits among the loose objects of a repository on disk. */
        private static long commitCount(Repository repository) throws IOException {
            Path objects = repository.getDirectory().toPath().resolve("objects");
            List<ObjectId> ids;
            try (Stream<Path> paths = Files.walk(objects)) {
                ids = paths.filter(Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().length() == LOOSE_NAME_LENGTH)
                        .map(file -> file.getParent().getFileName().toString() + file.getFileName())
                        .filter(ObjectId::isId)
                        .map(ObjectId::fromString)
                        .toList();
            }
            long commits = 0;
            for (ObjectId id : ids) {
                if (repository.open(id).getType() == Constants.OBJ_COMMIT) {
                    commits++;
                }
            }
            assertTrue(commits > 0, "the fixture's commits are loose objects");
            return commits;
        }
    }

    @Nested
    @DisplayName("not merged")
    class NotMerged {

        @Test
        @DisplayName("GDR-CORE-6: an unknown commit id is not found and names the field")
        void unknownCommit(@InMemory Repository repository) throws Exception {
            var commit = commit(repository, Map.of(TestCommits.name(), file()));

            assertAll(
                    () -> assertEquals(new MergeResult.NotMerged(GitOutcome.NOT_FOUND, "ours: not found"),
                            merge(repository, ObjectId.zeroId(), commit), "an unknown ours"),
                    () -> assertEquals(new MergeResult.NotMerged(GitOutcome.NOT_FOUND, "theirs: not found"),
                            merge(repository, commit, ObjectId.zeroId()), "an unknown theirs"));
        }

        @Test
        @DisplayName("GDR-CORE-11: an abbreviated commit id is no full commit id and names the field")
        void abbreviatedCommit(@InMemory Repository repository) throws Exception {
            var commit = commit(repository, Map.of(TestCommits.name(), file()));
            var abbreviated = commit.abbreviate(12).name();

            assertAll(
                    () -> assertEquals(new MergeResult.NotMerged(GitOutcome.NOT_FOUND, "ours: not a full commit id"),
                            merger.merge(repository, abbreviated, commit.name()), "an abbreviated ours"),
                    () -> assertEquals(
                            new MergeResult.NotMerged(GitOutcome.NOT_FOUND, "theirs: not a full commit id"),
                            merger.merge(repository, commit.name(), abbreviated), "an abbreviated theirs"));
        }

        @Test
        @DisplayName("GDR-CORE-6: the id of an annotated tag or a tree is no commit and is not found")
        void tagOrTree(@InMemory Repository repository) throws Exception {
            var commit = commit(repository, Map.of(TestCommits.name(), file()));
            var tree = parse(repository, commit.name()).getTree();
            var tag = annotatedTag(repository, commit);

            assertAll(Stream.of(tag, tree).<Executable>map(id -> () -> assertAll(
                    () -> assertEquals(new MergeResult.NotMerged(GitOutcome.NOT_FOUND, "ours: not found"),
                            merge(repository, id, commit), "ours is no commit"),
                    () -> assertEquals(new MergeResult.NotMerged(GitOutcome.NOT_FOUND, "theirs: not found"),
                            merge(repository, commit, id), "theirs is no commit"))));
        }

        @Test
        @DisplayName("GDR-CORE-6: more than one merge base is a conflict without paths")
        void multipleMergeBases(@InMemory Repository repository) throws Exception {
            var files = Map.of(TestCommits.name(), file());
            var root = commit(repository, files);
            var left = commit(repository, with(files, Map.of(TestCommits.name(), file())), root);
            var right = commit(repository, with(files, Map.of(TestCommits.name(), file())), root);
            var ours = commit(repository, with(files, Map.of(TestCommits.name(), file())), left, right);
            var theirs = commit(repository, with(files, Map.of(TestCommits.name(), file())), right, left);

            var result = merge(repository, ours, theirs);

            assertAll(
                    () -> assertEquals(new MergeResult.NotMerged(GitOutcome.CONFLICT, "merge base: more than one"),
                            result, "more than one merge base is a conflict"),
                    () -> assertEquals(Optional.of("merge base: more than one"), result.detail(),
                            "the detail names the merge base, no path"));
        }

        @Test
        @DisplayName("GDR-CORE-6: a tip whose tree holds a path JGit refuses is a failure naming the class only")
        void refusedPathInATip(@InMemory Repository repository) throws Exception {
            var kept = TestCommits.name();
            var changed = TestCommits.name();
            var keptContent = DISTINCT_CONTENTS.next();
            var changedContent = DISTINCT_CONTENTS.next();
            var base = InCoreWriterTest.commitWithTree(repository, Map.of(kept, keptContent, changed, changedContent));
            var ours = InCoreWriterTest.commitWithTree(repository, Map.of(kept, keptContent, changed, changedContent,
                    ".GIT", DISTINCT_CONTENTS.next()), base);
            var theirs = InCoreWriterTest.commitWithTree(repository, Map.of(kept, keptContent,
                    changed, DISTINCT_CONTENTS.next()), base);

            var result = merge(repository, ours, theirs);

            assertEquals(new MergeResult.NotMerged(GitOutcome.FAILED, "InvalidPathException"), result,
                    "the refused path is a failure, the detail naming the exception class, never the path");
        }

        @Test
        @DisplayName("GDR-CORE-6: a tip whose tree is too large to read in one piece is a failure naming the class")
        void largeTreeInATip(@LocalRepository Repository repository) throws Exception {
            var base = commit(repository, Map.of(TestCommits.name(), file()));
            var theirs = commit(repository, Map.of(TestCommits.name(), file()), base);
            ObjectId ours;
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                var commit = new CommitBuilder();
                commit.setTreeId(largeLooseTree(repository));
                commit.setParentIds(base);
                commit.setAuthor(person());
                commit.setCommitter(person());
                commit.setMessage(InCoreWriterTest.message());
                ours = inserter.insert(commit);
                inserter.flush();
            }

            var result = merge(repository, ours, theirs);

            assertEquals(new MergeResult.NotMerged(GitOutcome.FAILED, "LargeObjectException"), result,
                    "the large tree is a failure, the detail naming the exception class only");
        }

        @Test
        @DisplayName("a null argument is rejected")
        void nullArguments(@InMemory Repository repository) throws Exception {
            var commit = commit(repository, Map.of(TestCommits.name(), file())).name();

            assertAll(Stream.<Executable>of(
                    () -> merger.merge(nullValue(), commit, commit),
                    () -> merger.merge(repository, nullValue(), commit),
                    () -> merger.merge(repository, commit, nullValue()))
                    .<Executable>map(call -> () -> assertThrows(NullPointerException.class, call, "null is rejected")));
        }
    }

    /**
     * Writes a loose tree object whose header announces {@link Integer#MAX_VALUE} bytes, followed by a few bytes only.
     * JGit 7.8.0 {@code UnpackedObject.open} returns a loose object of a file-backed repository at or above the
     * streaming threshold as a {@code LargeObject} without reading its content, and its {@code getCachedBytes()} throws
     * a {@code LargeObjectException}; no threshold is above {@code Integer.MAX_VALUE}, so the object is large whatever
     * the process-wide {@code WindowCacheConfig} says.
     *
     * @return the id of the tree
     */
    static ObjectId largeLooseTree(Repository repository) throws IOException {
        var stub = content().getBytes(UTF_8);
        ObjectId id;
        try (var formatter = new ObjectInserter.Formatter()) {
            id = formatter.idFor(Constants.OBJ_TREE, stub);
        }
        Path loose = repository.getDirectory().toPath().resolve("objects").resolve(id.name().substring(0, 2))
                .resolve(id.name().substring(2));
        Files.createDirectories(loose.getParent());
        try (var out = new DeflaterOutputStream(Files.newOutputStream(loose))) {
            out.write((Constants.TYPE_TREE + " " + Integer.MAX_VALUE + "\0").getBytes(UTF_8));
            out.write(stub);
        }
        return id;
    }

    static ObjectId annotatedTag(Repository repository, ObjectId commit) throws IOException {
        var tag = new TagBuilder();
        tag.setObjectId(commit, Constants.OBJ_COMMIT);
        tag.setTag(TestCommits.name());
        tag.setTagger(person());
        tag.setMessage(InCoreWriterTest.message());
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var id = inserter.insert(tag);
            inserter.flush();
            return id;
        }
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    @SuppressWarnings("unchecked")
    private static <T> T nullValue() {
        return (T) null;
    }
}
