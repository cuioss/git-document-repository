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
package de.cuioss.git.document.timestamp;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;


import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.store.AnchorVerification;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.IdentityClaims;
import de.cuioss.git.document.store.StoreConfiguration;
import de.cuioss.git.document.store.StoreProviders;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.StoreRepository;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.StoreWriter;
import de.cuioss.git.document.store.TechnicalUser;
import de.cuioss.git.document.store.Ulid;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GDR-TSA-1, GDR-TSA-2 and GDR-STORE-15 end to end: with {@code anchor.provider = rfc3161}, the store selects the
 * configured provider among its candidates, releases a document onto a {@code git-testing} local remote, anchors the
 * release through the in-process test authority, and {@link AnchorVerification#verifyAnchors} verifies every proof with
 * the same provider. An unreachable authority leaves the subject pending without failing the release. No test needs
 * network access.
 */
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("GDR-TSA-1, GDR-TSA-2, GDR-STORE-15: anchoring with rfc3161 end to end")
class Rfc3161AnchoringTest {

    private static final IdentityClaims CLAIMS = new IdentityClaims("https://issuer.example/realms/test",
            Instant.parse("2026-03-01T08:15:30Z"), "urn:example:acr:mfa", List.of("pwd", "otp"));

    private static final ClosingFixtures FIXTURES = new ClosingFixtures();

    private static TestTimestampAuthority authority;
    private static HttpClient client;

    @TempDir
    static Path certificates;

    private static Path trust;

    @TempDir
    Path temp;

    @BeforeAll
    static void startAuthority() throws IOException {
        var ca = TestCertificates.ca("test ca");
        var tls = TestCertificates.tls(ca);
        authority = FIXTURES.add(new TestTimestampAuthority(tls, ca, TestCertificates.signer(ca, "test tsa", true)),
                TestTimestampAuthority::close);
        var builder = TimestampTransport.clientBuilder().sslContext(TestCertificates.clientContext(ca));
        client = FIXTURES.add(builder.build(), HttpClient::close);
        trust = Files.writeString(certificates.resolve("ca.pem"), TestCertificates.pem(ca.certificate()), US_ASCII);
    }

    @AfterAll
    static void stopAuthority() {
        FIXTURES.closeAll();
    }

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.outcome() + " " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    /** The shared configuration tree of the store and of the provider, with the section {@code timestamp}. */
    private static Map<String, Object> tree(URI authorityUrl) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", "P30D"));
        tree.put("fetch", Map.of("interval", "PT10M"));
        tree.put("signing", Map.of("key-source", TestStoreProviders.SIGNING_ID, "key", word()));
        tree.put("directory", Map.of("persons", Map.of("provider", TestStoreProviders.DIRECTORY_ID)));
        tree.put("anchor", Map.of("provider", Rfc3161AnchorProvider.PROVIDER_ID,
                "checkpoint-interval", Duration.ofHours(1).toString(), "retry-interval", Duration.ofHours(12).toString()));
        tree.put("timestamp", Map.of("url", authorityUrl.toString(), "trust", List.of(trust.toString())));
        return tree;
    }

    /** The writer and the provider it anchors with. */
    private record Opened(StoreWriter writer, Rfc3161AnchorProvider provider) implements AutoCloseable {

        @Override
        public void close() {
            writer.close();
        }
    }

    private Opened open(LocalRemote remote, URI authorityUrl) {
        var tree = tree(authorityUrl);
        StoreConfiguration configuration = value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
        Rfc3161AnchorProvider provider = value(Rfc3161AnchorProvider.from(tree, client));
        StoreProviders providers = value(StoreProviders.select(configuration, List.of(TestStoreProviders.signing()),
                List.of(provider), List.of(TestStoreProviders.directory())));
        assertEquals(provider, providers.anchor(), "anchor.provider = rfc3161 selects the configured provider");
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var writer = value(StoreWriter.open(storeRepository, temp.resolve(word() + ".git"), user, configuration,
                List.of(), providers, new JGitRepositoryOperations(RemotePolicy.localOnly()), Clock.systemUTC()));
        return new Opened(writer, provider);
    }

    /** Writes a commit holding exactly the files, nested paths included, onto a reference of the remote. */
    private static ObjectId commit(Repository repository, String ref, Map<String, String> files, ObjectId... parents)
            throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, String> file : new TreeMap<>(files).entrySet()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file.getValue().getBytes(UTF_8)));
                builder.add(entry);
            }
            builder.finish();
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "");
            commit.setTreeId(index.writeTree(inserter));
            commit.setParentIds(parents);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(ref);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            var result = update.update();
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED
                    || result == RefUpdate.Result.FAST_FORWARD, () -> "the fixture reference is written: " + result);
            return id;
        }
    }

    /**
     * Prepares a per-document repository: {@code main} with one file, and the working branch of the document forked
     * from it with the document's file added.
     */
    private static DocumentKey document(Repository repository) throws IOException {
        var collection = value(DocumentCollection.of("c" + word(), DocumentCollection.Kind.PER_DOCUMENT));
        var key = value(DocumentKey.of(collection, Ulid.next()));
        Map<String, String> base = Map.of(word() + ".txt", word());
        var start = commit(repository, StoreReferences.MAIN, base);
        Map<String, String> working = new TreeMap<>(base);
        working.put(collection.name() + "/" + key.id() + ".json", word());
        commit(repository, StoreReferences.target(key), working, start);
        return key;
    }

    private static Author author() {
        return Author.of(Generators.strings("abcdefghijklmnopqrstuvwxyz0123456789", 16, 24).next());
    }

    @Test
    @DisplayName("GDR-TSA-1, GDR-TSA-2, GDR-STORE-15: a release is anchored with rfc3161 and every proof verifies")
    void shouldAnchorAndVerifyRelease(@Remote LocalRemote remote) throws Exception {
        var key = document(remote.repository());

        try (var opened = open(remote, authority.url())) {
            var release = value(opened.writer().release(author(), CLAIMS, key));
            var anchoring = value(opened.writer().anchor());

            var report = value(AnchorVerification.verifyAnchors(remote.repository(), opened.provider()));

            assertAll(
                    () -> assertEquals(List.of(), anchoring.pending(), "nothing left pending"),
                    () -> assertTrue(anchoring.anchored().contains(release.commitId()),
                            () -> "the release commit is anchored: " + anchoring.anchored()),
                    () -> assertEquals(List.of(), report.findings(), "no finding"),
                    () -> assertTrue(report.clean(), "a complete anchoring"),
                    () -> assertEquals(report.subjects(), report.proofs(), "every subject has a verified proof"),
                    () -> assertTrue(report.subjects() >= 1, "the release commit is a subject"));
        }
    }

    @Test
    @DisplayName("GDR-TSA-1, GDR-STORE-15: an unreachable authority leaves the subject pending and the release stands")
    void shouldLeaveSubjectPendingWhenUnreachable(@Remote LocalRemote remote) throws Exception {
        var key = document(remote.repository());
        int port;
        try (var socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = socket.getLocalPort();
        }

        try (var opened = open(remote, URI.create("https://127.0.0.1:" + port + "/tsa"))) {
            var release = value(opened.writer().release(author(), CLAIMS, key));
            var anchoring = value(opened.writer().anchor());

            var report = value(AnchorVerification.verifyAnchors(remote.repository(), opened.provider()));

            assertAll(
                    () -> assertEquals(release.commitId(),
                            remote.repository().exactRef(release.tag()).getObjectId().name(),
                            "the release tag is on the remote"),
                    () -> assertTrue(anchoring.pending().contains(release.commitId()),
                            () -> "the release commit stays pending: " + anchoring.pending()),
                    () -> assertEquals(List.of(), anchoring.anchored(), "nothing anchored"),
                    () -> assertFalse(report.clean(), "the release commit has no proof"),
                    () -> assertTrue(report.findings().contains(new AnchorVerification.Finding(
                                    AnchorVerification.Kind.NO_PROOF, release.commitId(), null, null)),
                            () -> "the release commit is listed without a proof: " + report.findings()));
        }
    }
}
