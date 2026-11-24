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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.core.TreeEdit;
import org.jspecify.annotations.Nullable;

/**
 * The writes of one opened clone ({@link StoreClone}): the drafts, their listing, the explicit save, the release and
 * the draft expiry, over {@link DraftReferences}, {@link ExplicitSave}, {@link DocumentRelease} and
 * {@link DraftExpiry}. A successful save and a complete or incomplete release hand one due check of the anchoring to
 * the schedules of the clone ({@link CloneSchedules#triggerDueCheck()}) once every lock of the operation was released.
 * The locks and their order are those {@link StoreWriter} documents; this class adds none.
 */
final class CloneWrites {

    private final ExplicitSave.Clone clone;
    private final Parts parts;
    private final Runnable dueCheck;

    /**
     * The parts that write to the clone.
     *
     * @param drafts   the drafts
     * @param saves    the explicit save
     * @param releases the release
     * @param expiry   the draft expiry
     */
    record Parts(DraftReferences drafts, ExplicitSave saves, DocumentRelease releases, DraftExpiry expiry) {

        /**
         * Checks the parts.
         *
         * @param drafts   the drafts
         * @param saves    the explicit save
         * @param releases the release
         * @param expiry   the draft expiry
         * @throws NullPointerException if a part is {@code null}
         */
        Parts {
            Objects.requireNonNull(drafts, "drafts");
            Objects.requireNonNull(saves, "saves");
            Objects.requireNonNull(releases, "releases");
            Objects.requireNonNull(expiry, "expiry");
        }
    }

    /**
     * Creates the writes of a clone.
     *
     * @param clone    the opened bare clone with its maintenance and transport locks
     * @param parts    the parts that write to the clone
     * @param dueCheck hands one due check of the anchoring to the registered schedule, after the locks were released
     * @throws NullPointerException if an argument is {@code null}
     */
    CloneWrites(ExplicitSave.Clone clone, Parts parts, Runnable dueCheck) {
        this.clone = Objects.requireNonNull(clone, "clone");
        this.parts = Objects.requireNonNull(parts, "parts");
        this.dueCheck = Objects.requireNonNull(dueCheck, "dueCheck");
    }

    /** Writes a draft under the shared side of the maintenance lock; see {@link StoreWriter#draft}. */
    StoreResult<String> draft(Author author, DocumentKey key, List<TreeEdit> edits, String message) {
        return clone.guard().shared(() -> parts.drafts().write(author, key, edits, message));
    }

    /** Lists the drafts of a user under the shared side of the maintenance lock; see {@link StoreWriter#drafts}. */
    StoreResult<List<Draft>> drafts(String pseudonym) {
        return clone.guard().shared(() -> parts.drafts().list(pseudonym));
    }

    /** Saves a draft; see {@link StoreWriter#save(Author, IdentityClaims, DocumentKey, Map, String, SaveStep)}. */
    SaveResult save(Author author, IdentityClaims claims, DocumentKey key, Map<String, String> expectedBlobIds,
            String message, @Nullable SaveStep step) {
        SaveResult result = parts.saves().save(author, claims, key, expectedBlobIds, message, step);
        if (result instanceof SaveResult.Saved) {
            dueCheck.run();
        }
        return result;
    }

    /**
     * Releases a document or entry, pinned to a commit or not; see
     * {@link StoreWriter#release(Author, IdentityClaims, DocumentKey, String)} and
     * {@link StoreWriter#release(Author, IdentityClaims, DocumentKey)}.
     */
    StoreResult<Release> release(Author author, IdentityClaims claims, DocumentKey key,
            @Nullable String expectedCommitId) {
        var result = parts.releases().release(author, claims, key, expectedCommitId);
        if (result.isOk() || result.outcome() == StoreOutcome.INCOMPLETE) {
            dueCheck.run();
        }
        return result;
    }

    /** Expires the drafts; see {@link StoreWriter#expireDrafts}. */
    StoreResult<List<String>> expireDrafts(Instant now) {
        return parts.expiry().expire(now);
    }
}
