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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;


import de.cuioss.git.document.core.JGitRepositoryOperations.TransportFailures;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The classification of a transport failure (GDR-CORE-7): the wordings JGit 7.8.0 uses for a refused authentication
 * (HTTP 401 and 403, from {@code JGitText.properties} and {@code TransportHttp}) are
 * {@link GitOutcome#CREDENTIALS_INVALID}, every other transport failure is {@link GitOutcome#UNREACHABLE}. A local
 * remote never authenticates, so the classification is tested on the wordings.
 * <p>
 * The wordings are literals by design: each is a message JGit produces, the closed set under test.
 */
@EnableGeneratorController
@DisplayName("TransportFailures classification (GDR-CORE-7)")
class TransportFailuresTest {

    static Stream<String> refusedAuthentication() {
        var url = "https://" + TestCommits.name() + ".example.invalid/" + TestCommits.name() + ".git";
        return Stream.of(
                url + ": not authorized",
                url + ": Authentication is required but no CredentialsProvider has been registered",
                url + ": authentication not supported",
                url + ": git-upload-pack not permitted on '" + url + "/'",
                url + ": git-receive-pack not permitted on '" + url + "/'",
                "NOT AUTHORIZED");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedAuthentication")
    @DisplayName("GDR-CORE-7: a refused authentication is recognised")
    void recognised(String message) {
        assertTrue(TransportFailures.isRefusedAuthentication(message), "a refused authentication is recognised");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "https://example.invalid/r.git: Connection refused",
            "https://example.invalid/r.git: 503 Service Unavailable",
            "https://example.invalid/r.git: connection failed",
            "Read timed out",
            "https://example.invalid/r.git: not found.",
            "file:///tmp/r: not a git directory",
            "upload-pack: not our ref",
            ""})
    @DisplayName("GDR-CORE-7: any other transport failure is not a refused authentication (the wordings are the contract)")
    void notRecognised(String message) {
        assertFalse(TransportFailures.isRefusedAuthentication(message),
                "another transport failure is not a refused authentication");
    }

    @Test
    @DisplayName("a failure without message is not a refused authentication")
    void noMessage() {
        assertFalse(TransportFailures.isRefusedAuthentication(null), "a failure without a message is not recognised");
    }

    @Test
    @DisplayName("GDR-CORE-7: a refused authentication anywhere in the cause chain is credentials invalid")
    void classifiesCauseChain() throws Exception {
        var uri = new URIish("https://" + TestCommits.name() + ".example.invalid/" + TestCommits.name() + ".git");
        var refused = new org.eclipse.jgit.api.errors.TransportException("wrapped",
                new TransportException(uri, "not authorized"));
        var unreachable = new org.eclipse.jgit.api.errors.TransportException("wrapped",
                new TransportException(uri, "connection failed"));

        assertAll(
                () -> assertEquals(GitOutcome.CREDENTIALS_INVALID, TransportFailures.classify(refused),
                        "a wrapped refused authentication is credentials invalid"),
                () -> assertEquals(GitOutcome.UNREACHABLE, TransportFailures.classify(unreachable),
                        "a wrapped connection failure is unreachable"),
                () -> assertEquals(GitOutcome.UNREACHABLE, TransportFailures.classify(new TransportException(uri, "")),
                        "a failure without a known wording is unreachable"));
    }
}
