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
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UncheckedIOException;
import java.time.Duration;


import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.BasePackPushConnection;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PackTransport;
import org.eclipse.jgit.transport.PushConnection;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.TransportProtocol;
import org.eclipse.jgit.transport.URIish;
import org.jspecify.annotations.Nullable;

/**
 * A JGit transport protocol for tests that handles every URL whose path contains a marker: a push to it is received
 * by a JGit 7.8.0 {@link ReceivePack} over a served repository whose pre-receive hook refuses every command with a
 * result and a message of the test's choice, so the client reads the status report line {@code ng <ref> <message>}
 * that a remote answers for exactly that refusal.
 * <p>
 * It is registered with JGit 7.8.0 {@link Transport#register(TransportProtocol)}, ahead of the built-in protocols, and
 * unregistered on {@link #close()}; JGit holds a registered protocol by a weak reference only, so the test keeps this
 * instance for as long as it is needed. The connection is built like JGit's package-private
 * {@code InternalPushConnection}: the receiving side runs on its own thread, joined through pipes. A fetch is not
 * supported.
 * <p>
 * It stays a subclass instead of an EasyMock double: the push reads the real advertisement of the served repository
 * and the real status report that {@code BasePackPushConnection.readStatusReport} parses, which a mock would replace.
 */
final class RefusingReceiveProtocol extends TransportProtocol implements AutoCloseable {

    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(10);

    private final String marker;
    private final Repository served;
    private final ReceiveCommand.Result result;
    private final @Nullable String message;

    /**
     * Creates and registers the protocol.
     *
     * @param marker  the text whose presence in a URL's path selects this protocol
     * @param served  the repository the push is received into; it is not closed
     * @param result  the result the pre-receive hook sets on every command
     * @param message the message of the result; {@code null} for the default text of the result
     */
    RefusingReceiveProtocol(String marker, Repository served, ReceiveCommand.Result result,
            @Nullable String message) {
        this.marker = marker;
        this.served = served;
        this.result = result;
        this.message = message;
        Transport.register(this);
    }

    @Override
    public String getName() {
        return "refusing-receive";
    }

    @Override
    public boolean canHandle(URIish candidate, Repository local, String remoteName) {
        return candidate.getPath() != null && candidate.getPath().contains(marker);
    }

    @Override
    public Transport open(URIish target, Repository local, String remoteName) {
        return new RefusingTransport(local, target);
    }

    @Override
    public void close() {
        Transport.unregister(this);
    }

    private final class RefusingTransport extends Transport implements PackTransport {

        RefusingTransport(Repository local, URIish target) {
            super(local, target);
        }

        @Override
        public FetchConnection openFetch() throws NotSupportedException {
            throw new NotSupportedException("the refusing transport pushes only");
        }

        @Override
        public PushConnection openPush() throws TransportException {
            return new RefusingPushConnection(this);
        }

        @Override
        public void close() {
            // the connection holds its resources itself
        }
    }

    private final class RefusingPushConnection extends BasePackPushConnection {

        private final Thread worker;

        RefusingPushConnection(PackTransport transport) throws TransportException {
            super(transport);
            PipedInputStream toClient = new PipedInputStream();
            PipedInputStream toServer = new PipedInputStream();
            PipedOutputStream fromServer;
            PipedOutputStream fromClient;
            try {
                fromServer = new PipedOutputStream(toClient);
                fromClient = new PipedOutputStream(toServer);
            } catch (IOException e) {
                throw new TransportException(uri, "the pipes cannot be connected", e);
            }
            var receivePack = new ReceivePack(served);
            receivePack.setPreReceiveHook((pack, commands) -> commands
                    .forEach(command -> command.setResult(result, message)));
            worker = new Thread(() -> {
                try (toServer; fromServer) {
                    receivePack.receive(toServer, fromServer, null);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, "refusing-receive-pack");
            worker.setDaemon(true);
            worker.start();
            init(toClient, fromClient);
            readAdvertisedRefs();
        }

        @Override
        public void close() {
            super.close();
            try {
                worker.join(JOIN_TIMEOUT);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
