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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The opened bare clone of one {@link StoreWriter} with the parts that work on it: the drafts, the explicit save, the
 * release and the draft expiry, the fetch and its schedule, the notification seam and the listener dispatch. The
 * writer is the public face of the store and delegates every operation here; the behaviour, the locks and their order
 * are those its documentation states. {@link CloneOpening} opens the clone and builds these parts.
 */
// S6539 counts the parts this class delegates to; it is the one place that wires the parts of the clone, so its
// coupling is its purpose, and splitting it would only move the wiring
@SuppressWarnings("java:S6539")
final class StoreClone {

    private static final CuiLogger LOGGER = new CuiLogger(StoreClone.class);

    /** The remote of every bare clone of the store. */
    private static final String ORIGIN = "origin";

    private final ExplicitSave.Clone clone;
    private final Transport transport;
    private final StoreEvents events;
    private final ListenerDispatch listeners;
    private final DraftReferences drafts;
    private final ExplicitSave saves;
    private final DocumentRelease releases;
    private final DraftExpiry expiry;
    private final AnchorEngine anchors;

    /**
     * The registration of the schedule whose executor runs the due checks after a save, a release or a fetch;
     * {@code null} until a schedule is registered and after it was cancelled.
     */
    private final AtomicReference<@Nullable Registration> anchorRegistration = new AtomicReference<>();

    /** Whether a due check is handed to the executor and has not started yet, so further triggers coalesce. */
    private final AtomicBoolean anchorQueued = new AtomicBoolean();

    /**
     * Holds an opened clone with the parts that work on it.
     *
     * @param clone     the opened bare clone with its maintenance and transport locks
     * @param transport what the clone fetches with
     * @param parts     the parts that work on the clone
     */
    StoreClone(ExplicitSave.Clone clone, Transport transport, Parts parts) {
        this.clone = clone;
        this.transport = transport;
        this.events = parts.events();
        this.listeners = parts.listeners();
        this.drafts = parts.drafts();
        this.saves = parts.saves();
        this.releases = parts.releases();
        this.expiry = parts.expiry();
        this.anchors = parts.anchors();
    }

    /**
     * What the clone fetches with.
     *
     * @param operations    the repository operations of {@code git-core}
     * @param user          the technical user, holder of the credentials of every fetch
     * @param fetchInterval the interval of the periodic fetch ({@code fetch.interval})
     * @param retryInterval the interval of the periodic due check of the anchoring ({@code anchor.retry-interval})
     */
    record Transport(RepositoryOperations operations, TechnicalUser user, Duration fetchInterval,
    Duration retryInterval) {
    }

    /**
     * The parts that work on the clone.
     *
     * @param events    the notification seam
     * @param listeners the listener dispatch, the last observer of the events
     * @param drafts    the drafts
     * @param saves     the explicit save
     * @param releases  the release
     * @param expiry    the draft expiry
     * @param anchors   the anchoring engine
     */
    record Parts(StoreEvents events, ListenerDispatch listeners, DraftReferences drafts, ExplicitSave saves,
    DocumentRelease releases, DraftExpiry expiry, AnchorEngine anchors) {
    }

    /**
     * Returns the alias of the repository of the clone.
     *
     * @return the alias
     */
    String alias() {
        return clone.alias();
    }

    /**
     * Returns the opened clone.
     *
     * @return the repository of the clone
     */
    Repository repository() {
        return clone.repository();
    }

    /**
     * Returns the notification seam of the clone.
     *
     * @return the events
     */
    StoreEvents events() {
        return events;
    }

    /** Writes a draft under the shared side of the maintenance lock; see {@link StoreWriter#draft}. */
    StoreResult<String> draft(Author author, DocumentKey key, List<TreeEdit> edits, String message) {
        return clone.guard().shared(() -> drafts.write(author, key, edits, message));
    }

    /** Lists the drafts of a user under the shared side of the maintenance lock; see {@link StoreWriter#drafts}. */
    StoreResult<List<Draft>> drafts(String pseudonym) {
        return clone.guard().shared(() -> drafts.list(pseudonym));
    }

    /** Saves a draft; see {@link StoreWriter#save(Author, IdentityClaims, DocumentKey, Map, String, SaveStep)}. */
    SaveResult save(Author author, IdentityClaims claims, DocumentKey key, Map<String, String> expectedBlobIds,
            String message, @Nullable SaveStep step) {
        SaveResult result = saves.save(author, claims, key, expectedBlobIds, message, step);
        if (result instanceof SaveResult.Saved) {
            triggerDueCheck();
        }
        return result;
    }

    /** Releases a document or entry; see {@link StoreWriter#release}. */
    StoreResult<Release> release(Author author, IdentityClaims claims, DocumentKey key) {
        var result = releases.release(author, claims, key);
        if (result.isOk() || result.outcome() == StoreOutcome.INCOMPLETE) {
            triggerDueCheck();
        }
        return result;
    }

    /** Expires the drafts; see {@link StoreWriter#expireDrafts}. */
    StoreResult<List<String>> expireDrafts(Instant now) {
        return expiry.expire(now);
    }

    /**
     * Fetches the remote under the shared side of the maintenance lock and the transport lock, then hands the moved
     * references to the events; see {@link StoreWriter#fetch}.
     */
    StoreResult<List<String>> fetch() {
        List<StoreEvents.Update> updates = new ArrayList<>();
        RepositoryGuard guard = clone.guard();
        StoreResult<List<String>> result = guard.shared(() -> guard.transport(() -> mirror(updates)));
        events.emit(updates);
        if (result.isOk()) {
            triggerDueCheck();
        }
        return result;
    }

    /** Runs one due check of the anchoring on the calling thread; see {@link StoreWriter#anchor()}. */
    StoreResult<StoreWriter.Anchoring> anchor() {
        return anchors.dueCheck();
    }

    /**
     * Schedules the due check of the anchoring on the caller's executor at {@code anchor.retry-interval} and registers
     * the executor for the due checks after a save, a release or a fetch; see {@link StoreWriter#anchorPeriodically}.
     */
    AnchorSchedule anchorPeriodically(ScheduledExecutorService executor) {
        var registration = new Registration(executor);
        anchorRegistration.set(registration);
        long interval = nanos(transport.retryInterval());
        try {
            return new AnchorSchedule(executor.scheduleWithFixedDelay(this::safeDueCheck, interval, interval,
                    TimeUnit.NANOSECONDS), () -> end(registration));
        } catch (RejectedExecutionException e) {
            end(registration);
            throw e;
        }
    }

    /**
     * Ends a registration: a due check it handed over and that has not started yet does not run, and the registration
     * is removed unless a later schedule replaced it already.
     */
    private void end(Registration registration) {
        registration.ended = true;
        anchorRegistration.compareAndSet(registration, null);
    }

    /**
     * Hands one due check to the executor of the registered schedule after the locks of a save, a release or a fetch
     * were released; a trigger while one is handed over and not started yet coalesces with it. Without a registration
     * or a provider nothing happens, a handed-over due check whose registration ended before it started does not run,
     * and a refusal of the executor is ignored: the next trigger or the schedule retries.
     */
    private void triggerDueCheck() {
        Registration registration = anchorRegistration.get();
        if (registration == null || !anchors.active() || !anchorQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            registration.executor.execute(() -> {
                anchorQueued.set(false);
                if (!registration.ended) {
                    safeDueCheck();
                }
            });
        } catch (RejectedExecutionException e) {
            anchorQueued.set(false);
            LOGGER.debug("The executor refused the due check of the anchoring");
        }
    }

    /**
     * The registration of one schedule of the due check: its identity is the token that only its own schedule
     * removes, whatever executor a later schedule uses.
     */
    private static final class Registration {

        private final Executor executor;

        /** Whether the schedule was cancelled; a due check it handed over and that has not started yet then ends. */
        private volatile boolean ended;

        Registration(Executor executor) {
            this.executor = executor;
        }
    }

    /** One due check off the caller's thread: a failure is logged and never escapes to the executor. */
    private void safeDueCheck() {
        try {
            anchors.dueCheck();
        }
        // a due check must never end the schedule nor fail the executor's thread (GDR-STORE-15)
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (RuntimeException e) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHORING_PENDING, alias(), e.getClass().getSimpleName());
        }
    }

    private static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private StoreResult<List<String>> mirror(List<StoreEvents.Update> updates) {
        var fetched = transport.operations().fetch(FetchInput.builder().root(clone.gitDir()).remote(ORIGIN)
                .refSpecs(JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS).credentials(transport.user().credentials())
                .prune(true).build());
        if (!fetched.isOk()) {
            return StoreResult.passOn(fetched);
        }
        StoreEvents.fetched(fetched.value(), updates);
        return StoreResult.ok(updates.stream().map(StoreEvents.Update::ref).sorted().toList());
    }

    /** Schedules the fetch on the caller's executor; see {@link StoreWriter#fetchPeriodically}. */
    FetchSchedule fetchPeriodically(ScheduledExecutorService executor) {
        long interval;
        try {
            interval = transport.fetchInterval().toNanos();
        } catch (ArithmeticException e) {
            interval = Long.MAX_VALUE;
        }
        return new FetchSchedule(
                executor.scheduleWithFixedDelay(this::periodicFetch, interval, interval, TimeUnit.NANOSECONDS));
    }

    /** One run of the periodic fetch: a failure is logged and the schedule goes on. */
    private void periodicFetch() {
        try {
            var fetched = fetch();
            if (!fetched.isOk()) {
                LOGGER.warn(GitStoreLogMessages.WARN.PERIODIC_FETCH_FAILED, alias(),
                        StoreResult.name(fetched.outcome()));
            }
        }
        // an unchecked failure of one fetch must not end the schedule (GDR-STORE-9)
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (RuntimeException e) {
            LOGGER.warn(GitStoreLogMessages.WARN.PERIODIC_FETCH_FAILED, alias(), e.getClass().getSimpleName());
        }
    }

    /** Subscribes a listener to a branch by its full name; see {@link StoreWriter#subscribe}. */
    CommitListener.Subscription subscribe(String branch, CommitListener listener) {
        return listeners.subscribe(branch, listener);
    }

    /** Releases the opened clone. */
    void close() {
        clone.repository().close();
    }
}
