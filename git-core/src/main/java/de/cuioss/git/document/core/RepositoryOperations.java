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

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;


import lombok.Builder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * The operations on a repository: one method per operation, typed inputs, and results carrying the closed
 * {@link GitOutcome} (GDR-GEN-3).
 * <p>
 * Every repository an implementation opens or creates is built on {@link RefusingFS}, so no operation starts a
 * process (GDR-CORE-1); transport operations contact only remotes the {@link RemotePolicy} admits (GDR-CORE-2).
 * Credentials are passed per call as a JGit {@link CredentialsProvider} and are never stored, logged or put into a
 * result detail (GDR-CORE-7, GDR-NFR-3).
 *
 * @since 0.1
 */
public interface RepositoryOperations {

    /**
     * Opens a repository: the one whose working tree contains {@code root}, or the bare repository whose git
     * directory is {@code root}.
     *
     * @param root a working-tree root or the git directory of a bare repository
     * @return {@link GitOutcome#OK} with the repository facts, {@link GitOutcome#NOT_A_REPOSITORY} or
     *         {@link GitOutcome#FAILED}
     */
    GitResult<RepositoryInfo> open(Path root);

    /**
     * Initialises an empty repository with a working tree, or opens the one at {@code root}.
     *
     * @param root          the working-tree root to create
     * @param initialBranch the name of the initial branch, e.g. {@code main}
     * @return {@link GitOutcome#OK} with the repository facts, {@link GitOutcome#REFUSED_BY_POLICY} or
     *         {@link GitOutcome#FAILED}
     */
    GitResult<RepositoryInfo> init(Path root, String initialBranch);

    /**
     * Creates a bare clone of a remote if none exists at the git directory (GDR-CORE-3). An existing bare repository
     * there is opened and returned without contacting the remote. Otherwise a bare repository is initialised, its
     * remote {@code origin} is configured with the mirroring fetch reference specifications
     * {@code +refs/heads/*:refs/heads/*}, {@code +refs/tags/*:refs/tags/*} and {@code +refs/notes/*:refs/notes/*} and
     * the caller's push reference specifications, and it is fetched. A clone that could not be completed is removed.
     *
     * @param input the input
     * @return {@link GitOutcome#OK} with the repository facts, {@link GitOutcome#NOT_A_REPOSITORY} if the directory
     *         holds anything but a bare repository, {@link GitOutcome#REFUSED_BY_POLICY} if the policy does not admit
     *         the URL (nothing was sent) or a capability was refused, {@link GitOutcome#UNREACHABLE},
     *         {@link GitOutcome#CREDENTIALS_INVALID} or {@link GitOutcome#FAILED}
     */
    GitResult<RepositoryInfo> cloneIfMissing(CloneInput input);

    /**
     * The history of a revision, newest first. The revision is classified by {@link GitIdentity}: an ambiguous name is
     * refused with its candidates in the detail, and only a supported identity is resolved, exactly one reference or
     * a full commit id; an abbreviated commit id and a revision expression are never resolved (GDR-CORE-11).
     *
     * @param root     a working-tree root or the git directory of a bare repository
     * @param revision the start revision: {@code HEAD}, a reference by its short or full name, or a full commit id
     * @param maxCount the maximum number of commits; a value below 1 is taken as 1
     * @return {@link GitOutcome#OK} with the commits, {@link GitOutcome#NOT_FOUND} for an unknown, abbreviated or
     *         unsupported revision and for a full commit id absent from the repository, {@link GitOutcome#AMBIGUOUS},
     *         {@link GitOutcome#IDENTITY_MISMATCH},
     *         {@link GitOutcome#NOT_A_REPOSITORY} or {@link GitOutcome#FAILED}
     */
    GitResult<List<CommitInfo>> log(Path root, String revision, int maxCount);

    /**
     * Fetches from a configured remote.
     *
     * @param input the input
     * @return {@link GitOutcome#OK} with the updated references, {@link GitOutcome#REFUSED_BY_POLICY} (nothing was
     *         sent), {@link GitOutcome#NOT_FOUND} for an unknown remote, {@link GitOutcome#UNREACHABLE},
     *         {@link GitOutcome#CREDENTIALS_INVALID}, {@link GitOutcome#NOT_A_REPOSITORY} or {@link GitOutcome#FAILED}
     */
    GitResult<List<RefUpdate>> fetch(FetchInput input);

    /**
     * Pushes one reference to a configured remote, optionally bound to the expected remote head (lease). The local
     * reference is classified by {@link GitIdentity} as in {@link #log(Path, String, int)}: only exactly one reference
     * or a full commit id of the repository is pushed (GDR-CORE-11).
     *
     * @param input the input
     * @return {@link GitOutcome#OK} with the reference update, {@link GitOutcome#REJECTED},
     *         {@link GitOutcome#LEASE_REFUSED}, {@link GitOutcome#REFUSED_BY_POLICY} (nothing was sent, or a hook was
     *         refused), {@link GitOutcome#AMBIGUOUS} for an ambiguous local reference, {@link GitOutcome#NOT_FOUND}
     *         for an unknown remote or an unknown, abbreviated or unsupported local reference,
     *         {@link GitOutcome#UNREACHABLE}, {@link GitOutcome#CREDENTIALS_INVALID},
     *         {@link GitOutcome#NOT_A_REPOSITORY} or {@link GitOutcome#FAILED}
     */
    GitResult<RefUpdate> push(PushInput input);

    /**
     * Creates the bare cache repository at the path, or opens the one there (GDR-CORE-8). A cache repository holds the
     * pinned commits of a source outside every working tree.
     *
     * @param cache the git directory of the cache repository
     * @return {@link GitOutcome#OK} with the canonical git directory, {@link GitOutcome#NOT_A_REPOSITORY} if the path
     *         holds anything but a bare repository, or {@link GitOutcome#FAILED}
     */
    GitResult<Path> initCache(Path cache);

    /**
     * Fetches exactly one pinned commit, by its full id, from a URL into a cache repository, without credentials and
     * within a transport timeout (GDR-CORE-8). A commit already in the cache is not fetched again, and no remote is
     * contacted for it.
     *
     * @param input the input
     * @return {@link GitOutcome#OK} with the commit id, {@link GitOutcome#REFUSED_BY_POLICY} if the policy does not
     *         admit the URL or the transport would start a process (nothing was sent), {@link GitOutcome#NOT_FOUND} if
     *         the commit is no full commit id, {@link GitOutcome#UNSERVED} if the remote does not serve the commit,
     *         {@link GitOutcome#UNREACHABLE} if the remote did not answer, {@link GitOutcome#NOT_A_REPOSITORY} if the
     *         cache is no repository, or {@link GitOutcome#FAILED}
     */
    GitResult<String> fetchCommit(FetchCommitInput input);

    /**
     * Reads the raw blob of a path at a commit of a cache repository, as it is stored: no filter runs, so a file kept
     * in Git LFS is read as its pointer (GDR-CORE-8).
     *
     * @param input the input
     * @return {@link GitOutcome#OK} with the bytes, {@link GitOutcome#NOT_FOUND} if the commit is not in the cache or
     *         the path is no regular file at the commit, {@link GitOutcome#NOT_A_REPOSITORY} if the cache is no
     *         repository, or {@link GitOutcome#FAILED}
     */
    GitResult<byte[]> readFile(ReadFileInput input);

    /**
     * Input of {@link #fetchCommit(FetchCommitInput)}.
     *
     * @param cache  the git directory of the cache repository
     * @param url    the URL of the repository the commit is fetched from
     * @param commit the full id of the commit
     * @param policy the remotes the fetch may contact
     * @since 0.1
     */
    @Builder
    record FetchCommitInput(Path cache, String url, String commit, RemotePolicy policy) {

        /**
         * Requires every value.
         *
         * @param cache  the git directory of the cache repository
         * @param url    the URL
         * @param commit the commit id
         * @param policy the remote policy
         * @throws NullPointerException if a value is {@code null}
         */
        public FetchCommitInput {
            Objects.requireNonNull(cache, "cache");
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(commit, "commit");
            Objects.requireNonNull(policy, "policy");
        }
    }

    /**
     * Input of {@link #readFile(ReadFileInput)}.
     *
     * @param cache  the git directory of the cache repository
     * @param commit the full id of the commit
     * @param path   the repository-relative path of the file
     * @since 0.1
     */
    record ReadFileInput(Path cache, String commit, String path) {

        /**
         * Requires every value.
         *
         * @param cache  the git directory of the cache repository
         * @param commit the commit id
         * @param path   the path
         * @throws NullPointerException if a value is {@code null}
         */
        public ReadFileInput {
            Objects.requireNonNull(cache, "cache");
            Objects.requireNonNull(commit, "commit");
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * Facts of an opened repository.
     *
     * @param worktreeRoot the canonical working-tree root; {@code null} for a bare repository
     * @param gitDir       the canonical git directory
     * @param commonDir    the canonical common git directory
     * @param head         the {@code HEAD} commit id; {@code null} for an unborn branch
     * @param branch       the branch {@code HEAD} points to; {@code null} when detached
     * @param linked       whether this is a linked working tree
     * @since 0.1
     */
    @Builder
    record RepositoryInfo(@Nullable Path worktreeRoot, Path gitDir, Path commonDir, @Nullable String head,
    @Nullable String branch, boolean linked) {

        /**
         * Requires the git directories.
         *
         * @param worktreeRoot the working-tree root
         * @param gitDir       the git directory
         * @param commonDir    the common git directory
         * @param head         the head commit id
         * @param branch       the branch
         * @param linked       whether this is a linked working tree
         * @throws NullPointerException if {@code gitDir} or {@code commonDir} is {@code null}
         */
        public RepositoryInfo {
            Objects.requireNonNull(gitDir, "gitDir");
            Objects.requireNonNull(commonDir, "commonDir");
        }
    }

    /**
     * A person of a commit.
     *
     * @param name  the name
     * @param email the e-mail address
     * @since 0.1
     */
    record Identity(String name, String email) {

        /**
         * Requires every value.
         *
         * @param name  the name
         * @param email the e-mail address
         * @throws NullPointerException if a value is {@code null}
         */
        public Identity {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(email, "email");
        }
    }

    /**
     * A commit.
     *
     * @param id          the full commit id
     * @param parents     the full ids of the parents
     * @param author      the author
     * @param authoredAt  the author time
     * @param committer   the committer
     * @param committedAt the commit time
     * @param message     the full message
     * @since 0.1
     */
    @Builder
    record CommitInfo(String id, List<String> parents, Identity author, Instant authoredAt, Identity committer,
    Instant committedAt, String message) {

        /**
         * Requires every value and copies the parents.
         *
         * @param id          the commit id
         * @param parents     the parent ids
         * @param author      the author
         * @param authoredAt  the author time
         * @param committer   the committer
         * @param committedAt the commit time
         * @param message     the message
         * @throws NullPointerException if a value is {@code null}
         */
        public CommitInfo {
            Objects.requireNonNull(id, "id");
            parents = List.copyOf(Objects.requireNonNull(parents, "parents"));
            Objects.requireNonNull(author, "author");
            Objects.requireNonNull(authoredAt, "authoredAt");
            Objects.requireNonNull(committer, "committer");
            Objects.requireNonNull(committedAt, "committedAt");
            Objects.requireNonNull(message, "message");
        }
    }

    /**
     * A reference changed by a transport operation.
     *
     * @param ref    the local tracking or remote reference
     * @param oldId  the previous id; {@code null} when the reference was created
     * @param newId  the new id; {@code null} when the reference was deleted
     * @param status the status in lower case, e.g. {@code new}, {@code fast_forward}, {@code ok} or
     *               {@code up_to_date}
     * @since 0.1
     */
    @Builder
    record RefUpdate(String ref, @Nullable String oldId, @Nullable String newId, String status) {

        /**
         * Requires the reference and the status.
         *
         * @param ref    the reference
         * @param oldId  the previous id
         * @param newId  the new id
         * @param status the status
         * @throws NullPointerException if {@code ref} or {@code status} is {@code null}
         */
        public RefUpdate {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(status, "status");
        }
    }

    /**
     * Input of {@link #fetch(FetchInput)}.
     *
     * @param root        a working-tree root or the git directory of a bare repository
     * @param remote      the configured remote name, e.g. {@code origin}
     * @param refSpecs    the reference specifications; empty for the remote's configured ones
     * @param credentials the credentials of this call; {@code null} when none
     * @since 0.1
     */
    @Builder
    record FetchInput(Path root, String remote, List<String> refSpecs, @Nullable CredentialsProvider credentials) {

        /**
         * Requires the repository, the remote and the reference specifications, and copies the latter.
         *
         * @param root        the repository
         * @param remote      the remote name
         * @param refSpecs    the reference specifications
         * @param credentials the credentials
         * @throws NullPointerException if {@code root}, {@code remote} or {@code refSpecs} is {@code null}
         */
        public FetchInput {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(remote, "remote");
            refSpecs = List.copyOf(Objects.requireNonNull(refSpecs, "refSpecs"));
        }
    }

    /**
     * Input of {@link #push(PushInput)}.
     *
     * @param root               a working-tree root or the git directory of a bare repository
     * @param remote             the configured remote name, e.g. {@code origin}
     * @param localRef           the local reference or commit-ish to push
     * @param remoteRef          the full remote reference, e.g. {@code refs/heads/feature}
     * @param expectedRemoteHead the full id the remote reference is expected at (lease); {@code null} for a plain push
     * @param credentials        the credentials of this call; {@code null} when none
     * @since 0.1
     */
    @Builder
    record PushInput(Path root, String remote, String localRef, String remoteRef, @Nullable String expectedRemoteHead,
    @Nullable CredentialsProvider credentials) {

        /**
         * Requires the repository, the remote and both references.
         *
         * @param root               the repository
         * @param remote             the remote name
         * @param localRef           the local reference
         * @param remoteRef          the remote reference
         * @param expectedRemoteHead the expected remote head
         * @param credentials        the credentials
         * @throws NullPointerException if {@code root}, {@code remote}, {@code localRef} or {@code remoteRef} is
         *                              {@code null}
         */
        public PushInput {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(remote, "remote");
            Objects.requireNonNull(localRef, "localRef");
            Objects.requireNonNull(remoteRef, "remoteRef");
        }
    }

    /**
     * Input of {@link #cloneIfMissing(CloneInput)}.
     *
     * @param gitDir       the git directory of the bare clone
     * @param url          the URL of the remote; a password in it is never stored
     * @param pushRefSpecs the push reference specifications of remote {@code origin}; empty for none
     * @param credentials  the credentials of this call; {@code null} when none
     * @since 0.1
     */
    @Builder
    record CloneInput(Path gitDir, String url, List<String> pushRefSpecs, @Nullable CredentialsProvider credentials) {

        /**
         * Requires the git directory, the URL and the push reference specifications, and copies the latter.
         *
         * @param gitDir       the git directory
         * @param url          the URL
         * @param pushRefSpecs the push reference specifications
         * @param credentials  the credentials
         * @throws NullPointerException if {@code gitDir}, {@code url} or {@code pushRefSpecs} is {@code null}
         */
        public CloneInput {
            Objects.requireNonNull(gitDir, "gitDir");
            Objects.requireNonNull(url, "url");
            pushRefSpecs = List.copyOf(Objects.requireNonNull(pushRefSpecs, "pushRefSpecs"));
        }

        /**
         * Renders the input without the URL's user information and without the credentials.
         *
         * @return the rendering
         */
        @Override
        public String toString() {
            return "CloneInput[gitDir=" + gitDir + ", pushRefSpecs=" + pushRefSpecs + "]";
        }
    }
}
