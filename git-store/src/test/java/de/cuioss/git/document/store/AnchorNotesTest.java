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
 * failed is kept locally, pushed by the next due check and not requested again; and {@code previous} is the newest
 * anchored proof.
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
        var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(providerId),
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
}
