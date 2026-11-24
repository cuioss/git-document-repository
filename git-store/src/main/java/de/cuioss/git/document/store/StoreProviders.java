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

import java.time.Clock;
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
 * <p>
 * The anchor provider {@value SignedAnchorProvider#PROVIDER_ID} is built in: when {@code anchor.provider} names it, the store
 * builds a {@link SignedAnchorProvider} from the section {@code anchor.signed} (GDR-STORE-18), its anchor key taken from
 * the candidate signing key source that {@code anchor.signed.key-source} names, and a candidate anchor provider passed
 * to {@link #select(StoreConfiguration, Iterable, Iterable, Iterable)} that also carries that id is a second provider
 * with the id of {@code anchor.provider}. {@link #load(StoreConfiguration)} instantiates no anchor provider in that
 * case, nor when {@code anchor.provider} is absent.
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

    /** The key of the commit-signing key reference. */
    private static final String SIGNING_KEY = "signing.key";

    /** The key of the anchor key source of the provider {@code signed}. */
    private static final String SIGNED_KEY_SOURCE = "anchor.signed.key-source";

    /** The key of the anchor key reference of the provider {@code signed}. */
    private static final String SIGNED_KEY = "anchor.signed.key";

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
     * directives of the module or the {@code META-INF/services} files on the class path. The {@link AnchorProvider}
     * candidates are instantiated only when {@code anchor.provider} names an id other than
     * {@value SignedAnchorProvider#PROVIDER_ID} (GDR-STORE-15): a start that anchors nothing, or that anchors with the
     * built-in provider, never instantiates one, so a broken anchor provider on the class path cannot stop it. A
     * {@link ServiceConfigurationError} or a {@link LinkageError} while loading a provider is
     * {@link StoreOutcome#MALFORMED} naming the service interface and is logged as {@code GIT_STORE-100}; otherwise
     * the selection is that of {@link #select(StoreConfiguration, Iterable, Iterable, Iterable)}.
     *
     * @param configuration the configuration of the store
     * @return {@link StoreOutcome#MALFORMED} for a provider that cannot be loaded, otherwise the result of
     * {@link #select(StoreConfiguration, Iterable, Iterable, Iterable)}
     * @throws NullPointerException if {@code configuration} is {@code null}
     */
    public static StoreResult<StoreProviders> load(StoreConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        var signing = loadAll(SigningKeySource.class);
        if (!signing.isOk()) {
            return refuse(signing.detail());
        }
        List<AnchorProvider> anchors = List.of();
        String anchorId = configuration.anchor().provider();
        if (anchorId != null && !SignedAnchorProvider.PROVIDER_ID.equals(anchorId)) {
            var loaded = loadAll(AnchorProvider.class);
            if (!loaded.isOk()) {
                return refuse(loaded.detail());
            }
            anchors = Objects.requireNonNull(loaded.value());
        }
        var persons = loadAll(PersonDirectory.class);
        if (!persons.isOk()) {
            return refuse(persons.detail());
        }
        return select(configuration, Objects.requireNonNull(signing.value()), anchors,
                Objects.requireNonNull(persons.value()));
    }

    /**
     * Selects the configured providers among explicit candidates.
     * <p>
     * A success is logged as {@code GIT_STORE-1} with the three ids, a refusal as {@code GIT_STORE-100} with its
     * detail.
     * <p>
     * When {@code anchor.provider} is {@value SignedAnchorProvider#PROVIDER_ID}, the anchor key source is selected among the
     * signing candidates by the effective {@code anchor.signed.key-source} (default
     * {@value StoreConfiguration.Signing#DEFAULT_KEY_SOURCE}), {@code anchor.signed.key} is required (detail
     * {@code anchor.signed.key is missing}), and the start is refused when the public key of the anchor key equals the
     * public key of the commit-signing key ({@code anchor.signed.key equals the commit-signing key}); both public keys
     * are read from their sources here, once. No detail names a key reference or key material.
     * <p>
     * The id of every candidate the selection consults is read once and guarded: a candidate whose {@code id()} throws
     * a {@link RuntimeException} or a {@link LinkageError}, or returns {@code null}, ends the selection as
     * {@link StoreOutcome#MALFORMED} with {@code <Interface>: a provider id cannot be read}, and only the class of the
     * candidate and of the throwable are logged, never its message (GDR-GEN-3, GDR-NFR-3).
     *
     * @param configuration the configuration of the store
     * @param signing       the candidate signing key sources
     * @param anchors       the candidate anchor providers
     * @param persons       the candidate person directories
     * @return {@link StoreOutcome#OK} with the selected providers; {@link StoreOutcome#MALFORMED} naming the service
     * interface for a candidate whose id cannot be read, naming the service
     * interface and the configuration key whose id no candidate or more than one candidate carries, or the refusal of
     * the provider {@value SignedAnchorProvider#PROVIDER_ID}; {@link StoreOutcome#UNAVAILABLE} or {@link StoreOutcome#REFUSED}
     * with {@code <key>: key source unavailable} or {@code <key>: key source refused} when a public key the provider
     * {@value SignedAnchorProvider#PROVIDER_ID} needs cannot be read, the key being {@code anchor.signed.key} or
     * {@code signing.key}
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
        SigningKeySource signingSource = Objects.requireNonNull(source.value());
        @Nullable AnchorProvider anchor = null;
        String anchorId = configuration.anchor().provider();
        if (SignedAnchorProvider.PROVIDER_ID.equals(anchorId)) {
            var provider = signed(configuration, signingSource, signing, anchors);
            if (!provider.isOk()) {
                return refuse(provider);
            }
            anchor = provider.value();
        } else if (anchorId != null) {
            var provider = unique(AnchorProvider.class, StoreConfiguration.ANCHOR_PROVIDER, anchorId, anchors,
                    AnchorProvider::id);
            if (!provider.isOk()) {
                return refuse(provider.detail());
            }
            anchor = provider.value();
        }
        var providers = new StoreProviders(signingSource, Objects.requireNonNull(directory.value()), anchor);
        // the ids the selected candidates were matched by; a candidate's id() is not called a second time
        LOGGER.info(GitStoreLogMessages.INFO.PROVIDERS_SELECTED, configuration.signing().keySource(),
                configuration.directory().personsProvider(), anchorId == null ? NO_ANCHOR_PROVIDER : anchorId);
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
     * @return {@link StoreOutcome#OK} with the candidate, or {@link StoreOutcome#MALFORMED} when the id of a candidate
     * cannot be read, or when no candidate or more than one candidate carries the id
     */
    private static <S> StoreResult<S> unique(Class<S> service, String key, String id,
            Iterable<? extends S> candidates, Function<S, @Nullable String> idOf) {
        @Nullable S found = null;
        for (S candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            @Nullable String candidateId = readId(service, candidate, idOf);
            if (candidateId == null) {
                return unreadableId(service);
            }
            if (id.equals(candidateId)) {
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
     * Reads the id of a candidate, guarded against a provider that fails while answering it.
     * <p>
     * Only the class of the candidate and of the throwable are logged: the message of a failing provider may name a
     * credential (GDR-NFR-3). A {@link LinkageError} is handled like a {@link RuntimeException}, since a provider whose
     * classes cannot be linked fails first when one of its methods is called.
     *
     * @param service   the service interface, named in the log
     * @param candidate the candidate
     * @param idOf      the id of a candidate
     * @param <S>       the service type
     * @return the id, or {@code null} when {@code id()} throws or returns {@code null}
     */
    private static <S> @Nullable String readId(Class<S> service, S candidate, Function<S, @Nullable String> idOf) {
        try {
            return idOf.apply(candidate);
        }
        // a provider that fails while naming itself ends the selection as a result, never as an exception (GDR-GEN-3)
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (RuntimeException | LinkageError e) {
            LOGGER.debug("Reading the id of the provider %s of %s failed with %s", candidate.getClass().getName(),
                    service.getName(), e.getClass().getName());
            return null;
        }
    }

    /**
     * The refusal of a candidate whose id cannot be read.
     *
     * @param service the service interface, named in the detail
     * @param <V>     the value type of the result
     * @return {@link StoreOutcome#MALFORMED} with {@code <Interface>: a provider id cannot be read}
     */
    private static <V> StoreResult<V> unreadableId(Class<?> service) {
        return StoreResult.of(StoreOutcome.MALFORMED, service.getSimpleName() + ": a provider id cannot be read");
    }

    /**
     * Instantiates every provider {@link ServiceLoader} finds for a service interface.
     *
     * @param service the service interface
     * @param <S>     the service type
     * @return {@link StoreOutcome#OK} with the providers, possibly empty, or {@link StoreOutcome#MALFORMED} naming the
     * service interface when a provider cannot be loaded, a {@link ServiceConfigurationError} or a
     * {@link LinkageError} alike
     */
    private static <S> StoreResult<List<S>> loadAll(Class<S> service) {
        List<S> providers = new ArrayList<>();
        try {
            for (S provider : ServiceLoader.load(service)) {
                providers.add(provider);
            }
        } catch (ServiceConfigurationError | LinkageError e) {
            // neither the error nor its message is logged: a failing provider may name a credential in either
            LOGGER.debug("Loading a provider of %s failed with %s", service.getName(), e.getClass().getName());
            return StoreResult.of(StoreOutcome.MALFORMED, service.getSimpleName() + ": a provider cannot be loaded");
        }
        return StoreResult.ok(providers);
    }

    /**
     * Builds the built-in provider {@code signed} (GDR-STORE-18) from the section {@code anchor.signed}.
     * <p>
     * The anchor key source is the candidate signing key source carrying the effective {@code anchor.signed.key-source}
     * (default {@value StoreConfiguration.Signing#DEFAULT_KEY_SOURCE}); the public keys of the anchor key and, when
     * {@code signing.key} is set, of the commit-signing key are read once, and equal public keys stop the start, so a
     * different key reference or key source cannot hide the same key. {@code anchor.signed.allowed-signers}, when set,
     * is read for the verification. No detail names a key reference or key material (GDR-NFR-3).
     *
     * @return {@link StoreOutcome#OK} with the provider; {@link StoreOutcome#MALFORMED} for a candidate whose id cannot
     * be read, a candidate that also carries
     * the id {@value SignedAnchorProvider#PROVIDER_ID}, a missing {@code anchor.signed.key}, an anchor key source no candidate or
     * more than one candidate carries, a key of another type than Ed25519, an anchor key equal to the commit-signing key
     * or a refused allowed-signers file; {@link StoreOutcome#UNAVAILABLE} or {@link StoreOutcome#REFUSED} for a key
     * source that handed out no public key, or the result of a file that cannot be read
     */
    private static StoreResult<AnchorProvider> signed(StoreConfiguration configuration, SigningKeySource signingSource,
            Iterable<? extends SigningKeySource> signing, Iterable<? extends AnchorProvider> anchors) {
        for (AnchorProvider candidate : anchors) {
            @Nullable String candidateId = readId(AnchorProvider.class, Objects.requireNonNull(candidate, "candidate"),
                    AnchorProvider::id);
            if (candidateId == null) {
                return unreadableId(AnchorProvider.class);
            }
            if (SignedAnchorProvider.PROVIDER_ID.equals(candidateId)) {
                return StoreResult.of(StoreOutcome.MALFORMED, AnchorProvider.class.getSimpleName()
                        + ": more than one provider with the id of " + StoreConfiguration.ANCHOR_PROVIDER);
            }
        }
        StoreConfiguration.Anchor.Signed section = configuration.anchor().signed();
        String keyReference = section.key();
        if (keyReference == null) {
            return StoreResult.of(StoreOutcome.MALFORMED, SIGNED_KEY + " is missing");
        }
        @Nullable String configuredSource = section.keySource();
        String anchorSourceId = configuredSource == null ? StoreConfiguration.Signing.DEFAULT_KEY_SOURCE
                : configuredSource;
        var anchorSource = unique(SigningKeySource.class, SIGNED_KEY_SOURCE, anchorSourceId, signing,
                SigningKeySource::id);
        if (!anchorSource.isOk()) {
            return retype(anchorSource);
        }
        SigningKeySource keySource = Objects.requireNonNull(anchorSource.value());
        var anchorKey = publicKey(keySource, keyReference, SIGNED_KEY);
        if (!anchorKey.isOk()) {
            return retype(anchorKey);
        }
        SshSignatures.AcceptedKey publicKey = Objects.requireNonNull(anchorKey.value());
        String commitKeyReference = configuration.signing().key();
        if (commitKeyReference != null) {
            var commitKey = publicKey(signingSource, commitKeyReference, SIGNING_KEY);
            if (!commitKey.isOk()) {
                return retype(commitKey);
            }
            if (publicKey.equals(commitKey.value())) {
                return StoreResult.of(StoreOutcome.MALFORMED, SIGNED_KEY + " equals the commit-signing key");
            }
        }
        @Nullable AllowedSigners allowedSigners = null;
        if (section.allowedSigners() != null) {
            var read = AllowedSigners.fromAnchor(configuration);
            if (!read.isOk()) {
                return retype(read);
            }
            allowedSigners = read.value();
        }
        return StoreResult.ok(new SignedAnchorProvider(
                new SignedAnchorProvider.AnchorKey(keySource, keyReference, publicKey), allowedSigners,
                section.maxClockSkew(), Clock.systemUTC()));
    }

    /**
     * Reads the public key of a key reference.
     *
     * @param source       the key source
     * @param keyReference the key reference
     * @param key          the configuration key of the key reference, named in the detail
     * @return {@link StoreOutcome#OK} with the key; {@link StoreOutcome#UNAVAILABLE} with
     * {@code <key>: key source unavailable}; {@link StoreOutcome#REFUSED} with {@code <key>: key source refused};
     * {@link StoreOutcome#MALFORMED} with {@code <key>: key type not supported}
     */
    private static StoreResult<SshSignatures.AcceptedKey> publicKey(SigningKeySource source, String keyReference,
            String key) {
        StoreResult<String> line = source.publicKey(keyReference);
        String value = line.value();
        if (!line.isOk() || value == null) {
            // the source's own detail is never passed on: it may name the key reference
            return line.outcome() == StoreOutcome.UNAVAILABLE
                    ? StoreResult.of(StoreOutcome.UNAVAILABLE, key + ": key source unavailable")
                    : StoreResult.of(StoreOutcome.REFUSED, key + ": key source refused");
        }
        if (SshSignatures.parsePublicKeyLine(value) instanceof SshSignatures.AcceptedKey accepted) {
            return StoreResult.ok(accepted);
        }
        return StoreResult.of(StoreOutcome.MALFORMED, key + ": key type not supported");
    }

    private static <V> StoreResult<V> retype(StoreResult<?> result) {
        return new StoreResult<>(result.outcome(), null, result.detail());
    }

    private static StoreResult<StoreProviders> refuse(@Nullable String detail) {
        return refuse(StoreResult.of(StoreOutcome.MALFORMED, Objects.requireNonNull(detail)));
    }

    private static StoreResult<StoreProviders> refuse(StoreResult<?> result) {
        StoreResult<StoreProviders> refusal = retype(result);
        LOGGER.warn(GitStoreLogMessages.WARN.START_REFUSED, refusal.detail());
        return refusal;
    }
}
