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
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;


import de.cuioss.git.document.core.CommitMetadata;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.JGitRepositoryOperations;
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
 * A save runs under the shared side of the clone's maintenance lock and under the lock of its draft reference, and
 * each of its attempts under the clone's transport lock ({@link RepositoryGuard}). The draft lock serialises the saves
 * of one draft within the process: a save reads the draft tip and its applied-draft reference only after every
 * earlier save of that draft has settled, so it never applies again the changes an earlier save applied; a failed
 * write of the applied-draft reference for a kept draft is outside that guarantee (see {@code setApplied}). The save
 * reads the draft tip once, and every attempt applies that draft commit, so a draft written during the save is not
 * published with the expectations and the message of this save. An attempt fetches with the mirror reference
 * specifications {@link JGitRepositoryOperations#MIRROR_FETCH_REF_SPECS}, passed explicitly, and the credentials of
 * the technical user, pruning every reference below {@code refs/heads/}, {@code refs/tags/} and {@code refs/notes/}
 * the remote deleted: JGit 7.8.0 prunes only the destinations of the given reference specifications, so a draft or an
 * applied-draft reference is never pruned, whatever fetch reference specifications the clone's configuration holds.
 * It takes the local tip of the target branch as the base; a target branch that does not exist (any more) starts at the local tip of {@code main} (else at
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
 * An optional {@link SaveStep} runs on every attempt, after the conflict check and before the commit is written, with
 * a read-only view of the base and of the tree the draft's edits produce on it. The edits it returns are added to the
 * draft's edits of the same commit, an edit on a path the draft also changed replacing the draft's edit. The edits
 * together are checked once more for a file and a directory overlap, against the base and between two puts, every path
 * of the edits counting as changed, so a step put that would replace a directory of the base, or turn a file into a
 * directory, is a conflict as well, while a step edit disjoint from the base's files, such as a counter, stays valid;
 * its refusal
 * ends the save with {@link StoreOutcome#REFUSED}, and a {@link RuntimeException} it throws with the passed-on
 * {@link GitOutcome#FAILED} naming the class of the exception only, without a further attempt. In both cases nothing is
 * written or pushed, and draft and target branch stay as they are. A draft without changes calls no step.
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
 * After the save released every lock, the references its fetches moved in the clone and the commit its push placed
 * on the target branch are emitted to the {@link StoreEvents} of the writer, so the index refreshes (GDR-STORE-9); a
 * save that moved nothing emits nothing.
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

    /** The detail of a save whose per-attempt step refused, after the target. */
    private static final String STEP_REFUSED = ": step: refused";

    /** The detail of a save whose per-attempt step threw, after the target and before the class of the exception. */
    private static final String STEP_FAILED = ": step: ";

    private final Clone clone;
    private final RepositoryOperations operations;
    private final TechnicalUser user;
    private final CommitIdentities identities;
    private final Clock clock;
    private final int attempts;
    private final StoreEvents events;
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
     * @param events     the notification seam of the writer, told of the moved references after the locks were
     *                   released
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    ExplicitSave(Clone clone, RepositoryOperations operations, TechnicalUser user, CommitIdentities identities,
            Clock clock, int attempts, StoreEvents events) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.user = Objects.requireNonNull(user, "user");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
        this.attempts = attempts;
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * Saves the draft of the author for the document onto its target branch.
     *
     * @param author          the author, whose pseudonym names the draft reference
     * @param key             the document or entry
     * @param expectedBlobIds the blob id the caller loaded per path; the zero id expects the path to be absent
     * @param message         the commit message
     * @param step            the per-attempt step; {@code null} for none
     * @return {@link SaveResult.Saved} with the commit the target branch points at; {@link SaveResult.Conflicting}
     *         with every path whose blob on the fetched tip differs from its expected id; {@link SaveResult.NotSaved}
     *         with {@link StoreOutcome#MALFORMED} for an invalid pseudonym, {@link StoreOutcome#REFUSED} for a refusal
     *         of the step, {@link GitOutcome#NOT_FOUND} for a missing draft or an expected blob id that is no full
     *         object id, {@link GitOutcome#FAILED} for a step that threw, {@link GitOutcome#REJECTED} after every
     *         attempt lost its lease or was rejected, or the passed-on outcome that ended the save, for example
     *         {@link GitOutcome#UNREACHABLE} or {@link GitOutcome#CREDENTIALS_INVALID}
     * @throws NullPointerException if an argument but {@code step}, a path or an expected blob id is {@code null}
     */
    SaveResult save(Author author, DocumentKey key, Map<String, String> expectedBlobIds, String message,
            @Nullable SaveStep step) {
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
        var request = new Request(author, target, Objects.requireNonNull(draftRef.value()), expected, message, step,
                new ArrayList<>());
        RepositoryGuard guard = clone.guard();
        SaveResult result = report(target,
                guard.shared(() -> guard.draft(request.draft(), () -> saveDraft(request))));
        events.emit(request.updates());
        return result;
    }

    /**
     * Reads the draft once and runs the attempts on that draft commit, all under the shared side of the maintenance
     * lock, so no expiry deletes or collects the draft between two attempts, and under the lock of the draft, so the
     * draft and its applied-draft reference are read after every earlier save of the draft has settled.
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
                    .refSpecs(JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS).credentials(user.credentials())
                    .prune(true).build());
            if (!fetched.isOk()) {
                return Optional.of(passedOn(fetched));
            }
            StoreEvents.fetched(fetched.value(), request.updates());
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
                List<TreeEdit> edits = edits(repository, changed, draftCommit.getTree());
                @Nullable SaveStep step = request.step();
                if (step == null) {
                    return apply(request, drafted.draft(), base, edits);
                }
                var stepped = step(request, step, new AttemptView(repository, baseCommit, edits));
                SaveResult.@Nullable NotSaved ended = stepped.ended();
                if (ended != null) {
                    return Optional.of(ended);
                }
                List<TreeEdit> merged = merge(edits, Objects.requireNonNull(stepped.edits()));
                List<String> overlapping = overlapping(repository, merged, baseCommit);
                if (!overlapping.isEmpty()) {
                    return Optional.of(new SaveResult.Conflicting(overlapping));
                }
                return apply(request, drafted.draft(), base, merged);
            }
        } catch (IOException e) {
            return Optional.of(failed(request, e));
        }
    }

    /**
     * Calls the per-attempt step. A refusal ends the save with {@link StoreOutcome#REFUSED}; a
     * {@link RuntimeException} with the passed-on {@link GitOutcome#FAILED}, naming its class only and attaching no
     * throwable to a log record.
     */
    private static Stepped step(Request request, SaveStep step, AttemptView view) {
        SaveStep.Outcome outcome;
        try {
            outcome = Objects.requireNonNull(step.apply(view), "the step returned no outcome");
        }
        /*TODO: Catch specific not RuntimeException. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
        catch (RuntimeException e) {
            String type = e.getClass().getSimpleName();
            LOGGER.debug("The save step of the save onto %s threw %s", request.target(), type);
            return new Stepped(null, notSaved(GitOutcome.FAILED, request.target() + STEP_FAILED + type));
        }
        return switch (outcome) {
            case SaveStep.Edits(List<TreeEdit> edits) -> new Stepped(edits, null);
            case SaveStep.Refusal refusal -> new Stepped(null,
                    new SaveResult.NotSaved(StoreOutcome.REFUSED, request.target() + STEP_REFUSED));
        };
    }

    /**
     * The edits of the save commit: the draft's edits, each replaced by the step's edit of the same path, followed by
     * the step's edits of every other path.
     */
    private static List<TreeEdit> merge(List<TreeEdit> draftEdits, List<TreeEdit> stepEdits) {
        Map<String, TreeEdit> merged = new LinkedHashMap<>();
        draftEdits.forEach(edit -> merged.put(edit.path(), edit));
        stepEdits.forEach(edit -> merged.put(edit.path(), edit));
        return List.copyOf(merged.values());
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
        request.updates().add(StoreEvents.Update.pushed(request.target(), base.lease().name(), commit));
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

    /**
     * Every put of the save commit's edits, the draft's and the step's together, that overlaps as a file and a
     * directory what the edits do not resolve: another put at one of its parents or below it, or the base as
     * {@link #overlaps} checks it with every path of the edits counting as changed. Sorted and distinct.
     */
    private static List<String> overlapping(Repository repository, List<TreeEdit> edits, @Nullable RevCommit base)
            throws IOException {
        Set<String> paths = new HashSet<>();
        Set<String> puts = new HashSet<>();
        for (TreeEdit edit : edits) {
            paths.add(edit.path());
            if (edit instanceof TreeEdit.Put) {
                puts.add(edit.path());
            }
        }
        Set<String> conflicts = new TreeSet<>();
        for (String path : puts) {
            if (nested(path, puts) || base != null && overlaps(repository, path, paths, base)) {
                conflicts.add(path);
            }
        }
        return List.copyOf(conflicts);
    }

    /** Whether another put lies at one of the path's parents or below the path. */
    private static boolean nested(String path, Set<String> puts) {
        for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
            if (puts.contains(path.substring(0, slash))) {
                return true;
            }
        }
        String prefix = path + "/";
        return puts.stream().anyMatch(other -> other.startsWith(prefix));
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
     * under the draft and transport locks and the expiry under the exclusive lock write it, so the update is forced. A
     * failure is logged by the class of the error only: the next save then applies the draft's changes since its merge
     * base.
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
     * @param step     the per-attempt step; {@code null} for none
     * @param updates  the references the save moved, in the clone by its fetches and on the remote by its push,
     *                 collected on the saving thread and emitted after the locks were released
     */
    private record Request(Author author, String target, String draft, Map<String, ObjectId> expected,
    String message, @Nullable SaveStep step, List<StoreEvents.Update> updates) {
    }

    /**
     * What the per-attempt step decided: the edits to add, or the result that ends the save.
     *
     * @param edits the step's edits; {@code null} when the save ended
     * @param ended the result that ends the save; {@code null} when the step returned edits
     */
    private record Stepped(@Nullable List<TreeEdit> edits, SaveResult.@Nullable NotSaved ended) {
    }

    /**
     * The read-only view of one attempt given to the per-attempt step: the base tree overlaid with the draft's edits,
     * read on demand, writing no object. A read failure surfaces as an {@link UncheckedIOException}, which ends the
     * save like any exception of the step.
     */
    private static final class AttemptView implements SaveStep.Attempt {

        private final Repository repository;
        private final @Nullable RevCommit base;
        private final Map<String, TreeEdit> edits = new HashMap<>();

        AttemptView(Repository repository, @Nullable RevCommit base, List<TreeEdit> draftEdits) {
            this.repository = repository;
            this.base = base;
            draftEdits.forEach(edit -> edits.put(edit.path(), edit));
        }

        @Override
        public @Nullable String tip() {
            return base == null ? null : base.name();
        }

        @Override
        public Optional<byte[]> tipContent(String path) {
            Objects.requireNonNull(path, "path");
            try {
                return baseContent(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public Optional<byte[]> content(String path) {
            Objects.requireNonNull(path, "path");
            TreeEdit edit = edits.get(path);
            if (edit instanceof TreeEdit.Put put) {
                return Optional.of(put.content());
            }
            if (edit != null || replaced(path) || becameDirectory(path)) {
                return Optional.empty();
            }
            return tipContent(path);
        }

        @Override
        public List<String> paths(String directory) {
            Objects.requireNonNull(directory, "directory");
            String prefix = directory.isEmpty() ? "" : directory + "/";
            Set<String> paths = new TreeSet<>();
            if (base != null) {
                try (TreeWalk walk = new TreeWalk(repository)) {
                    walk.addTree(base.getTree());
                    walk.setRecursive(true);
                    if (!directory.isEmpty()) {
                        walk.setFilter(PathFilter.create(directory));
                    }
                    while (walk.next()) {
                        String path = walk.getPathString();
                        if (path.startsWith(prefix) && !edits.containsKey(path) && !replaced(path)
                                && !becameDirectory(path)) {
                            paths.add(path);
                        }
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            edits.values().stream().filter(TreeEdit.Put.class::isInstance).map(TreeEdit::path)
                    .filter(path -> path.startsWith(prefix)).forEach(paths::add);
            return List.copyOf(paths);
        }

        /** Whether a put of the draft at one of the path's parents replaced the directory holding the path. */
        private boolean replaced(String path) {
            for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
                if (edits.get(path.substring(0, slash)) instanceof TreeEdit.Put) {
                    return true;
                }
            }
            return false;
        }

        /** Whether a put of the draft below the path turned a file of the base at the path into a directory. */
        private boolean becameDirectory(String path) {
            String prefix = path + "/";
            return edits.values().stream()
                    .anyMatch(edit -> edit instanceof TreeEdit.Put && edit.path().startsWith(prefix));
        }

        private Optional<byte[]> baseContent(String path) throws IOException {
            if (base == null || path.isEmpty()) {
                return Optional.empty();
            }
            try (TreeWalk found = TreeWalk.forPath(repository, path, base.getTree())) {
                if (found == null || FileMode.TREE.equals(found.getRawMode(0))) {
                    return Optional.empty();
                }
                return Optional.of(repository.open(found.getObjectId(0), Constants.OBJ_BLOB)
                        .getBytes(Integer.MAX_VALUE));
            }
        }
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
