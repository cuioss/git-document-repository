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

import java.net.URI;
import java.util.Locale;


import org.eclipse.jgit.transport.URIish;
import org.jspecify.annotations.Nullable;

/**
 * Which remote URLs the transport may contact (GDR-CORE-2).
 * <p>
 * {@link #httpsOrigin(URI)} admits only {@code https} URLs of that host and port. {@link #localOnly()} admits
 * repositories on the local disk ({@code file://} or a plain path), which involve no network and no credential, and
 * refuses every network remote.
 *
 * @param origin the permitted HTTPS origin; {@code null} for local repositories only
 * @since 0.1
 */
public record RemotePolicy(@Nullable URI origin) {

    /**
     * Creates a policy admitting exactly one HTTPS origin.
     *
     * @param origin the HTTPS origin, e.g. {@code https://github.com}
     * @return a policy admitting exactly that origin
     * @throws IllegalArgumentException if the origin is not an absolute {@code https} URI with a host
     */
    public static RemotePolicy httpsOrigin(URI origin) {
        if (!"https".equalsIgnoreCase(origin.getScheme()) || origin.getHost() == null) {
            throw new IllegalArgumentException("not an https origin: " + origin);
        }
        return new RemotePolicy(origin);
    }

    /**
     * Creates a policy admitting local repositories only.
     *
     * @return a policy admitting local repositories only
     */
    public static RemotePolicy localOnly() {
        return new RemotePolicy(null);
    }

    /**
     * Tells whether the transport may contact a remote URL.
     *
     * @param uri the effective remote URL (after {@code insteadOf} rewriting)
     * @return whether the transport may contact it
     */
    public boolean permits(URIish uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (origin == null) {
            return uri.getHost() == null && (scheme.isEmpty() || "file".equals(scheme));
        }
        URI allowed = origin;
        return "https".equals(scheme) && allowed.getHost().equalsIgnoreCase(uri.getHost())
                && port(allowed.getPort()) == port(uri.getPort());
    }

    private static int port(int port) {
        return port == -1 ? 443 : port;
    }
}
