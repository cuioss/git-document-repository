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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Period;


import de.cuioss.git.document.core.InCoreWriter;
import de.cuioss.git.document.core.ReferenceUpdater;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The refusal of a draft expiry re-entered from a callback (GDR-STORE-7): the guard refuses it before the cutoff is
 * computed, so even a time the cutoff cannot be computed for ({@link Instant#MAX}) is refused and throws nothing. The
 * detail is the specified vocabulary and kept as a literal; names are generated.
 */
@EnableGeneratorController
@DisplayName("Draft expiry re-entered from a callback (GDR-STORE-7)")
class DraftExpiryReentryTest {

    @TempDir
    Path temp;

    @Test
    @DisplayName("GDR-STORE-7: an expiry from a thread holding the shared lock is REFUSED before its cutoff is computed")
    void reenteredExpiryIsRefusedBeforeTheCutoff() {
        var alias = Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
        try (Repository repository = new InMemoryRepository(new DfsRepositoryDescription(alias))) {
            var guard = RepositoryGuard.of(temp.resolve(alias));
            var expiry = new DraftExpiry(alias, repository, guard, new ReferenceUpdater(new InCoreWriter()),
                    Period.ofDays(Generators.integers(1, 90).next()), 1);

            // a callback of a save, a listener or a contributor runs under the shared side of the maintenance lock
            var result = guard.shared(() -> expiry.expire(Instant.MAX));

            assertEquals(StoreResult.of(StoreOutcome.REFUSED,
                            "maintenance: re-entered from a callback that holds the shared lock"), result,
                    "the re-entered expiry is refused and the time is never converted");
        }
    }
}
