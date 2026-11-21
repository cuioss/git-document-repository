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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import org.jspecify.annotations.Nullable;

/**
 * The document path convention of the store (GDR-STORE-2): which repository paths belong to which document or entry.
 * <p>
 * A path belongs to the document or entry {@code <collection>/<id>} when it is the file {@code <collection>/<id>}, a
 * file at any depth below the directory {@code <collection>/<id>/}, or the file {@code <collection>/<id>.<suffix>} with
 * a non-empty suffix, which may hold further dots but no slash. An id holds no dot and no slash ({@link DocumentKey}),
 * so the first dot after the id starts the suffix. A path whose segment after the collection starts with a dot, such as
 * the counter file {@value #COUNTER} of a numbered collection, belongs to no document. Every path is repository-relative,
 * its segments separated by {@code /}.
 * <p>
 * The release takes the paths of one entry by {@link #belongs}; the index ({@link StoreIndex}) groups the files of a
 * tree into documents by {@link #documents} and maps the paths a commit changed to the documents it changed by
 * {@link #changed}.
 */
final class DocumentPaths {

    /** The counter file of a numbered collection, below the collection's directory; no document. */
    static final String COUNTER = ".counter";

    private DocumentPaths() {
    }

    /**
     * Returns the document or entry of a collection a path belongs to.
     *
     * @param collection the collection
     * @param path       the repository-relative path
     * @return the key of the document or entry, or empty when the path lies outside the collection's directory, belongs
     *         to no document, or names an id that does not fit the kind of the collection
     * @throws NullPointerException if an argument is {@code null}
     */
    static Optional<DocumentKey> key(DocumentCollection collection, String path) {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(path, "path");
        String prefix = collection.name() + "/";
        if (!path.startsWith(prefix)) {
            return Optional.empty();
        }
        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String id;
        if (slash < 0) {
            int dot = rest.indexOf('.');
            if (dot == rest.length() - 1) {
                return Optional.empty();
            }
            id = dot < 0 ? rest : rest.substring(0, dot);
        } else {
            id = rest.substring(0, slash);
            if (id.indexOf('.') >= 0 || slash == rest.length() - 1) {
                return Optional.empty();
            }
        }
        if (id.isEmpty()) {
            return Optional.empty();
        }
        var key = DocumentKey.of(collection, id);
        return key.isOk() ? Optional.ofNullable(key.value()) : Optional.empty();
    }

    /**
     * Tells whether a path belongs to a document or entry.
     *
     * @param key  the key of the document or entry
     * @param path the repository-relative path
     * @return whether the path is the document's file, lies below its directory or is its file with a suffix
     * @throws NullPointerException if an argument is {@code null}
     */
    static boolean belongs(DocumentKey key, String path) {
        Objects.requireNonNull(key, "key");
        return key(key.collection(), path).filter(key::equals).isPresent();
    }

    /**
     * Groups the paths of a tree into the documents and entries of the given collections (GDR-STORE-9).
     *
     * @param collections the collections whose documents are grouped
     * @param paths       the repository-relative paths of the files of a tree
     * @return the paths of every document or entry, by key, both in the order the paths come; a path that belongs to
     *         no document of the collections is left out
     * @throws NullPointerException if an argument or one of its elements is {@code null}
     */
    static Map<DocumentKey, List<String>> documents(Collection<DocumentCollection> collections, Iterable<String> paths) {
        Map<String, DocumentCollection> byName = byName(collections);
        Map<DocumentKey, List<String>> documents = new LinkedHashMap<>();
        for (String path : Objects.requireNonNull(paths, "paths")) {
            key(byName, path).ifPresent(key -> documents.computeIfAbsent(key, k -> new ArrayList<>()).add(path));
        }
        return documents;
    }

    /**
     * Maps changed paths to the documents and entries of the given collections they change (GDR-STORE-9).
     *
     * @param collections the collections whose documents are looked for
     * @param paths       the repository-relative changed paths
     * @return the key of every document or entry with at least one changed path, in the order the paths come
     * @throws NullPointerException if an argument or one of its elements is {@code null}
     */
    static Set<DocumentKey> changed(Collection<DocumentCollection> collections, Iterable<String> paths) {
        Map<String, DocumentCollection> byName = byName(collections);
        Set<DocumentKey> changed = new LinkedHashSet<>();
        for (String path : Objects.requireNonNull(paths, "paths")) {
            key(byName, path).ifPresent(changed::add);
        }
        return changed;
    }

    /** The key of a path among the collections by name: the collection named by the path's first segment. */
    private static Optional<DocumentKey> key(Map<String, DocumentCollection> byName, String path) {
        Objects.requireNonNull(path, "path");
        int slash = path.indexOf('/');
        @Nullable DocumentCollection collection = slash < 0 ? null : byName.get(path.substring(0, slash));
        return collection == null ? Optional.empty() : key(collection, path);
    }

    private static Map<String, DocumentCollection> byName(Collection<DocumentCollection> collections) {
        Map<String, DocumentCollection> byName = new HashMap<>();
        for (DocumentCollection collection : Objects.requireNonNull(collections, "collections")) {
            byName.put(Objects.requireNonNull(collection, "collection").name(), collection);
        }
        return byName;
    }
}
