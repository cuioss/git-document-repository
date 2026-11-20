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

/**
 * Converts a value to the content of a file and back (GDR-STORE-11).
 * <p>
 * The store reads and writes a document only through a codec, which defines its file format; the store itself knows
 * no format. Every codec must keep two rules, so that a save without a change of the value changes no blob:
 * <ul>
 * <li>A round trip is byte-identical: for every value {@code v}, {@code encode(decode(encode(v)).value())} equals
 * {@code encode(v)} byte for byte.</li>
 * <li>Encoding is canonical: two equal values, and an unchanged value encoded again, yield byte-identical content.</li>
 * </ul>
 * A content the codec cannot read is the expected outcome {@link StoreOutcome#MALFORMED}, never an exception, and its
 * detail names what could not be read (for example a line number or a key) without repeating the content.
 *
 * @param <V> the value type
 * @since 0.1
 */
public interface Codec<V> {

    /**
     * Encodes a value as the content of a file.
     *
     * @param value the value
     * @return the content, canonical for the value: equal values yield byte-identical content
     */
    byte[] encode(V value);

    /**
     * Decodes the content of a file.
     *
     * @param content the content
     * @return {@link StoreOutcome#OK} with the decoded value, or {@link StoreOutcome#MALFORMED} for a content the codec
     *         cannot read, with a detail that does not contain the content
     */
    StoreResult<V> decode(byte[] content);
}
