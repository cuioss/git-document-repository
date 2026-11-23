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
package de.cuioss.git.document.store;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.ServiceConfigurationError;
import java.util.logging.Level;
import java.util.logging.LogRecord;


import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.store.StoreConfiguration.Anchor;
import de.cuioss.git.document.store.StoreConfiguration.Commit;
import de.cuioss.git.document.store.StoreConfiguration.Directory;
import de.cuioss.git.document.store.StoreConfiguration.Drafts;
import de.cuioss.git.document.store.StoreConfiguration.Fetch;
import de.cuioss.git.document.store.StoreConfiguration.Save;
import de.cuioss.git.document.store.StoreConfiguration.Signing;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The selection of the providers of the three service interfaces (GDR-STORE-12, GDR-STORE-15, GDR-STORE-18,
 * GDR-STORE-19): exactly one signing key source and one person directory, at most one anchor provider, the built-in
 * provider {@code signed} with its anchor key and the refusal of an anchor key equal to the commit-signing key, a missing
 * or duplicate configured id
 * refused as {@link StoreOutcome#MALFORMED} naming the service interface and the configuration key and logged as
 * {@code GIT_STORE-100}, a success logged as {@code GIT_STORE-1}, {@link StoreProviders#load(StoreConfiguration)}
 * finding the test providers registered under {@code META-INF/services} and, beside them, the shipped signing key
 * source {@code file} through the class-path provider-configuration file of {@code git-store}, and GDR-NFR-3: neither
 * a configured id nor the message of a failing provider reaches a detail or a log record.
 * <p>
 * The provider ids are generated; the ids of the registered test providers are fixed by their registration.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@DisplayName("StoreProviders (provider selection of git-store)")
class StoreProvidersTest {

    private static final TypedGenerator<String> IDS =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz-", 4, 16));

    private static final RepositoryRegistry EMPTY_REGISTRY = Objects.requireNonNull(
            RepositoryRegistry.of(List.of(), RemotePolicy.httpsOrigin(URI.create("https://git.example"))).value());

    private static String id() {
        return IDS.next();
    }

    /** A configuration naming the given provider ids. */
    private static StoreConfiguration configuration(String signing, String persons, @Nullable String anchor) {
        return new StoreConfiguration(EMPTY_REGISTRY, new Save(1), new Drafts(Period.ofDays(1)),
                new Fetch(Duration.ofMinutes(1)), new Signing(signing, null, null),
                new Anchor(anchor, true, Anchor.DEFAULT_CHECKPOINT_INTERVAL, Anchor.DEFAULT_RETRY_INTERVAL,
                        new Anchor.Signed(null, null, null, Anchor.Signed.DEFAULT_MAX_CLOCK_SKEW)),
                new Directory(persons), new Commit(Commit.DEFAULT_EMAIL));
    }

    private static StoreProviders providers(StoreResult<StoreProviders> result) {
        assertTrue(result.isOk(), () -> "the providers are selected: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries the providers");
    }

    /** Asserts a {@link StoreOutcome#MALFORMED} refusal with exactly the detail, logged once as GIT_STORE-100. */
    private static void assertRefused(String detail, StoreResult<StoreProviders> result) {
        assertEquals(StoreResult.of(StoreOutcome.MALFORMED, detail), result, "the start is refused");
        LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                GitStoreLogMessages.WARN.START_REFUSED.resolveIdentifierString());
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, detail);
        assertNotLogged(Level.INFO, GitStoreLogMessages.INFO.PROVIDERS_SELECTED.resolveIdentifierString());
    }

    /**
     * Asserts that no log record of this test at the level carries the text. The level matters because the identifier
     * {@code GIT_STORE-1} is a prefix of {@code GIT_STORE-100}.
     */
    private static void assertNotLogged(Level level, String text) {
        var records = TestLoggerFactory.getTestHandler().getRecords();
        assertTrue(records.stream()
                        .filter(logRecord -> level.equals(logRecord.getLevel()))
                        .noneMatch(logRecord -> String.valueOf(logRecord.getMessage()).contains(text)),
                () -> "no log record at " + level + " carries " + text);
    }

    @Nested
    @DisplayName("Selection over explicit candidates")
    class Select {

        @Test
        @DisplayName("the configured providers are selected and GIT_STORE-1 names their ids")
        void selectsConfiguredProviders() {
            var signingId = id();
            var personsId = id();
            var anchorId = id();
            var signing = new TestSigningKeySource(signingId);
            var persons = new TestPersonDirectory(personsId);
            var anchor = new TestAnchorProvider(anchorId);

            var providers = providers(StoreProviders.select(configuration(signingId, personsId, anchorId),
                    List.of(new TestSigningKeySource(id()), signing), List.of(anchor, new TestAnchorProvider(id())),
                    List.of(persons)));

            assertAll(
                    () -> assertSame(signing, providers.signing(), "the configured signing key source"),
                    () -> assertSame(persons, providers.persons(), "the configured person directory"),
                    () -> assertSame(anchor, providers.anchor(), "the configured anchor provider"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    GitStoreLogMessages.INFO.PROVIDERS_SELECTED.resolveIdentifierString());
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                    "signing key source " + signingId + ", person directory " + personsId + ", anchor provider "
                            + anchorId);
            assertNotLogged(Level.WARNING, GitStoreLogMessages.WARN.START_REFUSED.resolveIdentifierString());
        }

        @Test
        @DisplayName("no anchor provider is selected when anchor.provider is absent")
        void noAnchorProviderWithoutConfiguration() {
            var signingId = id();
            var personsId = id();

            var providers = providers(StoreProviders.select(configuration(signingId, personsId, null),
                    List.of(new TestSigningKeySource(signingId)), List.of(new TestAnchorProvider(id())),
                    List.of(new TestPersonDirectory(personsId))));

            assertNull(providers.anchor(), "nothing is anchored without anchor.provider");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "anchor provider none");
        }

        @Test
        @DisplayName("two providers with an id nobody configured do not stop the start")
        void duplicateOfUnconfiguredIdIsIgnored() {
            var signingId = id();
            var personsId = id();
            var unconfigured = id();

            var result = StoreProviders.select(configuration(signingId, personsId, null),
                    List.of(new TestSigningKeySource(signingId), new TestSigningKeySource(unconfigured),
                            new TestSigningKeySource(unconfigured)),
                    List.of(new TestAnchorProvider(unconfigured), new TestAnchorProvider(unconfigured)),
                    List.of(new TestPersonDirectory(unconfigured), new TestPersonDirectory(personsId),
                            new TestPersonDirectory(unconfigured)));

            assertTrue(result.isOk(), () -> "the start goes on: " + result.detail());
        }

        @Test
        @DisplayName("a missing signing key source is MALFORMED naming the key")
        void missingSigningKeySource() {
            var signingId = id();
            var personsId = id();

            var result = StoreProviders.select(configuration(signingId, personsId, null),
                    List.of(new TestSigningKeySource(id())), List.of(), List.of(new TestPersonDirectory(personsId)));

            assertRefused("SigningKeySource: no provider with the id of signing.key-source", result);
        }

        @Test
        @DisplayName("two signing key sources with the configured id are MALFORMED naming the key")
        void duplicateSigningKeySource() {
            var signingId = id();
            var personsId = id();

            var result = StoreProviders.select(configuration(signingId, personsId, null),
                    List.of(new TestSigningKeySource(signingId), new TestSigningKeySource(signingId)), List.of(),
                    List.of(new TestPersonDirectory(personsId)));

            assertRefused("SigningKeySource: more than one provider with the id of signing.key-source", result);
        }

        @Test
        @DisplayName("a missing person directory is MALFORMED naming the key")
        void missingPersonDirectory() {
            var signingId = id();
            var personsId = id();

            var result = StoreProviders.select(configuration(signingId, personsId, null),
                    List.of(new TestSigningKeySource(signingId)), List.of(), List.of());

            assertRefused("PersonDirectory: no provider with the id of directory.persons.provider", result);
        }

        @Test
        @DisplayName("two person directories with the configured id are MALFORMED naming the key")
        void duplicatePersonDirectory() {
            var signingId = id();
            var personsId = id();

            var result = StoreProviders.select(configuration(signingId, personsId, null),
                    List.of(new TestSigningKeySource(signingId)), List.of(),
                    List.of(new TestPersonDirectory(personsId), new TestPersonDirectory(personsId)));

            assertRefused("PersonDirectory: more than one provider with the id of directory.persons.provider", result);
        }

        @Test
        @DisplayName("a missing configured anchor provider is MALFORMED naming the key")
        void missingAnchorProvider() {
            var signingId = id();
            var personsId = id();
            var anchorId = id();

            var result = StoreProviders.select(configuration(signingId, personsId, anchorId),
                    List.of(new TestSigningKeySource(signingId)), List.of(new TestAnchorProvider(id())),
                    List.of(new TestPersonDirectory(personsId)));

            assertRefused("AnchorProvider: no provider with the id of anchor.provider", result);
        }

        @Test
        @DisplayName("two anchor providers with the configured id are MALFORMED naming the key")
        void duplicateAnchorProvider() {
            var signingId = id();
            var personsId = id();
            var anchorId = id();

            var result = StoreProviders.select(configuration(signingId, personsId, anchorId),
                    List.of(new TestSigningKeySource(signingId)),
                    List.of(new TestAnchorProvider(anchorId), new TestAnchorProvider(anchorId)),
                    List.of(new TestPersonDirectory(personsId)));

            assertRefused("AnchorProvider: more than one provider with the id of anchor.provider", result);
        }

        @Test
        @DisplayName("the signing key source and the person directory are required components")
        void requiresSigningAndPersons() {
            var signing = new TestSigningKeySource();
            var persons = new TestPersonDirectory();

            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> new StoreProviders(null, persons, null)),
                    () -> assertThrows(NullPointerException.class, () -> new StoreProviders(signing, null, null)));
        }
    }

    @Nested
    @DisplayName("The built-in anchor provider signed (GDR-STORE-18)")
    class Signed {

        private static final String COMMIT_SOURCE = "commit-keys";
        private static final String ANCHOR_SOURCE = "anchor-keys";

        private final String commitReference = "ghp_" + Generators.letterStrings(24, 32).next();
        private final String anchorReference = "ghp_" + Generators.letterStrings(24, 32).next();

        /** A configuration selecting the provider signed. */
        private StoreConfiguration configuration(@Nullable String anchorKeySource, @Nullable String anchorKey,
                @Nullable String allowedSigners) {
            return new StoreConfiguration(EMPTY_REGISTRY, new Save(1), new Drafts(Period.ofDays(1)),
                    new Fetch(Duration.ofMinutes(1)), new Signing(COMMIT_SOURCE, commitReference, null),
                    new Anchor(SignedAnchorProvider.PROVIDER_ID, true, Anchor.DEFAULT_CHECKPOINT_INTERVAL,
                            Anchor.DEFAULT_RETRY_INTERVAL, new Anchor.Signed(anchorKeySource, anchorKey,
                                    allowedSigners, Anchor.Signed.DEFAULT_MAX_CLOCK_SKEW)),
                    new Directory(TestPersonDirectory.ID), new Commit(Commit.DEFAULT_EMAIL));
        }

        private StoreResult<StoreProviders> select(StoreConfiguration configuration, SigningKeySource... sources) {
            return StoreProviders.select(configuration, List.of(sources), List.of(new TestAnchorProvider(id())),
                    List.of(new TestPersonDirectory()));
        }

        @Test
        @DisplayName("GDR-STORE-18: anchor.provider signed builds the provider over the anchor key of anchor.signed.key-source")
        void selectsSigned() {
            var commitKey = TestKeys.ed25519();
            var anchorKey = TestKeys.ed25519();

            var providers = providers(select(configuration(ANCHOR_SOURCE, anchorReference, null),
                    new KeyedSource(COMMIT_SOURCE, commitKey, commitReference), new KeyedSource(ANCHOR_SOURCE, anchorKey, anchorReference)));

            var anchor = assertInstanceOf(SignedAnchorProvider.class, providers.anchor(), "the provider signed");
            byte[] proof = Objects.requireNonNull(anchor.obtain("alias", ObjectId.zeroId(), Instant.now(),
                    new byte[0]).value(), "a proof");
            var signature = assertInstanceOf(SshSignatures.DecodedSignature.class,
                    SshSignatures.decode(AnchorStatement.split(proof).orElseThrow().signature()), "the signature");
            assertAll(
                    () -> assertEquals(SignedAnchorProvider.PROVIDER_ID, anchor.id(), "the id"),
                    () -> assertArrayEquals(anchorKey.publicKeyBlob(), signature.publicKeyBlob(),
                            "signed with the anchor key"));
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "anchor provider signed");
            assertSecretAbsentFromLog(anchorReference);
            assertSecretAbsentFromLog(commitReference);
        }

        @Test
        @DisplayName("GDR-STORE-18: without anchor.signed.key-source the anchor key source is file")
        void defaultKeySource() {
            var providers = providers(select(configuration(null, anchorReference, null),
                    new KeyedSource(COMMIT_SOURCE, TestKeys.ed25519(), commitReference),
                    new KeyedSource(StoreConfiguration.Signing.DEFAULT_KEY_SOURCE, TestKeys.ed25519(),
                            anchorReference)));

            assertInstanceOf(SignedAnchorProvider.class, providers.anchor(), "the provider signed");
        }

        @Test
        @DisplayName("GDR-STORE-18: a missing anchor.signed.key is MALFORMED")
        void missingKey() {
            var result = select(configuration(ANCHOR_SOURCE, null, null),
                    new KeyedSource(COMMIT_SOURCE, TestKeys.ed25519(), commitReference),
                    new KeyedSource(ANCHOR_SOURCE, TestKeys.ed25519(), anchorReference));

            assertRefused("anchor.signed.key is missing", result);
        }

        @Test
        @DisplayName("GDR-STORE-18: an anchor key whose public key equals the commit-signing key stops the start")
        void equalPublicKeys() {
            var key = TestKeys.ed25519();

            var result = select(configuration(ANCHOR_SOURCE, anchorReference, null),
                    new KeyedSource(COMMIT_SOURCE, key, commitReference),
                    new KeyedSource(ANCHOR_SOURCE, key, anchorReference));

            assertRefused("anchor.signed.key equals the commit-signing key", result);
            assertSecretAbsent(anchorReference, result);
            assertSecretAbsent(commitReference, result);
        }

        @Test
        @DisplayName("GDR-STORE-18: a candidate anchor provider with the id signed is a duplicate")
        void duplicateSigned() {
            var result = StoreProviders.select(configuration(ANCHOR_SOURCE, anchorReference, null),
                    List.of(new KeyedSource(COMMIT_SOURCE, TestKeys.ed25519(), commitReference),
                            new KeyedSource(ANCHOR_SOURCE, TestKeys.ed25519(), anchorReference)),
                    List.of(new TestAnchorProvider(SignedAnchorProvider.PROVIDER_ID)), List.of(new TestPersonDirectory()));

            assertRefused("AnchorProvider: more than one provider with the id of anchor.provider", result);
        }

        @Test
        @DisplayName("GDR-STORE-18: an anchor key source no candidate carries is MALFORMED naming the key")
        void unknownKeySource() {
            var result = select(configuration(id(), anchorReference, null),
                    new KeyedSource(COMMIT_SOURCE, TestKeys.ed25519(), commitReference));

            assertRefused("SigningKeySource: no provider with the id of anchor.signed.key-source", result);
        }

        @Test
        @DisplayName("GDR-STORE-18: an anchor key source that hands out no public key stops the start with its outcome")
        void unreachableKeySource() {
            var result = select(configuration(ANCHOR_SOURCE, anchorReference, null),
                    new KeyedSource(COMMIT_SOURCE, TestKeys.ed25519(), commitReference), new UnreachableSource(ANCHOR_SOURCE));

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.UNAVAILABLE,
                            "anchor.signed.key: key source unavailable"), result, "unavailable"),
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                            GitStoreLogMessages.WARN.START_REFUSED.resolveIdentifierString()));
            assertSecretAbsent(UnreachableSource.SECRET, result);
        }

        @Test
        @DisplayName("GDR-STORE-18: an anchor.signed.allowed-signers that cannot be read stops the start without its path")
        void unreadableAllowedSigners(@TempDir Path directory) {
            var missing = directory.resolve(Generators.letterStrings(12, 20).next());

            var result = select(configuration(ANCHOR_SOURCE, anchorReference, missing.toString()),
                    new KeyedSource(COMMIT_SOURCE, TestKeys.ed25519(), commitReference),
                    new KeyedSource(ANCHOR_SOURCE, TestKeys.ed25519(), anchorReference));

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.UNAVAILABLE,
                            "anchor.signed.allowed-signers: not readable"), result, "unavailable"),
                    () -> assertFalse(String.valueOf(result.detail()).contains(missing.toString()), "no path"));
        }

        @Test
        @DisplayName("GDR-STORE-18: a readable anchor.signed.allowed-signers makes the provider verify its proofs")
        void verifiesWithAllowedSigners(@TempDir Path directory) throws Exception {
            var anchorKey = TestKeys.ed25519();
            var allowed = Files.writeString(directory.resolve("allowed"),
                    "anchor@example.invalid " + anchorKey.publicKeyLine() + "\n");
            var providers = providers(select(configuration(ANCHOR_SOURCE, anchorReference, allowed.toString()),
                    new KeyedSource(COMMIT_SOURCE, TestKeys.ed25519(), commitReference), new KeyedSource(ANCHOR_SOURCE, anchorKey, anchorReference)));
            var anchor = Objects.requireNonNull(providers.anchor());
            var subject = ObjectId.zeroId();

            byte[] proof = Objects.requireNonNull(anchor.obtain("alias", subject, Instant.now(), new byte[0]).value());

            assertTrue(anchor.verify(subject, proof, new byte[0]).isOk(), "the proof verifies");
        }
    }

    /** Asserts that no captured log record carries the text. */
    private static void assertSecretAbsentFromLog(String secret) {
        assertTrue(TestLoggerFactory.getTestHandler().getRecords().stream()
                .noneMatch(logRecord -> carries(logRecord, secret)), "no log record carries the secret");
    }

    /**
     * A signing key source under a chosen id over a generated key, which answers only for the key reference it expects,
     * so a store that asks it under another reference, for example the one of the other key, is refused.
     */
    private record KeyedSource(String id, TestKeys.Ed25519 key, String expectedReference) implements SigningKeySource {

        /** The detail of a request under a key reference the source does not expect. */
        static final String UNEXPECTED_REFERENCE = "keyed: unexpected key reference";

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            return expectedReference.equals(keyReference) ? StoreResult.ok(key.sign(data))
                    : StoreResult.of(StoreOutcome.REFUSED, UNEXPECTED_REFERENCE);
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            return expectedReference.equals(keyReference) ? StoreResult.ok(key.publicKeyLine())
                    : StoreResult.of(StoreOutcome.REFUSED, UNEXPECTED_REFERENCE);
        }
    }

    /** A signing key source that cannot be reached, with a secret in its detail. */
    private record UnreachableSource(String id) implements SigningKeySource {

        static final String SECRET = "ghp_unreachablesourcesecretvalue";

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, SECRET);
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, SECRET);
        }
    }

    @Nested
    @DisplayName("Loading through ServiceLoader")
    class Load {

        @Test
        @DisplayName("load finds the three test providers registered under META-INF/services")
        void loadsRegisteredProviders() {
            var providers = providers(StoreProviders.load(configuration(TestSigningKeySource.ID,
                    TestPersonDirectory.ID, TestAnchorProvider.ID)));

            assertAll(
                    () -> assertEquals(TestSigningKeySource.class, providers.signing().getClass(),
                            "the registered signing key source"),
                    () -> assertEquals(TestPersonDirectory.class, providers.persons().getClass(),
                            "the registered person directory"),
                    () -> assertEquals(TestAnchorProvider.class,
                            Objects.requireNonNull(providers.anchor()).getClass(), "the registered anchor provider"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    GitStoreLogMessages.INFO.PROVIDERS_SELECTED.resolveIdentifierString());
        }

        @Test
        @DisplayName("GDR-STORE-12: load finds the shipped signing key source file beside the test providers")
        void loadsShippedFileSource() {
            var providers = providers(StoreProviders.load(configuration(FileSigningKeySource.SOURCE_ID,
                    TestPersonDirectory.ID, null)));

            assertAll(
                    () -> assertEquals(FileSigningKeySource.class, providers.signing().getClass(),
                            "the shipped signing key source"),
                    () -> assertEquals(FileSigningKeySource.SOURCE_ID, providers.signing().id(), "its id"));
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO, "signing key source "
                    + FileSigningKeySource.SOURCE_ID + ", person directory " + TestPersonDirectory.ID);
        }

        @Test
        @DisplayName("load refuses a configured id no registered provider carries")
        void loadRefusesUnregisteredId() {
            var personsId = id();

            var result = StoreProviders.load(configuration(TestSigningKeySource.ID, personsId, null));

            assertRefused("PersonDirectory: no provider with the id of directory.persons.provider", result);
        }

        @Test
        @DisplayName("GDR-NFR-3: a provider failing with a credential in its message is MALFORMED, the message is not logged")
        void loadRefusesFailingProviderWithoutItsMessage(@TempDir Path services) throws Exception {
            var secret = secret();
            FailingSigningKeySource.failure = "password=" + secret;
            var resource = "META-INF/services/" + SigningKeySource.class.getName();
            var file = Files.writeString(services.resolve("services"), FailingSigningKeySource.class.getName() + "\n");
            var configuration = configuration(TestSigningKeySource.ID, TestPersonDirectory.ID, null);
            var thread = Thread.currentThread();
            var original = thread.getContextClassLoader();
            thread.setContextClassLoader(new ServicesOverride(original, resource, file.toUri().toURL()));
            StoreResult<StoreProviders> result;
            try {
                result = StoreProviders.load(configuration);
            } finally {
                thread.setContextClassLoader(original);
            }

            assertRefused("SigningKeySource: a provider cannot be loaded", result);
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG,
                    SigningKeySource.class.getName() + " failed with " + ServiceConfigurationError.class.getName());
            assertSecretAbsent(secret, result);
        }
    }

    @Nested
    @DisplayName("No configured id in a detail or a log record (GDR-NFR-3)")
    class NoConfiguredId {

        @Test
        @DisplayName("GDR-NFR-3: a secret-looking configured id is neither in the detail nor logged")
        void secretLookingIdIsAbsent() {
            var secret = secret();
            var personsId = id();

            var result = StoreProviders.select(configuration(secret, personsId, null),
                    List.of(new TestSigningKeySource(id())), List.of(), List.of(new TestPersonDirectory(personsId)));

            assertRefused("SigningKeySource: no provider with the id of signing.key-source", result);
            assertSecretAbsent(secret, result);
        }

        @Test
        @DisplayName("GDR-NFR-3: a secret-looking configured id carried twice is neither in the detail nor logged")
        void secretLookingDuplicateIdIsAbsent() {
            var secret = secret();
            var signingId = id();
            var personsId = id();

            var result = StoreProviders.select(configuration(signingId, personsId, secret),
                    List.of(new TestSigningKeySource(signingId)),
                    List.of(new TestAnchorProvider(secret), new TestAnchorProvider(secret)),
                    List.of(new TestPersonDirectory(personsId)));

            assertRefused("AnchorProvider: more than one provider with the id of anchor.provider", result);
            assertSecretAbsent(secret, result);
        }
    }

    /** A secret-looking value: a generated token behind a recognisable prefix. */
    private static String secret() {
        return "ghp_" + Generators.letterStrings(24, 32).next();
    }

    /** Asserts that neither the detail nor any captured log record, its message or its throwable, carries the text. */
    private static void assertSecretAbsent(String secret, StoreResult<?> result) {
        var detail = Objects.requireNonNull(result.detail());
        var records = TestLoggerFactory.getTestHandler().getRecords();
        assertAll(
                () -> assertFalse(detail.contains(secret), "the detail carries no secret"),
                () -> assertFalse(records.isEmpty(), "the refusal is logged"),
                () -> assertTrue(records.stream().noneMatch(logRecord -> carries(logRecord, secret)),
                        "no log record carries the secret"));
    }

    private static boolean carries(LogRecord logRecord, String secret) {
        if (String.valueOf(logRecord.getMessage()).contains(secret)) {
            return true;
        }
        for (Throwable thrown = logRecord.getThrown(); thrown != null; thrown = thrown.getCause()) {
            if (String.valueOf(thrown.getMessage()).contains(secret)) {
                return true;
            }
        }
        return false;
    }

    /** A class loader that answers one services resource with a file of the test and delegates everything else. */
    private static final class ServicesOverride extends ClassLoader {

        private final String resource;
        private final URL file;

        ServicesOverride(ClassLoader parent, String resource, URL file) {
            super(parent);
            this.resource = resource;
            this.file = file;
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            return resource.equals(name) ? Collections.enumeration(List.of(file)) : super.getResources(name);
        }
    }
}
