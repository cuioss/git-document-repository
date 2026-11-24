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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
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
 * The checkpoints of the anchoring through {@link StoreWriter#anchor()} against a {@code git-testing} local remote
 * (GDR-STORE-16): the tree holds the single file {@code refs} with one line {@code <object id> SP <reference name> LF}
 * per listed reference sorted by name in byte order, without checkpoint tags and anchor notes; the parent is the previous
 * checkpoint, none for the first; the message is {@code Checkpoint <n>}; author and committer are the technical user;
 * the commit is signed and carries no identity trailers (GDR-STORE-12, GDR-STORE-13); the tags are numbered from 1, a
 * tag on the remote is never moved, and a checkpoint whose tag push failed is kept and its tag pushed by the next due
 * check.
 * <p>
 * Names, ids and contents are generated; the reference layout, the file name, the message and the outcomes are the
 * specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("Checkpoints (GDR-STORE-16)")
class CheckpointsTest {

    /** The checkpoint interval of every test. */
    private static final Duration INTERVAL = Duration.ofHours(1);

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static StoreConfiguration configuration(String provider) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", word()));
        tree.put("anchor", Map.of("provider", provider, "checkpoint-interval", INTERVAL.toString(),
                "retry-interval", Duration.ofHours(12).toString()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    /** A clock that stands still until a test moves it. */
    private static final class MovableClock extends Clock {

        private volatile Instant now;

        MovableClock(Instant start) {
            now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The writer under test with its doubles. */
    private record Fixture(StoreWriter writer, ScriptedRepositoryOperations operations, TestKeys.Source signing,
    ScriptedAnchorProvider provider, TechnicalUser user, MovableClock clock, Path gitDir) implements AutoCloseable {

        StoreWriter.Anchoring anchor() {
            return value(writer.anchor());
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var operations = new ScriptedRepositoryOperations();
        var clock = new MovableClock(Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next()));
        var signing = TestKeys.ed25519().source();
        var provider = new ScriptedAnchorProvider("a" + word());
        var gitDir = temp.resolve(word() + ".git");
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(provider.id()), List.of(),
                new StoreProviders(signing, new TestPersonDirectory(), provider), operations, clock));
        return new Fixture(writer, operations, signing, provider, user, clock, gitDir);
    }

    /** Writes a commit holding one generated file at a time onto a reference, forcing the reference. */
    private static ObjectId commit(Repository repository, String ref, Instant time, ObjectId... parents)
            throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(word(), FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "", time, ZoneOffset.UTC);
            commit.setTreeId(inserter.insert(tree));
            commit.setParentIds(parents);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            setRef(repository, ref, id);
            return id;
        }
    }

    private static ObjectId commit(Repository repository, String ref, ObjectId... parents) throws IOException {
        return commit(repository, ref, Instant.now(), parents);
    }

    private static void setRef(Repository repository, String ref, ObjectId id) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(id);
        update.setForceUpdate(true);
        var result = update.update();
        assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                || result == RefUpdate.Result.FAST_FORWARD, () -> "the fixture reference is written: " + result);
    }

    private static @Nullable ObjectId tip(Repository repository, String ref) throws IOException {
        Ref found = repository.exactRef(ref);
        return found == null ? null : found.getObjectId();
    }

    private static ObjectId checkpoint(Repository repository, int number) throws IOException {
        @Nullable ObjectId found = tip(repository, StoreReferences.checkpoint(number));
        assertNotNull(found, () -> "checkpoint " + number + " exists");
        return found;
    }

    private static RevCommit parse(Repository repository, ObjectId id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(id);
            walk.parseBody(commit);
            return commit;
        }
    }

    /** The files of a commit, path to content. */
    private static Map<String, String> files(Repository repository, ObjectId id) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(parse(repository, id).getTree());
            walk.setRecursive(true);
            while (walk.next()) {
                assertEquals(FileMode.REGULAR_FILE, walk.getFileMode(0), "a regular file");
                files.put(walk.getPathString(),
                        new String(repository.open(walk.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    @Test
    @DisplayName("GDR-STORE-16: the tree holds the file refs, one line per listed reference sorted in byte order")
    void treeListsReferencesInByteOrder(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN);
        String upper = "refs/heads/Z" + word();
        String lower = "refs/heads/a" + word();
        commit(repository, upper, main);
        commit(repository, lower, main);
        setRef(repository, "refs/tags/" + word(), main);
        commit(repository, "refs/notes/" + word());
        commit(repository, StoreReferences.anchorNotes("other-" + word()));

        try (var fixture = open(remote)) {
            Map<String, ObjectId> listed = new TreeMap<>();
            for (Ref ref : repository.getRefDatabase().getRefs()) {
                if (StoreReferences.listedByCheckpoint(ref.getName())) {
                    listed.put(ref.getName(), ref.getObjectId());
                }
            }
            var expected = new StringBuilder();
            listed.forEach((name, id) -> expected.append(id.name()).append(' ').append(name).append('\n'));

            fixture.anchor();

            var files = files(repository, checkpoint(repository, 1));
            String refs = files.get(Checkpoints.REFS_FILE);
            assertAll(
                    () -> assertEquals(List.of(Checkpoints.REFS_FILE), List.copyOf(files.keySet()),
                            "the single file refs"),
                    () -> assertEquals(expected.toString(), refs, "one line per listed reference, sorted by name"),
                    () -> assertTrue(refs.indexOf(upper) < refs.indexOf(lower), "byte order: Z before a"),
                    () -> assertFalse(refs.contains(StoreReferences.ANCHOR_NOTES), "no anchor notes listed"),
                    () -> assertFalse(refs.contains(StoreReferences.CHECKPOINTS), "no checkpoint tag listed"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-16: the checkpoint is a signed commit of the technical user, Checkpoint <n>, without trailers")
    void commitOfTechnicalUserSignedWithoutTrailers(@Remote LocalRemote remote) throws Exception {
        commit(remote.repository(), StoreReferences.MAIN);

        try (var fixture = open(remote)) {
            int signCalls = fixture.signing().signCalls();

            fixture.anchor();

            var commit = parse(remote.repository(), checkpoint(remote.repository(), 1));
            String raw = new String(commit.getRawBuffer(), StandardCharsets.ISO_8859_1);
            assertAll(
                    () -> assertEquals("Checkpoint 1", commit.getFullMessage().strip(), "the message"),
                    () -> assertFalse(commit.getFullMessage().contains("Actor"), "no identity trailers"),
                    () -> assertEquals(0, commit.getParentCount(), "the first checkpoint has no parent"),
                    () -> assertEquals(fixture.user().name(), commit.getAuthorIdent().getName(),
                            "the technical user is the author"),
                    () -> assertEquals(fixture.user().name(), commit.getCommitterIdent().getName(),
                            "the technical user is the committer"),
                    () -> assertEquals("", commit.getCommitterIdent().getEmailAddress(), "no e-mail by default"),
                    () -> assertEquals(fixture.clock().instant(), commit.getCommitterIdent().getWhenAsInstant(),
                            "the commit time is the time of the clock"),
                    () -> assertTrue(raw.contains("\ngpgsig -----BEGIN SSH SIGNATURE-----"),
                            "signed in the SSH signature format"),
                    () -> assertTrue(fixture.signing().signCalls() > signCalls, "signed through the key source"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-16: checkpoints are numbered from 1, each the parent of the next, and no tag is moved")
    void numberedFromOneInParentChain(@Remote LocalRemote remote) throws Exception {
        var main = commit(remote.repository(), StoreReferences.MAIN);

        try (var fixture = open(remote)) {
            List<ObjectId> tags = new ArrayList<>();
            for (int n = 1; n <= 3; n++) {
                fixture.anchor();
                tags.add(checkpoint(remote.repository(), n));
                main = commit(remote.repository(), StoreReferences.MAIN, main);
                fixture.clock().advance(INTERVAL);
            }

            var first = tags.getFirst();
            var second = tags.get(1);
            var third = tags.get(2);
            assertAll(
                    () -> assertNull(tip(remote.repository(), "refs/tags/checkpoints/0"), "no checkpoint 0"),
                    () -> assertEquals(first, checkpoint(remote.repository(), 1), "tag 1 never moved"),
                    () -> assertEquals(second, checkpoint(remote.repository(), 2), "tag 2 never moved"),
                    () -> assertEquals(0, parse(remote.repository(), first).getParentCount(), "1 has no parent"),
                    () -> assertEquals(List.of(first), List.of(parse(remote.repository(), second).getParents()),
                            "1 is the parent of 2"),
                    () -> assertEquals(List.of(second), List.of(parse(remote.repository(), third).getParents()),
                            "2 is the parent of 3"),
                    () -> assertEquals("Checkpoint 3", parse(remote.repository(), third).getFullMessage().strip(),
                            "the number in the message"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-16: a tag taken on the remote meanwhile is never moved; the next checkpoint follows it")
    void remoteTagNeverMoved(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        String tag = StoreReferences.checkpoint(1);

        try (var fixture = open(remote)) {
            fixture.operations().beforeNextPush(() -> {
                try {
                    setRef(repository, tag, main);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

            fixture.anchor();
            ObjectId keptAfterFailure;
            ObjectId localTagAfterFailure;
            try (Repository clone = new FileRepositoryBuilder().setGitDir(fixture.gitDir().toFile())
                         .setMustExist(true).build()) {
                keptAfterFailure = tip(clone, StoreReferences.retainedCheckpoints() + 1);
                localTagAfterFailure = tip(clone, tag);
            }
            var tagAfterFailure = tip(repository, tag);
            fixture.anchor();

            var second = checkpoint(repository, 2);
            ObjectId keptAfterwards;
            try (Repository clone = new FileRepositoryBuilder().setGitDir(fixture.gitDir().toFile())
                         .setMustExist(true).build()) {
                keptAfterwards = tip(clone, StoreReferences.retainedCheckpoints() + 1);
            }
            assertAll(
                    () -> assertEquals(main, tagAfterFailure, "the tag taken on the remote stays"),
                    () -> assertNotNull(keptAfterFailure, "the checkpoint whose tag was refused is kept"),
                    () -> assertNull(localTagAfterFailure, "its local tag was deleted again"),
                    () -> assertEquals(main, checkpoint(repository, 1), "tag 1 was never moved"),
                    () -> assertEquals(List.of(main), List.of(parse(repository, second).getParents()),
                            "the next checkpoint follows the one on the remote"),
                    () -> assertNull(keptAfterwards, "the kept checkpoint no longer follows and is dropped"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            GitStoreLogMessages.WARN.ANCHOR_PUSH_INCOMPLETE.resolveIdentifierString()));
        }
    }

    @Test
    @DisplayName("GDR-STORE-16: a checkpoint whose tag push failed is kept, and the next due check pushes its tag")
    void failedTagPushRepairedByNextDueCheck(@Remote LocalRemote remote) throws Exception {
        commit(remote.repository(), StoreReferences.MAIN);
        String tag = StoreReferences.checkpoint(1);

        try (var fixture = open(remote)) {
            fixture.operations().failPushTo(tag, GitOutcome.UNREACHABLE);

            var failed = fixture.anchor();
            ObjectId kept;
            try (Repository clone = new FileRepositoryBuilder().setGitDir(fixture.gitDir().toFile())
                         .setMustExist(true).build()) {
                kept = tip(clone, StoreReferences.retainedCheckpoints() + 1);
            }
            var remoteTagAfterFailure = tip(remote.repository(), tag);
            var repaired = fixture.anchor();

            assertAll(
                    () -> assertNull(remoteTagAfterFailure, "the tag is not on the remote"),
                    () -> assertNotNull(kept, "the checkpoint commit is kept"),
                    () -> assertEquals(List.of(), failed.anchored(), "nothing anchored without the tag"),
                    () -> assertEquals(kept, tip(remote.repository(), tag), "the kept checkpoint got its tag"),
                    () -> assertEquals(List.of(Objects.requireNonNull(kept).name()), repaired.anchored(),
                            "and is anchored"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.checkpoint(2)),
                            "no new checkpoint was made instead"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                            GitStoreLogMessages.WARN.ANCHOR_PUSH_INCOMPLETE.resolveIdentifierString()));
        }
    }

    @Test
    @DisplayName("GDR-STORE-16: a kept checkpoint whose tag push fails again blocks a new checkpoint of its number")
    void keptCheckpointBlocksCreation(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN);
        String tag = StoreReferences.checkpoint(1);

        try (var fixture = open(remote)) {
            fixture.operations().failPushTo(tag, GitOutcome.UNREACHABLE, GitOutcome.UNREACHABLE);
            fixture.anchor();
            var kept = Objects.requireNonNull(cloneTip(fixture, StoreReferences.retainedCheckpoints() + 1),
                    "the checkpoint is kept");
            commit(repository, StoreReferences.MAIN, main);
            fixture.clock().advance(INTERVAL);

            var blocked = fixture.anchor();
            var keptWhileBlocked = cloneTip(fixture, StoreReferences.retainedCheckpoints() + 1);
            var repaired = fixture.anchor();

            List<String> tagPushes = fixture.operations().pushes().stream()
                    .filter(push -> push.remoteRef().equals(tag)).map(PushInput::localRef).toList();
            assertAll(
                    () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of()), blocked,
                            "nothing is anchored while the kept checkpoint waits"),
                    () -> assertEquals(kept, keptWhileBlocked, "no new checkpoint took its number"),
                    () -> assertEquals(List.of(kept.name(), kept.name(), kept.name()), tagPushes,
                            "every push of tag 1 carries the kept checkpoint"),
                    () -> assertEquals(kept, tip(repository, tag), "the kept checkpoint got its tag"),
                    () -> assertEquals(kept.name(), repaired.anchored().getFirst(), "and is anchored first"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-16: a checkpoint whose kept reference cannot be written gets no tag")
    void unkeptCheckpointNotPushed(@Remote LocalRemote remote) throws Exception {
        commit(remote.repository(), StoreReferences.MAIN);
        String tag = StoreReferences.checkpoint(1);

        try (var fixture = open(remote)) {
            // a reference at the name of the kept prefix makes the creation of every kept checkpoint fail
            String prefix = StoreReferences.retainedCheckpoints();
            cloneRef(fixture, prefix.substring(0, prefix.length() - 1), Objects.requireNonNull(
                    tip(remote.repository(), StoreReferences.MAIN)));

            var anchoring = fixture.anchor();

            assertAll(
                    () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of()), anchoring,
                            "nothing is anchored"),
                    () -> assertTrue(fixture.operations().pushes().stream()
                            .noneMatch(push -> push.remoteRef().equals(tag)), "no tag push"),
                    () -> assertNull(tip(remote.repository(), tag), "no tag on the remote"),
                    () -> assertNull(cloneTip(fixture, tag), "no local tag"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-16: a local tag left after a failed push ends the due check before subject discovery")
    void strandedTagEndsDueCheck(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        String tag = StoreReferences.checkpoint(1);

        try (var fixture = open(remote)) {
            Path lock = fixture.gitDir().resolve(tag + ".lock");
            fixture.operations().beforeNextPush(() -> {
                try {
                    // the remote takes the number, so the push fails; the lock keeps the local tag from being deleted
                    setRef(repository, tag, main);
                    Files.createFile(lock);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });

            var result = fixture.writer().anchor();
            Files.delete(lock);

            assertAll(
                    () -> assertEquals(StoreOutcome.INCOMPLETE, result.outcome(), "the due check ends incomplete"),
                    () -> assertEquals(1, fixture.operations().pushes().size(), "after the one failed tag push"),
                    () -> assertNotNull(cloneTip(fixture, tag), "the local tag stayed"),
                    () -> assertEquals(List.of(), fixture.provider().calls(), "no proof is asked for"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-16: an annotated checkpoint tag is peeled, so the next checkpoint follows its commit")
    void annotatedCheckpointTagPeeled(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var annotated = new TagBuilder();
            annotated.setObjectId(main, Constants.OBJ_COMMIT);
            annotated.setTag("checkpoints/1");
            annotated.setTagger(new PersonIdent(word(), "", Instant.ofEpochSecond(100_000), ZoneOffset.UTC));
            annotated.setMessage(word());
            var tagObject = inserter.insert(annotated);
            inserter.flush();
            setRef(repository, StoreReferences.checkpoint(1), tagObject);
        }

        try (var fixture = open(remote)) {
            fixture.anchor();

            var second = parse(repository, checkpoint(repository, 2));
            assertEquals(List.of(main), List.of(second.getParents()), "the parent is the peeled commit");
        }
    }

    private static @Nullable ObjectId cloneTip(Fixture fixture, String ref) throws IOException {
        try (Repository clone = new FileRepositoryBuilder().setGitDir(fixture.gitDir().toFile()).setMustExist(true)
                     .build()) {
            return tip(clone, ref);
        }
    }

    private static void cloneRef(Fixture fixture, String ref, ObjectId id) throws IOException {
        try (Repository clone = new FileRepositoryBuilder().setGitDir(fixture.gitDir().toFile()).setMustExist(true)
                     .build()) {
            setRef(clone, ref, id);
        }
    }
}
