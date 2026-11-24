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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.RepositoryOperations.FetchInput;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The fetch and the schedules of one opened clone ({@link StoreClone}): the fetch with its mirror and the emission of
 * the moved references, the periodic fetch, the due check of the anchoring on demand and on the caller's schedule, and
 * the coalescing trigger that hands one due check to the registered schedule after a save, a release or a fetch. The
 * locks and their order are those {@link StoreWriter} documents; this class adds none.
 * <p>
 * The two scheduled runners, the periodic fetch and the due check of the anchoring off the caller's thread, isolate
 * every {@link Throwable} and log it by its class name only, so a schedule never ends (the rule of the scheduled
 * runners, {@link StoreEvents}). A handed-over due check of a closed clone does not run, and every due check reads the
 * closed flag of the clone under the anchoring lock that {@link StoreClone#close()} takes ({@link AnchorEngine#dueCheck}).
 */
final class CloneSchedules {

    private static final CuiLogger LOGGER = new CuiLogger(CloneSchedules.class);

    private final ExplicitSave.Clone clone;
    private final Transport transport;
    private final StoreEvents events;
    private final AnchorEngine anchors;
    private final AtomicBoolean closed;

    /**
     * The registration of the schedule whose executor runs the due checks after a save, a release or a fetch;
     * {@code null} until a schedule is registered and after it was cancelled.
     */
    private final AtomicReference<@Nullable Registration> anchorRegistration = new AtomicReference<>();

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

        /**
         * Checks the components.
         *
         * @param operations    the repository operations
         * @param user          the technical user
         * @param fetchInterval the interval of the periodic fetch
         * @param retryInterval the interval of the periodic due check
         * @throws NullPointerException if a component is {@code null}
         */
        Transport {
            Objects.requireNonNull(operations, "operations");
            Objects.requireNonNull(user, "user");
            Objects.requireNonNull(fetchInterval, "fetchInterval");
            Objects.requireNonNull(retryInterval, "retryInterval");
        }
    }

    /**
     * Creates the schedules of a clone.
     *
     * @param clone     the opened bare clone with its locks
     * @param transport what the clone fetches with
     * @param events    the notification seam of the clone
     * @param anchors   the anchoring engine of the clone
     * @param closed    the closed flag of the clone, set by {@link StoreClone#close()} under the anchoring lock
     * @throws NullPointerException if an argument is {@code null}
     */
    CloneSchedules(ExplicitSave.Clone clone, Transport transport, StoreEvents events, AnchorEngine anchors,
            AtomicBoolean closed) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.events = Objects.requireNonNull(events, "events");
        this.anchors = Objects.requireNonNull(anchors, "anchors");
        this.closed = Objects.requireNonNull(closed, "closed");
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

    private StoreResult<List<String>> mirror(List<StoreEvents.Update> updates) {
        var fetched = transport.operations().fetch(FetchInput.builder().root(clone.gitDir())
                .remote(StoreReferences.ORIGIN).refSpecs(JGitRepositoryOperations.MIRROR_FETCH_REF_SPECS)
                .credentials(transport.user().credentials()).prune(true).build());
        if (!fetched.isOk()) {
            return StoreResult.passOn(fetched);
        }
        StoreEvents.fetched(fetched.value(), updates);
        return StoreResult.ok(updates.stream().map(StoreEvents.Update::ref).sorted().toList());
    }

    /** Schedules the fetch on the caller's executor; see {@link StoreWriter#fetchPeriodically}. */
    FetchSchedule fetchPeriodically(ScheduledExecutorService executor) {
        long interval = nanos(transport.fetchInterval());
        return new FetchSchedule(
                executor.scheduleWithFixedDelay(this::periodicFetch, interval, interval, TimeUnit.NANOSECONDS));
    }

    /**
     * One run of the periodic fetch: a failure is logged and the schedule goes on, also for a {@link Throwable} of any
     * kind (the rule of the scheduled runners, {@link StoreEvents}), logged by its class name only.
     */
    private void periodicFetch() {
        try {
            var fetched = fetch();
            if (!fetched.isOk()) {
                LOGGER.warn(GitStoreLogMessages.WARN.PERIODIC_FETCH_FAILED, clone.alias(),
                        StoreResult.name(fetched.outcome()));
            }
        }
        // a failure of one fetch must not end the schedule (GDR-STORE-9); a scheduled runner isolates every Throwable
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (Throwable e) {
            LOGGER.warn(GitStoreLogMessages.WARN.PERIODIC_FETCH_FAILED, clone.alias(), e.getClass().getName());
        }
    }

    /** Runs one due check of the anchoring on the calling thread; see {@link StoreWriter#anchor()}. */
    StoreResult<StoreWriter.Anchoring> anchor() {
        return anchors.dueCheck(closed::get);
    }

    /**
     * Schedules the due check of the anchoring on the caller's executor at {@code anchor.retry-interval} and registers
     * the executor for the due checks after a save, a release or a fetch; see {@link StoreWriter#anchorPeriodically}.
     * The executor is registered only once the schedule exists: when it refuses the schedule, the refusal propagates
     * and the schedule registered before stays in effect.
     */
    AnchorSchedule anchorPeriodically(ScheduledExecutorService executor) {
        var registration = new Registration(executor);
        long interval = nanos(transport.retryInterval());
        ScheduledFuture<?> future = executor.scheduleWithFixedDelay(this::safeDueCheck, interval, interval,
                TimeUnit.NANOSECONDS);
        anchorRegistration.set(registration);
        return new AnchorSchedule(future, () -> end(registration));
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
     * were released; a trigger while one is handed over through the same registration and not started yet coalesces
     * with it. Without a registration or a provider nothing happens, a handed-over due check whose registration ended
     * or whose clone was closed before it started does not run, and a refusal of the executor is ignored: the next
     * trigger or the schedule retries. A handed-over due check that its executor discards never clears the flag of its
     * registration, so it holds back only the triggers of that registration, never those of a later schedule.
     */
    void triggerDueCheck() {
        Registration registration = anchorRegistration.get();
        if (registration == null || !anchors.active() || !registration.queued.compareAndSet(false, true)) {
            return;
        }
        try {
            registration.executor.execute(() -> {
                registration.queued.set(false);
                if (!registration.ended && !closed.get()) {
                    safeDueCheck();
                }
            });
        } catch (RejectedExecutionException e) {
            registration.queued.set(false);
            LOGGER.debug("The executor refused the due check of the anchoring");
        }
    }

    /**
     * The registration of one schedule of the due check: its identity is the token that only its own schedule
     * removes, whatever executor a later schedule uses.
     */
    private static final class Registration {

        private final Executor executor;

        /**
         * Whether a due check is handed to the executor through this registration and has not started yet, so further
         * triggers coalesce with it.
         */
        private final AtomicBoolean queued = new AtomicBoolean();

        /** Whether the schedule was cancelled; a due check it handed over and that has not started yet then ends. */
        private volatile boolean ended;

        Registration(Executor executor) {
            this.executor = executor;
        }
    }

    /**
     * One due check off the caller's thread: every failure, a {@link Throwable} of any kind (the rule of the scheduled
     * runners, {@link StoreEvents}), is logged by its class name only and never escapes to the executor, so the
     * schedule never ends.
     */
    private void safeDueCheck() {
        try {
            anchors.dueCheck(closed::get);
        }
        // a due check must never end the schedule nor fail the executor's thread (GDR-STORE-15); a scheduled runner
        // isolates every Throwable
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (Throwable e) {
            LOGGER.warn(GitStoreLogMessages.WARN.ANCHORING_PENDING, clone.alias(), e.getClass().getName());
        }
    }

    private static long nanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
