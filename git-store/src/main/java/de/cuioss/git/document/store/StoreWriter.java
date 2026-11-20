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
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;


import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.TreeEdit;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * The write path of one repository of the store: drafts per user and document (GDR-STORE-2).
 * <p>
 * {@link #open} clones the remote of the repository into a bare clone if none exists yet, with the push reference
 * specifications {@link StoreReferences#PUSH_REF_SPECS} (never {@code refs/drafts/*}, ADR-0003) and the credentials of
 * the technical user, and opens the clone on a {@link RefusingFS}, so no operation starts a process (GDR-CORE-1).
 * Every commit identity comes from the section {@code commit} of the configuration and the technical user.
 * <p>
 * Every expected non-success is a {@link StoreResult}; a Git outcome is passed on. Draft writes take the shared side of
 * the clone's maintenance lock. The writer holds the opened clone and releases it on {@link #close()}.
 *
 * @since 0.1
 */
public final class StoreWriter implements AutoCloseable {

    private final String alias;
    private final Repository repository;
    private final RepositoryGuard guard;
    private final DraftReferences drafts;

    private StoreWriter(String alias, Repository repository, RepositoryGuard guard, DraftReferences drafts) {
        this.alias = alias;
        this.repository = repository;
        this.guard = guard;
        this.drafts = drafts;
    }

    /**
     * Opens the write path of a repository, cloning its remote first if the bare clone does not exist yet.
     *
     * @param storeRepository the repository of the registry
     * @param gitDir          the git directory of the bare clone
     * @param user            the technical user: committer and holder of the transport credentials
     * @param configuration   the configuration of the store
     * @param operations      the repository operations of {@code git-core}
     * @param clock           the clock of every commit time
     * @return {@link StoreOutcome#OK} with the writer, or the passed-on Git outcome of the clone or of opening it, for
     *         example {@link de.cuioss.git.document.core.GitOutcome#UNREACHABLE},
     *         {@link de.cuioss.git.document.core.GitOutcome#CREDENTIALS_INVALID},
     *         {@link de.cuioss.git.document.core.GitOutcome#REFUSED_BY_POLICY} or
     *         {@link de.cuioss.git.document.core.GitOutcome#FAILED}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static StoreResult<StoreWriter> open(StoreRepository storeRepository, Path gitDir, TechnicalUser user,
            StoreConfiguration configuration, RepositoryOperations operations, Clock clock) {
        Objects.requireNonNull(storeRepository, "storeRepository");
        Objects.requireNonNull(gitDir, "gitDir");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(configuration, "configuration");
        Objects.requireNonNull(operations, "operations");
        Objects.requireNonNull(clock, "clock");
        var cloned = operations.cloneIfMissing(RepositoryOperations.CloneInput.builder()
                .gitDir(gitDir)
                .url(storeRepository.remote())
                .pushRefSpecs(StoreReferences.PUSH_REF_SPECS)
                .credentials(user.credentials())
                .build());
        if (!cloned.isOk()) {
            return StoreResult.passOn(cloned);
        }
        Path clone = Objects.requireNonNull(cloned.value()).gitDir();
        Repository repository;
        try {
            repository = new FileRepositoryBuilder().setGitDir(clone.toFile()).setFS(new RefusingFS())
                    .setMustExist(true).build();
        } catch (IOException e) {
            return StoreResult.passOn(DraftReferences.<StoreWriter>failure(storeRepository.alias(), e));
        }
        var identities = new CommitIdentities(configuration.commit(), user);
        var updater = new ReferenceUpdater(new InCoreWriter());
        var drafts = new DraftReferences(repository, updater, identities, clock, configuration.save().maxAttempts());
        return StoreResult.ok(new StoreWriter(storeRepository.alias(), repository, new RepositoryGuard(), drafts));
    }

    /**
     * Returns the alias of the repository this writer writes to.
     *
     * @return the alias
     */
    public String alias() {
        return alias;
    }

    /**
     * Writes an intermediate save: the next commit of {@code refs/drafts/<pseudonym>/<collection>/<id>}, created at
     * the tip of the local target branch (else {@code main}) when the user has no draft of the document yet
     * (GDR-STORE-2). The draft stays in the clone and is never pushed.
     *
     * @param author  the author, whose pseudonym names the draft reference
     * @param key     the document or entry
     * @param edits   the edits, applied onto the draft's current tree
     * @param message the commit message
     * @return {@link StoreOutcome#OK} with the full id of the draft commit, {@link StoreOutcome#MALFORMED} for a
     *         pseudonym outside {@code [A-Za-z0-9-]+}, or the passed-on Git outcome that prevented the write
     * @throws NullPointerException if an argument is {@code null}
     */
    public StoreResult<String> draft(Author author, DocumentKey key, List<TreeEdit> edits, String message) {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(edits, "edits");
        Objects.requireNonNull(message, "message");
        return guard.shared(() -> drafts.write(author, key, edits, message));
    }

    /**
     * Lists the drafts of a user, sorted by reference name (GDR-STORE-2).
     *
     * @param pseudonym the pseudonym of the user
     * @return {@link StoreOutcome#OK} with the drafts, possibly none; {@link StoreOutcome#MALFORMED} for a pseudonym
     *         outside {@code [A-Za-z0-9-]+}; or the passed-on {@link de.cuioss.git.document.core.GitOutcome#FAILED}
     * @throws NullPointerException if {@code pseudonym} is {@code null}
     */
    public StoreResult<List<Draft>> drafts(String pseudonym) {
        Objects.requireNonNull(pseudonym, "pseudonym");
        return guard.shared(() -> drafts.list(pseudonym));
    }

    /** Releases the opened clone. */
    @Override
    public void close() {
        repository.close();
    }

    /**
     * Renders the alias only.
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "StoreWriter[alias=" + alias + "]";
    }
}
