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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


import org.jspecify.annotations.Nullable;

/**
 * The contribution of a consumer to the in-memory index of a repository (GDR-STORE-9): for one document or entry, the
 * value the index keeps for it.
 * <p>
 * A contributor is registered per {@link DocumentCollection} when a {@link StoreIndex} is opened. The index calls it for
 * every document of that collection it reads, with the key, the source reference the document was read from and the
 * document's files ({@link DocumentPaths}); the index keeps only the value the contributor returns, never the files.
 * A contributor that returns {@code null} leaves the document out of the index; one that throws a
 * {@link RuntimeException} or an {@link Error} other than a {@link VirtualMachineError} leaves it out as well, the
 * failure being logged with the class name of the throwable only, and the index reads the next document; a
 * {@link VirtualMachineError} propagates.
 * <p>
 * A contributor is called on the thread that refreshes the index, one document after the other, under the lock of the
 * index, after the operation that moved the references released the locks of the clone. Every refresh, snapshot and
 * open of the index waits while it runs, and the save, release or fetch that triggered the refresh returns to its
 * caller only afterwards, so a contributor is expected to return within milliseconds per document: it decodes the
 * files it is given and calls no remote service. It must not block on the store; a call into the writer that would
 * wait for a lock the calling thread holds, such as {@link StoreWriter#expireDrafts} from a thread under the shared
 * side of the clone's maintenance lock, is refused with {@link StoreOutcome#REFUSED} instead of a deadlock. A contributor
 * that calls the writer waits for another thread's save, release or fetch to release the transport lock; a save step or
 * an anchor provider that calls {@link StoreIndex#snapshot(String)} or opens an index is refused, so that wait cannot
 * close a cycle.
 *
 * @param <E> the type of the entry value
 * @since 0.1
 */
@FunctionalInterface
public interface IndexContributor<E> {

    /**
     * Returns the entry value of one document.
     *
     * @param document the document as read from its source reference
     * @return the entry value the index keeps, or {@code null} to leave the document out
     */
    @Nullable
    E contribute(Document document);

    /**
     * One document or entry as the index read it.
     *
     * @param key    the key of the document or entry
     * @param source the full name of the reference, or the full commit id of a snapshot, the document was read from
     * @param files  the content of every file of the document by repository-relative path, in the order of the tree
     * @since 0.1
     */
    record Document(DocumentKey key, String source, Map<String, byte[]> files) {

        /**
         * Checks the components and copies the files.
         *
         * @param key    the key
         * @param source the source
         * @param files  the files
         * @throws NullPointerException if a component, a path or a content is {@code null}
         */
        public Document {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(source, "source");
            files = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(files, "files")));
            files.forEach((path, content) -> {
                Objects.requireNonNull(path, "path");
                Objects.requireNonNull(content, "content");
            });
        }

        /**
         * Renders the key and the source only, never a path or a content (GDR-NFR-3).
         *
         * @return the rendering
         */
        @Override
        public String toString() {
            return "Document[key=" + key + ", source=" + source + ", files=" + files.size() + "]";
        }
    }
}
