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

import java.util.Objects;

/**
 * The running numbers of a numbered kind of document (GDR-FORMS-1, GDR-FORMS-9): a prefix and a counter zero-padded to
 * a number of digits, for example prefix {@code R-} with three digits for {@code R-042}.
 *
 * @param prefix the prefix of every number
 * @param digits the number of digits the counter is zero-padded to, at least 1
 * @since 0.1
 */
public record Numbering(String prefix, int digits) {

    /**
     * Checks the components.
     *
     * @param prefix the prefix
     * @param digits the number of digits
     * @throws NullPointerException     if {@code prefix} is {@code null}
     * @throws IllegalArgumentException if {@code digits} is less than 1
     */
    public Numbering {
        Objects.requireNonNull(prefix, "prefix");
        if (digits < 1) {
            throw new IllegalArgumentException("digits must be at least 1");
        }
    }
}
