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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;


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
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
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
 * v<n>}, composed by the store, followed by the {@link IdentityTrailers} of the releasing user's pseudonym and
 * {@link IdentityClaims} (GDR-STORE-13).
 * <p>
 * A commit on the first-parent chain of {@code main}, after the commit of {@code v<n-1>}, whose first paragraph (the
 * text before the first blank line) is that store-composed subject and that has the shape of a release of the document
 * is a release {@code v<n>} whose tag is missing: the attempt creates and pushes only the tag on that commit and never
 * merges again. No trailer is read for that decision, and a save refuses a message whose first paragraph is a release
 * subject of its document ({@link #isReleaseSubject}), and a save onto {@code main} one with the form of a release
 * subject of any document ({@link #isAnyReleaseSubject}), so no caller-written message can make a save commit a
 * release, however it imitates the subject and the trailers. The shape tells a release from a commit written before that
 * refusal or by another writer: a per-document release has the tip of the working branch at its time as its second parent (or, onto an
 * absent {@code main}, as its only parent with the same tree), reachable from the working branch now; a numbered release
 * has at most one parent and changes no path outside the entry. Such a repair of an {@link StoreOutcome#INCOMPLETE}
 * release reports in {@link Release#branchMovedOn()} whether the working or collection branch moved on since the
 * released commit, so the caller learns that the release does not hold the latest saved state. Otherwise the attempt
 * merges in core: for a per-document collection {@link InCoreMerger} merges the working branch into
 * {@code main}, and the release commit has the tip of {@code main} and the tip of the working branch as its parents and
 * the edits from the tree of {@code main} to the merged tree; for a numbered collection only the paths of the entry
 * ({@link DocumentPaths}) are taken from the collection branch onto {@code main}, the only parent, a path-level copy
 * with conflict check: every path the copy would change is first compared on {@code main} with the commit of the
 * previous release {@code v<n-1>} (with the empty tree for the first release), and a path {@code main} changed since
 * ends the release with the passed-on {@link GitOutcome#CONFLICT} naming the number of such paths, nothing written.
 * Every changed file keeps its mode, so an executable file or a symbolic link of the source stays one
 * ({@link TreeEdit.Mode}). A path the release would set to a gitlink (a submodule entry, mode {@code 160000}) ends the
 * release with {@link StoreOutcome#MALFORMED} and the detail {@value #GITLINK}, before any blob is read:
 * {@link TreeEdit.Mode} carries no gitlink, so {@code git-core} cannot write one. A conflicting merge
 * ends the release with the passed-on {@link GitOutcome#CONFLICT} naming the number of conflicting paths. The commit is
 * written by {@link InCoreWriter} with the author and committer of {@link CommitIdentities}, the trailers, and the
 * signing of {@link CommitSignatures} (GDR-STORE-12); {@link #metadata} is the one place that builds its metadata. The
 * signing is prepared at most once per release, by the first attempt that writes a release commit, and re-used by the
 * later ones, so the public key is fetched once and a release that only pushes a missing tag never asks the source; a
 * source that refuses or cannot be reached ends the release with {@link StoreOutcome#REFUSED} or
 * {@link StoreOutcome#UNAVAILABLE} after the fetch of that attempt and before anything is written, and a signature that
 * fails while an attempt writes its commit ends it with the outcome of the source, nothing pushed.
 * <p>
 * The local tag is written by an update that expects it absent and writes no reflog. Then {@code main} is pushed with
 * the lease of the fetched tip (the zero id for an absent {@code main}), and after that the tag against the zero-id
 * lease. {@code git-core} pushes one reference per call, so the two pushes are not atomic. When the remote refuses
 * the tag ({@link GitOutcome#LEASE_REFUSED} or {@link GitOutcome#REJECTED}), the attempt fetches that one tag, still
 * under the transport lock, and reads it from the result of that fetch: a tag the remote already holds at the released
 * commit, for example pushed by an earlier attempt whose answer was lost, makes the release complete
 * ({@link StoreOutcome#OK}); a tag the remote holds at another commit, or a tag push that failed for another reason,
 * leaves the release {@link StoreOutcome#INCOMPLETE}, and a repeated release creates the missing tag. A lease refusal
 * of {@code main} starts the next attempt from the fetch, up to the configured attempts, then the release is
 * {@link GitOutcome#REJECTED}; the local tag of that attempt is pruned by the next fetch, since the remote lacks it. Every
 * other failure of the push of {@code main}, for example a rejection by a protected branch, ends the release at once
 * with its passed-on outcome and deletes the local tag of the attempt, expecting the release commit, so no local tag
 * points at a commit the remote never accepted. No tag on the remote is ever moved or deleted.
 * <p>
 * After the release released every lock, the references its fetches moved in the clone, the release commit its push
 * placed on {@code main} and its pushed tag are emitted to the {@link StoreEvents} of the writer, so the index
 * refreshes (GDR-STORE-9).
 * <p>
 * Every blob the release copies from the fetched working or collection branch is read bounded by
 * {@link ExplicitSave#BLOB_LIMIT}; a larger one ends the attempt like an unreadable object, as the passed-on
 * {@link GitOutcome#FAILED} naming the class of the exception.
 * <p>
 * A detail names a reference or a field, never a path, a content or a pseudonym, and an exception by its class name
 * ({@link Class#getName()}) only (GDR-NFR-3).
 */
final class DocumentRelease {

    private static final CuiLogger LOGGER = new CuiLogger(DocumentRelease.class);

    /** The detail of a key of content without release. */
    private static final String NO_RELEASE = "key: content without release";

    /** The detail of a release that would write a gitlink; it names the class of the path, never the path. */
    static final String GITLINK = "entry: a gitlink path cannot be released";

    /** The detail suffix of a numbered release whose paths {@code main} changed since the previous release. */
    private static final String CHANGED_ON_MAIN = " paths changed on main since the previous release";

    /**
     * The form of the subject of a release of any document: a collection name ({@code [a-z0-9-]+}), an id
     * ({@code [A-Za-z0-9-]+}, which a ULID fits) and a version.
     */
    private static final Pattern ANY_RELEASE_SUBJECT = Pattern.compile("Release [a-z0-9-]+/[A-Za-z0-9-]+ v\\d+");

    private final ExplicitSave.Clone clone;
    private final RepositoryOperations operations;
    private final TechnicalUser user;
    private final ExplicitSave.Committing committing;
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
     * @param committing the identities, the signing seam and the clock of the release commits
     * @param attempts   the attempts of a release ({@code save.max-attempts}), at least 1
     * @param events     the notification seam of the writer, told of the moved references after the locks were
     *                   released
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    DocumentRelease(ExplicitSave.Clone clone, RepositoryOperations operations, TechnicalUser user,
            ExplicitSave.Committing committing, int attempts, StoreEvents events) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.user = Objects.requireNonNull(user, "user");
        this.committing = Objects.requireNonNull(committing, "committing");
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
     * @param claims the claims of that user's authentication, written as identity trailers
     * @param key    the document or entry
     * @return {@link StoreOutcome#OK} with the {@link Release}, also when the remote refused the tag because it already
     *         holds it at the released commit; {@link StoreOutcome#INCOMPLETE} when {@code main} moved and the tag is
     *         missing or held at another commit; {@link StoreOutcome#MALFORMED} for content without release, an
     *         invalid pseudonym or a gitlink among the paths ({@value #GITLINK}); {@link StoreOutcome#REFUSED} or
     *         {@link StoreOutcome#UNAVAILABLE} of the signing key source; the passed-on {@link GitOutcome#NOT_FOUND}
     *         for a missing working or collection branch, {@link GitOutcome#CONFLICT} for a conflicting merge or for a
     *         numbered entry whose paths {@code main} changed since the previous release, {@link GitOutcome#REJECTED}
     *         for a rejected push of {@code main} or after every attempt lost its lease, or the passed-on outcome that
     *         ended the release, for example {@link GitOutcome#UNREACHABLE}
     * @throws NullPointerException if an argument is {@code null}
     */
    StoreResult<Release> release(Author author, IdentityClaims claims, DocumentKey key) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(claims, "claims");
        Objects.requireNonNull(key, "key");
        String source = StoreReferences.target(key);
        if (key.collection().kind() == DocumentCollection.Kind.MAIN) {
            return report(source, StoreResult.of(StoreOutcome.MALFORMED, NO_RELEASE));
        }
        if (!StoreReferences.isPseudonym(author.pseudonym())) {
            return report(source, StoreResult.of(StoreOutcome.MALFORMED, StoreReferences.INVALID_PSEUDONYM));
        }
        var request = new Request(author, claims, key, source, new ArrayList<>());
        RepositoryGuard guard = clone.guard();
        StoreResult<Release> result = report(source, guard.shared(() -> attempts(request)));
        events.emit(request.updates());
        return result;
    }

    /**
     * Runs the attempts of a release, each under the transport lock; the signing is prepared by the first attempt that
     * writes a release commit and re-used by the later ones.
     */
    private StoreResult<Release> attempts(Request request) {
        CommitSignatures.Lazy signing = committing.signatures().lazy(request.source());
        for (int attempt = 1; attempt <= attempts; attempt++) {
            Optional<StoreResult<Release>> done = clone.guard().transport(() -> attempt(request, signing));
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
    private Optional<StoreResult<Release>> attempt(Request request, CommitSignatures.Lazy lazySigning) {
        Repository repository = clone.repository();
        try {
            var fetched = operations.fetch(FetchInput.builder().root(clone.gitDir()).remote(StoreReferences.ORIGIN)
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
                    : mergedRelease(repository, main, versions.previous(), message, request.key(), source);
            if (merged != null) {
                LOGGER.debug("The release %s is on %s without its tag; pushing the tag only", tag,
                        StoreReferences.MAIN);
                return Optional.of(tagAndPush(request, merged, tag, version,
                        movedOn(repository, merged, request.key(), source)));
            }
            var prepared = lazySigning.get();
            if (!prepared.isOk()) {
                return Optional.of(new StoreResult<>(prepared.outcome(), null, prepared.detail()));
            }
            return mergeAndPush(request, new Attempt(main, source, versions.previous(), version, tag, message),
                    Objects.requireNonNull(prepared.value()));
        } catch (IOException e) {
            String type = e.getClass().getName();
            LOGGER.debug("I/O failure of the release of %s: %s", request.source(), type);
            return Optional.of(passedOn(GitOutcome.FAILED, request.source() + ": " + type));
        }
    }

    /**
     * Writes the signed release commit, tags it and pushes {@code main} with the lease of the fetched tip, then the tag.
     * A failure of the push of {@code main} other than a lost lease deletes the local tag again.
     *
     * @return the result of the release, or empty when the push of {@code main} lost its lease
     */
    private Optional<StoreResult<Release>> mergeAndPush(Request request, Attempt state,
            CommitSignatures.Prepared signing) throws IOException {
        Repository repository = clone.repository();
        StoreResult<List<TreeEdit>> edits = releaseEdits(repository, request, state);
        if (!edits.isOk()) {
            return Optional.of(new StoreResult<>(edits.outcome(), null, edits.detail()));
        }
        GitResult<String> written = writer.write(repository, parents(request.key(), state),
                metadata(request.author(), request.claims(), state.message(), signing),
                Objects.requireNonNull(edits.value()));
        if (!written.isOk()) {
            Optional<StoreResult<Release>> unsigned = committing.signatures().outcomeOf(written, signing,
                    request.source());
            return Optional.of(unsigned.orElseGet(() -> StoreResult.passOn(written)));
        }
        ObjectId commit = ObjectId.fromString(Objects.requireNonNull(written.value()));
        if (!createTag(repository, state.tag(), commit)) {
            return Optional.of(passedOn(GitOutcome.REJECTED, state.tag() + ": exists"));
        }
        @Nullable ObjectId main = state.main();
        ObjectId lease = main == null ? ObjectId.zeroId() : main;
        var pushed = operations.push(PushInput.builder().root(clone.gitDir()).remote(StoreReferences.ORIGIN)
                .localRef(commit.name()).remoteRef(StoreReferences.MAIN).expectedRemoteHead(lease.name())
                .credentials(user.credentials()).build());
        if (pushed.outcome() == GitOutcome.LEASE_REFUSED) {
            return Optional.empty();
        }
        if (!pushed.isOk()) {
            deleteTag(repository, state.tag(), commit);
            return Optional.of(StoreResult.passOn(pushed));
        }
        request.updates().add(StoreEvents.Update.pushed(StoreReferences.MAIN, lease.name(), commit.name()));
        moveMain(repository, lease, commit);
        return Optional.of(pushTag(request, state.tag(), commit, state.version(), false));
    }

    /** Creates the missing local tag of a merged release and pushes it. */
    private StoreResult<Release> tagAndPush(Request request, ObjectId commit, String tag, int version,
            boolean branchMovedOn) throws IOException {
        if (!createTag(clone.repository(), tag, commit)) {
            return passedOn(GitOutcome.REJECTED, tag + ": exists");
        }
        return pushTag(request, tag, commit, version, branchMovedOn);
    }

    /**
     * Pushes the tag against the zero-id lease. A refused push reads the remote tag by a fetch of that one tag, under
     * the transport lock the attempt holds: at the released commit the release is complete; otherwise, after
     * {@code main} moved on the remote, a failure is {@link StoreOutcome#INCOMPLETE}.
     */
    private StoreResult<Release> pushTag(Request request, String tag, ObjectId commit, int version,
            boolean branchMovedOn) {
        var pushed = operations.push(PushInput.builder().root(clone.gitDir()).remote(StoreReferences.ORIGIN).localRef(commit.name())
                .remoteRef(tag).expectedRemoteHead(ObjectId.zeroId().name()).credentials(user.credentials())
                .build());
        if (pushed.isOk()) {
            request.updates().add(StoreEvents.Update.pushed(tag, ObjectId.zeroId().name(), commit.name()));
            return StoreResult.ok(new Release(tag, commit.name(), version, branchMovedOn));
        }
        boolean refused = pushed.outcome() == GitOutcome.LEASE_REFUSED || pushed.outcome() == GitOutcome.REJECTED;
        if (refused && remoteTagAt(request, tag, commit)) {
            LOGGER.debug("The release tag %s was refused because the remote holds it at the released commit", tag);
            return StoreResult.ok(new Release(tag, commit.name(), version, branchMovedOn));
        }
        LOGGER.debug("The push of the release tag %s failed: %s", tag, pushed.outcome());
        LOGGER.warn(GitStoreLogMessages.WARN.RELEASE_INCOMPLETE, clone.alias(), tag, commit.name());
        return StoreResult.of(StoreOutcome.INCOMPLETE, tag + ": not pushed (" + pushed.outcome().name() + ")");
    }

    /**
     * Whether the remote holds the tag at the commit: the result of a fetch of that one tag into the local tag, so the
     * clone mirrors the remote tag afterwards. A failed fetch, a tag the remote lacks among it, is no evidence and
     * answers {@code false}.
     */
    private boolean remoteTagAt(Request request, String tag, ObjectId commit) {
        var fetched = operations.fetch(FetchInput.builder().root(clone.gitDir()).remote(StoreReferences.ORIGIN)
                .refSpecs(List.of("+" + tag + ":" + tag)).credentials(user.credentials()).prune(false).build());
        if (!fetched.isOk()) {
            LOGGER.debug("The refused release tag %s was not fetched: %s", tag, fetched.outcome());
            return false;
        }
        StoreEvents.fetched(fetched.value(), request.updates());
        try {
            return commit.equals(tip(clone.repository(), tag));
        } catch (IOException e) {
            LOGGER.debug("The fetched release tag %s was not read: %s", tag, e.getClass().getName());
            return false;
        }
    }

    /** The parents of the release commit: {@code main} and, for a per-document collection, the working branch. */
    private static List<String> parents(DocumentKey key, Attempt state) {
        @Nullable ObjectId main = state.main();
        if (key.collection().kind() == DocumentCollection.Kind.NUMBERED) {
            return main == null ? List.of() : List.of(main.name());
        }
        return main == null ? List.of(state.source().name()) : List.of(main.name(), state.source().name());
    }

    /**
     * The edits of the release commit: for a per-document collection those from {@code main} to the merge of the
     * working branch into it (none onto an absent {@code main}), for a numbered collection the entry's paths of the
     * collection branch onto {@code main}, each first checked against the previous release.
     *
     * @return {@link StoreOutcome#OK} with the edits; {@link StoreOutcome#MALFORMED} with {@value #GITLINK}; or the
     *         passed-on {@link GitOutcome#CONFLICT} of a conflicting merge or of entry paths {@code main} changed since
     *         the previous release, or the passed-on outcome of a merge that did not run
     */
    private StoreResult<List<TreeEdit>> releaseEdits(Repository repository, Request request, Attempt state)
            throws IOException {
        @Nullable ObjectId main = state.main();
        if (request.key().collection().kind() == DocumentCollection.Kind.NUMBERED) {
            return entryEdits(repository, request, state);
        }
        if (main == null) {
            return StoreResult.ok(List.of());
        }
        return switch (merger.merge(repository, main.name(), state.source().name())) {
            case MergeResult.Merged(String treeId) -> puts(repository,
                    changes(repository, commitTree(repository, main), ObjectId.fromString(treeId), null));
            case MergeResult.Conflicting(List<String> paths) -> passedOn(GitOutcome.CONFLICT,
                    request.source() + ": " + paths.size() + " conflicting paths");
            case MergeResult.NotMerged(GitOutcome outcome, String reason) -> passedOn(outcome, reason);
        };
    }

    /**
     * The metadata of a release commit, built in this one place: author and committer of {@link CommitIdentities},
     * the store-composed subject followed by the identity trailers, and the signing prepared for the release.
     */
    private CommitMetadata metadata(Author author, IdentityClaims claims, String subject,
            CommitSignatures.Prepared signing) {
        var now = committing.clock().instant();
        CommitIdentities identities = committing.identities();
        String message = Objects.requireNonNull(IdentityTrailers.append(subject, author.pseudonym(), claims).value(),
                "the pseudonym was checked before the attempts");
        return new CommitMetadata(identities.author(author, now), identities.committer(now), message,
                signing.signing());
    }

    /**
     * The edits that take the entry's paths of the collection branch onto {@code main}, a path-level copy with conflict
     * check: every path the copy changes must be on {@code main} as it was in the previous release (absent before the
     * first release), else {@code main} changed it since and the release ends with a conflict. The paths are compared
     * by mode and object id, before any blob is read.
     */
    private static StoreResult<List<TreeEdit>> entryEdits(Repository repository, Request request, Attempt state)
            throws IOException {
        @Nullable ObjectId main = state.main();
        @Nullable ObjectId mainTree = main == null ? null : commitTree(repository, main);
        var changes = changes(repository, mainTree, commitTree(repository, state.source()), request.key());
        if (!changes.isOk()) {
            return new StoreResult<>(changes.outcome(), null, changes.detail());
        }
        List<Change> changed = Objects.requireNonNull(changes.value());
        @Nullable ObjectId previous = state.previous();
        @Nullable ObjectId previousTree = previous == null ? null : commitTree(repository, previous);
        int changedOnMain = 0;
        for (Change change : changed) {
            if (!sameEntry(repository, change.path(), mainTree, previousTree)) {
                changedOnMain++;
            }
        }
        if (changedOnMain > 0) {
            return passedOn(GitOutcome.CONFLICT, request.source() + ": " + changedOnMain + CHANGED_ON_MAIN);
        }
        return puts(repository, changes);
    }

    /** Whether a path has the same mode and object id in two trees, an absent tree or path counting as absent. */
    private static boolean sameEntry(Repository repository, String path, @Nullable ObjectId one,
            @Nullable ObjectId other) throws IOException {
        return Objects.equals(entry(repository, path, one), entry(repository, path, other));
    }

    /** The mode and object id of a path in a tree; {@code null} when the tree or the path is absent. */
    private static @Nullable Entry entry(Repository repository, String path, @Nullable ObjectId tree)
            throws IOException {
        if (tree == null) {
            return null;
        }
        try (TreeWalk found = TreeWalk.forPath(repository, path, tree)) {
            return found == null ? null : new Entry(found.getRawMode(0), found.getObjectId(0));
        }
    }

    /**
     * The changed paths from one tree to another: a put of every file that differs or is new, a delete of every file
     * the second tree lacks; with a key, only the paths that belong to it. No blob is read: a put of a gitlink ends the
     * changes as {@link StoreOutcome#MALFORMED} with {@value #GITLINK} before {@link #puts} reads any content.
     */
    private static StoreResult<List<Change>> changes(Repository repository, @Nullable ObjectId from, ObjectId to,
            @Nullable DocumentKey key) throws IOException {
        List<Change> changes = new ArrayList<>();
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
                int mode = walk.getRawMode(1);
                boolean unchanged = walk.getRawMode(0) == mode && walk.idEqual(0, 1);
                if (key != null && !DocumentPaths.belongs(key, path) || unchanged) {
                    continue;
                }
                if (FileMode.GITLINK.equals(mode)) {
                    LOGGER.debug("A release would write a gitlink, which git-core cannot carry");
                    return StoreResult.of(StoreOutcome.MALFORMED, GITLINK);
                }
                changes.add(new Change(path, mode == FileMode.MISSING.getBits() ? null : walk.getObjectId(1), mode));
            }
        }
        return StoreResult.ok(changes);
    }

    /** Reads the content of every put among the changes, keeping its mode; a delete stays a delete. */
    private static StoreResult<List<TreeEdit>> puts(Repository repository, StoreResult<List<Change>> changes)
            throws IOException {
        if (!changes.isOk()) {
            return new StoreResult<>(changes.outcome(), null, changes.detail());
        }
        List<TreeEdit> edits = new ArrayList<>();
        for (Change change : Objects.requireNonNull(changes.value())) {
            @Nullable ObjectId blob = change.blob();
            if (blob == null) {
                edits.add(new TreeEdit.Delete(change.path()));
            } else {
                byte[] content = ExplicitSave.readBlob(repository, blob);
                edits.add(new TreeEdit.Put(change.path(), content, mode(change.mode())));
            }
        }
        return StoreResult.ok(edits);
    }

    /**
     * The mode of the put of a source entry, so the release and the save keep an executable file and a symbolic link
     * as the source tree holds them; every other blob is a regular file.
     *
     * @param rawMode the raw mode of the tree entry
     * @return the mode of the put
     */
    static TreeEdit.Mode mode(int rawMode) {
        if (FileMode.EXECUTABLE_FILE.equals(rawMode)) {
            return TreeEdit.Mode.EXECUTABLE_FILE;
        }
        if (FileMode.SYMLINK.equals(rawMode)) {
            return TreeEdit.Mode.SYMLINK;
        }
        return TreeEdit.Mode.REGULAR_FILE;
    }

    /**
     * The commit of a merged release {@code v<n>} whose tag is missing: the first commit on the first-parent chain of
     * {@code main}, after the commit of {@code v<n-1>}, whose first paragraph is the store-composed subject of
     * {@code v<n>} and that has the shape of a release of the document ({@link #releaseShaped}), so a commit of an
     * ordinary save that merely uses the subject, with or without imitated trailers, is never taken for it. The
     * trailers after the first paragraph are never read.
     */
    private static @Nullable ObjectId mergedRelease(Repository repository, ObjectId main, @Nullable ObjectId previous,
            String subject, DocumentKey key, ObjectId source) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(main);
            while (commit != null && !commit.equals(previous)) {
                walk.parseBody(commit);
                if (subject.equals(firstParagraph(commit.getFullMessage()))
                        && releaseShaped(repository, commit, key, source)) {
                    return commit.copy();
                }
                commit = commit.getParentCount() == 0 ? null : walk.parseCommit(commit.getParent(0));
            }
            return null;
        }
    }

    /**
     * Whether the first paragraph of a message is the subject of a release of the document, {@code Release
     * <collection>/<id> v<digits>}. The save refuses such a message ({@link ExplicitSave}), so no save commit can be
     * taken for a release whose tag is missing.
     *
     * @param key     the document or entry
     * @param message the commit message
     * @return whether the message carries a release subject of the key
     */
    static boolean isReleaseSubject(DocumentKey key, String message) {
        String first = firstParagraph(message);
        String prefix = subjectPrefix(key);
        if (first.length() <= prefix.length() || !first.startsWith(prefix)) {
            return false;
        }
        for (int index = prefix.length(); index < first.length(); index++) {
            char c = first.charAt(index);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the first paragraph of a message has the form of the subject of a release of any document,
     * {@code Release <collection>/<id> v<digits>} with a collection name and an id of their alphabets. Every release
     * commit lands on {@code main}, so a save onto {@code main} refuses such a message whatever its own key
     * ({@link ExplicitSave}): no save commit on {@code main} can be taken for a release whose tag is missing.
     *
     * @param message the commit message
     * @return whether the message carries the subject of a release of some document
     */
    static boolean isAnyReleaseSubject(String message) {
        return ANY_RELEASE_SUBJECT.matcher(firstParagraph(message)).matches();
    }

    /** The text of a commit message before its first blank line, stripped. */
    private static String firstParagraph(String message) {
        int blank = message.indexOf("\n\n");
        return (blank < 0 ? message : message.substring(0, blank)).strip();
    }

    /**
     * Whether a commit has the shape a release of the document writes. For a per-document collection: two parents,
     * the second reachable from the working branch, or the one parent of a release onto an absent {@code main}, reachable
     * from the working branch and with the same tree. For a numbered collection: at most one parent, every changed path
     * belonging to the entry ({@link DocumentPaths#belongs}).
     */
    private static boolean releaseShaped(Repository repository, RevCommit commit, DocumentKey key, ObjectId source)
            throws IOException {
        int parents = commit.getParentCount();
        if (key.collection().kind() == DocumentCollection.Kind.NUMBERED) {
            return parents <= 1 && onlyEntryChanged(repository,
                    parents == 0 ? null : commitTree(repository, commit.getParent(0)), commit.getTree(), key);
        }
        if (parents == 2) {
            return reachable(repository, commit.getParent(1), source);
        }
        return parents == 1 && commitTree(repository, commit.getParent(0)).equals(commit.getTree())
                && reachable(repository, commit.getParent(0), source);
    }

    /**
     * Whether the working or collection branch moved on since a merged release whose tag is missing: for a
     * per-document collection, its tip is no longer the parent the release merged (or, onto an absent {@code main}, its
     * only parent); for a numbered collection, a path of the entry differs between the release commit and the tip.
     */
    private static boolean movedOn(Repository repository, ObjectId release, DocumentKey key, ObjectId source)
            throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(release);
            if (key.collection().kind() == DocumentCollection.Kind.NUMBERED) {
                return !onlyEntryUnchanged(repository, commit.getTree(), commitTree(repository, source), key);
            }
            RevCommit merged = commit.getParent(commit.getParentCount() == 2 ? 1 : 0);
            return !merged.equals(source);
        }
    }

    /** Whether no path of the entry differs between two trees. */
    private static boolean onlyEntryUnchanged(Repository repository, ObjectId one, ObjectId other, DocumentKey key)
            throws IOException {
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(one);
            walk.addTree(other);
            walk.setRecursive(true);
            walk.setFilter(TreeFilter.ANY_DIFF);
            while (walk.next()) {
                if (DocumentPaths.belongs(key, walk.getPathString())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Whether the commit is the tip or an ancestor of it. */
    private static boolean reachable(Repository repository, AnyObjectId commit, ObjectId tip) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.isMergedInto(walk.parseCommit(commit), walk.parseCommit(tip));
        }
    }

    /**
     * Whether every path that changed from one tree to the other belongs to the entry. A release of an unchanged entry
     * changes no path; a save, which writes no commit without changes, always changes one.
     */
    private static boolean onlyEntryChanged(Repository repository, @Nullable ObjectId from, ObjectId to,
            DocumentKey key) throws IOException {
        try (TreeWalk walk = new TreeWalk(repository)) {
            if (from == null) {
                walk.addTree(new EmptyTreeIterator());
            } else {
                walk.addTree(from);
            }
            walk.addTree(to);
            walk.setRecursive(true);
            walk.setFilter(TreeFilter.ANY_DIFF);
            while (walk.next()) {
                if (!DocumentPaths.belongs(key, walk.getPathString())) {
                    return false;
                }
            }
        }
        return true;
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
        return subjectPrefix(key) + version;
    }

    /** The subject of a release commit of the document before its version: {@code Release <collection>/<id> v}. */
    private static String subjectPrefix(DocumentKey key) {
        return "Release " + key.collection().name() + "/" + key.id() + " v";
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
     * Deletes the local tag of a release whose push of {@code main} failed, expecting it at the release commit,
     * without a reflog. A failed delete changes nothing the remote holds; the next fetch prunes the tag the remote
     * lacks.
     */
    private static void deleteTag(Repository repository, String tag, ObjectId commit) {
        try {
            RefUpdate update = repository.updateRef(tag);
            update.setExpectedOldObjectId(commit);
            update.setForceUpdate(true);
            update.disableRefLog();
            RefUpdate.Result result = update.delete();
            if (result != RefUpdate.Result.FORCED) {
                LOGGER.debug("The local release tag %s was not deleted: %s", tag, result);
            }
        } catch (IOException e) {
            LOGGER.debug("The local release tag %s was not deleted: %s", tag, e.getClass().getName());
        }
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
            LOGGER.debug("The local main was not moved after the accepted release: %s", e.getClass().getName());
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
            LOGGER.warn(GitStoreLogMessages.WARN.RELEASE_NOT_COMPLETED, clone.alias(), source,
                    StoreResult.name(result.outcome()));
        }
        return result;
    }

    private static <V> StoreResult<V> passedOn(GitOutcome outcome, String detail) {
        return new StoreResult<>(new StoreResult.PassedOn(outcome), null, detail);
    }

    /**
     * The input of a release.
     *
     * @param author  the author of the release commit
     * @param claims  the claims of the author's authentication, written as identity trailers
     * @param key     the document or entry
     * @param source  the working or collection branch
     * @param updates the references the release moved, in the clone by its fetches and on the remote by its pushes,
     *                collected on the releasing thread and emitted after the locks were released
     */
    private record Request(Author author, IdentityClaims claims, DocumentKey key, String source,
    List<StoreEvents.Update> updates) {
    }

    /**
     * What one attempt read after its fetch.
     *
     * @param main     the fetched tip of {@code main}; {@code null} when {@code main} is absent
     * @param source   the tip of the working or collection branch
     * @param previous the commit of the previous release; {@code null} for the first release
     * @param version  the version of this release
     * @param tag      the full name of its tag
     * @param message  the store-composed subject of the release commit
     */
    private record Attempt(@Nullable ObjectId main, ObjectId source, @Nullable ObjectId previous, int version,
    String tag, String message) {
    }

    /**
     * One path a release changes.
     *
     * @param path the repository-relative path
     * @param blob the blob the path is set to; {@code null} for a delete
     * @param mode the raw mode of the path in the source tree
     */
    private record Change(String path, @Nullable ObjectId blob, int mode) {
    }

    /**
     * The tree entry of a path.
     *
     * @param mode the raw mode
     * @param id   the object id
     */
    private record Entry(int mode, ObjectId id) {
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
