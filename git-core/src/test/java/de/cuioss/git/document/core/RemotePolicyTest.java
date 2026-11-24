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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Locale;


import de.cuioss.git.document.testing.GitGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The remote policy of the transport. Every remote URL below is a literal by design: the scheme, host, port and
 * spelling of each is the remote-policy grammar under test, and each row states the verdict that grammar assigns it.
 */
@EnableGeneratorController
@DisplayName("RemotePolicy (GDR-CORE-2)")
class RemotePolicyTest {

    /** The https origin the policy admits: the contract under test. */
    private static final URI ORIGIN = URI.create("https://github.com");

    /** The values asserted absent from a message or rendering: distinct by construction, found nowhere by chance. */
    private final TypedGenerator<String> distinctTexts = GitGenerators.distinctTexts();

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

    @Test
    @DisplayName("GDR-NFR-3: the refusal of an origin renders no part of it, user information included")
    void refusalRendersNoOrigin() {
        var password = distinctTexts.next();
        var origin = URI.create("http://user:" + password + "@example.invalid");

        var refusal = assertThrows(IllegalArgumentException.class, () -> RemotePolicy.httpsOrigin(origin),
                "an origin without https is refused");

        assertFalse(refusal.getMessage().contains(password), "the message carries no password");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"https:example.invalid", "http://example.invalid", "https://example.invalid:0",
            "https://example.invalid:65536"})
    @DisplayName("GDR-CORE-2: the canonical constructor refuses what httpsOrigin refuses (URLs are the policy grammar)")
    void canonicalConstructorRefusesOrigin(String origin) {
        var uri = URI.create(origin);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new RemotePolicy(uri),
                        "the canonical constructor refuses the origin"),
                () -> assertThrows(IllegalArgumentException.class, () -> RemotePolicy.httpsOrigin(uri),
                        "httpsOrigin refuses the origin"));
    }

    @Test
    @DisplayName("GDR-CORE-2: the canonical constructor builds what the factories build")
    void canonicalConstructorMatchesFactories() {
        assertAll(
                () -> assertEquals(RemotePolicy.httpsOrigin(ORIGIN), new RemotePolicy(ORIGIN),
                        "an https origin is valid through both paths"),
                () -> assertEquals(RemotePolicy.localOnly(), new RemotePolicy(null),
                        "no origin is the local-only policy"));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "https://github.com:0/cuioss/x.git, false",
            "https://github.com:65536/cuioss/x.git, false",
            "https://github.com:443/cuioss/x.git, true"
    })
    @DisplayName("GDR-CORE-2: refuses an explicit remote port outside 1 to 65535 (JGit 7.8.0 sends port 0 to 443)")
    void remotePortRange(String remote, boolean permitted) throws Exception {
        var policy = RemotePolicy.httpsOrigin(ORIGIN);

        assertEquals(permitted, policy.permits(new URIish(remote)), "the policy decides the explicit port");
    }

    @Test
    @DisplayName("GDR-CORE-2: an origin with explicit port 443 admits a remote with an omitted port")
    void explicitDefaultPort() throws Exception {
        var policy = RemotePolicy.httpsOrigin(URI.create("https://github.com:443"));

        assertTrue(policy.permits(new URIish("https://github.com/cuioss/x.git")), "an omitted port means 443");
    }

    @Test
    @DisplayName("PLAN-02 (f), GDR-NFR-3: toString renders the origin as scheme, host and port, never user, path or query")
    void toStringRendersOriginOnly() {
        // distinct values: none occurs by chance in the generated host or in another of them
        var user = distinctTexts.next();
        var password = distinctTexts.next();
        var path = distinctTexts.next();
        var query = distinctTexts.next();
        var host = Generators.letterStrings(4, 12).next().toLowerCase(Locale.ROOT) + ".example.invalid";
        var origin = URI.create("https://" + user + ":" + password + "@" + host + ":8443/" + path + "?" + query);

        var rendered = new RemotePolicy(origin).toString();

        assertAll(
                () -> assertEquals("RemotePolicy[https://" + host + ":8443]", rendered,
                        "the origin is rendered as scheme, host and port"),
                () -> assertFalse(rendered.contains(user), "no user name is rendered"),
                () -> assertFalse(rendered.contains(password), "no password is rendered"),
                () -> assertFalse(rendered.contains(path), "no path is rendered"),
                () -> assertFalse(rendered.contains(query), "no query is rendered"),
                () -> assertEquals("RemotePolicy[localOnly]", RemotePolicy.localOnly().toString(),
                        "a policy without origin is rendered as local only"));
    }
}
