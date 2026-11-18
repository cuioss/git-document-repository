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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

@DisplayName("GitCredential")
@EnableGeneratorController
class GitCredentialTest {

    private final GitCredentialGenerator generator = new GitCredentialGenerator();

    @RepeatedTest(10)
    @DisplayName("Should not equal a credential with another user name")
    void shouldNotEqualCredentialWithAnotherUsername() {
        var credential = generator.next();
        var otherUsername = credential.username() + Generators.letterStrings(1, 8).next();

        var other = new GitCredential(otherUsername, credential.token().clone());

        assertNotEquals(credential, other, "Credentials with different user names differ");
    }

    @RepeatedTest(10)
    @DisplayName("Should not equal a credential with another token")
    void shouldNotEqualCredentialWithAnotherToken() {
        var credential = generator.next();
        var otherToken = (new String(credential.token()) + Generators.letterStrings(1, 8).next()).toCharArray();

        var other = new GitCredential(credential.username(), otherToken);

        assertNotEquals(credential, other, "Credentials with different tokens differ");
    }

    @Test
    @DisplayName("Should not equal an object of another type or null")
    void shouldNotEqualAnotherTypeOrNull() {
        var credential = generator.next();
        Object username = credential.username();
        Object nothing = null;

        assertAll("other objects",
                () -> assertNotEquals(credential, username, "A credential never equals its user name as a string"),
                () -> assertNotEquals(credential, nothing, "A credential never equals null"));
    }

    @Test
    @DisplayName("Should equal itself")
    void shouldEqualItself() {
        var credential = generator.next();

        assertEquals(credential, credential, "A credential equals itself");
    }

    @Test
    @DisplayName("Should render the user name and mask the token")
    void shouldRenderUsernameAndMaskToken() {
        var credential = generator.next();

        assertEquals("GitCredential[" + credential.username() + ", ***]", credential.toString(),
                "The string form names the user name and a mask in place of the token");
    }
}
