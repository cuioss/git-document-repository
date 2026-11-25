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

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.git.document.core.CommitMetadata;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.core.TreeOperation;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * The draft references of one bare clone (GDR-STORE-2): an intermediate save writes the next commit of
 * {@code refs/drafts/<pseudonym>/<collection>/<id>}, and the drafts of a user are listed from
 * {@code refs/drafts/<pseudonym>/}.
 * <p>
 * The first commit of a draft that does not exist yet has the tip of the local target branch of its document
 * ({@link StoreReferences#target(DocumentKey)}) as its parent, else the tip of {@code refs/heads/main}, else none, so
 * it holds the state the user started from. It is written through {@link InCoreWriter} first, and only then is the
 * draft reference published by a {@link RefUpdate} that expects it to be absent (the zero id) and writes no reflog, so
 * a refused first write, for example two edits of one path, leaves no draft behind. That update's
 * {@link RefUpdate.Result#LOCK_FAILURE} means that another writer published the draft first or that its lock could not
 * be acquired (JGit 7.8.0 reports both alike): the next attempt reads the draft again and writes on top of the
 * published one, or publishes again.
 * <p>
 * Every later draft commit is written by {@link ReferenceUpdater#compareAndSwap}, unsigned and without trailers, with
 * the author and committer of {@link CommitIdentities}; its edits are applied onto the draft's current tree. A draft
 * that existed when it was read but is absent when the compare-and-swap reads it, deleted meanwhile by a save or
 * another process, is never replaced by a root commit holding only the new edits: no commit is written and the next
 * attempt writes the first commit of the draft anew on its target branch or {@code main}. After
 * {@code save.max-attempts} attempts that all lost their draft or its publication, the write ends in
 * {@link GitOutcome#CONFLICT}, which may be retried.
 * <p>
 * A detail names a reference, never a content, a pseudonym beyond the reference or an address (GDR-NFR-3).
 */
final class DraftReferences {

    private static final CuiLogger LOGGER = new CuiLogger(DraftReferences.class);

    private final Repository repository;
    private final ReferenceUpdater updater;
    private final InCoreWriter writer = new InCoreWriter();
    private final CommitIdentities identities;
    private final Clock clock;
    private final int attempts;

    /**
     * Creates the draft references of a clone.
     *
     * @param repository the bare clone
     * @param updater    the compare-and-swap updater
     * @param identities the commit identities
     * @param clock      the clock of the commit times
     * @param attempts   the attempts of a compare-and-swap, at least 1
     * @throws NullPointerException if an argument is {@code null}
     */
    DraftReferences(Repository repository, ReferenceUpdater updater, CommitIdentities identities, Clock clock,
            int attempts) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.updater = Objects.requireNonNull(updater, "updater");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.attempts = attempts;
    }

    /**
     * Writes an intermediate save: the next commit of the draft reference of the author for the document.
     *
     * @param author  the author, whose pseudonym names the draft reference
     * @param key     the document or entry
     * @param edits   the edits, applied onto the draft's current tree
     * @param message the commit message
     * @return {@link StoreOutcome#OK} with the full id of the new draft commit, {@link StoreOutcome#MALFORMED} for an
     *         invalid pseudonym, or the passed-on Git outcome that prevented the write (for example
     *         {@link GitOutcome#NOT_FOUND} for an invalid path, {@link GitOutcome#CONFLICT} after every attempt lost
     *         its race or its draft, {@link GitOutcome#FAILED} for an I/O failure)
     * @throws NullPointerException if an argument is {@code null}
     */
    StoreResult<String> write(Author author, DocumentKey key, List<TreeEdit> edits, String message) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(key, "key");
        List<TreeEdit> copy = List.copyOf(Objects.requireNonNull(edits, "edits"));
        Objects.requireNonNull(message, "message");
        var draftRef = StoreReferences.draft(author.pseudonym(), key);
        if (!draftRef.isOk()) {
            return draftRef;
        }
        String refName = Objects.requireNonNull(draftRef.value());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Optional<StoreResult<String>> written = attempt(author, key, refName, copy, message);
            if (written.isPresent()) {
                return written.get();
            }
            LOGGER.debug("The draft %s vanished or its publication was lost, attempt %s of %s", refName, attempt,
                    attempts);
        }
        return StoreResult.passOn(GitResult.of(GitOutcome.CONFLICT,
                refName + ": draft lost or not published on any attempt (" + attempts + ")"));
    }

    /**
     * One attempt of a draft write: the next commit of an existing draft by compare-and-swap, or the first commit of a
     * missing one.
     *
     * @return the result of the write, or empty when the draft vanished before its compare-and-swap, or its creation
     *         met a held lock or a draft another writer published first, so the next attempt reads the draft again
     */
    private Optional<StoreResult<String>> attempt(Author author, DocumentKey key, String refName, List<TreeEdit> edits,
            String message) {
        try {
            if (repository.exactRef(refName) == null) {
                return create(author, key, refName, edits, message);
            }
        } catch (IOException e) {
            return Optional.of(StoreResult.passOn(failure(refName, e)));
        }
        TreeOperation operation = current -> {
            if (current == null) {
                // deleted since it was read, e.g. by a save: a root commit would drop the draft's base
                throw new DraftVanished();
            }
            return edits;
        };
        try {
            GitResult<String> written = updater.compareAndSwap(repository, refName, metadata(author, message),
                    operation, attempts);
            return Optional.of(written.isOk() ? StoreResult.ok(Objects.requireNonNull(written.value()))
                    : StoreResult.passOn(written));
        } catch (DraftVanished vanished) {
            return Optional.empty();
        }
    }

    /**
     * Writes the first commit of a missing draft with the tip of the local target branch as its parent, else the tip
     * of {@code main}, else as a root commit, and only then publishes the draft reference, expecting it absent. A
     * first write that is refused publishes nothing, so no draft is listed that holds no change of the user.
     *
     * @return the result of the write, or empty when the publication met a held lock or a draft another writer
     *         published first; JGit 7.8.0 reports both as {@link RefUpdate.Result#LOCK_FAILURE}
     */
    private Optional<StoreResult<String>> create(Author author, DocumentKey key, String refName, List<TreeEdit> edits,
            String message) throws IOException {
        Optional<ObjectId> base = tip(StoreReferences.target(key));
        if (base.isEmpty()) {
            base = tip(StoreReferences.MAIN);
        }
        List<String> parents = base.map(id -> List.of(id.name())).orElse(List.of());
        GitResult<String> written = writer.write(repository, parents, metadata(author, message), edits);
        if (!written.isOk()) {
            return Optional.of(StoreResult.passOn(written));
        }
        String commit = Objects.requireNonNull(written.value());
        RefUpdate publish = repository.updateRef(refName);
        publish.setExpectedOldObjectId(ObjectId.zeroId());
        publish.setNewObjectId(ObjectId.fromString(commit));
        publish.disableRefLog();
        RefUpdate.Result result = publish.update();
        return switch (result) {
            case NEW -> Optional.of(StoreResult.ok(commit));
            case LOCK_FAILURE -> {
                LOGGER.debug("The publication of the draft %s met a held lock or another writer", refName);
                yield Optional.empty();
            }
            case IO_FAILURE -> Optional.of(StoreResult.passOn(GitResult.of(GitOutcome.FAILED, refName + ": " + result)));
            default -> Optional.of(StoreResult.passOn(GitResult.of(GitOutcome.REJECTED, refName + ": " + result)));
        };
    }

    /** The metadata of a draft commit, its times read from the clock now. */
    private CommitMetadata metadata(Author author, String message) {
        var now = clock.instant();
        return new CommitMetadata(identities.author(author, now), identities.committer(now), message, null);
    }

    /**
     * Lists the drafts of a user, sorted by reference name.
     *
     * @param pseudonym the pseudonym of the user
     * @return {@link StoreOutcome#OK} with the drafts, possibly none, without a reference whose tip is no commit;
     *         {@link StoreOutcome#MALFORMED} for an invalid pseudonym; the passed-on {@link GitOutcome#FAILED} for an
     *         I/O failure
     * @throws NullPointerException if {@code pseudonym} is {@code null}
     */
    StoreResult<List<Draft>> list(String pseudonym) {
        Objects.requireNonNull(pseudonym, "pseudonym");
        if (!StoreReferences.isPseudonym(pseudonym)) {
            return StoreResult.of(StoreOutcome.MALFORMED, StoreReferences.INVALID_PSEUDONYM);
        }
        String prefix = StoreReferences.drafts(pseudonym);
        try (RevWalk walk = new RevWalk(repository)) {
            List<Ref> refs = new ArrayList<>(repository.getRefDatabase().getRefsByPrefix(prefix));
            refs.sort(Comparator.comparing(Ref::getName));
            List<Draft> drafts = new ArrayList<>(refs.size());
            for (Ref ref : refs) {
                draft(walk, prefix, ref).ifPresent(drafts::add);
            }
            return StoreResult.ok(List.copyOf(drafts));
        } catch (IOException e) {
            return StoreResult.passOn(failure(prefix, e));
        }
    }

    /**
     * The draft a reference below the drafts of a user holds; empty for a reference that is no draft: one not of the
     * form {@code <collection>/<id>}, without a tip, or whose tip is no commit.
     *
     * @throws IOException if the tip cannot be read
     */
    private static Optional<Draft> draft(RevWalk walk, String prefix, Ref ref) throws IOException {
        String[] segments = ref.getName().substring(prefix.length()).split("/");
        ObjectId id = ref.getObjectId();
        if (segments.length != 2 || id == null) {
            // only this class writes below refs/drafts/, always as <collection>/<id>
            LOGGER.debug("Skipped the reference %s, which is no draft of the form <collection>/<id>", ref.getName());
            return Optional.empty();
        }
        try {
            RevCommit commit = walk.parseCommit(id);
            return Optional.of(new Draft(segments[0], segments[1], id.name(),
                    commit.getCommitterIdent().getWhenAsInstant()));
        } catch (IncorrectObjectTypeException e) {
            // a reference of this form whose tip is no commit was not written by this class; it is no draft
            LOGGER.debug("Skipped a reference below %s whose tip is no commit: %s", StoreReferences.DRAFTS,
                    e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<ObjectId> tip(String refName) throws IOException {
        Ref ref = repository.exactRef(refName);
        return ref == null ? Optional.empty() : Optional.ofNullable(ref.getObjectId());
    }

    /**
     * Builds the failure of an I/O error: the reference and the class of the error, never its message, which may
     * carry a path or a content; the debug log carries the same and no exception (GDR-NFR-3).
     */
    static <V> GitResult<V> failure(String refName, IOException e) {
        String type = e.getClass().getSimpleName();
        LOGGER.debug("I/O failure on %s: %s", refName, type);
        return GitResult.of(GitOutcome.FAILED, refName + ": " + type);
    }

    /**
     * Ends a compare-and-swap whose draft vanished after it was read, before any commit is written; carries no stack
     * trace, it is control flow within this class.
     */
    private static final class DraftVanished extends RuntimeException {

        private static final long serialVersionUID = 1L;

        DraftVanished() {
            super(null, null, false, false);
        }
    }
}
