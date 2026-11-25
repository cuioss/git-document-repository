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
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * The expiry of the drafts of one bare clone (GDR-STORE-7; checked against JGit 7.8.0).
 * <p>
 * Every draft below {@code refs/drafts/} whose tip has a committer time at or before {@code now} minus
 * {@code drafts.expiry}, computed in UTC, is deleted by {@link ReferenceUpdater#compareAndDelete} expecting the tip
 * that was read as stale, so a draft changed meanwhile is kept. The deletes run under the exclusive side of the
 * clone's maintenance lock, so no in-process draft write or save runs while a draft is deleted: a write that found its
 * draft present cannot lose it before its compare-and-swap. Every applied-draft reference
 * ({@link StoreReferences#APPLIED}) whose draft no longer exists is deleted the same way, so it keeps no draft commit
 * alive.
 * <p>
 * Then, still under the exclusive side of that lock, so that no in-process write holds objects not yet referenced, a
 * garbage collection removes the objects reachable only from deleted references: {@code Git.wrap(repository).gc()}, JGit's
 * {@code GarbageCollectCommand}, with {@code gc.pruneExpire} and {@code gc.prunePackExpire} set to {@code now} in the
 * clone's configuration in memory. JGit 7.8.0 {@code GarbageCollectCommand.call} runs {@code GC.gc().get()}, which is
 * synchronous unless {@code GC.setBackground(true)}; {@code GC.doGc} packs the references, repacks the reachable
 * objects into a new pack, deletes the old packs older than the pack expiry, and prunes the unreferenced loose objects
 * older than the expiry ({@code GC.prune}), both read from that configuration ({@code GC.getExpireDate},
 * {@code GC.getPackExpireDate}); with the expiry {@code now} it loosens no object of an old pack
 * ({@code GC.deleteOldPacks}), so nothing unreachable survives in a fresh loose object. It runs no hook and starts no
 * process: the repository's {@code FS} is asked for file times only. Every reference update of the store disables the
 * reflog, so no reflog keeps an object of a deleted draft alive. JGit 7.8.0 {@code GC.doGc} returns without collecting
 * and without an error while another collection holds {@code gc.pid} ({@code GC.PidLock}), and calls
 * {@code ProgressMonitor.start} only after it took that file; the expiry gives the command a progress monitor
 * ({@code GarbageCollectCommand.setProgressMonitor}, passed on to {@code GC.setProgressMonitor}) and, when that monitor
 * was never started, ends in {@link GitOutcome#CONFLICT}, which may be retried, so a skipped collection is never
 * reported as a successful expiry, however late the other collection took {@code gc.pid}. The drafts it deleted stay
 * deleted.
 * <p>
 * The expiry {@code now} is kept, with no grace period (decision recorded in GDR-STORE-7, checked against JGit 7.8.0
 * {@code GC.prune}, {@code GC.getExpireDate} and {@code GC.PidLock}). {@code GC.prune} deletes a loose object only when
 * it is unreferenced and its file time lies before the expiry instant, and checks the file time once more right before
 * the delete, so an object written after the collection started is never pruned. An unreferenced older object belongs
 * to no write in flight: the clone is the cache of one process, and every write of that process runs under the shared
 * side of the maintenance lock, which the collection holds exclusively. {@code gc.pid} excludes only a second
 * collection of the same clone, not a writer; a grace period would protect a writer outside the process, which the
 * store does not have, and would let the objects of a deleted draft survive the expiry.
 * <p>
 * A reference below {@code refs/drafts/} whose tip is no commit was not written by the store; it is no draft, is kept
 * and does not end the expiry.
 * <p>
 * A failure detail and the debug log name the exception class only, never its message, which may carry a path
 * (GDR-NFR-3).
 */
final class DraftExpiry {

    private static final CuiLogger LOGGER = new CuiLogger(DraftExpiry.class);

    /** The expiry of the garbage collection: every unreferenced object and old pack, git's own word. */
    private static final String NOW = "now";

    /** The file a running JGit garbage collection holds in the git directory (JGit 7.8.0 {@code GC.PidLock}). */
    static final String GC_PID = "gc.pid";

    /** The detail of a skipped garbage collection. */
    static final String GC_SKIPPED = "gc: skipped, another collection holds " + GC_PID;

    private final String alias;
    private final Repository repository;
    private final RepositoryGuard guard;
    private final ReferenceUpdater updater;
    private final Period expiry;
    private final int attempts;

    /**
     * Creates the expiry of a clone.
     *
     * @param alias      the alias of the repository
     * @param repository the bare clone
     * @param guard      the in-process coordination of the clone
     * @param updater    the compare-and-delete updater
     * @param expiry     the period after which an unchanged draft is deleted ({@code drafts.expiry})
     * @param attempts   the attempts of a compare-and-delete, at least 1
     * @throws NullPointerException if an argument is {@code null}
     */
    DraftExpiry(String alias, Repository repository, RepositoryGuard guard, ReferenceUpdater updater, Period expiry,
            int attempts) {
        this.alias = Objects.requireNonNull(alias, "alias");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.updater = Objects.requireNonNull(updater, "updater");
        this.expiry = Objects.requireNonNull(expiry, "expiry");
        this.attempts = attempts;
    }

    /**
     * Deletes every draft unchanged for {@code drafts.expiry} and collects the garbage.
     *
     * @param now the current time
     * @return {@link StoreOutcome#OK} with the deleted draft references, sorted, possibly none; the passed-on
     *         {@link GitOutcome#CONFLICT} when another garbage collection holds {@value #GC_PID}; or the passed-on
     *         {@link GitOutcome#FAILED} of a failed read, delete or garbage collection, the detail naming the
     *         exception class or the reference only
     * @throws NullPointerException if {@code now} is {@code null}
     */
    StoreResult<List<String>> expire(Instant now) {
        Objects.requireNonNull(now, "now");
        // the cutoff is computed inside the action, so a call the guard refuses, from a callback that holds the shared
        // lock, never reaches the conversion of the time
        StoreResult<List<String>> deleted = guard
                .exclusive(() -> deleteAndCollect(now.atOffset(ZoneOffset.UTC).minus(expiry).toInstant()));
        if (deleted.isOk()) {
            List<String> names = Objects.requireNonNull(deleted.value());
            LOGGER.info(GitStoreLogMessages.INFO.DRAFTS_EXPIRED, alias, names.size());
        }
        return deleted;
    }

    /** Deletes the stale drafts, then collects the garbage; both under the exclusive side of the maintenance lock. */
    private StoreResult<List<String>> deleteAndCollect(Instant cutoff) {
        StoreResult<List<String>> deleted = deleteStale(cutoff);
        if (!deleted.isOk()) {
            return deleted;
        }
        StoreResult<List<String>> orphans = deleteOrphanedApplied();
        if (!orphans.isOk()) {
            return orphans;
        }
        StoreResult<List<String>> collected = collectGarbage();
        return collected.isOk() ? deleted : collected;
    }

    /**
     * Deletes every applied-draft reference whose draft no longer exists, by compare-and-delete expecting the tip that
     * was read, so the draft commits it kept reachable are collected as well; the value of a success is unused.
     */
    private StoreResult<List<String>> deleteOrphanedApplied() {
        try {
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(StoreReferences.APPLIED)) {
                ObjectId tip = ref.getObjectId();
                if (tip != null && repository.exactRef(StoreReferences.draftOf(ref.getName())) == null) {
                    var result = updater.compareAndDelete(repository, ref.getName(), tip.name(), attempts);
                    if (!result.isOk() && result.outcome() != GitOutcome.CONFLICT) {
                        return StoreResult.passOn(result);
                    }
                }
            }
            return StoreResult.ok(List.of());
        } catch (IOException e) {
            return StoreResult.passOn(DraftReferences.failure(StoreReferences.APPLIED, e));
        }
    }

    /** Deletes every draft whose tip lies at or before the cutoff, expecting that tip. */
    private StoreResult<List<String>> deleteStale(Instant cutoff) {
        try (RevWalk walk = new RevWalk(repository)) {
            List<Ref> refs = new ArrayList<>(repository.getRefDatabase().getRefsByPrefix(StoreReferences.DRAFTS));
            refs.sort(Comparator.comparing(Ref::getName));
            List<String> deleted = new ArrayList<>();
            for (Ref ref : refs) {
                GitResult<Boolean> decided = deleteIfStale(walk, ref, cutoff);
                if (!decided.isOk()) {
                    return StoreResult.passOn(decided);
                }
                if (Boolean.TRUE.equals(decided.value())) {
                    deleted.add(ref.getName());
                }
            }
            return StoreResult.ok(List.copyOf(deleted));
        } catch (IOException e) {
            return StoreResult.passOn(DraftReferences.failure(StoreReferences.DRAFTS, e));
        }
    }

    /**
     * Deletes one draft if its tip lies at or before the cutoff, expecting that tip.
     *
     * @return {@code true} for a deleted draft, {@code false} for one that is kept (younger, without a tip, with a tip
     *         that is no commit, or changed meanwhile), or the outcome of a failed delete
     */
    private GitResult<Boolean> deleteIfStale(RevWalk walk, Ref ref, Instant cutoff) throws IOException {
        ObjectId tip = ref.getObjectId();
        if (tip == null) {
            return GitResult.ok(false);
        }
        Instant committed;
        try {
            committed = walk.parseCommit(tip).getCommitterIdent().getWhenAsInstant();
        } catch (IncorrectObjectTypeException e) {
            // a reference below refs/drafts/ this class did not write; it is no draft and is left as it is
            LOGGER.debug("Skipped a reference below %s whose tip is no commit: %s", StoreReferences.DRAFTS,
                    e.getClass().getSimpleName());
            return GitResult.ok(false);
        }
        if (committed.isAfter(cutoff)) {
            return GitResult.ok(false);
        }
        var result = updater.compareAndDelete(repository, ref.getName(), tip.name(), attempts);
        if (result.isOk()) {
            return GitResult.ok(true);
        }
        if (result.outcome() == GitOutcome.CONFLICT) {
            LOGGER.debug("The draft %s changed meanwhile and is kept", ref.getName());
            return GitResult.ok(false);
        }
        return GitResult.of(result.outcome(), Objects.requireNonNull(result.detail()));
    }

    /**
     * Collects the garbage with both expiries {@value #NOW}; the value of a success is unused. A collection JGit skips
     * because another one holds {@value #GC_PID}, which its progress monitor shows by never being started, is passed on
     * as {@link GitOutcome#CONFLICT}, which may be retried.
     */
    private StoreResult<List<String>> collectGarbage() {
        StoredConfig config = repository.getConfig();
        config.setString(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_PRUNEEXPIRE, NOW);
        config.setString(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_PRUNEPACKEXPIRE, NOW);
        var started = new StartRecordingMonitor();
        try (Git git = Git.wrap(repository)) {
            git.gc().setProgressMonitor(started).call();
        } catch (GitAPIException | JGitInternalException e) {
            String type = e.getClass().getSimpleName();
            LOGGER.debug("The garbage collection of %s failed: %s", alias, type);
            LOGGER.warn(GitStoreLogMessages.WARN.GARBAGE_COLLECTION_FAILED, alias, type);
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.FAILED), null, "gc: " + type);
        }
        if (!started.started()) {
            LOGGER.warn(GitStoreLogMessages.WARN.GARBAGE_COLLECTION_SKIPPED, alias);
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.CONFLICT), null, GC_SKIPPED);
        }
        return StoreResult.ok(List.of());
    }

    /**
     * A progress monitor that records whether the collection started: JGit 7.8.0 {@code GC.doGc} calls
     * {@link #start(int)} only after it took {@value #GC_PID}, so a monitor never started marks a skipped collection.
     */
    private static final class StartRecordingMonitor implements ProgressMonitor {

        private boolean started;

        boolean started() {
            return started;
        }

        @Override
        public void start(int totalTasks) {
            started = true;
        }

        @Override
        public void beginTask(String title, int totalWork) {
            // the progress of a running collection is not reported
        }

        @Override
        public void update(int completed) {
            // the progress of a running collection is not reported
        }

        @Override
        public void endTask() {
            // the progress of a running collection is not reported
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void showDuration(boolean enabled) {
            // the progress of a running collection is not reported
        }
    }
}
