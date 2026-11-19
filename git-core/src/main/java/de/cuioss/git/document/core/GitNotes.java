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

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;


import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jspecify.annotations.Nullable;

/**
 * Reads and writes git notes on commits (GDR-CORE-10; checked against JGit 7.8.0).
 * <p>
 * A note is attached to a commit under a notes reference the caller names, below {@code refs/notes/}; the library
 * reserves no notes reference of its own. The notes reference points at a commit whose tree is the note map: JGit
 * 7.8.0 {@link NoteMap} reads it ({@code NoteMap.read(ObjectReader, RevCommit)} and
 * {@code NoteMap.readTree(ObjectReader, ObjectId)}), looks a note up ({@code NoteMap.get(AnyObjectId)}, the id of the
 * note's blob), sets one ({@code NoteMap.set(AnyObjectId, ObjectId)}) and writes the map back as a tree
 * ({@code NoteMap.writeTree(ObjectInserter)}). The map decides its own fan-out (JGit 7.8.0 {@code LeafBucket.set}
 * splits a bucket that already holds 256 notes into a fan-out subtree before it adds another), so the note tree is
 * never expressed as {@link TreeEdit}s and a note exists at exactly one path.
 * <p>
 * A write is a compare-and-swap of the notes reference (GDR-CORE-5) through the tree-level variant of
 * {@link ReferenceUpdater}: each attempt reads the notes tree the reference currently points at, sets the note on it
 * and commits the resulting tree, with the reference's commit as the only parent. A lost race re-reads the notes tree
 * the concurrent writer left and sets the note on that tree again, so the other writer's notes are kept. The update
 * replaces a symbolic notes reference instead of moving the reference it points at.
 * <p>
 * A commit is addressed by its full id and must be a commit itself: it is parsed as it is ({@code RevWalk.parseAny}),
 * so neither a tree, a blob nor an annotated tag, not even one that points at a commit, receives a note. A detail names
 * the field and the reason, never a note's content (GDR-NFR-3); a failure detail names the exception class only.
 *
 * @since 0.1
 */
public final class GitNotes {

    /** The input field of the notes reference. */
    private static final String NOTES_REF = "notesRef";

    /** The input field of the commit. */
    private static final String COMMIT = "commit";

    private final ReferenceUpdater updater;

    /**
     * Creates the notes access.
     *
     * @param updater the updater that moves the notes reference by compare-and-swap; stateless, so one may serve
     *                several instances
     * @throws NullPointerException if {@code updater} is {@code null}
     */
    public GitNotes(ReferenceUpdater updater) {
        this.updater = Objects.requireNonNull(updater, "updater");
    }

    /**
     * Reads the note of a commit.
     * <p>
     * The notes reference is read exactly ({@code Repository.exactRef(String)}), a symbolic one resolved to the commit
     * it points at, and the note map of that commit is searched for the commit.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the value is the note's content, a copy owned by the caller;</li>
     * <li>{@link GitOutcome#REFUSED_BY_POLICY}: {@code notesRef} is not below {@code refs/notes/} or no valid
     * reference name; the detail names the field only;</li>
     * <li>{@link GitOutcome#NOT_FOUND}: {@code commitId} is no full commit id or no commit of the repository
     * (detail {@code commit: not a full commit id} or {@code commit: not found}), the notes reference does not exist
     * ({@code notesRef: not found}), or the commit has no note under it ({@code note: not found});</li>
     * <li>{@link GitOutcome#FAILED}: an I/O failure, a notes reference that points at no commit included; the detail
     * names the exception class.</li>
     * </ul>
     *
     * @param repository the repository
     * @param notesRef   the full name of the notes reference, e.g. {@code refs/notes/commits}
     * @param commitId   the full id of the commit whose note is read
     * @return the note's content, or the outcome that prevented reading it
     * @throws NullPointerException if an argument is {@code null}
     */
    public GitResult<byte[]> read(Repository repository, String notesRef, String commitId) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(notesRef, NOTES_REF);
        Objects.requireNonNull(commitId, COMMIT);
        if (!isNotesRef(notesRef)) {
            return GitResult.of(GitOutcome.REFUSED_BY_POLICY, NOTES_REF + ": not a valid notes reference");
        }
        if (!GitIdentity.isFullCommitId(commitId)) {
            return GitResult.of(GitOutcome.NOT_FOUND, COMMIT + ": not a full commit id");
        }
        try (ObjectReader reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(reader)) {
            Optional<RevCommit> commit = JGitRepositoryOperations.parseCommit(walk, commitId);
            if (commit.isEmpty()) {
                return GitResult.of(GitOutcome.NOT_FOUND, COMMIT + ": not found");
            }
            Ref ref = repository.exactRef(notesRef);
            @Nullable ObjectId notesCommit = ref == null ? null : ref.getObjectId();
            if (notesCommit == null) {
                return GitResult.of(GitOutcome.NOT_FOUND, NOTES_REF + ": not found");
            }
            NoteMap notes = NoteMap.read(reader, walk.parseCommit(notesCommit));
            ObjectId note = notes.get(commit.get());
            if (note == null) {
                return GitResult.of(GitOutcome.NOT_FOUND, "note: not found");
            }
            return GitResult.ok(reader.open(note, Constants.OBJ_BLOB).getBytes());
        } catch (IOException | LargeObjectException e) {
            return JGitRepositoryOperations.failure(e);
        }
    }

    /**
     * Writes the note of a commit, replacing a note the commit already has under the notes reference, and moves the
     * notes reference to the new notes commit by compare-and-swap.
     * <p>
     * The note is copied on entry, so a later change of the caller's array changes nothing written. Every attempt
     * reads the notes tree of the reference's current commit ({@code NoteMap.readTree}; an empty map,
     * {@code NoteMap.newEmptyMap()}, for a reference that does not exist yet), inserts the note as a blob, sets it for
     * the commit and writes the map as the tree of the next notes commit.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the note was written; the value is the full id of the new notes commit, which the
     * notes reference now points at;</li>
     * <li>{@link GitOutcome#REFUSED_BY_POLICY}: {@code notesRef} is not below {@code refs/notes/} or no valid
     * reference name; the detail names the field only. Nothing is read or written;</li>
     * <li>{@link GitOutcome#NOT_FOUND}: {@code commitId} is no full commit id or no commit of the repository; the
     * detail names the field. Nothing is written;</li>
     * <li>{@link GitOutcome#CONFLICT}: every attempt lost its race against another writer of the notes reference;
     * the reference is left as the other writers left it;</li>
     * <li>{@link GitOutcome#FAILED}: an I/O or a signing failure; the detail names the exception class;</li>
     * <li>{@link GitOutcome#REJECTED}: the reference update ended in any other result; the detail names it.</li>
     * </ul>
     *
     * @param repository the repository
     * @param notesRef   the full name of the notes reference, e.g. {@code refs/notes/commits}
     * @param commitId   the full id of the commit the note is attached to
     * @param note       the content of the note
     * @param metadata   author, committer, message and signing of the notes commit
     * @param attempts   the total number of tries of the compare-and-swap, at least 1
     * @return the full id of the new notes commit, or the outcome that prevented it
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    public GitResult<String> write(Repository repository, String notesRef, String commitId, byte[] note,
            CommitMetadata metadata, int attempts) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(notesRef, NOTES_REF);
        Objects.requireNonNull(commitId, COMMIT);
        byte[] content = Objects.requireNonNull(note, "note").clone();
        Objects.requireNonNull(metadata, "metadata");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
        if (!isNotesRef(notesRef)) {
            return GitResult.of(GitOutcome.REFUSED_BY_POLICY, NOTES_REF + ": not a valid notes reference");
        }
        if (!GitIdentity.isFullCommitId(commitId)) {
            return GitResult.of(GitOutcome.NOT_FOUND, COMMIT + ": not a full commit id");
        }
        RevCommit commit;
        try (RevWalk walk = new RevWalk(repository)) {
            Optional<RevCommit> parsed = JGitRepositoryOperations.parseCommit(walk, commitId);
            if (parsed.isEmpty()) {
                return GitResult.of(GitOutcome.NOT_FOUND, COMMIT + ": not found");
            }
            commit = parsed.get();
        } catch (IOException e) {
            return JGitRepositoryOperations.failure(e);
        }
        return updater.compareAndSwapTree(repository, notesRef, metadata, (reader, inserter, notesTree) -> {
            NoteMap notes = notesTree == null ? NoteMap.newEmptyMap() : NoteMap.readTree(reader, notesTree);
            notes.set(commit, inserter.insert(Constants.OBJ_BLOB, content));
            return notes.writeTree(inserter);
        }, attempts);
    }

    /** Whether the name is a valid reference name below {@code refs/notes/}. */
    private static boolean isNotesRef(String notesRef) {
        return notesRef.startsWith(Constants.R_NOTES) && notesRef.length() > Constants.R_NOTES.length()
                && Repository.isValidRefName(notesRef);
    }
}
