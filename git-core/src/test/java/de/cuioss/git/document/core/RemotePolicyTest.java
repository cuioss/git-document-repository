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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;


import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The remote policy of the transport. Every remote URL below is a literal by design: the scheme, host, port and
 * spelling of each is the remote-policy grammar under test, and each row states the verdict that grammar assigns it.
 */
@EnableGeneratorController
@DisplayName("RemotePolicy (GDR-CORE-2)")
class RemotePolicyTest {

    /** The https origin the policy admits: the contract under test. */
    private static final URI ORIGIN = URI.create("https://github.com");

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "https://github.com/cuioss/x.git, true",
            "https://GITHUB.com:443/cuioss/x.git, true",
            "https://github.com:8443/cuioss/x.git, false",
            "https://gitlab.com/cuioss/x.git, false",
            "http://github.com/cuioss/x.git, false",
            "git@github.com:cuioss/x.git, false",
            "ssh://git@github.com/cuioss/x.git, false",
            "file:///tmp/x.git, false"
    })
    @DisplayName("GDR-CORE-2: admits only https URLs of the origin's host and port (URLs are the policy grammar)")
    void httpsOrigin(String remote, boolean permitted) throws Exception {
        var policy = RemotePolicy.httpsOrigin(ORIGIN);

        assertEquals(permitted, policy.permits(new URIish(remote)), "the https policy decides the remote's URL");
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "file:///tmp/x.git, true",
            "/tmp/x.git, true",
            "https://github.com/cuioss/x.git, false",
            "git@github.com:cuioss/x.git, false"
    })
    @DisplayName("GDR-CORE-2: admits local repositories only and refuses every network remote (URLs are the policy grammar)")
    void localOnly(String remote, boolean permitted) throws Exception {
        assertEquals(permitted, RemotePolicy.localOnly().permits(new URIish(remote)),
                "the local policy decides the remote's URL");
    }

    @Test
    @DisplayName("GDR-CORE-2: rejects a non-https origin (the http scheme is the contract)")
    void rejectsOrigin() {
        var origin = URI.create("http://github.com");

        assertThrows(IllegalArgumentException.class, () -> RemotePolicy.httpsOrigin(origin),
                "an origin without https is refused");
    }

    @Test
    @DisplayName("GDR-CORE-2: rejects an https origin without a host (the opaque form is the contract)")
    void rejectsOriginWithoutHost() {
        var origin = URI.create("https:github.com");

        assertThrows(IllegalArgumentException.class, () -> RemotePolicy.httpsOrigin(origin),
                "an origin without a host is refused");
    }
}
