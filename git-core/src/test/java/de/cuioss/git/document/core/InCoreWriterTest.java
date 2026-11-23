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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Serial;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import de.cuioss.git.document.testing.InMemory;
import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.easymock.EasyMock;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Signer;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * In-core writing (GDR-CORE-4) against the in-memory and the local-repository fixtures of {@code git-testing}: a root
 * commit and a commit on parents are written from in-memory edits with exactly the metadata passed, a signed commit is
 * verified through {@link TestSigner}, an invalid parent or path is not found and writes nothing, a detail never
 * carries the caller's values, and a write into a bare repository on disk changes nothing outside {@code objects/}.
 */
@EnableGeneratorController
@DisplayName("InCoreWriter (GDR-CORE-4)")
class InCoreWriterTest {

    /** The directory of the object database inside a bare repository: part of git's repository layout. */
    private static final String OBJECTS = "objects";

    private final InCoreWriter writer = new InCoreWriter();

    /**
     * A signer double that fails every signing with an {@link IOException} and locates no key.
     *
     * @param message the message of the exception, so a test can check it never reaches a result
     * @return the replayed double
     * @throws Exception never; the checked exceptions of {@link Signer#sign}, whose expectation is recorded here
     */
    static Signer failingSigner(String message) throws Exception {
        var failure = new IOException(message);
        Signer signer = EasyMock.niceMock(Signer.class);
        // EasyMock mocks the default method signObject as well, which JGit 7.8.0 calls to sign an object
        signer.signObject(EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.anyObject(), EasyMock.anyObject());
        EasyMock.expectLastCall().andStubThrow(failure);
        EasyMock.expect(signer.sign(EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject(),
                EasyMock.anyObject(), EasyMock.anyObject(), EasyMock.anyObject())).andStubThrow(failure);
        EasyMock.replay(signer);
        return signer;
    }

    /**
     * Generates an identity with a whole-second time and a whole-hour offset, the precision git stores.
     *
     * @return a new identity
     */
    static PersonIdent person() {
        var when = Instant.ofEpochSecond(Generators.integers(0, Integer.MAX_VALUE).next());
        var offset = ZoneOffset.ofHours(Generators.integers(-12, 14).next());
        return new PersonIdent(TestCommits.name(), TestCommits.name() + "@example.invalid", when, offset);
    }

    /**
     * Generates file content.
     *
     * @return generated letters ended by a line feed
     */
    static String content() {
        return Generators.letterStrings(1, 40).next() + "\n";
    }

    /**
     * Generates a commit message of a subject and a body.
     *
     * @return the message
     */
    static String message() {
        return Generators.letterStrings(3, 30).next() + "\n\n" + Generators.letterStrings(3, 60).next() + "\n";
    }

    /**
     * Generates an unsigned commit's metadata.
     *
     * @return the metadata
     */
    static CommitMetadata metadata() {
        return new CommitMetadata(person(), person(), message(), null);
    }

    static TreeEdit put(String path, String content) {
        return new TreeEdit.Put(path, content.getBytes(UTF_8));
    }

    static RevCommit parse(Repository repository, String id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(ObjectId.fromString(id));
        }
    }

    /**
     * Reads every file of a commit's tree, asserting each is a regular file.
     *
     * @return the content of each file by its path
     */
    static Map<String, String> files(Repository repository, RevCommit commit) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(commit.getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                assertEquals(FileMode.REGULAR_FILE, walk.getFileMode(0), "every written file is a regular file");
                files.put(walk.getPathString(), new String(repository.open(walk.getObjectId(0)).getBytes(), UTF_8));
            }
        }
        return files;
    }

    /** The id the content has as a blob, computed without inserting it. */
    static ObjectId blobId(String content) {
        try (var formatter = new ObjectInserter.Formatter()) {
            return formatter.idFor(Constants.OBJ_BLOB, content.getBytes(UTF_8));
        }
    }

    static Map<String, ObjectId> refs(Repository repository) throws IOException {
        return repository.getRefDatabase().getRefs().stream()
                .filter(ref -> ref.getObjectId() != null)
                .collect(Collectors.toMap(Ref::getName, Ref::getObjectId));
    }

    private String writeRoot(Repository repository, Map<String, String> files) {
        var edits = files.entrySet().stream().map(file -> put(file.getKey(), file.getValue())).toList();
        var result = writer.write(repository, List.of(), metadata(), edits);
        assertTrue(result.isOk(), "the root commit is written");
        return Objects.requireNonNull(result.value());
    }

    @Nested
    @DisplayName("writing")
    class Writing {

        @Test
        @DisplayName("GDR-CORE-4: writes a root commit whose tree holds exactly the puts, and moves no reference")
        void rootCommit(@InMemory Repository repository) throws Exception {
            var directory = TestCommits.name();
            var expected = Map.of(directory + "/" + TestCommits.name(), content(), TestCommits.name(), content());
            var refsBefore = refs(repository);

            var result = writer.write(repository, List.of(), metadata(),
                    expected.entrySet().stream().map(file -> put(file.getKey(), file.getValue())).toList());

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the root commit is written"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the root commit is written"),
                    () -> assertNull(result.detail(), "a success carries no detail"),
                    () -> assertTrue(GitIdentity.isFullCommitId(result.value()), "the value is a full commit id"),
                    () -> assertEquals(0, commit.getParentCount(), "a root commit has no parent"),
                    () -> assertEquals(expected, files(repository, commit), "the tree holds exactly the puts"),
                    () -> assertEquals(refsBefore, refs(repository), "no reference moved"),
                    () -> assertNull(headCommit(repository), "HEAD stays unborn"));
        }

        @Test
        @DisplayName("GDR-CORE-4: a commit on a parent applies a put, a replacing put and a delete to the parent's tree")
        void commitOnParent(@InMemory Repository repository) throws Exception {
            var kept = TestCommits.name();
            var replaced = TestCommits.name();
            var deleted = TestCommits.name();
            var added = TestCommits.name() + "/" + TestCommits.name();
            var keptContent = content();
            var parent = writeRoot(repository, Map.of(kept, keptContent, replaced, content(), deleted, content()));
            var replacedContent = content();
            var addedContent = content();

            var result = writer.write(repository, List.of(parent), metadata(), List.of(
                    put(replaced, replacedContent), put(added, addedContent), new TreeEdit.Delete(deleted)));

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the commit is written"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the commit on the parent is written"),
                    () -> assertEquals(1, commit.getParentCount(), "the commit has the one parent"),
                    () -> assertEquals(parent, commit.getParent(0).name(), "the parent is the one passed"),
                    () -> assertEquals(Map.of(kept, keptContent, replaced, replacedContent, added, addedContent),
                            files(repository, commit), "the parent's tree with the edits applied"));
        }

        @Test
        @DisplayName("GDR-CORE-4: a put writes its mode: regular by default, executable, symbolic link, and a mode change")
        void putWritesItsMode(@InMemory Repository repository) throws Exception {
            var regular = TestCommits.name();
            var executable = TestCommits.name();
            var link = TestCommits.name();
            var content = content();
            var parent = writeRoot(repository, Map.of(regular, content()));

            var result = writer.write(repository, List.of(parent), metadata(), List.of(
                    new TreeEdit.Put(regular, content.getBytes(UTF_8), TreeEdit.Mode.EXECUTABLE_FILE),
                    new TreeEdit.Put(executable, content.getBytes(UTF_8), TreeEdit.Mode.EXECUTABLE_FILE),
                    new TreeEdit.Put(link, regular.getBytes(UTF_8), TreeEdit.Mode.SYMLINK)));

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the commit is written"));
            Map<String, FileMode> modes = new TreeMap<>();
            try (TreeWalk walk = new TreeWalk(repository)) {
                walk.addTree(commit.getTree());
                while (walk.next()) {
                    modes.put(walk.getPathString(), walk.getFileMode(0));
                }
            }
            var put = new TreeEdit.Put(regular, content.getBytes(UTF_8));
            assertAll(
                    () -> assertEquals(Map.of(regular, FileMode.EXECUTABLE_FILE, executable, FileMode.EXECUTABLE_FILE,
                            link, FileMode.SYMLINK), modes, "every put with its mode, a regular file made executable"),
                    () -> assertEquals(TreeEdit.Mode.REGULAR_FILE, put.mode(), "a regular file by default"),
                    () -> assertNotEquals(new TreeEdit.Put(regular, content.getBytes(UTF_8),
                            TreeEdit.Mode.EXECUTABLE_FILE), put, "the mode is part of the value"),
                    () -> assertTrue(put.toString().endsWith("mode=REGULAR_FILE]"), "the mode is rendered"));
        }

        @Test
        @DisplayName("GDR-CORE-4: author, committer, message and parents are exactly as passed")
        void metadataAsPassed(@InMemory Repository repository) throws Exception {
            var first = writeRoot(repository, Map.of(TestCommits.name(), content()));
            var second = writeRoot(repository, Map.of(TestCommits.name(), content()));
            var metadata = metadata();

            var result = writer.write(repository, List.of(first, second), metadata, List.of());

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the commit is written"));
            assertAll(
                    () -> assertEquals(metadata.author(), commit.getAuthorIdent(), "the author is as passed"),
                    () -> assertEquals(metadata.author().getZoneOffset(), commit.getAuthorIdent().getZoneOffset(),
                            "the author's offset is as passed"),
                    () -> assertEquals(metadata.committer(), commit.getCommitterIdent(), "the committer is as passed"),
                    () -> assertEquals(metadata.committer().getZoneOffset(),
                            commit.getCommitterIdent().getZoneOffset(), "the committer's offset is as passed"),
                    () -> assertEquals(metadata.message(), commit.getFullMessage(), "the message is as passed"),
                    () -> assertEquals(List.of(first, second),
                            Stream.of(commit.getParents()).map(RevCommit::name).toList(),
                            "the parents are as passed, in order"),
                    () -> assertEquals(parse(repository, first).getTree(), commit.getTree(),
                            "without edits the tree is the first parent's tree"),
                    () -> assertNull(commit.getRawGpgSignature(), "an unsigned commit carries no signature"),
                    () -> assertFalse(TestSigner.verify(commit), "an unsigned commit does not verify"));
        }

        @Test
        @DisplayName("GDR-CORE-4: a signed commit carries the signer's signature over the commit without it")
        void signedCommit(@InMemory Repository repository) throws Exception {
            var signer = TestSigner.signing();
            var plain = metadata();
            var metadata = new CommitMetadata(plain.author(), plain.committer(), plain.message(),
                    new CommitSigning(signer, TestSigner.KEY, null));

            var result = writer.write(repository, List.of(), metadata, List.of(put(TestCommits.name(), content())));

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the signed commit is written"));
            assertAll(
                    () -> assertNotNull(commit.getRawGpgSignature(), "the commit carries a signature"),
                    () -> assertTrue(TestSigner.verify(commit), "the signature verifies"),
                    () -> assertEquals(1, signer.payloads().size(), "the signer is called once"),
                    () -> assertArrayEquals(TestSigner.signedPayload(commit), signer.payloads().getFirst(),
                            "the signer signed exactly the commit without its signature"),
                    () -> assertEquals(List.of(metadata.committer()), signer.committers(),
                            "the committer is the signing identity"),
                    () -> assertEquals(List.of(Optional.of(TestSigner.KEY)), signer.signingKeys(),
                            "the signing key is passed to the signer"),
                    () -> assertEquals(metadata.author(), commit.getAuthorIdent(), "signing keeps the author"),
                    () -> assertEquals(metadata.message(), commit.getFullMessage(), "signing keeps the message"));
        }

        @Test
        @DisplayName("GDR-CORE-4: a signing failure is a failure naming the exception class, and no commit is returned")
        void signingFailure(@InMemory Repository repository) throws Exception {
            var marker = TestCommits.name();
            var plain = metadata();
            var metadata = new CommitMetadata(plain.author(), plain.committer(), plain.message(),
                    new CommitSigning(failingSigner(marker), null, null));

            var result = writer.write(repository, List.of(), metadata, List.of(put(TestCommits.name(), content())));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "a signing failure is a failure"),
                    () -> assertNull(result.value(), "a failure carries no commit"),
                    () -> assertEquals(IOException.class.getSimpleName(), result.detail(),
                            "the detail names the exception class, never its message"));
        }

        @Test
        @DisplayName("GDR-NFR-3: a failure of an anonymous exception class is a failure with a nonblank detail")
        void anonymousFailure(@InMemory Repository repository) {
            var marker = TestCommits.name();
            var plain = metadata();
            var metadata = new CommitMetadata(plain.author(), plain.committer(), plain.message(),
                    new CommitSigning(anonymouslyFailing(marker), null, null));

            var result = writer.write(repository, List.of(), metadata, List.of(put(TestCommits.name(), content())));

            var detail = Objects.requireNonNull(result.detail(), "a failure carries a detail");
            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the signing failure is a failure"),
                    () -> assertNull(result.value(), "a failure carries no commit"),
                    () -> assertFalse(detail.isBlank(), "the detail is not blank although the simple name is empty"),
                    () -> assertFalse(detail.contains(marker), "the detail carries no message of the failure"));
        }

        @Test
        @DisplayName("a null argument or list element is rejected")
        void nullArguments(@InMemory Repository repository) {
            var metadata = metadata();
            List<String> parents = List.of();
            List<TreeEdit> edits = List.of();
            List<@Nullable TreeEdit> nullEdit = new ArrayList<>();
            nullEdit.add(null);

            assertAll(Stream.<Executable>of(
                    () -> writer.write(nullValue(), parents, metadata, edits),
                    () -> writer.write(repository, nullValue(), metadata, edits),
                    () -> writer.write(repository, parents, nullValue(), edits),
                    () -> writer.write(repository, parents, metadata, nullValue()),
                    () -> writer.write(repository, parents, metadata, nonNull(nullEdit)))
                    .<Executable>map(call -> () -> assertThrows(NullPointerException.class, call, "null is rejected")));
        }
    }

    @Nested
    @DisplayName("not found")
    class NotFound {

        @Test
        @DisplayName("GDR-CORE-4: a parent that is no commit of the repository is not found, nothing is written")
        void absentParent(@InMemory Repository repository) {
            var content = content();

            var result = writer.write(repository, List.of(ObjectId.zeroId().name()), metadata(),
                    List.of(put(TestCommits.name(), content)));

            assertNothingWritten(repository, result, "parents: not found", content);
        }

        @Test
        @DisplayName("GDR-CORE-4: a parent that names a blob is not found, nothing is written")
        void parentIsNoCommit(@InMemory Repository repository) throws Exception {
            ObjectId blob;
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                blob = inserter.insert(Constants.OBJ_BLOB, content().getBytes(UTF_8));
                inserter.flush();
            }
            var content = content();

            var result = writer.write(repository, List.of(blob.name()), metadata(),
                    List.of(put(TestCommits.name(), content)));

            assertNothingWritten(repository, result, "parents: not found", content);
        }

        @Test
        @DisplayName("GDR-CORE-4: a parent that names an annotated tag of a commit is not found, nothing is written")
        void parentIsAnnotatedTag(@InMemory Repository repository) throws Exception {
            var tagged = writeRoot(repository, Map.of(TestCommits.name(), content()));
            var tag = new TagBuilder();
            tag.setObjectId(ObjectId.fromString(tagged), Constants.OBJ_COMMIT);
            tag.setTag(TestCommits.name());
            tag.setTagger(person());
            tag.setMessage(message());
            ObjectId tagId;
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                tagId = inserter.insert(tag);
                inserter.flush();
            }
            var content = content();

            var result = writer.write(repository, List.of(tagId.name()), metadata(),
                    List.of(put(TestCommits.name(), content)));

            assertNothingWritten(repository, result, "parents: not found", content);
        }

        @Test
        @DisplayName("GDR-CORE-11: an abbreviated parent id is no full commit id and is not found")
        void abbreviatedParent(@InMemory Repository repository) {
            var parent = writeRoot(repository, Map.of(TestCommits.name(), content()));
            var content = content();

            var result = writer.write(repository, List.of(parent.substring(0, 12)), metadata(),
                    List.of(put(TestCommits.name(), content)));

            assertNothingWritten(repository, result, "parents: not a full commit id", content);
        }

        /**
         * Paths git rejects in a tree; each breaks one rule of git's path check.
         *
         * @return the invalid paths
         */
        static Stream<String> invalidPaths() {
            return Stream.of("", "/leading", "trailing/", "double//slash", ".git/config", "dot/../dot", "./dot");
        }

        @ParameterizedTest(name = "[{0}]")
        @MethodSource("invalidPaths")
        @DisplayName("GDR-CORE-4: an invalid path of a put is not found, nothing is written")
        void invalidPutPath(String path, @InMemory Repository repository) {
            var content = content();

            var result = writer.write(repository, List.of(), metadata(), List.of(put(path, content)));

            assertNothingWritten(repository, result, "edits: invalid path", content);
        }

        @Test
        @DisplayName("GDR-CORE-4: an invalid path of a delete is not found, nothing is written")
        void invalidDeletePath(@InMemory Repository repository) {
            var content = content();

            var result = writer.write(repository, List.of(), metadata(),
                    List.of(put(TestCommits.name(), content), new TreeEdit.Delete(".git/config")));

            assertNothingWritten(repository, result, "edits: invalid path", content);
        }

        @Test
        @DisplayName("GDR-CORE-4: two edits of one path are not found, nothing is written")
        void duplicatePath(@InMemory Repository repository) {
            var path = TestCommits.name();
            var content = content();

            var result = writer.write(repository, List.of(), metadata(),
                    List.of(put(path, content), new TreeEdit.Delete(path)));

            assertNothingWritten(repository, result, "edits: duplicate path", content);
        }

        @Test
        @DisplayName("GDR-NFR-3: a detail names the field and the reason, never an identity, message, content or path")
        void detailsCarryNoInput(@InMemory Repository repository) throws Exception {
            var marker = TestCommits.name();
            var plain = metadata();
            var metadata = new CommitMetadata(new PersonIdent(marker, marker + "@example.invalid"),
                    new PersonIdent(marker, marker + "@example.invalid"), marker + "\n", null);
            var signed = new CommitMetadata(plain.author(), plain.committer(), plain.message(),
                    new CommitSigning(failingSigner(marker), marker, null));
            var markedPut = put(marker, marker);

            var results = List.of(
                    writer.write(repository, List.of(marker + "@example.invalid"), metadata, List.of(markedPut)),
                    writer.write(repository, List.of(), metadata, List.of(put(marker + "/../" + marker, marker))),
                    writer.write(repository, List.of(), metadata, List.of(markedPut, new TreeEdit.Delete(marker))),
                    writer.write(repository, List.of(), signed, List.of(markedPut)));

            assertAll(results.stream().<Executable>map(result -> () -> assertAll(
                    () -> assertFalse(result.isOk(), "the invalid input is not written"),
                    () -> assertFalse(Objects.requireNonNull(result.detail()).contains(marker),
                            "the detail carries no value of the input"))));
        }

        private static void assertNothingWritten(Repository repository, GitResult<String> result, String detail,
                String content) {
            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "the invalid input is not found"),
                    () -> assertNull(result.value(), "no commit is returned"),
                    () -> assertEquals(detail, result.detail(), "the detail names the field and the reason"),
                    () -> assertFalse(repository.getObjectDatabase().has(blobId(content)),
                            "the content of the put is not written"));
        }
    }

    @Nested
    @DisplayName("tree source")
    class TreeSourceWriting {

        @Test
        @DisplayName("GDR-CORE-5: the tree the source returns is the commit's tree, the source receives the parent's tree")
        void treeTakenAsReturned(@InMemory Repository repository) throws Exception {
            var parent = writeRoot(repository, Map.of(TestCommits.name(), content()));
            var path = TestCommits.name();
            var content = content();
            var source = new SingleFileSource(path, content);

            var result = writer.writeTree(repository, List.of(parent), metadata(), source);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the commit is written"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the commit is written"),
                    () -> assertEquals(List.of(parent), Stream.of(commit.getParents()).map(RevCommit::name).toList(),
                            "the parents are as passed"),
                    () -> assertEquals(source.written(), commit.getTree(), "the commit's tree is the returned tree"),
                    () -> assertEquals(Map.of(path, content), files(repository, commit),
                            "the tree holds exactly what the source inserted, nothing of the parent's tree"),
                    () -> assertEquals(Collections.singletonList(parse(repository, parent).getTree().name()),
                            source.parentTrees(), "the source is called once with the first parent's tree"));
        }

        @Test
        @DisplayName("GDR-CORE-5: a root commit hands the source no parent tree")
        void rootCommitWithoutParentTree(@InMemory Repository repository) throws Exception {
            var path = TestCommits.name();
            var content = content();
            var source = new SingleFileSource(path, content);
            var refsBefore = refs(repository);

            var result = writer.writeTree(repository, List.of(), metadata(), source);

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the root commit is written"));
            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the root commit is written"),
                    () -> assertEquals(0, commit.getParentCount(), "a root commit has no parent"),
                    () -> assertEquals(Collections.singletonList(null), source.parentTrees(),
                            "the source is called once without a parent tree"),
                    () -> assertEquals(Map.of(path, content), files(repository, commit),
                            "the tree is the source's tree"),
                    () -> assertEquals(refsBefore, refs(repository), "no reference moved"));
        }

        @Test
        @DisplayName("GDR-NFR-3: an I/O failure of the source is a failure naming the exception class, nothing flushed")
        void sourceFailure(@InMemory Repository repository) {
            var marker = TestCommits.name();
            var content = content();

            var result = writer.writeTree(repository, List.of(), metadata(), (reader, inserter, parentTree) -> {
                inserter.insert(Constants.OBJ_BLOB, content.getBytes(UTF_8));
                throw new IOException(marker);
            });

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the source's failure is a failure"),
                    () -> assertNull(result.value(), "a failure carries no commit"),
                    () -> assertEquals(IOException.class.getSimpleName(), result.detail(),
                            "the detail names the exception class, never its message"),
                    () -> assertFalse(repository.getObjectDatabase().has(blobId(content)),
                            "the object the source inserted is not flushed"));
        }

        @Test
        @DisplayName("GDR-CORE-4: an abbreviated parent is not found and the source is never called")
        void abbreviatedParent(@InMemory Repository repository) {
            var parent = writeRoot(repository, Map.of(TestCommits.name(), content()));
            var source = new SingleFileSource(TestCommits.name(), content());

            var result = writer.writeTree(repository, List.of(parent.substring(0, 12)), metadata(), source);

            assertAll(
                    () -> assertEquals(GitOutcome.NOT_FOUND, result.outcome(), "the abbreviated parent is not found"),
                    () -> assertEquals("parents: not a full commit id", result.detail(), "the detail names the field"),
                    () -> assertTrue(source.parentTrees().isEmpty(), "the source is never called"));
        }

        @Test
        @DisplayName("a null argument is rejected")
        void nullArguments(@InMemory Repository repository) {
            var metadata = metadata();
            List<String> parents = List.of();
            var source = new SingleFileSource(TestCommits.name(), content());

            assertAll(Stream.<Executable>of(
                    () -> writer.writeTree(nullValue(), parents, metadata, source),
                    () -> writer.writeTree(repository, nullValue(), metadata, source),
                    () -> writer.writeTree(repository, parents, nullValue(), source),
                    () -> writer.writeTree(repository, parents, metadata, nullValue()))
                    .<Executable>map(call -> () -> assertThrows(NullPointerException.class, call, "null is rejected")));
        }
    }

    @Nested
    @DisplayName("one path strictness on every host")
    class PathStrictness {

        /**
         * Paths the checks for Windows or macOS refuse and the platform check of Linux accepts, so each is refused on
         * every host only because the writer applies one strictness: a Windows device name, also with an extension
         * and below a directory, a component ending in a dot or a space, a character Windows forbids, and a name HFS+
         * folds to {@code .git} (a zero-width non-joiner inside it).
         *
         * @return the paths
         */
        static Stream<String> refusedOnEveryHost() {
            return Stream.of("CON", "aux.txt", "dir/nul", "trailing.", "trailing ", "colon:name", "back\\slash",
                    "pipe|name", ".g‌it");
        }

        @ParameterizedTest(name = "[{0}]")
        @MethodSource("refusedOnEveryHost")
        @DisplayName("GDR-CORE-4: a path refused on Windows or macOS is refused on every host, nothing is written")
        void refusedOnEveryHost(String path, @InMemory Repository repository) {
            var content = content();

            var result = writer.write(repository, List.of(), metadata(), List.of(put(path, content)));

            NotFound.assertNothingWritten(repository, result, "edits: invalid path", content);
        }

        @Test
        @DisplayName("GDR-CORE-4: two paths equal without case are a duplicate, nothing is written")
        void caseFoldDuplicate(@InMemory Repository repository) {
            var path = TestCommits.name();
            var content = content();

            var result = writer.write(repository, List.of(), metadata(),
                    List.of(put(path, content), put(path.toUpperCase(Locale.ROOT), "upper-" + content)));

            NotFound.assertNothingWritten(repository, result, "edits: duplicate path", content);
        }

        @Test
        @DisplayName("GDR-CORE-4: a path that is a directory of another edit's path is refused, nothing is written")
        void overlappingPaths(@InMemory Repository repository) {
            var directory = TestCommits.name();
            var below = directory + "/" + TestCommits.name();
            var deeper = below + "/" + TestCommits.name();
            var content = content();
            List<List<TreeEdit>> overlapping = List.of(
                    List.of(put(directory, content), put(below, content)),
                    List.of(put(below, content), put(directory, content)),
                    List.of(put(directory, content), put(deeper, content)),
                    List.of(new TreeEdit.Delete(directory), put(below, content)),
                    List.of(put(directory.toUpperCase(Locale.ROOT), content), put(below, content)));

            assertAll(overlapping.stream().<Executable>map(edits -> () -> NotFound.assertNothingWritten(repository,
                    writer.write(repository, List.of(), metadata(), edits), "edits: overlapping path", content)));
        }

        @Test
        @DisplayName("GDR-CORE-4: a path that only shares a prefix of characters with another is no overlap")
        void sharedPrefixIsNoOverlap(@InMemory Repository repository) throws Exception {
            var name = TestCommits.name();
            var sibling = name + "-" + TestCommits.name() + "/" + TestCommits.name();
            var first = "first-" + content();
            var second = "second-" + content();

            var result = writer.write(repository, List.of(), metadata(), List.of(put(name, first), put(sibling, second)));

            var commit = parse(repository, Objects.requireNonNull(result.value(), "the commit is written"));
            assertEquals(Map.of(name, first, sibling, second), files(repository, commit),
                    "both paths are written, the shared characters are no directory");
        }
    }

    @Nested
    @DisplayName("failures JGit raises unchecked")
    class UncheckedFailures {

        /**
         * Names JGit's path check refuses on every host, so a tree holding one can only have been written by another
         * tool, through an {@link ObjectInserter}.
         *
         * @return the names
         */
        static Stream<String> namesRefusedInATree() {
            return Stream.of(".GIT", ".Git", "git~1", "GIT~1");
        }

        @ParameterizedTest(name = "[{0}]")
        @MethodSource("namesRefusedInATree")
        @DisplayName("GDR-CORE-4: a parent tree holding a path JGit refuses is a failure naming the class only")
        void parentTreeWithRefusedPath(String name, @InMemory Repository repository) throws Exception {
            var parent = commitWithTree(repository, Map.of(name, content(), TestCommits.name(), content()));
            var content = "put-" + content();

            var result = writer.write(repository, List.of(parent.name()), metadata(),
                    List.of(put(TestCommits.name(), content)));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the refused path is a failure"),
                    () -> assertNull(result.value(), "a failure carries no commit"),
                    () -> assertEquals("InvalidPathException", result.detail(),
                            "the detail names the exception class, never the path"),
                    () -> assertFalse(repository.getObjectDatabase().has(blobId(content)),
                            "the content of the put is not written"));
        }

        @Test
        @DisplayName("GDR-CORE-4: an invalid gpg.format of a signed commit is a failure, and the signer is not called")
        void invalidGpgFormat(@InMemory Repository repository) throws Exception {
            repository.getConfig().setString(ConfigConstants.CONFIG_GPG_SECTION, null,
                    ConfigConstants.CONFIG_KEY_FORMAT, "bogus-" + TestCommits.name());
            var signer = TestSigner.signing();
            var plain = metadata();
            var signed = new CommitMetadata(plain.author(), plain.committer(), plain.message(),
                    new CommitSigning(signer, TestSigner.KEY, null));
            var content = "signed-" + content();

            var result = writer.write(repository, List.of(), signed, List.of(put(TestCommits.name(), content)));
            var unsigned = writer.write(repository, List.of(), metadata(),
                    List.of(put(TestCommits.name(), "unsigned-" + content())));

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the invalid format is a failure"),
                    () -> assertNull(result.value(), "a failure carries no commit"),
                    () -> assertEquals(IllegalArgumentException.class.getSimpleName(), result.detail(),
                            "the detail names the exception class, never the value"),
                    () -> assertTrue(signer.payloads().isEmpty(), "the signer is not called"),
                    () -> assertFalse(repository.getObjectDatabase().has(blobId(content)),
                            "the content of the put is not written"),
                    () -> assertEquals(GitOutcome.OK, unsigned.outcome(),
                            "an unsigned commit does not read gpg.format and is written"));
        }

        @Test
        @DisplayName("GDR-CORE-4: an unchecked exception of the caller's signer propagates unchanged")
        void signerExceptionPropagates(@InMemory Repository repository) {
            var failure = new IllegalStateException(TestCommits.name());
            var plain = metadata();
            var signed = new CommitMetadata(plain.author(), plain.committer(), plain.message(),
                    new CommitSigning(throwing(failure), null, null));
            var content = "thrown-" + content();
            var edits = List.of(put(TestCommits.name(), content));
            List<String> parents = List.of();

            var thrown = assertThrows(IllegalStateException.class, () -> writer.write(repository, parents, signed,
                    edits), "the signer's exception is not mapped to an outcome");

            assertAll(
                    () -> assertSame(failure, thrown, "the very exception of the signer propagates"),
                    () -> assertFalse(repository.getObjectDatabase().has(blobId(content)),
                            "nothing is flushed"));
        }

        @Test
        @DisplayName("GDR-CORE-4: a tree of the source too large to be read in one piece is a failure naming the class")
        void largeTreeOfTheSource(@InMemory Repository repository) {
            var result = writer.writeTree(repository, List.of(), metadata(), (reader, inserter, parentTree) -> {
                throw new LargeObjectException();
            });

            assertAll(
                    () -> assertEquals(GitOutcome.FAILED, result.outcome(), "the large tree is a failure"),
                    () -> assertNull(result.value(), "a failure carries no commit"),
                    () -> assertEquals(LargeObjectException.class.getSimpleName(), result.detail(),
                            "the detail names the exception class"));
        }

        @Test
        @DisplayName("GDR-CORE-4: any other unchecked exception of the source propagates unchanged")
        void otherSourceExceptionPropagates(@InMemory Repository repository) {
            var failure = new IllegalStateException(TestCommits.name());
            var metadata = metadata();
            List<String> parents = List.of();

            var thrown = assertThrows(IllegalStateException.class,
                    () -> writer.writeTree(repository, parents, metadata, (reader, inserter, parentTree) -> {
                        throw failure;
                    }), "the source's exception is not mapped to an outcome");

            assertSame(failure, thrown, "the very exception of the source propagates");
        }
    }

    /**
     * Writes a commit whose tree holds exactly the regular files, through a {@link TreeFormatter}, so no path check of
     * JGit runs: the way another tool could have written a tree JGit refuses to read into a {@code DirCache}.
     *
     * @param repository the repository receiving the commit
     * @param files      the content of each file by its name, a single path component each
     * @param parents    the parents of the commit
     * @return the id of the commit
     * @throws IOException if an object cannot be inserted
     */
    static ObjectId commitWithTree(Repository repository, Map<String, String> files, ObjectId... parents)
            throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            for (var file : new TreeMap<>(files).entrySet()) {
                tree.append(file.getKey(), FileMode.REGULAR_FILE,
                        inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(UTF_8)));
            }
            var commit = new CommitBuilder();
            commit.setTreeId(inserter.insert(tree));
            commit.setParentIds(parents);
            commit.setAuthor(person());
            commit.setCommitter(person());
            commit.setMessage(message());
            var id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }

    /** A signer that throws the given unchecked exception from every signing. */
    private static Signer throwing(RuntimeException failure) {
        return new Signer() {

            @Override
            public GpgSignature sign(Repository repository, GpgConfig config, byte[] data, PersonIdent committer,
                    @Nullable String signingKey, @Nullable CredentialsProvider credentialsProvider) {
                throw failure;
            }

            @Override
            public boolean canLocateSigningKey(Repository repository, GpgConfig config, PersonIdent committer,
                    @Nullable String signingKey, @Nullable CredentialsProvider credentialsProvider) {
                return true;
            }
        };
    }

    /**
     * A tree source that inserts a tree of one regular file and records the parent trees it is called with.
     */
    static final class SingleFileSource implements InCoreWriter.TreeSource {

        private final String path;
        private final String content;
        private final List<@Nullable String> parentTrees = new ArrayList<>();
        private @Nullable ObjectId written;

        SingleFileSource(String path, String content) {
            this.path = path;
            this.content = content;
        }

        @Override
        public ObjectId write(ObjectReader reader, ObjectInserter inserter, @Nullable ObjectId firstParentTree)
                throws IOException {
            parentTrees.add(firstParentTree == null ? null : firstParentTree.name());
            var tree = new TreeFormatter();
            tree.append(path, FileMode.REGULAR_FILE, inserter.insert(Constants.OBJ_BLOB, content.getBytes(UTF_8)));
            var id = inserter.insert(tree);
            written = id;
            return id;
        }

        /** The parent trees of the calls, in order; {@code null} for a root commit. */
        List<@Nullable String> parentTrees() {
            return new ArrayList<>(parentTrees);
        }

        /** The tree of the last call. */
        ObjectId written() {
            return Objects.requireNonNull(written, "the source was called");
        }
    }

    @Test
    @DisplayName("GDR-CORE-4: writing into a bare repository on disk changes no file outside objects/ and no reference")
    void onlyObjectsChange(@LocalRepository Repository repository) throws Exception {
        var parent = TestCommits.commitOn(repository, GitIdentity.branchRef(TestCommits.name()));
        var root = repository.getDirectory().toPath();
        var filesBefore = snapshot(root);
        var refsBefore = refs(repository);
        var content = content();
        var path = TestCommits.name();

        var result = writer.write(repository, List.of(parent.name()), metadata(), List.of(put(path, content)));

        var commit = parse(repository, Objects.requireNonNull(result.value(), "the commit is written"));
        assertAll(
                () -> assertFalse(filesBefore.isEmpty(), "the repository holds files outside objects/"),
                () -> assertEquals(filesBefore, snapshot(root), "no file outside objects/ changed"),
                () -> assertEquals(refsBefore, refs(repository), "no reference changed"),
                () -> assertTrue(repository.getObjectDatabase().has(commit), "the commit is in the object database"),
                () -> assertEquals(content, files(repository, commit).get(path), "the commit holds the put"));
    }

    /** The content of every regular file below the repository directory outside {@code objects/}, by its path. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> files = paths.filter(Files::isRegularFile)
                    .filter(file -> !root.relativize(file).startsWith(OBJECTS))
                    .toList();
            Map<String, String> snapshot = new TreeMap<>();
            for (Path file : files) {
                snapshot.put(root.relativize(file).toString(), HexFormat.of().formatHex(Files.readAllBytes(file)));
            }
            return snapshot;
        }
    }

    /** The commit {@code HEAD} points at; {@code null} while it is unborn. */
    private static @Nullable ObjectId headCommit(Repository repository) throws IOException {
        Ref head = repository.exactRef(Constants.HEAD);
        return head == null ? null : head.getObjectId();
    }

    /**
     * A signer that fails every signing with an instance of an anonymous {@link IOException} subclass, whose simple
     * class name is empty.
     *
     * @param message the message of the exception, so a test can check it never reaches a result
     */
    private static Signer anonymouslyFailing(String message) {
        return new Signer() {

            @Override
            public GpgSignature sign(Repository repository, GpgConfig config, byte[] data, PersonIdent committer,
                    @Nullable String signingKey, @Nullable CredentialsProvider credentialsProvider) throws IOException {
                throw new IOException(message) {

                    @Serial
                    private static final long serialVersionUID = 1L;
                };
            }

            @Override
            public boolean canLocateSigningKey(Repository repository, GpgConfig config, PersonIdent committer,
                    @Nullable String signingKey, @Nullable CredentialsProvider credentialsProvider) {
                return true;
            }
        };
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    private static <T> T nullValue() {
        return nonNull(null);
    }

    /** Hands a value past the null-marked signature unchecked, to exercise the null checks. */
    @SuppressWarnings("unchecked")
    private static <T> T nonNull(@Nullable Object value) {
        return (T) value;
    }
}
