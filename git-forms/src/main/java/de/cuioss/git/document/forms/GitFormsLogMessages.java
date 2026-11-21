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
package de.cuioss.git.document.forms;

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;
import lombok.experimental.UtilityClass;

/**
 * The {@link LogRecord} holder of {@code git-forms} (GDR-GEN-4). Identifiers carry the prefix {@value #PREFIX} and
 * follow the CUI ranges: INFO 1-99, WARN 100-199, ERROR 200-299. The catalogue is
 * {@code git-forms/doc/LogMessages.adoc}.
 * <p>
 * No message carries anything GDR-NFR-3 forbids: a message names a number, a directory or a commit id, never a
 * configured or entered value, a token, a password, an e-mail address or a person name.
 *
 * @since 0.1
 */
@UtilityClass
public final class GitFormsLogMessages {

    /** The prefix of every identifier of {@code git-forms}. */
    public static final String PREFIX = "GIT_FORMS";

    /**
     * Messages at level INFO.
     *
     * @since 0.1
     */
    @UtilityClass
    public static final class INFO {

        /**
         * The kinds of documents were loaded: their number and the source, {@code directory <path>} for a
         * configuration directory or {@code commit <id>} for a commit of a configuration repository, never a
         * configured value.
         */
        public static final LogRecord KINDS_LOADED = LogRecordModel.builder()
                .template("Loaded %s kinds of documents from %s")
                .prefix(PREFIX)
                .identifier(1)
                .build();
    }
}
