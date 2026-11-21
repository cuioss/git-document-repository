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
import org.jspecify.annotations.Nullable;

/**
 * The opened bare clone of one {@link StoreWriter} with the parts that work on it: the drafts, the explicit save, the
 * release and the draft expiry, the fetch and its schedule, the notification seam and the listener dispatch. The
 * writer is the public face of the store and delegates every operation here; the behaviour, the locks and their order
 * are those its documentation states.
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

    private StoreClone(ExplicitSave.Clone clone, Transport transport, Parts parts) {
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
    private record Transport(RepositoryOperations operations, TechnicalUser user, Duration fetchInterval) {
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
    private record Parts(StoreEvents events, ListenerDispatch listeners, DraftReferences drafts, ExplicitSave saves,
    DocumentRelease releases, DraftExpiry expiry) {
    }

    /**
     * Opens the bare clone of a repository, cloning its remote first if it does not exist yet; see
     * {@link StoreWriter#open}.
     *
     * @param storeRepository the repository of the registry
     * @param gitDir          the git directory of the bare clone
     * @param user            the technical user
     * @param configuration   the configuration of the store
     * @param operations      the repository operations of {@code git-core}
     * @param clock           the clock of every commit time
     * @return the results of {@link StoreWriter#open}, with the clone as the value
     */
    static StoreResult<StoreClone> open(StoreRepository storeRepository, Path gitDir, TechnicalUser user,
            StoreConfiguration configuration, RepositoryOperations operations, Clock clock) {
        var cloned = operations.cloneIfMissing(RepositoryOperations.CloneInput.builder()
                .gitDir(gitDir)
                .url(storeRepository.remote())
                .pushRefSpecs(StoreReferences.PUSH_REF_SPECS)
                .credentials(user.credentials())
                .build());
        if (!cloned.isOk()) {
            return StoreResult.passOn(cloned);
        }
        Path gitDirectory = Objects.requireNonNull(cloned.value()).gitDir();
        Repository repository;
        try {
            repository = new FileRepositoryBuilder().setGitDir(gitDirectory.toFile()).setFS(new RefusingFS())
                    .setMustExist(true).build();
        } catch (IOException e) {
            return StoreResult.passOn(DraftReferences.<StoreClone>failure(storeRepository.alias(), e));
        }
        if (!transportsTo(repository, storeRepository.remote())) {
            repository.close();
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.NOT_A_REPOSITORY), null,
                    storeRepository.alias() + ": " + StoreWriter.FOREIGN_CLONE);
        }
        var identities = new CommitIdentities(configuration.commit(), user);
        var updater = new ReferenceUpdater(new InCoreWriter());
        int attempts = configuration.save().maxAttempts();
        var guard = RepositoryGuard.of(gitDirectory);
        var bareClone = new ExplicitSave.Clone(storeRepository.alias(), repository, gitDirectory, guard);
        var listeners = new ListenerDispatch(storeRepository.alias(), repository);
        var events = new StoreEvents(listeners);
        var parts = new Parts(events, listeners, new DraftReferences(repository, updater, identities, clock, attempts),
                new ExplicitSave(bareClone, operations, user, identities, clock, attempts, events),
                new DocumentRelease(bareClone, operations, user, identities, clock, attempts, events),
                new DraftExpiry(storeRepository.alias(), repository, guard, updater, configuration.drafts().expiry(),
                        attempts));
        return StoreResult.ok(new StoreClone(bareClone,
                new Transport(operations, user, configuration.fetch().interval()), parts));
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

    String alias() {
        return clone.alias();
    }

    Repository repository() {
        return clone.repository();
    }

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

    /** Saves a draft; see {@link StoreWriter#save(Author, DocumentKey, Map, String, SaveStep)}. */
    SaveResult save(Author author, DocumentKey key, Map<String, String> expectedBlobIds, String message,
            @Nullable SaveStep step) {
        return saves.save(author, key, expectedBlobIds, message, step);
    }

    /** Releases a document or entry; see {@link StoreWriter#release}. */
    StoreResult<Release> release(Author author, DocumentKey key) {
        return releases.release(author, key);
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

    /** Subscribes a listener to a branch by its full name; see {@link StoreWriter#subscribe}. */
    CommitListener.Subscription subscribe(String branch, CommitListener listener) {
        return listeners.subscribe(branch, listener);
    }

    /** Releases the opened clone. */
    void close() {
        clone.repository().close();
    }
}
