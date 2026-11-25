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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;


import de.cuioss.git.document.core.GitIdentity;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RepositoryReader;
import de.cuioss.git.document.core.RepositoryReader.PathChange;
import de.cuioss.git.document.core.RepositoryReader.SnapshotFile;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.jspecify.annotations.Nullable;

/**
 * The in-memory index of one repository of the store (GDR-STORE-9): the value an {@link IndexContributor} returns for
 * every document of its collection, per source reference.
 * <p>
 * The sources of the index are the branches of the clone: {@code main}, holding the released documents of every
 * collection that has a contributor; every working branch {@code refs/heads/<collection>/<id>} of a per-document
 * collection, holding the one document {@code <collection>/<id>}; and every collection branch
 * {@code refs/heads/<collection>} of a numbered collection, holding its entries. A document is found by the path
 * convention of {@link DocumentPaths}; the counter file of a numbered collection is no document. Every other branch,
 * and every collection without a contributor, is not indexed.
 * <p>
 * The index is built when it is opened and refreshed after every fetch, save and release of its {@link StoreWriter},
 * once that operation released every lock of the store. A refresh reads the tips of the source branches and keeps a
 * source whose tip did not move; for a source whose tip moved, it re-reads only the documents with a path changed
 * between the indexed commit and the new tip ({@link RepositoryReader#changes}); a new source is read in full, and a
 * source whose branch is gone is dropped. Only when the changed paths cannot be read, for example because the indexed
 * commit is no longer in the clone, the source is read in full. A document whose contributor returns {@code null} or
 * fails is left out; a contributor failure isolated by the observer rule of {@link StoreEvents} (a
 * {@link RuntimeException} or an {@link Error} other than a {@link VirtualMachineError}) is logged
 * ({@code GIT_STORE-108}) with the class name of the throwable only, and the refresh goes on.
 * <p>
 * Each source is read in isolation: a source that cannot be read, for example because a blob of its tip is missing
 * from the clone, keeps the entries it was indexed with before (a new source stays out of the index), is logged
 * ({@code GIT_STORE-113}) with the alias and the class of the source ({@code main}, {@code working branch} or
 * {@code collection branch}), never its reference name, and the other sources are published fresh. Every blob handed to
 * a contributor is read bounded by {@link ExplicitSave#BLOB_LIMIT}, so a document with a larger blob makes its source
 * unreadable in that sense, and makes a snapshot the passed-on {@link GitOutcome#FAILED}.
 * <p>
 * Refreshes are serialised by the lock of the index, the one lock of the writer's {@link StoreEvents} shared by every
 * index of that writer, and always rebuild from the tips read under that lock, so a refresh
 * started after a save and one started after a fetch can never publish out of order. The published index is an
 * immutable value swapped atomically: a reader sees either the index before or the index after a refresh, never a
 * mix. The lock of the index is taken only after the operation released the transport lock, never while it is held, and
 * {@link #snapshot(String)} and {@link #open(StoreWriter, Map)} return {@link StoreOutcome#REFUSED} without waiting
 * when the calling thread holds a lock of the clone (inside a save step or an anchor provider call), so no thread
 * waits for the lock of the index while it holds a lock of the clone, and the lock order of {@link RepositoryGuard}
 * gains no cycle, also not across two indexes of the writer. A refresh that fails to read the references of the clone
 * keeps the previous index, and one that could not read a source keeps that source's previous entries; either marks
 * the index stale, and the next operation of the writer retries the refresh, also a fetch or save that moved no
 * reference, until one reads every source; the stale mark is read and set under the lock of the index, so an operation
 * that follows a failing refresh never misses its retry. A retry requested from a contributor of a running refresh or
 * snapshot of any index of the writer is deferred to the next operation, like one from the same index, and no two
 * indexes of a writer wait for each other's lock. A move reported from there, a contributor that fetches, is deferred
 * the same way: the running refresh publishes its own result but stays stale, and the next operation of the writer, also
 * one that moves nothing, refreshes from the latest references. A snapshot calls the contributors under the same lock, so no
 * contributor of any index of the writer is called by two threads at once; the refreshes, retries and snapshots of the
 * indexes of one writer are serialised by that one lock.
 * <p>
 * {@link #snapshot(String)} builds a separate index of one tag or commit, for reporting, without changing the current
 * one.
 *
 * @param <E> the type of the entry values
 * @since 0.1
 */
public final class StoreIndex<E> implements AutoCloseable {

    private static final CuiLogger LOGGER = new CuiLogger(StoreIndex.class);

    /** The name of a collection argument in the null checks. */
    private static final String COLLECTION = "collection";

    /**
     * The detail of a snapshot or an opening from a thread that holds a lock of the clone: a save step or an anchor
     * provider call that would otherwise wait for the lock of the index.
     */
    static final String IN_CALLBACK = "index: refused inside a callback that holds a lock of the clone";

    private final String alias;
    private final Repository repository;
    private final StoreEvents events;
    private final Map<String, DocumentCollection> collections;
    private final Map<DocumentCollection, IndexContributor<? extends E>> contributors;
    /** The one lock of the writer's indexes, owned by its events and shared by every index of the writer. */
    private final ReentrantLock lock;
    private final RepositoryReader reader = new RepositoryReader();
    private final StoreEvents.Observer observer = new StoreEvents.Observer() {

        /**
         * Refreshes after an operation that moved a reference. A move reported from a contributor of a running refresh
         * or snapshot of this or any other index of the writer, which holds the shared lock already (a hold count above
         * one), is deferred like a nested retry: it does not refresh but marks the index stale and the running refresh
         * as overtaken, so that refresh publishes its own result and stays stale, and the next operation of the writer
         * refreshes from the latest references, also one that moves nothing.
         */
        @Override
        public void moved(List<StoreEvents.Update> updates) {
            lock.lock();
            try {
                if (lock.getHoldCount() > 1) {
                    movedDuringRefresh = true;
                    stale = true;
                    return;
                }
                refreshQuietly();
            } finally {
                lock.unlock();
            }
        }

        /**
         * Retries a failed refresh, or one overtaken by a nested move. The stale flag is read under the lock of the index, the one lock of the writer's
         * indexes, which a running refresh holds, so a refresh that is about to mark the index stale is waited for
         * rather than read as current; the lock is reentrant, so the refresh taken under it cannot deadlock. A retry
         * requested from a contributor of a running refresh or snapshot of this or any other index of the writer,
         * which holds the shared lock already (a hold count above one), is deferred to the next operation: it neither
         * refreshes nor clears the flag, so a contributor that fetches cannot recurse, and two indexes of a writer
         * never wait for each other's lock.
         */
        @Override
        public void unchanged() {
            lock.lock();
            try {
                // the hold count, not an own flag: only the outermost holder retries, a nested call returns
                if (stale && lock.getHoldCount() == 1) {
                    refreshQuietly();
                }
            } finally {
                lock.unlock();
            }
        }

        /**
         * Refreshes after an operation of the writer; a failure keeps the previous index and marks it stale, so the
         * next operation retries the refresh even when it moves no reference. The failure is marked under the lock of
         * the index, so {@code unchanged()} never reads the flag between the failed refresh and its mark.
         */
        private void refreshQuietly() {
            lock.lock();
            try {
                refresh();
            } catch (IOException e) {
                stale = true;
                LOGGER.debug("The index of %s was not refreshed: %s", alias, e.getClass().getName());
            } finally {
                lock.unlock();
            }
        }
    };
    private final AtomicReference<Map<String, Source<E>>> current = new AtomicReference<>(Map.of());

    /** Whether the last refresh failed, so the index may lag behind the clone until a refresh succeeds. */
    private volatile boolean stale;

    /**
     * Whether a move was reported from a contributor while a refresh or snapshot held the lock of the index, so the
     * running refresh read references that are no longer current; reset when a refresh starts.
     */
    private volatile boolean movedDuringRefresh;

    private StoreIndex(String alias, Repository repository, StoreEvents events,
            Map<DocumentCollection, IndexContributor<? extends E>> contributors) {
        this.alias = alias;
        this.repository = repository;
        this.events = events;
        this.lock = events.indexLock();
        this.contributors = contributors;
        Map<String, DocumentCollection> byName = new LinkedHashMap<>();
        contributors.keySet().forEach(collection -> byName.put(collection.name(), collection));
        this.collections = byName;
    }

    /**
     * Opens the index of a writer's repository and builds it from the branches of the clone.
     *
     * @param writer       the writer whose fetches, saves and releases refresh the index
     * @param contributors the contributor of every indexed collection
     * @param <E>          the type of the entry values
     * @return {@link StoreOutcome#OK} with the built index; {@link StoreOutcome#MALFORMED} with the detail
     *         {@code collection <name>: declared twice} for two collections of one name; the passed-on
     *         {@link GitOutcome#FAILED} naming the class of the exception when the clone could not be read; or
     *         {@link StoreOutcome#REFUSED}, without waiting, when the calling thread holds a lock of the clone, inside a
     *         save step or an anchor provider call of the writer
     * @throws NullPointerException if an argument, a collection or a contributor is {@code null}
     */
    public static <E> StoreResult<StoreIndex<E>> open(StoreWriter writer,
            Map<DocumentCollection, ? extends IndexContributor<? extends E>> contributors) {
        Objects.requireNonNull(writer, "writer");
        StoreClone opened = writer.storeClone();
        return open(opened.alias(), opened.repository(), opened.events(), contributors);
    }

    /**
     * Opens the index of a repository refreshed by the given events and builds it from the branches of the
     * repository; {@link #open(StoreWriter, Map)} passes those of a writer.
     *
     * @param alias        the alias of the repository, for the log
     * @param repository   the repository whose branches are indexed
     * @param events       the events that refresh the index
     * @param contributors the contributor of every indexed collection
     * @param <E>          the type of the entry values
     * @return the results of {@link #open(StoreWriter, Map)}
     * @throws NullPointerException if an argument, a collection or a contributor is {@code null}
     */
    static <E> StoreResult<StoreIndex<E>> open(String alias, Repository repository, StoreEvents events,
            Map<DocumentCollection, ? extends IndexContributor<? extends E>> contributors) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(contributors, "contributors");
        Map<DocumentCollection, IndexContributor<? extends E>> copy = new LinkedHashMap<>();
        contributors.forEach((collection, contributor) -> copy.put(Objects.requireNonNull(collection, COLLECTION),
                Objects.requireNonNull(contributor, "contributor")));
        if (events.insideClone()) {
            return StoreResult.of(StoreOutcome.REFUSED, IN_CALLBACK);
        }
        var declared = StoreReferences.collections(List.copyOf(copy.keySet()));
        if (!declared.isOk()) {
            return new StoreResult<>(declared.outcome(), null, declared.detail());
        }
        var index = new StoreIndex<>(alias, repository, events, copy);
        index.events.register(index.observer);
        try {
            index.refresh();
        } catch (IOException e) {
            index.close();
            String type = e.getClass().getSimpleName();
            LOGGER.debug("The index of %s was not built: %s", index.alias, type);
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.FAILED), null, "index: " + type);
        }
        return StoreResult.ok(index);
    }

    /**
     * Returns the source references the index currently holds.
     *
     * @return the full names of the indexed branches, sorted
     */
    public Set<String> sources() {
        return new TreeSet<>(current.get().keySet());
    }

    /**
     * Returns the commit a source reference is indexed at.
     *
     * @param source the full name of a source reference
     * @return the full id of the indexed commit, or empty when the index does not hold the source
     * @throws NullPointerException if {@code source} is {@code null}
     */
    public Optional<String> commitId(String source) {
        Objects.requireNonNull(source, "source");
        Source<E> found = current.get().get(source);
        return found == null ? Optional.empty() : Optional.of(found.commit().name());
    }

    /**
     * Returns the current entries of one collection on one source reference.
     *
     * @param source     the full name of a source reference, such as {@code refs/heads/main}
     * @param collection the collection
     * @return the entry value of every indexed document of the collection on the source, by key, unmodifiable;
     *         empty when the index does not hold the source or the collection
     * @throws NullPointerException if an argument is {@code null}
     */
    public Map<DocumentKey, E> entries(String source, DocumentCollection collection) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(collection, COLLECTION);
        Source<E> found = current.get().get(source);
        return found == null ? Map.of() : found.entries().getOrDefault(collection, Map.of());
    }

    /**
     * Builds a separate index of one tag or commit, as {@code main} would be indexed at it, without changing the
     * current index (GDR-STORE-9). The contributors are called for every document of the tag or commit, under the
     * lock of the index, so never at the same time as by a refresh or another snapshot.
     *
     * @param tagOrCommit a tag, by its full name or its name below {@code refs/tags/}, or a full commit id
     * @return {@link StoreOutcome#OK} with the snapshot; or the passed-on {@link GitOutcome#NOT_FOUND} for a tag or
     *         commit the clone does not hold, {@link GitOutcome#AMBIGUOUS}, or {@link GitOutcome#FAILED} naming the
     *         class of the exception; the detail names the field {@code revision}, never the revision itself; or
     *         {@link StoreOutcome#REFUSED}, without waiting, when the calling thread holds a lock of the clone, inside a
     *         save step or an anchor provider call of the writer
     * @throws NullPointerException if {@code tagOrCommit} is {@code null}
     */
    public StoreResult<Snapshot<E>> snapshot(String tagOrCommit) {
        Objects.requireNonNull(tagOrCommit, "tagOrCommit");
        if (events.insideClone()) {
            return StoreResult.of(StoreOutcome.REFUSED, IN_CALLBACK);
        }
        String revision = GitIdentity.isFullCommitId(tagOrCommit) || tagOrCommit.startsWith(StoreReferences.TAGS)
                ? tagOrCommit : StoreReferences.TAGS + tagOrCommit;
        var snapshot = reader.snapshot(repository, revision);
        if (!snapshot.isOk()) {
            return StoreResult.passOn(snapshot);
        }
        RepositoryReader.Snapshot read = Objects.requireNonNull(snapshot.value());
        Map<String, ObjectId> files = new LinkedHashMap<>();
        for (SnapshotFile file : read.files()) {
            files.put(file.path(), ObjectId.fromString(file.blobId()));
        }
        lock.lock();
        try {
            var scope = new Scope(List.copyOf(collections.values()), null);
            return StoreResult.ok(new Snapshot<>(read.commitId(), contribute(scope, read.commitId(), files)));
        } catch (IOException e) {
            String type = e.getClass().getSimpleName();
            LOGGER.debug("The snapshot of %s was not built: %s", alias, type);
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.FAILED), null, "snapshot: " + type);
        } finally {
            lock.unlock();
        }
    }

    /** Stops the refreshes of the index; the entries stay readable as they were. */
    @Override
    public void close() {
        events.unregister(observer);
    }

    /**
     * Renders the alias and the number of sources only.
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "StoreIndex[alias=" + alias + ", sources=" + current.get().size() + "]";
    }

    /**
     * Reads the tips of the source branches under the lock of the index and publishes the index of those tips: a
     * source whose tip did not move is kept, one whose tip moved re-reads its changed documents, a new one is read in
     * full, and a gone one is dropped. Each source is read in isolation: one that cannot be read keeps its previous
     * entries, or stays out when it is new, is logged by its class and marks the index stale; the others are
     * published fresh.
     *
     * @throws IOException when the references of the clone cannot be read; the previous index stays published
     */
    private void refresh() throws IOException {
        lock.lock();
        try {
            // reset before the references are read: a move nested from here on overtakes this refresh
            movedDuringRefresh = false;
            Map<String, Source<E>> before = current.get();
            Map<String, Source<E>> next = new HashMap<>();
            boolean unread = false;
            for (Ref ref : repository.getRefDatabase().getRefsByPrefix(StoreReferences.HEADS)) {
                @Nullable Scope scope = scope(ref.getName());
                @Nullable ObjectId tip = ref.getObjectId();
                if (scope != null && tip != null && !refreshSource(ref.getName(), scope, tip, before, next)) {
                    unread = true;
                }
            }
            current.set(Map.copyOf(next));
            stale = unread || movedDuringRefresh;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Puts one source into the next index: kept when its tip did not move, else read again; a source that cannot be
     * read keeps its previous entries, or stays out when it is new, and is logged by its class.
     *
     * @return whether the source is current in the next index; {@code false} when it could not be read
     */
    private boolean refreshSource(String ref, Scope scope, ObjectId tip, Map<String, Source<E>> before,
            Map<String, Source<E>> next) {
        @Nullable Source<E> indexed = before.get(ref);
        if (indexed != null && indexed.commit().equals(tip)) {
            next.put(ref, indexed);
            return true;
        }
        try {
            next.put(ref, read(ref, scope, indexed, tip));
            return true;
        } catch (IOException e) {
            LOGGER.warn(GitStoreLogMessages.WARN.INDEX_SOURCE_UNREAD, alias, sourceClass(ref), e.getClass().getName());
            if (indexed != null) {
                next.put(ref, indexed);
            }
            return false;
        }
    }

    /** The class of a source for the log, never its reference name: {@code main}, a collection or a working branch. */
    private static String sourceClass(String ref) {
        if (StoreReferences.MAIN.equals(ref)) {
            return "main";
        }
        return ref.indexOf('/', StoreReferences.HEADS.length()) < 0 ? "collection branch" : "working branch";
    }

    /**
     * The scope of a branch: {@code main} holds every indexed collection, a collection branch of a numbered collection
     * its entries, and a working branch of a per-document collection its one document; {@code null} for any other
     * branch.
     */
    private @Nullable Scope scope(String ref) {
        if (StoreReferences.MAIN.equals(ref)) {
            return new Scope(List.copyOf(collections.values()), null);
        }
        String rest = ref.substring(StoreReferences.HEADS.length());
        int slash = rest.indexOf('/');
        @Nullable DocumentCollection collection = collections.get(slash < 0 ? rest : rest.substring(0, slash));
        if (collection == null) {
            return null;
        }
        if (slash < 0) {
            return collection.kind() == DocumentCollection.Kind.NUMBERED ? new Scope(List.of(collection), null) : null;
        }
        if (collection.kind() != DocumentCollection.Kind.PER_DOCUMENT) {
            return null;
        }
        var key = DocumentKey.of(collection, rest.substring(slash + 1));
        return key.isOk() ? new Scope(List.of(collection), key.value()) : null;
    }

    /**
     * Reads one source at a commit: only the changed documents when the source was indexed before and the changed
     * paths can be read, otherwise every document.
     */
    private Source<E> read(String source, Scope scope, @Nullable Source<E> indexed, ObjectId tip)
            throws IOException {
        RevCommit commit;
        try (RevWalk walk = new RevWalk(repository)) {
            commit = walk.parseCommit(tip);
        }
        if (indexed != null) {
            var changes = reader.changes(repository, indexed.commit().name(), commit.name());
            if (changes.isOk()) {
                List<String> paths = Objects.requireNonNull(changes.value()).stream().map(PathChange::path).toList();
                return new Source<>(commit.copy(), reread(source, scope, indexed, commit.getTree(), paths));
            }
            LOGGER.debug("The changes of a source of %s since its indexed commit were not read: %s", alias,
                    changes.outcome());
        }
        return new Source<>(commit.copy(), contribute(scope, source, files(commit.getTree(), scope.collections())));
    }

    /**
     * Re-reads the documents of the source with a changed path and keeps every other entry. The files of a collection
     * are enumerated once per collection, however many of its documents changed.
     */
    private Map<DocumentCollection, Map<DocumentKey, E>> reread(String source, Scope scope, Source<E> indexed,
            RevTree tree, List<String> changedPaths) throws IOException {
        Map<DocumentCollection, Map<DocumentKey, E>> entries = new LinkedHashMap<>();
        indexed.entries().forEach((collection, values) -> entries.put(collection, new LinkedHashMap<>(values)));
        Map<DocumentCollection, CollectionFiles> enumerated = new HashMap<>();
        for (DocumentKey key : DocumentPaths.changed(scope.collections(), changedPaths)) {
            if (!scope.covers(key)) {
                continue;
            }
            CollectionFiles collectionFiles = enumerated.get(key.collection());
            if (collectionFiles == null) {
                collectionFiles = enumerate(tree, key.collection());
                enumerated.put(key.collection(), collectionFiles);
            }
            Map<String, ObjectId> files = new LinkedHashMap<>();
            for (String path : collectionFiles.documents().getOrDefault(key, List.of())) {
                files.put(path, collectionFiles.files().get(path));
            }
            Map<DocumentKey, E> values = entries.computeIfAbsent(key.collection(), c -> new LinkedHashMap<>());
            @Nullable E value = files.isEmpty() ? null : contribute(key, source, files);
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
        }
        return immutable(entries);
    }

    /** Enumerates the files of one collection of a tree once and groups them into its documents. */
    private CollectionFiles enumerate(RevTree tree, DocumentCollection collection) throws IOException {
        Map<String, ObjectId> files = files(tree, List.of(collection));
        return new CollectionFiles(files, DocumentPaths.documents(List.of(collection), files.keySet()));
    }

    /** Calls the contributors for every document of the scope among the files. */
    private Map<DocumentCollection, Map<DocumentKey, E>> contribute(Scope scope, String source,
            Map<String, ObjectId> files) throws IOException {
        Map<DocumentKey, List<String>> documents = DocumentPaths.documents(scope.collections(), files.keySet());
        Map<DocumentCollection, Map<DocumentKey, E>> entries = new LinkedHashMap<>();
        for (Map.Entry<DocumentKey, List<String>> document : documents.entrySet()) {
            DocumentKey key = document.getKey();
            if (!scope.covers(key)) {
                continue;
            }
            Map<String, ObjectId> own = new LinkedHashMap<>();
            document.getValue().forEach(path -> own.put(path, files.get(path)));
            @Nullable E value = contribute(key, source, own);
            if (value != null) {
                entries.computeIfAbsent(key.collection(), c -> new LinkedHashMap<>()).put(key, value);
            }
        }
        return immutable(entries);
    }

    /**
     * Calls the contributor of one document with its files. A failure the observer rule of {@link StoreEvents} isolates
     * leaves the document out and is logged with the class name of the throwable only, no throwable attached.
     */
    private @Nullable E contribute(DocumentKey key, String source, Map<String, ObjectId> blobs) throws IOException {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (Map.Entry<String, ObjectId> blob : blobs.entrySet()) {
            contents.put(blob.getKey(), ExplicitSave.readBlob(repository, blob.getValue()));
        }
        IndexContributor<? extends E> contributor = Objects.requireNonNull(contributors.get(key.collection()));
        try {
            return contributor.contribute(new IndexContributor.Document(key, source, contents));
        }
        // a failing contributor leaves its document out and never fails the refresh (GDR-STORE-9); the observer rule
        // of StoreEvents isolates every Error but a VirtualMachineError
        // operator-selected: AaIOFP3tAtdQWIZKzKJv: isolation rule of StoreEvents.rethrowIfFatal decided by the operator
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (@SuppressWarnings("java:S1181") RuntimeException | Error e) {
            StoreEvents.rethrowIfFatal(e);
            LOGGER.warn(GitStoreLogMessages.WARN.CONTRIBUTOR_FAILED, alias, key.collection().name(),
                    e.getClass().getName());
            return null;
        }
    }

    /** The regular and executable files of a tree below the directories of the collections, by path. */
    private Map<String, ObjectId> files(RevTree tree, List<DocumentCollection> scope) throws IOException {
        Map<String, ObjectId> files = new LinkedHashMap<>();
        if (scope.isEmpty()) {
            return files;
        }
        List<String> directories = new ArrayList<>(scope.size());
        scope.forEach(collection -> directories.add(collection.name()));
        try (TreeWalk walk = new TreeWalk(repository)) {
            walk.addTree(tree);
            walk.setRecursive(true);
            walk.setFilter(directories.size() == 1 ? PathFilter.create(directories.getFirst())
                    : PathFilterGroup.createFromStrings(directories));
            while (walk.next()) {
                FileMode mode = walk.getFileMode(0);
                if (FileMode.REGULAR_FILE.equals(mode) || FileMode.EXECUTABLE_FILE.equals(mode)) {
                    files.put(walk.getPathString(), walk.getObjectId(0));
                }
            }
        }
        return files;
    }

    private static <E> Map<DocumentCollection, Map<DocumentKey, E>> immutable(
            Map<DocumentCollection, Map<DocumentKey, E>> entries) {
        Map<DocumentCollection, Map<DocumentKey, E>> copy = new HashMap<>();
        entries.forEach((collection, values) -> {
            if (!values.isEmpty()) {
                copy.put(collection, Map.copyOf(values));
            }
        });
        return Map.copyOf(copy);
    }

    /**
     * A separate index of one tag or commit (GDR-STORE-9).
     *
     * @param commitId the full id of the commit the snapshot was built from
     * @param entries  the entry values of every indexed document, by collection and key
     * @param <E>      the type of the entry values
     * @since 0.1
     */
    public record Snapshot<E>(String commitId, Map<DocumentCollection, Map<DocumentKey, E>> entries) {

        /**
         * Checks the components and copies the entries.
         *
         * @param commitId the commit id
         * @param entries  the entries
         * @throws NullPointerException if a component, a key or a value is {@code null}
         */
        public Snapshot {
            Objects.requireNonNull(commitId, "commitId");
            Map<DocumentCollection, Map<DocumentKey, E>> copy = new HashMap<>();
            Objects.requireNonNull(entries, "entries").forEach((collection, values) -> copy.put(collection,
                    Map.copyOf(values)));
            entries = Map.copyOf(copy);
        }

        /**
         * Returns the entries of one collection.
         *
         * @param collection the collection
         * @return the entry value of every document of the collection, by key, unmodifiable; empty when there is none
         * @throws NullPointerException if {@code collection} is {@code null}
         */
        public Map<DocumentKey, E> entries(DocumentCollection collection) {
            Objects.requireNonNull(collection, COLLECTION);
            return entries.getOrDefault(collection, Map.of());
        }
    }

    /**
     * The indexed state of one source reference.
     *
     * @param commit  the commit the source is indexed at
     * @param entries the entry values, by collection and key
     * @param <E>     the type of the entry values
     */
    private record Source<E>(ObjectId commit, Map<DocumentCollection, Map<DocumentKey, E>> entries) {
    }

    /**
     * The files of one collection of a tree, enumerated once.
     *
     * @param files     the regular and executable files below the collection's directory, by path
     * @param documents the paths of every document of the collection among them, by key
     */
    private record CollectionFiles(Map<String, ObjectId> files, Map<DocumentKey, List<String>> documents) {
    }

    /**
     * The documents a source holds.
     *
     * @param collections the collections of the source
     * @param only        the one document of a working branch; {@code null} for every document of the collections
     */
    private record Scope(List<DocumentCollection> collections, @Nullable DocumentKey only) {

        boolean covers(DocumentKey key) {
            return only == null ? collections.contains(key.collection()) : only.equals(key);
        }
    }
}
