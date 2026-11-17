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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.test.generator.junit.EnableGeneratorController;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

@DisplayName("GitCredential generator")
@EnableGeneratorController
class GitCredentialGeneratorTest {

    private final GitCredentialGenerator generator = new GitCredentialGenerator();

    @RepeatedTest(20)
    @DisplayName("Should yield credentials with a user name and a token")
    void shouldYieldValidCredentials() {
        var generated = generator.next();

        assertAll("generated credential",
                () -> assertFalse(generated.username().isBlank(), "The user name of the credential is set"),
                () -> assertTrue(generated.token().length >= 8, "The token holds at least eight characters"),
                () -> assertFalse(generated.toString().contains(new String(generated.token())),
                        "The string form of the credential masks the token"));
    }

    @RepeatedTest(20)
    @DisplayName("Should hand out a fresh token array per credential")
    void shouldHandOutAFreshTokenArrayPerCredential() {
        var first = generator.next();
        var second = generator.next();
        var secondBefore = second.token().clone();

        Arrays.fill(first.token(), '\0');

        assertAll("token arrays",
                () -> assertNotSame(first.token(), second.token(), "Two credentials share no token array"),
                () -> assertTrue(Arrays.equals(secondBefore, second.token()),
                        "Clearing one token leaves the other intact"));
    }

    @RepeatedTest(20)
    @DisplayName("Should compare credentials by content, not by token array identity")
    void shouldCompareCredentialsByContent() {
        var generated = generator.next();
        var copy = new GitCredential(generated.username(), generated.token().clone());

        assertAll("content-based equality",
                () -> assertNotSame(generated.token(), copy.token(), "The two credentials hold distinct arrays"),
                () -> assertEquals(generated, copy, "Credentials with equal values are equal"),
                () -> assertEquals(generated.hashCode(), copy.hashCode(),
                        "Credentials with equal values have equal hash codes"));
    }

    @Test
    @DisplayName("Should report the generated type")
    void shouldReportTheGeneratedType() {
        assertEquals(GitCredential.class, generator.getType(), "The generator names the credential type");
    }

    @Test
    @DisplayName("Should be registered by the module factory")
    void shouldBeRegisteredByTheModuleFactory() {
        assertEquals(GitCredential.class, GitGenerators.credentials().getType(),
                "The factory yields the credential generator");
    }
}
