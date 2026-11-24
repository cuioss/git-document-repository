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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The anchor proofs as notes through {@link StoreWriter#anchor()} against a {@code git-testing} local remote
 * (GDR-STORE-17): the base64 of each proof is the note of the anchored commit under
 * {@code refs/notes/anchors/<provider>}, in a signed notes commit of the technical user without identity trailers,
 * pushed one note per push with the lease of the notes reference as the last fetch mirrored it, separately from the
 * commits of a save; the pending subjects are derived from the remote notes as of the last fetch; a proof whose push
 * failed is kept locally, pushed by the next due check and not requested again; {@code previous} is the newest
 * anchored proof; every proof is bound to the notes tip it extends, so a moved tip ends the due check and a kept proof
 * of a moved tip is requested anew; a proof that cannot be kept is not published; and the fetch of a due check reaches
 * the listeners.
 * <p>
 * Names, ids and contents are generated; the reference layout, the message prefix and the outcomes are the specified
 * vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("Anchor proofs as notes (GDR-STORE-17)")
class AnchorNotesTest {

    /** The fixed claims of a save. */
    private static final IdentityClaims CLAIMS = new IdentityClaims("https://issuer.example/realms/test",
            Instant.parse("2026-03-01T08:15:30Z"), "urn:example:acr:mfa", List.of("pwd", "otp"));

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static DocumentKey document() {
        var collection = value(DocumentCollection.of("c" + word(), Kind.PER_DOCUMENT));
        return value(DocumentKey.of(collection, Ulid.next()));
    }

    private static StoreConfiguration configuration(String provider) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", word()));
        tree.put("anchor", Map.of("provider", provider, "checkpoint-interval", Duration.ofHours(1).toString(),
                "retry-interval", Duration.ofHours(12).toString()));
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    private static Instant start() {
        return Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
    }

    /** The writer under test with its doubles. */
    private record Fixture(StoreWriter writer, ScriptedRepositoryOperations operations,
    ScriptedAnchorProvider provider, TechnicalUser user, Path gitDir) implements AutoCloseable {

        StoreWriter.Anchoring anchor() {
            return value(writer.anchor());
        }

        String notesRef() {
            return StoreReferences.anchorNotes(provider.id());
        }

        @Override
        public void close() {
            writer.close();
        }
    }

    private Fixture open(LocalRemote remote, String providerId, Instant now) {
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var operations = new ScriptedRepositoryOperations();
        var provider = new ScriptedAnchorProvider(providerId);
        var gitDir = temp.resolve(word() + ".git");
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(providerId), List.of(),
                new StoreProviders(TestKeys.ed25519().source(), new TestPersonDirectory(), provider), operations,
                Clock.fixed(now, ZoneOffset.UTC)));
        return new Fixture(writer, operations, provider, user, gitDir);
    }

    private Fixture open(LocalRemote remote) {
        return open(remote, "a" + word(), start());
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
            RefUpdate update = repository.updateRef(ref);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            var result = update.update();
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.FAST_FORWARD, () -> "the fixture reference is written: " + result);
            return id;
        }
    }

    private static void delete(Repository repository, String ref) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setForceUpdate(true);
        assertEquals(RefUpdate.Result.FORCED, update.delete(), "the fixture reference is deleted");
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

    /** The annotated commits of a notes reference with their raw note. */
    private static Map<ObjectId, byte[]> notes(Repository repository, String notesRef) throws IOException {
        Map<ObjectId, byte[]> notes = new LinkedHashMap<>();
        @Nullable ObjectId tip = tip(repository, notesRef);
        if (tip == null) {
            return notes;
        }
        try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
            for (Note note : NoteMap.read(reader, walk.parseCommit(tip))) {
                notes.put(note.copy(), reader.open(note.getData()).getBytes());
            }
        }
        return notes;
    }

    private static byte[] encoded(ObjectId subject) {
        return Base64.getEncoder().encode(ScriptedAnchorProvider.proofOf(subject));
    }

    private static @Nullable ObjectId cloneTip(Fixture fixture, String ref) throws IOException {
        try (Repository clone = new FileRepositoryBuilder().setGitDir(fixture.gitDir().toFile()).setMustExist(true)
                     .build()) {
            return tip(clone, ref);
        }
    }

    private static byte[] cloneBlob(Fixture fixture, ObjectId blob) throws IOException {
        try (Repository clone = new FileRepositoryBuilder().setGitDir(fixture.gitDir().toFile()).setMustExist(true)
                     .build()) {
            return clone.open(blob, Constants.OBJ_BLOB).getBytes();
        }
    }

    /** Writes a reference in the clone of a fixture onto a new blob, forcing the reference. */
    private static void cloneRef(Fixture fixture, String ref) throws IOException {
        try (Repository clone = new FileRepositoryBuilder().setGitDir(fixture.gitDir().toFile()).setMustExist(true)
                     .build(); ObjectInserter inserter = clone.newObjectInserter()) {
            var blob = inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8));
            inserter.flush();
            RefUpdate update = clone.updateRef(ref);
            update.setNewObjectId(blob);
            update.setForceUpdate(true);
            assertEquals(RefUpdate.Result.NEW, update.update(), "the fixture reference is written");
        }
    }

    /** Writes the base64 of a proof as the note of a subject onto a notes reference, as another clone would. */
    private static ObjectId note(Repository repository, String notesRef, ObjectId subject, byte[] proof)
            throws IOException {
        @Nullable ObjectId parent = tip(repository, notesRef);
        try (ObjectInserter inserter = repository.newObjectInserter(); ObjectReader reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(reader)) {
            NoteMap notes = parent == null ? NoteMap.newEmptyMap() : NoteMap.read(reader, walk.parseCommit(parent));
            notes.set(subject, inserter.insert(Constants.OBJ_BLOB, Base64.getEncoder().encode(proof)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "", Instant.ofEpochSecond(100_000), ZoneOffset.UTC);
            commit.setTreeId(notes.writeTree(inserter));
            if (parent != null) {
                commit.setParentId(parent);
            }
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(notesRef);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            var result = update.update();
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.FAST_FORWARD, () -> "the fixture note is written: " + result);
            return id;
        }
    }

    /**
     * Writes one notes commit onto a notes reference, as a tool other than the store would: the notes of its parent,
     * the current tip, with the base64 of every given proof added; with no proof given it adds no note.
     */
    private static ObjectId notesCommit(Repository repository, String notesRef, Map<ObjectId, byte[]> proofs)
            throws IOException {
        @Nullable ObjectId parent = tip(repository, notesRef);
        try (ObjectInserter inserter = repository.newObjectInserter(); ObjectReader reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(reader)) {
            NoteMap notes = parent == null ? NoteMap.newEmptyMap() : NoteMap.read(reader, walk.parseCommit(parent));
            for (Map.Entry<ObjectId, byte[]> proof : proofs.entrySet()) {
                notes.set(proof.getKey(), inserter.insert(Constants.OBJ_BLOB,
                        Base64.getEncoder().encode(proof.getValue())));
            }
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "", Instant.ofEpochSecond(100_000), ZoneOffset.UTC);
            commit.setTreeId(notes.writeTree(inserter));
            if (parent != null) {
                commit.setParentId(parent);
            }
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(notesRef);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            var result = update.update();
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.FAST_FORWARD, () -> "the fixture notes commit is written: " + result);
            return id;
        }
    }

    private static List<PushInput> notePushes(Fixture fixture) {
        return fixture.operations().pushes().stream().filter(push -> push.remoteRef().equals(fixture.notesRef()))
                .toList();
    }

    @Test
    @DisplayName("GDR-STORE-17: the base64 proof is the note of the subject in a signed notes commit of the technical user")
    void base64NoteUnderProviderReference(@Remote LocalRemote remote) throws Exception {
        commit(remote.repository(), StoreReferences.MAIN, Instant.ofEpochSecond(100_000));

        try (var fixture = open(remote)) {
            fixture.anchor();

            var subject = checkpoint(remote.repository(), 1);
            var notesCommit = parse(remote.repository(),
                    Objects.requireNonNull(tip(remote.repository(), fixture.notesRef()), "the notes reference"));
            String raw = new String(notesCommit.getRawBuffer(), StandardCharsets.ISO_8859_1);
            var notes = notes(remote.repository(), fixture.notesRef());
            assertAll(
                    () -> assertEquals(List.of(subject), List.copyOf(notes.keySet()), "one note, on the subject"),
                    () -> assertArrayEquals(encoded(subject), notes.get(subject), "the base64 of the proof"),
                    () -> assertEquals(AnchorNotes.MESSAGE + subject.name(), notesCommit.getFullMessage().strip(),
                            "the message names the subject"),
                    () -> assertFalse(notesCommit.getFullMessage().contains("Actor"), "no identity trailers"),
                    () -> assertEquals(fixture.user().name(), notesCommit.getAuthorIdent().getName(),
                            "the technical user is the author"),
                    () -> assertEquals(fixture.user().name(), notesCommit.getCommitterIdent().getName(),
                            "the technical user is the committer"),
                    () -> assertTrue(raw.contains("\ngpgsig -----BEGIN SSH SIGNATURE-----"),
                            "signed in the SSH signature format"),
                    () -> assertEquals(StoreReferences.ANCHOR_NOTES + fixture.provider().id(), fixture.notesRef(),
                            "the notes reference of the provider"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: one note per push, with the lease of the fetched notes reference, apart from a save")
    void pushedWithLeaseSeparatelyFromSaveCommits(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        var key = document();
        commit(repository, StoreReferences.target(key), Instant.ofEpochSecond(150_000), main);
        commit(repository, StoreReferences.releaseTag(document(), 1), Instant.ofEpochSecond(200_000));

        try (var fixture = open(remote)) {
            var author = Author.of(word());
            value(fixture.writer().draft(author, key,
                    List.of(new TreeEdit.Put(word(), word().getBytes(StandardCharsets.UTF_8))), word()));
            var saved = assertInstanceOf(SaveResult.Saved.class,
                    fixture.writer().save(author, CLAIMS, key, Map.of(), word()), "the save is saved");

            var anchoring = fixture.anchor();

            List<PushInput> notePushes = fixture.operations().pushes().stream()
                    .filter(push -> push.remoteRef().equals(fixture.notesRef())).toList();
            List<PushInput> savePushes = fixture.operations().pushes().stream()
                    .filter(push -> push.remoteRef().equals(StoreReferences.target(key))).toList();
            var notesTip = Objects.requireNonNull(tip(repository, fixture.notesRef()), "the notes reference");
            var secondNotes = parse(repository, notesTip);
            var firstNotes = secondNotes.getParent(0);
            assertAll(
                    () -> assertEquals(2, anchoring.anchored().size(), "the release commit and the checkpoint"),
                    () -> assertEquals(2, notePushes.size(), "one push per note"),
                    () -> assertEquals(ObjectId.zeroId().name(), notePushes.getFirst().expectedRemoteHead(),
                            "the first lease expects no notes reference"),
                    () -> assertEquals(firstNotes.name(), notePushes.getFirst().localRef(), "the first notes commit"),
                    () -> assertEquals(firstNotes.name(), notePushes.get(1).expectedRemoteHead(),
                            "the second lease expects the first notes commit"),
                    () -> assertEquals(notesTip.name(), notePushes.get(1).localRef(), "the second notes commit"),
                    () -> assertEquals(1, savePushes.size(), "the save pushed its target once"),
                    () -> assertEquals(saved.commitId(), savePushes.getFirst().localRef(), "with the save commit"),
                    () -> assertTrue(notePushes.stream().noneMatch(push -> push.localRef().equals(saved.commitId())),
                            "no note push carries the save commit"),
                    () -> assertEquals(1, secondNotes.getParentCount(), "notes commits form their own chain"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: the pending subjects are derived from the remote notes as of the last fetch")
    void pendingDerivedFromRemoteNotes(@Remote LocalRemote remote) throws Exception {
        commit(remote.repository(), StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        var providerId = "a" + word();
        var now = start();

        try (var first = open(remote, providerId, now); var second = open(remote, providerId, now)) {
            first.anchor();
            var subject = checkpoint(remote.repository(), 1);

            var annotatedElsewhere = second.anchor();
            int callsBefore = second.provider().calls().size();
            delete(remote.repository(), second.notesRef());
            var afterRemoval = second.anchor();

            assertAll(
                    () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of()), annotatedElsewhere,
                            "a subject with a note on the remote is not pending"),
                    () -> assertEquals(0, callsBefore, "the second clone asked for nothing"),
                    () -> assertEquals(List.of(subject.name()), afterRemoval.anchored(),
                            "without the remote note the subject is pending again"),
                    () -> assertEquals(List.of(subject), second.provider().subjects(), "and is asked for"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: a proof whose push failed is kept locally, pushed by the next due check, never re-requested")
    void keptProofPushedAndNotRerequested(@Remote LocalRemote remote) throws Exception {
        commit(remote.repository(), StoreReferences.MAIN, Instant.ofEpochSecond(100_000));

        try (var fixture = open(remote)) {
            fixture.operations().failPushTo(fixture.notesRef(), GitOutcome.UNREACHABLE);

            var failed = fixture.anchor();
            var subject = checkpoint(remote.repository(), 1);
            String keptRef = StoreReferences.retainedProofs(fixture.provider().id()) + subject.name();
            var keptAfterFailure = cloneTip(fixture, keptRef);
            var localNotesAfterFailure = cloneTip(fixture, fixture.notesRef());
            var remoteNotesAfterFailure = tip(remote.repository(), fixture.notesRef());
            var pushed = fixture.anchor();

            assertAll(
                    () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of(subject.name())), failed,
                            "the subject stays pending"),
                    () -> assertNotNull(keptAfterFailure, "the proof is kept under its local-only reference"),
                    () -> assertNull(localNotesAfterFailure, "the local notes reference is set back"),
                    () -> assertNull(remoteNotesAfterFailure, "nothing reached the remote"),
                    () -> assertEquals(List.of(subject.name()), pushed.anchored(), "the kept proof is pushed"),
                    () -> assertEquals(1, fixture.provider().calls().size(), "and never requested again"),
                    () -> assertArrayEquals(encoded(subject), notes(remote.repository(), fixture.notesRef())
                            .get(subject), "the kept proof is the note"),
                    () -> assertNull(cloneTip(fixture, keptRef), "the kept reference is gone"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: previous is the newest anchored proof, in the due check and across due checks")
    void previousIsNewestAnchoredProof(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        var older = commit(repository, StoreReferences.releaseTag(document(), 1), Instant.ofEpochSecond(200_000));
        var newer = commit(repository, StoreReferences.releaseTag(document(), 1), Instant.ofEpochSecond(300_000));

        try (var fixture = open(remote)) {
            fixture.anchor();
            var checkpoint = checkpoint(repository, 1);
            var late = commit(repository, StoreReferences.releaseTag(document(), 1), Instant.ofEpochSecond(250_000));
            fixture.anchor();

            var calls = fixture.provider().calls();
            assertAll(
                    () -> assertEquals(List.of(older, newer, checkpoint, late), fixture.provider().subjects(),
                            "oldest commit time first within a due check"),
                    () -> assertArrayEquals(new byte[0], calls.getFirst().previous(), "the first proof has none"),
                    () -> assertArrayEquals(ScriptedAnchorProvider.proofOf(older), calls.get(1).previous(),
                            "the proof obtained just before"),
                    () -> assertArrayEquals(ScriptedAnchorProvider.proofOf(newer), calls.get(2).previous(),
                            "the proof obtained just before"),
                    () -> assertArrayEquals(ScriptedAnchorProvider.proofOf(checkpoint), calls.get(3).previous(),
                            "across due checks: the newest anchored proof, not the newest commit time"),
                    () -> assertEquals(Instant.ofEpochSecond(250_000), calls.get(3).commitTime(),
                            "the commit time of the subject is passed along"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: a tip that moved while the proof was obtained ends the due check; the next one chains on")
    void movedTipEndsDueCheckAndNextChainsOnRemoteProof(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        var release = commit(repository, StoreReferences.releaseTag(document(), 1), Instant.ofEpochSecond(200_000));
        byte[] foreign = ("foreign " + word()).getBytes(StandardCharsets.US_ASCII);

        try (var fixture = open(remote)) {
            ObjectId[] foreignTip = new ObjectId[1];
            fixture.provider().beforeNextObtain(() -> {
                try {
                    foreignTip[0] = note(repository, fixture.notesRef(), main, foreign);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                value(fixture.writer().fetch());
            });

            var moved = fixture.anchor();
            var checkpoint = checkpoint(repository, 1);
            var keptAfterMove = cloneTip(fixture,
                    StoreReferences.retainedProofs(fixture.provider().id()) + release.name());
            int pushesAfterMove = notePushes(fixture).size();
            var chained = fixture.anchor();

            var calls = fixture.provider().calls();
            var pushes = notePushes(fixture);
            var firstNotes = parse(repository, ObjectId.fromString(pushes.getFirst().localRef()));
            assertAll(
                    () -> assertEquals(new StoreWriter.Anchoring(List.of(), List.of(release.name(), checkpoint.name())),
                            moved, "the moved tip ends the due check before any note is written"),
                    () -> assertNull(keptAfterMove, "a proof of a moved tip is not kept"),
                    () -> assertEquals(0, pushesAfterMove, "and its note is not pushed"),
                    () -> assertEquals(List.of(release.name(), checkpoint.name()), chained.anchored(),
                            "the next due check anchors both"),
                    () -> assertArrayEquals(foreign, calls.get(1).previous(),
                            "with the proof the remote tip added as previous"),
                    () -> assertEquals(release, calls.get(1).subject(), "for a new proof of the subject"),
                    () -> assertEquals(foreignTip[0].name(), pushes.getFirst().expectedRemoteHead(),
                            "the push leases the tip the proof extends"),
                    () -> assertEquals(List.of(foreignTip[0]), List.of(firstNotes.getParents()),
                            "the note is written onto that tip"),
                    () -> assertArrayEquals(foreign, AnchorNotes.decode(notes(repository, fixture.notesRef())
                            .get(main)), "the note of the other clone is kept"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: a kept proof whose tip moved is dropped and a new proof is obtained on the new tip")
    void keptProofOfMovedTipRerequested(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        byte[] foreign = ("foreign " + word()).getBytes(StandardCharsets.US_ASCII);

        try (var fixture = open(remote)) {
            fixture.operations().failPushTo(fixture.notesRef(), GitOutcome.UNREACHABLE);
            fixture.anchor();
            var subject = checkpoint(repository, 1);
            String keptRef = StoreReferences.retainedProofs(fixture.provider().id()) + subject.name();
            byte[] keptBlob = cloneBlob(fixture, Objects.requireNonNull(cloneTip(fixture, keptRef), "the kept proof"));
            var foreignTip = note(repository, fixture.notesRef(), main, foreign);

            var anchoring = fixture.anchor();

            var calls = fixture.provider().calls();
            var notesTip = Objects.requireNonNull(tip(repository, fixture.notesRef()), "the notes reference");
            String expectedKept = ObjectId.zeroId().name() + "\n"
                    + new String(ScriptedAnchorProvider.proofOf(subject), StandardCharsets.US_ASCII);
            assertAll(
                    () -> assertEquals(expectedKept, new String(keptBlob, StandardCharsets.US_ASCII),
                            "the kept blob names the tip the proof extends, none here, then the proof"),
                    () -> assertEquals(List.of(subject.name()), anchoring.anchored(), "the subject is anchored"),
                    () -> assertEquals(List.of(subject, subject), fixture.provider().subjects(),
                            "with a new proof, since the kept one extends a tip that moved"),
                    () -> assertArrayEquals(foreign, calls.get(1).previous(), "chained on the new tip"),
                    () -> assertEquals(List.of(foreignTip), List.of(parse(repository, notesTip).getParents()),
                            "the note is written onto the new tip"),
                    () -> assertNull(cloneTip(fixture, keptRef), "the kept proof is gone"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: a proof whose local-only reference cannot be written is neither noted nor pushed")
    void unkeptProofNotPublished(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        var release = commit(repository, StoreReferences.releaseTag(document(), 1), Instant.ofEpochSecond(200_000));

        try (var fixture = open(remote)) {
            // a reference below the name of the kept reference makes its creation fail with LOCK_FAILURE
            cloneRef(fixture, StoreReferences.retainedProofs(fixture.provider().id()) + release.name() + "/"
                    + word());

            var anchoring = fixture.anchor();

            var checkpoint = checkpoint(repository, 1);
            assertAll(
                    () -> assertEquals(
                            new StoreWriter.Anchoring(List.of(), List.of(release.name(), checkpoint.name())),
                            anchoring, "the subject stays pending and ends the due check"),
                    () -> assertEquals(List.of(), notePushes(fixture), "no note is pushed"),
                    () -> assertNull(cloneTip(fixture, fixture.notesRef()), "no note is written"),
                    () -> assertNull(tip(repository, fixture.notesRef()), "nothing reached the remote"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: a notes tip that adds no note over a proof history keeps the earlier proof as previous")
    void tipAddingNoNoteKeepsEarlierProofAsPrevious(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));

        try (var fixture = open(remote)) {
            fixture.anchor();
            var checkpoint = checkpoint(repository, 1);
            notesCommit(repository, fixture.notesRef(), Map.of());
            var release = commit(repository, StoreReferences.releaseTag(document(), 1), Instant.ofEpochSecond(200_000));

            var anchoring = fixture.anchor();

            var calls = fixture.provider().calls();
            assertAll(
                    () -> assertEquals(List.of(release.name()), anchoring.anchored(), "the release is anchored"),
                    () -> assertEquals(release, calls.get(1).subject(), "with a proof of its own"),
                    () -> assertArrayEquals(ScriptedAnchorProvider.proofOf(checkpoint), calls.get(1).previous(),
                            "chained on the earlier proof, not on none"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: a notes tip that adds several notes ends the due check incomplete without a proof")
    void tipAddingSeveralNotesEndsDueCheck(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));
        var other = commit(repository, "refs/heads/" + word(), Instant.ofEpochSecond(110_000));

        try (var fixture = open(remote)) {
            fixture.anchor();
            Map<ObjectId, byte[]> proofs = new LinkedHashMap<>();
            proofs.put(main, word().getBytes(StandardCharsets.US_ASCII));
            proofs.put(other, word().getBytes(StandardCharsets.US_ASCII));
            var foreignTip = notesCommit(repository, fixture.notesRef(), proofs);
            commit(repository, StoreReferences.releaseTag(document(), 1), Instant.ofEpochSecond(200_000));

            var anchoring = fixture.writer().anchor();

            assertAll(
                    () -> assertEquals(StoreOutcome.INCOMPLETE, anchoring.outcome(), "the due check fails closed"),
                    () -> assertEquals(1, fixture.provider().calls().size(), "no proof is requested on the tip"),
                    () -> assertEquals(foreignTip, tip(repository, fixture.notesRef()), "the notes tip is unchanged"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: a notes history without any note starts with previous none")
    void historyWithoutNoteStartsWithNone(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));

        try (var fixture = open(remote)) {
            var empty = notesCommit(repository, fixture.notesRef(), Map.of());

            var anchoring = fixture.anchor();

            var checkpoint = checkpoint(repository, 1);
            var notesTip = Objects.requireNonNull(tip(repository, fixture.notesRef()), "the notes reference");
            assertAll(
                    () -> assertEquals(List.of(checkpoint.name()), anchoring.anchored(), "the checkpoint is anchored"),
                    () -> assertArrayEquals(new byte[0], fixture.provider().calls().getFirst().previous(),
                            "with previous none"),
                    () -> assertEquals(List.of(empty), List.of(parse(repository, notesTip).getParents()),
                            "its note is written onto the empty notes history"));
        }
    }

    @Test
    @DisplayName("GDR-STORE-17: a tip and a kept proof are equal by their commit and the bytes of their proof")
    void tipAndKeptEqualByCommitAndProofBytes() {
        var commit = ObjectId.fromString("1".repeat(Constants.OBJECT_ID_STRING_LENGTH));
        var other = ObjectId.fromString("2".repeat(Constants.OBJECT_ID_STRING_LENGTH));
        byte[] proof = word().getBytes(StandardCharsets.US_ASCII);
        byte[] otherProof = word().getBytes(StandardCharsets.US_ASCII);
        var tip = new AnchorNotes.Tip(commit, proof);
        var kept = new AnchorNotes.Kept(commit, proof);

        assertAll(
                () -> assertEquals(tip, new AnchorNotes.Tip(commit, proof.clone()), "equal commit and proof bytes"),
                () -> assertEquals(tip.hashCode(), new AnchorNotes.Tip(commit, proof.clone()).hashCode(),
                        "with equal hash codes"),
                () -> assertNotEquals(tip, new AnchorNotes.Tip(other, proof), "another commit"),
                () -> assertNotEquals(tip, new AnchorNotes.Tip(commit, otherProof), "another proof"),
                () -> assertNotEquals(tip, new AnchorNotes.Tip(null, proof), "no commit"),
                () -> assertEquals(kept, new AnchorNotes.Kept(commit, proof.clone()), "equal basis and proof bytes"),
                () -> assertEquals(kept.hashCode(), new AnchorNotes.Kept(commit, proof.clone()).hashCode(),
                        "with equal hash codes"),
                () -> assertNotEquals(kept, new AnchorNotes.Kept(other, proof), "another basis"),
                () -> assertNotEquals(kept, new AnchorNotes.Kept(commit, otherProof), "another proof"),
                () -> assertNotEquals(tip, kept, "a tip is no kept proof"));
    }

    @Test
    @DisplayName("GDR-STORE-9: the references the fetch of a due check moved reach the listeners")
    void dueCheckFetchReachesListeners(@Remote LocalRemote remote) throws Exception {
        var repository = remote.repository();
        var main = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(100_000));

        try (var fixture = open(remote)) {
            List<String> received = new CopyOnWriteArrayList<>();
            try (var subscription = fixture.writer().subscribe(StoreReferences.MAIN,
                         (branch, commitId) -> received.add(commitId))) {
                var moved = commit(repository, StoreReferences.MAIN, Instant.ofEpochSecond(150_000), main);

                fixture.anchor();

                assertEquals(List.of(moved.name()), received, "the commit the due check fetched is delivered");
            }
        }
    }
}
