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
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

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
 *
 * @since 0.1
 */
public final class StoreWriter implements AutoCloseable {

    /** The detail of a clone whose remote is not the registered one; it names no URL, credential or path. */
    static final String FOREIGN_CLONE = "clone: remote does not match the registry";

    private static final CuiLogger LOGGER = new CuiLogger(StoreWriter.class);

    /** The remote of every bare clone of the store. */
    private static final String ORIGIN = "origin";

    /** The prefix of every branch. */
    private static final String HEADS = "refs/heads/";

    private final ExplicitSave.Clone clone;
    private final RepositoryOperations operations;
    private final TechnicalUser user;
    private final Duration fetchInterval;
    private final StoreEvents events;
    private final ListenerDispatch listeners;
    private final DraftReferences drafts;
    private final ExplicitSave saves;
    private final DocumentRelease releases;
    private final DraftExpiry expiry;

    private StoreWriter(Transport transport, Notification notification, DraftReferences drafts, ExplicitSave saves,
            DocumentRelease releases, DraftExpiry expiry) {
        this.clone = transport.bareClone();
        this.operations = transport.operations();
        this.user = transport.user();
        this.fetchInterval = transport.fetchInterval();
        this.events = notification.events();
        this.listeners = notification.listeners();
        this.drafts = drafts;
        this.saves = saves;
        this.releases = releases;
        this.expiry = expiry;
    }

    /**
     * What the writer fetches with.
     *
     * @param bareClone     the bare clone
     * @param operations    the repository operations of {@code git-core}
     * @param user          the technical user, holder of the credentials of every fetch
     * @param fetchInterval the interval of the periodic fetch ({@code fetch.interval})
     */
    private record Transport(ExplicitSave.Clone bareClone, RepositoryOperations operations, TechnicalUser user,
    Duration fetchInterval) {
    }

    /**
     * What the writer notifies.
     *
     * @param events    the notification seam of the writer
     * @param listeners the listener dispatch, the last observer of the events
     */
    private record Notification(StoreEvents events, ListenerDispatch listeners) {
    }

    /**
     * Opens the write path of a repository, cloning its remote first if the bare clone does not exist yet.
     *
     * @param storeRepository the repository of the registry
     * @param gitDir          the git directory of the bare clone
     * @param user            the technical user: committer and holder of the transport credentials
     * @param configuration   the configuration of the store
     * @param operations      the repository operations of {@code git-core}
     * @param clock           the clock of every commit time
     * @return {@link StoreOutcome#OK} with the writer, or the passed-on Git outcome of the clone or of opening it, for
     *         example {@link GitOutcome#UNREACHABLE}, {@link GitOutcome#CREDENTIALS_INVALID},
     *         {@link GitOutcome#REFUSED_BY_POLICY}, {@link GitOutcome#FAILED}, or {@link GitOutcome#NOT_A_REPOSITORY}
     *         with the detail {@code <alias>: clone: remote does not match the registry} for an existing clone whose
     *         remote is not the registered one; no detail names a URL, a credential or a path
     * @throws NullPointerException if an argument is {@code null}
     */
    public static StoreResult<StoreWriter> open(StoreRepository storeRepository, Path gitDir, TechnicalUser user,
            StoreConfiguration configuration, RepositoryOperations operations, Clock clock) {
        Objects.requireNonNull(storeRepository, "storeRepository");
        Objects.requireNonNull(gitDir, "gitDir");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(clock, "clock");
        var cloned = operations.cloneIfMissing(RepositoryOperations.CloneInput.builder()
                .gitDir(gitDir)
                .url(storeRepository.remote())
                .pushRefSpecs(StoreReferences.PUSH_REF_SPECS)
                .credentials(user.credentials())
                .build());
        if (!cloned.isOk()) {
            return StoreResult.passOn(cloned);
        }
        Path clone = Objects.requireNonNull(cloned.value()).gitDir();
        Repository repository;
        try {
            repository = new FileRepositoryBuilder().setGitDir(clone.toFile()).setFS(new RefusingFS())
                    .setMustExist(true).build();
        } catch (IOException e) {
            return StoreResult.passOn(DraftReferences.<StoreWriter>failure(storeRepository.alias(), e));
        }
        if (!transportsTo(repository, storeRepository.remote())) {
            repository.close();
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.NOT_A_REPOSITORY), null,
                    storeRepository.alias() + ": " + FOREIGN_CLONE);
        }
        var identities = new CommitIdentities(configuration.commit(), user);
        var updater = new ReferenceUpdater(new InCoreWriter());
        int attempts = configuration.save().maxAttempts();
        var drafts = new DraftReferences(repository, updater, identities, clock, attempts);
        var guard = RepositoryGuard.of(clone);
        var bareClone = new ExplicitSave.Clone(storeRepository.alias(), repository, clone, guard);
        var listeners = new ListenerDispatch(storeRepository.alias(), repository);
        var events = new StoreEvents(listeners);
        var saves = new ExplicitSave(bareClone, operations, user, identities, clock, attempts, events);
        var releases = new DocumentRelease(bareClone, operations, user, identities, clock, attempts, events);
        var expiry = new DraftExpiry(storeRepository.alias(), repository, guard, updater,
                configuration.drafts().expiry(), attempts);
        var transport = new Transport(bareClone, operations, user, configuration.fetch().interval());
        return StoreResult.ok(new StoreWriter(transport, new Notification(events, listeners), drafts, saves, releases,
                expiry));
    }

    /**
     * Tells whether every fetch and push of the clone's {@code origin} goes to the registered remote: exactly one
     * fetch URL, and every push URL (the fetch URL when none is configured), equal to it. JGit 7.8.0
     * {@code RemoteConfig} applies {@code url.<base>.insteadOf} to the fetch URLs and {@code pushInsteadOf} to the
     * push URLs it derives, so the comparison sees the destinations a transport would contact.
     */
    private static boolean transportsTo(Repository repository, String remote) {
        try {
            var expected = new URIish(remote);
            var origin = new RemoteConfig(repository.getConfig(), Constants.DEFAULT_REMOTE_NAME);
            List<URIish> fetch = origin.getURIs();
            List<URIish> push = origin.getPushURIs().isEmpty() ? fetch : origin.getPushURIs();
            return fetch.size() == 1 && expected.equals(fetch.getFirst()) && push.stream().allMatch(expected::equals);
        } catch (URISyntaxException e) {
            return false;
        }
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
     * (GDR-STORE-2). The draft stays in the clone and is never pushed.
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
        return clone.guard().shared(() -> drafts.write(author, key, edits, message));
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
        return clone.guard().shared(() -> drafts.list(pseudonym));
    }

    /**
     * Saves the draft of the author for the document onto its target branch (GDR-STORE-3): the draft's changes as one
     * commit on the remote tip, pushed with lease by the technical user, re-applied onto a new tip up to
     * {@code save.max-attempts}; then the local target branch moves and the draft is deleted by compare-and-delete.
     * Every changed path with an expected blob id that differs on the fetched tip is a conflict (GDR-STORE-4,
     * GDR-STORE-5); a changed path without one overwrites a concurrent change.
     *
     * @param author          the author, whose pseudonym names the draft reference and the commit's author
     * @param key             the document or entry
     * @param expectedBlobIds the full blob id the caller loaded per repository-relative path; the zero id expects the
     *                        path to be absent; empty for no check
     * @param message         the commit message
     * @return {@link SaveResult.Saved} with the commit the target branch points at, {@link SaveResult.Conflicting}
     *         with every conflicting path, or {@link SaveResult.NotSaved} with the outcome that prevented the save, for
     *         example {@link StoreOutcome#MALFORMED} for an invalid pseudonym,
     *         {@link de.cuioss.git.document.core.GitOutcome#NOT_FOUND} for a missing draft,
     *         {@link de.cuioss.git.document.core.GitOutcome#REJECTED} after the last attempt,
     *         {@link de.cuioss.git.document.core.GitOutcome#UNREACHABLE} or
     *         {@link de.cuioss.git.document.core.GitOutcome#CREDENTIALS_INVALID}
     * @throws NullPointerException if an argument, a path or an expected blob id is {@code null}
     */
    public SaveResult save(Author author, DocumentKey key, Map<String, String> expectedBlobIds, String message) {
        return saves.save(author, key, expectedBlobIds, message, null);
    }

    /**
     * Saves the draft of the author for the document onto its target branch like
     * {@link #save(Author, DocumentKey, Map, String)}, with a per-attempt step (GDR-STORE-3). The step runs on every
     * attempt, the first and each re-application onto a new tip, after the conflict check; it sees the tip and the tree
     * the draft's changes produce on it through a read-only {@link SaveStep.Attempt}. Its {@link SaveStep.Edits} are
     * written into the same single save commit, whose only parent stays the tip, an edit on a path the draft also
     * changed replacing the draft's edit. A draft without changes writes no commit and calls no step.
     *
     * @param author          the author, whose pseudonym names the draft reference and the commit's author
     * @param key             the document or entry
     * @param expectedBlobIds the full blob id the caller loaded per repository-relative path; the zero id expects the
     *                        path to be absent; empty for no check
     * @param message         the commit message
     * @param step            the per-attempt step
     * @return the results of {@link #save(Author, DocumentKey, Map, String)}, and {@link SaveResult.NotSaved} with
     *         {@link StoreOutcome#REFUSED} and the detail {@code <target>: step: refused} for a {@link SaveStep.Refusal},
     *         or with the passed-on {@link de.cuioss.git.document.core.GitOutcome#FAILED} and the detail
     *         {@code <target>: step: <simple class name of the exception>} for a step that threw a
     *         {@link RuntimeException}, without a further attempt; in both cases nothing is pushed, and draft and target
     *         branch stay unchanged
     * @throws NullPointerException if an argument, a path or an expected blob id is {@code null}
     */
    public SaveResult save(Author author, DocumentKey key, Map<String, String> expectedBlobIds, String message,
            SaveStep step) {
        return saves.save(author, key, expectedBlobIds, message, Objects.requireNonNull(step, "step"));
    }

    /**
     * Releases a document or numbered entry (GDR-STORE-6): merges its saved state into {@code main} as one release
     * commit, tags that commit {@code refs/tags/<collection>/<id>/v<n>}, {@code n} counting the releases of the
     * document from 1, and pushes {@code main} with lease and then the tag. For a per-document collection the release
     * commit merges the working branch into {@code main}, with both tips as its parents; for a numbered collection it
     * takes only the entry's paths from the collection branch onto {@code main}, its only parent. Its message is
     * {@code Release <collection>/<id> v<n>}, composed by the store; its author is the user who releases.
     * <p>
     * The release runs under the shared side of the clone's maintenance lock, each attempt, from the fetch to the push
     * of the tag, under its transport lock. When {@code main} moved on the remote and the tag is missing there, a
     * repeated release creates and pushes the missing tag on the release commit instead of merging again. A release
     * tag on the remote is never moved or deleted.
     *
     * @param author the user who releases, author of the release commit
     * @param key    the document or numbered entry
     * @return {@link StoreOutcome#OK} with the {@link Release}; {@link StoreOutcome#INCOMPLETE} with the detail
     *         {@code <tag>: not pushed (<outcome>)} when {@code main} moved and the tag push failed;
     *         {@link StoreOutcome#MALFORMED} for content without release; the passed-on
     *         {@link de.cuioss.git.document.core.GitOutcome#NOT_FOUND} for a missing working or collection branch,
     *         {@link de.cuioss.git.document.core.GitOutcome#CONFLICT} with the number of conflicting paths for a
     *         conflicting merge, {@link de.cuioss.git.document.core.GitOutcome#REJECTED} for a refused push of
     *         {@code main} or after every attempt lost its lease, or the passed-on outcome that ended the release, for
     *         example {@link de.cuioss.git.document.core.GitOutcome#UNREACHABLE}; no detail names a path, a content or
     *         a pseudonym
     * @throws NullPointerException if an argument is {@code null}
     */
    public StoreResult<Release> release(Author author, DocumentKey key) {
        return releases.release(author, key);
    }

    /**
     * Deletes every draft unchanged for {@code drafts.expiry} and removes the objects reachable only from deleted
     * references (GDR-STORE-7): a draft whose tip has a committer time at or before {@code now} minus
     * {@code drafts.expiry} (UTC) is deleted by compare-and-delete expecting that tip, so a draft changed meanwhile is
     * kept; then a garbage collection runs. Both run under the exclusive side of the clone's maintenance lock, so no
     * draft write or save of this writer runs meanwhile.
     *
     * @param now the current time
     * @return {@link StoreOutcome#OK} with the full names of the deleted draft references, sorted, possibly none; or
     *         the passed-on {@link de.cuioss.git.document.core.GitOutcome#FAILED} of a failed read, delete or garbage
     *         collection
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public StoreResult<List<String>> expireDrafts(Instant now) {
        return expiry.expire(now);
    }

    /**
     * Fetches the remote into the clone (GDR-STORE-9): every branch, tag and note of the remote mirrored, with the
     * credentials of the technical user, and every local branch, tag and note the remote deleted pruned; drafts and
     * applied-draft references are never pruned. The fetch runs under the shared side of the clone's maintenance lock
     * and under its transport lock; after both were released, the moved references are handed to every
     * {@link StoreIndex} opened over this writer, which refreshes.
     *
     * @return {@link StoreOutcome#OK} with the full names of the references the fetch moved, created or deleted, sorted,
     *         possibly none; or the passed-on Git outcome of the fetch, for example
     *         {@link de.cuioss.git.document.core.GitOutcome#UNREACHABLE} or
     *         {@link de.cuioss.git.document.core.GitOutcome#CREDENTIALS_INVALID}
     */
    public StoreResult<List<String>> fetch() {
        List<StoreEvents.Update> updates = new ArrayList<>();
        RepositoryGuard guard = clone.guard();
        StoreResult<List<String>> result = guard.shared(() -> guard.transport(() -> mirror(updates)));
        events.emit(updates);
        return result;
    }

    private StoreResult<List<String>> mirror(List<StoreEvents.Update> updates) {
        var fetched = operations.fetch(FetchInput.builder().root(clone.gitDir()).remote(ORIGIN)
                .refSpecs(JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS).credentials(user.credentials())
                .prune(true).build());
        if (!fetched.isOk()) {
            return StoreResult.passOn(fetched);
        }
        StoreEvents.fetched(fetched.value(), updates);
        return StoreResult.ok(updates.stream().map(StoreEvents.Update::ref).sorted().toList());
    }

    /**
     * Schedules {@link #fetch()} on the caller's executor at {@code fetch.interval} of the configuration, the first
     * run one interval from now and every next one an interval after the previous one ended (GDR-STORE-9). The writer
     * starts no thread of its own. A failed fetch is logged ({@code GIT_STORE-107}) with the alias and the outcome, or
     * the class of an exception, and never ends the schedule; the schedule ends when the caller cancels the returned
     * future or shuts the executor down, which the caller does before it closes the writer.
     *
     * @param executor the executor of the caller
     * @return the future of the schedule, to cancel it
     * @throws NullPointerException                                if {@code executor} is {@code null}
     * @throws java.util.concurrent.RejectedExecutionException if the executor refuses the schedule
     */
    public ScheduledFuture<?> fetchPeriodically(ScheduledExecutorService executor) {
        Objects.requireNonNull(executor, "executor");
        long interval;
        try {
            interval = fetchInterval.toNanos();
        } catch (ArithmeticException e) {
            interval = Long.MAX_VALUE;
        }
        return executor.scheduleWithFixedDelay(this::periodicFetch, interval, interval, TimeUnit.NANOSECONDS);
    }

    /** One run of the periodic fetch: a failure is logged and the schedule goes on. */
    private void periodicFetch() {
        try {
            var fetched = fetch();
            if (!fetched.isOk()) {
                LOGGER.warn(GitStoreLogMessages.WARN.PERIODIC_FETCH_FAILED, alias(), name(fetched.outcome()));
            }
        }
        /*TODO: Catch specific not RuntimeException. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
        catch (RuntimeException e) {
            LOGGER.warn(GitStoreLogMessages.WARN.PERIODIC_FETCH_FAILED, alias(), e.getClass().getSimpleName());
        }
    }

    private static String name(StoreResult.Outcome outcome) {
        return switch (outcome) {
            case StoreOutcome store -> store.name();
            case StoreResult.PassedOn(GitOutcome git) -> git.name();
        };
    }

    /**
     * Subscribes an in-process listener to the new commits of a branch (GDR-STORE-10). The listener is called with each
     * new commit of the branch, oldest first, after every save or release this writer pushed onto it and after every
     * fetch of this writer that moved it, on the thread of that operation, once it released every lock of the store
     * and the indexes over this writer were refreshed. The subscription starts at the tip the branch has in the clone
     * now; its cursor is kept in memory only, so nothing is delivered twice within the subscription and nothing older
     * than the subscription is delivered, also not by a new writer over the same clone. A listener that throws a
     * {@link RuntimeException} is logged ({@code GIT_STORE-109}) with the alias, the branch and the class of the
     * exception and never fails the save, release or fetch.
     *
     * @param branch   the branch, by its full name ({@code refs/heads/main}) or its name below {@code refs/heads/}
     * @param listener the listener
     * @return the subscription; closing it unsubscribes the listener
     * @throws NullPointerException if an argument is {@code null}
     */
    public CommitListener.Subscription subscribe(String branch, CommitListener listener) {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(listener, "listener");
        String full = branch.startsWith(HEADS) ? branch : HEADS + branch;
        return listeners.subscribe(full, listener);
    }

    /**
     * Returns the opened clone, for the index over this writer.
     *
     * @return the clone
     */
    Repository repository() {
        return clone.repository();
    }

    /**
     * Returns the notification seam of this writer, for the index over it.
     *
     * @return the events
     */
    StoreEvents events() {
        return events;
    }

    /** Releases the opened clone. */
    @Override
    public void close() {
        clone.repository().close();
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
