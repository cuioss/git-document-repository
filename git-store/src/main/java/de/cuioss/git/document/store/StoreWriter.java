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

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.TreeEdit;

/**
 * The write path of one repository of the store: drafts per user and document (GDR-STORE-2) and the explicit save
 * with lease, conflicts and change detection and an optional per-attempt step (GDR-STORE-3, GDR-STORE-4,
 * GDR-STORE-5), the release of a document onto {@code main} with its version tag (GDR-STORE-6), the expiry of
 * drafts (GDR-STORE-7), and the fetch of the remote, on demand or on the caller's schedule, that keeps the clone and
 * every {@link StoreIndex} over the writer current (GDR-STORE-9), and the subscription of in-process
 * {@link CommitListener}s to the new commits of a branch (GDR-STORE-10).
 * <p>
 * {@link #open} clones the remote of the repository into a bare clone if none exists yet, with the push reference
 * specifications {@link StoreReferences#PUSH_REF_SPECS} (never {@code refs/drafts/*}, ADR-0003) and the credentials of
 * the technical user, and opens the clone on a {@link RefusingFS}, so no operation on the clone starts a process
 * (GDR-CORE-1). That covers the clone only: JGit 7.8.0 reads the user and system configuration through the process-wide
 * {@code SystemReader}, whose default runs {@code git} once per JVM to locate the system configuration, so an embedding
 * application that needs the guarantee for its whole JVM installs
 * {@link RefusingFS#systemReader(org.eclipse.jgit.util.SystemReader)} before any JGit use. An
 * existing clone is reused only when every fetch and push destination of its {@code origin} is the registered remote;
 * a clone of another repository at the path is refused. Every commit identity comes from the section {@code commit}
 * of the configuration and the technical user.
 * <p>
 * Every save commit and every release commit is signed in the SSH signature format through the active
 * {@link SigningKeySource} under the key reference {@code signing.key} (GDR-STORE-12), and carries the identity
 * trailers {@code Actor}, {@code Actor-Issuer}, {@code Auth-Time} and {@code Auth-Context} built from the author's
 * pseudonym and the {@link IdentityClaims} of the call (GDR-STORE-13). The public key is fetched at most once per save
 * or release, by the first attempt that writes a commit. A source that refuses or cannot be reached ends the save or release with
 * {@link StoreOutcome#REFUSED} or {@link StoreOutcome#UNAVAILABLE}, nothing pushed. Draft commits stay unsigned and
 * carry no trailers.
 * <p>
 * Every expected non-success is a {@link StoreResult} or a {@link SaveResult}; a Git outcome is passed on. Draft writes,
 * saves and releases take the shared side of the clone's maintenance lock, every save the lock of its draft, so the
 * saves of one draft run one after the other, and every save or release attempt and every fetch the transport lock;
 * the moved references of a save, a release or a fetch reach the index and then the listeners only after every lock
 * was released; the expiry, its
 * deletes and its garbage
 * collection, takes the exclusive side. The locks belong to the clone, not to the
 * writer: every writer of the JVM that opens the same git directory (by its real path) shares them, so the garbage
 * collection of one writer never runs while another writer of the clone holds objects not yet referenced. No
 * operation takes a credential: every fetch and push uses those of the technical user (GDR-STORE-8). The writer holds
 * the opened clone and releases it on {@link #close()}.
 * <p>
 * Opened with the {@link StoreProviders} of the start, the writer anchors through the active
 * {@link AnchorProvider} (GDR-STORE-15 to GDR-STORE-17): {@link #anchor()} runs a due check on demand, and
 * {@link #anchorPeriodically} runs one every {@code anchor.retry-interval} and after every save, release and fetch on
 * the caller's executor. A due check pushes checkpoint tags {@code refs/tags/checkpoints/<n>} and notes under
 * {@code refs/notes/anchors/<provider>} in their own pushes, separately from save commits, under the transport lock;
 * the checkpoint and notes commits are signed like save commits and carry no identity trailers. A failing or
 * unreachable provider never blocks or fails a save, a release or a fetch.
 *
 * @since 0.1
 */
public final class StoreWriter implements AutoCloseable {

    /** The detail of a clone whose remote is not the registered one; it names no URL, credential or path. */
    static final String FOREIGN_CLONE = "clone: remote does not match the registry";

    /** The detail of a configuration without the key reference of the commit signature. */
    static final String SIGNING_KEY_MISSING = "signing.key is missing";

    /** The detail of declared collections that hold one name twice; it names the argument, never the name. */
    static final String COLLECTIONS_DECLARED_TWICE = "collections: a name is declared twice";

    /** The opened clone and the parts that work on it; every operation delegates to it. */
    private final StoreClone clone;

    private StoreWriter(StoreClone clone) {
        this.clone = clone;
    }

    /**
     * Opens the write path of a repository, cloning its remote first if the bare clone does not exist yet.
     *
     * @param storeRepository the repository of the registry
     * @param gitDir          the git directory of the bare clone
     * @param user            the technical user: committer and holder of the transport credentials
     * @param configuration   the configuration of the store; {@code signing.key} must be set
     * @param collections     the declared collections of the repository, in declaration order; no name twice
     *                        (GDR-STORE-2)
     * @param signing         the active signing key source ({@link StoreProviders#signing()}), which signs every save
     *                        and release commit under {@code signing.key}
     * @param operations      the repository operations of {@code git-core}
     * @param clock           the clock of every commit time
     * @return {@link StoreOutcome#OK} with the writer; {@link StoreOutcome#MALFORMED} with the detail
     *         {@code collections: a name is declared twice} for collections that declare one name twice, checked with
     *         {@link StoreReferences#collections(List)}, or with the detail {@code signing.key is missing} for a
     *         configuration without the key reference, both checked before anything is cloned; or the passed-on Git
     *         outcome of the clone or of opening it, for example
     *         {@link GitOutcome#UNREACHABLE}, {@link GitOutcome#CREDENTIALS_INVALID},
     *         {@link GitOutcome#REFUSED_BY_POLICY}, {@link GitOutcome#FAILED}, or {@link GitOutcome#NOT_A_REPOSITORY}
     *         with the detail {@code <alias>: clone: remote does not match the registry} for an existing clone whose
     *         remote is not the registered one; no detail names a URL, a credential, a key reference, a collection or
     *         a path
     * @throws NullPointerException if an argument or a collection is {@code null}
     */
    // operator-selected: AaIOFP3oAtdQWIZKzKJs: public API decided by the operator (collections argument)
    @SuppressWarnings("java:S107")
    public static StoreResult<StoreWriter> open(StoreRepository storeRepository, Path gitDir, TechnicalUser user,
            StoreConfiguration configuration, List<DocumentCollection> collections, SigningKeySource signing,
            RepositoryOperations operations, Clock clock) {
        return writer(CloneOpening.open(new CloneOpening.Arguments(storeRepository, gitDir, user, configuration,
                collections, operations, clock), signing));
    }

    /**
     * Opens the write path of a repository like
     * {@link #open(StoreRepository, Path, TechnicalUser, StoreConfiguration, List, SigningKeySource, RepositoryOperations, Clock)},
     * with the providers selected at start: the signing key source signs every save and release commit, and the anchor
     * provider, when one is active, anchors the release commits and checkpoints of the repository (GDR-STORE-15).
     *
     * @param storeRepository the repository of the registry
     * @param gitDir          the git directory of the bare clone
     * @param user            the technical user: committer and holder of the transport credentials
     * @param configuration   the configuration of the store; {@code signing.key} must be set
     * @param collections     the declared collections of the repository, in declaration order; no name twice
     *                        (GDR-STORE-2)
     * @param providers       the selected providers ({@link StoreProviders#load} or {@link StoreProviders#select})
     * @param operations      the repository operations of {@code git-core}
     * @param clock           the clock of every commit time and of the due checks of the anchoring
     * @return the results of
     *         {@link #open(StoreRepository, Path, TechnicalUser, StoreConfiguration, List, SigningKeySource, RepositoryOperations, Clock)}
     * @throws NullPointerException if an argument or a collection is {@code null}
     */
    // operator-selected: AaIOFP3oAtdQWIZKzKJt: public API decided by the operator (collections argument)
    @SuppressWarnings("java:S107")
    public static StoreResult<StoreWriter> open(StoreRepository storeRepository, Path gitDir, TechnicalUser user,
            StoreConfiguration configuration, List<DocumentCollection> collections, StoreProviders providers,
            RepositoryOperations operations, Clock clock) {
        return writer(CloneOpening.open(new CloneOpening.Arguments(storeRepository, gitDir, user, configuration,
                collections, operations, clock), providers));
    }

    /** The writer over an opened clone, or the non-success of opening it. */
    private static StoreResult<StoreWriter> writer(StoreResult<StoreClone> opened) {
        if (!opened.isOk()) {
            return new StoreResult<>(opened.outcome(), null, opened.detail());
        }
        return StoreResult.ok(new StoreWriter(Objects.requireNonNull(opened.value())));
    }

    /**
     * Returns the alias of the repository this writer writes to.
     *
     * @return the alias
     */
    public String alias() {
        return clone.alias();
    }

    /**
     * Writes an intermediate save: the next commit of {@code refs/drafts/<pseudonym>/<collection>/<id>}, created at
     * the tip of the local target branch (else {@code main}) when the user has no draft of the document yet
     * (GDR-STORE-2). The draft stays in the clone and is never pushed; its commit is unsigned and carries no trailers.
     *
     * @param author  the author, whose pseudonym names the draft reference
     * @param key     the document or entry
     * @param edits   the edits, applied onto the draft's current tree
     * @param message the commit message
     * @return {@link StoreOutcome#OK} with the full id of the draft commit, {@link StoreOutcome#MALFORMED} for a
     *         pseudonym outside {@code [A-Za-z0-9-]+}, or the passed-on Git outcome that prevented the write
     * @throws NullPointerException if an argument is {@code null}
     */
    public StoreResult<String> draft(Author author, DocumentKey key, List<TreeEdit> edits, String message) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(edits, "edits");
        Objects.requireNonNull(message, "message");
        return clone.writes().draft(author, key, edits, message);
    }

    /**
     * Lists the drafts of a user, sorted by reference name (GDR-STORE-2).
     *
     * @param pseudonym the pseudonym of the user
     * @return {@link StoreOutcome#OK} with the drafts, possibly none; {@link StoreOutcome#MALFORMED} for a pseudonym
     *         outside {@code [A-Za-z0-9-]+}; or the passed-on {@link de.cuioss.git.document.core.GitOutcome#FAILED}
     * @throws NullPointerException if {@code pseudonym} is {@code null}
     */
    public StoreResult<List<Draft>> drafts(String pseudonym) {
        Objects.requireNonNull(pseudonym, "pseudonym");
        return clone.writes().drafts(pseudonym);
    }

    /**
     * Saves the draft of the author for the document onto its target branch (GDR-STORE-3): the draft's changes as one
     * commit on the remote tip, pushed with lease by the technical user, re-applied onto a new tip up to
     * {@code save.max-attempts}; then the local target branch moves and the draft is deleted by compare-and-delete.
     * Every changed path with an expected blob id that differs on the fetched tip is a conflict (GDR-STORE-4,
     * GDR-STORE-5); a changed path without one overwrites a concurrent change.
     * <p>
     * The save commit is signed through the active signing key source (GDR-STORE-12) and its message is the given
     * message followed by the identity trailers of the author's pseudonym and the claims (GDR-STORE-13); a blank
     * message is replaced by the subject {@code Save <collection>/<id>}, and a message whose first paragraph is the
     * subject of a release of the document ({@code Release <collection>/<id> v<n>}) is
     * {@link StoreOutcome#MALFORMED}. The public key is fetched once, by the first attempt that writes a commit, so a
     * save of a draft without changes never asks the source. A source that refuses or cannot be reached, and a
     * signature that fails, end the save with no save commit written or pushed; the fetch that attempt ran before may
     * still have updated the clone, a moved remote target included, and those references are emitted to the index and
     * the listeners as after any fetch.
     *
     * @param author          the author, whose pseudonym names the draft reference, the commit's author and the
     *                        trailer {@code Actor}
     * @param claims          the claims of the author's authentication, written as identity trailers
     * @param key             the document or entry
     * @param expectedBlobIds the full blob id the caller loaded per repository-relative path; the zero id expects the
     *                        path to be absent; empty for no check
     * @param message         the commit message, before the trailers
     * @return {@link SaveResult.Saved} with the commit the target branch points at, {@link SaveResult.Conflicting}
     *         with every conflicting path, or {@link SaveResult.NotSaved} with the outcome that prevented the save, for
     *         example {@link StoreOutcome#MALFORMED} for an invalid pseudonym, an expected blob id that is no full
     *         object id (detail {@code expectedBlobIds: not a full object id}, never the value) or a release subject
     *         as the message,
     *         {@link StoreOutcome#REFUSED} for a
     *         signing key source that refuses or a key of another type than Ed25519,
     *         {@link StoreOutcome#UNAVAILABLE} for a signing key source that cannot be reached,
     *         {@link de.cuioss.git.document.core.GitOutcome#NOT_FOUND} for a missing draft,
     *         {@link de.cuioss.git.document.core.GitOutcome#REJECTED} after the last attempt lost its lease or at once
     *         for a permanent rejection of the push, for example by a protected branch or a hook,
     *         {@link de.cuioss.git.document.core.GitOutcome#UNREACHABLE} or
     *         {@link de.cuioss.git.document.core.GitOutcome#CREDENTIALS_INVALID}
     * @throws NullPointerException if an argument, a path or an expected blob id is {@code null}
     */
    public SaveResult save(Author author, IdentityClaims claims, DocumentKey key, Map<String, String> expectedBlobIds,
            String message) {
        return clone.writes().save(author, claims, key, expectedBlobIds, message, null);
    }

    /**
     * Saves the draft of the author for the document onto its target branch like
     * {@link #save(Author, IdentityClaims, DocumentKey, Map, String)}, with a per-attempt step (GDR-STORE-3); the
     * commit that carries the step's edits is signed and carries the identity trailers. The step runs on every
     * attempt, the first and each re-application onto a new tip, after the conflict check; it sees the tip and the tree
     * the draft's changes produce on it through a read-only {@link SaveStep.Attempt}. Its {@link SaveStep.Edits} are
     * written into the same single save commit, whose only parent stays the tip, an edit on a path the draft also
     * changed replacing the draft's edit. A draft without changes writes no commit and calls no step. The step runs
     * under the locks of the save, so every other operation of the clone waits for it: it is expected to return within
     * milliseconds, and one that calls {@link #expireDrafts} gets {@link StoreOutcome#REFUSED} instead of a deadlock.
     *
     * @param author          the author, whose pseudonym names the draft reference, the commit's author and the
     *                        trailer {@code Actor}
     * @param claims          the claims of the author's authentication, written as identity trailers
     * @param key             the document or entry
     * @param expectedBlobIds the full blob id the caller loaded per repository-relative path; the zero id expects the
     *                        path to be absent; empty for no check
     * @param message         the commit message, before the trailers
     * @param step            the per-attempt step
     * @return the results of {@link #save(Author, IdentityClaims, DocumentKey, Map, String)}, and
     *         {@link SaveResult.NotSaved} with
     *         {@link StoreOutcome#REFUSED} and the detail {@code <target>: step: refused} for a {@link SaveStep.Refusal},
     *         or with the passed-on {@link de.cuioss.git.document.core.GitOutcome#FAILED} and the detail
     *         {@code <target>: step: <class name of the throwable>} ({@link Class#getName()}) for a step that threw a
     *         {@link RuntimeException} or an {@link Error} other than a {@link VirtualMachineError}, without a further
     *         attempt; in both cases nothing is pushed, the draft stays unchanged and so does the target branch on the
     *         remote, while the fetch that attempt ran before the step may still have moved the local mirror of the
     *         target in the clone, emitted to the index and the listeners as after any fetch
     * @throws NullPointerException if an argument, a path or an expected blob id is {@code null}
     */
    public SaveResult save(Author author, IdentityClaims claims, DocumentKey key, Map<String, String> expectedBlobIds,
            String message, SaveStep step) {
        return clone.writes().save(author, claims, key, expectedBlobIds, message,
                Objects.requireNonNull(step, "step"));
    }

    /**
     * Releases a document or numbered entry (GDR-STORE-6): merges its saved state into {@code main} as one release
     * commit, tags that commit {@code refs/tags/<collection>/<id>/v<n>}, {@code n} counting the releases of the
     * document from 1, and pushes {@code main} with lease and then the tag. For a per-document collection the release
     * commit merges the working branch into {@code main}, with both tips as its parents; for a numbered collection it
     * takes only the entry's paths from the collection branch onto {@code main}, its only parent. Its message is
     * {@code Release <collection>/<id> v<n>}, composed by the store, followed by the identity trailers of the user who
     * releases (GDR-STORE-13); its author is that user, and it is signed through the active signing key source
     * (GDR-STORE-12), whose public key is fetched once, by the first attempt that writes a release commit; a release
     * that only pushes the missing tag of a merged release never asks the source.
     * <p>
     * The release runs under the shared side of the clone's maintenance lock, each attempt, from the fetch to the push
     * of the tag, under its transport lock. When {@code main} moved on the remote and the tag is missing there, a
     * repeated release creates and pushes the missing tag on the release commit instead of merging again, and reports
     * in {@link Release#branchMovedOn()} whether the working or collection branch moved on since that commit. A tag
     * push the remote refuses is checked by a fetch of that tag: when the remote holds it at the released commit
     * already, the release is complete. A release tag on the remote is never moved or deleted; a local tag whose push of
     * {@code main} failed for any reason but a lost lease is deleted again. A numbered release copies the entry's paths
     * onto {@code main} only when {@code main} did not change them since the previous release.
     *
     * @param author the user who releases, author of the release commit and its trailer {@code Actor}
     * @param claims the claims of that user's authentication, written as identity trailers
     * @param key    the document or numbered entry
     * @return {@link StoreOutcome#OK} with the {@link Release}; {@link StoreOutcome#INCOMPLETE} with the detail
     *         {@code <tag>: not pushed (<outcome>)} when {@code main} moved and the tag push failed or the remote holds
     *         the tag at another commit; {@link StoreOutcome#MALFORMED} for content without release, a pseudonym
     *         outside {@code [A-Za-z0-9-]+} or a gitlink among the paths the release would write (detail
     *         {@code entry: a gitlink path cannot be released}); {@link StoreOutcome#REFUSED} or
     *         {@link StoreOutcome#UNAVAILABLE} for a signing key source that refuses or cannot be reached, or
     *         {@link StoreOutcome#REFUSED} for a key of another type than Ed25519, nothing pushed; the passed-on
     *         {@link de.cuioss.git.document.core.GitOutcome#NOT_FOUND} for a missing working or collection branch,
     *         {@link de.cuioss.git.document.core.GitOutcome#CONFLICT} with the number of conflicting paths for a
     *         conflicting merge or with the number of entry paths {@code main} changed since the previous release,
     *         {@link de.cuioss.git.document.core.GitOutcome#REJECTED} for a refused push of
     *         {@code main} or after every attempt lost its lease, or the passed-on outcome that ended the release, for
     *         example {@link de.cuioss.git.document.core.GitOutcome#UNREACHABLE}; no detail names a path, a content or
     *         a pseudonym
     * @throws NullPointerException if an argument is {@code null}
     */
    public StoreResult<Release> release(Author author, IdentityClaims claims, DocumentKey key) {
        return clone.writes().release(author, claims, key);
    }

    /**
     * Deletes every draft unchanged for {@code drafts.expiry} and removes the objects reachable only from deleted
     * references (GDR-STORE-7): a draft whose tip has a committer time at or before {@code now} minus
     * {@code drafts.expiry} (UTC) is deleted by compare-and-delete expecting that tip, so a draft changed meanwhile is
     * kept; then a garbage collection runs. Both run under the exclusive side of the clone's maintenance lock, so no
     * draft write or save of this writer runs meanwhile.
     * <p>
     * The exclusive side cannot be taken by a thread that holds the shared side: a call from a {@link SaveStep} or
     * another callback that runs under the locks of an operation of the clone is refused instead of waiting for
     * itself.
     *
     * @param now the current time
     * @return {@link StoreOutcome#OK} with the full names of the deleted draft references, sorted, possibly none;
     *         {@link StoreOutcome#REFUSED} with the detail
     *         {@code maintenance: re-entered from a callback that holds the shared lock} for a call from a thread that
     *         holds the shared side of the maintenance lock; or the passed-on
     *         {@link de.cuioss.git.document.core.GitOutcome#CONFLICT} when another garbage collection holds
     *         {@code gc.pid}, or {@link de.cuioss.git.document.core.GitOutcome#FAILED} of a failed read, delete or
     *         garbage collection
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public StoreResult<List<String>> expireDrafts(Instant now) {
        return clone.writes().expireDrafts(now);
    }

    /**
     * Fetches the remote into the clone (GDR-STORE-9): every branch, tag and note of the remote mirrored, with the
     * credentials of the technical user, and every local branch, tag and note the remote deleted pruned; drafts and
     * applied-draft references are never pruned. The fetch runs under the shared side of the clone's maintenance lock
     * and under its transport lock; after both were released, the moved references are handed to every
     * {@link StoreIndex} opened over this writer, which refreshes. A fetch that moved no reference still lets an index
     * whose last refresh failed retry it; the listeners are called only for moved references.
     *
     * @return {@link StoreOutcome#OK} with the full names of the references the fetch moved, created or deleted, sorted,
     *         possibly none; or the passed-on Git outcome of the fetch, for example
     *         {@link de.cuioss.git.document.core.GitOutcome#UNREACHABLE} or
     *         {@link de.cuioss.git.document.core.GitOutcome#CREDENTIALS_INVALID}
     */
    public StoreResult<List<String>> fetch() {
        return clone.schedules().fetch();
    }

    /**
     * Schedules {@link #fetch()} on the caller's executor at {@code fetch.interval} of the configuration, the first
     * run one interval from now and every next one an interval after the previous one ended (GDR-STORE-9). The writer
     * starts no thread of its own. A failed fetch is logged ({@code GIT_STORE-107}) with the alias and the outcome, or
     * the class of an exception, and never ends the schedule; the schedule ends when the caller cancels the returned
     * {@link FetchSchedule} or shuts the executor down, which the caller does before it closes the writer.
     *
     * @param executor the executor of the caller
     * @return the schedule, to cancel it
     * @throws NullPointerException                                if {@code executor} is {@code null}
     * @throws java.util.concurrent.RejectedExecutionException if the executor refuses the schedule
     */
    public FetchSchedule fetchPeriodically(ScheduledExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return clone.schedules().fetchPeriodically(executor);
    }

    /**
     * Runs one due check of the anchoring on the calling thread (GDR-STORE-15, GDR-STORE-16, GDR-STORE-17). Without an
     * active anchor provider nothing is anchored. Otherwise the due check fetches, pushes the checkpoint tag and the
     * notes a former due check kept because their push failed, creates a checkpoint when
     * {@code anchor.checkpoint-interval} has passed since the previous one and a reference it lists changed, and asks
     * the provider for a proof of every pending subject, the commit of each release tag (unless
     * {@code anchor.releases} is {@code false}) and each checkpoint without a note in
     * {@code refs/notes/anchors/<provider>}, oldest first; each proof is written as a base64 note and pushed with lease
     * right after. A failure of the provider or of a push leaves the subject pending and is logged once
     * ({@code GIT_STORE-111}); an empty proof counts as a refusal of the provider. The references the fetch of the due
     * check moved reach the indexes and the listeners also when the due check ends early.
     *
     * @return {@link StoreOutcome#OK} with the anchored and the pending subjects; or the non-success that ended the
     *         due check before any subject was asked for, for example the passed-on outcome of its fetch,
     *         {@link StoreOutcome#INCOMPLETE} with the detail {@code anchor: <class name of the exception>} for an
     *         unchecked failure while it fetched, repaired or planned, or {@link StoreOutcome#REFUSED} with the detail
     *         {@code anchor: the writer is closed} after {@link #close()}
     */
    public StoreResult<Anchoring> anchor() {
        return clone.schedules().anchor();
    }

    /**
     * Schedules the due check of {@link #anchor()} on the caller's executor at {@code anchor.retry-interval}, the
     * first run one interval from now, and registers the executor for the due checks that follow every save, release
     * and fetch of this writer: those are handed to the executor once the operation released every lock, coalesced
     * while one is waiting, so a slow or failing provider never delays or fails the operation. The writer starts no
     * thread of its own; a failed due check is logged and never ends the schedule. The caller cancels the returned
     * {@link AnchorSchedule} or shuts the executor down before it closes the writer.
     *
     * @param executor the executor of the caller
     * @return the schedule, to cancel it
     * @throws NullPointerException                                if {@code executor} is {@code null}
     * @throws java.util.concurrent.RejectedExecutionException if the executor refuses the schedule
     */
    public AnchorSchedule anchorPeriodically(ScheduledExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        return clone.schedules().anchorPeriodically(executor);
    }

    /**
     * The result of a due check of the anchoring.
     *
     * @param anchored the full ids of the commits whose note this due check pushed, in order
     * @param pending  the full ids of the commits left without a pushed proof, oldest first, retried by the next due
     *                 check
     * @since 0.1
     */
    public record Anchoring(List<String> anchored, List<String> pending) {

        /**
         * Copies the lists.
         *
         * @param anchored the anchored commits
         * @param pending  the pending commits
         * @throws NullPointerException if a list or an element is {@code null}
         */
        public Anchoring {
            anchored = List.copyOf(anchored);
            pending = List.copyOf(pending);
        }
    }

    /**
     * Subscribes an in-process listener to the new commits of a branch (GDR-STORE-10). The listener is called with each
     * new commit of the branch, oldest first, after every save or release this writer pushed onto it and after every
     * fetch of this writer that moved it, on the thread of that operation, once it released every lock of the store
     * and the indexes over this writer were refreshed. The subscription starts at the tip the branch has in the clone
     * now, or at the tip of every branch of the clone when the branch does not exist yet; every commit reachable from
     * the branch and not from that start is delivered once, whatever order the events of concurrent operations arrive
     * in. The subscription is registered before its start is read, so a commit an operation moved the branch to while
     * the listener subscribes is never missed. What the subscription delivered is kept in memory only, so nothing older
     * than the subscription is delivered, also not by a new writer over the same clone. A listener that throws a
     * {@link RuntimeException} or an {@link Error} other than a {@link VirtualMachineError} is logged
     * ({@code GIT_STORE-109}) with the alias, the branch and the class name of the throwable, never fails the save,
     * release or fetch and never keeps the next listener from its call.
     *
     * @param branch   the branch, by its full name ({@code refs/heads/main}) or its name below {@code refs/heads/}
     * @param listener the listener
     * @return the subscription; closing it unsubscribes the listener
     * @throws NullPointerException if an argument is {@code null}
     */
    public CommitListener.Subscription subscribe(String branch, CommitListener listener) {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(listener, "listener");
        String full = branch.startsWith(StoreReferences.HEADS) ? branch : StoreReferences.HEADS + branch;
        return clone.subscribe(full, listener);
    }

    /**
     * Returns the opened clone with its repository and its notification seam, for the index over this writer. The
     * accessor is not named {@code clone()}, which would override {@link Object#clone()} with weaker access.
     *
     * @return the clone
     */
    StoreClone storeClone() {
        return clone;
    }

    /**
     * Releases the opened clone: sets its closed flag under the anchoring lock, so a due check handed to the caller's
     * executor and run after the close does nothing, and one running waits until it ended. A second call releases
     * nothing.
     */
    @Override
    public void close() {
        clone.close();
    }

    /**
     * Renders the alias only.
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "StoreWriter[alias=" + alias() + "]";
    }
}
