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
package de.cuioss.git.document.store;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.LogRecord;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-attempt step of an explicit save against a {@code git-testing} local remote (GDR-STORE-3): the step runs on
 * every attempt and sees the new tip on a re-application, its edits land in the one save commit, its refusal pushes
 * nothing, and a step that throws ends the save after one attempt with the class name only. With every log record
 * captured down to trace, no path, content or pseudonym reaches a log record or a detail (GDR-NFR-3).
 * {@link ScriptedRepositoryOperations} moves the remote between fetch and push, so the first attempt loses its lease.
 * <p>
 * Names, ids, pseudonyms, paths and contents are generated, long enough to appear nowhere by chance; the details are
 * the specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("SaveStep (GDR-STORE-3)")
class SaveStepTest {

    /** The attempts of every test: enough for one lost lease. */
    private static final int ATTEMPTS = 3;

    @TempDir
    Path temp;

    /** A path segment long enough to appear in no log text by chance. */
    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 16, 24).next();
    }

    /** A content long enough to appear in no log text by chance. */
    private static String content() {
        return Generators.letterStrings(24, 40).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static DocumentKey key() {
        var collection = value(DocumentCollection.of("c" + word(), Kind.PER_DOCUMENT));
        return value(DocumentKey.of(collection, Ulid.next()));
    }

    private static StoreConfiguration configuration() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", ATTEMPTS));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static @Nullable String text(Optional<byte[]> content) {
        return content.map(found -> new String(found, StandardCharsets.UTF_8)).orElse(null);
    }

    private static List<TreeEdit> puts(Map<String, String> files) {
        return files.entrySet().stream().<TreeEdit>map(file -> new TreeEdit.Put(file.getKey(), bytes(file.getValue())))
                .toList();
    }

    /** Writes a commit holding exactly the files, nested paths included, onto a reference, forcing the reference. */
    private static ObjectId commit(Repository repository, String ref, Map<String, String> files,
            ObjectId... parents) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, String> file : files.entrySet()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, bytes(file.getValue())));
                builder.add(entry);
            }
            builder.finish();
            var commit = new CommitBuilder();
            var ident = new PersonIdent(pseudonym(), "");
            commit.setTreeId(index.writeTree(inserter));
            commit.setParentIds(parents);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(ref);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            var result = update.update();
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.FAST_FORWARD, () -> "the fixture reference is written: " + result);
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static @Nullable ObjectId tip(Repository repository, String ref) throws IOException {
        Ref found = repository.exactRef(ref);
        return found == null ? null : found.getObjectId();
    }

    private static RevCommit parse(Repository repository, ObjectId id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(id);
        }
    }

    /** The files of a commit, path to content. */
    private static Map<String, String> tree(Repository repository, ObjectId id) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(parse(repository, id).getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                files.put(walk.getPathString(), new String(repository.open(walk.getObjectId(0)).getBytes(),
                        StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    /** The log records of the save, the class that runs the step. */
    private static List<LogRecord> saveRecords() {
        return TestLoggerFactory.getTestHandler().getRecords().stream()
                .filter(logRecord -> ExplicitSave.class.getName().equals(logRecord.getLoggerName())).toList();
    }

    /** Every text a captured log record carries: message, parameters and the chain of its throwable. */
    private static List<String> logTexts() {
        List<String> texts = new ArrayList<>();
        for (LogRecord logRecord : TestLoggerFactory.getTestHandler().getRecords()) {
            texts.add(String.valueOf(logRecord.getMessage()));
            Object[] parameters = logRecord.getParameters();
            if (parameters != null) {
                for (Object parameter : parameters) {
                    texts.add(String.valueOf(parameter));
                }
            }
            for (Throwable thrown = logRecord.getThrown(); thrown != null; thrown = thrown.getCause()) {
                texts.add(String.valueOf(thrown));
            }
        }
        return texts;
    }

    /** Asserts that no log text and no detail carries one of the secrets. */
    private static void assertNothingLeaks(String detail, List<String> secrets) {
        List<String> texts = new ArrayList<>(logTexts());
        texts.add(detail);
        for (String secret : secrets) {
            assertTrue(texts.stream().noneMatch(text -> text.contains(secret)),
                    () -> "a log record or the detail carries a path, a content or a pseudonym");
        }
    }

    /** The writer under test with its clone and its transport double. */
    private record Fixture(StoreWriter writer, Path gitDir, ScriptedRepositoryOperations operations)
            implements AutoCloseable {

        @Nullable
        ObjectId localTip(String ref) throws IOException {
            try (Repository clone = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true)
                         .build()) {
                return tip(clone, ref);
            }
        }

        String draft(Author author, DocumentKey key, List<TreeEdit> edits) {
            return value(writer.draft(author, key, edits, word()));
        }

        SaveResult save(Author author, DocumentKey key, SaveStep step) {
            return writer.save(author, key, Map.of(), word(), step);
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var gitDir = temp.resolve(word() + ".git");
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var operations = new ScriptedRepositoryOperations();
        var now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(), operations,
                Clock.fixed(now, ZoneOffset.UTC)));
        return new Fixture(writer, gitDir, operations);
    }

    @Test
    @DisplayName("GDR-STORE-3: the step runs on every attempt and sees the new tip on a re-application")
    void runsOnEveryAttemptAndSeesTheNewTip(@Remote LocalRemote remote) {
        var key = key();
        var target = StoreReferences.target(key);
        var path = word();
        var start = commit(remote.repository(), target, Map.of(path, content()));
        var concurrent = content();
        var author = Author.of(pseudonym());
        List<String> tips = new ArrayList<>();
        List<String> tipContents = new ArrayList<>();

        try (var fixture = open(remote)) {
            fixture.draft(author, key, puts(Map.of(word() + "x", content())));
            ObjectId[] moved = new ObjectId[1];
            fixture.operations().beforeNextPush(() -> moved[0] = commit(remote.repository(), target,
                    Map.of(path, concurrent), start));

            var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, attempt -> {
                tips.add(attempt.tip());
                tipContents.add(text(attempt.tipContent(path)));
                return new SaveStep.Edits(List.of());
            }));

            assertAll(
                    () -> assertEquals(List.of(start.name(), moved[0].name()), tips,
                            "the step sees the tip of each attempt"),
                    () -> assertEquals(concurrent, tipContents.getLast(), "the re-application reads the new tip"),
                    () -> assertEquals(2, fixture.operations().pushes().size(), "two attempts"),
                    () -> assertEquals(List.of(moved[0]),
                            List.of(parse(remote.repository(), ObjectId.fromString(saved.commitId())).getParents()),
                            "the save commit is on the new tip"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-3: the step's edits land in the one save commit whose only parent is the tip")
    void editsLandInTheSaveCommit(@Remote LocalRemote remote) {
        var key = key();
        var target = StoreReferences.target(key);
        var kept = word() + "a";
        var replaced = word() + "b";
        var added = word() + "c";
        var stepped = word() + "d/" + word();
        var keptContent = content();
        var tipId = commit(remote.repository(), target, Map.of(kept, keptContent, replaced, content()));
        var draftAdded = content();
        var stepReplaced = content();
        var stepAdded = content();
        var author = Author.of(pseudonym());

        try (var fixture = open(remote)) {
            fixture.draft(author, key, puts(Map.of(replaced, content(), added, draftAdded)));

            var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key,
                    attempt -> new SaveStep.Edits(List.of(new TreeEdit.Put(replaced, bytes(stepReplaced)),
                            new TreeEdit.Put(stepped, bytes(stepAdded))))));

            var commit = ObjectId.fromString(saved.commitId());
            assertAll(
                    () -> assertEquals(commit, tip(remote.repository(), target), "the remote target moved"),
                    () -> assertEquals(List.of(tipId), List.of(parse(remote.repository(), commit).getParents()),
                            "the only parent is the tip"),
                    () -> assertEquals(Map.of(kept, keptContent, replaced, stepReplaced, added, draftAdded, stepped,
                                    stepAdded), tree(remote.repository(), commit),
                            "the step's edit replaces the draft's edit of the same path, beside the draft's edits"),
                    () -> assertEquals(1, fixture.operations().pushes().size(), "one push of one commit"),
                    () -> assertNull(fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                            "the draft is deleted"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-5: a step put that replaces a directory of the tip is a conflict; nothing is pushed")
    void stepPutReplacingADirectoryConflicts(@Remote LocalRemote remote) {
        var key = key();
        var target = StoreReferences.target(key);
        var directory = word() + "d";
        var keep = directory + "/" + word();
        var changed = word() + "x";
        var tipFiles = Map.of(keep, content(), changed, content());
        var tipId = commit(remote.repository(), target, tipFiles);
        var author = Author.of(pseudonym());

        try (var fixture = open(remote)) {
            var draft = fixture.draft(author, key, puts(Map.of(changed, content())));

            var result = fixture.save(author, key,
                    attempt -> new SaveStep.Edits(List.of(new TreeEdit.Put(directory, bytes(content())))));

            assertAll(
                    () -> assertEquals(new SaveResult.Conflicting(List.of(directory)), result,
                            "the step's path conflicts"),
                    () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                    () -> assertEquals(tipId, tip(remote.repository(), target), "the remote is unchanged"),
                    () -> assertEquals(tipFiles, tree(remote.repository(), tipId), "the directory keeps its file"),
                    () -> assertEquals(ObjectId.fromString(draft),
                            fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                            "the draft is unchanged"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-5: a step put that turns a file of the tip into a directory is a conflict")
    void stepPutBelowAFileConflicts(@Remote LocalRemote remote) {
        var key = key();
        var target = StoreReferences.target(key);
        var file = word() + "f";
        var changed = word() + "x";
        var below = file + "/" + word();
        var tipId = commit(remote.repository(), target, Map.of(file, content(), changed, content()));
        var author = Author.of(pseudonym());

        try (var fixture = open(remote)) {
            fixture.draft(author, key, puts(Map.of(changed, content())));

            var result = fixture.save(author, key,
                    attempt -> new SaveStep.Edits(List.of(new TreeEdit.Put(below, bytes(content())))));

            assertAll(
                    () -> assertEquals(new SaveResult.Conflicting(List.of(below)), result, "the step's path conflicts"),
                    () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                    () -> assertEquals(tipId, tip(remote.repository(), target), "the remote is unchanged"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-5: a step edit disjoint from the tip's files, such as a counter, still saves")
    void disjointStepEditSaves(@Remote LocalRemote remote) throws Exception {
        var key = key();
        var target = StoreReferences.target(key);
        var directory = word() + "d";
        var keep = directory + "/" + word();
        var counter = directory + "/" + DocumentPaths.COUNTER;
        var changed = word() + "x";
        var keepContent = content();
        var changedContent = content();
        var counterContent = content();
        commit(remote.repository(), target, Map.of(keep, keepContent, changed, content()));
        var author = Author.of(pseudonym());

        try (var fixture = open(remote)) {
            fixture.draft(author, key, puts(Map.of(changed, changedContent)));

            var saved = assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key,
                    attempt -> new SaveStep.Edits(List.of(new TreeEdit.Put(counter, bytes(counterContent))))));

            assertEquals(Map.of(keep, keepContent, changed, changedContent, counter, counterContent),
                    tree(remote.repository(), ObjectId.fromString(saved.commitId())),
                    "the counter beside the directory's file and the draft's change");
        }
    }

    @Test
    @DisplayName("GDR-STORE-3: the step reads the tip and the tree the draft's changes produce on it")
    void readsTipAndProducedTree(@Remote LocalRemote remote) {
        var key = key();
        var target = StoreReferences.target(key);
        var directory = word() + "d";
        var kept = directory + "/" + word() + "a";
        var modified = directory + "/" + word() + "b";
        var deleted = word() + "c";
        var added = directory + "/" + word() + "e";
        var outside = word() + "f";
        var keptContent = content();
        var baseModified = content();
        var draftModified = content();
        var deletedContent = content();
        var addedContent = content();
        var tipId = commit(remote.repository(), target, Map.of(kept, keptContent, modified, baseModified, deleted,
                deletedContent, outside, content()));
        var author = Author.of(pseudonym());
        Map<String, Object> seen = new LinkedHashMap<>();

        try (var fixture = open(remote)) {
            List<TreeEdit> draftEdits = new ArrayList<>(puts(Map.of(modified, draftModified, added, addedContent)));
            draftEdits.add(new TreeEdit.Delete(deleted));
            fixture.draft(author, key, draftEdits);

            assertInstanceOf(SaveResult.Saved.class, fixture.save(author, key, attempt -> {
                seen.put("tip", attempt.tip());
                seen.put("tip modified", text(attempt.tipContent(modified)));
                seen.put("tip deleted", text(attempt.tipContent(deleted)));
                seen.put("tip added", text(attempt.tipContent(added)));
                seen.put("kept", text(attempt.content(kept)));
                seen.put("modified", text(attempt.content(modified)));
                seen.put("deleted", text(attempt.content(deleted)));
                seen.put("added", text(attempt.content(added)));
                seen.put("directory", attempt.paths(directory));
                seen.put("whole", attempt.paths(""));
                return new SaveStep.Edits(List.of());
            }));

            assertAll(
                    () -> assertEquals(tipId.name(), seen.get("tip"), "the tip"),
                    () -> assertEquals(baseModified, seen.get("tip modified"), "the tip holds the base content"),
                    () -> assertEquals(deletedContent, seen.get("tip deleted"), "the tip holds the deleted file"),
                    () -> assertNull(seen.get("tip added"), "the tip lacks the added file"),
                    () -> assertEquals(keptContent, seen.get("kept"), "an unchanged file of the produced tree"),
                    () -> assertEquals(draftModified, seen.get("modified"), "the draft's content"),
                    () -> assertNull(seen.get("deleted"), "the deleted file is absent from the produced tree"),
                    () -> assertEquals(addedContent, seen.get("added"), "the added file"),
                    () -> assertEquals(List.of(kept, modified, added).stream().sorted().toList(),
                            seen.get("directory"), "the files below the directory"),
                    () -> assertEquals(List.of(kept, modified, added, outside).stream().sorted().toList(),
                            seen.get("whole"), "every file of the produced tree"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-3: a refusal pushes nothing and leaves draft and branch unchanged, outcome REFUSED")
    void refusalChangesNothing(@Remote LocalRemote remote) {
        var key = key();
        var target = StoreReferences.target(key);
        var path = word();
        var baseContent = content();
        var draftContent = content();
        var tipId = commit(remote.repository(), target, Map.of(path, baseContent));
        var author = Author.of(pseudonym());

        try (var fixture = open(remote)) {
            var draft = fixture.draft(author, key, puts(Map.of(path, draftContent)));

            var result = fixture.save(author, key, attempt -> new SaveStep.Refusal());

            var notSaved = assertInstanceOf(SaveResult.NotSaved.class, result);
            assertAll(
                    () -> assertEquals(new SaveResult.NotSaved(StoreOutcome.REFUSED, target + ": step: refused"),
                            result, "refused, the detail names the target only"),
                    () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                    () -> assertEquals(tipId, tip(remote.repository(), target), "the remote is unchanged"),
                    () -> assertEquals(tipId, fixture.localTip(target), "the local target is the fetched tip"),
                    () -> assertEquals(ObjectId.fromString(draft),
                            fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                            "the draft is unchanged"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            GitStoreLogMessages.WARN.SAVE_NOT_COMPLETED.resolveIdentifierString()),
                    () -> assertNothingLeaks(notSaved.reason(),
                            List.of(path, baseContent, draftContent, author.pseudonym())));
        }
    }

    @Test
    @DisplayName("GDR-STORE-3: a throwing step ends FAILED after one attempt, naming the class only, without throwable")
    void throwingStepFailsAfterOneAttempt(@Remote LocalRemote remote) {
        var key = key();
        var target = StoreReferences.target(key);
        var path = word();
        var baseContent = content();
        var draftContent = content();
        var tipId = commit(remote.repository(), target, Map.of(path, baseContent));
        var author = Author.of(pseudonym());
        var message = path + " " + draftContent + " " + author.pseudonym();
        int[] calls = new int[1];

        try (var fixture = open(remote)) {
            var draft = fixture.draft(author, key, puts(Map.of(path, draftContent)));

            var result = fixture.save(author, key, attempt -> {
                calls[0]++;
                throw new StepBrokeException(message);
            });

            var notSaved = assertInstanceOf(SaveResult.NotSaved.class, result);
            assertAll(
                    () -> assertEquals(new SaveResult.NotSaved(new StoreResult.PassedOn(GitOutcome.FAILED),
                            target + ": step: StepBrokeException"), result, "failed, naming the class only"),
                    () -> assertEquals(1, calls[0], "the step ran once"),
                    () -> assertEquals(1, fixture.operations().fetches().size(), "exactly one attempt"),
                    () -> assertEquals(List.of(), fixture.operations().pushes(), "nothing is pushed"),
                    () -> assertEquals(tipId, tip(remote.repository(), target), "the remote is unchanged"),
                    () -> assertEquals(ObjectId.fromString(draft),
                            fixture.localTip(value(StoreReferences.draft(author.pseudonym(), key))),
                            "the draft is unchanged"),
                    () -> assertFalse(saveRecords().isEmpty(), "the save was logged"),
                    () -> assertTrue(saveRecords().stream().allMatch(logRecord -> logRecord.getThrown() == null),
                            "no log record carries a throwable"),
                    () -> assertNothingLeaks(notSaved.reason(),
                            List.of(path, baseContent, draftContent, author.pseudonym())));
        }
    }

    @Test
    @DisplayName("GDR-STORE-3: the edits of a step are copied and reject a missing edit")
    void editsRejectMissingEdit() {
        List<TreeEdit> edits = Arrays.asList(new TreeEdit.Delete(word()), null);

        assertThrows(NullPointerException.class, () -> new SaveStep.Edits(edits));
    }

    /** The exception of a step that breaks, whose message carries a path, a content and a pseudonym. */
    private static final class StepBrokeException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        StepBrokeException(String message) {
            super(message);
        }
    }
}
