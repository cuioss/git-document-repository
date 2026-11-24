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

import java.net.URISyntaxException;
import java.time.Period;
import java.util.Objects;


import org.eclipse.jgit.transport.URIish;
import org.jspecify.annotations.Nullable;

/**
 * A repository of the registry (GDR-STORE-1): its stable alias, its remote, its confidentiality class and its optional
 * retention period.
 * <p>
 * The record checks only that the alias, the remote and the confidentiality class are present; the rules of the
 * registry (the form and the uniqueness of the alias, the remote and the remote policy) are checked by
 * {@link RepositoryRegistry#of(java.util.List, de.cuioss.git.document.core.RemotePolicy)}. The store only reports the
 * retention period; the operator deletes the repository after it.
 *
 * @param alias           the stable alias, used to address the repository and in links
 * @param remote          the remote: an {@code https} URL or the path of a local bare repository
 * @param confidentiality the confidentiality class, a name the application maps to access rules
 * @param retention       the retention period; {@code null} when the repository has none
 * @since 0.1
 */
public record StoreRepository(String alias, String remote, String confidentiality, @Nullable Period retention) {

    /**
     * Checks that the alias, the remote and the confidentiality class are present.
     *
     * @param alias           the alias
     * @param remote          the remote
     * @param confidentiality the confidentiality class
     * @param retention       the retention period, or {@code null}
     * @throws NullPointerException if {@code alias}, {@code remote} or {@code confidentiality} is {@code null}
     */
    public StoreRepository {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(remote, "remote");
        Objects.requireNonNull(confidentiality, "confidentiality");
    }

    /**
     * Renders the repository with its remote reduced to scheme, host, port and path (GDR-NFR-3): the user, the
     * password, the query and the fragment of the remote are never rendered, so a token carried in any of them does
     * not reach a log record. A remote that cannot be parsed is rendered as {@code (malformed)}.
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "StoreRepository[alias=" + alias + ", remote=" + rendered(remote) + ", confidentiality="
                + confidentiality + ", retention=" + retention + "]";
    }

    /**
     * Renders a remote as scheme, host, port and path only.
     * <p>
     * JGit 7.8.0 {@link URIish} knows no query and no fragment: it keeps both in its path. The path is therefore cut at
     * its first {@code ?} or {@code #} before it is rendered.
     *
     * @param remote the remote
     * @return the scheme, host, port and path of the remote, or {@code (malformed)} when it cannot be parsed
     */
    private static String rendered(String remote) {
        try {
            URIish parsed = new URIish(remote);
            // URIish.toString renders the raw path of a URL with a scheme and the decoded path of any other remote
            String path = parsed.getScheme() != null ? parsed.getRawPath() : parsed.getPath();
            return parsed.setUser(null).setPass(null).setPath(withoutQueryAndFragment(path)).toString();
        } catch (URISyntaxException | NumberFormatException e) {
            // JGit 7.8.0 throws NumberFormatException, not URISyntaxException, for a port beyond int
            return "(malformed)";
        }
    }

    /**
     * Cuts a path at its first {@code ?} or {@code #}.
     *
     * @param path the path, or {@code null}
     * @return the path before its query and fragment, or {@code null} for a {@code null} path
     */
    private static @Nullable String withoutQueryAndFragment(@Nullable String path) {
        if (path == null) {
            return null;
        }
        int end = path.length();
        int query = path.indexOf('?');
        if (query >= 0) {
            end = query;
        }
        int fragment = path.indexOf('#');
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        return path.substring(0, end);
    }
}
