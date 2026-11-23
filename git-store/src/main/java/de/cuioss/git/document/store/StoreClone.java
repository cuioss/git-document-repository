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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import de.cuioss.git.document.core.GitOutcome;
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
    }

    /**
     * What the clone fetches with.
     *
     * @param operations    the repository operations of {@code git-core}
     * @param user          the technical user, holder of the credentials of every fetch
     * @param fetchInterval the interval of the periodic fetch ({@code fetch.interval})
     */
    record Transport(RepositoryOperations operations, TechnicalUser user, Duration fetchInterval) {
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
     */
    record Parts(StoreEvents events, ListenerDispatch listeners, DraftReferences drafts, ExplicitSave saves,
    DocumentRelease releases, DraftExpiry expiry) {
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
        return saves.save(author, claims, key, expectedBlobIds, message, step);
    }

    /** Releases a document or entry; see {@link StoreWriter#release}. */
    StoreResult<Release> release(Author author, IdentityClaims claims, DocumentKey key) {
        return releases.release(author, claims, key);
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
        return result;
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
                LOGGER.warn(GitStoreLogMessages.WARN.PERIODIC_FETCH_FAILED, alias(), name(fetched.outcome()));
            }
        }
        // an unchecked failure of one fetch must not end the schedule (GDR-STORE-9)
        // cui-rewrite:disable InvalidExceptionUsageRecipe
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

    /** Subscribes a listener to a branch by its full name; see {@link StoreWriter#subscribe}. */
    CommitListener.Subscription subscribe(String branch, CommitListener listener) {
        return listeners.subscribe(branch, listener);
    }

    /** Releases the opened clone. */
    void close() {
        clone.repository().close();
    }
}
