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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.git.document.core.CommitMetadata;
import de.cuioss.git.document.core.GitNotes;
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
 * Every proof is bound to the notes tip it extends, the notes commit whose newest note was the {@code previous} of the
 * proof ({@link Kept}): its note is written only onto that tip, the new notes commit has that tip as its only parent,
 * and the push leases that tip; a proof whose tip moved meanwhile is not published, since it would fork the chain of
 * the proofs, and a new proof is needed.
 * <p>
 * Before its note is written a proof is kept under the local-only reference
 * {@code refs/anchoring/<provider>/proofs/<commit id>}, a blob outside every mirror destination, so no pruning fetch
 * removes it; the blob holds the id of the tip the proof extends (the zero id for none), a line feed, and the proof.
 * The reference is deleted once the note is on the remote; a proof whose push failed stays kept, the local notes
 * reference is set back to the fetched id, and the next due check pushes the kept proof instead of requesting a new
 * one, as long as the tip is still the one the proof extends. Every method runs under the transport lock of the clone,
 * which the caller holds ({@link RepositoryGuard}).
 * <p>
 * The notes reference, the kept references and the notes commits name commit ids only; no log record names more than
 * the alias and an outcome (GDR-NFR-3).
 */
final class AnchorNotes {

    /** The message of a notes commit before the anchored commit id. */
    static final String MESSAGE = "Anchor ";

    /** The remote of every bare clone of the store. */
    private static final String ORIGIN = "origin";

    /** The detail of a proof whose notes tip moved: it would fork the chain of the proofs. */
    static final String TIP_MOVED = "the notes tip moved";

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
     * The notes tip a due check builds on, with the newest proof of its history ({@link #current()}): the
     * {@code previous} of the next proof.
     *
     * @param commit the notes commit the notes reference points at; {@code null} without a notes reference
     * @param proof  the newest proof of the notes history; empty only when the history holds no note at all
     */
    record Tip(@Nullable ObjectId commit, byte[] proof) {

        /**
         * Copies the proof.
         *
         * @param commit the notes commit
         * @param proof  the proof
         * @throws NullPointerException if {@code proof} is {@code null}
         */
        Tip {
            proof = Objects.requireNonNull(proof, "proof").clone();
        }

        /** @return a copy of the proof */
        @Override
        public byte[] proof() {
            return proof.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Tip(var otherCommit, var otherProof) && Objects.equals(commit, otherCommit)
                    && Arrays.equals(proof, otherProof);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(commit) + Arrays.hashCode(proof);
        }

        @Override
        public String toString() {
            return "Tip[" + (commit == null ? "none" : commit.name()) + ", " + proof.length + " bytes]";
        }
    }

    /**
     * A proof bound to the notes tip it extends.
     *
     * @param basis the notes commit whose newest note was the {@code previous} of the proof; {@code null} when there
     *              was no notes reference
     * @param proof the proof
     */
    record Kept(@Nullable ObjectId basis, byte[] proof) {

        /**
         * Copies the proof.
         *
         * @param basis the notes commit the proof extends
         * @param proof the proof
         * @throws NullPointerException if {@code proof} is {@code null}
         */
        Kept {
            proof = Objects.requireNonNull(proof, "proof").clone();
        }

        /** @return a copy of the proof */
        @Override
        public byte[] proof() {
            return proof.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Kept(var otherBasis, var otherProof) && Objects.equals(basis, otherBasis)
                    && Arrays.equals(proof, otherProof);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hashCode(basis) + Arrays.hashCode(proof);
        }

        @Override
        public String toString() {
            return "Kept[" + (basis == null ? "none" : basis.name()) + ", " + proof.length + " bytes]";
        }
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
     * Returns the notes commit the notes reference points at, as the last fetch mirrored it or the last publication
     * moved it.
     *
     * @return the notes commit; {@code null} without a notes reference
     * @throws IOException if the reference cannot be read
     */
    @Nullable
    ObjectId tip() throws IOException {
        Ref ref = clone.repository().exactRef(notesRef);
        return ref == null ? null : ref.getObjectId();
    }

    /**
     * Returns the notes tip with the newest proof of its history, the {@code previous} of the next proof: the note the
     * newest notes commit along the first-parent history added, that is the note its first parent lacks or holds with
     * other content. A notes commit that adds no note, for example one that only removes a note, is passed over.
     * {@link #publish} adds exactly one note per notes commit, so only a notes commit written elsewhere adds none or
     * several.
     *
     * @return the tip with the newest proof; with an empty proof without a notes reference or when its history adds no
     *         note at all; empty when the newest notes commit that adds a note adds several, or adds one that is no
     *         base64, so no predecessor can be identified and no proof may be built on the tip
     * @throws IOException if the notes cannot be read
     */
    Optional<Tip> current() throws IOException {
        @Nullable ObjectId commit = tip();
        if (commit == null) {
            return Optional.of(new Tip(null, new byte[0]));
        }
        Repository repository = clone.repository();
        try (ObjectReader reader = repository.newObjectReader(); RevWalk walk = new RevWalk(reader)) {
            @Nullable RevCommit step = walk.parseCommit(commit);
            while (step != null) {
                @Nullable RevCommit parent = step.getParentCount() == 0 ? null : walk.parseCommit(step.getParent(0));
                NoteMap before = parent == null ? NoteMap.newEmptyMap() : NoteMap.read(reader, parent);
                List<Note> added = new ArrayList<>();
                for (Note note : NoteMap.read(reader, step)) {
                    if (!note.getData().equals(before.get(note))) {
                        added.add(note);
                    }
                }
                if (added.size() > 1) {
                    LOGGER.debug("A notes commit adds several notes; no proof predecessor is identified");
                    return Optional.empty();
                }
                if (added.size() == 1) {
                    return Optional.ofNullable(decode(reader.open(added.getFirst().getData(), Constants.OBJ_BLOB)
                            .getBytes(Integer.MAX_VALUE))).map(proof -> new Tip(commit, proof));
                }
                step = parent;
            }
        }
        return Optional.of(new Tip(commit, new byte[0]));
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
     * Returns the proofs kept by an earlier due check whose note is not on the remote yet; a kept reference whose blob
     * is not of the kept form is dropped.
     *
     * @return the kept proofs by subject
     * @throws IOException if the references or blobs cannot be read
     */
    Map<ObjectId, Kept> kept() throws IOException {
        Map<ObjectId, Kept> kept = new LinkedHashMap<>();
        Repository repository = clone.repository();
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(keptPrefix)) {
            String name = ref.getName().substring(keptPrefix.length());
            @Nullable ObjectId blob = ref.getObjectId();
            if (blob != null && ObjectId.isId(name)) {
                ObjectId subject = ObjectId.fromString(name);
                @Nullable Kept proof = decodeKept(repository.open(blob, Constants.OBJ_BLOB)
                        .getBytes(Integer.MAX_VALUE));
                if (proof == null) {
                    LOGGER.debug("A kept proof is not of the kept form and is dropped");
                    drop(subject);
                } else {
                    kept.put(subject, proof);
                }
            }
        }
        return kept;
    }

    /**
     * Keeps a proof under its local-only reference, before its note is written; a reference that already points at the
     * same blob counts as kept.
     *
     * @param subject the anchored commit
     * @param proof   the proof with the tip it extends
     * @return whether the reference points at the blob of the proof now
     * @throws IOException if the blob or the reference cannot be written
     */
    private boolean keep(ObjectId subject, Kept proof) throws IOException {
        Repository repository = clone.repository();
        ObjectId blob;
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            blob = inserter.insert(Constants.OBJ_BLOB, encode(proof));
            inserter.flush();
        }
        String name = keptPrefix + subject.name();
        RefUpdate update = repository.updateRef(name);
        update.setForceUpdate(true);
        update.setNewObjectId(blob);
        update.disableRefLog();
        RefUpdate.Result result = update.update();
        Ref written = repository.exactRef(name);
        boolean kept = switch (result) {
            case NEW, FORCED, FAST_FORWARD, NO_CHANGE -> written != null && blob.equals(written.getObjectId());
            default -> false;
        };
        if (!kept) {
            LOGGER.debug("The kept proof reference was not written: %s", result);
        }
        return kept;
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
     * Keeps a proof, writes the note of its subject onto the tip the proof extends and pushes the notes reference with
     * that tip as the lease; on success the kept proof is dropped. Nothing is kept, written or pushed when the notes
     * tip is no longer the one the proof extends.
     *
     * @param subject the anchored commit
     * @param proof   the proof with the tip it extends
     * @return {@link StoreOutcome#OK} with the notes commit; {@link StoreOutcome#INCOMPLETE} when the tip moved, so a
     *         new proof is needed, when the proof cannot be kept, or when the push failed, the proof staying kept;
     *         {@link StoreOutcome#REFUSED} or {@link StoreOutcome#UNAVAILABLE} of the signing key source; or the
     *         passed-on Git outcome of the write
     * @throws IOException if a reference cannot be read or written
     */
    StoreResult<ObjectId> publish(ObjectId subject, Kept proof) throws IOException {
        @Nullable ObjectId basis = proof.basis();
        if (!Objects.equals(tip(), basis)) {
            return StoreResult.of(StoreOutcome.INCOMPLETE, notesRef + ": " + TIP_MOVED);
        }
        if (!keep(subject, proof)) {
            return StoreResult.of(StoreOutcome.INCOMPLETE, notesRef + ": the proof was not kept");
        }
        ObjectId lease = basis == null ? ObjectId.zeroId() : basis;
        var prepared = committing.signatures().prepare(notesRef);
        if (!prepared.isOk()) {
            return new StoreResult<>(prepared.outcome(), null, prepared.detail());
        }
        CommitSignatures.Prepared signing = Objects.requireNonNull(prepared.value());
        Instant now = committing.clock().instant();
        var identity = committing.identities().committer(now);
        var written = notes.write(clone.repository(), notesRef, subject.name(),
                Base64.getEncoder().encode(proof.proof()),
                new CommitMetadata(identity, identity, MESSAGE + subject.name(), signing.signing()), attempts);
        if (!written.isOk()) {
            return committing.signatures().<ObjectId>outcomeOf(written, signing, notesRef)
                    .orElseGet(() -> StoreResult.passOn(written));
        }
        ObjectId commit = ObjectId.fromString(Objects.requireNonNull(written.value()));
        if (!follows(commit, basis)) {
            // the compare-and-swap wrote onto another tip; the note must not fork the chain of the proofs
            reset(commit, parent(commit));
            return StoreResult.of(StoreOutcome.INCOMPLETE, notesRef + ": " + TIP_MOVED);
        }
        var pushed = operations.push(PushInput.builder().root(clone.gitDir()).remote(ORIGIN).localRef(commit.name())
                .remoteRef(notesRef).expectedRemoteHead(lease.name()).credentials(user.credentials()).build());
        if (!pushed.isOk()) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHOR_PUSH_INCOMPLETE, clone.alias(), pushed.outcome().name());
            reset(commit, basis);
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

    /** Whether a notes commit has the given tip as its only parent, or no parent when there was no tip. */
    private boolean follows(ObjectId commit, @Nullable ObjectId basis) throws IOException {
        try (RevWalk walk = new RevWalk(clone.repository())) {
            RevCommit parsed = walk.parseCommit(commit);
            if (basis == null) {
                return parsed.getParentCount() == 0;
            }
            return parsed.getParentCount() == 1 && parsed.getParent(0).equals(basis);
        }
    }

    /** The first parent of a notes commit; {@code null} for none. */
    private @Nullable ObjectId parent(ObjectId commit) throws IOException {
        try (RevWalk walk = new RevWalk(clone.repository())) {
            RevCommit parsed = walk.parseCommit(commit);
            return parsed.getParentCount() == 0 ? null : parsed.getParent(0).copy();
        }
    }

    /** The content of the blob of a kept proof: the id of its basis, a line feed, the proof. */
    private static byte[] encode(Kept kept) {
        @Nullable ObjectId basis = kept.basis();
        byte[] id = (basis == null ? ObjectId.zeroId() : basis).name().getBytes(US_ASCII);
        byte[] proof = kept.proof();
        byte[] content = new byte[id.length + 1 + proof.length];
        System.arraycopy(id, 0, content, 0, id.length);
        content[id.length] = '\n';
        System.arraycopy(proof, 0, content, id.length + 1, proof.length);
        return content;
    }

    /** Decodes the blob of a kept proof; {@code null} when it does not start with an object id and a line feed. */
    private static @Nullable Kept decodeKept(byte[] content) {
        int length = Constants.OBJECT_ID_STRING_LENGTH;
        if (content.length <= length || content[length] != '\n') {
            return null;
        }
        String id = new String(content, 0, length, US_ASCII);
        if (!ObjectId.isId(id)) {
            return null;
        }
        ObjectId basis = ObjectId.fromString(id);
        return new Kept(ObjectId.zeroId().equals(basis) ? null : basis,
                Arrays.copyOfRange(content, length + 1, content.length));
    }
}
