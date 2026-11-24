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
package de.cuioss.git.document.forms;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.SaveStep;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;

/**
 * The read side of the bare clone of a writer for {@link DocumentTransitions} (GDR-FORMS-5): whether a reference
 * exists, a file of the commit a reference points at, the file the draft started from, and a saved commit read as the
 * attempt of a save. It only reads the clone; an I/O failure, and a file larger than {@link OutputCheck#MAX_FILE_BYTES},
 * is a {@link ReadFailure}. A reader serves the thread of one transition.
 */
final class CloneReader {

    private final Repository repository;

    /**
     * Creates the reader of a clone.
     *
     * @param repository the bare clone of the writer
     * @throws NullPointerException if {@code repository} is {@code null}
     */
    CloneReader(Repository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    /**
     * Whether a reference exists and points at an object.
     *
     * @param ref the full name of the reference
     * @return whether it exists
     * @throws ReadFailure for an I/O failure while reading the clone
     */
    boolean exists(String ref) {
        try {
            Ref found = repository.exactRef(ref);
            return found != null && found.getObjectId() != null;
        } catch (IOException e) {
            throw new ReadFailure(e);
        }
    }

    /**
     * Reads a file of the commit a reference points at.
     *
     * @param ref  the full name of the reference
     * @param path the repository-relative path of the file
     * @return the content, or empty when the reference, the commit or the file is absent
     * @throws ReadFailure for an I/O failure, and for a file larger than {@link OutputCheck#MAX_FILE_BYTES}
     */
    Optional<byte[]> read(String ref, String path) {
        Ref found;
        try {
            found = repository.exactRef(ref);
        } catch (IOException e) {
            throw new ReadFailure(e);
        }
        ObjectId commit = found == null ? null : found.getObjectId();
        return commit == null ? Optional.empty() : read(commit, path);
    }

    /**
     * Reads the file the draft started from: the file at the merge base of the draft and the branch
     * ({@code RevFilter.MERGE_BASE} of JGit 7.8.0) when the draft exists and they have a common commit, else the file on
     * the branch, so that a draft that does not exist yet has changed nothing.
     *
     * @param draft  the full name of the draft reference
     * @param branch the full name of the branch of the key
     * @param path   the repository-relative path of the file
     * @return the content, or empty when there is no file
     * @throws ReadFailure for an I/O failure, and for a file larger than {@link OutputCheck#MAX_FILE_BYTES}
     */
    Optional<byte[]> startedFrom(String draft, String branch, String path) {
        @Nullable ObjectId mergeBase = exists(draft) ? mergeBase(draft, branch) : null;
        return mergeBase == null ? read(branch, path) : read(mergeBase, path);
    }

    /**
     * A saved commit read as the attempt of a save: its tip and its produced tree are both the commit, so the checks of
     * a step apply to what the commit holds. Its methods throw {@link ReadFailure} for an I/O failure.
     *
     * @param commitId the full id of the commit
     * @return the attempt over the commit
     */
    SaveStep.Attempt savedCommit(String commitId) {
        return new SavedCommit(commitId);
    }

    /**
     * The merge base of two references, or {@code null} when one is absent or they have no common commit.
     *
     * @throws ReadFailure for an I/O failure while reading the clone
     */
    private @Nullable ObjectId mergeBase(String first, String second) {
        try (RevWalk walk = new RevWalk(repository)) {
            Ref one = repository.exactRef(first);
            Ref other = repository.exactRef(second);
            if (one == null || other == null || one.getObjectId() == null || other.getObjectId() == null) {
                return null;
            }
            walk.setRevFilter(RevFilter.MERGE_BASE);
            walk.markStart(walk.parseCommit(one.getObjectId()));
            walk.markStart(walk.parseCommit(other.getObjectId()));
            RevCommit found = walk.next();
            return found == null ? null : found.copy();
        } catch (IOException e) {
            throw new ReadFailure(e);
        }
    }

    /**
     * Reads a file of a commit.
     *
     * @return the content, or empty when the file is absent
     * @throws ReadFailure for an I/O failure, and for a file larger than {@link OutputCheck#MAX_FILE_BYTES}
     */
    private Optional<byte[]> read(ObjectId commit, String path) {
        try (RevWalk walk = new RevWalk(repository);
             TreeWalk file = TreeWalk.forPath(repository, path, walk.parseCommit(commit).getTree())) {
            if (file == null || FileMode.TREE.equals(file.getRawMode(0))) {
                return Optional.empty();
            }
            ObjectLoader loader = repository.open(file.getObjectId(0), Constants.OBJ_BLOB);
            // JGit 7.8.0 ObjectLoader.getBytes(int) returns a cached object whole, so the size is checked first.
            if (loader.getSize() > OutputCheck.MAX_FILE_BYTES) {
                throw new ReadFailure(new IOException("too large"));
            }
            return Optional.of(loader.getBytes(OutputCheck.MAX_FILE_BYTES));
        } catch (IOException e) {
            throw new ReadFailure(e);
        }
    }

    /** A saved commit read as the attempt of a save. */
    private final class SavedCommit implements SaveStep.Attempt {

        private final String commitId;

        SavedCommit(String commitId) {
            this.commitId = commitId;
        }

        @Override
        public String tip() {
            return commitId;
        }

        @Override
        public Optional<byte[]> tipContent(String path) {
            return read(ObjectId.fromString(commitId), path);
        }

        @Override
        public Optional<byte[]> content(String path) {
            return tipContent(path);
        }

        @Override
        public Optional<TreeEdit.Mode> mode(String path) {
            Objects.requireNonNull(path, "path");
            try (RevWalk walk = new RevWalk(repository);
                 TreeWalk file = TreeWalk.forPath(repository, path,
                         walk.parseCommit(ObjectId.fromString(commitId)).getTree())) {
                if (file == null) {
                    return Optional.empty();
                }
                int rawMode = file.getRawMode(0);
                if (FileMode.TREE.equals(rawMode) || FileMode.GITLINK.equals(rawMode)) {
                    return Optional.empty();
                }
                if (FileMode.EXECUTABLE_FILE.equals(rawMode)) {
                    return Optional.of(TreeEdit.Mode.EXECUTABLE_FILE);
                }
                if (FileMode.SYMLINK.equals(rawMode)) {
                    return Optional.of(TreeEdit.Mode.SYMLINK);
                }
                return Optional.of(TreeEdit.Mode.REGULAR_FILE);
            } catch (IOException e) {
                throw new ReadFailure(e);
            }
        }

        @Override
        public List<String> paths(String directory) {
            String prefix = directory.isEmpty() ? "" : directory + "/";
            List<String> paths = new ArrayList<>();
            try (RevWalk walk = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
                tree.addTree(walk.parseCommit(ObjectId.fromString(commitId)).getTree());
                tree.setRecursive(true);
                while (tree.next()) {
                    if (tree.getPathString().startsWith(prefix)) {
                        paths.add(tree.getPathString());
                    }
                }
            } catch (IOException e) {
                throw new ReadFailure(e);
            }
            return paths.stream().sorted().toList();
        }
    }

    /** An I/O failure while reading the clone; its message is unused, its cause names the kind of failure. */
    static final class ReadFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ReadFailure(IOException cause) {
            super(null, cause, false, false);
        }
    }
}
