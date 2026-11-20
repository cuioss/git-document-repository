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

import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * The in-process coordination of one bare clone.
 * <p>
 * Draft writes and saves take the shared side of the maintenance lock, the garbage collection of expired drafts its
 * exclusive side, so no write runs while the collection deletes references and objects. One transport lock serialises
 * the fetch, apply and push of a save attempt, so two saves of the clone never interleave their transport.
 * <p>
 * The guard does not close the check-then-act window between reading a draft reference and moving it: that window is
 * closed by the lock-held expected-id comparison of {@link de.cuioss.git.document.core.ReferenceUpdater}, which also
 * protects against a writer outside this process.
 */
final class RepositoryGuard {

    private final ReentrantReadWriteLock maintenance = new ReentrantReadWriteLock();
    private final ReentrantLock transport = new ReentrantLock();

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
     * Runs an action under the exclusive side of the maintenance lock: the garbage collection.
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
