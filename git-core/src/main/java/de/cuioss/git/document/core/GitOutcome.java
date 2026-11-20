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

/**
 * The closed list of Git outcomes (GDR-GEN-3).
 * <p>
 * Every operation of the foundation whose expected outcomes include a non-success reports one of these constants in
 * a {@link GitResult}; an expected non-success is never signalled by an exception. The list holds Git outcomes only
 * and carries no domain term; it includes the outcomes raised by the {@code worktrees} package. {@link #FAILED} is an
 * I/O failure outside the other outcomes.
 *
 * @since 0.1
 */
public enum GitOutcome {

    /** Success: the operation completed. Raised by every operation. */
    OK,

    /**
     * Conflict: a concurrent change could not be reconciled. Raised by compare-and-swap after the last attempt and by
     * the in-core merge.
     */
    CONFLICT,

    /**
     * Rejected: {@code push} was refused by the remote (non-fast-forward, a protected branch or another refusal of the
     * remote), or a reference update was refused locally.
     */
    REJECTED,

    /** Lease refused: {@code push} with a lease, the remote reference moved away from the expected value. */
    LEASE_REFUSED,

    /**
     * Unreachable: {@code fetch}, {@code push}, {@code cloneIfMissing} or {@code fetchCommit} could not reach the
     * remote.
     */
    UNREACHABLE,

    /**
     * Credentials expired or invalid: {@code fetch}, {@code push}, {@code cloneIfMissing} or {@code fetchCommit} was
     * refused authentication by the remote.
     */
    CREDENTIALS_INVALID,

    /**
     * Refused by policy: a remote outside the {@link RemotePolicy} or a push to more than one destination (refused
     * before any contact), or a capability the
     * {@link RefusingFS} refuses (a present hook or a process start). The detail names the refused remote or
     * capability. Raised for a remote only by the operations that contact one ({@code fetch}, {@code push},
     * {@code cloneIfMissing} and {@code fetchCommit}), and for a capability by an operation that would run the hook or
     * start the process. {@code open} checks no configured remote: it reports a valid repository as {@link #OK}
     * whatever its remotes are.
     */
    REFUSED_BY_POLICY,

    /**
     * Not found: an unknown revision, reference, remote or path; the detail names which. Raised by {@code log},
     * {@code fetch}, {@code push}, {@code fetchCommit} and {@code readFile}.
     */
    NOT_FOUND,

    /** Unserved: {@code fetchCommit}, the remote does not serve the requested commit. */
    UNSERVED,

    /**
     * Ambiguous: a name resolves as two references, or as a reference and a full object id; the detail lists the
     * candidates. Raised by identity resolution, e.g. in {@code log}.
     */
    AMBIGUOUS,

    /**
     * Not a repository: the path is not a Git repository. Raised by {@code open}, {@code log}, {@code fetch},
     * {@code push}, {@code cloneIfMissing} and the cache repository operations.
     */
    NOT_A_REPOSITORY,

    /** Identity mismatch: the commit an operation reached differs from the pinned commit it was meant to reach. */
    IDENTITY_MISMATCH,

    /** Failed: an I/O failure outside the outcomes above. Raised by every operation. */
    FAILED,

    /**
     * Nothing to commit: a commit from a working tree with nothing staged or changed. Raised by the {@code worktrees}
     * package.
     */
    NOTHING_TO_COMMIT,

    /**
     * Already exists: the target path or the branch of a new working tree exists. Raised by the {@code worktrees}
     * package.
     */
    ALREADY_EXISTS,

    /**
     * Branch checked out: the branch is checked out in another working tree. Raised by the {@code worktrees} package.
     */
    BRANCH_CHECKED_OUT,

    /** Working tree not found: no linked working tree at that path. Raised by the {@code worktrees} package. */
    WORKTREE_NOT_FOUND,

    /**
     * Working tree dirty: the working tree has uncommitted or untracked changes. Raised by the {@code worktrees}
     * package.
     */
    WORKTREE_DIRTY,

    /** Working tree locked: the working tree is locked. Raised by the {@code worktrees} package. */
    WORKTREE_LOCKED,

    /**
     * Path refused: a derived path lies outside the permitted root or is the main working tree. Raised by the
     * {@code worktrees} package.
     */
    PATH_REFUSED
}
