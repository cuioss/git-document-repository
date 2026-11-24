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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;


import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jspecify.annotations.Nullable;

/**
 * The dispatch of the new commits of a branch to its subscribed {@link CommitListener}s (GDR-STORE-10), the last
 * observer of the {@link StoreEvents} of a writer, after every index.
 * <p>
 * Every subscription derives what is new from the Git state, never from the old id of an event. Its <em>start</em> is
 * fixed when it subscribes: the tip the branch has in the clone, or, for a branch the clone does not hold yet, the tip
 * of every branch of the clone. The subscription is registered before its start is read, under its own lock, so an
 * event emitted while it subscribes reaches it and waits until the start is fixed: a commit a save moved the branch to
 * after the start was read is delivered, one it moved to before is part of the start and not delivered again. Its
 * <em>delivered tips</em> are the new ids it has walked, an ancestor of a later one
 * dropped. When an event moves the branch to a new id, the dispatch walks the commits reachable from that id and from
 * neither the start nor a delivered tip, oldest first ({@code RevSort.TOPO} with {@code RevSort.REVERSE}, JGit 7.8.0),
 * and calls the listener with each. Every commit reachable from the branch that was not reachable from the start
 * therefore reaches the listener exactly once, oldest first, whatever order the events of two operations arrive in: a
 * later move dispatched first delivers the earlier commits with it, and the earlier move then finds them delivered. A
 * branch created after the subscription delivers its own commits, and the history it forked from that was not in the
 * clone when the subscription started. Neither start nor delivered tips are persisted: a new writer starts its
 * subscriptions at the tips of the clone and delivers nothing older.
 * <p>
 * The delivery of one subscription is serialised by its lock, so concurrent triggers deliver every commit once, in
 * order. A thread never waits for the lock of a subscription while it is inside a listener call: a listener that calls
 * the store on its own thread, a save included, re-enters the dispatch, and the delivery of a move it caused is queued
 * and runs once the listener returned (for its own subscription) or at once when the other subscription is free, else
 * by the thread that holds it, which drains the queue before it lets go of it. Two listeners that save onto each other's
 * branch on two threads therefore never deadlock. {@link CommitListener.Subscription#close} takes the lock, so it waits
 * for a delivery in progress and no call starts after it returned; called from within a listener of another
 * subscription it does not wait, so it cannot deadlock either, and only stops the calls not yet started. A failure of
 * a listener isolated by the observer rule of {@link StoreEvents} (a {@link RuntimeException} or an {@link Error} other
 * than a {@link VirtualMachineError}) is caught, logged as {@code GIT_STORE-109} with the alias, the branch and the
 * class name of the throwable ({@link Class#getName()}), no throwable attached, and never fails the trigger nor keeps
 * the next listener from its call; the commit counts as delivered. A walk that fails to read the clone delivers
 * nothing and keeps the delivered tips.
 */
final class ListenerDispatch implements StoreEvents.Observer {

    private static final CuiLogger LOGGER = new CuiLogger(ListenerDispatch.class);

    /** How deep the current thread is inside listener calls, of every dispatch; absent outside any. */
    private static final ThreadLocal<int[]> CALLBACK_DEPTH = new ThreadLocal<>();

    private final String alias;
    private final Repository repository;
    private final List<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * Creates the dispatch of a clone.
     *
     * @param alias      the alias of the repository, for the log
     * @param repository the opened clone
     * @throws NullPointerException if an argument is {@code null}
     */
    ListenerDispatch(String alias, Repository repository) {
        this.alias = Objects.requireNonNull(alias, "alias");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Subscribes a listener to a branch, starting at the tip the branch has in the clone now, or at the tips of every
     * branch when the clone does not hold it. The subscription is registered first and its start read after, both
     * under its lock, so an event emitted meanwhile is delivered against that start once it is fixed.
     *
     * @param branch   the full name of the branch
     * @param listener the listener
     * @return the subscription
     * @throws NullPointerException if an argument is {@code null}
     */
    CommitListener.Subscription subscribe(String branch, CommitListener listener) {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(listener, "listener");
        return subscribe(branch, listener, () -> {
            // no pause after the start outside the tests
        });
    }

    /**
     * Subscribes a listener like {@link #subscribe(String, CommitListener)}, with a pause point after the start was
     * read and before {@code subscribe} returns, through which a test moves the branch and emits its event in the
     * window where a subscription that registered only after reading its start would miss the commit.
     *
     * @param branch   the full name of the branch
     * @param listener the listener
     * @param pause    run after the registration and the read of the start, with the subscription's lock held
     * @return the subscription
     * @throws NullPointerException if an argument is {@code null}
     */
    CommitListener.Subscription subscribe(String branch, CommitListener listener, Runnable pause) {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(pause, "pause");
        var subscription = new Subscription(branch, listener);
        // a new lock no other thread knows yet: taking it never waits
        subscription.lock.lock();
        boolean started = false;
        try {
            subscriptions.add(subscription);
            subscription.start = start(branch);
            pause.run();
            started = true;
        } finally {
            if (!started) {
                subscriptions.remove(subscription);
            }
            subscription.lock.unlock();
        }
        subscription.drainWhileFree();
        return subscription;
    }

    @Override
    public void moved(List<StoreEvents.Update> updates) {
        for (StoreEvents.Update update : updates) {
            @Nullable String newId = update.newId();
            if (newId == null || !update.ref().startsWith(StoreReferences.HEADS)) {
                continue;
            }
            for (Subscription subscription : subscriptions) {
                if (subscription.branch.equals(update.ref())) {
                    subscription.deliver(ObjectId.fromString(newId));
                }
            }
        }
    }

    /** The start of a subscription: the tip of the branch, else the tip of every branch of the clone. */
    private List<ObjectId> start(String branch) {
        try {
            Ref ref = repository.exactRef(branch);
            @Nullable ObjectId tip = ref == null ? null : ref.getObjectId();
            if (tip != null) {
                return List.of(tip);
            }
            List<ObjectId> tips = new ArrayList<>();
            for (Ref other : repository.getRefDatabase().getRefsByPrefix(StoreReferences.HEADS)) {
                @Nullable ObjectId id = other.getObjectId();
                if (id != null) {
                    tips.add(id);
                }
            }
            return List.copyOf(tips);
        } catch (IOException e) {
            LOGGER.debug("The start of a subscription was not read: %s", e.getClass().getName());
            return List.of();
        }
    }

    /** Whether the current thread is inside a listener call. */
    private static boolean inCallback() {
        int[] depth = CALLBACK_DEPTH.get();
        return depth != null && depth[0] > 0;
    }

    private static void enterCallback() {
        int[] depth = CALLBACK_DEPTH.get();
        if (depth == null) {
            depth = new int[1];
            CALLBACK_DEPTH.set(depth);
        }
        depth[0]++;
    }

    private static void leaveCallback() {
        int[] depth = Objects.requireNonNull(CALLBACK_DEPTH.get(), "entered before");
        depth[0]--;
        if (depth[0] == 0) {
            CALLBACK_DEPTH.remove();
        }
    }

    /** Marks a commit uninteresting; one the clone no longer holds marks nothing. */
    private static void uninteresting(RevWalk walk, ObjectId commit) throws IOException {
        try {
            walk.markUninteresting(walk.parseCommit(commit));
        } catch (MissingObjectException e) {
            LOGGER.debug("A boundary commit is no longer in the clone");
        }
    }

    /** One listener on one branch, with its start and its delivered tips. */
    private final class Subscription implements CommitListener.Subscription {

        private final String branch;
        private final CommitListener listener;
        private final ReentrantLock lock = new ReentrantLock();

        /** The start, fixed once by {@link #subscribe} under {@link #lock} before any walk; guarded by it. */
        private List<ObjectId> start = List.of();

        /** The new ids still to walk, queued by any thread without waiting. */
        private final Queue<ObjectId> pending = new ConcurrentLinkedQueue<>();

        /** The new ids walked, none an ancestor of another; guarded by {@link #lock}. */
        private List<ObjectId> delivered = List.of();

        private volatile boolean closed;

        Subscription(String branch, CommitListener listener) {
            this.branch = branch;
            this.listener = listener;
        }

        /**
         * Delivers the new commits up to a new id. Inside a listener call the thread never waits for the lock: the move
         * is queued, and walked at once only when the lock is free.
         */
        void deliver(ObjectId newId) {
            pending.add(newId);
            if (lock.isHeldByCurrentThread()) {
                return;
            }
            if (!inCallback()) {
                lock.lock();
                try {
                    drain();
                } finally {
                    lock.unlock();
                }
            }
            drainWhileFree();
        }

        /**
         * Drains the queue as long as the lock is free, checking again after each release, so a move a thread queued
         * without waiting is walked by whoever holds the lock last.
         */
        private void drainWhileFree() {
            while (!pending.isEmpty() && lock.tryLock()) {
                try {
                    drain();
                } finally {
                    lock.unlock();
                }
            }
        }

        /** Walks every queued new id; with the lock held. */
        private void drain() {
            for (ObjectId next = pending.poll(); next != null; next = pending.poll()) {
                if (closed) {
                    pending.clear();
                    return;
                }
                walk(next);
            }
        }

        /** Walks the commits up to a new id that the subscription does not have, oldest first, and calls the listener. */
        private void walk(ObjectId newId) {
            List<ObjectId> commits;
            try {
                commits = commits(newId);
            } catch (IOException e) {
                LOGGER.debug("The new commits of %s were not read: %s", branch, e.getClass().getName());
                return;
            }
            for (ObjectId commit : commits) {
                if (closed) {
                    return;
                }
                call(commit);
            }
            if (!commits.isEmpty()) {
                delivered = advanced(newId);
            }
        }

        private void call(ObjectId commit) {
            enterCallback();
            try {
                listener.committed(branch, commit.name());
            }
            // a failing listener never fails the save, release or fetch that triggered it (GDR-STORE-10); the observer
            // rule of StoreEvents isolates every Error but a VirtualMachineError
            // operator-selected: AaIOFP3GAtdQWIZKzKJi: isolation rule of StoreEvents.rethrowIfFatal decided by the operator
            // cui-rewrite:disable InvalidExceptionUsageRecipe
            catch (@SuppressWarnings("java:S1181") RuntimeException | Error e) {
                StoreEvents.rethrowIfFatal(e);
                LOGGER.warn(GitStoreLogMessages.WARN.LISTENER_FAILED, alias, branch, e.getClass().getName());
            } finally {
                leaveCallback();
            }
        }

        /** The commits reachable from the new id and from neither the start nor a delivered tip, oldest first. */
        private List<ObjectId> commits(ObjectId newId) throws IOException {
            List<ObjectId> commits = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repository)) {
                walk.sort(RevSort.TOPO, true);
                walk.sort(RevSort.REVERSE, true);
                walk.markStart(walk.parseCommit(newId));
                for (ObjectId known : start) {
                    uninteresting(walk, known);
                }
                for (ObjectId known : delivered) {
                    uninteresting(walk, known);
                }
                for (RevCommit commit = walk.next(); commit != null; commit = walk.next()) {
                    commits.add(commit.copy());
                }
            }
            return commits;
        }

        /** The delivered tips after a walk up to a new id: the new id and every former tip not an ancestor of it. */
        private List<ObjectId> advanced(ObjectId newId) {
            List<ObjectId> next = new ArrayList<>();
            next.add(newId);
            try (RevWalk walk = new RevWalk(repository)) {
                for (ObjectId known : delivered) {
                    if (!walk.isMergedInto(walk.parseCommit(known), walk.parseCommit(newId))) {
                        next.add(known);
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("The delivered tips of %s were not compared: %s", branch, e.getClass().getName());
                delivered.stream().filter(known -> !next.contains(known)).forEach(next::add);
            }
            return List.copyOf(next);
        }

        /**
         * Unsubscribes. Marks the subscription closed, so a delivery in progress starts no further call, then takes the
         * lock, so the call in progress ends first and no call starts afterwards; from within a listener call of
         * another subscription it does not wait, and stops only the calls not yet started.
         */
        @Override
        public void close() {
            closed = true;
            boolean locked;
            if (inCallback()) {
                locked = lock.tryLock();
            } else {
                lock.lock();
                locked = true;
            }
            try {
                subscriptions.remove(this);
                pending.clear();
            } finally {
                if (locked) {
                    lock.unlock();
                }
            }
        }
    }
}
