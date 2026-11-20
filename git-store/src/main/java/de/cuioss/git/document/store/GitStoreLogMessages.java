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
 * alias, a path, a reference, a commit id, an outcome or a number, never a configured value, a key reference, key
 * material, a token, a password, the user information of a remote, a content, an e-mail address or a person name. The
 * messages of a save name no path of the saved document, only the number of conflicting paths.
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

        /** A save was accepted by the remote: the repository alias, the target reference and the commit id. */
        public static final LogRecord SAVED = LogRecordModel.builder()
                .template("Saved repository %s, reference %s at commit %s")
                .prefix(PREFIX)
                .identifier(2)
                .build();

        /**
         * A save conflicted and pushed nothing: the repository alias, the target reference and the number of
         * conflicting paths, never a path.
         */
        public static final LogRecord SAVE_CONFLICT = LogRecordModel.builder()
                .template("Did not save repository %s, reference %s: %s conflicting paths")
                .prefix(PREFIX)
                .identifier(3)
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
         * The start was refused: the detail of the result, which names a key, an alias or a service interface, never
         * a value.
         */
        public static final LogRecord START_REFUSED = LogRecordModel.builder()
                .template("Refused the start: %s")
                .prefix(PREFIX)
                .identifier(100)
                .build();

        /**
         * A save did not complete, for a reason other than a conflict: the repository alias, the target reference and
         * the outcome.
         */
        public static final LogRecord SAVE_NOT_COMPLETED = LogRecordModel.builder()
                .template("Did not complete the save of repository %s, reference %s: %s")
                .prefix(PREFIX)
                .identifier(101)
                .build();
    }
}
