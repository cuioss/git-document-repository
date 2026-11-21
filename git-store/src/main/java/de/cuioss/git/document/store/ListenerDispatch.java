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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
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
 * Every subscription keeps an in-memory cursor: the last commit of its branch it delivered, or the tip of the branch in
 * the clone when it was subscribed. When an event moves the branch to a new id, the dispatch walks the commits
 * reachable from that id and not from the cursor, oldest first ({@code RevSort.TOPO} with {@code RevSort.REVERSE},
 * JGit 7.8.0), and calls the listener with each; without a cursor, the boundary is the old id of the update, else the
 * tips of every other branch of the clone, so a branch the event created delivers its own commits, not the history it
 * forked from. The cursor moves to each commit before the listener is called, so a failing listener never gets the
 * commit again. A cursor is never persisted: a new writer starts its subscriptions at the tips of the clone and delivers
 * nothing older.
 * <p>
 * The delivery of one subscription is serialised by its lock, so concurrent triggers deliver every commit once, in
 * order: a later trigger walks from the cursor an earlier one left. A listener that calls the store on its own thread,
 * a save included, re-enters the dispatch; that delivery is queued and runs once the listener returned, so the listener
 * gets its own new commits after its call. A {@link RuntimeException} of a listener is caught, logged as
 * {@code GIT_STORE-109} with the alias, the branch and the class of the exception, no throwable attached, and never
 * fails the trigger. A walk that fails to read the clone delivers nothing and keeps the cursor.
 */
final class ListenerDispatch implements StoreEvents.Observer {

    private static final CuiLogger LOGGER = new CuiLogger(ListenerDispatch.class);

    /** The prefix of every branch. */
    private static final String HEADS = "refs/heads/";

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
     * Subscribes a listener to a branch, its cursor at the tip the branch has in the clone now.
     *
     * @param branch   the full name of the branch
     * @param listener the listener
     * @return the subscription
     * @throws NullPointerException if an argument is {@code null}
     */
    CommitListener.Subscription subscribe(String branch, CommitListener listener) {
        Objects.requireNonNull(branch, "branch");
        Objects.requireNonNull(listener, "listener");
        var subscription = new Subscription(branch, listener, tip(branch));
        subscriptions.add(subscription);
        return subscription;
    }

    @Override
    public void moved(List<StoreEvents.Update> updates) {
        for (StoreEvents.Update update : updates) {
            @Nullable String newId = update.newId();
            if (newId == null || !update.ref().startsWith(HEADS)) {
                continue;
            }
            for (Subscription subscription : subscriptions) {
                if (subscription.branch.equals(update.ref())) {
                    subscription.deliver(new Move(update.oldId(), newId));
                }
            }
        }
    }

    private @Nullable ObjectId tip(String branch) {
        try {
            Ref ref = repository.exactRef(branch);
            return ref == null ? null : ref.getObjectId();
        } catch (IOException e) {
            LOGGER.debug("The tip of a subscribed branch was not read: %s", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * One move of a branch.
     *
     * @param oldId the id the branch had before; {@code null} when the event created it
     * @param newId the id the branch has now
     */
    private record Move(@Nullable String oldId, String newId) {
    }

    /** One listener on one branch, with its in-memory cursor. */
    private final class Subscription implements CommitListener.Subscription {

        private final String branch;
        private final CommitListener listener;
        private final ReentrantLock lock = new ReentrantLock();

        /** The moves still to deliver; guarded by {@link #lock}. */
        private final Deque<Move> pending = new ArrayDeque<>();

        /** The last commit delivered; guarded by {@link #lock}. */
        private @Nullable ObjectId cursor;

        private volatile boolean closed;

        Subscription(String branch, CommitListener listener, @Nullable ObjectId cursor) {
            this.branch = branch;
            this.listener = listener;
            this.cursor = cursor;
        }

        /**
         * Delivers the commits of a move. A call of the delivering thread from within a listener only queues the move;
         * the outer delivery runs it once the listener returned.
         */
        void deliver(Move move) {
            if (lock.isHeldByCurrentThread()) {
                pending.add(move);
                return;
            }
            lock.lock();
            try {
                pending.add(move);
                for (Move next = pending.poll(); next != null && !closed; next = pending.poll()) {
                    walk(next);
                }
                pending.clear();
            } finally {
                lock.unlock();
            }
        }

        /** Walks the commits of one move from the cursor, oldest first, and calls the listener with each. */
        private void walk(Move move) {
            List<ObjectId> commits;
            try {
                commits = commits(move);
            } catch (IOException e) {
                LOGGER.debug("The new commits of %s were not read: %s", branch, e.getClass().getSimpleName());
                return;
            }
            for (ObjectId commit : commits) {
                if (closed) {
                    return;
                }
                cursor = commit;
                try {
                    listener.committed(branch, commit.name());
                }
                /*TODO: Catch specific not RuntimeException. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
                catch (RuntimeException e) {
                    LOGGER.warn(GitStoreLogMessages.WARN.LISTENER_FAILED, alias, branch, e.getClass().getSimpleName());
                }
            }
        }

        /** The commits reachable from the new id and not from the boundary, oldest first. */
        private List<ObjectId> commits(Move move) throws IOException {
            List<ObjectId> commits = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repository)) {
                walk.sort(RevSort.TOPO, true);
                walk.sort(RevSort.REVERSE, true);
                walk.markStart(walk.parseCommit(ObjectId.fromString(move.newId())));
                markBoundary(walk, move);
                for (RevCommit commit = walk.next(); commit != null; commit = walk.next()) {
                    commits.add(commit.copy());
                }
            }
            return commits;
        }

        /**
         * Marks what the subscription already has: the cursor; without one the old id of the move; without that the
         * tips of every other branch.
         */
        private void markBoundary(RevWalk walk, Move move) throws IOException {
            @Nullable ObjectId known = cursor;
            @Nullable String oldId = move.oldId();
            if (known == null && oldId != null) {
                known = ObjectId.fromString(oldId);
            }
            if (known != null) {
                uninteresting(walk, known);
                return;
            }
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(HEADS)) {
                @Nullable ObjectId other = ref.getObjectId();
                if (!branch.equals(ref.getName()) && other != null) {
                    uninteresting(walk, other);
                }
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

        @Override
        public void close() {
            closed = true;
            subscriptions.remove(this);
        }
    }
}
