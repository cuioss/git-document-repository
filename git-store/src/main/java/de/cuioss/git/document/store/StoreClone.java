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
import java.util.concurrent.atomic.AtomicBoolean;


import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.Repository;

/**
 * The opened bare clone of one {@link StoreWriter}: its alias, its repository and its notification seam, the listener
 * subscription, the closed flag and {@link #close()}, with the two parts that work on it: the writes
 * ({@link CloneWrites}: drafts, listing, save, release, expiry) and the fetch with the schedules
 * ({@link CloneSchedules}: fetch, periodic fetch, anchoring). The writer is the public face of the store and delegates
 * every operation here; the behaviour, the locks and their order are those its documentation states.
 * {@link CloneOpening} opens the clone and builds these parts.
 * <p>
 * {@link #close()} sets the closed flag and releases the repository under the anchoring lock of the clone, which every
 * due check of the anchoring holds while it runs and under which it reads the flag ({@link AnchorEngine#dueCheck}): a
 * due check that started before the close ends before the repository is released, and one queued or called after it
 * does nothing. A close issued inside a callback that holds a lock of the clone is refused and leaves the clone open.
 */
final class StoreClone {

    private static final CuiLogger LOGGER = new CuiLogger(StoreClone.class);

    private final ExplicitSave.Clone clone;
    private final StoreEvents events;
    private final ListenerDispatch listeners;
    private final CloneWrites writes;
    private final CloneSchedules schedules;
    private final AtomicBoolean closed;

    /**
     * Holds an opened clone with the parts that work on it.
     *
     * @param clone     the opened bare clone with its maintenance, transport and anchoring locks
     * @param events    the notification seam of the clone
     * @param listeners the listener dispatch, the last observer of the events
     * @param writes    the writes of the clone
     * @param schedules the fetch and the schedules of the clone
     * @param closed    the closed flag, shared with {@code schedules}
     * @throws NullPointerException if an argument is {@code null}
     */
    StoreClone(ExplicitSave.Clone clone, StoreEvents events, ListenerDispatch listeners, CloneWrites writes,
            CloneSchedules schedules, AtomicBoolean closed) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.events = Objects.requireNonNull(events, "events");
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        this.writes = Objects.requireNonNull(writes, "writes");
        this.schedules = Objects.requireNonNull(schedules, "schedules");
        this.closed = Objects.requireNonNull(closed, "closed");
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

    /**
     * Returns the writes of the clone.
     *
     * @return drafts, listing, save, release and expiry
     */
    CloneWrites writes() {
        return writes;
    }

    /**
     * Returns the fetch and the schedules of the clone.
     *
     * @return fetch, periodic fetch and anchoring
     */
    CloneSchedules schedules() {
        return schedules;
    }

    /** Subscribes a listener to a branch by its full name; see {@link StoreWriter#subscribe}. */
    CommitListener.Subscription subscribe(String branch, CommitListener listener) {
        return listeners.subscribe(branch, listener);
    }

    /**
     * Sets the closed flag and releases the opened clone, both under the anchoring lock of the clone, so no due check
     * runs on a released repository and none runs after the close; a second call releases nothing.
     * <p>
     * A close from a thread that holds a lock of the guard ({@link RepositoryGuard#insideCallback()}), a callback
     * inside an operation of the clone, is refused before the anchoring lock is requested, so it never waits: the
     * closed flag is not set, the repository is not released, and {@link GitStoreLogMessages.WARN#CLOSE_REFUSED} is
     * logged with the detail {@value RepositoryGuard#CLOSE_IN_CALLBACK}. A close after the callback returned closes the
     * clone.
     * <p>
     * The events of an operation are emitted after every lock of the clone was released ({@link StoreEvents}), so that
     * a listener can call the writer, {@code close()} included; the close does not wait for that delivery. A close on
     * another thread between the end of an operation and the delivery of its events therefore releases the repository
     * first, and the index and listener reads of that delivery fail on it: each such failure is isolated and logged at
     * debug level by the observer rule. This is the chosen mechanism; the
     * alternative, to hold the repository open until the delivery ends, would let a close wait for a listener.
     */
    void close() {
        RepositoryGuard guard = clone.guard();
        if (guard.insideCallback()) {
            LOGGER.warn(GitStoreLogMessages.WARN.CLOSE_REFUSED, alias(), RepositoryGuard.CLOSE_IN_CALLBACK);
            return;
        }
        guard.anchoring(() -> {
            if (!closed.getAndSet(true)) {
                clone.repository().close();
            }
            return Boolean.TRUE;
        });
    }
}
