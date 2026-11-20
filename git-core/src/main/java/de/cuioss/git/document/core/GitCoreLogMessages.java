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

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;
import lombok.experimental.UtilityClass;

/**
 * The {@link LogRecord} holder of {@code git-core} (GDR-GEN-4), for both its packages: the repository part in
 * {@code de.cuioss.git.document.core} and the working-tree part in {@code de.cuioss.git.document.core.worktrees}.
 * Identifiers carry the prefix {@value #PREFIX} and follow the CUI ranges: INFO 1-99, WARN 100-199, ERROR 200-299. The
 * catalogue is {@code git-core/doc/LogMessages.adoc}.
 * <p>
 * No message carries anything GDR-NFR-3 forbids: a URL is rendered without user information, and neither a
 * credential nor a JGit message that may carry one is logged. A message of the working-tree part names a commit id or
 * a refused capability, never a path, an administrative name derived from the caller's path, a branch name, an author
 * or committer name, an e-mail address or a commit message.
 *
 * @since 0.1
 */
@UtilityClass
public final class GitCoreLogMessages {

    /** The prefix of every identifier of {@code git-core}. */
    public static final String PREFIX = "GIT_CORE";

    /**
     * Messages at level INFO.
     *
     * @since 0.1
     */
    @UtilityClass
    public static final class INFO {

        /** A bare clone was created: the git directory and the URL without user information. */
        public static final LogRecord CLONE_CREATED = LogRecordModel.builder()
                .template("Created bare clone at %s from %s")
                .prefix(PREFIX)
                .identifier(1)
                .build();

        /** A linked working tree was added: the commit it starts at. */
        public static final LogRecord WORKTREE_ADDED = LogRecordModel.builder()
                .template("Added a linked working tree at commit %s")
                .prefix(PREFIX)
                .identifier(2)
                .build();

        /** A linked working tree was removed; the message carries no argument. */
        public static final LogRecord WORKTREE_REMOVED = LogRecordModel.builder()
                .template("Removed a linked working tree")
                .prefix(PREFIX)
                .identifier(3)
                .build();

        /**
         * A branch reservation was released; the message carries no argument, neither the branch name nor a path.
         */
        public static final LogRecord BRANCH_RESERVATION_RELEASED = LogRecordModel.builder()
                .template("Released a branch reservation")
                .prefix(PREFIX)
                .identifier(4)
                .build();
    }

    /**
     * Messages at level WARN.
     *
     * @since 0.1
     */
    @UtilityClass
    public static final class WARN {

        /** A remote was refused by the remote policy before any contact: the URL without user information. */
        public static final LogRecord REMOTE_REFUSED = LogRecordModel.builder()
                .template("Refused remote %s: outside the remote policy")
                .prefix(PREFIX)
                .identifier(100)
                .build();

        /** A capability was refused by the refusing file system: the refused capability. */
        public static final LogRecord CAPABILITY_REFUSED = LogRecordModel.builder()
                .template("Refused capability %s")
                .prefix(PREFIX)
                .identifier(101)
                .build();

        /**
         * A capability was refused by the refusing file system during a working-tree operation: the refused
         * capability.
         */
        public static final LogRecord WORKTREE_CAPABILITY_REFUSED = LogRecordModel.builder()
                .template("Refused capability %s")
                .prefix(PREFIX)
                .identifier(102)
                .build();

        /** A commit was created in a linked working tree but its reflog entry was not written: the commit id. */
        public static final LogRecord REFLOG_NOT_WRITTEN = LogRecordModel.builder()
                .template("Created commit %s, its reflog entry was not written")
                .prefix(PREFIX)
                .identifier(103)
                .build();

        /**
         * The rollback of a refused or failed addition did not undo everything it created, for example a created
         * branch it could not delete; the message carries no argument, neither the branch name nor a path.
         */
        public static final LogRecord ROLLBACK_INCOMPLETE = LogRecordModel.builder()
                .template("The rollback of a failed working-tree addition is incomplete")
                .prefix(PREFIX)
                .identifier(104)
                .build();
    }
}
