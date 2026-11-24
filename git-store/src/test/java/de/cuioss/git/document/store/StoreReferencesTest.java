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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.store.DocumentCollection.Kind;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The reference layout of GDR-STORE-2: the target branch of each collection kind, the draft reference of a user, the
 * refusal of invalid and reserved collection names, of invalid ids and pseudonyms and of a name declared twice, the push
 * reference specifications without {@code refs/drafts/*}, and the JGit 7.8.0 fact behind "per-document or numbered,
 * never both": {@code RefUpdate.updateImpl} answers a new reference whose name conflicts with an existing one
 * ({@code RefDatabase.isNameConflicting}) with {@code LOCK_FAILURE}.
 * <p>
 * Names, ids and pseudonyms are generated; the reference prefixes, the reserved names and the details are the
 * specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@DisplayName("StoreReferences (GDR-STORE-2)")
class StoreReferencesTest {

    private static String name() {
        return "c" + Generators.strings("abcdefghijklmnopqrstuvwxyz0123456789-", 3, 12).next();
    }

    private static String readableId() {
        return "R-" + Generators.integers(1, 999).next();
    }

    private static String pseudonym() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", 8, 24).next();
    }

    private static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value(), "a success carries its value");
    }

    private static DocumentKey key(Kind kind, String id) {
        return value(DocumentKey.of(value(DocumentCollection.of(name(), kind)), id));
    }

    @Nested
    @DisplayName("Reference forms")
    class Forms {

        @Test
        @DisplayName("GDR-STORE-2: a document of a per-document collection has the working branch refs/heads/<c>/<id>")
        void perDocumentTarget() {
            var key = key(Kind.PER_DOCUMENT, Ulid.next());

            assertEquals("refs/heads/" + key.collection().name() + "/" + key.id(), StoreReferences.target(key),
                    "the working branch of the document");
        }

        @Test
        @DisplayName("GDR-STORE-2: an entry of a numbered collection has the collection branch refs/heads/<c>")
        void numberedTarget() {
            var key = key(Kind.NUMBERED, readableId());

            assertEquals("refs/heads/" + key.collection().name(), StoreReferences.target(key),
                    "the collection branch");
        }

        @Test
        @DisplayName("GDR-STORE-2: content without release is saved onto refs/heads/main")
        void mainTarget() {
            var key = key(Kind.MAIN, readableId());

            assertAll(
                    () -> assertEquals("refs/heads/main", StoreReferences.target(key), "main itself"),
                    () -> assertEquals("refs/heads/main", StoreReferences.MAIN, "the constant MAIN"));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("GDR-STORE-2: a draft is refs/drafts/<pseudonym>/<collection>/<id> for every kind")
        void draft(Kind kind) {
            var key = key(kind, Ulid.next());
            var pseudonym = pseudonym();

            assertEquals("refs/drafts/" + pseudonym + "/" + key.collection().name() + "/" + key.id(),
                    value(StoreReferences.draft(pseudonym, key)), "the draft reference");
        }

        @Test
        @DisplayName("GDR-STORE-2: a new numbered entry may use a ULID for its draft until its first save")
        void numberedEntryWithUlid() {
            var key = key(Kind.NUMBERED, Ulid.next());

            assertTrue(value(StoreReferences.draft(pseudonym(), key)).endsWith("/" + key.id()),
                    "the ULID is the last segment");
        }

        @Test
        @DisplayName("GDR-STORE-2: the push refspecs carry heads, tags and notes and never refs/drafts")
        void pushRefSpecs() {
            var specs = StoreReferences.PUSH_REF_SPECS;

            assertAll(
                    () -> assertEquals(List.of("refs/heads/*:refs/heads/*", "refs/tags/*:refs/tags/*",
                            "refs/notes/*:refs/notes/*"), specs, "heads, tags and notes"),
                    () -> assertTrue(specs.stream().noneMatch(spec -> spec.contains("refs/drafts")),
                            "no refs/drafts"),
                    () -> assertTrue(specs.stream().noneMatch(spec -> spec.startsWith(StoreReferences.DRAFTS)
                            || spec.contains(":" + StoreReferences.DRAFTS)), "the draft prefix is never a side"),
                    () -> assertEquals("refs/drafts/", StoreReferences.DRAFTS, "the draft prefix"));
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", "Risks", "a/b", "a.b", "a b", "a_b", "~x~"})
        @DisplayName("GDR-STORE-2: a collection name outside [a-z0-9-]+ is refused without naming it")
        void invalidCollectionName(String invalid) {
            var result = DocumentCollection.of(invalid, Kind.PER_DOCUMENT);

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "collection: invalid name"), result,
                            "MALFORMED"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new DocumentCollection(invalid, Kind.NUMBERED), "the constructor refuses it too"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"main", "checkpoints"})
        @DisplayName("GDR-STORE-2: a collection is never named main or checkpoints")
        void reservedCollectionName(String reserved) {
            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "collection: reserved name"),
                            DocumentCollection.of(reserved, Kind.NUMBERED), "MALFORMED"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new DocumentCollection(reserved, Kind.PER_DOCUMENT),
                            "the constructor refuses it too"));
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"R-042", "01arz3ndektsv4rrffq69g5fav", "01ARZ3NDEKTSV4RRFFQ69G5FA", "", "a/b"})
        @DisplayName("GDR-STORE-2: the id of a per-document collection must be a ULID")
        void perDocumentIdNotUlid(String id) {
            var collection = value(DocumentCollection.of(name(), Kind.PER_DOCUMENT));

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "id: invalid"),
                            DocumentKey.of(collection, id), "MALFORMED"),
                    () -> assertThrows(IllegalArgumentException.class, () -> new DocumentKey(collection, id),
                            "the constructor refuses it too"));
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", "R/042", "R.042", "R 042", "R_042", "~R~"})
        @DisplayName("GDR-STORE-2: the id of another collection must match [A-Za-z0-9-]+")
        void readableIdInvalid(String id) {
            var numbered = value(DocumentCollection.of(name(), Kind.NUMBERED));
            var main = value(DocumentCollection.of(name(), Kind.MAIN));

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "id: invalid"),
                            DocumentKey.of(numbered, id), "numbered"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "id: invalid"),
                            DocumentKey.of(main, id), "main"));
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", "a/b", "a.b", "a b", "a_b", "~x~", "a:b"})
        @DisplayName("GDR-STORE-2: a pseudonym outside [A-Za-z0-9-]+ is refused without naming it")
        void invalidPseudonym(String pseudonym) {
            var key = key(Kind.PER_DOCUMENT, Ulid.next());

            assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "pseudonym: invalid"),
                    StoreReferences.draft(pseudonym, key), "MALFORMED");
        }

        @Test
        @DisplayName("GDR-STORE-2: a name declared per-document and numbered is refused")
        void declaredTwice() {
            var name = name();
            var perDocument = value(DocumentCollection.of(name, Kind.PER_DOCUMENT));
            var numbered = value(DocumentCollection.of(name, Kind.NUMBERED));
            var other = value(DocumentCollection.of(name + "-x", Kind.NUMBERED));

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "collection " + name + ": declared twice"),
                            StoreReferences.collections(List.of(perDocument, other, numbered)), "both kinds"),
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "collection " + name + ": declared twice"),
                            StoreReferences.collections(List.of(perDocument, perDocument)), "the same twice"));
        }

        @Test
        @DisplayName("GDR-STORE-2: the writer refuses at open collections that declare one name twice, before a clone")
        void writerOpenRefusesDeclaredTwice() {
            // a digit cannot occur in the detail, so the name is absent from it
            var name = name() + "0";
            var declared = List.of(value(DocumentCollection.of(name, Kind.PER_DOCUMENT)),
                    value(DocumentCollection.of(name, Kind.NUMBERED)));
            Map<String, Object> tree = new LinkedHashMap<>();
            tree.put("save", Map.of("max-attempts", Generators.integers(1, 10).next()));
            tree.put("drafts", Map.of("expiry", Period.ofDays(Generators.integers(1, 90).next()).toString()));
            tree.put("fetch", Map.of("interval", Duration.ofMinutes(Generators.integers(1, 60).next()).toString()));
            tree.put("signing", Map.of("key", name()));
            var configuration = value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
            var operations = new ScriptedRepositoryOperations();

            var opened = StoreWriter.open(new StoreRepository(name(), "https://example.invalid/" + name() + ".git",
                            name(), null), Path.of(name() + ".git"),
                    new TechnicalUser(name(), null, new UsernamePasswordCredentialsProvider(name(), name())),
                    configuration, declared, TestKeys.ed25519().source(), operations, Clock.systemUTC());

            assertAll(
                    () -> assertEquals(StoreResult.of(StoreOutcome.MALFORMED, "collections: a name is declared twice"),
                            opened, "MALFORMED, naming the argument"),
                    () -> assertFalse(Objects.requireNonNull(opened.detail()).contains(name),
                            "the detail never names the collection"),
                    () -> assertEquals(List.of(), operations.credentials(), "nothing was cloned or fetched"));
        }

        @Test
        @DisplayName("GDR-STORE-2: distinct collections are accepted, in declaration order, as an unmodifiable copy")
        void distinctCollections() {
            var declared = new ArrayList<>(List.of(value(DocumentCollection.of(name() + "-a", Kind.PER_DOCUMENT)),
                    value(DocumentCollection.of(name() + "-b", Kind.NUMBERED)),
                    value(DocumentCollection.of(name() + "-c", Kind.MAIN))));

            var accepted = value(StoreReferences.collections(declared));
            declared.clear();

            assertAll(
                    () -> assertEquals(3, accepted.size(), "every collection is kept"),
                    () -> assertThrows(UnsupportedOperationException.class, accepted::clear, "unmodifiable"));
        }
    }

    @Nested
    @DisplayName("JGit refuses conflicting reference names (JGit 7.8.0)")
    class NameConflict {

        private ObjectId commit(Repository repository) throws IOException {
            try (ObjectInserter inserter = repository.newObjectInserter()) {
                var tree = inserter.insert(new TreeFormatter());
                var commit = new CommitBuilder();
                var ident = new PersonIdent(pseudonym(), "");
                commit.setTreeId(tree);
                commit.setAuthor(ident);
                commit.setCommitter(ident);
                commit.setMessage(pseudonym());
                var id = inserter.insert(commit);
                inserter.flush();
                return id;
            }
        }

        private RefUpdate.Result create(Repository repository, String name, ObjectId id) throws IOException {
            RefUpdate update = repository.updateRef(name);
            update.setNewObjectId(id);
            update.setExpectedOldObjectId(ObjectId.zeroId());
            return update.update();
        }

        @Test
        @DisplayName("GDR-STORE-2: refs/heads/<c> cannot be created beside refs/heads/<c>/<id>")
        void collectionBranchBesideWorkingBranch(@InMemory Repository repository) throws Exception {
            var id = commit(repository);
            var perDocument = key(Kind.PER_DOCUMENT, Ulid.next());
            var numbered = value(DocumentKey.of(new DocumentCollection(perDocument.collection().name(), Kind.NUMBERED),
                    readableId()));

            var working = create(repository, StoreReferences.target(perDocument), id);
            var collection = create(repository, StoreReferences.target(numbered), id);

            assertAll(
                    () -> assertEquals(RefUpdate.Result.NEW, working, "the working branch is created"),
                    () -> assertEquals(RefUpdate.Result.LOCK_FAILURE, collection, "the collection branch is refused"),
                    () -> assertNull(repository.exactRef(StoreReferences.target(numbered)),
                            "no collection branch exists"));
        }

        @Test
        @DisplayName("GDR-STORE-2: refs/heads/<c>/<id> cannot be created below refs/heads/<c>")
        void workingBranchBelowCollectionBranch(@InMemory Repository repository) throws Exception {
            var id = commit(repository);
            var numbered = key(Kind.NUMBERED, readableId());
            var perDocument = value(DocumentKey.of(
                    new DocumentCollection(numbered.collection().name(), Kind.PER_DOCUMENT), Ulid.next()));

            var collection = create(repository, StoreReferences.target(numbered), id);
            var working = create(repository, StoreReferences.target(perDocument), id);

            assertAll(
                    () -> assertEquals(RefUpdate.Result.NEW, collection, "the collection branch is created"),
                    () -> assertEquals(RefUpdate.Result.LOCK_FAILURE, working, "the working branch is refused"),
                    () -> assertFalse(repository.getRefDatabase().getRefsByPrefix(
                            StoreReferences.target(numbered) + "/").iterator().hasNext(), "no working branch exists"));
        }
    }
}
