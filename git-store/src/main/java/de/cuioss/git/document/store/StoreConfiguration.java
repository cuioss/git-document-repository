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

import static de.cuioss.git.document.store.ConfigurationTree.retype;

import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The typed configuration of the store: every key of the configuration of {@code git-store}, checked at start.
 * <p>
 * {@link #from(Map, RemotePolicy)} reads an already-parsed configuration tree: nested maps as a YAML or JSON parser
 * delivers them, keys nested by dot segment. Keys outside the configuration of {@code git-store} are ignored, because
 * the tree is shared with the modules above it. Every refusal is a {@link StoreResult}, never an exception.
 *
 * @param repositories the repository registry; empty when the key {@code repositories} is absent
 * @param save         the section {@code save}
 * @param drafts       the section {@code drafts}
 * @param fetch        the section {@code fetch}
 * @param signing      the section {@code signing}
 * @param anchor       the section {@code anchor}
 * @param directory    the section {@code directory}
 * @since 0.1
 */
public record StoreConfiguration(RepositoryRegistry repositories, Save save, Drafts drafts, Fetch fetch,
Signing signing, Anchor anchor, Directory directory) {

    private static final CuiLogger LOGGER = new CuiLogger(StoreConfiguration.class);

    private static final String REPOSITORIES_KEY = "repositories";
    private static final String MAX_ATTEMPTS = "save.max-attempts";
    private static final String DRAFTS_EXPIRY = "drafts.expiry";
    private static final String FETCH_INTERVAL = "fetch.interval";
    private static final String SIGNING_KEY_SOURCE = "signing.key-source";
    private static final String SIGNING_KEY = "signing.key";
    private static final String SIGNING_ALLOWED_SIGNERS = "signing.allowed-signers";
    private static final String ANCHOR_PROVIDER = "anchor.provider";
    private static final String ANCHOR_RELEASES = "anchor.releases";
    private static final String ANCHOR_CHECKPOINT_INTERVAL = "anchor.checkpoint-interval";
    private static final String ANCHOR_RETRY_INTERVAL = "anchor.retry-interval";
    private static final String SIGNED_KEY_SOURCE = "anchor.signed.key-source";
    private static final String SIGNED_KEY = "anchor.signed.key";
    private static final String SIGNED_ALLOWED_SIGNERS = "anchor.signed.allowed-signers";
    private static final String SIGNED_MAX_CLOCK_SKEW = "anchor.signed.max-clock-skew";
    private static final String PERSONS_PROVIDER = "directory.persons.provider";

    /**
     * Checks that every section is present.
     *
     * @param repositories the repository registry
     * @param save         the section {@code save}
     * @param drafts       the section {@code drafts}
     * @param fetch        the section {@code fetch}
     * @param signing      the section {@code signing}
     * @param anchor       the section {@code anchor}
     * @param directory    the section {@code directory}
     * @throws NullPointerException if a section is {@code null}
     */
    public StoreConfiguration {
        Objects.requireNonNull(repositories, REPOSITORIES_KEY);
        Objects.requireNonNull(save, "save");
        Objects.requireNonNull(drafts, "drafts");
        Objects.requireNonNull(fetch, "fetch");
        Objects.requireNonNull(signing, "signing");
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(directory, "directory");
    }

    /**
     * Reads and checks the configuration of the store at start.
     * <p>
     * {@code save.max-attempts} (an integer of at least 1), {@code drafts.expiry} (a positive ISO 8601 period) and
     * {@code fetch.interval} (a positive ISO 8601 duration) are required; every other key has the default its section
     * documents; only an omitted key takes its default, a key supplied with a {@code null} value is malformed. A
     * missing required key, a malformed value, a refused repository of the registry and an anchor key
     * equal to the commit-signing key are refused: {@link StoreOutcome#MALFORMED}, or the passed-on
     * {@link de.cuioss.git.document.core.GitOutcome#REFUSED_BY_POLICY} of the registry. The detail names the key or
     * the alias, never the value, and every refusal is logged once as {@code GIT_STORE-100}.
     * <p>
     * The anchor key equals the commit-signing key when {@code anchor.signed.key} equals {@code signing.key} under the
     * same effective key source ({@code anchor.signed.key-source}, an absent one taken as the default
     * {@value Signing#DEFAULT_KEY_SOURCE}). Comparing the public keys themselves needs the key source and is left to
     * the provider of the anchor key.
     *
     * @param tree   the configuration tree
     * @param policy the remote policy every registered remote must satisfy
     * @return {@link StoreOutcome#OK} with the configuration, or the first refusal
     * @throws NullPointerException if {@code tree} or {@code policy} is {@code null}
     */
    public static StoreResult<StoreConfiguration> from(Map<String, ?> tree, RemotePolicy policy) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(policy, "policy");
        var result = read(new ConfigurationTree(tree), policy);
        if (!result.isOk()) {
            LOGGER.warn(GitStoreLogMessages.WARN.START_REFUSED, result.detail());
        }
        return result;
    }

    private static StoreResult<StoreConfiguration> read(ConfigurationTree tree, RemotePolicy policy) {
        var save = readSave(tree);
        if (!save.isOk()) {
            return retype(save);
        }
        var drafts = readDrafts(tree);
        if (!drafts.isOk()) {
            return retype(drafts);
        }
        var fetch = readFetch(tree);
        if (!fetch.isOk()) {
            return retype(fetch);
        }
        var signing = readSigning(tree);
        if (!signing.isOk()) {
            return retype(signing);
        }
        var anchor = readAnchor(tree);
        if (!anchor.isOk()) {
            return retype(anchor);
        }
        var directory = readDirectory(tree);
        if (!directory.isOk()) {
            return retype(directory);
        }
        var registry = readRegistry(tree, policy);
        if (!registry.isOk()) {
            return retype(registry);
        }
        Signing signingSection = Objects.requireNonNull(signing.value());
        Anchor anchorSection = Objects.requireNonNull(anchor.value());
        if (anchorKeyIsSigningKey(signingSection, anchorSection.signed())) {
            return StoreResult.of(StoreOutcome.MALFORMED, SIGNED_KEY + " equals the commit-signing key");
        }
        return StoreResult.ok(new StoreConfiguration(Objects.requireNonNull(registry.value()),
                Objects.requireNonNull(save.value()), Objects.requireNonNull(drafts.value()),
                Objects.requireNonNull(fetch.value()), signingSection, anchorSection,
                Objects.requireNonNull(directory.value())));
    }

    private static boolean anchorKeyIsSigningKey(Signing signing, Anchor.Signed signed) {
        String anchorKeySource = Objects.requireNonNullElse(signed.keySource(), Signing.DEFAULT_KEY_SOURCE);
        String anchorKey = signed.key();
        return anchorKey != null && anchorKey.equals(signing.key()) && anchorKeySource.equals(signing.keySource());
    }

    private static StoreResult<Save> readSave(ConfigurationTree tree) {
        var maxAttempts = tree.integer(MAX_ATTEMPTS);
        if (!maxAttempts.isOk()) {
            return retype(maxAttempts);
        }
        Integer attempts = maxAttempts.value();
        if (attempts == null) {
            return tree.missing(MAX_ATTEMPTS);
        }
        return attempts < 1 ? tree.malformed(MAX_ATTEMPTS) : StoreResult.ok(new Save(attempts));
    }

    private static StoreResult<Drafts> readDrafts(ConfigurationTree tree) {
        var expiry = tree.period(DRAFTS_EXPIRY);
        if (!expiry.isOk()) {
            return retype(expiry);
        }
        Period value = expiry.value();
        return value == null ? tree.missing(DRAFTS_EXPIRY) : StoreResult.ok(new Drafts(value));
    }

    private static StoreResult<Fetch> readFetch(ConfigurationTree tree) {
        var interval = tree.duration(FETCH_INTERVAL);
        if (!interval.isOk()) {
            return retype(interval);
        }
        Duration value = interval.value();
        return value == null ? tree.missing(FETCH_INTERVAL) : StoreResult.ok(new Fetch(value));
    }

    // S4449 knows javax.annotation.Nullable only; the nullable components are marked with the JSpecify @Nullable,
    // and a dependency on the javax annotations is not permitted (AGENTS.md: no new dependency)
    @SuppressWarnings("java:S4449")
    private static StoreResult<Signing> readSigning(ConfigurationTree tree) {
        var keySource = tree.string(SIGNING_KEY_SOURCE);
        if (!keySource.isOk()) {
            return retype(keySource);
        }
        var key = tree.string(SIGNING_KEY);
        if (!key.isOk()) {
            return retype(key);
        }
        var allowedSigners = tree.string(SIGNING_ALLOWED_SIGNERS);
        if (!allowedSigners.isOk()) {
            return retype(allowedSigners);
        }
        return StoreResult.ok(new Signing(Objects.requireNonNullElse(keySource.value(), Signing.DEFAULT_KEY_SOURCE),
                key.value(), allowedSigners.value()));
    }

    // S4449 knows javax.annotation.Nullable only; the nullable components are marked with the JSpecify @Nullable
    @SuppressWarnings("java:S4449")
    private static StoreResult<Anchor> readAnchor(ConfigurationTree tree) {
        var provider = tree.string(ANCHOR_PROVIDER);
        if (!provider.isOk()) {
            return retype(provider);
        }
        var releases = tree.bool(ANCHOR_RELEASES);
        if (!releases.isOk()) {
            return retype(releases);
        }
        var checkpointInterval = tree.duration(ANCHOR_CHECKPOINT_INTERVAL);
        if (!checkpointInterval.isOk()) {
            return retype(checkpointInterval);
        }
        var retryInterval = tree.duration(ANCHOR_RETRY_INTERVAL);
        if (!retryInterval.isOk()) {
            return retype(retryInterval);
        }
        var signed = readSigned(tree);
        if (!signed.isOk()) {
            return retype(signed);
        }
        return StoreResult.ok(new Anchor(provider.value(), Objects.requireNonNullElse(releases.value(), true),
                Objects.requireNonNullElse(checkpointInterval.value(), Anchor.DEFAULT_CHECKPOINT_INTERVAL),
                Objects.requireNonNullElse(retryInterval.value(), Anchor.DEFAULT_RETRY_INTERVAL),
                Objects.requireNonNull(signed.value())));
    }

    // S4449 knows javax.annotation.Nullable only; the nullable components are marked with the JSpecify @Nullable
    @SuppressWarnings("java:S4449")
    private static StoreResult<Anchor.Signed> readSigned(ConfigurationTree tree) {
        var keySource = tree.string(SIGNED_KEY_SOURCE);
        if (!keySource.isOk()) {
            return retype(keySource);
        }
        var key = tree.string(SIGNED_KEY);
        if (!key.isOk()) {
            return retype(key);
        }
        var allowedSigners = tree.string(SIGNED_ALLOWED_SIGNERS);
        if (!allowedSigners.isOk()) {
            return retype(allowedSigners);
        }
        var maxClockSkew = tree.duration(SIGNED_MAX_CLOCK_SKEW);
        if (!maxClockSkew.isOk()) {
            return retype(maxClockSkew);
        }
        return StoreResult.ok(new Anchor.Signed(keySource.value(), key.value(), allowedSigners.value(),
                Objects.requireNonNullElse(maxClockSkew.value(), Anchor.Signed.DEFAULT_MAX_CLOCK_SKEW)));
    }

    // S4449 does not see that requireNonNullElse never returns null; the argument is never null
    @SuppressWarnings("java:S4449")
    private static StoreResult<Directory> readDirectory(ConfigurationTree tree) {
        var personsProvider = tree.string(PERSONS_PROVIDER);
        if (!personsProvider.isOk()) {
            return retype(personsProvider);
        }
        return StoreResult.ok(new Directory(
                Objects.requireNonNullElse(personsProvider.value(), Directory.DEFAULT_PERSONS_PROVIDER)));
    }

    private static StoreResult<RepositoryRegistry> readRegistry(ConfigurationTree tree, RemotePolicy policy) {
        var sections = tree.sections(REPOSITORIES_KEY);
        if (!sections.isOk()) {
            return retype(sections);
        }
        List<StoreRepository> repositories = new ArrayList<>();
        for (ConfigurationTree section : Objects.requireNonNullElse(sections.value(), List.<ConfigurationTree>of())) {
            var repository = readRepository(section);
            if (!repository.isOk()) {
                return retype(repository);
            }
            repositories.add(Objects.requireNonNull(repository.value()));
        }
        return RepositoryRegistry.of(repositories, policy);
    }

    private static StoreResult<StoreRepository> readRepository(ConfigurationTree section) {
        var alias = required(section, "alias");
        if (!alias.isOk()) {
            return retype(alias);
        }
        var remote = required(section, "remote");
        if (!remote.isOk()) {
            return retype(remote);
        }
        var confidentiality = required(section, "confidentiality");
        if (!confidentiality.isOk()) {
            return retype(confidentiality);
        }
        var retention = section.period("retention");
        if (!retention.isOk()) {
            return retype(retention);
        }
        return StoreResult.ok(new StoreRepository(Objects.requireNonNull(alias.value()),
                Objects.requireNonNull(remote.value()), Objects.requireNonNull(confidentiality.value()),
                retention.value()));
    }

    private static StoreResult<String> required(ConfigurationTree tree, String key) {
        var value = tree.string(key);
        if (value.isOk() && value.value() == null) {
            return tree.missing(key);
        }
        return value;
    }

    /**
     * The section {@code save}.
     *
     * @param maxAttempts the attempts of a save or of a push of notes ({@code save.max-attempts}), at least 1
     * @since 0.1
     */
    public record Save(int maxAttempts) {

        /**
         * Checks the number of attempts.
         *
         * @param maxAttempts the attempts
         * @throws IllegalArgumentException if {@code maxAttempts} is less than 1
         */
        public Save {
            if (maxAttempts < 1) {
                throw new IllegalArgumentException("at least one attempt is required");
            }
        }
    }

    /**
     * The section {@code drafts}.
     *
     * @param expiry the period after which an unchanged draft is deleted ({@code drafts.expiry})
     * @since 0.1
     */
    public record Drafts(Period expiry) {

        /**
         * Checks that the expiry is present.
         *
         * @param expiry the expiry
         * @throws NullPointerException if {@code expiry} is {@code null}
         */
        public Drafts {
            Objects.requireNonNull(expiry, "expiry");
        }
    }

    /**
     * The section {@code fetch}.
     *
     * @param interval the interval of the periodic fetch ({@code fetch.interval})
     * @since 0.1
     */
    public record Fetch(Duration interval) {

        /**
         * Checks that the interval is present.
         *
         * @param interval the interval
         * @throws NullPointerException if {@code interval} is {@code null}
         */
        public Fetch {
            Objects.requireNonNull(interval, "interval");
        }
    }

    /**
     * The section {@code signing}: the commit-signing key.
     *
     * @param keySource      the id of the signing key source ({@code signing.key-source}), default
     *                       {@value #DEFAULT_KEY_SOURCE}
     * @param key            the key reference passed to the source ({@code signing.key}); {@code null} when absent
     * @param allowedSigners the allowed-signers file of the commit-signing keys ({@code signing.allowed-signers});
     *                       {@code null} when absent
     * @since 0.1
     */
    public record Signing(String keySource, @Nullable String key, @Nullable String allowedSigners) {

        /** The default id of the signing key source. */
        public static final String DEFAULT_KEY_SOURCE = "file";

        /**
         * Checks that the key source is present.
         *
         * @param keySource      the id of the key source
         * @param key            the key reference, or {@code null}
         * @param allowedSigners the allowed-signers file, or {@code null}
         * @throws NullPointerException if {@code keySource} is {@code null}
         */
        public Signing {
            Objects.requireNonNull(keySource, "keySource");
        }

        /**
         * Renders the section without the key reference (GDR-NFR-3), stating only whether one is set.
         *
         * @return the rendering
         */
        @Override
        public String toString() {
            return "Signing[keySource=" + keySource + ", key=" + (key == null ? "absent" : "present")
                    + ", allowedSigners=" + allowedSigners + "]";
        }
    }

    /**
     * The section {@code anchor}.
     *
     * @param provider           the id of the active anchor provider ({@code anchor.provider}); {@code null} when
     *                           none is active, so nothing is anchored
     * @param releases           whether the commit of every release tag is anchored ({@code anchor.releases}),
     *                           default {@code true}
     * @param checkpointInterval the interval between checkpoints ({@code anchor.checkpoint-interval}), default
     *                           {@code P1D}
     * @param retryInterval      the interval of the periodic due check and retry ({@code anchor.retry-interval}),
     *                           default {@code PT15M}
     * @param signed             the section {@code anchor.signed}
     * @since 0.1
     */
    public record Anchor(@Nullable String provider, boolean releases, Duration checkpointInterval,
    Duration retryInterval, Signed signed) {

        /** The default interval between checkpoints, {@code P1D}. */
        public static final Duration DEFAULT_CHECKPOINT_INTERVAL = Duration.ofDays(1);

        /** The default interval of the due check and retry, {@code PT15M}. */
        public static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMinutes(15);

        /**
         * Checks that the intervals and the section {@code signed} are present.
         *
         * @param provider           the id of the anchor provider, or {@code null}
         * @param releases           whether release commits are anchored
         * @param checkpointInterval the interval between checkpoints
         * @param retryInterval      the interval of the due check and retry
         * @param signed             the section {@code anchor.signed}
         * @throws NullPointerException if an interval or {@code signed} is {@code null}
         */
        public Anchor {
            Objects.requireNonNull(checkpointInterval, "checkpointInterval");
            Objects.requireNonNull(retryInterval, "retryInterval");
            Objects.requireNonNull(signed, "signed");
        }

        /**
         * The section {@code anchor.signed}: the anchor key of the provider {@code signed}, never the commit-signing
         * key.
         *
         * @param keySource      the id of the key source of the anchor key ({@code anchor.signed.key-source});
         *                       {@code null} when absent, then the default {@value Signing#DEFAULT_KEY_SOURCE}
         *                       applies
         * @param key            the key reference of the anchor key ({@code anchor.signed.key}); {@code null} when
         *                       absent
         * @param allowedSigners the allowed-signers file of the anchor keys
         *                       ({@code anchor.signed.allowed-signers}); {@code null} when absent
         * @param maxClockSkew   the largest delay of the provider's clock behind the commit time of a subject
         *                       ({@code anchor.signed.max-clock-skew}), default {@code PT5M}
         * @since 0.1
         */
        public record Signed(@Nullable String keySource, @Nullable String key, @Nullable String allowedSigners,
        Duration maxClockSkew) {

            /** The default largest clock delay, {@code PT5M}. */
            public static final Duration DEFAULT_MAX_CLOCK_SKEW = Duration.ofMinutes(5);

            /**
             * Checks that the largest clock delay is present.
             *
             * @param keySource      the id of the key source, or {@code null}
             * @param key            the key reference, or {@code null}
             * @param allowedSigners the allowed-signers file, or {@code null}
             * @param maxClockSkew   the largest clock delay
             * @throws NullPointerException if {@code maxClockSkew} is {@code null}
             */
            public Signed {
                Objects.requireNonNull(maxClockSkew, "maxClockSkew");
            }

            /**
             * Renders the section without the key reference (GDR-NFR-3), stating only whether one is set.
             *
             * @return the rendering
             */
            @Override
            public String toString() {
                return "Signed[keySource=" + keySource + ", key=" + (key == null ? "absent" : "present")
                        + ", allowedSigners=" + allowedSigners + ", maxClockSkew=" + maxClockSkew + "]";
            }
        }
    }

    /**
     * The section {@code directory}.
     *
     * @param personsProvider the id of the active person directory ({@code directory.persons.provider}), default
     *                        {@value #DEFAULT_PERSONS_PROVIDER}
     * @since 0.1
     */
    public record Directory(String personsProvider) {

        /** The default id of the person directory. */
        public static final String DEFAULT_PERSONS_PROVIDER = "git";

        /**
         * Checks that the provider id is present.
         *
         * @param personsProvider the id of the person directory
         * @throws NullPointerException if {@code personsProvider} is {@code null}
         */
        public Directory {
            Objects.requireNonNull(personsProvider, "personsProvider");
        }
    }
}
