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
package de.cuioss.git.document.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;


import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The classification of a transport failure of {@code fetchCommit} (GDR-CORE-8): the closed set of wordings by which a
 * remote that answered refuses a wanted commit is {@link GitOutcome#UNSERVED}; every other failure is
 * {@link GitOutcome#UNREACHABLE}, the remote did not answer.
 * <p>
 * The wordings are literals by design: each is a message a remote or JGit sends, the closed set under test. The
 * commit id inside a refusal is generated.
 */
@EnableGeneratorController
@DisplayName("Unserved want classification of JGitRepositoryOperations (GDR-CORE-8)")
class UnservedWantTest {

    static Stream<String> refusals() {
        var sha = JGitCacheRepositoryTest.commitId();
        return Stream.of(
                "upload-pack: not our ref " + sha,
                "https://github.com/o/r: remote error: upload-pack: not our ref " + sha,
                "ERR upload-pack: not our ref " + sha,
                "fatal: remote error: upload-pack: not our ref " + sha,
                "Not Our Ref " + sha,
                "want " + sha + " not valid",
                "file:///tmp/r: want " + sha + " not valid",
                "want " + sha + " not found");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    @DisplayName("GDR-CORE-8: a remote's refusal of the wanted commit is recognised")
    void recognised(String message) {
        assertTrue(JGitRepositoryOperations.isUnservedWant(message), "a refusal of the wanted commit is recognised");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://github.com/o/r: Connection refused",
            "https://github.com/o/r: 503 Service Unavailable",
            "Read timed out",
            "https://github.com/o/r: not found",
            "https://github.com/o/r: authentication not supported",
            "the wanted pack is not valid",
            ""})
    @DisplayName("GDR-CORE-8: any other transport failure is not recognised (the wordings are the contract)")
    void notRecognised(String message) {
        assertFalse(JGitRepositoryOperations.isUnservedWant(message), "another transport failure is not recognised");
    }

    @Test
    @DisplayName("a failure without message is not recognised")
    void noMessage() {
        assertFalse(JGitRepositoryOperations.isUnservedWant(null), "a failure without a message is not recognised");
    }
}
