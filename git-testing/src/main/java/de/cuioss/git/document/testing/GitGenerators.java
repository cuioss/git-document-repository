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

import de.cuioss.test.generator.TypedGenerator;

/**
 * Registers the generators of this module in one place: one factory method per {@link TypedGenerator}. A factory is
 * named for {@code @TypeGeneratorFactorySource(factoryClass = GitGenerators.class, factoryMethod = "credentials")} or
 * {@code factoryMethod = "distinctTexts"}.
 *
 * @since 0.1
 */
public final class GitGenerators {

    private GitGenerators() {
        // factory methods only
    }

    /**
     * Creates a generator of transport credentials.
     *
     * @return a generator of transport credentials, each with a fresh token array
     */
    public static TypedGenerator<GitCredential> credentials() {
        return new GitCredentialGenerator();
    }

    /**
     * Creates a generator of text values that are distinct by construction and occur in no text a test did not put
     * them in; see {@link DistinctTextGenerator}.
     *
     * @return a new generator, numbering its values from 1
     */
    public static TypedGenerator<String> distinctTexts() {
        return new DistinctTextGenerator();
    }
}
