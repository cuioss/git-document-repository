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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;


import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;

/**
 * The notification seam of one {@link StoreWriter} (GDR-STORE-9): the references a save, a release or a fetch of the
 * writer moved, handed to every registered observer.
 * <p>
 * An event carries the commit a save pushed onto its target branch, the commit a release pushed onto {@code main} and
 * its tag, and the reference updates of every fetch, each as the full reference name with its old and new id. An
 * operation collects its updates while it runs and emits them once, on its own thread, after it released every lock of
 * the store ({@link RepositoryGuard}), so an observer that calls the store again cannot deadlock it and never runs
 * while the transport lock is held. An operation that moved nothing emits no update: it only tells the registered
 * observers so ({@link Observer#unchanged}), and the listener dispatch is not called. The registered observers, the
 * indexes ({@link StoreIndex}), are called first, in the order of their registration, and the listener dispatch
 * ({@link ListenerDispatch}, GDR-STORE-10) last, so a listener finds the index refreshed. A {@link RuntimeException}
 * of one observer is logged by its class only and never fails the operation or keeps the next observer from its call.
 * <p>
 * The events of two operations may reach an observer in either order, since each is emitted by its own thread once its
 * locks are free: an observer derives its state from the Git state the event points at, never from the old id of an
 * update, which a concurrent operation may already have moved past.
 * <p>
 * An update names a reference and object ids only, never a path, a content or a pseudonym (GDR-NFR-3).
 */
final class StoreEvents {

    private static final CuiLogger LOGGER = new CuiLogger(StoreEvents.class);

    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private final Observer listeners;

    /**
     * Creates the events of a writer.
     *
     * @param listeners the observer called after every registered one: the listener dispatch of the writer
     * @throws NullPointerException if {@code listeners} is {@code null}
     */
    StoreEvents(Observer listeners) {
        this.listeners = Objects.requireNonNull(listeners, "listeners");
    }

    /**
     * An observer of the references the operations of a writer moved.
     */
    @FunctionalInterface
    interface Observer {

        /**
         * Called once per operation that moved a reference, after every lock of the store was released.
         *
         * @param updates the moved references, in the order the operation moved them, never empty
         */
        void moved(List<Update> updates);

        /**
         * Called once per operation that moved no reference, for example a fetch that found the clone current, after
         * every lock of the store was released; an index retries a failed refresh here. Does nothing by default.
         */
        default void unchanged() {
            // nothing moved, nothing to observe
        }
    }

    /**
     * One moved reference.
     *
     * @param ref   the full name of the reference
     * @param oldId the full id the reference pointed at before; {@code null} when it was created
     * @param newId the full id the reference points at now; {@code null} when it was deleted
     */
    record Update(String ref, @Nullable String oldId, @Nullable String newId) {

        /**
         * Checks the reference.
         *
         * @param ref   the reference
         * @param oldId the old id
         * @param newId the new id
         * @throws NullPointerException if {@code ref} is {@code null}
         */
        Update {
            Objects.requireNonNull(ref, "ref");
        }

        /**
         * The update of a reference pushed against a lease: created when the lease was the zero id.
         *
         * @param ref   the reference
         * @param lease the id the remote reference was expected at, the zero id for an absent one
         * @param newId the pushed id
         * @return the update
         */
        static Update pushed(String ref, String lease, String newId) {
            return new Update(ref, ObjectId.zeroId().name().equals(lease) ? null : lease, newId);
        }
    }

    /**
     * Registers an observer.
     *
     * @param observer the observer
     * @throws NullPointerException if {@code observer} is {@code null}
     */
    void register(Observer observer) {
        observers.add(Objects.requireNonNull(observer, "observer"));
    }

    /**
     * Removes an observer; one never registered is ignored.
     *
     * @param observer the observer
     */
    void unregister(Observer observer) {
        observers.remove(observer);
    }

    /**
     * Collects the reference updates of a fetch, leaving out every reference whose id did not change.
     *
     * @param updates the updated references of the fetch
     * @param into    the updates of the operation
     */
    static void fetched(@Nullable List<RepositoryOperations.RefUpdate> updates, List<Update> into) {
        if (updates == null) {
            return;
        }
        for (RepositoryOperations.RefUpdate update : updates) {
            if (!Objects.equals(update.oldId(), update.newId())) {
                into.add(new Update(update.ref(), update.oldId(), update.newId()));
            }
        }
    }

    /**
     * Hands the updates of one operation to every observer; called after every lock of the store was released.
     *
     * @param updates the updates of the operation; when there are none, every registered observer is told that the
     *                operation moved nothing ({@link Observer#unchanged}) and the listener dispatch is not called
     */
    void emit(List<Update> updates) {
        if (updates.isEmpty()) {
            observers.forEach(observer -> call(observer::unchanged));
            return;
        }
        List<Update> moved = List.copyOf(updates);
        observers.forEach(observer -> call(() -> observer.moved(moved)));
        call(() -> listeners.moved(moved));
    }

    private static void call(Runnable call) {
        try {
            call.run();
        }
        // a failing observer never fails the operation that emitted the events (GDR-STORE-9, GDR-STORE-10)
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (RuntimeException e) {
            LOGGER.debug("An observer of the store events threw %s", e.getClass().getSimpleName());
        }
    }
}
