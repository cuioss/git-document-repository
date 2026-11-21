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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.git.document.core.RefusingFS;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.core.TreeEdit;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;

/**
 * The write path of one repository of the store: drafts per user and document (GDR-STORE-2) and the explicit save
 * with lease, conflicts and change detection (GDR-STORE-3, GDR-STORE-4, GDR-STORE-5), and the expiry of drafts
 * (GDR-STORE-7).
 * <p>
 * {@link #open} clones the remote of the repository into a bare clone if none exists yet, with the push reference
 * specifications {@link StoreReferences#PUSH_REF_SPECS} (never {@code refs/drafts/*}, ADR-0003) and the credentials of
 * the technical user, and opens the clone on a {@link RefusingFS}, so no operation on the clone starts a process
 * (GDR-CORE-1). That covers the clone only: JGit 7.8.0 reads the user and system configuration through the process-wide
 * {@code SystemReader}, whose default runs {@code git} once per JVM to locate the system configuration, so an embedding
 * application that needs the guarantee for its whole JVM installs
 * {@link RefusingFS#systemReader(org.eclipse.jgit.util.SystemReader)} before any JGit use. An
 * existing clone is reused only when every fetch and push destination of its {@code origin} is the registered remote;
 * a clone of another repository at the path is refused. Every commit identity comes from the section {@code commit}
 * of the configuration and the technical user.
 * <p>
 * Every expected non-success is a {@link StoreResult} or a {@link SaveResult}; a Git outcome is passed on. Draft writes
 * and saves take the shared side of the clone's maintenance lock, and every save attempt its transport lock; the
 * expiry, its deletes and its garbage collection, takes the exclusive side. The locks belong to the clone, not to the
 * writer: every writer of the JVM that opens the same git directory (by its real path) shares them, so the garbage
 * collection of one writer never runs while another writer of the clone holds objects not yet referenced. No
 * operation takes a credential: every fetch and push uses those of the technical user (GDR-STORE-8). The writer holds
 * the opened clone and releases it on {@link #close()}.
 *
 * @since 0.1
 */
public final class StoreWriter implements AutoCloseable {

    /** The detail of a clone whose remote is not the registered one; it names no URL, credential or path. */
    static final String FOREIGN_CLONE = "clone: remote does not match the registry";

    private final String alias;
    private final Repository repository;
    private final RepositoryGuard guard;
    private final DraftReferences drafts;
    private final ExplicitSave saves;
    private final DraftExpiry expiry;

    private StoreWriter(String alias, Repository repository, RepositoryGuard guard, DraftReferences drafts,
            ExplicitSave saves, DraftExpiry expiry) {
        this.alias = alias;
        this.repository = repository;
        this.guard = guard;
        this.drafts = drafts;
        this.saves = saves;
        this.expiry = expiry;
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
     *         example {@link GitOutcome#UNREACHABLE}, {@link GitOutcome#CREDENTIALS_INVALID},
     *         {@link GitOutcome#REFUSED_BY_POLICY}, {@link GitOutcome#FAILED}, or {@link GitOutcome#NOT_A_REPOSITORY}
     *         with the detail {@code <alias>: clone: remote does not match the registry} for an existing clone whose
     *         remote is not the registered one; no detail names a URL, a credential or a path
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
        if (!transportsTo(repository, storeRepository.remote())) {
            repository.close();
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.NOT_A_REPOSITORY), null,
                    storeRepository.alias() + ": " + FOREIGN_CLONE);
        }
        var identities = new CommitIdentities(configuration.commit(), user);
        var updater = new ReferenceUpdater(new InCoreWriter());
        int attempts = configuration.save().maxAttempts();
        var drafts = new DraftReferences(repository, updater, identities, clock, attempts);
        var guard = RepositoryGuard.of(clone);
        var saves = new ExplicitSave(new ExplicitSave.Clone(storeRepository.alias(), repository, clone, guard),
                operations, user, identities, clock, attempts);
        var expiry = new DraftExpiry(storeRepository.alias(), repository, guard, updater,
                configuration.drafts().expiry(), attempts);
        return StoreResult.ok(new StoreWriter(storeRepository.alias(), repository, guard, drafts, saves, expiry));
    }

    /**
     * Tells whether every fetch and push of the clone's {@code origin} goes to the registered remote: exactly one
     * fetch URL, and every push URL (the fetch URL when none is configured), equal to it. JGit 7.8.0
     * {@code RemoteConfig} applies {@code url.<base>.insteadOf} to the fetch URLs and {@code pushInsteadOf} to the
     * push URLs it derives, so the comparison sees the destinations a transport would contact.
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

    /**
     * Saves the draft of the author for the document onto its target branch (GDR-STORE-3): the draft's changes as one
     * commit on the remote tip, pushed with lease by the technical user, re-applied onto a new tip up to
     * {@code save.max-attempts}; then the local target branch moves and the draft is deleted by compare-and-delete.
     * Every changed path with an expected blob id that differs on the fetched tip is a conflict (GDR-STORE-4,
     * GDR-STORE-5); a changed path without one overwrites a concurrent change.
     *
     * @param author          the author, whose pseudonym names the draft reference and the commit's author
     * @param key             the document or entry
     * @param expectedBlobIds the full blob id the caller loaded per repository-relative path; the zero id expects the
     *                        path to be absent; empty for no check
     * @param message         the commit message
     * @return {@link SaveResult.Saved} with the commit the target branch points at, {@link SaveResult.Conflicting}
     *         with every conflicting path, or {@link SaveResult.NotSaved} with the outcome that prevented the save, for
     *         example {@link StoreOutcome#MALFORMED} for an invalid pseudonym,
     *         {@link de.cuioss.git.document.core.GitOutcome#NOT_FOUND} for a missing draft,
     *         {@link de.cuioss.git.document.core.GitOutcome#REJECTED} after the last attempt,
     *         {@link de.cuioss.git.document.core.GitOutcome#UNREACHABLE} or
     *         {@link de.cuioss.git.document.core.GitOutcome#CREDENTIALS_INVALID}
     * @throws NullPointerException if an argument, a path or an expected blob id is {@code null}
     */
    public SaveResult save(Author author, DocumentKey key, Map<String, String> expectedBlobIds, String message) {
        return saves.save(author, key, expectedBlobIds, message);
    }

    /**
     * Deletes every draft unchanged for {@code drafts.expiry} and removes the objects reachable only from deleted
     * references (GDR-STORE-7): a draft whose tip has a committer time at or before {@code now} minus
     * {@code drafts.expiry} (UTC) is deleted by compare-and-delete expecting that tip, so a draft changed meanwhile is
     * kept; then a garbage collection runs. Both run under the exclusive side of the clone's maintenance lock, so no
     * draft write or save of this writer runs meanwhile.
     *
     * @param now the current time
     * @return {@link StoreOutcome#OK} with the full names of the deleted draft references, sorted, possibly none; or
     *         the passed-on {@link de.cuioss.git.document.core.GitOutcome#FAILED} of a failed read, delete or garbage
     *         collection
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public StoreResult<List<String>> expireDrafts(Instant now) {
        return expiry.expire(now);
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
