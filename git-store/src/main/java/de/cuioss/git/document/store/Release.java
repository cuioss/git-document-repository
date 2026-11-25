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

import java.util.Objects;


import de.cuioss.git.document.core.GitIdentity;

/**
 * A completed release of a document or numbered entry (GDR-STORE-6): the release tag on the remote and the release
 * commit on {@code main} it points at.
 * <p>
 * A release that completes an {@link StoreOutcome#INCOMPLETE} one only pushes the missing tag on the release commit
 * written before; the working or collection branch may have moved on since that commit, and the release then does not
 * hold the latest saved state of the document. {@link #branchMovedOn()} reports that case, so the caller can release
 * again.
 *
 * @param tag           the full name of the release tag, {@code refs/tags/<collection>/<id>/v<n>}
 * @param commitId      the full id of the release commit the tag points at
 * @param version       the version {@code n} of the release, counted from 1 per document or entry
 * @param branchMovedOn whether the working or collection branch moved on since the released commit; only a release
 *                      that pushed the missing tag of an earlier, incomplete release can report {@code true}
 * @since 0.1
 */
public record Release(String tag, String commitId, int version, boolean branchMovedOn) {

    /**
     * Checks the components.
     *
     * @param tag           the full name of the release tag
     * @param commitId      the full id of the release commit
     * @param version       the version
     * @param branchMovedOn whether the branch moved on since the released commit
     * @throws NullPointerException     if {@code tag} or {@code commitId} is {@code null}
     * @throws IllegalArgumentException if {@code tag} lies outside {@value StoreReferences#TAGS}, {@code commitId} is
     *                                  no full commit id or {@code version} is less than 1
     */
    public Release {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(commitId, "commitId");
        if (!tag.startsWith(StoreReferences.TAGS)) {
            throw new IllegalArgumentException("tag: not a tag reference");
        }
        if (!GitIdentity.isFullCommitId(commitId)) {
            throw new IllegalArgumentException("commitId: not a full object id");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version: less than 1");
        }
    }
}
