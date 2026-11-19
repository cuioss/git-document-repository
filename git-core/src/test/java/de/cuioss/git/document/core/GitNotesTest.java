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
import static de.cuioss.git.document.core.InCoreWriterTest.metadata;
import static de.cuioss.git.document.core.InCoreWriterTest.parse;
import static de.cuioss.git.document.core.InCoreWriterTest.person;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


import de.cuioss.git.document.testing.InMemory;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Notes on commits (GDR-CORE-10) against the in-memory and the local-repository fixtures of {@code git-testing}: a note
 * is written and read back, overwritten, refused under an invalid notes reference and not found for an object that is
 * no commit; after the note map fanned out, an overwrite leaves exactly one entry for the commit; a symbolic notes
 * reference is replaced by the update; and two writers on one notes reference never lose a note.
 * <p>
 * The two writer tests follow the two reference databases (checked against JGit 7.8.0, see
 * {@code ReferenceUpdaterTest}): the in-memory database loses an attempt only to a writer that advanced the reference,
 * so with as many attempts as writers both succeed (liveness); the file-based database also fails an attempt while
 * another writer merely holds the lock file, so on the local fixture the test asserts the safety invariant only.
 */
@EnableGeneratorController
@DisplayName("GitNotes (GDR-CORE-10)")
class GitNotesTest {

    /** The notes of the fan-out test: more than the 256 notes JGit 7.8.0 {@code LeafBucket} holds before it splits. */
    private static final int FANNED_OUT_NOTES = 300;

    /** The number of concurrent writers. */
    private static final int WRITERS = 2;

    /** The attempts of a writer on the local fixture, where a held lock file costs an attempt too. */
    private static final int LOCAL_ATTEMPTS = 5;

    /** The bound on the threaded tests' wait for their writers, far above their run time. */
    private static final long TIMEOUT_SECONDS = 60;

    private final GitNotes notes = new GitNotes(new ReferenceUpdater(new InCoreWriter()));

    private static String notesRef() {
        return Constants.R_NOTES + TestCommits.name();
    }

    private static byte[] noteContent() {
        return content().getBytes(UTF_8);
    }

    private static @Nullable ObjectId refId(Repository repository, String refName) throws IOException {
        Ref ref = repository.exactRef(refName);
        return ref == null ? null : ref.getObjectId();
    }

    /** Reads a note with JGit's own {@link NoteMap}, independent of the class under test. */
    private static byte @Nullable [] readWithNoteMap(Repository repository, String notesRef, ObjectId commit)
            throws IOException {
        var head = Objects.requireNonNull(refId(repository, notesRef), "the notes reference exists");
        try (RevWalk walk = new RevWalk(repository)) {
            var map = NoteMap.read(walk.getObjectReader(), walk.parseCommit(head));
            var blob = map.get(commit);
            return blob == null ? null : repository.open(blob, Constants.OBJ_BLOB).getBytes();
        }
    }

    /** Every path of the notes tree the reference points at, recursively. */
    private static List<String> notePaths(Repository repository, String notesRef) throws IOException {
        var head = Objects.requireNonNull(refId(repository, notesRef), "the notes reference exists");
        List<String> paths = new ArrayList<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(parse(repository, head.name()).getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                paths.add(walk.getPathString());
            }
        }
        return paths;
    }

    private String write(Repository repository, String notesRef, ObjectId commit, byte[] note) {
        var result = notes.write(repository, notesRef, commit.name(), note, metadata(), 1);
        assertTrue(result.isOk(), "the note is written");
        return Objects.requireNonNull(result.value());
    }

    @Nested
    @DisplayName("writing and reading")
    class WritingAndReading {

        @Test
        @DisplayName("GDR-CORE-10: a written note is read back and stored in git's notes layout")
        void writeThenRead(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var notesRef = notesRef();
            var note = noteContent();

            var written = notes.write(repository, notesRef, commit.name(), note, metadata(), 1);
            var read = notes.read(repository, notesRef, commit.name());

            var notesCommit = parse(repository, Objects.requireNonNull(written.value(), "the note is written"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, written.outcome(), "the note is written"),
                    () -> assertEquals(notesCommit, refId(repository, notesRef),
                            "the notes reference points at the new notes commit"),
                    () -> assertEquals(0, notesCommit.getParentCount(), "the first notes commit is a root commit"),
                    () -> assertEquals(GitOutcome.OK, read.outcome(), "the note is read"),
                    () -> assertArrayEquals(note, read.value(), "the read note is the written note"),
                    () -> assertArrayEquals(note, readWithNoteMap(repository, notesRef, commit),
                            "JGit's own note map reads the same note"),
                    () -> assertEquals(List.of(commit.name()), notePaths(repository, notesRef),
                            "the note lies at the commit's id"));
        }

        @Test
        @DisplayName("GDR-CORE-10: an overwrite replaces the note and keeps the notes history linear")
        void overwrite(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var other = TestCommits.commit(repository, null);
            var notesRef = notesRef();
            var otherNote = noteContent();
            write(repository, notesRef, other, otherNote);
            var first = write(repository, notesRef, commit, noteContent());
            var replacement = noteContent();

            var result = notes.write(repository, notesRef, commit.name(), replacement, metadata(), 1);

            var notesCommit = parse(repository, Objects.requireNonNull(result.value(), "the note is overwritten"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the note is overwritten"),
                    () -> assertEquals(first, notesCommit.getParent(0).name(),
                            "the new notes commit follows the previous one"),
                    () -> assertArrayEquals(replacement, notes.read(repository, notesRef, commit.name()).value(),
                            "the read note is the replacement"),
                    () -> assertArrayEquals(otherNote, notes.read(repository, notesRef, other.name()).value(),
                            "the note of the other commit is kept"));
        }

        @Test
        @DisplayName("GDR-CORE-10: the note is copied on entry and on exit")
        void copiedOnEntryAndExit(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var notesRef = notesRef();
            var note = noteContent();
            var original = note.clone();
            write(repository, notesRef, commit, note);
            note[0]++;

            var read = Objects.requireNonNull(notes.read(repository, notesRef, commit.name()).value());
            read[0]++;

            assertArrayEquals(original, notes.read(repository, notesRef, commit.name()).value(),
                    "neither the caller's array nor the returned one changes the stored note");
        }

        @Test
        @DisplayName("GDR-CORE-10: after the note map fanned out, an overwrite leaves exactly one entry for the commit")
        void fanOutOverwrite(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var notesRef = notesRef();
            fannedOutNotes(repository, notesRef, commit);
            var replacement = noteContent();

            var result = notes.write(repository, notesRef, commit.name(), replacement, metadata(), 1);

            var paths = notePaths(repository, notesRef);
            var entriesOfCommit = paths.stream().filter(path -> path.replace("/", "").equals(commit.name())).toList();
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the note is overwritten"),
                    () -> assertTrue(paths.stream().allMatch(path -> path.contains("/")),
                            "the notes tree is fanned out"),
                    () -> assertEquals(FANNED_OUT_NOTES, paths.size(), "the overwrite adds no entry"),
                    () -> assertEquals(1, entriesOfCommit.size(), "exactly one entry names the commit"),
                    () -> assertArrayEquals(replacement, notes.read(repository, notesRef, commit.name()).value(),
                            "the read note is the replacement"),
                    () -> assertArrayEquals(replacement, readWithNoteMap(repository, notesRef, commit),
                            "JGit's own note map reads the replacement"));
        }

        @Test
        @DisplayName("GDR-CORE-10: a symbolic notes reference is replaced, the reference it pointed at is unchanged")
        void symbolicNotesReference(@LocalRepository Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var target = notesRef();
            var alias = notesRef();
            var before = write(repository, target, commit, noteContent());
            var link = repository.updateRef(alias).link(target);
            assertEquals(RefUpdate.Result.NEW, link, "the symbolic notes reference is created");
            var note = noteContent();

            var result = notes.write(repository, alias, commit.name(), note, metadata(), 1);

            var aliasRef = Objects.requireNonNull(repository.exactRef(alias), "the alias exists");
            var notesCommit = parse(repository, Objects.requireNonNull(result.value(), "the note is written"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the note is written through the alias"),
                    () -> assertFalse(aliasRef.isSymbolic(), "the alias is no longer symbolic"),
                    () -> assertEquals(notesCommit, aliasRef.getObjectId(), "the alias points at the notes commit"),
                    () -> assertEquals(before, notesCommit.getParent(0).name(),
                            "the notes commit follows the commit the alias resolved to"),
                    () -> assertEquals(ObjectId.fromString(before), refId(repository, target),
                            "the former target is unchanged"),
                    () -> assertArrayEquals(note, notes.read(repository, alias, commit.name()).value(),
                            "the alias holds the new note"));
        }
    }

    @Nested
    @DisplayName("refused and not found")
    class RefusedAndNotFound {

        /**
         * Notes references the class refuses: outside {@code refs/notes/}, the bare prefix and an invalid name.
         *
         * @return the refused names
         */
        static Stream<String> refusedRefs() {
            return Stream.of(GitIdentity.branchRef(TestCommits.name()), Constants.R_NOTES,
                    Constants.R_NOTES + TestCommits.name() + ".." + TestCommits.name());
        }

        @Test
        @DisplayName("GDR-CORE-10: a notes reference outside refs/notes/ or invalid is refused, nothing is written")
        void refusedNotesRef(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var note = noteContent();
            var refsBefore = InCoreWriterTest.refs(repository);

            assertAll(refusedRefs().<Executable>map(notesRef -> () -> {
                var written = notes.write(repository, notesRef, commit.name(), note, metadata(), 1);
                var read = notes.read(repository, notesRef, commit.name());
                assertAll(
                        () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, written.outcome(), "the write is refused"),
                        () -> assertEquals("notesRef: not a valid notes reference", written.detail(),
                                "the detail names the field only"),
                        () -> assertEquals(GitOutcome.REFUSED_BY_POLICY, read.outcome(), "the read is refused"),
                        () -> assertEquals("notesRef: not a valid notes reference", read.detail(),
                                "the detail names the field only"));
            }));
            assertEquals(refsBefore, InCoreWriterTest.refs(repository), "no reference is written");
        }

        @Test
        @DisplayName("GDR-CORE-10: a note on an absent commit, a tree or an annotated tag is not found")
        void notACommit(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var tree = parse(repository, commit.name()).getTree();
            var tag = annotatedTag(repository, commit);
            var notesRef = notesRef();
            write(repository, notesRef, commit, noteContent());
            var before = refId(repository, notesRef);

            assertAll(Stream.of(ObjectId.zeroId(), tree, tag).<Executable>map(id -> () -> {
                var written = notes.write(repository, notesRef, id.name(), noteContent(), metadata(), 1);
                var read = notes.read(repository, notesRef, id.name());
                assertAll(
                        () -> assertEquals(GitOutcome.NOT_FOUND, written.outcome(), "the write is not found"),
                        () -> assertEquals("commit: not found", written.detail(), "the detail names the field"),
                        () -> assertEquals(GitOutcome.NOT_FOUND, read.outcome(), "the read is not found"),
                        () -> assertEquals("commit: not found", read.detail(), "the detail names the field"));
            }));
            assertEquals(before, refId(repository, notesRef), "the notes reference is unchanged");
        }

        @Test
        @DisplayName("GDR-CORE-11: an abbreviated commit id is no full commit id and is not found")
        void abbreviatedCommit(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var notesRef = notesRef();
            var abbreviated = commit.abbreviate(12).name();

            var written = notes.write(repository, notesRef, abbreviated, noteContent(), metadata(), 1);
            var read = notes.read(repository, notesRef, abbreviated);

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, written.outcome(), "the write is not found"),
                    () -> assertEquals("commit: not a full commit id", written.detail(), "the detail names the field"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, read.outcome(), "the read is not found"),
                    () -> assertEquals("commit: not a full commit id", read.detail(), "the detail names the field"),
                    () -> assertNull(refId(repository, notesRef), "no notes reference is created"));
        }

        @Test
        @DisplayName("GDR-CORE-10: an absent notes reference and a commit without a note are not found")
        void absentNote(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var unnoted = TestCommits.commit(repository, null);
            var notesRef = notesRef();

            var noReference = notes.read(repository, notesRef, commit.name());
            write(repository, notesRef, commit, noteContent());
            var noNote = notes.read(repository, notesRef, unnoted.name());

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, noReference.outcome(), "an absent reference"),
                    () -> assertEquals("notesRef: not found", noReference.detail(), "the detail names the field"),
                    () -> assertEquals(GitOutcome.NOT_FOUND, noNote.outcome(), "a commit without a note"),
                    () -> assertEquals("note: not found", noNote.detail(), "the detail names the note"));
        }

        @Test
        @DisplayName("GDR-NFR-3: no detail carries the note's content")
        void detailsCarryNoContent(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null);
            var marker = TestCommits.name();
            var note = marker.getBytes(UTF_8);
            var notesRef = notesRef();

            var results = List.of(
                    notes.write(repository, Constants.R_NOTES, commit.name(), note, metadata(), 1),
                    notes.write(repository, notesRef, ObjectId.zeroId().name(), note, metadata(), 1),
                    notes.write(repository, notesRef, marker, note, metadata(), 1));

            assertAll(results.stream().<Executable>map(result -> () -> assertAll(
                    () -> assertFalse(result.isOk(), "the invalid input is not written"),
                    () -> assertFalse(Objects.requireNonNull(result.detail()).contains(marker),
                            "the detail carries no content"))));
        }

        @Test
        @DisplayName("fewer than one attempt and a null argument are programming errors")
        void programmingErrors(@InMemory Repository repository) throws Exception {
            var commit = TestCommits.commit(repository, null).name();
            var notesRef = notesRef();
            var note = noteContent();
            var metadata = metadata();

            assertAll(Stream.<Executable>of(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> notes.write(repository, notesRef, commit, note, metadata, 0), "zero attempts"),
                    () -> assertThrows(NullPointerException.class,
                            () -> notes.write(repository, notesRef, commit, nullValue(), metadata, 1), "a null note"),
                    () -> assertThrows(NullPointerException.class,
                            () -> notes.read(repository, nullValue(), commit), "a null notes reference"),
                    () -> assertThrows(NullPointerException.class, () -> new GitNotes(nullValue()),
                            "a null updater")));
        }
    }

    @Nested
    @DisplayName("concurrent writers")
    class ConcurrentWriters {

        @Test
        @DisplayName("GDR-CORE-10: two writers on a local repository never lose a note, a loser conflicts")
        void safetyOnLocal(@LocalRepository Repository repository) throws Exception {
            var notesRef = notesRef();
            var commits = commits(repository);
            var contents = contents();

            var results = writeConcurrently(repository, notesRef, commits, contents, LOCAL_ATTEMPTS);

            var head = refId(repository, notesRef);
            for (int i = 0; i < WRITERS; i++) {
                var result = results.get(i);
                var other = results.get(1 - i);
                if (result.isOk()) {
                    assertArrayEquals(contents.get(i), readWithNoteMap(repository, notesRef, commits.get(i)),
                            "the note of a successful writer is present");
                } else {
                    assertEquals(GitOutcome.CONFLICT, result.outcome(), "a writer that did not succeed conflicts");
                    if (other.isOk()) {
                        assertEquals(ObjectId.fromString(Objects.requireNonNull(other.value())), head,
                                "the reference holds the other writer's commit");
                    }
                }
            }
        }

        @Test
        @DisplayName("GDR-CORE-10: two writers on the in-memory database both succeed with enough attempts")
        void livenessInMemory(@InMemory Repository repository) throws Exception {
            var notesRef = notesRef();
            var commits = commits(repository);
            var contents = contents();

            var results = writeConcurrently(repository, notesRef, commits, contents, WRITERS);

            assertAll(
                    () -> assertTrue(results.stream().allMatch(GitResult::isOk), "every writer succeeds"),
                    () -> assertArrayEquals(contents.getFirst(), readWithNoteMap(repository, notesRef, commits.getFirst()),
                            "the first writer's note is present"),
                    () -> assertArrayEquals(contents.get(1), readWithNoteMap(repository, notesRef, commits.get(1)),
                            "the second writer's note is present"),
                    () -> assertEquals(WRITERS, notePaths(repository, notesRef).size(),
                            "the notes tree holds one note per writer"));
        }

        private static List<ObjectId> commits(Repository repository) throws IOException {
            List<ObjectId> commits = new ArrayList<>();
            while (commits.size() < WRITERS) {
                commits.add(TestCommits.commit(repository, null));
            }
            return commits;
        }

        private static List<byte[]> contents() {
            List<byte[]> contents = new ArrayList<>();
            while (contents.size() < WRITERS) {
                contents.add(noteContent());
            }
            return contents;
        }

        private List<GitResult<String>> writeConcurrently(Repository repository, String notesRef,
                List<ObjectId> commits, List<byte[]> contents, int attempts)
                throws InterruptedException, ExecutionException {
            // generated before the writers start, so no writer thread draws from the generators
            List<CommitMetadata> commitMetadata = new ArrayList<>();
            while (commitMetadata.size() < commits.size()) {
                commitMetadata.add(metadata());
            }
            var start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(commits.size());
            try {
                List<Future<GitResult<String>>> futures = new ArrayList<>();
                for (int i = 0; i < commits.size(); i++) {
                    var commit = commits.get(i).name();
                    var content = contents.get(i);
                    var writerMetadata = commitMetadata.get(i);
                    futures.add(pool.submit(() -> {
                        start.await();
                        return notes.write(repository, notesRef, commit, content, writerMetadata, attempts);
                    }));
                }
                start.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS), "every writer finishes");
                List<GitResult<String>> results = new ArrayList<>();
                for (var future : futures) {
                    results.add(future.get());
                }
                return results;
            } finally {
                pool.shutdownNow();
            }
        }
    }

    /**
     * Writes a notes commit with {@link #FANNED_OUT_NOTES} notes, one of them on {@code commit}, through JGit's own
     * note map and points the notes reference at it. The other notes are attached to ids of generated content, which
     * need not exist as objects.
     */
    private static void fannedOutNotes(Repository repository, String notesRef, ObjectId commit) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter();
             var formatter = new ObjectInserter.Formatter()) {
            var map = NoteMap.newEmptyMap();
            map.set(commit, inserter.insert(Constants.OBJ_BLOB, noteContent()));
            int index = 0;
            while (index < FANNED_OUT_NOTES - 1) {
                var noted = formatter.idFor(Constants.OBJ_BLOB, (TestCommits.name() + index).getBytes(UTF_8));
                if (!map.contains(noted)) {
                    map.set(noted, inserter.insert(Constants.OBJ_BLOB, noteContent()));
                    index++;
                }
            }
            var notesCommit = new CommitBuilder();
            notesCommit.setTreeId(map.writeTree(inserter));
            notesCommit.setAuthor(person());
            notesCommit.setCommitter(person());
            notesCommit.setMessage(InCoreWriterTest.message());
            var id = inserter.insert(notesCommit);
            inserter.flush();
            TestCommits.setRef(repository, notesRef, id);
        }
    }

    private static ObjectId annotatedTag(Repository repository, ObjectId commit) throws IOException {
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
