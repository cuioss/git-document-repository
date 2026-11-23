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

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.git.document.core.CommitMetadata;
import de.cuioss.git.document.core.GitNotes;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jspecify.annotations.Nullable;

/**
 * The anchor proofs of one provider in one bare clone, kept as notes (GDR-STORE-17): the base64 of each proof is the
 * note of the anchored commit under {@code refs/notes/anchors/<provider>}, written through {@link GitNotes} of
 * {@code git-core} (used, never changed), in a notes commit by the technical user, signed through
 * {@link CommitSignatures} with {@code CommitSigning(signer, null, null)} and without identity trailers, and pushed
 * with the lease of the notes reference as the last fetch mirrored it, one note per push and separately from the
 * commits of any save.
 * <p>
 * Before its note is written a proof is kept under the local-only reference
 * {@code refs/anchoring/<provider>/proofs/<commit id>}, a blob outside every mirror destination, so no pruning fetch
 * removes it. The reference is deleted once the note is on the remote; a proof whose push failed stays kept, the local
 * notes reference is set back to the fetched id, and the next due check pushes the kept proof instead of requesting a
 * new one. Every method runs under the transport lock of the clone, which the caller holds ({@link RepositoryGuard}).
 * <p>
 * The notes reference, the kept references and the notes commits name commit ids only; no log record names more than
 * the alias and an outcome (GDR-NFR-3).
 */
final class AnchorNotes {

    /** The message of a notes commit before the anchored commit id. */
    static final String MESSAGE = "Anchor ";

    /** The remote of every bare clone of the store. */
    private static final String ORIGIN = "origin";

    private static final CuiLogger LOGGER = new CuiLogger(AnchorNotes.class);

    private final ExplicitSave.Clone clone;
    private final String notesRef;
    private final String keptPrefix;
    private final RepositoryOperations operations;
    private final TechnicalUser user;
    private final ExplicitSave.Committing committing;
    private final int attempts;
    private final GitNotes notes = new GitNotes(new ReferenceUpdater(new InCoreWriter()));

    /**
     * Creates the notes access of a provider.
     *
     * @param clone      the bare clone
     * @param provider   the id of the provider, of the form {@code [a-z0-9-]+}
     * @param operations the repository operations of {@code git-core}, for the push of the notes reference
     * @param user       the technical user, author, committer and holder of the push credentials
     * @param committing the identities, the signing seam and the clock of the notes commits
     * @param attempts   the attempts of the compare-and-swap of the local notes reference, at least 1
     * @throws NullPointerException if an argument is {@code null}
     */
    AnchorNotes(ExplicitSave.Clone clone, String provider, RepositoryOperations operations, TechnicalUser user,
            ExplicitSave.Committing committing, int attempts) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.notesRef = StoreReferences.anchorNotes(provider);
        this.keptPrefix = StoreReferences.retainedProofs(provider);
        this.operations = Objects.requireNonNull(operations, "operations");
        this.user = Objects.requireNonNull(user, "user");
        this.committing = Objects.requireNonNull(committing, "committing");
        this.attempts = attempts;
    }

    /**
     * Returns the notes reference of the provider.
     *
     * @return {@code refs/notes/anchors/<provider>}
     */
    String notesRef() {
        return notesRef;
    }

    /**
     * Returns the commits that have a note, as the last fetch mirrored the notes reference.
     *
     * @return the annotated commits; empty without a notes reference
     * @throws IOException if the notes cannot be read
     */
    Set<ObjectId> annotated() throws IOException {
        Set<ObjectId> annotated = new HashSet<>();
        Repository repository = clone.repository();
        @Nullable ObjectId tip = tip();
        if (tip == null) {
            return annotated;
        }
        try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
            for (Note note : NoteMap.read(reader, walk.parseCommit(tip))) {
                annotated.add(note.copy());
            }
        }
        return annotated;
    }

    /**
     * Returns the subject whose note the newest notes commit added: the note of the tip that its first parent lacks or
     * holds with other content. One note is written per notes commit, so that subject carries the newest proof.
     *
     * @return the subject, or empty without a notes reference or when the tip added no single note
     * @throws IOException if the notes cannot be read
     */
    Optional<ObjectId> newest() throws IOException {
        @Nullable ObjectId tip = tip();
        if (tip == null) {
            return Optional.empty();
        }
        Repository repository = clone.repository();
        try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
            RevCommit commit = walk.parseCommit(tip);
            NoteMap current = NoteMap.read(reader, commit);
            NoteMap parent = commit.getParentCount() == 0 ? NoteMap.newEmptyMap()
                    : NoteMap.read(reader, walk.parseCommit(commit.getParent(0)));
            @Nullable ObjectId added = null;
            for (Note note : current) {
                if (!note.getData().equals(parent.get(note))) {
                    if (added != null) {
                        return Optional.empty();
                    }
                    added = note.copy();
                }
            }
            return Optional.ofNullable(added);
        }
    }

    /**
     * Reads and decodes the proof of a subject from its note.
     *
     * @param subject the anchored commit
     * @return the proof, or empty when the subject has no note or the note is no base64
     */
    Optional<byte[]> proof(ObjectId subject) {
        GitResult<byte[]> read = notes.read(clone.repository(), notesRef, subject.name());
        byte[] note = read.value();
        if (!read.isOk() || note == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(decode(note));
    }

    /**
     * Decodes the base64 content of a note.
     *
     * @param note the content of a note
     * @return the proof, or {@code null} when the note is no base64
     */
    static byte @Nullable [] decode(byte[] note) {
        try {
            return Base64.getDecoder().decode(new String(note, US_ASCII).strip());
        } catch (IllegalArgumentException e) {
            LOGGER.debug("An anchor note is no base64");
            return null;
        }
    }

    /**
     * Returns the proofs kept by an earlier due check whose note is not on the remote yet.
     *
     * @return the kept proofs by subject
     * @throws IOException if the references or blobs cannot be read
     */
    Map<ObjectId, byte[]> kept() throws IOException {
        Map<ObjectId, byte[]> kept = new LinkedHashMap<>();
        Repository repository = clone.repository();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(keptPrefix)) {
            String name = ref.getName().substring(keptPrefix.length());
            @Nullable ObjectId blob = ref.getObjectId();
            if (blob == null || !ObjectId.isId(name)) {
                continue;
            }
            kept.put(ObjectId.fromString(name), repository.open(blob, Constants.OBJ_BLOB).getBytes(Integer.MAX_VALUE));
        }
        return kept;
    }

    /**
     * Keeps a proof under its local-only reference, before its note is written.
     *
     * @param subject the anchored commit
     * @param proof   the proof
     * @throws IOException if the blob or the reference cannot be written
     */
    void keep(ObjectId subject, byte[] proof) throws IOException {
        Repository repository = clone.repository();
        ObjectId blob;
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            blob = inserter.insert(Constants.OBJ_BLOB, proof);
            inserter.flush();
        }
        RefUpdate update = repository.updateRef(keptPrefix + subject.name());
        update.setForceUpdate(true);
        update.setNewObjectId(blob);
        update.disableRefLog();
        RefUpdate.Result result = update.update();
        LOGGER.debug("The kept proof reference was written: %s", result);
    }

    /**
     * Drops the kept proof of a subject.
     *
     * @param subject the anchored commit
     * @throws IOException if the reference cannot be deleted
     */
    void drop(ObjectId subject) throws IOException {
        String name = keptPrefix + subject.name();
        if (clone.repository().exactRef(name) == null) {
            return;
        }
        RefUpdate update = clone.repository().updateRef(name);
        update.setForceUpdate(true);
        update.disableRefLog();
        RefUpdate.Result result = update.delete();
        LOGGER.debug("The kept proof reference was deleted: %s", result);
    }

    /**
     * Writes the note of a subject and pushes the notes reference with the lease of its fetched id; on success the
     * kept proof is dropped.
     *
     * @param subject the anchored commit
     * @param proof   the proof, already kept
     * @return {@link StoreOutcome#OK} with the notes commit; {@link StoreOutcome#REFUSED} or
     *         {@link StoreOutcome#UNAVAILABLE} of the signing key source; {@link StoreOutcome#INCOMPLETE} when the push
     *         failed, the proof staying kept; or the passed-on Git outcome of the write
     * @throws IOException if a reference cannot be read or written
     */
    StoreResult<ObjectId> publish(ObjectId subject, byte[] proof) throws IOException {
        @Nullable ObjectId fetched = tip();
        ObjectId lease = fetched == null ? ObjectId.zeroId() : fetched;
        var prepared = committing.signatures().prepare(notesRef);
        if (!prepared.isOk()) {
            return new StoreResult<>(prepared.outcome(), null, prepared.detail());
        }
        CommitSignatures.Prepared signing = Objects.requireNonNull(prepared.value());
        Instant now = committing.clock().instant();
        var identity = committing.identities().committer(now);
        var written = notes.write(clone.repository(), notesRef, subject.name(),
                Base64.getEncoder().encode(proof),
                new CommitMetadata(identity, identity, MESSAGE + subject.name(), signing.signing()), attempts);
        if (!written.isOk()) {
            return committing.signatures().<ObjectId>outcomeOf(written, signing, notesRef)
                    .orElseGet(() -> StoreResult.passOn(written));
        }
        ObjectId commit = ObjectId.fromString(Objects.requireNonNull(written.value()));
        var pushed = operations.push(PushInput.builder().root(clone.gitDir()).remote(ORIGIN).localRef(commit.name())
                .remoteRef(notesRef).expectedRemoteHead(lease.name()).credentials(user.credentials()).build());
        if (!pushed.isOk()) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHOR_PUSH_INCOMPLETE, clone.alias(), pushed.outcome().name());
            reset(commit, fetched);
            return StoreResult.of(StoreOutcome.INCOMPLETE,
                    notesRef + ": not pushed (" + pushed.outcome().name() + ")");
        }
        drop(subject);
        return StoreResult.ok(commit);
    }

    /**
     * Sets the local notes reference back to its fetched id after a failed push, so the next note is written onto the
     * state of the remote and its lease matches; a reference that did not exist is deleted.
     */
    private void reset(ObjectId written, @Nullable ObjectId fetched) throws IOException {
        RefUpdate update = clone.repository().updateRef(notesRef);
        update.setExpectedOldObjectId(written);
        update.disableRefLog();
        update.setForceUpdate(true);
        RefUpdate.Result result;
        if (fetched == null) {
            result = update.delete();
        } else {
            update.setNewObjectId(fetched);
            result = update.update();
        }
        if (result == RefUpdate.Result.LOCK_FAILURE || result == RefUpdate.Result.IO_FAILURE) {
            LOGGER.debug("The local notes reference was not set back: %s", result);
        }
    }

    private @Nullable ObjectId tip() throws IOException {
        Ref ref = clone.repository().exactRef(notesRef);
        return ref == null ? null : ref.getObjectId();
    }
}
