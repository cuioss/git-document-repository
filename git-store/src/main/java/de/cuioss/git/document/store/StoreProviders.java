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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Function;


import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The providers of the three service interfaces of the store, selected at start by the ids the configuration names.
 * <p>
 * Exactly one {@link SigningKeySource} is selected, by {@code signing.key-source}, and exactly one
 * {@link PersonDirectory}, by {@code directory.persons.provider}; at most one {@link AnchorProvider} is selected, by
 * {@code anchor.provider}, and none when that key is absent, so the store anchors nothing (GDR-STORE-15). A configured
 * id that no provider carries, or that two providers carry, stops the start as {@link StoreOutcome#MALFORMED} naming
 * the service interface and the configuration key, never the configured id (GDR-NFR-3); two providers with an id nobody
 * configured do not stop it.
 *
 * @param signing the active signing key source
 * @param persons the active person directory
 * @param anchor  the active anchor provider; {@code null} when {@code anchor.provider} is absent
 * @since 0.1
 */
public record StoreProviders(SigningKeySource signing, PersonDirectory persons, @Nullable AnchorProvider anchor) {

    private static final CuiLogger LOGGER = new CuiLogger(StoreProviders.class);

    /** The rendering of an absent anchor provider in the log record {@code GIT_STORE-1}. */
    private static final String NO_ANCHOR_PROVIDER = "none";

    /**
     * Checks that the signing key source and the person directory are present.
     *
     * @param signing the signing key source
     * @param persons the person directory
     * @param anchor  the anchor provider, or {@code null}
     * @throws NullPointerException if {@code signing} or {@code persons} is {@code null}
     */
    public StoreProviders {
        Objects.requireNonNull(signing, "signing");
        Objects.requireNonNull(persons, "persons");
    }

    /**
     * Finds the providers through {@link ServiceLoader} and selects the configured ones.
     * <p>
     * The providers are those {@code ServiceLoader.load} finds for each interface, through the {@code uses}
     * directives of the module or the {@code META-INF/services} files on the class path. A
     * {@link ServiceConfigurationError} while loading a provider is {@link StoreOutcome#MALFORMED} naming the service
     * interface and is logged as {@code GIT_STORE-100}; otherwise the selection is that of
     * {@link #select(StoreConfiguration, Iterable, Iterable, Iterable)}.
     *
     * @param configuration the configuration of the store
     * @return {@link StoreOutcome#OK} with the selected providers, or {@link StoreOutcome#MALFORMED}
     * @throws NullPointerException if {@code configuration} is {@code null}
     */
    public static StoreResult<StoreProviders> load(StoreConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        var signing = loadAll(SigningKeySource.class);
        if (!signing.isOk()) {
            return refuse(signing.detail());
        }
        var anchors = loadAll(AnchorProvider.class);
        if (!anchors.isOk()) {
            return refuse(anchors.detail());
        }
        var persons = loadAll(PersonDirectory.class);
        if (!persons.isOk()) {
            return refuse(persons.detail());
        }
        return select(configuration, Objects.requireNonNull(signing.value()), Objects.requireNonNull(anchors.value()),
                Objects.requireNonNull(persons.value()));
    }

    /**
     * Selects the configured providers among explicit candidates.
     * <p>
     * A success is logged as {@code GIT_STORE-1} with the three ids, a refusal as {@code GIT_STORE-100} with its
     * detail.
     *
     * @param configuration the configuration of the store
     * @param signing       the candidate signing key sources
     * @param anchors       the candidate anchor providers
     * @param persons       the candidate person directories
     * @return {@link StoreOutcome#OK} with the selected providers, or {@link StoreOutcome#MALFORMED} naming the service
     * interface and the configuration key whose id no candidate or more than one candidate carries
     * @throws NullPointerException if an argument or a candidate is {@code null}
     */
    public static StoreResult<StoreProviders> select(StoreConfiguration configuration,
            Iterable<? extends SigningKeySource> signing, Iterable<? extends AnchorProvider> anchors,
            Iterable<? extends PersonDirectory> persons) {
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(signing, "signing");
        Objects.requireNonNull(anchors, "anchors");
        Objects.requireNonNull(persons, "persons");
        var source = unique(SigningKeySource.class, StoreConfiguration.SIGNING_KEY_SOURCE,
                configuration.signing().keySource(), signing, SigningKeySource::id);
        if (!source.isOk()) {
            return refuse(source.detail());
        }
        var directory = unique(PersonDirectory.class, StoreConfiguration.PERSONS_PROVIDER,
                configuration.directory().personsProvider(), persons, PersonDirectory::id);
        if (!directory.isOk()) {
            return refuse(directory.detail());
        }
        @Nullable AnchorProvider anchor = null;
        String anchorId = configuration.anchor().provider();
        if (anchorId != null) {
            var provider = unique(AnchorProvider.class, StoreConfiguration.ANCHOR_PROVIDER, anchorId, anchors,
                    AnchorProvider::id);
            if (!provider.isOk()) {
                return refuse(provider.detail());
            }
            anchor = provider.value();
        }
        var providers = new StoreProviders(Objects.requireNonNull(source.value()),
                Objects.requireNonNull(directory.value()), anchor);
        LOGGER.info(GitStoreLogMessages.INFO.PROVIDERS_SELECTED, providers.signing().id(), providers.persons().id(),
                anchor == null ? NO_ANCHOR_PROVIDER : anchor.id());
        return StoreResult.ok(providers);
    }

    /**
     * Picks the one candidate carrying the configured id.
     * <p>
     * The detail of a refusal names the service interface and the configuration key, never the configured id: the id
     * is a configured value, and a value may be secret material (GDR-NFR-3).
     *
     * @param service    the service interface, named in the detail
     * @param key        the configuration key the id is read from, named in the detail
     * @param id         the configured id
     * @param candidates the candidates
     * @param idOf       the id of a candidate
     * @param <S>        the service type
     * @return {@link StoreOutcome#OK} with the candidate, or {@link StoreOutcome#MALFORMED} when no candidate or more
     * than one candidate carries the id
     */
    private static <S> StoreResult<S> unique(Class<S> service, String key, String id,
            Iterable<? extends S> candidates, Function<S, String> idOf) {
        @Nullable S found = null;
        for (S candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (id.equals(idOf.apply(candidate))) {
                if (found != null) {
                    return StoreResult.of(StoreOutcome.MALFORMED,
                            service.getSimpleName() + ": more than one provider with the id of " + key);
                }
                found = candidate;
            }
        }
        if (found == null) {
            return StoreResult.of(StoreOutcome.MALFORMED,
                    service.getSimpleName() + ": no provider with the id of " + key);
        }
        return StoreResult.ok(found);
    }

    /**
     * Instantiates every provider {@link ServiceLoader} finds for a service interface.
     *
     * @param service the service interface
     * @param <S>     the service type
     * @return {@link StoreOutcome#OK} with the providers, possibly empty, or {@link StoreOutcome#MALFORMED} naming the
     * service interface when a provider cannot be loaded
     */
    private static <S> StoreResult<List<S>> loadAll(Class<S> service) {
        List<S> providers = new ArrayList<>();
        try {
            for (S provider : ServiceLoader.load(service)) {
                providers.add(provider);
            }
        } catch (ServiceConfigurationError e) {
            // neither the error nor its message is logged: a failing provider may name a credential in either
            LOGGER.debug("Loading a provider of %s failed with %s", service.getName(), e.getClass().getName());
            return StoreResult.of(StoreOutcome.MALFORMED, service.getSimpleName() + ": a provider cannot be loaded");
        }
        return StoreResult.ok(providers);
    }

    private static StoreResult<StoreProviders> refuse(@Nullable String detail) {
        var refusal = StoreResult.<StoreProviders>of(StoreOutcome.MALFORMED, Objects.requireNonNull(detail));
        LOGGER.warn(GitStoreLogMessages.WARN.START_REFUSED, refusal.detail());
        return refusal;
    }
}
