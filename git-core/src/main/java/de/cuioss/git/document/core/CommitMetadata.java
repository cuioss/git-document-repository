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

import java.util.Objects;


import org.eclipse.jgit.lib.PersonIdent;
import org.jspecify.annotations.Nullable;

/**
 * What the caller sets on a commit written in core (GDR-CORE-4): author, committer, message and, optionally, the
 * signing. The parents are passed to {@link InCoreWriter#write(org.eclipse.jgit.lib.Repository, java.util.List,
 * CommitMetadata, java.util.List)} beside it.
 * <p>
 * {@link #toString()} renders neither an identity nor the message, because both may carry a person name or an e-mail
 * address (GDR-NFR-3).
 *
 * @param author    the author of the commit
 * @param committer the committer of the commit, also the signing identity
 * @param message   the commit message
 * @param signing   the signing of the commit; {@code null} for an unsigned commit
 * @since 0.1
 */
public record CommitMetadata(PersonIdent author, PersonIdent committer, String message,
@Nullable CommitSigning signing) {

    /**
     * Checks the components.
     *
     * @param author    the author of the commit
     * @param committer the committer of the commit
     * @param message   the commit message
     * @param signing   the signing, {@code null} for an unsigned commit
     * @throws NullPointerException if {@code author}, {@code committer} or {@code message} is {@code null}
     */
    public CommitMetadata {
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(committer, "committer");
        Objects.requireNonNull(message, "message");
    }

    /**
     * Renders whether the commit is signed, never an identity or the message.
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "CommitMetadata[signed=" + (signing != null) + "]";
    }
}
