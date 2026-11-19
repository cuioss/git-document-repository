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


import de.cuioss.git.document.core.RepositoryOperations.CommitInfo;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.AndTreeFilter;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;

/**
 * Reads a repository without a working tree: the files of a commit, the history of a path and the paths changed
 * between two commits (GDR-CORE-9; checked against JGit 7.8.0).
 * <p>
 * The reader takes a JGit {@link Repository}, so it reads an in-memory repository as well as one on disk, and changes
 * nothing: it writes no object and no reference.
 * <p>
 * A revision of {@link #snapshot(Repository, String)} and {@link #history(Repository, String, String, int)} is a
 * reference, by its short or full name, or a full commit id, resolved exactly as
 * {@link RepositoryOperations#log(java.nio.file.Path, String, int)} resolves it, through the one resolution the two
 * share ({@code JGitRepositoryOperations.resolveRevision}): an ambiguous revision is {@link GitOutcome#AMBIGUOUS}, an
 * abbreviated commit id or a revision expression {@link GitOutcome#NOT_FOUND}, a full commit id that reaches another
 * commit {@link GitOutcome#IDENTITY_MISMATCH}, and an annotated tag is peeled to its commit. The commits of
 * {@link #changes(Repository, String, String)} are full commit ids, parsed as they are with JGit 7.8.0
 * {@code RevWalk.parseAny} and required to be commits.
 * <p>
 * A detail names the input field and the reason, never a path or content (GDR-NFR-3); a failure detail names the
 * exception class only.
 *
 * @since 0.1
 */
public final class RepositoryReader {

    /** The input field of the revision of a snapshot or a history. */
    private static final String REVISION = "revision";

    /** The input field of the path of a history. */
    private static final String PATH = "path";

    /** The input field of the older commit of a comparison. */
    private static final String FROM = "from";

    /** The input field of the newer commit of a comparison. */
    private static final String TO = "to";

    /** Creates the reader. It holds no state, so one instance may serve every repository and thread. */
    public RepositoryReader() {
        // stateless
    }

    /**
     * The kind of file a snapshot holds: git's regular and executable file modes.
     *
     * @since 0.1
     */
    public enum FileKind {

        /** A regular file, mode {@code 100644}. */
        REGULAR,

        /** An executable file, mode {@code 100755}. */
        EXECUTABLE
    }

    /**
     * A file of a snapshot.
     *
     * @param path   the repository-relative path, with {@code /} between its segments
     * @param kind   the kind of the file
     * @param blobId the full id of the file's content
     * @since 0.1
     */
    public record SnapshotFile(String path, FileKind kind, String blobId) {

        /**
         * Checks the components.
         *
         * @param path   the path
         * @param kind   the kind
         * @param blobId the blob id
         * @throws NullPointerException if a component is {@code null}
         */
        public SnapshotFile {
            Objects.requireNonNull(path, PATH);
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(blobId, "blobId");
        }
    }

    /**
     * The files of a commit.
     *
     * @param commitId the full id of the commit
     * @param treeId   the full id of the commit's tree
     * @param files    every regular and executable file of the tree, recursively, in the tree's order; a symbolic
     *                 link or a submodule entry is not a file of the snapshot
     * @since 0.1
     */
    public record Snapshot(String commitId, String treeId, List<SnapshotFile> files) {

        /**
         * Checks the components and copies the files.
         *
         * @param commitId the commit id
         * @param treeId   the tree id
         * @param files    the files
         * @throws NullPointerException if a component or a file is {@code null}
         */
        public Snapshot {
            Objects.requireNonNull(commitId, "commitId");
            Objects.requireNonNull(treeId, "treeId");
            files = List.copyOf(files);
        }
    }

    /**
     * How a path changed between two commits.
     *
     * @since 0.1
     */
    public enum ChangeType {

        /** The path exists in the newer commit only. */
        ADDED,

        /** The path exists in both commits with another content or file mode. */
        MODIFIED,

        /** The path exists in the older commit only. */
        DELETED
    }

    /**
     * A path changed between two commits.
     *
     * @param path the repository-relative path
     * @param type how it changed
     * @since 0.1
     */
    public record PathChange(String path, ChangeType type) {

        /**
         * Checks the components.
         *
         * @param path the path
         * @param type the change
         * @throws NullPointerException if a component is {@code null}
         */
        public PathChange {
            Objects.requireNonNull(path, PATH);
            Objects.requireNonNull(type, "type");
        }
    }

    /**
     * Reads the files of a commit.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the value is the commit, its tree and every regular and executable file of the tree,
     * recursively;</li>
     * <li>{@link GitOutcome#AMBIGUOUS}, {@link GitOutcome#NOT_FOUND}, {@link GitOutcome#IDENTITY_MISMATCH}: the
     * revision is no supported identity, names nothing, or is a full commit id that reaches another commit; the detail
     * names the field {@code revision};</li>
     * <li>{@link GitOutcome#FAILED}: an I/O failure; the detail names the exception class.</li>
     * </ul>
     *
     * @param repository the repository
     * @param revision   a reference, by its short or full name, or a full commit id
     * @return the snapshot, or the outcome that prevented it
     * @throws NullPointerException if an argument is {@code null}
     */
    public GitResult<Snapshot> snapshot(Repository repository, String revision) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(revision, REVISION);
        try (RevWalk walk = new RevWalk(repository)) {
            GitResult<ObjectId> resolved = JGitRepositoryOperations.resolveRevision(repository, revision, REVISION);
            if (!resolved.isOk()) {
                return JGitRepositoryOperations.withoutValue(resolved);
            }
            RevCommit commit = walk.parseCommit(Objects.requireNonNull(resolved.value()));
            List<SnapshotFile> files = new ArrayList<>();
            try (TreeWalk tree = new TreeWalk(repository)) {
                tree.addTree(commit.getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    fileKind(tree.getFileMode(0)).ifPresent(kind -> files.add(
                            new SnapshotFile(tree.getPathString(), kind, tree.getObjectId(0).name())));
                }
            }
            return GitResult.ok(new Snapshot(commit.name(), commit.getTree().name(), files));
        } catch (IOException e) {
            return JGitRepositoryOperations.failure(e);
        }
    }

    /**
     * Reads the commits that changed a path, newest first.
     * <p>
     * The walk starts at the revision's commit and keeps a commit whose tree differs at the path from the tree of one
     * of its parents (JGit 7.8.0 {@code AndTreeFilter} of {@code PathFilter} and {@code TreeFilter.ANY_DIFF}); a root
     * commit that holds the path counts as a change. The path names a file or a directory; a directory changes when a
     * path below it changes. No rename is followed. The parents of a returned commit are its real parents, not the
     * simplified history of the path ({@code RevWalk.setRewriteParents(false)}).
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the value is the commits that changed the path, at most {@code maxCount}, newest
     * first; empty when no commit changed it;</li>
     * <li>{@link GitOutcome#AMBIGUOUS}, {@link GitOutcome#NOT_FOUND}, {@link GitOutcome#IDENTITY_MISMATCH}: as for
     * {@link #snapshot(Repository, String)}, naming the field {@code revision};</li>
     * <li>{@link GitOutcome#NOT_FOUND} with detail {@code path: not a valid path}: the path is blank, starts or ends
     * with {@code /} or holds an empty segment;</li>
     * <li>{@link GitOutcome#FAILED}: an I/O failure; the detail names the exception class.</li>
     * </ul>
     *
     * @param repository the repository
     * @param revision   a reference, by its short or full name, or a full commit id
     * @param path       the repository-relative path of a file or a directory
     * @param maxCount   the most commits returned; a value below 1 counts as 1, as for {@code log}
     * @return the commits that changed the path, or the outcome that prevented the walk
     * @throws NullPointerException if an argument is {@code null}
     */
    public GitResult<List<CommitInfo>> history(Repository repository, String revision, String path, int maxCount) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(revision, REVISION);
        Objects.requireNonNull(path, PATH);
        if (!isValidPath(path)) {
            return GitResult.of(GitOutcome.NOT_FOUND, PATH + ": not a valid path");
        }
        try (RevWalk walk = new RevWalk(repository)) {
            GitResult<ObjectId> start = JGitRepositoryOperations.resolveRevision(repository, revision, REVISION);
            if (!start.isOk()) {
                return JGitRepositoryOperations.withoutValue(start);
            }
            walk.setTreeFilter(AndTreeFilter.create(PathFilter.create(path), TreeFilter.ANY_DIFF));
            // with a tree filter, JGit 7.8.0 RevWalk rewrites a commit's parents to the simplified history by default,
            // so a CommitInfo would name the previous change of the path as the parent instead of the real one
            walk.setRewriteParents(false);
            walk.markStart(walk.parseCommit(Objects.requireNonNull(start.value())));
            int limit = Math.max(1, maxCount);
            List<CommitInfo> commits = new ArrayList<>();
            for (RevCommit commit : walk) {
                if (commits.size() >= limit) {
                    break;
                }
                commits.add(JGitRepositoryOperations.toInfo(commit));
            }
            return GitResult.ok(List.copyOf(commits));
        } catch (IOException e) {
            return JGitRepositoryOperations.failure(e);
        }
    }

    /**
     * Reads the paths changed between two commits.
     * <p>
     * The trees of both commits are compared recursively ({@code TreeWalk} with {@code TreeFilter.ANY_DIFF}, JGit
     * 7.8.0), path by path and without rename detection: a path in the newer commit only is {@link ChangeType#ADDED},
     * in the older one only {@link ChangeType#DELETED}, and in both with another content or file mode
     * {@link ChangeType#MODIFIED}. A file replaced by a directory of the same name is the file deleted and the files
     * below the directory added.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the value is every changed path in the trees' order; empty when the trees are
     * equal;</li>
     * <li>{@link GitOutcome#NOT_FOUND}: a commit id is no full commit id or no commit of the repository (the id of an
     * annotated tag or a tree included); the detail names the field ({@code from} or {@code to});</li>
     * <li>{@link GitOutcome#FAILED}: an I/O failure; the detail names the exception class.</li>
     * </ul>
     *
     * @param repository the repository
     * @param from       the full id of the older commit
     * @param to         the full id of the newer commit
     * @return the changed paths, or the outcome that prevented the comparison
     * @throws NullPointerException if an argument is {@code null}
     */
    public GitResult<List<PathChange>> changes(Repository repository, String from, String to) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(from, FROM);
        Objects.requireNonNull(to, TO);
        if (!GitIdentity.isFullCommitId(from)) {
            return GitResult.of(GitOutcome.NOT_FOUND, FROM + ": not a full commit id");
        }
        if (!GitIdentity.isFullCommitId(to)) {
            return GitResult.of(GitOutcome.NOT_FOUND, TO + ": not a full commit id");
        }
        try (RevWalk walk = new RevWalk(repository)) {
            Optional<RevCommit> fromCommit = JGitRepositoryOperations.parseCommit(walk, from);
            if (fromCommit.isEmpty()) {
                return GitResult.of(GitOutcome.NOT_FOUND, FROM + ": not found");
            }
            Optional<RevCommit> toCommit = JGitRepositoryOperations.parseCommit(walk, to);
            if (toCommit.isEmpty()) {
                return GitResult.of(GitOutcome.NOT_FOUND, TO + ": not found");
            }
            List<PathChange> changes = new ArrayList<>();
            try (TreeWalk tree = new TreeWalk(repository)) {
                tree.addTree(fromCommit.get().getTree());
                tree.addTree(toCommit.get().getTree());
                tree.setRecursive(true);
                tree.setFilter(TreeFilter.ANY_DIFF);
                while (tree.next()) {
                    changes.add(new PathChange(tree.getPathString(), changeType(tree.getRawMode(0),
                            tree.getRawMode(1))));
                }
            }
            return GitResult.ok(List.copyOf(changes));
        } catch (IOException e) {
            return JGitRepositoryOperations.failure(e);
        }
    }

    private static ChangeType changeType(int fromMode, int toMode) {
        if (fromMode == FileMode.MISSING.getBits()) {
            return ChangeType.ADDED;
        }
        return toMode == FileMode.MISSING.getBits() ? ChangeType.DELETED : ChangeType.MODIFIED;
    }

    private static Optional<FileKind> fileKind(FileMode mode) {
        if (FileMode.REGULAR_FILE.equals(mode)) {
            return Optional.of(FileKind.REGULAR);
        }
        return FileMode.EXECUTABLE_FILE.equals(mode) ? Optional.of(FileKind.EXECUTABLE) : Optional.empty();
    }

    /** A repository-relative path: not blank, neither starting nor ending with {@code /}, without an empty segment. */
    private static boolean isValidPath(String path) {
        return !path.isBlank() && !path.startsWith("/") && !path.endsWith("/") && !path.contains("//");
    }
}
