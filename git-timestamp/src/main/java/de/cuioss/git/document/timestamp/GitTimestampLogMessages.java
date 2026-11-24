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

/**
 * The {@link LogRecord} holder of {@code git-timestamp} (GDR-GEN-4). Identifiers carry the prefix {@value #PREFIX} and
 * follow the CUI ranges: INFO 1-99, WARN 100-199, ERROR 200-299. The catalogue is
 * {@code git-timestamp/doc/LogMessages.adoc}.
 * <p>
 * No message carries anything GDR-NFR-3 forbids: a message never names the bearer token of
 * {@code timestamp.credentials}, the user information of a URL, the body of a response or the content of a
 * certificate file.
 *
 * @since 0.1
 */
public final class GitTimestampLogMessages {

    /** The prefix of every identifier of {@code git-timestamp}. */
    public static final String PREFIX = "GIT_TIMESTAMP";

    private GitTimestampLogMessages() {
        // holder of constants
    }
}
