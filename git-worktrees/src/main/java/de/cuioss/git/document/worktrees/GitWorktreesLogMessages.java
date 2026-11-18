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
package de.cuioss.git.document.worktrees;

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;
import lombok.experimental.UtilityClass;

/**
 * The {@link LogRecord} holder of {@code git-worktrees} (GDR-GEN-4). Identifiers carry the prefix {@value #PREFIX} and
 * follow the CUI ranges: INFO 1-99, WARN 100-199, ERROR 200-299. The catalogue is
 * {@code git-worktrees/doc/LogMessages.adoc}.
 * <p>
 * No message carries anything GDR-NFR-3 forbids: a message names a commit id or a refused capability, never a path, an
 * administrative name derived from the caller's path, an author or committer name, an e-mail address or a commit
 * message.
 *
 * @since 0.1
 */
@UtilityClass
public final class GitWorktreesLogMessages {

    /** The prefix of every identifier of {@code git-worktrees}. */
    public static final String PREFIX = "GIT_WORKTREES";

    /**
     * Messages at level INFO.
     *
     * @since 0.1
     */
    @UtilityClass
    public static final class INFO {

        /** A linked working tree was added: the commit it starts at. */
        public static final LogRecord WORKTREE_ADDED = LogRecordModel.builder()
                .template("Added a linked working tree at commit %s")
                .prefix(PREFIX)
                .identifier(1)
                .build();

        /** A linked working tree was removed; the message carries no argument. */
        public static final LogRecord WORKTREE_REMOVED = LogRecordModel.builder()
                .template("Removed a linked working tree")
                .prefix(PREFIX)
                .identifier(2)
                .build();
    }

    /**
     * Messages at level WARN.
     *
     * @since 0.1
     */
    @UtilityClass
    public static final class WARN {

        /** A capability was refused by the refusing file system: the refused capability. */
        public static final LogRecord CAPABILITY_REFUSED = LogRecordModel.builder()
                .template("Refused capability %s")
                .prefix(PREFIX)
                .identifier(100)
                .build();

        /** A commit was created in a linked working tree but its reflog entry was not written: the commit id. */
        public static final LogRecord REFLOG_NOT_WRITTEN = LogRecordModel.builder()
                .template("Created commit %s, its reflog entry was not written")
                .prefix(PREFIX)
                .identifier(101)
                .build();
    }
}
