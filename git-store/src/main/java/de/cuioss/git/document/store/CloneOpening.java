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

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jspecify.annotations.Nullable;

/**
 * Opens the bare clone of one {@link StoreWriter}: checks the arguments of {@link StoreWriter#open}, builds the
 * {@link Providers} from the signing key source or the selected {@link StoreProviders}, clones the remote if the clone
 * does not exist yet, opens the clone on a {@link RefusingFS}, refuses declared collections that hold one name twice
 * and a clone whose {@code origin} is not the registered remote, and builds the parts that work on the opened clone
 * into a {@link StoreClone} with its {@link CloneWrites} and {@link CloneSchedules}, which share the closed flag of the
 * clone: among them one {@link CommitSignatures} per clone over the
 * active signing key source and {@code signing.key}, whose presence is checked before anything is cloned, and the
 * {@link AnchorEngine} over the active anchor provider, the section {@code anchor}, that signing seam, the technical
 * user, the guard and the clock.
 */
final class CloneOpening {

    private CloneOpening() {
    }

    /**
     * The providers a clone is opened with.
     *
     * @param signing the active signing key source
     * @param anchor  the active anchor provider; {@code null} when none is active, so nothing is anchored
     */
    record Providers(SigningKeySource signing, @Nullable AnchorProvider anchor) {

        /**
         * Checks the signing key source.
         *
         * @param signing the signing key source
         * @param anchor  the anchor provider, or {@code null}
         * @throws NullPointerException if {@code signing} is {@code null}
         */
        Providers {
            Objects.requireNonNull(signing, "signing");
        }
    }

    /**
     * Opens the bare clone of a repository with the signing key source only, nothing anchored; see
     * {@link StoreWriter#open(StoreRepository, Path, TechnicalUser, StoreConfiguration, List, SigningKeySource, RepositoryOperations, Clock)}.
     *
     * @param storeRepository the repository of the registry
     * @param gitDir          the git directory of the bare clone
     * @param user            the technical user
     * @param configuration   the configuration of the store
     * @param collections     the declared collections of the repository, checked before anything is cloned
     * @param signing         the active signing key source
     * @param operations      the repository operations of {@code git-core}
     * @param clock           the clock of every commit time
     * @return the results of {@link StoreWriter#open}, with the clone as the value
     * @throws NullPointerException if an argument or a collection is {@code null}
     */
    static StoreResult<StoreClone> open(StoreRepository storeRepository, Path gitDir, TechnicalUser user,
            StoreConfiguration configuration, List<DocumentCollection> collections, SigningKeySource signing,
            RepositoryOperations operations, Clock clock) {
        return checked(storeRepository, gitDir, user, configuration, collections, new Providers(signing, null),
                operations, clock);
    }

    /**
     * Opens the bare clone of a repository with the providers selected at start; see
     * {@link StoreWriter#open(StoreRepository, Path, TechnicalUser, StoreConfiguration, List, StoreProviders, RepositoryOperations, Clock)}.
     *
     * @param storeRepository the repository of the registry
     * @param gitDir          the git directory of the bare clone
     * @param user            the technical user
     * @param configuration   the configuration of the store
     * @param collections     the declared collections of the repository, checked before anything is cloned
     * @param providers       the selected providers
     * @param operations      the repository operations of {@code git-core}
     * @param clock           the clock of every commit time and of the due checks of the anchoring
     * @return the results of {@link StoreWriter#open}, with the clone as the value
     * @throws NullPointerException if an argument or a collection is {@code null}
     */
    static StoreResult<StoreClone> open(StoreRepository storeRepository, Path gitDir, TechnicalUser user,
            StoreConfiguration configuration, List<DocumentCollection> collections, StoreProviders providers,
            RepositoryOperations operations, Clock clock) {
        Objects.requireNonNull(providers, "providers");
        return checked(storeRepository, gitDir, user, configuration, collections,
                new Providers(providers.signing(), providers.anchor()), operations, clock);
    }

    private static StoreResult<StoreClone> checked(StoreRepository storeRepository, Path gitDir, TechnicalUser user,
            StoreConfiguration configuration, List<DocumentCollection> collections, Providers providers,
            RepositoryOperations operations, Clock clock) {
        Objects.requireNonNull(storeRepository, "storeRepository");
        Objects.requireNonNull(gitDir, "gitDir");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(collections, "collections");
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(clock, "clock");
        return open(storeRepository, gitDir, user, configuration, collections, providers, operations, clock);
    }

    private static StoreResult<StoreClone> open(StoreRepository storeRepository, Path gitDir, TechnicalUser user,
            StoreConfiguration configuration, List<DocumentCollection> collections, Providers providers,
            RepositoryOperations operations, Clock clock) {
        if (!StoreReferences.collections(collections).isOk()) {
            // the detail of StoreReferences names the collection; the writer names the argument only
            return StoreResult.of(StoreOutcome.MALFORMED, StoreWriter.COLLECTIONS_DECLARED_TWICE);
        }
        SigningKeySource signing = providers.signing();
        @Nullable AnchorProvider anchor = providers.anchor();
        String keyReference = configuration.signing().key();
        if (keyReference == null) {
            return StoreResult.of(StoreOutcome.MALFORMED, StoreWriter.SIGNING_KEY_MISSING);
        }
        var cloned = operations.cloneIfMissing(RepositoryOperations.CloneInput.builder()
                .gitDir(gitDir)
                .url(storeRepository.remote())
                .pushRefSpecs(StoreReferences.PUSH_REF_SPECS)
                .credentials(user.credentials())
                .build());
        if (!cloned.isOk()) {
            return StoreResult.passOn(cloned);
        }
        Path gitDirectory = Objects.requireNonNull(cloned.value()).gitDir();
        Repository repository;
        try {
            repository = new FileRepositoryBuilder().setGitDir(gitDirectory.toFile()).setFS(new RefusingFS())
                    .setMustExist(true).build();
        } catch (IOException e) {
            return StoreResult.passOn(DraftReferences.<StoreClone>failure(storeRepository.alias(), e));
        }
        if (!transportsTo(repository, storeRepository.remote())) {
            repository.close();
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.NOT_A_REPOSITORY), null,
                    storeRepository.alias() + ": " + StoreWriter.FOREIGN_CLONE);
        }
        var identities = new CommitIdentities(configuration.commit(), user);
        var updater = new ReferenceUpdater(new InCoreWriter());
        int attempts = configuration.save().maxAttempts();
        var guard = RepositoryGuard.of(gitDirectory);
        var bareClone = new ExplicitSave.Clone(storeRepository.alias(), repository, gitDirectory, guard);
        var listeners = new ListenerDispatch(storeRepository.alias(), repository);
        var events = new StoreEvents(listeners);
        var signatures = new CommitSignatures(storeRepository.alias(), signing, keyReference);
        var committing = new ExplicitSave.Committing(identities, signatures, clock);
        var closed = new AtomicBoolean();
        var schedules = new CloneSchedules(bareClone,
                new CloneSchedules.Transport(operations, user, configuration.fetch().interval(),
                        configuration.anchor().retryInterval()),
                events, new AnchorEngine(bareClone, new AnchorEngine.Selection(anchor, configuration.anchor()),
                        operations, user, committing, attempts, events),
                closed);
        var writes = new CloneWrites(bareClone, new CloneWrites.Parts(
                        new DraftReferences(repository, updater, identities, clock, attempts),
                        new ExplicitSave(bareClone, operations, user, committing, attempts, events),
                        new DocumentRelease(bareClone, operations, user, committing, attempts, events),
                        new DraftExpiry(storeRepository.alias(), repository, guard, updater, configuration.drafts().expiry(),
                                attempts)),
                schedules::triggerDueCheck);
        return StoreResult.ok(new StoreClone(bareClone, events, listeners, writes, schedules, closed));
    }

    /**
     * Tells whether every fetch and push of the clone's {@code origin} goes to the registered remote: exactly one
     * fetch URL, and every push URL (the fetch URL when none is configured), equal to it. JGit 7.8.0
     * {@code RemoteConfig} applies {@code url.<base>.insteadOf} to the fetch URLs and {@code pushInsteadOf} to the
     * push URLs it derives, so the comparison sees the destinations a transport would contact.
     *
     * @param repository the opened clone
     * @param remote     the registered remote
     * @return whether every transport of {@code origin} goes to {@code remote}; {@code false} for an unparsable URL
     */
    private static boolean transportsTo(Repository repository, String remote) {
        try {
            var expected = new URIish(remote);
            var origin = new RemoteConfig(repository.getConfig(), Constants.DEFAULT_REMOTE_NAME);
            List<URIish> fetch = origin.getURIs();
            List<URIish> push = origin.getPushURIs().isEmpty() ? fetch : origin.getPushURIs();
            return fetch.size() == 1 && expected.equals(fetch.getFirst()) && push.stream().allMatch(expected::equals);
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
