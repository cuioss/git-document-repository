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

import java.io.IOException;
import java.util.List;
import java.util.Objects;


import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jspecify.annotations.Nullable;

/**
 * Moves a reference by compare-and-swap (GDR-CORE-5; checked against JGit 7.8.0).
 * <p>
 * An attempt reads the reference exactly ({@code Repository.exactRef(String)}), hands the tree of its commit to the
 * {@link TreeOperation}, writes the next commit through the {@link InCoreWriter} with the reference's commit as the
 * only parent (none for a reference that does not exist yet), and moves the reference through a {@link RefUpdate} that
 * expects the id it read ({@code setExpectedOldObjectId}, {@link ObjectId#zeroId()} for a reference that must not
 * exist), never forces ({@code setForceUpdate} is never called) and writes no reflog ({@code disableRefLog()}).
 * <p>
 * The window between reading the reference and moving it is closed by JGit, not by a check of this class: JGit 7.8.0
 * {@code RefUpdate.updateImpl} compares the expected old id with the reference's id while it holds the reference's
 * lock, and reports a mismatch, like a lock held by another writer, as {@link RefUpdate.Result#LOCK_FAILURE}. Such a
 * lost race starts the next attempt, which reads the reference again and runs the operation again on the tree the
 * concurrent writer left. The commit written by a lost attempt stays in the object database unreferenced.
 * <p>
 * A detail names the reference and the {@link RefUpdate.Result}, never content or an identity (GDR-NFR-3).
 *
 * @since 0.1
 */
public final class ReferenceUpdater {

    private static final CuiLogger LOGGER = new CuiLogger(ReferenceUpdater.class);

    private final InCoreWriter writer;

    /**
     * Creates the updater.
     *
     * @param writer the writer of the commits; stateless, so one may serve several updaters
     * @throws NullPointerException if {@code writer} is {@code null}
     */
    public ReferenceUpdater(InCoreWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * Writes the next commit of a reference and moves the reference to it, provided no other writer moved the
     * reference in between; a lost race is retried until the attempts are used up.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the reference was created ({@link RefUpdate.Result#NEW}) or moved
     * ({@link RefUpdate.Result#FAST_FORWARD}); the value is the full id of the new commit;</li>
     * <li>{@link GitOutcome#CONFLICT}: every attempt lost its race ({@link RefUpdate.Result#LOCK_FAILURE}); the reference
     * is left as the concurrent writers left it;</li>
     * <li>{@link GitOutcome#FAILED}: an I/O failure, including {@link RefUpdate.Result#IO_FAILURE}, which is neither
     * retried nor reported as a conflict;</li>
     * <li>{@link GitOutcome#REJECTED}: the update ended in any other {@link RefUpdate.Result}; the detail names it;</li>
     * <li>the result of the {@link InCoreWriter} when it did not write the commit, e.g. {@link GitOutcome#NOT_FOUND}
     * for an edit with an invalid path; the reference is not touched.</li>
     * </ul>
     *
     * @param repository the repository
     * @param refName    the full name of the reference, e.g. {@code refs/heads/main}
     * @param metadata   author, committer, message and signing of every commit written
     * @param operation  derives the edits from the reference's current tree; called once per attempt
     * @param attempts   the total number of tries, at least 1
     * @return the full id of the commit the reference now points at, or the outcome that prevented it
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code attempts} is less than 1
     */
    public GitResult<String> compareAndSwap(Repository repository, String refName, CommitMetadata metadata,
            TreeOperation operation, int attempts) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(operation, "operation");
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts must be at least 1, was " + attempts);
        }
        try {
            for (int attempt = 1; attempt <= attempts; attempt++) {
                Ref current = repository.exactRef(refName);
                @Nullable ObjectId expected = current == null ? null : current.getObjectId();
                @Nullable RevCommit parent = expected == null ? null : parseCommit(repository, expected);
                List<TreeEdit> edits = operation.apply(parent == null ? null : parent.getTree().name());
                GitResult<String> written = writer.write(repository,
                        parent == null ? List.of() : List.of(parent.name()), metadata, edits);
                if (!written.isOk()) {
                    return written;
                }
                ObjectId commit = ObjectId.fromString(Objects.requireNonNull(written.value()));
                RefUpdate.Result result = update(repository, refName, expected, commit);
                switch (result) {
                    case NEW, FAST_FORWARD -> {
                        return GitResult.ok(commit.name());
                    }
                    case LOCK_FAILURE -> LOGGER.debug("Compare-and-swap of %s lost its race, attempt %s of %s",
                            refName, attempt, attempts);
                    case IO_FAILURE -> {
                        return GitResult.of(GitOutcome.FAILED, refName + ": " + result);
                    }
                    default -> {
                        return GitResult.of(GitOutcome.REJECTED, refName + ": " + result);
                    }
                }
            }
            return GitResult.of(GitOutcome.CONFLICT,
                    refName + ": " + RefUpdate.Result.LOCK_FAILURE + " on every attempt (" + attempts + ")");
        } catch (IOException e) {
            return GitResult.of(GitOutcome.FAILED, e.getClass().getSimpleName());
        }
    }

    private static RevCommit parseCommit(Repository repository, ObjectId id) throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            return walk.parseCommit(id);
        }
    }

    private static RefUpdate.Result update(Repository repository, String refName, @Nullable ObjectId expected,
            ObjectId commit) throws IOException {
        RefUpdate update = repository.updateRef(refName);
        update.setExpectedOldObjectId(expected == null ? ObjectId.zeroId() : expected);
        update.setNewObjectId(commit);
        update.disableRefLog();
        return update.update();
    }
}
