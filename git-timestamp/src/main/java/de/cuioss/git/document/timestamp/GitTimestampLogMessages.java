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
package de.cuioss.git.document.timestamp;

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;

/**
 * The {@link LogRecord} holder of {@code git-timestamp} (GDR-GEN-4). Identifiers carry the prefix {@value #PREFIX} and
 * follow the CUI ranges: INFO 1-99, WARN 100-199, ERROR 200-299. The catalogue is
 * {@code git-timestamp/doc/LogMessages.adoc}.
 * <p>
 * No message carries anything GDR-NFR-3 forbids: a message names a repository alias, an object id, a time or the
 * fixed detail of an outcome, never the URL of the authority, the bearer token of {@code timestamp.credentials}, the
 * body of a reply or the content of a certificate file.
 *
 * @since 0.1
 */
public final class GitTimestampLogMessages {

    /** The prefix of every identifier of {@code git-timestamp}. */
    public static final String PREFIX = "GIT_TIMESTAMP";

    private GitTimestampLogMessages() {
        // holder of constants
    }

    /**
     * Messages at level INFO.
     *
     * @since 0.1
     */
    public static final class INFO {

        /**
         * A timestamp token was obtained: the repository alias, the object id and the time the token states.
         */
        public static final LogRecord TOKEN_OBTAINED = LogRecordModel.builder()
                .template("Obtained a timestamp token for repository %s, object %s, time %s")
                .prefix(PREFIX)
                .identifier(1)
                .build();

        private INFO() {
            // holder of constants
        }
    }

    /**
     * Messages at level WARN.
     *
     * @since 0.1
     */
    public static final class WARN {

        /**
         * The timestamp authority could not be reached: the repository alias, the object id and the fixed detail of
         * the outcome. The subject stays pending.
         */
        public static final LogRecord AUTHORITY_UNAVAILABLE = LogRecordModel.builder()
                .template("Timestamp authority unavailable for repository %s, object %s: %s")
                .prefix(PREFIX)
                .identifier(100)
                .build();

        /**
         * The timestamp authority refused or answered with an error: the repository alias, the object id and the fixed
         * detail of the outcome. The subject stays pending.
         */
        public static final LogRecord AUTHORITY_REFUSED = LogRecordModel.builder()
                .template("Timestamp authority refused for repository %s, object %s: %s")
                .prefix(PREFIX)
                .identifier(101)
                .build();

        private WARN() {
            // holder of constants
        }
    }
}
