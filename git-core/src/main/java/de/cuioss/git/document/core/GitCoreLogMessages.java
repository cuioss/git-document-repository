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
 * The {@link LogRecord} holder of {@code git-core} (GDR-GEN-4). Identifiers carry the prefix {@value #PREFIX} and
 * follow the CUI ranges: INFO 1-99, WARN 100-199, ERROR 200-299. The catalogue is {@code git-core/doc/LogMessages.adoc}.
 * <p>
 * No message carries anything GDR-NFR-3 forbids: a URL is rendered without user information, and neither a
 * credential nor a JGit message that may carry one is logged.
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
    }
}
