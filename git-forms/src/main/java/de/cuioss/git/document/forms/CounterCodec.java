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

import java.nio.charset.StandardCharsets;
import java.util.Objects;


import de.cuioss.git.document.store.Codec;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;

/**
 * The codec of the counter file {@value DocumentLayout#COUNTER} of a numbered kind (GDR-FORMS-3, GDR-FORMS-9): the last
 * running number given, as one decimal integer without sign and without leading zero, followed by one line feed, for
 * example {@code 42\n}; a counter that has given no number yet is {@code 0\n}.
 * <p>
 * Any other content is {@link StoreOutcome#MALFORMED}, its detail never repeating the content: an empty file, a sign, a
 * leading zero, a character that is no ASCII digit, a missing or a further line feed, and a number beyond the range of
 * {@code long}. The codec defines the format only; how the counter is advanced is the business of the running numbers.
 * The codec holds no state; one instance may serve every thread.
 *
 * @since 0.1
 */
public final class CounterCodec implements Codec<Long> {

    private static final String FORMAT = DocumentLayout.COUNTER
            + ": not one decimal integer without sign or leading zero, followed by one line feed";

    /**
     * Encodes a counter.
     *
     * @param value the counter, not negative
     * @return the canonical content: the decimal digits and one line feed
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if {@code value} is negative
     */
    @Override
    public byte[] encode(Long value) {
        Objects.requireNonNull(value, "value");
        if (value < 0) {
            throw new IllegalArgumentException("value: a counter is not negative");
        }
        return (value + "\n").getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Decodes a counter.
     *
     * @param content the content of the counter file
     * @return {@link StoreOutcome#OK} with the counter, or {@link StoreOutcome#MALFORMED}
     * @throws NullPointerException if {@code content} is {@code null}
     */
    @Override
    public StoreResult<Long> decode(byte[] content) {
        Objects.requireNonNull(content, "content");
        int digits = content.length - 1;
        if (digits < 1 || content[digits] != '\n' || content[0] == '0' && digits > 1) {
            return StoreResult.of(StoreOutcome.MALFORMED, FORMAT);
        }
        long value = 0;
        for (int index = 0; index < digits; index++) {
            byte digit = content[index];
            if (digit < '0' || digit > '9') {
                return StoreResult.of(StoreOutcome.MALFORMED, FORMAT);
            }
            if (value > (Long.MAX_VALUE - (digit - '0')) / 10) {
                return StoreResult.of(StoreOutcome.MALFORMED, DocumentLayout.COUNTER + ": beyond the range of a long");
            }
            value = value * 10 + (digit - '0');
        }
        return StoreResult.ok(value);
    }
}
