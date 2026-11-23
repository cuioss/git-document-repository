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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import org.eclipse.jgit.dircache.InvalidPathException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.NoMergeBaseException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.NameConflictTreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * Merges two commits at path level, in core, without a working tree (GDR-CORE-6; checked against JGit 7.8.0).
 * <p>
 * The merge runs JGit's {@code MergeStrategy.SIMPLE_TWO_WAY_IN_CORE} on a {@link ThreeWayMerger}, never the recursive
 * strategy (ADR-0001): no textual content is merged. JGit 7.8.0 {@code StrategySimpleTwoWayInCore.InCoreMerger} walks
 * the merge base and both tips with a {@code NameConflictTreeWalk}: an entry equal on both sides, or unchanged on one
 * side, is taken as it is, a whole subtree included; a subtree changed on both sides is entered; any other entry that
 * is no tree on one of the three sides is a conflict, a file on one side and a directory on the other included.
 * <p>
 * The merge base is the one merge base of the two commits ({@code Merger.getBaseCommit} with
 * {@code RevFilter.MERGE_BASE}); two commits without a common ancestor merge against the empty tree, and more than one
 * merge base is refused by JGit with a {@link NoMergeBaseException} of reason
 * {@code MULTIPLE_MERGE_BASES_NOT_SUPPORTED}. A clean merge inserts the merged tree through the merger's own inserter
 * and flushes it; no commit is written and no reference is moved.
 * <p>
 * The merger reports no conflicting paths, so on failure the library computes them itself, with the walk the merger
 * runs (the package-private {@code conflictingPathsOf}): a path is conflicting
 * when its entry, object id or file mode, differs from the merge base on both sides and differs between the sides.
 * <p>
 * A detail names the field and the reason, never a path or content (GDR-NFR-3); a failure detail names the exception
 * class only.
 *
 * @since 0.1
 */
public final class InCoreMerger {

    /** The input field of our commit. */
    private static final String OURS = "ours";

    /** The input field of their commit. */
    private static final String THEIRS = "theirs";

    /** The tree positions of the walk over the merge base and the two tips. */
    private static final int BASE = 0;
    private static final int OURS_TREE = 1;
    private static final int THEIRS_TREE = 2;

    /** Creates the merger. It holds no state, so one instance may serve every repository and thread. */
    public InCoreMerger() {
        // stateless
    }

    /**
     * Merges two commits at path level.
     * <p>
     * The results:
     * <ul>
     * <li>{@link MergeResult.Merged}: the merge is clean; the value is the full id of the merged tree, written to the
     * object database;</li>
     * <li>{@link MergeResult.Conflicting}: the merge conflicts; the value is every conflicting path, sorted;</li>
     * <li>{@link MergeResult.NotMerged} with {@link GitOutcome#NOT_FOUND}: a commit id is no full commit id or no
     * commit of the repository (the id of an annotated tag or a tree included); the detail names the field
     * ({@code ours} or {@code theirs});</li>
     * <li>{@link MergeResult.NotMerged} with {@link GitOutcome#CONFLICT}: the commits have more than one merge base;
     * detail {@code merge base: more than one}, no paths, because none were computed;</li>
     * <li>{@link MergeResult.NotMerged} with {@link GitOutcome#FAILED}: an I/O failure, or a tree of the merge base or
     * a tip holding a path JGit refuses, such as {@code .GIT} or {@code git~1} (JGit 7.8.0
     * {@code StrategySimpleTwoWayInCore} builds a {@code DirCacheEntry} per entry it takes, whose path check throws an
     * {@code InvalidPathException}), or a tree too large to be read in one piece ({@code LargeObjectException}: JGit
     * 7.8.0 {@code CanonicalTreeParser.reset} reads a tree through {@code ObjectLoader.getCachedBytes()}, which throws
     * it above the streaming threshold); the detail names the exception class.</li>
     * </ul>
     *
     * @param repository the repository holding both commits
     * @param ours       the full id of our commit
     * @param theirs     the full id of their commit
     * @return the merged tree, the conflicting paths, or the outcome that prevented the merge
     * @throws NullPointerException if an argument is {@code null}
     */
    public MergeResult merge(Repository repository, String ours, String theirs) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(ours, OURS);
        Objects.requireNonNull(theirs, THEIRS);
        if (!GitIdentity.isFullCommitId(ours)) {
            return new MergeResult.NotMerged(GitOutcome.NOT_FOUND, OURS + ": not a full commit id");
        }
        if (!GitIdentity.isFullCommitId(theirs)) {
            return new MergeResult.NotMerged(GitOutcome.NOT_FOUND, THEIRS + ": not a full commit id");
        }
        try (RevWalk walk = new RevWalk(repository)) {
            Optional<RevCommit> oursCommit = JGitRepositoryOperations.parseCommit(walk, ours);
            if (oursCommit.isEmpty()) {
                return new MergeResult.NotMerged(GitOutcome.NOT_FOUND, OURS + ": not found");
            }
            Optional<RevCommit> theirsCommit = JGitRepositoryOperations.parseCommit(walk, theirs);
            if (theirsCommit.isEmpty()) {
                return new MergeResult.NotMerged(GitOutcome.NOT_FOUND, THEIRS + ": not found");
            }
            ThreeWayMerger merger = MergeStrategy.SIMPLE_TWO_WAY_IN_CORE.newMerger(repository);
            if (merger.merge(oursCommit.get(), theirsCommit.get())) {
                return new MergeResult.Merged(merger.getResultTreeId().name());
            }
            @Nullable ObjectId base = merger.getBaseCommitId();
            @Nullable ObjectId baseTree = base == null ? null : walk.parseCommit(base).getTree();
            try (ObjectReader reader = repository.newObjectReader()) {
                List<String> paths = conflictingPathsOf(reader, baseTree, oursCommit.get().getTree(),
                        theirsCommit.get().getTree());
                return paths.isEmpty() ? new MergeResult.NotMerged(GitOutcome.CONFLICT, "merge: no conflicting path")
                        : new MergeResult.Conflicting(paths);
            }
        } catch (NoMergeBaseException e) {
            if (e.getReason() == NoMergeBaseException.MergeBaseFailureReason.MULTIPLE_MERGE_BASES_NOT_SUPPORTED) {
                return new MergeResult.NotMerged(GitOutcome.CONFLICT, "merge base: more than one");
            }
            return notMerged(e);
        } catch (IOException | InvalidPathException | LargeObjectException e) {
            return notMerged(e);
        }
    }

    /**
     * Computes the conflicting paths of a merge with the walk JGit 7.8.0 {@code StrategySimpleTwoWayInCore} runs.
     * <p>
     * A {@link NameConflictTreeWalk} over the merge base and both tips, so a file on one side and a directory of the
     * same name on the other meet as one entry: an entry equal on both sides, or equal to the base on one side, is
     * skipped, a subtree included; any other entry is conflicting when it is no tree on one of the three sides, and is
     * entered when it is a tree on one of them. The rule is therefore the merger's own: an entry, object id or file
     * mode, that differs from the base on both sides and between the sides.
     *
     * @param reader     the reader of the repository
     * @param baseTree   the tree of the merge base; {@code null} when the commits have no common ancestor
     * @param oursTree   the tree of our commit
     * @param theirsTree the tree of their commit
     * @return the conflicting paths in the walk's order
     * @throws IOException if a tree cannot be read
     */
    static List<String> conflictingPathsOf(ObjectReader reader, @Nullable AnyObjectId baseTree, AnyObjectId oursTree,
            AnyObjectId theirsTree) throws IOException {
        List<String> paths = new ArrayList<>();
        try (NameConflictTreeWalk walk = new NameConflictTreeWalk(reader)) {
            if (baseTree == null) {
                walk.addTree(new EmptyTreeIterator());
            } else {
                walk.addTree(baseTree);
            }
            walk.addTree(oursTree);
            walk.addTree(theirsTree);
            while (walk.next()) {
                int modeBase = walk.getRawMode(BASE);
                int modeOurs = walk.getRawMode(OURS_TREE);
                int modeTheirs = walk.getRawMode(THEIRS_TREE);
                boolean sidesEqual = modeOurs == modeTheirs && walk.idEqual(OURS_TREE, THEIRS_TREE);
                boolean oursUnchanged = modeBase == modeOurs && walk.idEqual(BASE, OURS_TREE);
                boolean theirsUnchanged = modeBase == modeTheirs && walk.idEqual(BASE, THEIRS_TREE);
                if (sidesEqual || oursUnchanged || theirsUnchanged) {
                    continue;
                }
                if (nonTree(modeBase) || nonTree(modeOurs) || nonTree(modeTheirs)) {
                    paths.add(walk.getPathString());
                }
                if (walk.isSubtree()) {
                    walk.enterSubtree();
                }
            }
        }
        return paths;
    }

    private static boolean nonTree(int mode) {
        return mode != 0 && !FileMode.TREE.equals(mode);
    }

    private static MergeResult notMerged(Exception e) {
        GitResult<String> failure = JGitRepositoryOperations.failure(e);
        return new MergeResult.NotMerged(failure.outcome(), Objects.requireNonNull(failure.detail()));
    }
}
