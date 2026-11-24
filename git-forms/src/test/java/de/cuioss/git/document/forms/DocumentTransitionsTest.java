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
package de.cuioss.git.document.forms;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.IdentityClaims;
import de.cuioss.git.document.store.SigningKeySource;
import de.cuioss.git.document.store.StoreConfiguration;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.StoreRepository;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.StoreWriter;
import de.cuioss.git.document.store.TechnicalUser;
import de.cuioss.git.document.store.Ulid;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.juli.LogAsserts;
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
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The transitions of a workflow over the mechanisms of the store against a {@code git-testing} local remote
 * (GDR-FORMS-2, GDR-FORMS-4, GDR-FORMS-5, GDR-FORMS-7): every transition of the default workflow on its mechanism with
 * the field {@code state} it writes, the refusal of a transition the current state does not allow, {@code required} on
 * leaving the initial state and on every release, an invalid document, the output regenerated in the save commit also
 * on a moved remote tip and absent for a numbered kind without a template, the step-up check before any write, and a
 * release that fails after its save, which leaves the saved state.
 * <p>
 * The schema, the template, the workflows and the claims are literals, being the contract; ULIDs and the values of the
 * data are generated or fixed per test.
 */
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@DisplayName("DocumentTransitions (GDR-FORMS-4, GDR-FORMS-5, GDR-FORMS-7)")
class DocumentTransitionsTest {

    /** The instant of every commit and of every step-up check. */
    static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

    /** The collection of the test kinds. */
    static final String COLLECTION = "items";

    static final String ISSUER = "https://issuer.example.invalid";

    /** Claims of a multi-factor authentication one minute ago. */
    static final IdentityClaims MFA = new IdentityClaims(ISSUER, NOW.minusSeconds(60), null, List.of("pwd", "mfa"));

    /** Claims of a password authentication one minute ago. */
    static final IdentityClaims PASSWORD = new IdentityClaims(ISSUER, NOW.minusSeconds(60), null, List.of("pwd"));

    static final String SCHEMA = """
            {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object",
             "properties": {"title": {"type": "string", "maxLength": 40}, "owner": {"type": "string"},
                            "state": {"type": "string"}},
             "required": ["title", "owner"]}
            """;

    static final String TEMPLATE = """
            = {{title}}
            Owner: {{owner}}
            State: {{state}}

            {{>summary}}
            """;

    @TempDir
    Path temp;

    static FormSchema schema() {
        return Objects.requireNonNull(FormSchema.parse("schemas/item.schema.json",
                SCHEMA.getBytes(StandardCharsets.UTF_8)).value());
    }

    static DocumentTemplate template() {
        return Objects.requireNonNull(DocumentTemplate.parse("templates/item.adoc.tpl",
                TEMPLATE.getBytes(StandardCharsets.UTF_8)).value());
    }

    static Kind perDocument() {
        return new Kind("item", "register", COLLECTION, "schemas/item.schema.json", "templates/item.adoc.tpl",
                "workflows/approval.yaml", null);
    }

    static Kind numbered() {
        return new Kind("entry", "register", COLLECTION, "schemas/item.schema.json", null, "workflows/approval.yaml",
                new Numbering("R-", 3));
    }

    static <V> V value(StoreResult<V> result) {
        assertTrue(result.isOk(), () -> "accepted: " + result.detail());
        return Objects.requireNonNull(result.value());
    }

    static DocumentKey document() {
        return new DocumentKey(value(DocumentCollection.of(COLLECTION, DocumentCollection.Kind.PER_DOCUMENT)),
                Ulid.next());
    }

    static String directory(DocumentKey key) {
        return COLLECTION + "/" + key.id() + "/";
    }

    /** The content of {@code document.yaml} with the given fields, {@code null} values left out. */
    static byte[] data(@Nullable String title, @Nullable String owner, @Nullable String state) {
        Map<String, @Nullable Object> data = new LinkedHashMap<>();
        if (title != null) {
            data.put("title", title);
        }
        if (owner != null) {
            data.put("owner", owner);
        }
        if (state != null) {
            data.put("state", state);
        }
        return new YamlDocumentCodec(schema()).encode(data);
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Writes a commit holding exactly the files onto a reference, forcing it. */
    static ObjectId commit(Repository repository, String ref, Map<String, byte[]> files, ObjectId... parents) {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            for (Map.Entry<String, byte[]> file : new TreeMap<>(files).entrySet()) {
                var entry = new DirCacheEntry(file.getKey());
                entry.setFileMode(FileMode.REGULAR_FILE);
                entry.setObjectId(inserter.insert(Constants.OBJ_BLOB, file.getValue()));
                builder.add(entry);
            }
            builder.finish();
            var commit = new CommitBuilder();
            var ident = new PersonIdent("fixture", "");
            commit.setTreeId(index.writeTree(inserter));
            commit.setParentIds(parents);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("fixture");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(ref);
            update.setNewObjectId(id);
            update.setForceUpdate(true);
            update.update();
            return id;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static @Nullable ObjectId tip(Repository repository, String ref) {
        try {
            Ref found = repository.exactRef(ref);
            return found == null ? null : found.getObjectId();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The files of the tip of a reference, path to UTF-8 content; empty when the reference is absent. */
    static Map<String, String> files(Repository repository, String ref) {
        Map<String, String> files = new TreeMap<>();
        @Nullable ObjectId id = tip(repository, ref);
        if (id == null) {
            return files;
        }
        try (RevWalk walk = new RevWalk(repository); TreeWalk tree = new TreeWalk(repository)) {
            tree.addTree(walk.parseCommit(id).getTree());
            tree.setRecursive(true);
            while (tree.next()) {
                files.put(tree.getPathString(),
                        new String(repository.open(tree.getObjectId(0)).getBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return files;
    }

    /** The value of {@code state} in the file of a document on a reference, or {@code null}. */
    static @Nullable Object state(Repository repository, String ref, String path) {
        String content = files(repository, ref).get(path);
        if (content == null) {
            return null;
        }
        return value(new YamlDocumentCodec(schema()).decode(bytes(content))).get("state");
    }

    /** A writer over a local remote, the transitions of one kind over it, and its clone. */
    record Fixture(StoreWriter writer, Repository bareClone, DocumentTransitions transitions)
            implements AutoCloseable {

        DocumentTransitions.Result perform(Author author, IdentityClaims claims, DocumentKey key, String transition,
                List<TreeEdit> edits) {
            return transitions.perform(author, claims, key, transition, DocumentTransitions.Change.of(edits,
                    "Transition " + transition));
        }

        @Override
        public void close() {
            bareClone.close();
            writer.close();
        }
    }

    static Fixture open(Path temp, LocalRemote remote, DocumentTransitions.Definition definition,
            SigningKeySource signing) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("save", Map.of("max-attempts", 3));
        tree.put("drafts", Map.of("expiry", "P30D"));
        tree.put("fetch", Map.of("interval", "PT10M"));
        tree.put("signing", Map.of("key", "test-key"));
        var configuration = value(StoreConfiguration.from(tree, RemotePolicy.localOnly()));
        var gitDir = temp.resolve("clone.git");
        var writer = value(StoreWriter.open(new StoreRepository("register", remote.uri().toString(), "internal", null),
                gitDir, new TechnicalUser("store", null, new UsernamePasswordCredentialsProvider("store", "secret")),
                configuration, signing, new JGitRepositoryOperations(RemotePolicy.localOnly()),
                Clock.fixed(NOW, ZoneOffset.UTC)));
        try {
            Repository clone = new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build();
            return new Fixture(writer, clone, new DocumentTransitions(writer, clone, definition,
                    StepUpPolicy.of(Duration.parse("PT5M")), Clock.fixed(NOW, ZoneOffset.UTC)));
        } catch (IOException e) {
            writer.close();
            throw new UncheckedIOException(e);
        }
    }

    static DocumentTransitions.Definition perDocumentDefinition(Workflow workflow) {
        return new DocumentTransitions.Definition(perDocument(), schema(), workflow, template());
    }

    private Fixture open(LocalRemote remote) {
        return open(temp, remote, perDocumentDefinition(Workflow.defaultWorkflow()), new TestSigning(Integer.MAX_VALUE));
    }

    /** Seeds {@code main} on the remote, which every release merges into. */
    static ObjectId seedMain(LocalRemote remote) {
        return commit(remote.repository(), StoreReferences.MAIN, Map.of("README.adoc", bytes("= Register\n")));
    }

    private static List<TreeEdit> complete(DocumentKey key, String title) {
        return List.of(new TreeEdit.Put(directory(key) + DocumentLayout.DOCUMENT_FILE, data(title, "owner", null)),
                new TreeEdit.Put(directory(key) + "narrative/summary.adoc", bytes("A summary.\n")));
    }

    private static DocumentTransitions.Result.Performed performed(DocumentTransitions.Result result) {
        return assertInstanceOf(DocumentTransitions.Result.Performed.class, result,
                () -> "performed: " + result.outcome() + " " + result.detail());
    }

    /**
     * The transitions of the default workflow that require step-up, performed with {@link #MFA}: derived from the
     * {@link Transition#stepUp()} flags of {@link Workflow#defaultWorkflow()}, so a change of the workflow moves them.
     */
    private static final Set<String> STEP_UP_TRANSITIONS = stepUpTransitions();

    private static Set<String> stepUpTransitions() {
        Set<String> names = Workflow.defaultWorkflow().transitions().stream().filter(Transition::stepUp)
                .map(Transition::name).collect(Collectors.toUnmodifiableSet());
        if (names.isEmpty()) {
            throw new IllegalStateException("the default workflow has no transition that requires step-up");
        }
        return names;
    }

    /** The transitions up to and including the first release of the default workflow. */
    private static final String[] TO_FIRST_RELEASE = {"edit", "submit", "rework", "submit", "approve"};

    /**
     * Performs transitions of the default workflow in order, each asserted performed: {@code edit} with a complete
     * document, every other transition without edits; {@link #MFA} for a transition that requires step-up,
     * {@link #PASSWORD} otherwise.
     *
     * @return the last transition performed
     */
    private static DocumentTransitions.Result.Performed perform(Fixture fixture, DocumentKey key,
            String... transitions) {
        DocumentTransitions.Result.@Nullable Performed last = null;
        for (String transition : transitions) {
            IdentityClaims claims = STEP_UP_TRANSITIONS.contains(transition) ? MFA : PASSWORD;
            List<TreeEdit> edits = "edit".equals(transition) ? complete(key, "First") : List.of();
            last = performed(fixture.perform(Author.of("p-author"), claims, key, transition, edits));
        }
        return Objects.requireNonNull(last, "at least one transition");
    }

    private static String[] then(String[] prefix, String... transitions) {
        String[] all = Arrays.copyOf(prefix, prefix.length + transitions.length);
        System.arraycopy(transitions, 0, all, prefix.length, transitions.length);
        return all;
    }

    @Test
    @DisplayName("GDR-FORMS-5: edit of the default workflow is an intermediate save")
    void defaultWorkflowEdit(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        try (var fixture = open(remote)) {
            var edited = perform(fixture, key, "edit");
            assertAll("edit is an intermediate save",
                    () -> assertEquals(edited.commitId(),
                            Objects.requireNonNull(tip(fixture.bareClone(), value(StoreReferences.draft("p-author", key))))
                                    .name(), "the draft commit"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.target(key)), "nothing saved"),
                    () -> assertNull(edited.release(), "no release"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: submit and rework of the default workflow save the state")
    void defaultWorkflowSubmitAndRework(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        try (var fixture = open(remote)) {
            perform(fixture, key, "edit", "submit");
            assertEquals("review", state(remote.repository(), target, document), "submit saves review");

            perform(fixture, key, "rework");
            assertEquals("draft", state(remote.repository(), target, document), "rework saves draft");
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: approve of the default workflow is a save and a release")
    void defaultWorkflowApprove(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        try (var fixture = open(remote)) {
            var approved = perform(fixture, key, TO_FIRST_RELEASE);
            assertAll("approve is a save and a release",
                    () -> assertEquals("approved", state(remote.repository(), target, document), "saved approved"),
                    () -> assertEquals("approved", state(remote.repository(), StoreReferences.MAIN, document),
                            "released approved"),
                    () -> assertEquals(1, Objects.requireNonNull(approved.release()).version(), "v1"),
                    () -> assertEquals(approved.commitId(),
                            Objects.requireNonNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1))).name(),
                            "the tag on the release commit"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: revise of the default workflow saves draft and keeps main")
    void defaultWorkflowRevise(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        try (var fixture = open(remote)) {
            perform(fixture, key, then(TO_FIRST_RELEASE, "revise"));
            assertAll("revise saves draft and keeps main",
                    () -> assertEquals("draft", state(remote.repository(), target, document), "the working branch"),
                    () -> assertEquals("approved", state(remote.repository(), StoreReferences.MAIN, document),
                            "main keeps the last release"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: supersede of the default workflow is the last release")
    void defaultWorkflowSupersede(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        try (var fixture = open(remote)) {
            var superseded = perform(fixture, key,
                    then(TO_FIRST_RELEASE, "revise", "submit", "approve", "supersede"));
            assertAll("supersede is the last release",
                    () -> assertEquals("superseded", state(remote.repository(), StoreReferences.MAIN, document),
                            "released superseded"),
                    () -> assertEquals(3, Objects.requireNonNull(superseded.release()).version(), "v3"),
                    () -> assertNotNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1)),
                            "the earlier tags stay"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            GitFormsLogMessages.INFO.TRANSITION_PERFORMED.resolveIdentifierString()));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: a transition the current state does not allow, or the workflow lacks, is refused")
    void transitionRefused(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var author = Author.of("p-author");
        try (var fixture = open(remote)) {
            var notAllowed = fixture.perform(author, MFA, key, "approve", complete(key, "First"));
            var unknown = fixture.perform(author, MFA, key, "publish", complete(key, "First"));

            assertAll(
                    () -> assertEquals(new DocumentTransitions.Result.NotPerformed(FormsOutcome.TRANSITION_REFUSED,
                            "transition: not allowed in the current state"), notAllowed, "approve in draft"),
                    () -> assertEquals(new DocumentTransitions.Result.NotPerformed(FormsOutcome.TRANSITION_REFUSED,
                            "transition: not in the workflow"), unknown, "a name the workflow lacks"),
                    () -> assertNull(tip(fixture.bareClone(), value(StoreReferences.draft("p-author", key))),
                            "no draft written"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.target(key)), "nothing saved"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            GitFormsLogMessages.INFO.TRANSITION_NOT_PERFORMED.resolveIdentifierString()));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-2: leaving the initial state checks required and names the JSON Pointers")
    void requiredOnLeavingInitialState(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var author = Author.of("p-author");
        try (var fixture = open(remote)) {
            var edits = List.<TreeEdit>of(new TreeEdit.Put(directory(key) + DocumentLayout.DOCUMENT_FILE,
                    data("First", null, null)));

            var result = fixture.perform(author, PASSWORD, key, "submit", edits);

            assertAll(
                    () -> assertEquals(new DocumentTransitions.Result.Refused(
                            new ValidationResult.MandatoryFieldsMissing(List.of("/owner"))), result, "the pointer"),
                    () -> assertEquals(FormsOutcome.MANDATORY_FIELDS_MISSING, result.outcome(), "the outcome"),
                    () -> assertEquals("1 mandatory fields missing", result.detail().orElseThrow(),
                            "the detail names the number only"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.target(key)), "nothing saved"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-2: every release checks required; a save that stays out of the initial state does not")
    void requiredOnRelease(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var inReview = document();
        var approved = document();
        commit(remote.repository(), StoreReferences.target(inReview),
                Map.of(directory(inReview) + DocumentLayout.DOCUMENT_FILE, data("First", null, "review")), main);
        commit(remote.repository(), StoreReferences.target(approved),
                Map.of(directory(approved) + DocumentLayout.DOCUMENT_FILE, data("Second", null, "approved")), main);
        var author = Author.of("p-author");
        try (var fixture = open(remote)) {
            value(fixture.writer().fetch());
            var mainBefore = tip(remote.repository(), StoreReferences.MAIN);

            var release = fixture.perform(author, MFA, inReview, "approve", List.of());
            var revise = fixture.perform(author, PASSWORD, approved, "revise", List.of());

            assertAll(
                    () -> assertEquals(new DocumentTransitions.Result.Refused(
                            new ValidationResult.MandatoryFieldsMissing(List.of("/owner"))), release, "approve"),
                    () -> assertEquals(mainBefore, tip(remote.repository(), StoreReferences.MAIN), "nothing released"),
                    () -> assertEquals("review", state(remote.repository(), StoreReferences.target(inReview),
                            directory(inReview) + DocumentLayout.DOCUMENT_FILE), "the state stays"),
                    () -> performed(revise),
                    () -> assertEquals("draft", state(remote.repository(), StoreReferences.target(approved),
                            directory(approved) + DocumentLayout.DOCUMENT_FILE), "revise without required"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-2: an invalid document is refused as INVALID with its violations as data")
    void invalidRefused(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var author = Author.of("p-author");
        try (var fixture = open(remote)) {
            var result = fixture.perform(author, PASSWORD, key, "submit", complete(key, "x".repeat(41)));

            var refused = assertInstanceOf(DocumentTransitions.Result.Refused.class, result);
            var invalid = assertInstanceOf(ValidationResult.Invalid.class, refused.findings());
            assertAll(
                    () -> assertEquals(FormsOutcome.INVALID, result.outcome(), "the outcome"),
                    () -> assertEquals(List.of("/title"), invalid.violations().stream().map(Violation::pointer)
                            .toList(), "the pointer of the violation"),
                    () -> assertEquals("1 violations", result.detail().orElseThrow(), "the number only"),
                    () -> assertEquals(FormsResult.of(FormsOutcome.INVALID, "1 violations"), result.toResult(),
                            "the plain shape"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.target(key)), "nothing saved"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-4: the save commit carries the output regenerated from its data on the moved remote tip")
    void outputRegeneratedOnMovedTip(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        var summary = directory(key) + "narrative/summary.adoc";
        var first = commit(remote.repository(), target,
                Map.of(document, data("First", "owner", "draft"), summary, bytes("The first summary.\n")), main);
        var author = Author.of("p-author");
        try (var fixture = open(remote)) {
            value(fixture.writer().fetch());
            performed(fixture.perform(author, PASSWORD, key, "edit",
                    List.of(new TreeEdit.Put(document, data("Second", "owner", "draft")))));
            Map<String, byte[]> moved = new TreeMap<>();
            moved.put(document, data("First", "owner", "draft"));
            moved.put(summary, bytes("A concurrent summary.\n"));
            commit(remote.repository(), target, moved, first);

            var saved = performed(fixture.perform(author, PASSWORD, key, "submit", List.of()));

            var files = files(remote.repository(), target);
            var output = Objects.requireNonNull(files.get(directory(key) + DocumentLayout.OUTPUT_FILE));
            assertAll(
                    () -> assertEquals(saved.commitId(), Objects.requireNonNull(tip(remote.repository(), target))
                            .name(), "one save commit"),
                    () -> assertEquals("= Second\nOwner: owner\nState: review\n\nA concurrent summary.\n", output,
                            "the data of the commit and the fragment of the moved tip"),
                    () -> assertEquals(FormsOutcome.OK,
                            new OutputCheck(perDocument(), schema(), template()).check(remote.repository(), target)
                                    .outcome(), "the committed output matches its data"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-4: a numbered kind without a template saves its entry and generates no output")
    void numberedWithoutTemplate(@Remote LocalRemote remote) {
        seedMain(remote);
        var collection = value(DocumentCollection.of(COLLECTION, DocumentCollection.Kind.NUMBERED));
        var key = new DocumentKey(collection, "R-001");
        var entry = COLLECTION + "/R-001.yaml";
        var definition = new DocumentTransitions.Definition(numbered(), schema(), Workflow.defaultWorkflow(), null);
        var author = Author.of("p-author");
        try (var fixture = open(temp, remote, definition, new TestSigning(Integer.MAX_VALUE))) {
            performed(fixture.perform(author, PASSWORD, key, "submit",
                    List.of(new TreeEdit.Put(entry, data("First", "owner", null)))));

            var files = files(remote.repository(), StoreReferences.target(key));
            assertAll(
                    () -> assertEquals("review", state(remote.repository(), StoreReferences.target(key), entry),
                            "the entry carries the state"),
                    () -> assertFalse(files.containsKey(COLLECTION + "/R-001.adoc"), "no output"),
                    () -> assertEquals(List.of(entry), files.keySet().stream().filter(path -> path.startsWith(
                            COLLECTION + "/")).toList(), "the entry only"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-7: a release without step-up claims is STEP_UP_REQUIRED and changes nothing")
    void releaseWithoutStepUp(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var working = commit(remote.repository(), target,
                Map.of(directory(key) + DocumentLayout.DOCUMENT_FILE, data("First", "owner", "review")), main);
        var author = Author.of("p-author");
        var stale = new IdentityClaims(ISSUER, NOW.minus(Duration.parse("PT5M")).minusSeconds(1), null,
                List.of("mfa"));
        try (var fixture = open(remote)) {
            value(fixture.writer().fetch());

            var password = fixture.perform(author, PASSWORD, key, "approve", List.of());
            var old = fixture.perform(author, stale, key, "approve", List.of());

            var refused = new DocumentTransitions.Result.NotPerformed(FormsOutcome.STEP_UP_REQUIRED,
                    "step-up: the claims do not hold step-up");
            assertAll(
                    () -> assertEquals(refused, password, "a single method"),
                    () -> assertEquals(refused, old, "an authentication older than step-up.max-age"),
                    () -> assertEquals(working, tip(remote.repository(), target), "the working branch unchanged"),
                    () -> assertEquals(main, tip(remote.repository(), StoreReferences.MAIN), "main unchanged"),
                    () -> assertNull(tip(fixture.bareClone(), value(StoreReferences.draft("p-author", key))),
                            "no draft written"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1)), "no tag"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-7: a save declared with step-up: true is refused without step-up claims, performed with")
    void saveWithStepUp(@Remote LocalRemote remote) {
        seedMain(remote);
        var workflow = Objects.requireNonNull(Workflow.parse("workflows/strict.yaml", bytes("""
                states: [draft, review, approved]
                transitions:
                  - { name: submit,  from: draft,  to: review,   mechanism: save,    step-up: true }
                  - { name: approve, from: review, to: approved, mechanism: release, step-up: false }
                """)).value());
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        var author = Author.of("p-author");
        try (var fixture = open(temp, remote, perDocumentDefinition(workflow), new TestSigning(Integer.MAX_VALUE))) {
            var refused = fixture.perform(author, PASSWORD, key, "submit", complete(key, "First"));
            var saved = fixture.perform(author, MFA, key, "submit", complete(key, "First"));
            var approved = fixture.perform(author, PASSWORD, key, "approve", List.of());

            assertAll(
                    () -> assertEquals(FormsOutcome.STEP_UP_REQUIRED, refused.outcome(), "without step-up"),
                    () -> performed(saved),
                    () -> performed(approved),
                    () -> assertEquals("approved", state(remote.repository(), StoreReferences.MAIN, document),
                            "a release declared without step-up"),
                    () -> assertEquals("approved", state(remote.repository(), target, document), "the saved state"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-7: a release with step-up claims is one save with the step and then the release")
    void releaseIsOneSaveThenRelease(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var working = commit(remote.repository(), target,
                Map.of(directory(key) + DocumentLayout.DOCUMENT_FILE, data("First", "owner", "review")), main);
        var author = Author.of("p-author");
        try (var fixture = open(remote)) {
            value(fixture.writer().fetch());

            var released = performed(fixture.perform(author, MFA, key, "approve", List.of()));

            var saveCommit = Objects.requireNonNull(tip(remote.repository(), target));
            var files = files(remote.repository(), target);
            Map<String, String> onMain = new TreeMap<>(files(remote.repository(), StoreReferences.MAIN));
            onMain.keySet().removeIf(path -> !path.startsWith(COLLECTION + "/"));
            assertAll(
                    () -> assertNotEquals(working, saveCommit, "the save moved the working branch"),
                    () -> assertTrue(Objects.requireNonNull(files.get(directory(key) + DocumentLayout.OUTPUT_FILE))
                            .contains("State: approved"), "the output of the save commit carries the new state"),
                    () -> assertEquals(files, onMain, "main holds the saved document"),
                    () -> assertEquals(released.commitId(), Objects.requireNonNull(
                            tip(remote.repository(), StoreReferences.MAIN)).name(), "the release commit"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-7: a release that fails after its save is passed on and the saved state stays")
    void failedReleaseKeepsTheSavedState(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        commit(remote.repository(), target,
                Map.of(directory(key) + DocumentLayout.DOCUMENT_FILE, data("First", "owner", "review")), main);
        var author = Author.of("p-author");
        try (var fixture = open(temp, remote, perDocumentDefinition(Workflow.defaultWorkflow()), new TestSigning(1))) {
            value(fixture.writer().fetch());

            var result = fixture.perform(author, MFA, key, "approve", List.of());

            var notPerformed = assertInstanceOf(DocumentTransitions.Result.NotPerformed.class, result);
            assertAll(
                    () -> assertEquals(new FormsResult.PassedOn(StoreOutcome.REFUSED), notPerformed.outcome(),
                            "the outcome of the release, passed on"),
                    () -> assertEquals("approved", state(remote.repository(), target,
                            directory(key) + DocumentLayout.DOCUMENT_FILE), "the saved state stays"),
                    () -> assertEquals(main, tip(remote.repository(), StoreReferences.MAIN), "nothing released"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1)), "no tag"));
        }
    }

    /** A workflow with self-loops on {@code release} and {@code save} from the state {@code approved}. */
    static Workflow selfLoopWorkflow() {
        return Objects.requireNonNull(Workflow.parse("workflows/loops.yaml", bytes("""
                states: [draft, review, approved]
                transitions:
                  - { name: edit,      from: draft,    to: draft,    mechanism: intermediate-save }
                  - { name: submit,    from: draft,    to: review,   mechanism: save }
                  - { name: rework,    from: review,   to: draft,    mechanism: save }
                  - { name: approve,   from: review,   to: approved, mechanism: release }
                  - { name: republish, from: approved, to: approved, mechanism: release }
                  - { name: resave,    from: approved, to: approved, mechanism: save }
                """)).value());
    }

    private Fixture openSelfLoops(LocalRemote remote) {
        return open(temp, remote, perDocumentDefinition(selfLoopWorkflow()), new TestSigning(Integer.MAX_VALUE));
    }

    /** The parents of a commit. */
    static List<ObjectId> parents(Repository repository, @Nullable ObjectId commit) {
        try (RevWalk walk = new RevWalk(repository)) {
            return List.of(walk.parseCommit(Objects.requireNonNull(commit, "a commit")).getParents()).stream()
                    .map(ObjectId::copy).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The content of {@code document.yaml} with every field of the map. */
    static byte[] fields(Map<String, @Nullable Object> fields) {
        return new YamlDocumentCodec(schema()).encode(new LinkedHashMap<>(fields));
    }

    /** The decoded {@code document.yaml} of a document on a reference of the remote. */
    static Map<String, @Nullable Object> decoded(LocalRemote remote, String ref, DocumentKey key) {
        String content = Objects.requireNonNull(files(remote.repository(), ref).get(directory(key)
                + DocumentLayout.DOCUMENT_FILE), "the file of the document");
        return value(new YamlDocumentCodec(schema()).decode(bytes(content)));
    }

    @Test
    @DisplayName("GDR-FORMS-5: an unchanged self-loop release of invalid data is refused as INVALID; nothing released")
    void selfLoopReleaseOfInvalidDataIsRefused(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var working = commit(remote.repository(), target,
                Map.of(directory(key) + DocumentLayout.DOCUMENT_FILE, data("x".repeat(41), "owner", "approved")), main);
        try (var fixture = openSelfLoops(remote)) {
            value(fixture.writer().fetch());

            var result = fixture.perform(Author.of("p-author"), MFA, key, "republish", List.of());

            assertAll(
                    () -> assertEquals(FormsOutcome.INVALID, result.outcome(), "the validation of the saved commit"),
                    () -> assertInstanceOf(DocumentTransitions.Result.Refused.class, result, "findings as data"),
                    () -> assertEquals(main, tip(remote.repository(), StoreReferences.MAIN), "nothing released"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1)), "no tag"),
                    () -> assertEquals(working, tip(remote.repository(), target), "nothing written"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: an unchanged self-loop release without a required field is MANDATORY_FIELDS_MISSING")
    void selfLoopReleaseWithoutRequiredFieldIsRefused(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        commit(remote.repository(), StoreReferences.target(key),
                Map.of(directory(key) + DocumentLayout.DOCUMENT_FILE, data("First", null, "approved")), main);
        try (var fixture = openSelfLoops(remote)) {
            value(fixture.writer().fetch());

            var result = fixture.perform(Author.of("p-author"), MFA, key, "republish", List.of());

            assertAll(
                    () -> assertEquals(new DocumentTransitions.Result.Refused(
                            new ValidationResult.MandatoryFieldsMissing(List.of("/owner"))), result, "the pointer"),
                    () -> assertEquals(main, tip(remote.repository(), StoreReferences.MAIN), "nothing released"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1)), "no tag"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: an unchanged self-loop release of a valid document releases once, writing no commit")
    void selfLoopReleaseOfValidDocument(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        try (var fixture = openSelfLoops(remote)) {
            perform(fixture, key, "edit", "submit", "approve");
            var saved = tip(remote.repository(), target);

            var republished = performed(fixture.perform(Author.of("p-author"), MFA, key, "republish", List.of()));

            var release = ObjectId.fromString(republished.commitId());
            assertAll(
                    () -> assertEquals(2, Objects.requireNonNull(republished.release()).version(), "v2"),
                    () -> assertEquals(saved, tip(remote.repository(), target), "no commit before the release"),
                    () -> assertEquals(saved, parents(remote.repository(), release).get(1),
                            "the saved commit released"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: an unchanged self-loop with a stale output writes the regenerated output and releases it")
    void selfLoopWithStaleOutputIsRewritten(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var stale = commit(remote.repository(), target, Map.of(
                directory(key) + DocumentLayout.DOCUMENT_FILE, data("First", "owner", "approved"),
                directory(key) + "narrative/summary.adoc", bytes("A summary.\n"),
                directory(key) + DocumentLayout.OUTPUT_FILE, bytes("stale\n")), main);
        try (var fixture = openSelfLoops(remote)) {
            value(fixture.writer().fetch());

            var republished = performed(fixture.perform(Author.of("p-author"), MFA, key, "republish", List.of()));

            var rewritten = tip(remote.repository(), target);
            assertAll(
                    () -> assertNotEquals(stale, rewritten, "the regenerated output written"),
                    () -> assertEquals(List.of(stale), parents(remote.repository(), rewritten), "one commit"),
                    () -> assertEquals(FormsOutcome.OK, new OutputCheck(perDocument(), schema(), template())
                            .check(remote.repository(), target).outcome(), "the output matches its data"),
                    () -> assertEquals(rewritten, parents(remote.repository(),
                            ObjectId.fromString(republished.commitId())).get(1), "the rewritten commit released"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: a self-loop save that changes data runs the step once and writes one commit")
    void changedSelfLoopSaveIsOneCommit(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        try (var fixture = openSelfLoops(remote)) {
            perform(fixture, key, "edit", "submit", "approve");
            var before = tip(remote.repository(), target);

            var saved = performed(fixture.perform(Author.of("p-author"), PASSWORD, key, "resave",
                    List.of(new TreeEdit.Put(directory(key) + DocumentLayout.DOCUMENT_FILE, data("Second", "owner",
                            null)))));

            var after = tip(remote.repository(), target);
            assertAll(
                    () -> assertEquals(Objects.requireNonNull(after).name(), saved.commitId(), "the save commit"),
                    () -> assertEquals(List.of(before), parents(remote.repository(), after), "one commit"),
                    () -> assertTrue(Objects.requireNonNull(files(remote.repository(), target)
                                    .get(directory(key) + DocumentLayout.OUTPUT_FILE)).startsWith("= Second\n"),
                            "the output regenerated by the step"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: a state-only transition keeps a title another writer changed on the tip")
    void stateOnlyTransitionKeepsConcurrentChange(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        var first = commit(remote.repository(), target, Map.of(document, data("First", "owner", "review")), main);
        try (var fixture = open(remote)) {
            value(fixture.writer().fetch());
            commit(remote.repository(), target, Map.of(document, data("Changed", "owner", "review")), first);

            performed(fixture.perform(Author.of("p-author"), PASSWORD, key, "rework", List.of()));

            assertEquals(Map.of("title", "Changed", "owner", "owner", "state", "draft"),
                    decoded(remote, target, key), "the concurrent title and the new state");
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: an intermediate-save edit is kept by the state transition, a concurrent change too")
    void draftEditAndConcurrentChangeAreKept(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        var first = commit(remote.repository(), target, Map.of(document, data("First", "owner", "draft")), main);
        var author = Author.of("p-author");
        try (var fixture = open(remote)) {
            value(fixture.writer().fetch());
            performed(fixture.perform(author, PASSWORD, key, "edit",
                    List.of(new TreeEdit.Put(document, data("First", "other", "draft")))));
            commit(remote.repository(), target, Map.of(document, data("Changed", "owner", "draft")), first);

            performed(fixture.perform(author, PASSWORD, key, "submit", List.of()));

            assertEquals(Map.of("title", "Changed", "owner", "other", "state", "review"),
                    decoded(remote, target, key), "the draft's owner, the tip's title");
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: a field the draft changed wins over the tip's change of the same field")
    void draftChangeWins(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        var first = commit(remote.repository(), target, Map.of(document, data("First", "owner", "draft")), main);
        var author = Author.of("p-author");
        try (var fixture = open(remote)) {
            value(fixture.writer().fetch());
            performed(fixture.perform(author, PASSWORD, key, "edit",
                    List.of(new TreeEdit.Put(document, data("Mine", "owner", "draft")))));
            commit(remote.repository(), target, Map.of(document, data("Theirs", "owner", "draft")), first);

            performed(fixture.perform(author, PASSWORD, key, "submit", List.of()));

            assertEquals("Mine", decoded(remote, target, key).get("title"), "the draft changed the title");
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: a key the draft deleted is removed from the tip's file")
    void draftDeletionRemovesTheKey(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        Map<String, @Nullable Object> withNote = new LinkedHashMap<>();
        withNote.put("title", "First");
        withNote.put("owner", "owner");
        withNote.put("note", "remove me");
        withNote.put("state", "draft");
        var first = commit(remote.repository(), target, Map.of(document, fields(withNote)), main);
        var author = Author.of("p-author");
        try (var fixture = open(remote)) {
            value(fixture.writer().fetch());
            performed(fixture.perform(author, PASSWORD, key, "edit",
                    List.of(new TreeEdit.Put(document, data("First", "owner", "draft")))));
            Map<String, @Nullable Object> moved = new LinkedHashMap<>(withNote);
            moved.put("title", "Changed");
            commit(remote.repository(), target, Map.of(document, fields(moved)), first);

            performed(fixture.perform(author, PASSWORD, key, "submit", List.of()));

            assertEquals(Map.of("title", "Changed", "owner", "owner", "state", "review"),
                    decoded(remote, target, key), "the note removed, the tip's title kept");
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: the release commit merges exactly the commit the transition saved")
    void releaseMergesTheSavedCommit(@Remote LocalRemote remote) {
        seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        try (var fixture = open(remote)) {
            var approved = perform(fixture, key, TO_FIRST_RELEASE);

            var release = ObjectId.fromString(approved.commitId());
            assertAll(
                    () -> assertEquals(tip(remote.repository(), target), parents(remote.repository(), release).get(1),
                            "the save commit is the second parent"),
                    () -> assertEquals(approved.commitId(), Objects.requireNonNull(approved.release()).commitId(),
                            "the result carries the release commit"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: a branch that moves between the save and the release refuses the release")
    void movedBranchRefusesTheRelease(@Remote LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        commit(remote.repository(), target, Map.of(document, data("First", "owner", "review")), main);
        AtomicBoolean moved = new AtomicBoolean();
        ObjectId[] saved = new ObjectId[1];
        try (var fixture = open(remote)) {
            value(fixture.writer().fetch());
            // another writer saves the document right after this save pushed, before the release fetches
            try (var subscription = fixture.writer().subscribe(target, (branch, commitId) -> {
                     if (moved.compareAndSet(false, true)) {
                         saved[0] = ObjectId.fromString(commitId);
                         commit(remote.repository(), target, Map.of(document, data("Other", "owner", "draft")),
                                 saved[0]);
                     }
                 })) {
                var result = fixture.perform(Author.of("p-author"), MFA, key, "approve", List.of());

                var notPerformed = assertInstanceOf(DocumentTransitions.Result.NotPerformed.class, result);
                assertAll(
                        () -> assertEquals(new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.CONFLICT)),
                                notPerformed.outcome(), "the refusal of the store, passed on"),
                        () -> assertEquals(target + ": no longer points to the saved commit", notPerformed.reason(),
                                "the branch named, no data"),
                        () -> assertEquals(main, tip(remote.repository(), StoreReferences.MAIN), "nothing released"),
                        () -> assertNull(tip(remote.repository(), StoreReferences.releaseTag(key, 1)), "no tag"),
                        () -> assertEquals(List.of(saved[0]), parents(remote.repository(),
                                tip(remote.repository(), target)), "the saved commit stays under the other save"));
            }
        }
    }

    /**
     * An in-memory signing key source over a generated Ed25519 key that signs a given number of times and refuses
     * every later call; its public key line is the OpenSSH form {@code ssh-ed25519 <base64>}.
     */
    static final class TestSigning implements SigningKeySource {

        private final KeyPair pair;
        private final int signatures;
        private final AtomicInteger signed = new AtomicInteger();

        TestSigning(int signatures) {
            try {
                this.pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("every Java platform from Java 15 on provides Ed25519", e);
            }
            this.signatures = signatures;
        }

        @Override
        public String id() {
            return "test";
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            if (signed.incrementAndGet() > signatures) {
                return StoreResult.of(StoreOutcome.REFUSED, "signing: refused");
            }
            try {
                var signature = Signature.getInstance("Ed25519");
                signature.initSign(pair.getPrivate());
                signature.update(data);
                return StoreResult.ok(signature.sign());
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Ed25519 signing with a generated key", e);
            }
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            byte[] encoded = pair.getPublic().getEncoded();
            // the X.509 SubjectPublicKeyInfo of an Ed25519 key ends with the 32 key bytes (RFC 8410, section 4)
            byte[] key = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
            var blob = new ByteArrayOutputStream();
            writeString(blob, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
            writeString(blob, key);
            return StoreResult.ok("ssh-ed25519 " + Base64.getEncoder().encodeToString(blob.toByteArray()));
        }

        private static void writeString(ByteArrayOutputStream out, byte[] value) {
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            out.writeBytes(value);
        }
    }
}
