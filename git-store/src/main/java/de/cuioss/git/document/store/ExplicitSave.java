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
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.git.document.core.CommitMetadata;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.core.RepositoryReader;
import de.cuioss.git.document.core.RepositoryReader.ChangeType;
import de.cuioss.git.document.core.RepositoryReader.PathChange;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * The explicit save of one bare clone (GDR-STORE-3, GDR-STORE-4, GDR-STORE-5, GDR-STORE-8): the draft's changes
 * applied onto the remote tip of the target branch as one commit, pushed with lease.
 * <p>
 * An attempt runs under the shared side of the clone's maintenance lock and under its transport lock
 * ({@link RepositoryGuard}): it reads the draft tip, fetches with the configured mirror reference specifications and
 * the credentials of the technical user, and takes the local tip of the target branch as the base; a target branch
 * that does not exist yet starts at the local tip of {@code main} (else at nothing, so the commit is a root commit) and
 * is pushed against the zero-id lease, which expects the remote branch to be absent. The changes of the draft are the
 * paths changed between the merge base of draft tip and base ({@code RevFilter.MERGE_BASE}) and the draft tip
 * ({@link RepositoryReader#changes}); without a merge base every file of the draft is a change. Every changed path
 * with an expected blob id is checked against the base, the zero id expecting the path to be absent there, and every
 * differing path is a conflict: nothing is pushed, draft and target branch stay as they are. Otherwise one commit with
 * the base as its only parent and the changes as its edits is written through {@link InCoreWriter}, unsigned, without
 * trailers, with the author and committer of {@link CommitIdentities}, and pushed to the target with the lease of the
 * base.
 * <p>
 * The save is a cooperative compare-and-swap against the remote: a lease refusal or a rejection starts the next
 * attempt from the fetch, up to the configured attempts, then the save is {@link GitOutcome#REJECTED}; an unreachable
 * remote or invalid credentials end the save at once with nothing changed. Only after the remote accepted the commit
 * the local target branch moves by a {@link RefUpdate} expecting the base, with the reflog disabled, and the draft is
 * deleted by {@link ReferenceUpdater#compareAndDelete} expecting the applied draft commit, so a newer draft is kept. A
 * draft without changes is saved as the base itself, with no commit and no push. The save commit is the only commit
 * pushed; no other commit the store writes is combined with it.
 * <p>
 * A detail names a reference or a field, never a path, a content, a pseudonym or an address (GDR-NFR-3).
 */
final class ExplicitSave {

    private static final CuiLogger LOGGER = new CuiLogger(ExplicitSave.class);

    /** The remote of every bare clone of the store. */
    private static final String ORIGIN = "origin";

    /** The detail of a save whose draft does not exist. */
    private static final String DRAFT_NOT_FOUND = "draft: not found";

    /** The detail of an expected blob id that is no full object id. */
    private static final String INVALID_EXPECTED_BLOB_ID = "expectedBlobIds: not a full object id";

    /** The detail of a draft without changes in a repository without the target branch and without {@code main}. */
    private static final String NOTHING_TO_SAVE = "draft: no changes and no target branch";

    private final Clone clone;
    private final RepositoryOperations operations;
    private final TechnicalUser user;
    private final CommitIdentities identities;
    private final Clock clock;
    private final int attempts;
    private final InCoreWriter writer = new InCoreWriter();
    private final ReferenceUpdater updater = new ReferenceUpdater(writer);
    private final RepositoryReader reader = new RepositoryReader();

    /**
     * The bare clone a save writes to.
     *
     * @param alias      the alias of the repository
     * @param repository the opened bare clone
     * @param gitDir     the git directory of the bare clone, for the transport operations
     * @param guard      the in-process coordination of the clone
     */
    record Clone(String alias, Repository repository, Path gitDir, RepositoryGuard guard) {

        /**
         * Checks the components.
         *
         * @param alias      the alias
         * @param repository the opened clone
         * @param gitDir     the git directory
         * @param guard      the guard
         * @throws NullPointerException if a component is {@code null}
         */
        Clone {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(gitDir, "gitDir");
            Objects.requireNonNull(guard, "guard");
        }
    }

    /**
     * Creates the explicit save of a clone.
     *
     * @param clone      the bare clone
     * @param operations the repository operations of {@code git-core}, for fetch and push
     * @param user       the technical user, holder of the credentials of every fetch and push
     * @param identities the commit identities
     * @param clock      the clock of the commit times
     * @param attempts   the attempts of a save ({@code save.max-attempts}), at least 1
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    ExplicitSave(Clone clone, RepositoryOperations operations, TechnicalUser user, CommitIdentities identities,
            Clock clock, int attempts) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.user = Objects.requireNonNull(user, "user");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
        this.attempts = attempts;
    }

    /**
     * Saves the draft of the author for the document onto its target branch.
     *
     * @param author          the author, whose pseudonym names the draft reference
     * @param key             the document or entry
     * @param expectedBlobIds the blob id the caller loaded per path; the zero id expects the path to be absent
     * @param message         the commit message
     * @return {@link SaveResult.Saved} with the commit the target branch points at; {@link SaveResult.Conflicting}
     *         with every path whose blob on the fetched tip differs from its expected id; {@link SaveResult.NotSaved}
     *         with {@link StoreOutcome#MALFORMED} for an invalid pseudonym, {@link GitOutcome#NOT_FOUND} for a missing
     *         draft or an expected blob id that is no full object id, {@link GitOutcome#REJECTED} after every attempt
     *         lost its lease or was rejected, or the passed-on outcome that ended the save, for example
     *         {@link GitOutcome#UNREACHABLE} or {@link GitOutcome#CREDENTIALS_INVALID}
     * @throws NullPointerException if an argument, a path or an expected blob id is {@code null}
     */
    SaveResult save(Author author, DocumentKey key, Map<String, String> expectedBlobIds, String message) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(key, "key");
        Map<String, String> expectations = Map.copyOf(Objects.requireNonNull(expectedBlobIds, "expectedBlobIds"));
        Objects.requireNonNull(message, "message");
        String target = StoreReferences.target(key);
        Map<String, ObjectId> expected = new HashMap<>();
        for (Map.Entry<String, String> expectation : expectations.entrySet()) {
            if (!ObjectId.isId(expectation.getValue())) {
                return report(target, notSaved(GitOutcome.NOT_FOUND, INVALID_EXPECTED_BLOB_ID));
            }
            expected.put(expectation.getKey(), ObjectId.fromString(expectation.getValue()));
        }
        var draftRef = StoreReferences.draft(author.pseudonym(), key);
        if (!draftRef.isOk()) {
            return report(target, SaveResult.NotSaved.of(draftRef));
        }
        var request = new Request(author, target, Objects.requireNonNull(draftRef.value()), expected, message);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Optional<SaveResult> done = clone.guard().shared(() -> clone.guard().transport(() -> attempt(request)));
            if (done.isPresent()) {
                return report(target, done.get());
            }
            LOGGER.debug("The save onto %s lost its lease or was rejected, attempt %s of %s", target, attempt,
                    attempts);
        }
        return report(target, notSaved(GitOutcome.REJECTED,
                target + ": lease refused or rejected on every attempt (" + attempts + ")"));
    }

    /**
     * Runs one attempt.
     *
     * @return the result of the save, or empty when the push lost its lease or was rejected and the next attempt
     *         starts from the fetch
     */
    private Optional<SaveResult> attempt(Request request) {
        Repository repository = clone.repository();
        try {
            Ref draftTip = repository.exactRef(request.draft());
            if (draftTip == null || draftTip.getObjectId() == null) {
                return Optional.of(notSaved(GitOutcome.NOT_FOUND, DRAFT_NOT_FOUND));
            }
            ObjectId draft = draftTip.getObjectId();
            var fetched = operations.fetch(FetchInput.builder().root(clone.gitDir()).remote(ORIGIN)
                    .refSpecs(List.of()).credentials(user.credentials()).build());
            if (!fetched.isOk()) {
                return Optional.of(passedOn(fetched));
            }
            Base base = base(repository, request.target());
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit draftCommit = walk.parseCommit(draft);
                @Nullable ObjectId baseId = base.commit();
                @Nullable RevCommit baseCommit = baseId == null ? null : walk.parseCommit(baseId);
                var changes = changes(repository, draftCommit, baseCommit);
                if (!changes.isOk()) {
                    return Optional.of(passedOn(changes));
                }
                List<PathChange> changed = Objects.requireNonNull(changes.value());
                if (changed.isEmpty()) {
                    return Optional.of(unchanged(request, draft, baseCommit));
                }
                List<String> conflicts = conflicts(repository, changed, request.expected(), baseCommit);
                if (!conflicts.isEmpty()) {
                    return Optional.of(new SaveResult.Conflicting(conflicts));
                }
                return apply(request, draft, base, edits(repository, changed, draftCommit.getTree()));
            }
        } catch (IOException e) {
            LOGGER.debug(e, "I/O failure of the save onto %s", request.target());
            return Optional.of(notSaved(GitOutcome.FAILED, request.target() + ": " + e.getClass().getSimpleName()));
        }
    }

    /** Writes the save commit, pushes it with the lease and, once accepted, moves the local branch and the draft. */
    private Optional<SaveResult> apply(Request request, ObjectId draft, Base base, List<TreeEdit> edits)
            throws IOException {
        var now = clock.instant();
        var metadata = new CommitMetadata(identities.author(request.author(), now), identities.committer(now),
                request.message(), null);
        @Nullable ObjectId parent = base.commit();
        List<String> parents = parent == null ? List.of() : List.of(parent.name());
        GitResult<String> written = writer.write(clone.repository(), parents, metadata, edits);
        if (!written.isOk()) {
            return Optional.of(passedOn(written));
        }
        String commit = Objects.requireNonNull(written.value());
        var pushed = operations.push(PushInput.builder().root(clone.gitDir()).remote(ORIGIN).localRef(commit)
                .remoteRef(request.target()).expectedRemoteHead(base.lease().name()).credentials(user.credentials())
                .build());
        if (pushed.outcome() == GitOutcome.LEASE_REFUSED || pushed.outcome() == GitOutcome.REJECTED) {
            return Optional.empty();
        }
        if (!pushed.isOk()) {
            return Optional.of(passedOn(pushed));
        }
        moveLocal(request.target(), base.localExpected(), ObjectId.fromString(commit));
        deleteDraft(request.draft(), draft);
        return Optional.of(new SaveResult.Saved(commit));
    }

    /** A draft without changes: saved as the base itself, with no commit and no push; the draft is deleted. */
    private SaveResult unchanged(Request request, ObjectId draft, @Nullable RevCommit baseCommit) {
        if (baseCommit == null) {
            return notSaved(GitOutcome.NOT_FOUND, NOTHING_TO_SAVE);
        }
        deleteDraft(request.draft(), draft);
        return new SaveResult.Saved(baseCommit.name());
    }

    /**
     * The base of an attempt: the local tip of the target branch, else of {@code main} with the zero-id lease, else
     * nothing with the zero-id lease.
     */
    private static Base base(Repository repository, String target) throws IOException {
        Ref targetRef = repository.exactRef(target);
        if (targetRef != null && targetRef.getObjectId() != null) {
            ObjectId tip = targetRef.getObjectId();
            return new Base(tip, tip, tip);
        }
        Ref main = repository.exactRef(StoreReferences.MAIN);
        @Nullable ObjectId start = main == null ? null : main.getObjectId();
        return new Base(start, ObjectId.zeroId(), ObjectId.zeroId());
    }

    /**
     * The paths the draft changed: between the merge base of draft tip and base and the draft tip, or every file of
     * the draft when there is no merge base.
     */
    private GitResult<List<PathChange>> changes(Repository repository, RevCommit draft, @Nullable RevCommit base)
            throws IOException {
        @Nullable ObjectId mergeBase = base == null ? null : mergeBase(repository, draft, base);
        if (mergeBase != null) {
            return reader.changes(repository, mergeBase.name(), draft.name());
        }
        var snapshot = reader.snapshot(repository, draft.name());
        if (!snapshot.isOk()) {
            return GitResult.of(snapshot.outcome(), Objects.requireNonNull(snapshot.detail()));
        }
        return GitResult.ok(Objects.requireNonNull(snapshot.value()).files().stream()
                .map(file -> new PathChange(file.path(), ChangeType.ADDED)).toList());
    }

    private static @Nullable ObjectId mergeBase(Repository repository, RevCommit draft, RevCommit base)
            throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(draft));
            walk.markStart(walk.parseCommit(base));
            RevCommit found = walk.next();
            return found == null ? null : found.copy();
        }
    }

    /** Every changed path with an expected blob id that differs from the path's blob on the base. */
    private static List<String> conflicts(Repository repository, List<PathChange> changed,
            Map<String, ObjectId> expected, @Nullable RevCommit base) throws IOException {
        List<String> conflicts = new ArrayList<>();
        for (PathChange change : changed) {
            ObjectId expectation = expected.get(change.path());
            if (expectation != null && !expectation.equals(blob(repository, change.path(), base))) {
                conflicts.add(change.path());
            }
        }
        return conflicts;
    }

    /** The blob id of a path on a commit; the zero id when the commit or the path is absent. */
    private static ObjectId blob(Repository repository, String path, @Nullable RevCommit commit) throws IOException {
        if (commit == null) {
            return ObjectId.zeroId();
        }
        try (TreeWalk found = TreeWalk.forPath(repository, path, commit.getTree())) {
            return found == null ? ObjectId.zeroId() : found.getObjectId(0);
        }
    }

    /** The edits of the save: the content of every added or modified path of the draft, a delete of every other. */
    private static List<TreeEdit> edits(Repository repository, List<PathChange> changed, RevTree draftTree)
            throws IOException {
        List<TreeEdit> edits = new ArrayList<>(changed.size());
        for (PathChange change : changed) {
            if (change.type() == ChangeType.DELETED) {
                edits.add(new TreeEdit.Delete(change.path()));
            } else {
                try (TreeWalk found = TreeWalk.forPath(repository, change.path(), draftTree)) {
                    ObjectId blob = Objects.requireNonNull(found, "a changed path exists on the draft").getObjectId(0);
                    byte[] content = repository.open(blob, Constants.OBJ_BLOB).getBytes(Integer.MAX_VALUE);
                    edits.add(new TreeEdit.Put(change.path(), content));
                }
            }
        }
        return edits;
    }

    /**
     * Moves the local target branch to the accepted commit, expecting the base. A failed move changes nothing the
     * remote holds; the next fetch mirrors the remote branch into the clone.
     */
    private void moveLocal(String target, ObjectId expected, ObjectId commit) throws IOException {
        RefUpdate update = clone.repository().updateRef(target);
        update.setExpectedOldObjectId(expected);
        update.setNewObjectId(commit);
        update.disableRefLog();
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
            LOGGER.debug("The local branch %s was not moved after the accepted save: %s", target, result);
        }
    }

    /** Deletes the draft by compare-and-delete, expecting the applied draft commit; a moved draft is kept. */
    private void deleteDraft(String draftRef, ObjectId applied) {
        var deleted = updater.compareAndDelete(clone.repository(), draftRef, applied.name(), attempts);
        if (!deleted.isOk()) {
            LOGGER.debug("The draft was kept after the save: %s", deleted.outcome());
        }
    }

    /** Logs the result of a save and returns it. */
    private SaveResult report(String target, SaveResult result) {
        switch (result) {
            case SaveResult.Saved(String commit) -> LOGGER.info(GitStoreLogMessages.INFO.SAVED, clone.alias(), target,
                    commit);
            case SaveResult.Conflicting(List<String> paths) -> LOGGER.info(GitStoreLogMessages.INFO.SAVE_CONFLICT,
                    clone.alias(), target, paths.size());
            case SaveResult.NotSaved notSaved -> LOGGER.warn(GitStoreLogMessages.WARN.SAVE_NOT_COMPLETED,
                    clone.alias(), target, name(notSaved.outcome()));
        }
        return result;
    }

    private static String name(StoreResult.Outcome outcome) {
        return switch (outcome) {
            case StoreOutcome store -> store.name();
            case StoreResult.PassedOn(GitOutcome git) -> git.name();
        };
    }

    private static SaveResult.NotSaved notSaved(GitOutcome outcome, String detail) {
        return new SaveResult.NotSaved(new StoreResult.PassedOn(outcome), detail);
    }

    private static SaveResult.NotSaved passedOn(GitResult<?> result) {
        return new SaveResult.NotSaved(new StoreResult.PassedOn(result.outcome()),
                Objects.requireNonNullElse(result.detail(), result.outcome().name()));
    }

    /**
     * The input of a save.
     *
     * @param author   the author
     * @param target   the target branch
     * @param draft    the draft reference
     * @param expected the expected blob id per path
     * @param message  the commit message
     */
    private record Request(Author author, String target, String draft, Map<String, ObjectId> expected,
    String message) {
    }

    /**
     * The base of an attempt.
     *
     * @param commit        the parent of the save commit; {@code null} for a root commit
     * @param lease         the id the remote target branch is expected at, the zero id for an absent branch
     * @param localExpected the id the local target branch is expected at when it moves, the zero id for an absent one
     */
    private record Base(@Nullable ObjectId commit, ObjectId lease, ObjectId localExpected) {
    }
}
