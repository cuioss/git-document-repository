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
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;


import de.cuioss.git.document.core.CommitMetadata;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.InCoreMerger;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.MergeResult;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.git.document.core.RepositoryOperations.PushInput;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * The release of a document or numbered entry of one bare clone (GDR-STORE-6): the saved state merged into
 * {@code main} as one release commit, tagged {@code refs/tags/<collection>/<id>/v<n>}, and both pushed.
 * <p>
 * A release runs under the shared side of the clone's maintenance lock, and each of its attempts under the clone's
 * transport lock ({@link RepositoryGuard}), from the fetch to the push of the tag, so no fetch of another operation of
 * the process prunes the local tag before it is pushed. An attempt fetches with
 * {@link JGitRepositoryOperations#MIRROR_FETCH_REF_SPECS} and the credentials of the technical user, pruning what the
 * remote deleted, and reads {@code main}, the working or collection branch and the release tags of the document once.
 * The version {@code n} is one more than the highest {@code v<k>} below the document's tag prefix
 * ({@link StoreReferences#releaseTags}); the message of the release commit is always {@code Release <collection>/<id>
 * v<n>}, composed by the store.
 * <p>
 * A commit on the first-parent chain of {@code main}, after the commit of {@code v<n-1>}, that carries that message is a
 * release {@code v<n>} whose tag is missing: the attempt creates and pushes only the tag on that commit and never merges
 * again. Otherwise it merges in core: for a per-document collection {@link InCoreMerger} merges the working branch into
 * {@code main}, and the release commit has the tip of {@code main} and the tip of the working branch as its parents and
 * the edits from the tree of {@code main} to the merged tree; for a numbered collection only the paths of the entry
 * ({@link DocumentPaths}) are taken from the collection branch onto {@code main}, the only parent. A conflicting merge
 * ends the release with the passed-on {@link GitOutcome#CONFLICT} naming the number of conflicting paths. The commit is
 * written by {@link InCoreWriter}, unsigned and without trailers, with the author and committer of
 * {@link CommitIdentities}; {@link #metadata} is the one place that builds its metadata.
 * <p>
 * The local tag is written by an update that expects it absent and writes no reflog. Then {@code main} is pushed with
 * the lease of the fetched tip (the zero id for an absent {@code main}), and after that the tag against the zero-id
 * lease. {@code git-core} pushes one reference per call, so the two pushes are not atomic: when {@code main} was
 * accepted and the tag push failed, the release is {@link StoreOutcome#INCOMPLETE}, and a repeated release creates the
 * missing tag. A lease refusal of {@code main} starts the next attempt from the fetch, up to the configured attempts,
 * then the release is {@link GitOutcome#REJECTED}; a rejection of {@code main}, for example by a protected branch, ends
 * the release at once as {@link GitOutcome#REJECTED}. A local tag whose push never happened is pruned by the next
 * fetch, since the remote lacks it; no tag on the remote is ever moved or deleted.
 * <p>
 * After the release released every lock, the references its fetches moved in the clone, the release commit its push
 * placed on {@code main} and its pushed tag are emitted to the {@link StoreEvents} of the writer, so the index
 * refreshes (GDR-STORE-9).
 * <p>
 * A detail names a reference or a field, never a path, a content or a pseudonym (GDR-NFR-3).
 */
final class DocumentRelease {

    private static final CuiLogger LOGGER = new CuiLogger(DocumentRelease.class);

    /** The remote of every bare clone of the store. */
    private static final String ORIGIN = "origin";

    /** The detail of a key of content without release. */
    private static final String NO_RELEASE = "key: content without release";

    private final ExplicitSave.Clone clone;
    private final RepositoryOperations operations;
    private final TechnicalUser user;
    private final CommitIdentities identities;
    private final Clock clock;
    private final int attempts;
    private final StoreEvents events;
    private final InCoreWriter writer = new InCoreWriter();
    private final InCoreMerger merger = new InCoreMerger();

    /**
     * Creates the release of a clone.
     *
     * @param clone      the bare clone
     * @param operations the repository operations of {@code git-core}, for fetch and push
     * @param user       the technical user, holder of the credentials of every fetch and push
     * @param identities the commit identities
     * @param clock      the clock of the commit times
     * @param attempts   the attempts of a release ({@code save.max-attempts}), at least 1
     * @param events     the notification seam of the writer, told of the moved references after the locks were
     *                   released
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    DocumentRelease(ExplicitSave.Clone clone, RepositoryOperations operations, TechnicalUser user,
            CommitIdentities identities, Clock clock, int attempts, StoreEvents events) {
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
     * Releases a document or numbered entry.
     *
     * @param author the author of the release commit, the user who releases
     * @param key    the document or entry
     * @return {@link StoreOutcome#OK} with the {@link Release}; {@link StoreOutcome#INCOMPLETE} when {@code main} moved
     *         and the tag is missing; {@link StoreOutcome#MALFORMED} for content without release; the passed-on
     *         {@link GitOutcome#NOT_FOUND} for a missing working or collection branch, {@link GitOutcome#CONFLICT} for a
     *         conflicting merge, {@link GitOutcome#REJECTED} for a rejected push of {@code main} or after every attempt
     *         lost its lease, or the passed-on outcome that ended the release, for example
     *         {@link GitOutcome#UNREACHABLE}
     * @throws NullPointerException if an argument is {@code null}
     */
    StoreResult<Release> release(Author author, DocumentKey key) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(key, "key");
        String source = StoreReferences.target(key);
        if (key.collection().kind() == DocumentCollection.Kind.MAIN) {
            return report(source, StoreResult.of(StoreOutcome.MALFORMED, NO_RELEASE));
        }
        var request = new Request(author, key, source, new ArrayList<>());
        RepositoryGuard guard = clone.guard();
        StoreResult<Release> result = report(source, guard.shared(() -> attempts(request)));
        events.emit(request.updates());
        return result;
    }

    /** Runs the attempts of a release, each under the transport lock. */
    private StoreResult<Release> attempts(Request request) {
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Optional<StoreResult<Release>> done = clone.guard().transport(() -> attempt(request));
            if (done.isPresent()) {
                return done.get();
            }
            LOGGER.debug("The release of %s lost its lease on %s, attempt %s of %s", request.source(),
                    StoreReferences.MAIN, attempt, attempts);
        }
        return passedOn(GitOutcome.REJECTED,
                StoreReferences.MAIN + ": lease refused on every attempt (" + attempts + ")");
    }

    /**
     * Runs one attempt: fetches, reads the state once, then pushes the missing tag of a merged release, or merges,
     * tags and pushes.
     *
     * @return the result of the release, or empty when the push of {@code main} lost its lease and the next attempt
     *         starts from the fetch
     */
    private Optional<StoreResult<Release>> attempt(Request request) {
        Repository repository = clone.repository();
        try {
            var fetched = operations.fetch(FetchInput.builder().root(clone.gitDir()).remote(ORIGIN)
                    .refSpecs(JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS).credentials(user.credentials())
                    .prune(true).build());
            if (!fetched.isOk()) {
                return Optional.of(StoreResult.passOn(fetched));
            }
            StoreEvents.fetched(fetched.value(), request.updates());
            @Nullable ObjectId main = tip(repository, StoreReferences.MAIN);
            @Nullable ObjectId source = tip(repository, request.source());
            if (source == null) {
                return Optional.of(passedOn(GitOutcome.NOT_FOUND, request.source() + ": not found"));
            }
            Versions versions = versions(repository, request.key());
            int version = versions.next();
            String tag = StoreReferences.releaseTag(request.key(), version);
            String message = message(request.key(), version);
            @Nullable ObjectId merged = main == null ? null
                    : mergedRelease(repository, main, versions.previous(), message);
            if (merged != null) {
                LOGGER.debug("The release %s is on %s without its tag; pushing the tag only", tag,
                        StoreReferences.MAIN);
                return Optional.of(tagAndPush(request, merged, tag, version));
            }
            GitResult<String> written = write(repository, request, main, source, message);
            if (!written.isOk()) {
                return Optional.of(StoreResult.passOn(written));
            }
            ObjectId commit = ObjectId.fromString(Objects.requireNonNull(written.value()));
            if (!createTag(repository, tag, commit)) {
                return Optional.of(passedOn(GitOutcome.REJECTED, tag + ": exists"));
            }
            ObjectId lease = main == null ? ObjectId.zeroId() : main;
            var pushed = operations.push(PushInput.builder().root(clone.gitDir()).remote(ORIGIN)
                    .localRef(commit.name()).remoteRef(StoreReferences.MAIN).expectedRemoteHead(lease.name())
                    .credentials(user.credentials()).build());
            if (pushed.outcome() == GitOutcome.LEASE_REFUSED) {
                return Optional.empty();
            }
            if (!pushed.isOk()) {
                return Optional.of(StoreResult.passOn(pushed));
            }
            request.updates().add(StoreEvents.Update.pushed(StoreReferences.MAIN, lease.name(), commit.name()));
            moveMain(repository, lease, commit);
            return Optional.of(pushTag(request, tag, commit, version));
        } catch (IOException e) {
            String type = e.getClass().getSimpleName();
            LOGGER.debug("I/O failure of the release of %s: %s", request.source(), type);
            return Optional.of(passedOn(GitOutcome.FAILED, request.source() + ": " + type));
        }
    }

    /** Creates the missing local tag of a merged release and pushes it. */
    private StoreResult<Release> tagAndPush(Request request, ObjectId commit, String tag, int version)
            throws IOException {
        if (!createTag(clone.repository(), tag, commit)) {
            return passedOn(GitOutcome.REJECTED, tag + ": exists");
        }
        return pushTag(request, tag, commit, version);
    }

    /**
     * Pushes the tag against the zero-id lease; after {@code main} moved on the remote, a failure is
     * {@link StoreOutcome#INCOMPLETE}.
     */
    private StoreResult<Release> pushTag(Request request, String tag, ObjectId commit, int version) {
        var pushed = operations.push(PushInput.builder().root(clone.gitDir()).remote(ORIGIN).localRef(commit.name())
                .remoteRef(tag).expectedRemoteHead(ObjectId.zeroId().name()).credentials(user.credentials())
                .build());
        if (!pushed.isOk()) {
            LOGGER.debug("The push of the release tag %s failed: %s", tag, pushed.outcome());
            LOGGER.warn(GitStoreLogMessages.WARN.RELEASE_INCOMPLETE, clone.alias(), tag, commit.name());
            return StoreResult.of(StoreOutcome.INCOMPLETE, tag + ": not pushed (" + pushed.outcome().name() + ")");
        }
        request.updates().add(StoreEvents.Update.pushed(tag, ObjectId.zeroId().name(), commit.name()));
        return StoreResult.ok(new Release(tag, commit.name(), version));
    }

    /**
     * Writes the release commit: for a per-document collection the merge of the working branch into {@code main}, for
     * a numbered collection the entry's paths of the collection branch onto {@code main}.
     */
    private GitResult<String> write(Repository repository, Request request, @Nullable ObjectId main, ObjectId source,
            String message) throws IOException {
        CommitMetadata metadata = metadata(request.author(), message);
        if (request.key().collection().kind() == DocumentCollection.Kind.NUMBERED) {
            List<String> parents = main == null ? List.of() : List.of(main.name());
            return writer.write(repository, parents, metadata, entryEdits(repository, request.key(), main, source));
        }
        if (main == null) {
            return writer.write(repository, List.of(source.name()), metadata, List.of());
        }
        return switch (merger.merge(repository, main.name(), source.name())) {
            case MergeResult.Merged(String treeId) -> writer.write(repository, List.of(main.name(), source.name()),
                    metadata, edits(repository, commitTree(repository, main), ObjectId.fromString(treeId), null));
            case MergeResult.Conflicting(List<String> paths) -> GitResult.of(GitOutcome.CONFLICT,
                    request.source() + ": " + paths.size() + " conflicting paths");
            case MergeResult.NotMerged(GitOutcome outcome, String reason) -> GitResult.of(outcome, reason);
        };
    }

    /**
     * The metadata of a release commit: author and committer of {@link CommitIdentities}, the store-composed message,
     * no signing and no trailers. GDR-STORE-13 extends this one method with the identity trailers.
     */
    private CommitMetadata metadata(Author author, String message) {
        var now = clock.instant();
        return new CommitMetadata(identities.author(author, now), identities.committer(now), message, null);
    }

    /** The edits that take the entry's paths of the collection branch onto {@code main}. */
    private static List<TreeEdit> entryEdits(Repository repository, DocumentKey key, @Nullable ObjectId main,
            ObjectId source) throws IOException {
        @Nullable ObjectId mainTree = main == null ? null : commitTree(repository, main);
        return edits(repository, mainTree, commitTree(repository, source), key);
    }

    /**
     * The edits from one tree to another: a put of every file that differs or is new, a delete of every file the
     * second tree lacks; with a key, only the paths that belong to it.
     */
    private static List<TreeEdit> edits(Repository repository, @Nullable ObjectId from, ObjectId to,
            @Nullable DocumentKey key) throws IOException {
        List<TreeEdit> edits = new ArrayList<>();
        try (TreeWalk walk = new TreeWalk(repository)) {
            if (from == null) {
                walk.addTree(new EmptyTreeIterator());
            } else {
                walk.addTree(from);
            }
            walk.addTree(to);
            walk.setRecursive(true);
            while (walk.next()) {
                String path = walk.getPathString();
                boolean unchanged = walk.getRawMode(0) == walk.getRawMode(1) && walk.idEqual(0, 1);
                if (key != null && !DocumentPaths.belongs(key, path) || unchanged) {
                    continue;
                }
                if (walk.getRawMode(1) == FileMode.MISSING.getBits()) {
                    edits.add(new TreeEdit.Delete(path));
                } else {
                    byte[] content = repository.open(walk.getObjectId(1), Constants.OBJ_BLOB)
                            .getBytes(Integer.MAX_VALUE);
                    edits.add(new TreeEdit.Put(path, content));
                }
            }
        }
        return edits;
    }

    /**
     * The commit of a merged release {@code v<n>} whose tag is missing: the first commit on the first-parent chain of
     * {@code main}, after the commit of {@code v<n-1>}, that carries the store-composed message of {@code v<n>}.
     */
    private static @Nullable ObjectId mergedRelease(Repository repository, ObjectId main, @Nullable ObjectId previous,
            String message) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(main);
            while (commit != null && !commit.equals(previous)) {
                walk.parseBody(commit);
                if (message.equals(commit.getFullMessage().strip())) {
                    return commit.copy();
                }
                commit = commit.getParentCount() == 0 ? null : walk.parseCommit(commit.getParent(0));
            }
            return null;
        }
    }

    /**
     * The release tags of the document after the fetch: the next version and the object the latest one points at, the
     * release commit for the lightweight tags the store writes.
     */
    private static Versions versions(Repository repository, DocumentKey key) throws IOException {
        int highest = 0;
        @Nullable ObjectId previous = null;
        for (Ref ref : repository.getRefDatabase().getRefsByPrefix(StoreReferences.releaseTags(key))) {
            OptionalInt version = StoreReferences.releaseVersion(key, ref.getName());
            if (version.isPresent() && version.getAsInt() > highest) {
                highest = version.getAsInt();
                previous = ref.getObjectId();
            }
        }
        return new Versions(highest + 1, previous);
    }

    /** The message of a release commit, composed by the store. */
    private static String message(DocumentKey key, int version) {
        return "Release " + key.collection().name() + "/" + key.id() + " v" + version;
    }

    /** Writes the local tag by an update that expects it absent, without a reflog; whether it was created. */
    private static boolean createTag(Repository repository, String tag, ObjectId commit) throws IOException {
        RefUpdate update = repository.updateRef(tag);
        update.setExpectedOldObjectId(ObjectId.zeroId());
        update.setNewObjectId(commit);
        update.disableRefLog();
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW) {
            LOGGER.debug("The local release tag %s was not created: %s", tag, result);
            return false;
        }
        return true;
    }

    /**
     * Moves the local {@code main} to the accepted release commit, expecting the fetched tip, without a reflog. A failed
     * move changes nothing the remote holds; the next fetch mirrors the remote {@code main} into the clone.
     */
    private static void moveMain(Repository repository, ObjectId expected, ObjectId commit) {
        try {
            RefUpdate update = repository.updateRef(StoreReferences.MAIN);
            update.setExpectedOldObjectId(expected);
            update.setNewObjectId(commit);
            update.disableRefLog();
            RefUpdate.Result result = update.update();
            if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
                LOGGER.debug("The local main was not moved after the accepted release: %s", result);
            }
        } catch (IOException e) {
            LOGGER.debug("The local main was not moved after the accepted release: %s", e.getClass().getSimpleName());
        }
    }

    private static @Nullable ObjectId tip(Repository repository, String ref) throws IOException {
        Ref found = repository.exactRef(ref);
        return found == null ? null : found.getObjectId();
    }

    private static ObjectId commitTree(Repository repository, AnyObjectId commit) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(commit).getTree().copy();
        }
    }

    /**
     * Logs the result of a release and returns it; an {@link StoreOutcome#INCOMPLETE} release was logged where its tag
     * and commit are known.
     */
    private StoreResult<Release> report(String source, StoreResult<Release> result) {
        if (result.isOk()) {
            Release release = Objects.requireNonNull(result.value());
            LOGGER.info(GitStoreLogMessages.INFO.RELEASED, clone.alias(), release.tag(), release.commitId());
        } else if (result.outcome() != StoreOutcome.INCOMPLETE) {
            LOGGER.warn(GitStoreLogMessages.WARN.RELEASE_NOT_COMPLETED, clone.alias(), source, name(result.outcome()));
        }
        return result;
    }

    private static String name(StoreResult.Outcome outcome) {
        return switch (outcome) {
            case StoreOutcome store -> store.name();
            case StoreResult.PassedOn(GitOutcome git) -> git.name();
        };
    }

    private static <V> StoreResult<V> passedOn(GitOutcome outcome, String detail) {
        return new StoreResult<>(new StoreResult.PassedOn(outcome), null, detail);
    }

    /**
     * The input of a release.
     *
     * @param author the author of the release commit
     * @param key    the document or entry
     * @param source  the working or collection branch
     * @param updates the references the release moved, in the clone by its fetches and on the remote by its pushes,
     *                collected on the releasing thread and emitted after the locks were released
     */
    private record Request(Author author, DocumentKey key, String source, List<StoreEvents.Update> updates) {
    }

    /**
     * The release tags of a document as the attempt found them.
     *
     * @param next     the version of this release
     * @param previous the commit of the latest release; {@code null} for the first release
     */
    private record Versions(int next, @Nullable ObjectId previous) {
    }
}
