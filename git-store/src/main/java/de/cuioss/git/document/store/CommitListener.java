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

/**
 * An in-process listener of the new commits of one branch (GDR-STORE-10), subscribed through
 * {@link StoreWriter#subscribe(String, CommitListener)}.
 * <p>
 * The store calls the listener with each new commit of its branch, oldest first: after every save or release the store
 * pushed onto the branch, and after every fetch that moved the branch. The call happens on the thread of the save,
 * release or fetch, after it released every lock of the store, and after the index was refreshed; the calls of one
 * subscription never overlap. A listener may call the store, a save included: the commits that call adds are delivered
 * after the listener returned. A {@link RuntimeException} of the listener is logged with its class only and never
 * fails the save, release or fetch; the commit counts as delivered. The store keeps no progress of a listener: what
 * must not be lost has to be derivable from the Git state at start.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface CommitListener {

    /**
     * Called with one new commit of the subscribed branch.
     *
     * @param branch   the full name of the branch, such as {@code refs/heads/main}
     * @param commitId the full id of the new commit
     */
    void committed(String branch, String commitId);

    /**
     * The subscription of one listener to one branch; closing it ends the calls.
     *
     * @since 0.1
     */
    interface Subscription extends AutoCloseable {

        /**
         * Unsubscribes the listener: no call starts after this method returned, and closing again has no effect.
         */
        @Override
        void close();
    }
}
