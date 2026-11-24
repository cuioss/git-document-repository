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
/**
 * The store of versioned documents on Git (GDR-STORE): a server-side bare clone per repository, reached through
 * {@code git-core}, as the persistence of documents users maintain.
 * <p>
 * The module holds the whole store:
 * <ul>
 * <li>the repository registry ({@code RepositoryRegistry}, {@code StoreRepository}) and the typed configuration
 * ({@code StoreConfiguration}), with the closed outcome list {@code StoreOutcome} and the result {@code StoreResult}
 * (GDR-GEN-3);</li>
 * <li>the codec interface {@code Codec} a consumer encodes its documents with;</li>
 * <li>the service interfaces {@code SigningKeySource}, {@code AnchorProvider} and {@code PersonDirectory} and their
 * selection at start ({@code StoreProviders});</li>
 * <li>the write path ({@code StoreWriter}): drafts per user and document, the explicit save with lease and an
 * optional per-attempt {@code SaveStep}, the release of a document with its version tag ({@code Release}), the draft
 * expiry, and the fetch on demand or on the caller's schedule (GDR-STORE-2 to GDR-STORE-8);</li>
 * <li>the in-memory index ({@code StoreIndex}) over the consumer's {@code IndexContributor} per collection, refreshed
 * after every fetch, save and release, with snapshots of a tag or commit (GDR-STORE-9);</li>
 * <li>the in-process {@code CommitListener}s of a branch (GDR-STORE-10);</li>
 * <li>the signing of every save and release commit in the SSH signature format with its identity trailers, and the
 * verification of those signatures ({@code SignatureVerification}, {@code VerifySignatures}, GDR-STORE-12 to
 * GDR-STORE-14);</li>
 * <li>the anchoring of releases and checkpoints with proofs as notes, the built-in provider {@code signed} and the
 * anchor verification ({@code AnchorVerification}, {@code VerifyAnchors}, GDR-STORE-15 to GDR-STORE-18).</li>
 * </ul>
 * <p>
 * The module requires {@code de.cuioss.git.document.core} transitively, because its public API exposes types of that
 * module and of JGit, and {@code de.cuioss.java.tools} transitively, because the public holder
 * {@code GitStoreLogMessages} exposes its {@code LogRecord}.
 * <p>
 * It provides the signing key source {@code file} ({@code FileSigningKeySource}, an unencrypted OpenSSH Ed25519 key
 * from a file), which {@code StoreProviders} finds through {@link java.util.ServiceLoader} beside the sources an
 * application supplies, and the built-in anchor provider {@code signed} ({@code SignedAnchorProvider}, the own
 * timestamp), which {@code StoreProviders} builds from the configuration when {@code anchor.provider} names it, its
 * anchor key taken from one of those signing key sources.
 */
module de.cuioss.git.document.store {
    exports de.cuioss.git.document.store;

    requires transitive de.cuioss.git.document.core;
    requires transitive de.cuioss.java.tools;
    requires static lombok;
    requires static org.jspecify;

    uses de.cuioss.git.document.store.SigningKeySource;
    uses de.cuioss.git.document.store.AnchorProvider;
    uses de.cuioss.git.document.store.PersonDirectory;

    provides de.cuioss.git.document.store.SigningKeySource with de.cuioss.git.document.store.FileSigningKeySource;
}
