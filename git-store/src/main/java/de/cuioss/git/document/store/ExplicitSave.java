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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


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
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.jspecify.annotations.Nullable;

/**
 * The explicit save of one bare clone (GDR-STORE-3, GDR-STORE-4, GDR-STORE-5, GDR-STORE-8): the draft's changes
 * applied onto the remote tip of the target branch as one commit, pushed with lease.
 * <p>
 * A save runs under the shared side of the clone's maintenance lock, and each of its attempts under the clone's
 * transport lock ({@link RepositoryGuard}). The save reads the draft tip once, and every attempt applies that draft
 * commit, so a draft written during the save is not published with the expectations and the message of this save. An
 * attempt fetches with the configured mirror reference specifications and the credentials of the technical user,
 * pruning every mirrored reference the remote deleted (never a draft), and takes the local tip of the target branch
 * as the base; a target branch that does not exist (any more) starts at the local tip of {@code main} (else at
 * nothing, so the commit is a root commit) and is pushed against the zero-id lease, which expects the remote branch to
 * be absent. The changes of the draft are the paths changed since the draft commit a former save applied
 * ({@link StoreReferences#APPLIED}), when the draft descends from it; otherwise between the merge base of draft tip
 * and base ({@code RevFilter.MERGE_BASE}) and the draft tip ({@link RepositoryReader#changes}); without a merge base
 * every file of the draft is a change. Every changed path with an expected blob id is checked against the base, the
 * zero id expecting the path to be absent there, and every written path that would turn a file of the base into a
 * directory, or replace a directory of the base holding a file the draft did not change, is checked for that overlap;
 * every differing or overlapping path is a conflict: nothing is pushed, draft and target branch stay as they are.
 * Otherwise one commit with the base as its only parent and the changes as its edits is written through
 * {@link InCoreWriter}, unsigned, without trailers, with the author and committer of {@link CommitIdentities}, and
 * pushed to the target with the lease of the base.
 * <p>
 * The save is a cooperative compare-and-swap against the remote: a lease refusal or a rejection starts the next
 * attempt from the fetch, up to the configured attempts, then the save is {@link GitOutcome#REJECTED}; an unreachable
 * remote or invalid credentials end the save at once with nothing changed. Only after the remote accepted the commit
 * the local target branch moves by a {@link RefUpdate} expecting the base, with the reflog disabled (an I/O failure
 * there is logged and the save stays {@link SaveResult.Saved}), and the draft is deleted by
 * {@link ReferenceUpdater#compareAndDelete} expecting the applied draft commit, so a newer draft is kept; for a kept
 * draft the applied-draft reference records the applied commit, otherwise it is deleted with the draft. A draft
 * without changes is saved as the tip of an existing target branch, with no commit and no push; without the target
 * branch it is {@link GitOutcome#NOT_FOUND} and kept. The save commit is the only commit pushed; no other commit the
 * store writes is combined with it.
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

    /** The detail of a draft without changes in a repository without its target branch. */
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
        return report(target, clone.guard().shared(() -> saveDraft(request)));
    }

    /**
     * Reads the draft once and runs the attempts on that draft commit, all under the shared side of the maintenance
     * lock, so no expiry deletes or collects the draft between two attempts.
     */
    private SaveResult saveDraft(Request request) {
        Repository repository = clone.repository();
        Ref draftTip;
        Ref appliedTip;
        try {
            draftTip = repository.exactRef(request.draft());
            appliedTip = repository.exactRef(StoreReferences.applied(request.draft()));
        } catch (IOException e) {
            return failed(request, e);
        }
        @Nullable ObjectId draft = draftTip == null ? null : draftTip.getObjectId();
        if (draft == null) {
            return notSaved(GitOutcome.NOT_FOUND, DRAFT_NOT_FOUND);
        }
        var drafted = new Drafted(draft, appliedTip == null ? null : appliedTip.getObjectId());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Optional<SaveResult> done = clone.guard().transport(() -> attempt(request, drafted));
            if (done.isPresent()) {
                return done.get();
            }
            LOGGER.debug("The save onto %s lost its lease or was rejected, attempt %s of %s", request.target(),
                    attempt, attempts);
        }
        return notSaved(GitOutcome.REJECTED,
                request.target() + ": lease refused or rejected on every attempt (" + attempts + ")");
    }

    /**
     * Runs one attempt on the draft commit the save started with: fetches, pruning the mirrored references the remote
     * deleted, and applies the draft's changes onto the fetched base.
     *
     * @return the result of the save, or empty when the push lost its lease or was rejected and the next attempt
     *         starts from the fetch
     */
    private Optional<SaveResult> attempt(Request request, Drafted drafted) {
        Repository repository = clone.repository();
        try {
            var fetched = operations.fetch(FetchInput.builder().root(clone.gitDir()).remote(ORIGIN)
                    .refSpecs(List.of()).credentials(user.credentials()).prune(true).build());
            if (!fetched.isOk()) {
                return Optional.of(passedOn(fetched));
            }
            Base base = base(repository, request.target());
            try (RevWalk walk = new RevWalk(repository)) {
                RevCommit draftCommit = walk.parseCommit(drafted.draft());
                @Nullable ObjectId baseId = base.commit();
                @Nullable RevCommit baseCommit = baseId == null ? null : walk.parseCommit(baseId);
                var changes = changes(repository, draftCommit, baseCommit, drafted.applied());
                if (!changes.isOk()) {
                    return Optional.of(passedOn(changes));
                }
                List<PathChange> changed = Objects.requireNonNull(changes.value());
                if (changed.isEmpty()) {
                    return Optional.of(unchanged(request, drafted.draft(), base));
                }
                List<String> conflicts = conflicts(repository, changed, request.expected(), baseCommit);
                if (!conflicts.isEmpty()) {
                    return Optional.of(new SaveResult.Conflicting(conflicts));
                }
                return apply(request, drafted.draft(), base, edits(repository, changed, draftCommit.getTree()));
            }
        } catch (IOException e) {
            return Optional.of(failed(request, e));
        }
    }

    /** The failure of an I/O error: the target and the class of the error, never its message. */
    private static SaveResult failed(Request request, IOException e) {
        LOGGER.debug("I/O failure of the save onto %s: %s", request.target(), e.getClass().getSimpleName());
        return notSaved(GitOutcome.FAILED, request.target() + ": " + e.getClass().getSimpleName());
    }

    /**
     * Writes the save commit, pushes it with the lease and, once accepted, moves the local branch and settles the
     * draft. A local branch that cannot be moved after the remote accepted the commit does not undo the save: it is
     * logged by the class of the error only, and the next fetch mirrors the remote branch into the clone.
     */
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
        try {
            moveLocal(request.target(), base.localExpected(), ObjectId.fromString(commit));
        } catch (IOException e) {
            String type = e.getClass().getSimpleName();
            LOGGER.debug("The local branch %s was not moved after the accepted save: %s", request.target(), type);
            LOGGER.warn(GitStoreLogMessages.WARN.LOCAL_BRANCH_NOT_MOVED, clone.alias(), request.target(), type);
        }
        settle(request.draft(), draft);
        return Optional.of(new SaveResult.Saved(commit));
    }

    /**
     * A draft without changes. On an existing target branch it is saved as the branch tip itself, with no commit and
     * no push, and the draft is settled; without the target branch nothing is saved and the draft is kept, because no
     * branch exists that a {@link SaveResult.Saved} could name.
     */
    private SaveResult unchanged(Request request, ObjectId draft, Base base) {
        @Nullable ObjectId tip = base.commit();
        if (tip == null || ObjectId.zeroId().equals(base.lease())) {
            return notSaved(GitOutcome.NOT_FOUND, NOTHING_TO_SAVE);
        }
        settle(request.draft(), draft);
        return new SaveResult.Saved(tip.name());
    }

    /**
     * The base of an attempt: the local tip of the target branch, else of {@code main} with the zero-id lease, else
     * nothing with the zero-id lease. The fetch before has pruned a target branch the remote deleted.
     */
    private static Base base(Repository repository, String target) throws IOException {
        Ref targetRef = repository.exactRef(target);
        @Nullable ObjectId tip = targetRef == null ? null : targetRef.getObjectId();
        if (tip != null) {
            return new Base(tip, tip, tip);
        }
        Ref main = repository.exactRef(StoreReferences.MAIN);
        @Nullable ObjectId start = main == null ? null : main.getObjectId();
        return new Base(start, ObjectId.zeroId(), ObjectId.zeroId());
    }

    /**
     * The paths the draft changed: since the draft commit a former save applied, when the draft descends from it;
     * otherwise between the merge base of draft tip and base and the draft tip, or every file of the draft when there
     * is no merge base.
     */
    private GitResult<List<PathChange>> changes(Repository repository, RevCommit draft, @Nullable RevCommit base,
            @Nullable ObjectId applied) throws IOException {
        if (applied != null && descends(repository, draft, applied)) {
            return reader.changes(repository, applied.name(), draft.name());
        }
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

    /** Whether the draft commit descends from, or is, the commit a former save applied. */
    private static boolean descends(Repository repository, RevCommit draft, ObjectId applied) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.isMergedInto(walk.parseCommit(applied), walk.parseCommit(draft));
        }
    }

    /**
     * Every changed path with an expected blob id that differs from the path's blob on the base, and every written
     * path that overlaps the base as a file and a directory in a way the draft did not resolve.
     */
    private static List<String> conflicts(Repository repository, List<PathChange> changed,
            Map<String, ObjectId> expected, @Nullable RevCommit base) throws IOException {
        Set<String> changedPaths = new HashSet<>();
        changed.forEach(change -> changedPaths.add(change.path()));
        List<String> conflicts = new ArrayList<>();
        for (PathChange change : changed) {
            ObjectId expectation = expected.get(change.path());
            boolean differs = expectation != null && !expectation.equals(blob(repository, change.path(), base));
            boolean overlaps = change.type() != ChangeType.DELETED && base != null
                    && overlaps(repository, change.path(), changedPaths, base);
            if (differs || overlaps) {
                conflicts.add(change.path());
            }
        }
        return conflicts;
    }

    /**
     * Whether writing a file at the path would replace what the draft did not change on the base: a file of the base
     * at one of the path's parents, which the edit turns into a directory, or a file of the base below the path, which
     * the edit replaces by the file. A change of the draft at that place, such as the delete of the parent file,
     * resolves the overlap.
     */
    private static boolean overlaps(Repository repository, String path, Set<String> changed, RevCommit base)
            throws IOException {
        for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
            String parent = path.substring(0, slash);
            if (!changed.contains(parent) && isFile(repository, parent, base)) {
                return true;
            }
        }
        try (TreeWalk below = new TreeWalk(repository)) {
            below.addTree(base.getTree());
            below.setRecursive(true);
            below.setFilter(PathFilter.create(path));
            while (below.next()) {
                if (!changed.contains(below.getPathString())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether the commit holds a file, anything but a directory, at the path. */
    private static boolean isFile(Repository repository, String path, RevCommit commit) throws IOException {
        try (TreeWalk found = TreeWalk.forPath(repository, path, commit.getTree())) {
            return found != null && !FileMode.TREE.equals(found.getRawMode(0));
        }
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

    /**
     * Settles the draft after a save: deletes it by compare-and-delete, expecting the applied draft commit, together
     * with its applied-draft reference. A draft that moved meanwhile is kept, and its applied-draft reference is set to
     * the applied commit, so its next save applies only what it changed since.
     */
    private void settle(String draftRef, ObjectId applied) {
        String appliedRef = StoreReferences.applied(draftRef);
        var deleted = updater.compareAndDelete(clone.repository(), draftRef, applied.name(), attempts);
        if (deleted.isOk()) {
            setApplied(appliedRef, null);
        } else if (deleted.outcome() == GitOutcome.CONFLICT) {
            LOGGER.debug("The draft moved during the save and is kept");
            setApplied(appliedRef, applied);
        } else {
            LOGGER.debug("The draft was kept after the save: %s", deleted.outcome());
        }
    }

    /**
     * Points the applied-draft reference at the commit, or deletes it for {@code null}; without a reflog. Only a save
     * under the transport lock and the expiry under the exclusive lock write it, so the update is forced. A failure is
     * logged by the class of the error only: the next save then applies the draft's changes since its merge base.
     */
    private void setApplied(String appliedRef, @Nullable ObjectId commit) {
        Repository repository = clone.repository();
        try {
            if (commit == null && repository.exactRef(appliedRef) == null) {
                return;
            }
            RefUpdate update = repository.updateRef(appliedRef);
            update.setForceUpdate(true);
            update.disableRefLog();
            RefUpdate.Result result;
            if (commit == null) {
                result = update.delete();
            } else {
                update.setNewObjectId(commit);
                result = update.update();
            }
            LOGGER.debug("The applied-draft reference was written: %s", result);
        } catch (IOException e) {
            LOGGER.debug("The applied-draft reference was not written: %s", e.getClass().getSimpleName());
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
        GitOutcome outcome = result.outcome();
        @Nullable String detail = result.detail();
        String shown = detail == null ? outcome.name() : detail;
        return new SaveResult.NotSaved(new StoreResult.PassedOn(outcome), shown);
    }

    /**
     * The draft a save works on, read once at its start.
     *
     * @param draft   the draft commit every attempt applies
     * @param applied the draft commit a former save applied while a newer draft was kept; {@code null} when none
     */
    private record Drafted(ObjectId draft, @Nullable ObjectId applied) {
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
