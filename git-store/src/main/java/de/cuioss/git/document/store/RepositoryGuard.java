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
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * The in-process coordination of one bare clone: one guard per clone and JVM ({@link #of(Path)}), shared by every
 * {@link StoreWriter} that opens the clone.
 * <p>
 * Draft writes, saves and releases take the shared side of the maintenance lock, the expiry of drafts, its deletes and
 * its garbage collection, the exclusive side, so no write runs while the expiry deletes references and objects. One
 * lock per draft reference serialises the saves of that draft, from the read of the draft and its applied-draft
 * reference to the settlement, so a save reads both only after every earlier save of the draft has settled; the lock
 * exists only while a save holds or waits for it. One
 * transport lock serialises the fetch, apply and push of a save attempt and the fetch, merge, local tag and the two
 * pushes of a release attempt (GDR-STORE-6), so two operations of the clone never interleave their transport and no
 * pruning fetch deletes the local tag of a release before its push. The order is fixed: the shared side of the
 * maintenance lock, then, for a save, the draft lock, then the transport lock per attempt; a release takes no draft
 * lock, and the expiry takes the exclusive side only, so no cycle arises. A thread that holds the shared side and asks
 * for the exclusive side, a callback re-entering the writer, is refused ({@link #exclusive}) instead of waiting for
 * itself. The locks are in-process only.
 * <p>
 * The transport lock also serialises the writes and pushes of the anchoring (GDR-STORE-16, GDR-STORE-17): the fetch of
 * a due check, the checkpoint commit with its local tag and the tag push, and each anchor note with the push of its
 * notes reference run in one transport section, so no pruning fetch of a concurrent save deletes a local checkpoint tag
 * or overwrites a local notes commit before its push. One anchoring lock serialises the due checks of the clone; it is
 * taken first, before the shared side of the maintenance lock and the transport lock, and a save, a release or a fetch
 * never takes it, so no cycle arises and a slow anchor provider holds no lock those operations need.
 * <p>
 * The guard does not close the check-then-act window between reading a draft reference and moving it: that window is
 * closed by the lock-held expected-id comparison of {@link de.cuioss.git.document.core.ReferenceUpdater}, which also
 * protects against a writer outside this process.
 */
final class RepositoryGuard {

    /**
     * The guard of every clone opened in this JVM, by the real path of its git directory; never removed. The map is
     * bounded by the number of clone paths the JVM opens, one entry per clone, which the configuration fixes at start.
     */
    private static final ConcurrentMap<Path, RepositoryGuard> GUARDS = new ConcurrentHashMap<>();

    /**
     * The detail of an exclusive request from a thread that holds the shared side of the maintenance lock: a callback
     * that re-entered the writer.
     */
    static final String REENTERED = "maintenance: re-entered from a callback that holds the shared lock";

    private final ReentrantReadWriteLock maintenance = new ReentrantReadWriteLock();
    private final ReentrantLock transport = new ReentrantLock();
    private final ReentrantLock anchoring = new ReentrantLock();

    /**
     * The lock of every draft reference a save currently holds or waits for, by its full name. An entry is registered
     * and released only inside one atomic map operation on its key, together with the count of its holders, and is
     * removed when the last holder releases it: the map stays bounded by the saves in flight, and a save never locks
     * an instance that is no longer in the map, so two saves of one draft always meet the same lock.
     */
    private final ConcurrentMap<String, DraftLock> drafts = new ConcurrentHashMap<>();

    /**
     * The lock of one draft reference with the number of saves holding or waiting for it. The count is read and changed
     * only inside a {@link ConcurrentMap#compute} of its key, which the map runs atomically per key.
     */
    private static final class DraftLock {

        private final ReentrantLock lock = new ReentrantLock();
        private int holders;
    }

    /**
     * Returns the one guard of a clone, shared by every {@link StoreWriter} of the JVM that opens it, so the garbage
     * collection of one writer never runs while another writer of the same clone holds objects not yet referenced.
     * The key is the real path of the git directory ({@link Path#toRealPath}), else its normalised absolute path, so
     * two spellings of one directory share the guard.
     *
     * @param clone the git directory of the clone
     * @return the guard of the clone
     * @throws NullPointerException if {@code clone} is {@code null}
     */
    static RepositoryGuard of(Path clone) {
        return GUARDS.computeIfAbsent(canonical(Objects.requireNonNull(clone, "clone")), path -> new RepositoryGuard());
    }

    private static Path canonical(Path clone) {
        try {
            return clone.toRealPath();
        } catch (IOException e) {
            return clone.toAbsolutePath().normalize();
        }
    }

    /**
     * Runs an action under the shared side of the maintenance lock: a draft write, a save or a release.
     *
     * @param action the action
     * @param <T>    the result type
     * @return the result of the action
     */
    <T> T shared(Supplier<T> action) {
        return locked(maintenance.readLock(), action);
    }

    /**
     * Runs an action under the exclusive side of the maintenance lock: the deletes and the garbage collection of the
     * draft expiry.
     * <p>
     * The shared side cannot be upgraded: a thread that holds it and asked for the exclusive side would wait for itself
     * forever. A request from such a thread, a {@link SaveStep} or an {@link IndexContributor} that calls back into the
     * writer under the locks of the operation that called it, is refused before the lock is requested: it reads the
     * calling thread's own hold count of the shared side, a fact of that thread alone, so no other thread can change it
     * between the check and the request.
     *
     * @param action the action
     * @param <V>    the value type of the result
     * @return the result of the action; {@link StoreOutcome#REFUSED} with the detail {@value #REENTERED} when the
     *         calling thread holds the shared side of the maintenance lock
     */
    <V> StoreResult<V> exclusive(Supplier<StoreResult<V>> action) {
        Objects.requireNonNull(action, "action");
        if (maintenance.getReadHoldCount() > 0) {
            return StoreResult.of(StoreOutcome.REFUSED, REENTERED);
        }
        return locked(maintenance.writeLock(), action);
    }

    /**
     * Runs an action under the lock of one draft reference: one save of that draft, from its read to its settlement.
     *
     * @param draftRef the full name of the draft reference
     * @param action   the action
     * @param <T>      the result type
     * @return the result of the action
     * @throws NullPointerException if an argument is {@code null}
     */
    <T> T draft(String draftRef, Supplier<T> action) {
        return draft(draftRef, () -> {
            // no pause between registration and lock outside the tests
        }, action);
    }

    /**
     * Runs an action under the lock of one draft reference, with a pause point between the registration of the lock and
     * taking it, through which a test holds a save at the moment another save may release the lock.
     *
     * @param draftRef the full name of the draft reference
     * @param pause    run after the lock is registered and before it is taken
     * @param action   the action
     * @param <T>      the result type
     * @return the result of the action
     * @throws NullPointerException if an argument is {@code null}
     */
    <T> T draft(String draftRef, Runnable pause, Supplier<T> action) {
        Objects.requireNonNull(draftRef, "draftRef");
        Objects.requireNonNull(pause, "pause");
        Objects.requireNonNull(action, "action");
        DraftLock entry = drafts.compute(draftRef, (name, existing) -> {
            DraftLock registered = existing == null ? new DraftLock() : existing;
            registered.holders++;
            return registered;
        });
        try {
            pause.run();
            return locked(entry.lock, action);
        } finally {
            // the last holder removes the entry in the same atomic operation that counts it down
            drafts.computeIfPresent(draftRef, (name, registered) -> --registered.holders == 0 ? null : registered);
        }
    }

    /**
     * The number of draft locks currently registered, for the tests of the bound of the map.
     *
     * @return the number of draft references a save holds or waits for
     */
    int draftLocks() {
        return drafts.size();
    }

    /**
     * Runs an action under the transport lock: the fetch, apply and push of one save attempt, or the fetch, merge,
     * local tag and pushes of one release attempt.
     *
     * @param action the action
     * @param <T>    the result type
     * @return the result of the action
     */
    <T> T transport(Supplier<T> action) {
        return locked(transport, action);
    }

    /**
     * Runs an action under the anchoring lock: one due check of the anchoring, so two due checks of the clone never
     * request a proof for the same subject. It is taken before the shared side of the maintenance lock and the
     * transport lock, and no save, release or fetch takes it.
     *
     * @param action the action
     * @param <T>    the result type
     * @return the result of the action
     */
    <T> T anchoring(Supplier<T> action) {
        return locked(anchoring, action);
    }

    private static <T> T locked(Lock lock, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
