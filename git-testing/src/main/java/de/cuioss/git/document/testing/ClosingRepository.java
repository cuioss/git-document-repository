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
package de.cuioss.git.document.testing;

import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * A repository without a directory, stored in the extension context so that JUnit Jupiter closes it after the test.
 * It is both an {@link AutoCloseable} and an {@link ExtensionContext.Store.CloseableResource} with one
 * {@link #close()}, so Jupiter closes it exactly once whether
 * {@code junit.jupiter.extensions.store.close.autocloseable.enabled} is {@code true} or {@code false}.
 *
 * @param repository the repository handed to the test, closed on {@link #close()}
 */
// CloseableResource is deprecated, but it is the only store type Jupiter closes while
// junit.jupiter.extensions.store.close.autocloseable.enabled=false (junit-jupiter-engine 6.1.3,
// AbstractExtensionContext)
@SuppressWarnings("deprecation")
record ClosingRepository(Repository repository) implements AutoCloseable, ExtensionContext.Store.CloseableResource {

    /**
     * Closes the repository.
     */
    @Override
    public void close() {
        repository.close();
    }
}
