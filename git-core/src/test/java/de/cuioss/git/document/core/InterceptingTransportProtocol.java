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
package de.cuioss.git.document.core;

import java.io.IOException;


import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportProtocol;
import org.eclipse.jgit.transport.URIish;

/**
 * A JGit transport protocol for tests that handles every URL whose path contains a marker: before a fetch connection
 * is opened it runs an interceptor on the fetching repository, then fetches from a real local repository, so a test
 * can change the fetching side at the moment the fetch starts (a concurrent clone, a held lock) or abort it with an
 * exception of its choice.
 * <p>
 * It is registered with JGit 7.8.0 {@link Transport#register(TransportProtocol)}, ahead of the built-in protocols, and
 * unregistered on {@link #close()}; JGit holds a registered protocol by a weak reference only, so the test keeps this
 * instance for as long as it is needed. The fetch itself runs through the built-in local transport of the delegate URL
 * ({@code TransportLocal}), whose path carries no marker.
 */
final class InterceptingTransportProtocol extends TransportProtocol implements AutoCloseable {

    /** What runs on the fetching repository before the fetch connection is opened. */
    @FunctionalInterface
    interface Interceptor {

        /**
         * Acts on the fetching repository.
         *
         * @param local the repository the fetch writes into
         * @throws IOException if the action fails
         */
        void beforeFetch(Repository local) throws IOException;
    }

    private final String marker;
    private final URIish delegate;
    private final Interceptor interceptor;

    /**
     * Creates and registers the protocol.
     *
     * @param marker      the text whose presence in a URL's path selects this protocol
     * @param delegate    the URL of the local repository the fetch reads from
     * @param interceptor what runs before the fetch connection is opened
     */
    InterceptingTransportProtocol(String marker, URIish delegate, Interceptor interceptor) {
        this.marker = marker;
        this.delegate = delegate;
        this.interceptor = interceptor;
        Transport.register(this);
    }

    @Override
    public String getName() {
        return "intercepting";
    }

    @Override
    public boolean canHandle(URIish candidate, Repository local, String remoteName) {
        return candidate.getPath() != null && candidate.getPath().contains(marker);
    }

    @Override
    public Transport open(URIish target, Repository local, String remoteName) {
        return new Transport(local, target) {

            @Override
            public FetchConnection openFetch() throws TransportException, NotSupportedException {
                try {
                    interceptor.beforeFetch(local);
                } catch (IOException e) {
                    throw new TransportException(target, "the interceptor failed", e);
                }
                return Transport.open(local, delegate).openFetch();
            }

            @Override
            public PushConnection openPush() throws NotSupportedException {
                throw new NotSupportedException("the intercepting transport fetches only");
            }

            @Override
            public void close() {
                // the local transport of the delegate holds its resources per connection
            }
        };
    }

    @Override
    public void close() {
        Transport.unregister(this);
    }
}
