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
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.tools.logging.CuiLogger;
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
 * A draft that does not exist yet is first created pointing at the tip of the local target branch of its document
 * ({@link StoreReferences#target(DocumentKey)}), else at the tip of {@code refs/heads/main}, else at nothing, so its
 * first commit has the state the user started from as its parent. That creation is a {@link RefUpdate} that expects the
 * reference to be absent (the zero id) and writes no reflog; when a concurrent writer created the draft first, its
 * {@link RefUpdate.Result#LOCK_FAILURE} is not an error, the compare-and-swap that follows writes on top of that draft.
 * Every draft commit is written by {@link ReferenceUpdater#compareAndSwap}, unsigned and without trailers, with the
 * author and committer of {@link CommitIdentities}; its edits are applied onto the draft's current tree.
 * <p>
 * A detail names a reference, never a content, a pseudonym beyond the reference or an address (GDR-NFR-3).
 */
final class DraftReferences {

    private static final CuiLogger LOGGER = new CuiLogger(DraftReferences.class);

    private final Repository repository;
    private final ReferenceUpdater updater;
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
     *         its race, {@link GitOutcome#FAILED} for an I/O failure)
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
        var created = createIfMissing(refName, key);
        if (!created.isOk()) {
            return StoreResult.passOn(created);
        }
        var now = clock.instant();
        var metadata = new CommitMetadata(identities.author(author, now), identities.committer(now), message, null);
        GitResult<String> written = updater.compareAndSwap(repository, refName, metadata, current -> copy, attempts);
        return written.isOk() ? StoreResult.ok(Objects.requireNonNull(written.value())) : StoreResult.passOn(written);
    }

    /**
     * Lists the drafts of a user, sorted by reference name.
     *
     * @param pseudonym the pseudonym of the user
     * @return {@link StoreOutcome#OK} with the drafts, possibly none; {@link StoreOutcome#MALFORMED} for an invalid
     *         pseudonym; the passed-on {@link GitOutcome#FAILED} for an I/O failure
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
                String[] segments = ref.getName().substring(prefix.length()).split("/");
                ObjectId id = ref.getObjectId();
                if (segments.length != 2 || id == null) {
                    // only this class writes below refs/drafts/, always as <collection>/<id>
                    LOGGER.debug("Skipped the reference %s, which is no draft of the form <collection>/<id>",
                            ref.getName());
                    continue;
                }
                RevCommit commit = walk.parseCommit(id);
                drafts.add(new Draft(segments[0], segments[1], id.name(),
                        commit.getCommitterIdent().getWhenAsInstant()));
            }
            return StoreResult.ok(List.copyOf(drafts));
        } catch (IOException e) {
            return StoreResult.passOn(failure(prefix, e));
        }
    }

    /**
     * Creates a missing draft reference at the tip of the local target branch, else of {@code main}, else leaves it
     * absent so that the first draft commit is a root commit.
     */
    private GitResult<String> createIfMissing(String refName, DocumentKey key) {
        try {
            if (repository.exactRef(refName) != null) {
                return GitResult.ok(refName);
            }
            Optional<ObjectId> start = tip(StoreReferences.target(key));
            if (start.isEmpty()) {
                start = tip(StoreReferences.MAIN);
            }
            if (start.isEmpty()) {
                return GitResult.ok(refName);
            }
            RefUpdate create = repository.updateRef(refName);
            create.setExpectedOldObjectId(ObjectId.zeroId());
            create.setNewObjectId(start.get());
            create.disableRefLog();
            RefUpdate.Result result = create.update();
            return switch (result) {
                case NEW -> GitResult.ok(refName);
                case LOCK_FAILURE -> {
                    // a concurrent writer created the draft first; the compare-and-swap writes on top of it
                    LOGGER.debug("The draft %s was created concurrently", refName);
                    yield GitResult.ok(refName);
                }
                case IO_FAILURE -> GitResult.of(GitOutcome.FAILED, refName + ": " + result);
                default -> GitResult.of(GitOutcome.REJECTED, refName + ": " + result);
            };
        } catch (IOException e) {
            return failure(refName, e);
        }
    }

    private Optional<ObjectId> tip(String refName) throws IOException {
        Ref ref = repository.exactRef(refName);
        return ref == null ? Optional.empty() : Optional.ofNullable(ref.getObjectId());
    }

    /**
     * Builds the failure of an I/O error: the reference and the class of the error, never its message, which may
     * carry a path or a content.
     */
    static <V> GitResult<V> failure(String refName, IOException e) {
        LOGGER.debug(e, "I/O failure on %s", refName);
        return GitResult.of(GitOutcome.FAILED, refName + ": " + e.getClass().getSimpleName());
    }
}
