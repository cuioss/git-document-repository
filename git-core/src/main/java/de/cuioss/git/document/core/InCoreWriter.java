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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEditor;
import org.eclipse.jgit.dircache.DirCacheEditor.DeletePath;
import org.eclipse.jgit.dircache.DirCacheEditor.PathEdit;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.util.SystemReader;

/**
 * Writes blobs, trees and commits from in-memory content, without a working tree (GDR-CORE-4; checked against JGit
 * 7.8.0).
 * <p>
 * The new tree is built from the first parent's tree, read into an in-core {@link DirCache} through
 * {@code DirCache.read(ObjectReader, AnyObjectId)} (an empty {@code DirCache.newInCore()} for a root commit). The edits
 * are applied through a {@link DirCacheEditor}: a {@link TreeEdit.Put} as a {@link PathEdit} that sets a regular file
 * (mode {@code 100644}), a {@link TreeEdit.Delete} as a {@link DeletePath}. Blobs, the tree
 * ({@code DirCache.writeTree(ObjectInserter)}) and the commit go through one {@link ObjectInserter}, which is flushed
 * once, after the commit was inserted. With a {@link CommitSigning}, the commit is signed through JGit's
 * {@code Signer.signObject(Repository, GpgConfig, ObjectBuilder, PersonIdent, String, CredentialsProvider)} before it
 * is inserted.
 * <p>
 * The writer creates objects only: it reads and writes no reference, so nothing outside the object database changes.
 * Moving a reference to the new commit is the caller's step (GDR-CORE-5).
 * <p>
 * A detail names the input field and the reason, never the caller's value, and a failure detail names the exception
 * class without its message (GDR-NFR-3).
 *
 * @since 0.1
 */
public final class InCoreWriter {

    /** The input field of the parents. */
    private static final String PARENTS = "parents";

    /** The input field of the edits. */
    private static final String EDITS = "edits";

    /** Creates the writer. It holds no state, so one instance may serve every repository and thread. */
    public InCoreWriter() {
        // stateless
    }

    /**
     * Writes a commit whose tree is the first parent's tree with the edits applied.
     * <p>
     * The outcomes:
     * <ul>
     * <li>{@link GitOutcome#OK}: the commit was written; the value is its full 40-digit id;</li>
     * <li>{@link GitOutcome#NOT_FOUND}: a parent is no full commit id or no commit of the repository, or an edit names
     * an invalid path or a path another edit names too; the detail names the field ({@code parents} or {@code edits})
     * and the reason. Nothing is written;</li>
     * <li>{@link GitOutcome#FAILED}: an I/O or a signing failure; the detail names the exception class.</li>
     * </ul>
     *
     * @param repository the repository whose object database receives the objects
     * @param parents    the full commit ids of the parents, in order; empty for a root commit, whose tree holds only the
     *                   puts of the edits
     * @param metadata   author, committer, message and signing of the commit
     * @param edits      the edits of the first parent's tree; empty to keep it unchanged
     * @return the full id of the new commit, or the outcome that prevented it
     * @throws NullPointerException if an argument or an element of a list is {@code null}
     */
    public GitResult<String> write(Repository repository, List<String> parents, CommitMetadata metadata,
            List<TreeEdit> edits) {
        Objects.requireNonNull(repository, "repository");
        List<String> parentIds = List.copyOf(Objects.requireNonNull(parents, PARENTS));
        Objects.requireNonNull(metadata, "metadata");
        List<TreeEdit> treeEdits = List.copyOf(Objects.requireNonNull(edits, EDITS));
        if (!parentIds.stream().allMatch(GitIdentity::isFullCommitId)) {
            return GitResult.of(GitOutcome.NOT_FOUND, PARENTS + ": not a full commit id");
        }
        Optional<String> invalidEdits = checkPaths(treeEdits);
        if (invalidEdits.isPresent()) {
            return GitResult.of(GitOutcome.NOT_FOUND, invalidEdits.get());
        }
        try (ObjectReader reader = repository.newObjectReader();
             RevWalk walk = new RevWalk(reader);
             ObjectInserter inserter = repository.newObjectInserter()) {
            List<RevCommit> parentCommits = new ArrayList<>(parentIds.size());
            for (String parentId : parentIds) {
                Optional<RevCommit> parent = parseCommit(walk, parentId);
                if (parent.isEmpty()) {
                    return GitResult.of(GitOutcome.NOT_FOUND, PARENTS + ": not found");
                }
                parentCommits.add(parent.get());
            }
            DirCache tree = parentCommits.isEmpty()
                    ? DirCache.newInCore()
                    : DirCache.read(reader, parentCommits.getFirst().getTree());
            applyEdits(tree, treeEdits, inserter);
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(tree.writeTree(inserter));
            commit.setParentIds(parentCommits);
            commit.setAuthor(metadata.author());
            commit.setCommitter(metadata.committer());
            commit.setMessage(metadata.message());
            sign(repository, metadata, commit);
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            return GitResult.ok(commitId.name());
        } catch (IOException | GitAPIException | JGitInternalException e) {
            return GitResult.of(GitOutcome.FAILED, e.getClass().getSimpleName());
        }
    }

    /**
     * Checks every path of the edits with the check JGit's {@link DirCacheEntry} applies, before anything is written.
     */
    private static Optional<String> checkPaths(List<TreeEdit> edits) {
        Set<String> seen = new HashSet<>();
        for (TreeEdit edit : edits) {
            try {
                SystemReader.getInstance().checkPath(edit.path());
            } catch (CorruptObjectException e) {
                return Optional.of(EDITS + ": invalid path");
            }
            if (!seen.add(edit.path())) {
                return Optional.of(EDITS + ": duplicate path");
            }
        }
        return Optional.empty();
    }

    /** Parses a parent; empty when the id names no commit of the repository. */
    private static Optional<RevCommit> parseCommit(RevWalk walk, String id) throws IOException {
        try {
            return Optional.of(walk.parseCommit(ObjectId.fromString(id)));
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            return Optional.empty();
        }
    }

    private static void applyEdits(DirCache tree, List<TreeEdit> edits, ObjectInserter inserter) throws IOException {
        DirCacheEditor editor = tree.editor();
        for (TreeEdit edit : edits) {
            switch (edit) {
                case TreeEdit.Put put -> {
                    byte[] content = put.content();
                    ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content);
                    editor.add(new PathEdit(put.path()) {
                        @Override
                        public void apply(DirCacheEntry entry) {
                            entry.setFileMode(FileMode.REGULAR_FILE);
                            entry.setObjectId(blob);
                            entry.setLength(content.length);
                        }
                    });
                }
                case TreeEdit.Delete delete -> editor.add(new DeletePath(delete.path()));
            }
        }
        editor.finish();
    }

    private static void sign(Repository repository, CommitMetadata metadata, CommitBuilder commit)
            throws IOException, GitAPIException {
        CommitSigning signing = metadata.signing();
        if (signing != null) {
            signing.signer().signObject(repository, new GpgConfig(repository.getConfig()), commit,
                    metadata.committer(), signing.signingKey(), signing.credentials());
        }
    }
}
