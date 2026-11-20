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

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;
import lombok.experimental.UtilityClass;

/**
 * The {@link LogRecord} holder of {@code git-store} (GDR-GEN-4). Identifiers carry the prefix {@value #PREFIX} and
 * follow the CUI ranges: INFO 1-99, WARN 100-199, ERROR 200-299. The catalogue is
 * {@code git-store/doc/LogMessages.adoc}.
 * <p>
 * No message carries anything GDR-NFR-3 forbids: a message names a provider id, a configuration key, a repository
 * alias or a path, never a configured value, a key reference, key material, a token, a password or the user
 * information of a remote.
 *
 * @since 0.1
 */
@UtilityClass
public final class GitStoreLogMessages {

    /** The prefix of every identifier of {@code git-store}. */
    public static final String PREFIX = "GIT_STORE";

    /**
     * Messages at level INFO.
     *
     * @since 0.1
     */
    @UtilityClass
    public static final class INFO {

        /**
         * The providers of the store were selected: the ids of the signing key source, the person directory and the
         * anchor provider.
         */
        public static final LogRecord PROVIDERS_SELECTED = LogRecordModel.builder()
                .template("Selected the providers: signing key source %s, person directory %s, anchor provider %s")
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

        /**
         * The start was refused: the detail of the result, which names a key, an alias or a provider id, never a
         * value.
         */
        public static final LogRecord START_REFUSED = LogRecordModel.builder()
                .template("Refused the start: %s")
                .prefix(PREFIX)
                .identifier(100)
                .build();
    }
}
