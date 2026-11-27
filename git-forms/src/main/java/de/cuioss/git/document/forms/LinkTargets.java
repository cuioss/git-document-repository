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

import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreIndex;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The resolution of the targets of typed links (GDR-FORMS-8), which the application passes into
 * {@link DocumentTransitions}: the kinds of documents, the {@link StoreIndex} of every repository a link may name, by
 * its alias, and the {@link CatalogResolver} of the catalogs.
 * <p>
 * A {@link TypedLink.DocumentLink} exists when the configuration has a kind of its {@code kind}, the alias of the link is
 * the repository of that kind, and the index of that alias holds the key of the link in the collection of the kind: on
 * the working branch of a document, the collection branch of an entry, or {@code main}. The fragment is stripped first:
 * a link with a fragment exists exactly when the document without it exists, and the fragment is never looked at. The
 * index holds a document only when a contributor is registered for its collection and returns a value for it
 * (GDR-STORE-9), so the application registers one for every collection a link may name. A
 * {@link TypedLink.CatalogLink} exists when the {@link CatalogResolver} says so, a retired entry included.
 * <p>
 * The resolution reads only the published values of the indexes ({@link StoreIndex#entries(String, DocumentCollection)}),
 * no snapshot and no {@code open}, so it runs inside the per-attempt step of a save without the {@code REFUSED} a callback
 * that holds a lock of the clone receives, and it waits for no lock. An index reflects the branches of its clone at its
 * last refresh: a document created by another writer becomes a target once a fetch of this writer refreshed the index.
 * An instance holds no state of its own and may serve every thread, as far as its resolver does.
 *
 * @since 0.1
 */
public final class LinkTargets {

    /** The detail of a document link to a repository whose index the application did not pass. */
    static final String NO_INDEX = "link: no index for the repository of the kind";

    private final KindConfiguration kinds;
    private final Map<String, StoreIndex<?>> indexes;
    private final CatalogResolver catalogs;

    /**
     * Creates the resolution of link targets.
     *
     * @param kinds    the kinds of documents, which give the repository and the collection of each kind
     * @param indexes  the index of every repository a document link may name, by its alias
     * @param catalogs the resolver of the catalog links
     * @throws NullPointerException if an argument, an alias or an index is {@code null}
     */
    // the wildcard is needed: StoreIndex is final but generic, so a Map<String, StoreIndex<Risk>> is no
    // Map<String, StoreIndex<?>>; java:S4968 does not see that the type argument differs
    @SuppressWarnings("java:S4968")
    public LinkTargets(KindConfiguration kinds, Map<String, ? extends StoreIndex<?>> indexes,
            CatalogResolver catalogs) {
        this.kinds = Objects.requireNonNull(kinds, "kinds");
        this.indexes = Map.copyOf(Objects.requireNonNull(indexes, "indexes"));
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs");
    }

    /**
     * Tells whether the target of a link exists.
     *
     * @param link the link
     * @return {@link FormsOutcome#OK} with whether the target exists; for a document link to a kind whose repository
     *         has no index the passed-on {@link StoreOutcome#MALFORMED} with the detail {@value #NO_INDEX}; for a catalog
     *         link the non-success of the {@link CatalogResolver} passed on
     * @throws NullPointerException if {@code link} is {@code null}
     */
    public FormsResult<Boolean> exists(TypedLink link) {
        Objects.requireNonNull(link, "link");
        return switch (link) {
            case TypedLink.DocumentLink document -> documentExists(document);
            case TypedLink.CatalogLink entry -> catalogEntryExists(entry);
        };
    }

    private FormsResult<Boolean> documentExists(TypedLink.DocumentLink link) {
        Optional<Kind> found = kinds.find(link.kind());
        if (found.isEmpty() || !found.get().repository().equals(link.alias())) {
            return FormsResult.ok(false);
        }
        Kind kind = found.get();
        @Nullable StoreIndex<?> index = indexes.get(link.alias());
        if (index == null) {
            return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, NO_INDEX));
        }
        StoreResult<DocumentCollection> collection = DocumentCollection.of(kind.collection(),
                kind.isNumbered() ? DocumentCollection.Kind.NUMBERED : DocumentCollection.Kind.PER_DOCUMENT);
        if (!collection.isOk()) {
            return FormsResult.ok(false);
        }
        DocumentCollection named = Objects.requireNonNull(collection.value());
        StoreResult<DocumentKey> key = DocumentKey.of(named, link.id());
        if (!key.isOk()) {
            return FormsResult.ok(false);
        }
        DocumentKey target = Objects.requireNonNull(key.value());
        return FormsResult.ok(index.entries(StoreReferences.target(target), named).containsKey(target)
                || index.entries(StoreReferences.MAIN, named).containsKey(target));
    }

    private FormsResult<Boolean> catalogEntryExists(TypedLink.CatalogLink link) {
        StoreResult<Boolean> resolved = catalogs.resolve(link.catalog(), link.id());
        if (!resolved.isOk()) {
            return FormsResult.passOn(resolved);
        }
        return FormsResult.ok(Boolean.TRUE.equals(resolved.value()));
    }
}
