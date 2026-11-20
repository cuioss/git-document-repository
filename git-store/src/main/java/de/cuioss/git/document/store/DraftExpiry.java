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
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * The expiry of the drafts of one bare clone (GDR-STORE-7; checked against JGit 7.8.0).
 * <p>
 * Every draft below {@code refs/drafts/} whose tip has a committer time at or before {@code now} minus
 * {@code drafts.expiry}, computed in UTC, is deleted by {@link ReferenceUpdater#compareAndDelete} expecting the tip
 * that was read as stale, so a draft changed meanwhile is kept. The deletes run under the shared side of the clone's
 * maintenance lock, beside draft writes and saves.
 * <p>
 * Then, under the exclusive side of that lock, so that no in-process write holds objects not yet referenced, a garbage
 * collection removes the objects reachable only from deleted references: {@code Git.wrap(repository).gc()}, JGit's
 * {@code GarbageCollectCommand}, with {@code gc.pruneExpire} and {@code gc.prunePackExpire} set to {@code now} in the
 * clone's configuration in memory. JGit 7.8.0 {@code GarbageCollectCommand.call} runs {@code GC.gc().get()}, which is
 * synchronous unless {@code GC.setBackground(true)}; {@code GC.doGc} packs the references, repacks the reachable
 * objects into a new pack, deletes the old packs older than the pack expiry, and prunes the unreferenced loose objects
 * older than the expiry ({@code GC.prune}), both read from that configuration ({@code GC.getExpireDate},
 * {@code GC.getPackExpireDate}); with the expiry {@code now} it loosens no object of an old pack
 * ({@code GC.deleteOldPacks}), so nothing unreachable survives in a fresh loose object. It runs no hook and starts no
 * process: the repository's {@code FS} is asked for file times only. Every reference update of the store disables the
 * reflog, so no reflog keeps an object of a deleted draft alive.
 * <p>
 * A failure detail names the exception class only, never its message, which may carry a path (GDR-NFR-3).
 */
final class DraftExpiry {

    private static final CuiLogger LOGGER = new CuiLogger(DraftExpiry.class);

    /** The expiry of the garbage collection: every unreferenced object and old pack, git's own word. */
    static final String NOW = "now";

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
     * @return {@link StoreOutcome#OK} with the deleted draft references, sorted, possibly none; or the passed-on
     *         {@link GitOutcome#FAILED} of a failed read, delete or garbage collection, the detail naming the
     *         exception class or the reference only
     * @throws NullPointerException if {@code now} is {@code null}
     */
    StoreResult<List<String>> expire(Instant now) {
        Objects.requireNonNull(now, "now");
        Instant cutoff = now.atOffset(ZoneOffset.UTC).minus(expiry).toInstant();
        StoreResult<List<String>> deleted = guard.shared(() -> deleteStale(cutoff));
        if (!deleted.isOk()) {
            return deleted;
        }
        StoreResult<List<String>> collected = guard.exclusive(this::collectGarbage);
        if (!collected.isOk()) {
            return collected;
        }
        List<String> names = Objects.requireNonNull(deleted.value());
        LOGGER.info(GitStoreLogMessages.INFO.DRAFTS_EXPIRED, alias, names.size());
        return deleted;
    }

    /** Deletes every draft whose tip lies at or before the cutoff, expecting that tip. */
    private StoreResult<List<String>> deleteStale(Instant cutoff) {
        try (RevWalk walk = new RevWalk(repository)) {
            List<Ref> refs = new ArrayList<>(repository.getRefDatabase().getRefsByPrefix(StoreReferences.DRAFTS));
            refs.sort(Comparator.comparing(Ref::getName));
            List<String> deleted = new ArrayList<>();
            for (Ref ref : refs) {
                ObjectId tip = ref.getObjectId();
                if (tip == null) {
                    continue;
                }
                Instant changedAt = walk.parseCommit(tip).getCommitterIdent().getWhenAsInstant();
                if (changedAt.isAfter(cutoff)) {
                    continue;
                }
                var result = updater.compareAndDelete(repository, ref.getName(), tip.name(), attempts);
                if (result.isOk()) {
                    deleted.add(ref.getName());
                } else if (result.outcome() == GitOutcome.CONFLICT) {
                    LOGGER.debug("The draft %s changed meanwhile and is kept", ref.getName());
                } else {
                    return StoreResult.passOn(result);
                }
            }
            return StoreResult.ok(List.copyOf(deleted));
        } catch (IOException e) {
            return StoreResult.passOn(DraftReferences.failure(StoreReferences.DRAFTS, e));
        }
    }

    /** Collects the garbage with both expiries {@value #NOW}; the value of a success is unused. */
    private StoreResult<List<String>> collectGarbage() {
        StoredConfig config = repository.getConfig();
        config.setString(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_PRUNEEXPIRE, NOW);
        config.setString(ConfigConstants.CONFIG_GC_SECTION, null, ConfigConstants.CONFIG_KEY_PRUNEPACKEXPIRE, NOW);
        try (Git git = Git.wrap(repository)) {
            git.gc().call();
            return StoreResult.ok(List.of());
        } catch (GitAPIException | JGitInternalException e) {
            String type = e.getClass().getSimpleName();
            LOGGER.debug(e, "The garbage collection of %s failed", alias);
            LOGGER.warn(GitStoreLogMessages.WARN.GARBAGE_COLLECTION_FAILED, alias, type);
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.FAILED), null, "gc: " + type);
        }
    }
}
