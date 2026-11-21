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
 * Draft writes and saves take the shared side of the maintenance lock, the expiry of drafts, its deletes and its garbage
 * collection, the exclusive side, so no write runs while the expiry deletes references and objects. One transport lock
 * serialises the fetch, apply and push of a save attempt, so two saves of the clone never interleave their transport.
 * <p>
 * The guard does not close the check-then-act window between reading a draft reference and moving it: that window is
 * closed by the lock-held expected-id comparison of {@link de.cuioss.git.document.core.ReferenceUpdater}, which also
 * protects against a writer outside this process.
 */
final class RepositoryGuard {

    /** The guard of every clone opened in this JVM, by the real path of its git directory; never removed. */
    private static final ConcurrentMap<Path, RepositoryGuard> GUARDS = new ConcurrentHashMap<>();

    private final ReentrantReadWriteLock maintenance = new ReentrantReadWriteLock();
    private final ReentrantLock transport = new ReentrantLock();

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
     * Runs an action under the shared side of the maintenance lock: a draft write or a save.
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
     *
     * @param action the action
     * @param <T>    the result type
     * @return the result of the action
     */
    <T> T exclusive(Supplier<T> action) {
        return locked(maintenance.writeLock(), action);
    }

    /**
     * Runs an action under the transport lock: the fetch, apply and push of one save attempt.
     *
     * @param action the action
     * @param <T>    the result type
     * @return the result of the action
     */
    <T> T transport(Supplier<T> action) {
        return locked(transport, action);
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
