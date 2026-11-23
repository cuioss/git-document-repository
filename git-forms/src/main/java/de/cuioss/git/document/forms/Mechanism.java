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

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * The mechanism of the store a transition of a workflow maps onto (GDR-FORMS-5).
 *
 * @since 0.1
 */
public enum Mechanism {

    /** An intermediate save on the user's draft reference (GDR-STORE-2); it keeps the state of the document. */
    INTERMEDIATE_SAVE("intermediate-save"),

    /** An explicit save onto the working branch (GDR-STORE-3). */
    SAVE("save"),

    /** A release onto {@code main} with its tag (GDR-STORE-6). */
    RELEASE("release");

    private final String fileName;

    Mechanism(String fileName) {
        this.fileName = fileName;
    }

    /**
     * The spelling of the mechanism in a workflow file.
     *
     * @return {@code intermediate-save}, {@code save} or {@code release}
     */
    public String fileName() {
        return fileName;
    }

    /**
     * Finds a mechanism by its spelling in a workflow file.
     *
     * @param fileName the spelling
     * @return the mechanism, or empty for a spelling no mechanism has
     * @throws NullPointerException if {@code fileName} is {@code null}
     */
    public static Optional<Mechanism> fromFileName(String fileName) {
        Objects.requireNonNull(fileName, "fileName");
        return Arrays.stream(values()).filter(mechanism -> mechanism.fileName.equals(fileName)).findFirst();
    }
}
