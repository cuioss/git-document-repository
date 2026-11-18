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

import java.io.Serial;


import org.eclipse.jgit.api.errors.JGitInternalException;

/**
 * Raised by {@link RefusingFS} when an operation would run a present hook or start a process (GDR-CORE-1).
 * <p>
 * It extends JGit's unchecked {@link JGitInternalException}, so JGit propagates it unchanged through its callers of
 * {@link org.eclipse.jgit.util.FS}. The repository operations map it to {@link GitOutcome#REFUSED_BY_POLICY} with the
 * refused capability as detail. The message names the capability and what would have been run, never a secret.
 *
 * @since 0.1
 */
public final class CapabilityRefusedException extends JGitInternalException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The refused capability, e.g. {@code repository_hook}. */
    private final String reason;

    /**
     * Creates the exception.
     *
     * @param reason the refused capability, e.g. {@code repository_hook} or {@code process_execution}
     * @param detail what would have been run, e.g. the hook name
     */
    public CapabilityRefusedException(String reason, String detail) {
        super("refused_by_policy{reason: " + reason + "}: " + detail);
        this.reason = reason;
    }

    /**
     * Returns the refused capability.
     *
     * @return the refused capability
     */
    public String getReason() {
        return reason;
    }
}
