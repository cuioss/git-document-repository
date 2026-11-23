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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.logging.LogRecord;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.domain.FullNameGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The secrets of GDR-NFR-3 on the write path, with every log record captured down to trace: over drafts, the listing,
 * a save, a conflict, rejections and the expiry, no log record, result detail, rendering ({@code toString()}),
 * exception message or commit message carries the password or the user name of the technical user's credentials, an
 * e-mail address, or a person name that travels in the document content. With the default {@code commit.email}
 * {@code false} the e-mail fields of author and committer of every written commit stay empty although addresses are
 * supplied; with {@code commit.email} {@code true} the supplied addresses appear in those fields and nowhere else. No
 * log record, result detail, commit message or further commit header carries the key path of {@code signing.key}, the
 * key bytes or the detail of a refusing or unavailable signing key source, and no log record on the signing path
 * carries a throwable (GDR-STORE-12). Over the anchoring, with an unavailable, a throwing and then an answering
 * provider, a failed note push and a failed fetch, no log record or result of a due check carries a credential, an
 * address, a person name, the key path or key bytes, a document id or a reference that carries one, or the detail of
 * the provider, and none carries a throwable (GDR-STORE-15 to GDR-STORE-17).
 * <p>
 * Credentials, addresses, the person name, pseudonyms, paths and contents are generated; the configuration keys are the
 * specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("Write path secrets (GDR-NFR-3)")
class WritePathSecretsTest {

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 6, 16).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-", 8, 24).next();
    }

    private static String address() {
        return word() + "@" + word() + ".example";
    }

    /** A secret long and random enough to appear nowhere by chance. */
    private static String secret() {
        return Generators.letterStrings(24, 40).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static DocumentKey key() {
        var collection = value(DocumentCollection.of("c" + word(), Kind.PER_DOCUMENT));
        return value(DocumentKey.of(collection, Ulid.next()));
    }

    private static StoreConfiguration configuration(@Nullable Boolean email, String keyReference) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", Generators.integers(1, 10).next()));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", keyReference));
        if (email != null) {
            tree.put("commit", Map.of("email", email));
        }
        return value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
    }

    private static String blob(String content) {
        try (ObjectInserter.Formatter formatter = new ObjectInserter.Formatter()) {
            return formatter.idFor(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8)).name();
        }
    }

    private static List<TreeEdit> put(String path, String content) {
        return List.of(new TreeEdit.Put(path, content.getBytes(StandardCharsets.UTF_8)));
    }

    /** Writes a commit holding one file onto a reference of the remote. */
    private static void commit(Repository repository, String ref, String path, String content) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(path, FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(pseudonym(), "");
            commit.setTreeId(inserter.insert(tree));
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(ref);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            var result = update.update();
            assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED,
                    () -> "the fixture reference is written: " + result);
        }
    }

    /** Every text a captured log record carries: message, parameters and the chain of its throwable. */
    private static List<String> logTexts() {
        List<String> texts = new ArrayList<>();
        for (LogRecord logRecord : TestLoggerFactory.getTestHandler().getRecords()) {
            texts.add(String.valueOf(logRecord.getMessage()));
            Object[] parameters = logRecord.getParameters();
            if (parameters != null) {
                for (Object parameter : parameters) {
                    texts.add(String.valueOf(parameter));
                }
            }
            for (Throwable thrown = logRecord.getThrown(); thrown != null; thrown = thrown.getCause()) {
                texts.add(String.valueOf(thrown));
            }
        }
        return texts;
    }

    /**
     * The secrets of a run and the commit identities it expects; the key reference is a key path, the public key the
     * base64 of the signing key's public key bytes.
     */
    private record Secrets(String userName, String password, String personName, String authorAddress,
    String otherAddress, String userAddress, String keyReference, String publicKey) {

        List<String> all() {
            return List.of(userName, password, personName, authorAddress, otherAddress, userAddress, keyReference,
                    publicKey);
        }
    }

    /** A key path long and random enough to appear nowhere by chance. */
    private static String keyPath() {
        return "/" + secret() + "/" + secret() + "/id_ed25519";
    }

    /** The claims of a save; the issuer carries no secret, the claims carry no {@code sub}, {@code sid} or address. */
    private static IdentityClaims claims() {
        return new IdentityClaims("https://issuer.example/realms/" + word(), Instant.parse("2026-03-01T08:15:30Z"),
                "urn:example:acr:mfa", List.of("pwd", "otp"));
    }

    /**
     * A signing key source that hands out the public key of a test key, or refuses or is unavailable with a detail that
     * carries a secret, for the public key or for the signature.
     */
    private static final class SecretDetailSource implements SigningKeySource {

        private final TestKeys.Source keys;
        private final StoreOutcome publicKeyOutcome;
        private final StoreOutcome signOutcome;
        private final String detail;

        SecretDetailSource(TestKeys.Source keys, StoreOutcome publicKeyOutcome, StoreOutcome signOutcome,
                String detail) {
            this.keys = keys;
            this.publicKeyOutcome = publicKeyOutcome;
            this.signOutcome = signOutcome;
            this.detail = detail;
        }

        @Override
        public String id() {
            return "secret-detail";
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            return signOutcome == StoreOutcome.OK ? keys.sign(keyReference, data)
                    : StoreResult.of(signOutcome, detail + " " + keyReference);
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            return publicKeyOutcome == StoreOutcome.OK ? keys.publicKey(keyReference)
                    : StoreResult.of(publicKeyOutcome, detail + " " + keyReference);
        }
    }

    /**
     * What a run produced: every text the write path rendered, the commits it wrote, and the author address, the
     * author name per commit and the committer name it expects.
     */
    private record Run(List<String> texts, List<RevCommit> commits, Map<ObjectId, String> authorAddresses,
    Map<ObjectId, String> authorNames, String committerName) {
    }

    /**
     * Runs drafts, the listing, a save, a conflict, rejections and the expiry, and collects every result detail and
     * rendering, every exception message and every commit written.
     */
    private Run run(LocalRemote remote, @Nullable Boolean email, Secrets secrets, SigningKeySource signing)
            throws IOException {
        var key = key();
        var target = StoreReferences.target(key);
        var path = word();
        var loaded = word() + " " + secrets.personName() + " " + word();
        commit(remote.repository(), target, path, loaded);
        var user = new TechnicalUser(word(), secrets.userAddress(),
                new UsernamePasswordCredentialsProvider(secrets.userName(), secrets.password()));
        var author = new Author(pseudonym(), secrets.authorAddress());
        var other = new Author(pseudonym(), secrets.otherAddress());
        var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
        var gitDir = temp.resolve(word() + ".git");
        var now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
        List<Object> rendered = new ArrayList<>(List.of(user, author, other));
        Map<ObjectId, String> authorAddresses = new LinkedHashMap<>();
        Map<ObjectId, String> authorNames = new LinkedHashMap<>();
        List<ObjectId> written = new ArrayList<>();

        var claims = claims();

        try (var writer = value(StoreWriter.open(storeRepository, gitDir, user,
                     configuration(email, secrets.keyReference()), signing,
                     new JGitRepositoryOperations(RemotePolicy.localOnly()), Clock.fixed(now, ZoneOffset.UTC)))) {
            var draft = writer.draft(author, key, put(path, secrets.personName() + " " + word()), word());
            rendered.add(draft);
            written.add(ObjectId.fromString(value(draft)));
            authorAddresses.put(written.getLast(), secrets.authorAddress());
            authorNames.put(written.getLast(), author.pseudonym());
            rendered.add(writer.drafts(author.pseudonym()));
            var saved = writer.save(author, claims, key, Map.of(path, blob(loaded)), word());
            rendered.add(saved);
            written.add(ObjectId.fromString(assertInstanceOf(SaveResult.Saved.class, saved).commitId()));
            authorAddresses.put(written.getLast(), secrets.authorAddress());
            authorNames.put(written.getLast(), author.pseudonym());

            var otherDraft = writer.draft(other, key, put(path, word() + " " + secrets.personName()), word());
            rendered.add(otherDraft);
            written.add(ObjectId.fromString(value(otherDraft)));
            authorAddresses.put(written.getLast(), secrets.otherAddress());
            authorNames.put(written.getLast(), other.pseudonym());
            var conflict = writer.save(other, claims, key, Map.of(path, blob(loaded)), word());
            rendered.add(assertInstanceOf(SaveResult.Conflicting.class, conflict));
            rendered.add(conflict.toResult());

            var missing = writer.save(new Author(pseudonym(), address()), claims, key, Map.of(), word());
            assertEquals(new StoreResult.PassedOn(GitOutcome.NOT_FOUND), missing.outcome(), "a missing draft");
            rendered.add(missing);
            var malformed = writer.draft(new Author(word() + "/" + word(), secrets.authorAddress()), key,
                    put(path, secrets.personName()), word());
            assertEquals(StoreOutcome.MALFORMED, malformed.outcome(), "an invalid pseudonym");
            rendered.add(malformed);
            rendered.add(writer.save(new Author(word() + "/" + word(), secrets.authorAddress()), claims, key,
                    Map.of(), word()));

            List<RevCommit> commits = new ArrayList<>();
            try (Repository clone = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build();
                 RevWalk walk = new RevWalk(clone)) {
                for (ObjectId id : written) {
                    RevCommit commit = walk.parseCommit(id);
                    walk.parseBody(commit);
                    commits.add(commit);
                }
            }

            var expired = writer.expireDrafts(now.atOffset(ZoneOffset.UTC).plusYears(1).toInstant());
            assertEquals(List.of(value(StoreReferences.draft(other.pseudonym(), key))), value(expired),
                    "the remaining draft expires");
            rendered.add(expired);

            List<String> texts = new ArrayList<>();
            for (Object object : rendered) {
                texts.add(String.valueOf(object));
                if (object instanceof StoreResult<?> result && result.detail() != null) {
                    texts.add(result.detail());
                }
                if (object instanceof SaveResult result) {
                    result.detail().ifPresent(texts::add);
                }
            }
            var refusedPseudonym = pseudonym();
            var refusedAuthorAddress = secrets.authorAddress() + "<";
            texts.add(assertThrows(IllegalArgumentException.class,
                    () -> new Author(refusedPseudonym, refusedAuthorAddress)).getMessage());
            var refusedName = word();
            var refusedUserAddress = secrets.userAddress() + "\n";
            var refusedCredentials = new UsernamePasswordCredentialsProvider(secrets.userName(), secrets.password());
            texts.add(assertThrows(IllegalArgumentException.class,
                    () -> new TechnicalUser(refusedName, refusedUserAddress, refusedCredentials)).getMessage());
            for (RevCommit commit : commits) {
                texts.add(commit.getFullMessage());
                texts.add(headers(commit));
            }
            texts.addAll(logTexts());
            return new Run(texts, commits, authorAddresses, authorNames, user.name());
        }
    }

    private static Secrets secrets(TestKeys.Source signing) {
        return new Secrets("u" + secret(), secret(), new FullNameGenerator().next(), address(), address(), address(),
                keyPath(), Base64.getEncoder().encodeToString(signing.key().publicKey()));
    }

    /**
     * The header lines of a commit other than its author and committer, whose e-mail fields a test checks separately,
     * and its {@code gpgsig} header, which carries the signature: a claim or a key reference in any further header
     * would surface here.
     */
    private static String headers(RevCommit commit) {
        String raw = new String(commit.getRawBuffer(), StandardCharsets.ISO_8859_1);
        int end = raw.indexOf("\n\n");
        var kept = new StringBuilder();
        boolean signature = false;
        for (String line : (end < 0 ? raw : raw.substring(0, end)).split("\n")) {
            if (line.startsWith(" ")) {
                if (!signature) {
                    kept.append(line).append('\n');
                }
                continue;
            }
            signature = line.startsWith("gpgsig ");
            if (!signature && !line.startsWith("author ") && !line.startsWith("committer ")) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    /** The captured log records from the given index on. */
    private static List<LogRecord> recordsFrom(int index) {
        List<LogRecord> records = TestLoggerFactory.getTestHandler().getRecords();
        return List.copyOf(records.subList(index, records.size()));
    }

    private static void assertNoSecret(Run run, Secrets secrets) {
        for (String secret : secrets.all()) {
            for (String text : run.texts()) {
                assertFalse(text.contains(secret), () -> "a rendered text carries a secret: " + text);
            }
        }
    }

    @Test
    @DisplayName("GDR-NFR-3: by default no secret is rendered and every commit has empty e-mail fields")
    void defaultCarriesNoSecretAndNoAddress(@Remote LocalRemote remote) throws Exception {
        var signing = TestKeys.ed25519().source();
        var secrets = secrets(signing);

        var run = run(remote, null, secrets, signing);

        assertNoSecret(run, secrets);
        assertAll(
                () -> assertEquals(3, run.commits().size(), "two drafts and a save were written"),
                () -> assertEquals(1, signing.signCalls(), "the save commit was signed"),
                () -> assertFalse(TestLoggerFactory.getTestHandler().getRecords().isEmpty(), "log records captured"),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                        GitStoreLogMessages.INFO.SAVED.resolveIdentifierString()),
                () -> assertTrue(run.commits().stream().allMatch(commit ->
                                commit.getAuthorIdent().getEmailAddress().isEmpty()
                                        && commit.getCommitterIdent().getEmailAddress().isEmpty()),
                        "author and committer of every commit carry an empty e-mail"));
        assertExactNames(run);
    }

    /** Asserts that every commit names exactly the author's pseudonym and the technical user. */
    private static void assertExactNames(Run run) {
        for (RevCommit commit : run.commits()) {
            assertAll(
                    () -> assertEquals(run.authorNames().get(commit.getId()), commit.getAuthorIdent().getName(),
                            "the author name is the pseudonym"),
                    () -> assertEquals(run.committerName(), commit.getCommitterIdent().getName(),
                            "the committer name is the technical user"));
        }
    }

    @Test
    @DisplayName("GDR-NFR-3: with commit.email true the addresses appear only in the e-mail fields of the commits")
    void withEmailAddressesOnlyInEmailFields(@Remote LocalRemote remote) throws Exception {
        var signing = TestKeys.ed25519().source();
        var secrets = secrets(signing);

        var run = run(remote, true, secrets, signing);

        assertNoSecret(run, secrets);
        for (RevCommit commit : run.commits()) {
            var author = commit.getAuthorIdent();
            var committer = commit.getCommitterIdent();
            assertAll(
                    () -> assertEquals(run.authorAddresses().get(commit.getId()), author.getEmailAddress(),
                            "the author address"),
                    () -> assertEquals(secrets.userAddress(), committer.getEmailAddress(), "the committer address"),
                    () -> assertTrue(secrets.all().stream().noneMatch(secret -> author.getName().contains(secret)
                            || committer.getName().contains(secret)), "no secret in the names"));
        }
        assertExactNames(run);
    }

    @Test
    @DisplayName("GDR-NFR-3: a refusing or unavailable signing key source leaks no key path, key byte or its detail")
    void signingFailureCarriesNoSecretAndNoThrowable(@Remote LocalRemote remote) throws Exception {
        var key = key();
        var target = StoreReferences.target(key);
        var path = word();
        commit(remote.repository(), target, path, word());
        var tipBefore = Objects.requireNonNull(remote.repository().exactRef(target), "the target").getObjectId();
        var keys = TestKeys.ed25519().source();
        var keyReference = keyPath();
        var sourceDetail = secret();
        var authorAddress = address();
        List<String> secrets = List.of(keyReference, sourceDetail, authorAddress,
                Base64.getEncoder().encodeToString(keys.key().publicKey()));
        List<SigningKeySource> sources = List.of(
                new SecretDetailSource(keys, StoreOutcome.REFUSED, StoreOutcome.OK, sourceDetail),
                new SecretDetailSource(keys, StoreOutcome.UNAVAILABLE, StoreOutcome.OK, sourceDetail),
                new SecretDetailSource(keys, StoreOutcome.OK, StoreOutcome.REFUSED, sourceDetail),
                new SecretDetailSource(keys, StoreOutcome.OK, StoreOutcome.UNAVAILABLE, sourceDetail));
        int firstRecord = TestLoggerFactory.getTestHandler().getRecords().size();
        List<String> texts = new ArrayList<>();
        var user = new TechnicalUser(word(), null, new UsernamePasswordCredentialsProvider(word(), word()));
        var now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());

        for (SigningKeySource source : sources) {
            var storeRepository = new StoreRepository(word(), remote.uri().toString(), word(), null);
            try (var writer = value(StoreWriter.open(storeRepository, temp.resolve(word() + ".git"), user,
                         configuration(null, keyReference), source, new JGitRepositoryOperations(RemotePolicy.localOnly()),
                         Clock.fixed(now, ZoneOffset.UTC)))) {
                var author = new Author(pseudonym(), authorAddress);
                value(writer.draft(author, key, put(word() + "x", word()), word()));
                var saved = writer.save(author, claims(), key, Map.of(), word());
                var released = writer.release(author, claims(), key);
                assertAll(
                        () -> assertInstanceOf(SaveResult.NotSaved.class, saved, "the save is not saved"),
                        () -> assertTrue(saved.outcome() == StoreOutcome.REFUSED
                                || saved.outcome() == StoreOutcome.UNAVAILABLE, "the outcome of the source"),
                        () -> assertTrue(released.outcome() == StoreOutcome.REFUSED
                                || released.outcome() == StoreOutcome.UNAVAILABLE, "the outcome of the source"));
                texts.add(String.valueOf(saved));
                saved.detail().ifPresent(texts::add);
                texts.add(String.valueOf(released));
                texts.add(String.valueOf(released.detail()));
                texts.add(String.valueOf(source));
            }
        }

        var records = recordsFrom(firstRecord);
        for (LogRecord logRecord : records) {
            texts.add(String.valueOf(logRecord.getMessage()));
            Object[] parameters = logRecord.getParameters();
            if (parameters != null) {
                for (Object parameter : parameters) {
                    texts.add(String.valueOf(parameter));
                }
            }
        }
        for (String secret : secrets) {
            for (String text : texts) {
                assertFalse(text.contains(secret), () -> "a rendered text carries a secret: " + text);
            }
        }
        assertAll(
                () -> assertTrue(records.stream().noneMatch(logRecord -> logRecord.getThrown() != null),
                        "no log record on the signing path carries a throwable"),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                        GitStoreLogMessages.WARN.SIGNING_FAILED.resolveIdentifierString()),
                () -> assertEquals(tipBefore, Objects.requireNonNull(remote.repository().exactRef(target))
                        .getObjectId(), "nothing was pushed"),
                () -> assertNull(remote.repository().exactRef(StoreReferences.MAIN), "no release on main"));
    }

    /**
     * An anchor provider whose first answer is unavailable with a detail that carries a secret, whose second throws
     * with a message that carries it, and which then obtains a proof.
     */
    private static final class SecretDetailProvider implements AnchorProvider {

        private final String id = "a" + word();
        private final String detail;
        private int calls;

        SecretDetailProvider(String detail) {
            this.detail = detail;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public synchronized StoreResult<byte[]> obtain(String repository, ObjectId subject, Instant commitTime,
                byte[] previous) {
            calls++;
            if (calls == 1) {
                return StoreResult.of(StoreOutcome.UNAVAILABLE, detail + " " + repository);
            }
            if (calls == 2) {
                throw new IllegalStateException(detail);
            }
            return StoreResult.ok(("proof " + subject.name()).getBytes(StandardCharsets.US_ASCII));
        }

        @Override
        public StoreResult<Instant> verify(ObjectId subject, byte[] proof, byte[] previous) {
            return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, detail);
        }
    }

    @Test
    @DisplayName("GDR-NFR-3: the anchoring leaks no credential, address, person name, key, document id or provider detail")
    void anchoringCarriesNoSecretAndNoThrowable(@Remote LocalRemote remote) throws Exception {
        var key = key();
        var target = StoreReferences.target(key);
        var path = word();
        var personName = new FullNameGenerator().next();
        commit(remote.repository(), target, path, word() + " " + personName);
        var signing = TestKeys.ed25519().source();
        var keyReference = keyPath();
        var userName = "u" + secret();
        var password = secret();
        var userAddress = address();
        var authorAddress = address();
        var providerDetail = secret();
        var provider = new SecretDetailProvider(providerDetail);
        var user = new TechnicalUser(word(), userAddress, new UsernamePasswordCredentialsProvider(userName, password));
        var operations = new ScriptedRepositoryOperations();
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
        tree.put("signing", Map.of("key", keyReference));
        tree.put("anchor", Map.of("provider", provider.id()));
        var configuration = value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
        var now = Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
        List<String> texts = new ArrayList<>();
        int firstRecord;
        String releaseTag;

        try (var writer = value(StoreWriter.open(new StoreRepository(word(), remote.uri().toString(), word(), null),
                     temp.resolve(word() + ".git"), user, configuration,
                     new StoreProviders(signing, new TestPersonDirectory(), provider), operations,
                     Clock.fixed(now, ZoneOffset.UTC)))) {
            var author = new Author(pseudonym(), authorAddress);
            value(writer.draft(author, key, put(path + "x", personName + " " + word()), word()));
            assertInstanceOf(SaveResult.Saved.class, writer.save(author, claims(), key, Map.of(), word()), "saved");
            releaseTag = value(writer.release(author, claims(), key)).tag();
            firstRecord = TestLoggerFactory.getTestHandler().getRecords().size();

            List<StoreResult<StoreWriter.Anchoring>> results = new ArrayList<>();
            results.add(writer.anchor());
            results.add(writer.anchor());
            operations.failPushTo(StoreReferences.anchorNotes(provider.id()), GitOutcome.UNREACHABLE);
            results.add(writer.anchor());
            operations.failFetch(GitOutcome.UNREACHABLE);
            results.add(writer.anchor());
            results.add(writer.anchor());

            assertAll(
                    () -> assertEquals(2, value(results.getFirst()).pending().size(),
                            "the release commit and the checkpoint are pending"),
                    () -> assertTrue(value(results.getFirst()).anchored().isEmpty(), "unavailable: nothing anchored"),
                    () -> assertTrue(value(results.get(1)).anchored().isEmpty(), "throwing: nothing anchored"),
                    () -> assertTrue(value(results.get(2)).anchored().isEmpty(), "unpushed: nothing anchored"),
                    () -> assertFalse(results.get(3).isOk(), "the failed fetch ends the due check"),
                    () -> assertEquals(List.of(), value(results.get(4)).pending(), "finally everything anchored"));
            for (StoreResult<StoreWriter.Anchoring> result : results) {
                texts.add(String.valueOf(result));
                texts.add(String.valueOf(result.detail()));
            }
        }

        var records = recordsFrom(firstRecord);
        for (LogRecord logRecord : records) {
            texts.add(String.valueOf(logRecord.getMessage()));
            Object[] parameters = logRecord.getParameters();
            if (parameters != null) {
                for (Object parameter : parameters) {
                    texts.add(String.valueOf(parameter));
                }
            }
        }
        List<String> secrets = List.of(userName, password, userAddress, authorAddress, personName, keyReference,
                Base64.getEncoder().encodeToString(signing.key().publicKey()), providerDetail, key.id(), releaseTag,
                target);
        for (String secret : secrets) {
            for (String text : texts) {
                assertFalse(text.contains(secret), () -> "a rendered text carries a secret: " + text);
            }
        }
        assertAll(
                () -> assertTrue(records.stream().noneMatch(logRecord -> logRecord.getThrown() != null),
                        "no log record of the anchoring carries a throwable"),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                        GitStoreLogMessages.INFO.CHECKPOINT_CREATED.resolveIdentifierString()),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                        GitStoreLogMessages.INFO.SUBJECT_ANCHORED.resolveIdentifierString()),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                        GitStoreLogMessages.WARN.ANCHORING_PENDING.resolveIdentifierString()),
                () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                        GitStoreLogMessages.WARN.ANCHOR_PUSH_INCOMPLETE.resolveIdentifierString()));
    }
}
