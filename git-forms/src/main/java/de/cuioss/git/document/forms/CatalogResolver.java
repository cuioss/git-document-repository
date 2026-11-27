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

import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;

/**
 * Resolves the target of a {@link TypedLink.CatalogLink} (GDR-FORMS-8): tells whether a catalog holds an entry. A
 * catalog provider implements it, for example the directory of {@code git-directory}, and the application passes it
 * into {@link LinkTargets}.
 * <p>
 * A retired entry ({@code retired: true}) must resolve: it exists, so a document that links it can still be saved, and
 * {@link #resolve(String, String)} returns {@code true} for it.
 * <p>
 * {@link #resolve(String, String)} is called inside the per-attempt step of a save, under the save locks of the store,
 * once per catalog link of the data and on every attempt. It must therefore return within milliseconds, read only
 * state it holds in memory, and call no remote service and no operation of the store; a slow or blocking resolver
 * holds up every save of the repository. A {@link RuntimeException} it throws ends the save as a refused step of the
 * store.
 *
 * @since 0.1
 */
@FunctionalInterface
public interface CatalogResolver {

    /**
     * Tells whether a catalog holds an entry.
     *
     * @param catalog the name of the catalog, of the form {@code [a-z0-9-]+}
     * @param id      the id of the entry, of the form {@code [A-Za-z0-9_-]+}; data of the document, never logged
     * @return {@link StoreOutcome#OK} with {@code true} when the catalog holds the entry, a retired entry included, and
     *         {@code false} when it does not hold it or the catalog is unknown; any other outcome when the catalog
     *         cannot be read, which refuses the save with that outcome passed on; a detail never names the id
     */
    StoreResult<Boolean> resolve(String catalog, String id);
}
