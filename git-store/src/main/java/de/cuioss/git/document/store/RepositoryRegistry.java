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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RemotePolicy;
import org.eclipse.jgit.transport.URIish;

/**
 * The registry of the repositories the store manages (GDR-STORE-1), each addressed by its alias.
 * <p>
 * The registry is immutable and keeps the registration order. It is built by
 * {@link #of(List, RemotePolicy)}, which checks every repository against the rules of the registry and against the
 * one {@link RemotePolicy} of the store: every repository has a remote the policy permits (ADR-0002).
 *
 * @since 0.1
 */
public final class RepositoryRegistry {

    /** The form of an alias: lower-case letters, digits and hyphens. */
    private static final Pattern ALIAS = Pattern.compile("[a-z0-9-]+");

    /** The start of the detail of every refusal that names a repository by its alias. */
    private static final String DETAIL_PREFIX = "repository ";

    /** The repositories by alias, in registration order. */
    private final Map<String, StoreRepository> repositories;

    private RepositoryRegistry(Map<String, StoreRepository> repositories) {
        this.repositories = repositories;
    }

    /**
     * Builds a registry and checks every repository.
     * <p>
     * An alias outside {@code [a-z0-9-]+}, a duplicate alias, a blank confidentiality class, a remote that cannot be
     * parsed as a Git URL and a remote that carries user information are {@link StoreOutcome#MALFORMED}; a remote
     * the policy does not permit is the passed-on {@link GitOutcome#REFUSED_BY_POLICY}. The detail names the alias,
     * or the zero-based position of the repository ({@code repositories[0]}) when its alias is invalid, and never the
     * remote.
     *
     * @param repositories the repositories, in registration order; possibly empty
     * @param policy       the remote policy every remote must satisfy
     * @return {@link StoreOutcome#OK} with the registry, or the first refusal
     * @throws NullPointerException if {@code repositories}, one of its elements or {@code policy} is {@code null}
     */
    public static StoreResult<RepositoryRegistry> of(List<StoreRepository> repositories, RemotePolicy policy) {
        Objects.requireNonNull(repositories, "repositories");
        Objects.requireNonNull(policy, "policy");
        Map<String, StoreRepository> registered = new LinkedHashMap<>();
        int index = 0;
        for (StoreRepository repository : repositories) {
            Objects.requireNonNull(repository, "repository");
            String alias = repository.alias();
            if (!ALIAS.matcher(alias).matches()) {
                return StoreResult.of(StoreOutcome.MALFORMED, "repositories[" + index + "]: invalid alias");
            }
            index++;
            if (registered.containsKey(alias)) {
                return StoreResult.of(StoreOutcome.MALFORMED, detail(alias, "duplicate alias"));
            }
            if (repository.confidentiality().isBlank()) {
                return StoreResult.of(StoreOutcome.MALFORMED, detail(alias, "blank confidentiality"));
            }
            URIish remote;
            try {
                remote = new URIish(repository.remote());
            } catch (URISyntaxException | NumberFormatException e) {
                // JGit 7.8.0 throws NumberFormatException, not URISyntaxException, for a port beyond int
                return StoreResult.of(StoreOutcome.MALFORMED, detail(alias, "malformed remote"));
            }
            if (remote.getUser() != null || remote.getPass() != null) {
                return StoreResult.of(StoreOutcome.MALFORMED, detail(alias, "remote carries user information"));
            }
            if (!policy.permits(remote)) {
                return StoreResult.passOn(GitResult.of(GitOutcome.REFUSED_BY_POLICY,
                        detail(alias, "remote not permitted by the remote policy")));
            }
            registered.put(alias, repository);
        }
        return StoreResult.ok(new RepositoryRegistry(registered));
    }

    /** Builds the detail of a refused repository: {@code repository <alias>: <problem>}, never naming the remote. */
    private static String detail(String alias, String problem) {
        return DETAIL_PREFIX + alias + ": " + problem;
    }

    /**
     * Returns the registered repositories.
     *
     * @return the repositories in registration order, unmodifiable
     */
    public List<StoreRepository> repositories() {
        return List.copyOf(repositories.values());
    }

    /**
     * Finds a repository by its alias.
     *
     * @param alias the alias
     * @return the repository, or empty when no repository has that alias
     */
    public Optional<StoreRepository> find(String alias) {
        return Optional.ofNullable(repositories.get(alias));
    }

    /**
     * Renders the registry by its repositories, each without user information.
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "RepositoryRegistry" + repositories.values();
    }
}
