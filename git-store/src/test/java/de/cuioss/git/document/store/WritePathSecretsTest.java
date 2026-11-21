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
 * supplied; with {@code commit.email} {@code true} the supplied addresses appear in those fields and nowhere else.
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

    private static StoreConfiguration configuration(@Nullable Boolean email) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", Generators.integers(1, 10).next()));
        tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
        tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
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

    /** The secrets of a run and the commit identities it expects. */
    private record Secrets(String userName, String password, String personName, String authorAddress,
    String otherAddress, String userAddress) {

        List<String> all() {
            return List.of(userName, password, personName, authorAddress, otherAddress, userAddress);
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
    private Run run(LocalRemote remote, @Nullable Boolean email, Secrets secrets) throws IOException {
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

        try (var writer = value(StoreWriter.open(storeRepository, gitDir, user, configuration(email),
                     new JGitRepositoryOperations(RemotePolicy.localOnly()), Clock.fixed(now, ZoneOffset.UTC)))) {
            var draft = writer.draft(author, key, put(path, secrets.personName() + " " + word()), word());
            rendered.add(draft);
            written.add(ObjectId.fromString(value(draft)));
            authorAddresses.put(written.getLast(), secrets.authorAddress());
            authorNames.put(written.getLast(), author.pseudonym());
            rendered.add(writer.drafts(author.pseudonym()));
            var saved = writer.save(author, key, Map.of(path, blob(loaded)), word());
            rendered.add(saved);
            written.add(ObjectId.fromString(assertInstanceOf(SaveResult.Saved.class, saved).commitId()));
            authorAddresses.put(written.getLast(), secrets.authorAddress());
            authorNames.put(written.getLast(), author.pseudonym());

            var otherDraft = writer.draft(other, key, put(path, word() + " " + secrets.personName()), word());
            rendered.add(otherDraft);
            written.add(ObjectId.fromString(value(otherDraft)));
            authorAddresses.put(written.getLast(), secrets.otherAddress());
            authorNames.put(written.getLast(), other.pseudonym());
            var conflict = writer.save(other, key, Map.of(path, blob(loaded)), word());
            rendered.add(assertInstanceOf(SaveResult.Conflicting.class, conflict));
            rendered.add(conflict.toResult());

            var missing = writer.save(new Author(pseudonym(), address()), key, Map.of(), word());
            assertEquals(new StoreResult.PassedOn(GitOutcome.NOT_FOUND), missing.outcome(), "a missing draft");
            rendered.add(missing);
            var malformed = writer.draft(new Author(word() + "/" + word(), secrets.authorAddress()), key,
                    put(path, secrets.personName()), word());
            assertEquals(StoreOutcome.MALFORMED, malformed.outcome(), "an invalid pseudonym");
            rendered.add(malformed);
            rendered.add(writer.save(new Author(word() + "/" + word(), secrets.authorAddress()), key, Map.of(),
                    word()));

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
            }
            texts.addAll(logTexts());
            return new Run(texts, commits, authorAddresses, authorNames, user.name());
        }
    }

    private static Secrets secrets() {
        return new Secrets("u" + secret(), secret(), new FullNameGenerator().next(), address(), address(), address());
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
        var secrets = secrets();

        var run = run(remote, null, secrets);

        assertNoSecret(run, secrets);
        assertAll(
                () -> assertEquals(3, run.commits().size(), "two drafts and a save were written"),
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
        var secrets = secrets();

        var run = run(remote, true, secrets);

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
}
