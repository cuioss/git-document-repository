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
package de.cuioss.git.document.testing;

import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates text values that are distinct by construction, for a test that asserts a value absent from a text the code
 * under test produced, or that relies on two generated values differing.
 * <p>
 * Every value is the fixed marker {@value #MARKER}, the number of the value within this generator, the separator
 * {@code -} and a few generated lower-case letters, for example {@code gdrdistinct7-kqp}. The marker occurs in no text a
 * test did not put it in, so a value found in a text was put there by the test; the number makes two values of one
 * generator differ, and the separator after it keeps one value from being the start of another (value 1 is no prefix of
 * value 12). A value consists of lower-case letters, digits and {@code -} only, so it is a valid reference name
 * component and file name, and it never reads as a commit id.
 * <p>
 * The numbering belongs to the instance: values of two instances may be equal, so a test draws the values it compares
 * from one instance. The generator is safe for use by several threads.
 *
 * @since 0.1
 */
public final class DistinctTextGenerator implements TypedGenerator<String> {

    /** The fixed start of every value. */
    public static final String MARKER = "gdrdistinct";

    /** The separator between the number of a value and its generated letters. */
    private static final char SEPARATOR = '-';

    private final TypedGenerator<String> letters = Generators.strings("abcdefghijklmnopqrstuvwxyz", 3, 8);
    private final AtomicLong count = new AtomicLong();

    /**
     * Creates a generator whose first value is number 1; {@link GitGenerators#distinctTexts()} is the factory method.
     */
    public DistinctTextGenerator() {
        // the numbering starts with the first call of next()
    }

    /**
     * Generates the next value.
     *
     * @return the marker, the next number of this generator, the separator and generated letters
     */
    @Override
    public String next() {
        return MARKER + count.incrementAndGet() + SEPARATOR + letters.next();
    }

    /**
     * Returns the type of the generated values.
     *
     * @return {@link String}
     */
    @Override
    public Class<String> getType() {
        return String.class;
    }
}
