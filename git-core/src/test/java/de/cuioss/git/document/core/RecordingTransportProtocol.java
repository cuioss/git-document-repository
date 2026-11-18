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

import java.util.Optional;


import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportProtocol;
import org.eclipse.jgit.transport.URIish;
import org.jspecify.annotations.Nullable;

/**
 * A JGit transport protocol for tests that handles every URL whose path contains a marker, contacts nothing, and
 * records the URL and the credentials provider a connection would have used, then fails the connection.
 * <p>
 * It is registered with JGit 7.8.0 {@link Transport#register(TransportProtocol)}, ahead of the built-in protocols, and
 * unregistered on {@link #close()}. JGit holds a registered protocol by a weak reference only, so the test keeps this
 * instance for as long as it is needed.
 */
final class RecordingTransportProtocol extends TransportProtocol implements AutoCloseable {

    private final String marker;
    private final @Nullable URIish destination;
    private boolean connected;
    private @Nullable URIish uri;
    private @Nullable CredentialsProvider credentials;

    /**
     * Creates and registers the protocol.
     *
     * @param marker the text whose presence in a URL's path selects this protocol
     */
    RecordingTransportProtocol(String marker) {
        this(marker, null);
    }

    /**
     * Creates and registers a protocol whose transports connect to another destination than the URL they were opened
     * for, as a configuration changed between a check and the use would make them.
     *
     * @param marker      the text whose presence in a URL's path selects this protocol
     * @param destination the URL every opened transport reports as its destination; {@code null} for the opened URL
     */
    RecordingTransportProtocol(String marker, @Nullable URIish destination) {
        this.marker = marker;
        this.destination = destination;
        Transport.register(this);
    }

    @Override
    public String getName() {
        return "recording";
    }

    @Override
    public boolean canHandle(URIish candidate, Repository local, String remoteName) {
        return candidate.getPath() != null && candidate.getPath().contains(marker);
    }

    @Override
    public Transport open(URIish target, Repository local, String remoteName) {
        return new Transport(local, destination == null ? target : destination) {

            @Override
            public FetchConnection openFetch() throws NotSupportedException {
                throw attempt(getURI(), getCredentialsProvider());
            }

            @Override
            public PushConnection openPush() throws NotSupportedException {
                throw attempt(getURI(), getCredentialsProvider());
            }

            @Override
            public void close() {
                // nothing was opened
            }
        };
    }

    private NotSupportedException attempt(URIish used, @Nullable CredentialsProvider provider) {
        connected = true;
        uri = used;
        credentials = provider;
        return new NotSupportedException("the recording transport contacts nothing");
    }

    /**
     * Tells whether a connection was attempted.
     *
     * @return whether a fetch or push connection was opened
     */
    boolean connected() {
        return connected;
    }

    /**
     * Returns the URL of the attempted connection.
     *
     * @return the URL, empty when no connection was attempted
     */
    Optional<URIish> uri() {
        return Optional.ofNullable(uri);
    }

    /**
     * Returns the credentials provider of the attempted connection.
     *
     * @return the provider, empty when the connection had none or none was attempted
     */
    Optional<CredentialsProvider> credentials() {
        return Optional.ofNullable(credentials);
    }

    @Override
    public void close() {
        Transport.unregister(this);
    }
}
