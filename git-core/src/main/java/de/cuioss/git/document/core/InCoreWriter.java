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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
import org.eclipse.jgit.dircache.InvalidPathException;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.ObjectChecker;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jspecify.annotations.Nullable;

/**
 * Writes blobs, trees and commits from in-memory content, without a working tree (GDR-CORE-4; checked against JGit
 * 7.8.0).
 * <p>
 * The new tree is built from the first parent's tree, read into an in-core {@link DirCache} through
 * {@code DirCache.read(ObjectReader, AnyObjectId)} (an empty {@code DirCache.newInCore()} for a root commit). The edits
 * are applied through a {@link DirCacheEditor}: a {@link TreeEdit.Put} as a {@link PathEdit} that sets a file of its
 * {@link TreeEdit.Mode} (mode {@code 100644}, {@code 100755} or {@code 120000}), a {@link TreeEdit.Delete} as a
 * {@link DeletePath}. Blobs, the tree
 * ({@code DirCache.writeTree(ObjectInserter)}) and the commit go through one {@link ObjectInserter}, which is flushed
 * once, after the commit was inserted. With a {@link CommitSigning}, the commit is signed through JGit's
 * {@code Signer.signObject(Repository, GpgConfig, ObjectBuilder, PersonIdent, String, CredentialsProvider)} before it
 * is inserted.
 * <p>
 * The writer creates objects only: it reads and writes no reference, so nothing outside the object database changes.
 * Moving a reference to the new commit is the caller's step (GDR-CORE-5).
 * <p>
 * The paths of the edits are checked with one strictness on every host: a JGit {@link ObjectChecker} with
 * {@code setSafeForWindows(true)} and {@code setSafeForMacOS(true)}, whatever the platform the library runs on, so a
 * tree written on Linux checks out on Windows and macOS too; JGit 7.8.0 {@code SystemReader.checkPath} would apply the
 * checks of the running platform only. Two edits whose paths are equal when compared after Unicode normalization
 * (form NFC) and without case, or of which one is a directory of the other's path, are refused: JGit 7.8.0
 * {@code DirCacheEditor} keeps only one of two equal paths, and a tree holding two names that differ in case or only
 * in their composition cannot be checked out on a file system that folds them, such as HFS+, which stores names
 * decomposed.
 * <p>
 * Code of the caller runs on the caller's contract: an unchecked exception of the {@link CommitSigning#signer()}
 * other than a {@link JGitInternalException} propagates unchanged, as does a {@code null} the caller passes.
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
     * <li>{@link GitOutcome#NOT_FOUND}: a parent is no full commit id or no commit of the repository (the id of an
     * annotated tag included, even one that points at a commit), or an edit names an invalid path on any of Linux,
     * Windows and macOS (detail {@code edits: invalid path}), a path another edit names too, also when compared
     * after Unicode normalization (form NFC) and without case ({@code edits: duplicate path}), or a path of which
     * another edit's path is a directory, compared the same way ({@code edits: overlapping path}); the detail names the field ({@code parents} or {@code edits}) and the
     * reason. Nothing is written;</li>
     * <li>{@link GitOutcome#FAILED}: an I/O or a signing failure, a tree of the first parent holding a path JGit
     * refuses ({@code InvalidPathException}, e.g. {@code .GIT} or {@code git~1}), a tree too large to be read in one
     * piece ({@code LargeObjectException}), or an invalid {@code gpg.format} of the configuration when the commit is
     * signed ({@code IllegalArgumentException} of JGit 7.8.0 {@code GpgConfig}); the detail names the exception class,
     * by its binary name for an anonymous class.</li>
     * </ul>
     * <p>
     * An unchecked exception of the caller's {@code Signer}, other than a {@link JGitInternalException}, propagates
     * unchanged.
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
        return writeTree(repository, parentIds, metadata, (reader, inserter, firstParentTree) -> {
            DirCache tree = firstParentTree == null ? DirCache.newInCore() : DirCache.read(reader, firstParentTree);
            applyEdits(tree, treeEdits, inserter);
            return tree.writeTree(inserter);
        });
    }

    /**
     * Writes a commit whose tree is the finished tree a {@link TreeSource} inserts (checked against JGit 7.8.0).
     * <p>
     * The parents are parsed as {@link #write(Repository, List, CommitMetadata, List)} parses them; the source is then
     * called once with the reader and the inserter of the commit and the tree of the first parent, and the id it
     * returns becomes the commit's tree as it is. The source's objects, the commit and its signature go through the one
     * inserter, which is flushed once, after the commit was inserted. A source that fails prevents that flush and the
     * caller's reference update, but it does not undo an insertion: JGit 7.8.0 {@code ObjectDirectoryInserter} writes
     * each object of a file-backed repository as a loose object when it is inserted, so the objects the source had
     * already inserted may stay in the object database, unreferenced.
     * <p>
     * The outcomes are those of the public {@code write}, except that no edit is checked: {@link GitOutcome#OK} with the
     * full id of the new commit; {@link GitOutcome#NOT_FOUND} for a parent that is no full commit id or no commit of the
     * repository, the detail naming the field {@code parents}; {@link GitOutcome#FAILED} for an I/O failure, of the
     * source included ({@code JGitRepositoryOperations.failure}), an {@code InvalidPathException} or a
     * {@code LargeObjectException} of the source (JGit 7.8.0 {@code DirCache.read} and {@code NoteMap.readTree} read a
     * tree in one piece through {@code ObjectLoader.getCachedBytes()} and build a {@code DirCacheEntry} per path), an
     * invalid {@code gpg.format} or a signing failure, the detail naming the exception class only. Any other unchecked
     * exception of the source propagates unchanged.
     *
     * @param repository the repository whose object database receives the objects
     * @param parents    the full commit ids of the parents, in order; empty for a root commit
     * @param metadata   author, committer, message and signing of the commit
     * @param source     inserts the finished tree of the commit and returns its id
     * @return the full id of the new commit, or the outcome that prevented it
     * @throws NullPointerException if an argument or an element of {@code parents} is {@code null}
     */
    GitResult<String> writeTree(Repository repository, List<String> parents, CommitMetadata metadata,
            TreeSource source) {
        Objects.requireNonNull(repository, "repository");
        List<String> parentIds = List.copyOf(Objects.requireNonNull(parents, PARENTS));
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(source, "source");
        if (!parentIds.stream().allMatch(GitIdentity::isFullCommitId)) {
            return GitResult.of(GitOutcome.NOT_FOUND, PARENTS + ": not a full commit id");
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
            @Nullable ObjectId firstParentTree = parentCommits.isEmpty() ? null : parentCommits.getFirst().getTree();
            GitResult<ObjectId> tree = sourceTree(source, reader, inserter, firstParentTree);
            if (!tree.isOk()) {
                return JGitRepositoryOperations.withoutValue(tree);
            }
            CommitBuilder commit = new CommitBuilder();
            commit.setTreeId(Objects.requireNonNull(tree.value()));
            commit.setParentIds(parentCommits);
            commit.setAuthor(metadata.author());
            commit.setCommitter(metadata.committer());
            commit.setMessage(metadata.message());
            Optional<GitResult<String>> unsigned = sign(repository, metadata, commit);
            if (unsigned.isPresent()) {
                return unsigned.get();
            }
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            return GitResult.ok(commitId.name());
        } catch (IOException | GitAPIException | JGitInternalException e) {
            return JGitRepositoryOperations.failure(e);
        }
    }

    /**
     * Calls the source for the commit's tree. An {@link InvalidPathException} or a {@link LargeObjectException} of the
     * source is {@link GitOutcome#FAILED}, its detail the exception class only; an {@link IOException} and every other
     * unchecked exception propagate to the caller.
     *
     * @return the id of the inserted tree, or the failure
     * @throws IOException if the source fails to read or insert an object
     */
    private static GitResult<ObjectId> sourceTree(TreeSource source, ObjectReader reader, ObjectInserter inserter,
            @Nullable ObjectId firstParentTree) throws IOException {
        try {
            return GitResult.ok(source.write(reader, inserter, firstParentTree));
        } catch (InvalidPathException | LargeObjectException e) {
            return JGitRepositoryOperations.failure(e);
        }
    }

    /**
     * Checks every path of the edits before anything is written: each path with a strict {@link ObjectChecker}, the
     * checks for Windows and macOS included on every host, and the paths against each other, compared after Unicode
     * normalization and without case: a path named twice, or a path that is a directory of another edit's path, is
     * refused. The comparison key is the path in normalization form NFC, then in lower case, so the NFC and the NFD
     * spelling of one name collide as they do on HFS+, which stores names decomposed; the edit keeps its own path.
     */
    private static Optional<String> checkPaths(List<TreeEdit> edits) {
        ObjectChecker checker = new ObjectChecker().setSafeForWindows(true).setSafeForMacOS(true);
        Set<String> seen = new HashSet<>();
        for (TreeEdit edit : edits) {
            try {
                checker.checkPath(edit.path());
            } catch (CorruptObjectException e) {
                return Optional.of(EDITS + ": invalid path");
            }
            if (!seen.add(comparisonKey(edit.path()))) {
                return Optional.of(EDITS + ": duplicate path");
            }
        }
        for (String path : seen) {
            for (int slash = path.indexOf('/'); slash > 0; slash = path.indexOf('/', slash + 1)) {
                if (seen.contains(path.substring(0, slash))) {
                    return Optional.of(EDITS + ": overlapping path");
                }
            }
        }
        return Optional.empty();
    }

    /** The key two edit paths are compared by: normalization form NFC, then lower case. */
    private static String comparisonKey(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a parent; empty when the id names no commit of the repository.
     * <p>
     * The object is parsed as it is ({@code RevWalk.parseAny}) and must be a commit itself: JGit 7.8.0
     * {@code RevWalk.parseCommit} peels an annotated tag to the commit it points at ({@code peel(parseAny(id))}), so it
     * would accept the id of a tag and write the tagged commit as the parent.
     */
    private static Optional<RevCommit> parseCommit(RevWalk walk, String id) throws IOException {
        try {
            return walk.parseAny(ObjectId.fromString(id)) instanceof RevCommit commit ? Optional.of(commit)
                    : Optional.empty();
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            return Optional.empty();
        }
    }

    private static void applyEdits(DirCache tree, List<TreeEdit> edits, ObjectInserter inserter) throws IOException {
        DirCacheEditor editor = tree.editor();
        for (TreeEdit edit : edits) {
            switch (edit) {
                case TreeEdit.Put(String path, byte[] content, TreeEdit.Mode mode) -> {
                    ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content);
                    FileMode fileMode = fileMode(mode);
                    editor.add(new PathEdit(path) {
                        @Override
                        public void apply(DirCacheEntry entry) {
                            entry.setFileMode(fileMode);
                            entry.setObjectId(blob);
                            entry.setLength(content.length);
                        }
                    });
                }
                case TreeEdit.Delete(String path) -> editor.add(new DeletePath(path));
            }
        }
        editor.finish();
    }

    /** The JGit mode of a tree entry a {@link TreeEdit.Put} writes. */
    private static FileMode fileMode(TreeEdit.Mode mode) {
        return switch (mode) {
            case REGULAR_FILE -> FileMode.REGULAR_FILE;
            case EXECUTABLE_FILE -> FileMode.EXECUTABLE_FILE;
            case SYMLINK -> FileMode.SYMLINK;
        };
    }

    /**
     * Signs the commit when the metadata carries a signing; empty when it was signed or carries none, the failure
     * when the configuration names an invalid {@code gpg.format}: JGit 7.8.0 {@code GpgConfig} reads it through
     * {@code Config.getEnum}, which throws an {@link IllegalArgumentException} for an unknown value. Only that
     * construction is guarded, so an unchecked exception of the caller's signer propagates.
     */
    private static Optional<GitResult<String>> sign(Repository repository, CommitMetadata metadata,
            CommitBuilder commit) throws IOException, GitAPIException {
        CommitSigning signing = metadata.signing();
        if (signing == null) {
            return Optional.empty();
        }
        GpgConfig config;
        try {
            config = new GpgConfig(repository.getConfig());
        } catch (IllegalArgumentException e) {
            return Optional.of(JGitRepositoryOperations.failure(e));
        }
        signing.signer().signObject(repository, config, commit, metadata.committer(), signing.signingKey(),
                signing.credentials());
        return Optional.empty();
    }

    /**
     * Produces the finished tree of a commit the writer writes (checked against JGit 7.8.0).
     * <p>
     * The writer calls the source once per commit, with the reader and the inserter of that commit, and takes the
     * returned id as the commit's tree as it is. The source inserts every object of the tree through the given
     * inserter and does not flush it: the writer flushes once, after the commit. A source used in a compare-and-swap is
     * called again after a lost race with the tree the concurrent writer left, so it must be free of side effects on
     * references and derive its tree from the tree it is handed, not from a tree it read earlier.
     */
    @FunctionalInterface
    interface TreeSource {

        /**
         * Inserts the finished tree and returns its id.
         *
         * @param reader          the reader of the commit's repository
         * @param inserter        the inserter of the commit, not to be flushed by the source
         * @param firstParentTree the tree of the first parent; {@code null} for a root commit
         * @return the id of the inserted tree
         * @throws IOException if reading or inserting an object fails
         */
        ObjectId write(ObjectReader reader, ObjectInserter inserter, @Nullable ObjectId firstParentTree)
                throws IOException;
    }
}
