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

/**
 * Generates a {@link GitCredential} for an authenticated transport: a generated user name and a generated token.
 * Every call draws fresh values and hands out a fresh token array, so no two credentials share the array a caller may
 * clear.
 * <p>
 * The values are random letters, never real secrets.
 *
 * @since 0.1
 */
public final class GitCredentialGenerator implements TypedGenerator<GitCredential> {

    /**
     * Creates a generator of transport credentials.
     */
    public GitCredentialGenerator() {
        // no state: every call of next() draws fresh values
    }

    /**
     * Generates a credential with a user name of 1 to 20 letters and a token of 8 to 40 letters in a fresh array.
     *
     * @return a new credential of random values
     */
    @Override
    public GitCredential next() {
        var username = Generators.letterStrings(1, 20).next();
        var token = Generators.letterStrings(8, 40).next().toCharArray();
        return new GitCredential(username, token);
    }

    /**
     * Reports the generated type.
     *
     * @return {@link GitCredential}
     */
    @Override
    public Class<GitCredential> getType() {
        return GitCredential.class;
    }
}
