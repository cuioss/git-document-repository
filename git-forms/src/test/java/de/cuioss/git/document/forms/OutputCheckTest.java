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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check of the generated output (GDR-FORMS-4) against in-memory repositories of {@code git-testing}: a committed
 * output that matches its data and fragments is {@link FormsOutcome#OK}, an edited output, edited data or a missing
 * output is {@link FormsOutcome#MISMATCH} naming the output path only, a numbered kind without a template checks
 * nothing, and data that cannot be read or a revision that cannot be resolved is passed on; no detail carries data
 * (GDR-NFR-3).
 * <p>
 * The template and the expected output are literals, being the contract; ULIDs, numbers and the strings of the data
 * are generated, from letters only, so that none can occur in the fixed text of a detail.
 */
@EnableGeneratorController
@DisplayName("OutputCheck (GDR-FORMS-4)")
class OutputCheckTest {

    /** The branch the test commits are written to. */
    static final String BRANCH = "refs/heads/main";

    /** The template of the test kinds. */
    static final String TEMPLATE = """
            = {{title}}
            Owner: {{owner}}
            Reason: {{details.reason}}
            {{>summary}}
            """;

    /** The collection of the test kinds. */
    static final String COLLECTION = "items";

    private static final TypedGenerator<String> WORDS =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20));

    static String word() {
        return WORDS.next();
    }

    static FormSchema schema() {
        return SchemaValidatorTest.schema(SchemaValidatorTest.SCHEMA);
    }

    static Kind perDocument() {
        return new Kind("item", "register", COLLECTION, "schemas/item.schema.json", "templates/item.adoc.tpl",
                "workflows/approval.yaml", null);
    }

    static Kind numbered(boolean withTemplate) {
        return new Kind("entry", "register", COLLECTION, "schemas/item.schema.json",
                withTemplate ? "templates/item.adoc.tpl" : null, "workflows/approval.yaml", new Numbering("R-", 3));
    }

    /** A document of a per-document kind: its data, part, fragment and the output these give. */
    record Document(String ulid, String title, String owner, String reason, String summary) {

        static Document generate() {
            return new Document(word(), word(), word(), word(), word());
        }

        String directory() {
            return COLLECTION + "/" + ulid + "/";
        }

        String outputPath() {
            return directory() + DocumentLayout.OUTPUT_FILE;
        }

        String output() {
            return "= " + title + "\nOwner: " + owner + "\nReason: " + reason + "\n" + summary + "\n";
        }

        /** The committed files: document, split part, fragment and, if given, the output. */
        Map<String, byte[]> files(@Nullable String output) {
            var codec = new YamlDocumentCodec(schema());
            Map<String, @Nullable Object> document = new LinkedHashMap<>();
            document.put("title", title);
            document.put("owner", owner);
            Map<String, @Nullable Object> details = new LinkedHashMap<>();
            details.put("reason", reason);
            Map<String, byte[]> files = new TreeMap<>();
            files.put(directory() + DocumentLayout.DOCUMENT_FILE, codec.encode(document));
            files.put(directory() + "details.yaml", codec.part("details").encode(details));
            files.put(directory() + "narrative/summary.adoc", (summary + "\n").getBytes(StandardCharsets.UTF_8));
            if (output != null) {
                files.put(outputPath(), output.getBytes(StandardCharsets.UTF_8));
            }
            return files;
        }
    }

    /** Commits the files as the only commit of {@link #BRANCH} and returns its id. */
    static ObjectId commit(Repository repository, Map<String, byte[]> files) throws IOException {
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
            var ident = new PersonIdent("test", "");
            commit.setTreeId(index.writeTree(inserter));
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("test");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            RefUpdate update = repository.updateRef(BRANCH);
            update.setNewObjectId(id);
            update.forceUpdate();
            return id;
        }
    }

    private static OutputCheck check(Kind kind) {
        @Nullable DocumentTemplate template = kind.template() == null ? null : DocumentTemplateTest.template(TEMPLATE);
        return new OutputCheck(kind, schema(), template);
    }

    @Test
    @DisplayName("GDR-FORMS-4: a committed output that matches its data and fragments is OK")
    void matching(@InMemory Repository repository) throws Exception {
        var first = Document.generate();
        var second = Document.generate();
        Map<String, byte[]> files = new TreeMap<>(first.files(first.output()));
        files.putAll(second.files(second.output()));
        commit(repository, files);

        var result = check(perDocument()).check(repository, BRANCH);

        assertEquals(FormsResult.ok(List.of(first.outputPath(), second.outputPath()).stream().sorted().toList()),
                result, "both outputs checked and matching");
    }

    @Test
    @DisplayName("GDR-FORMS-4: an edited output is MISMATCH, naming the output path only")
    void editedOutput(@InMemory Repository repository) throws Exception {
        var document = Document.generate();
        commit(repository, document.files(document.output() + word() + "\n"));

        var result = check(perDocument()).check(repository, BRANCH);

        assertMismatch(document, result);
    }

    @Test
    @DisplayName("GDR-FORMS-4: edited data under an unchanged output is MISMATCH")
    void editedData(@InMemory Repository repository) throws Exception {
        var document = Document.generate();
        var edited = new Document(document.ulid(), word(), document.owner(), document.reason(), document.summary());
        commit(repository, edited.files(document.output()));

        var result = check(perDocument()).check(repository, BRANCH);

        assertMismatch(edited, result);
        assertFalse(Objects.requireNonNull(result.detail()).contains(document.title()), "no data in the detail");
    }

    @Test
    @DisplayName("GDR-FORMS-4: a missing output is checked and mismatches")
    void missingOutput(@InMemory Repository repository) throws Exception {
        var document = Document.generate();
        commit(repository, document.files(null));

        var comparison = check(perDocument()).compare(repository, BRANCH);

        assertEquals(FormsResult.ok(new OutputCheck.Comparison(List.of(document.outputPath()),
                List.of(document.outputPath()))), comparison, "checked and mismatching");
    }

    @Test
    @DisplayName("GDR-FORMS-4: a numbered kind without a template generates and checks nothing")
    void numberedWithoutTemplate(@InMemory Repository repository) {
        var result = check(numbered(false)).check(repository, BRANCH);

        assertEquals(FormsResult.ok(List.of()), result, "nothing checked, not even the revision");
    }

    @Test
    @DisplayName("GDR-FORMS-4: a numbered kind with a template checks <collection>/<number>.adoc, not the counter")
    void numberedWithTemplate(@InMemory Repository repository) throws Exception {
        var codec = new YamlDocumentCodec(schema());
        var title = word();
        var owner = word();
        Map<String, @Nullable Object> entry = new LinkedHashMap<>();
        entry.put("title", title);
        entry.put("owner", owner);
        Map<String, byte[]> files = new TreeMap<>();
        files.put(COLLECTION + "/R-001.yaml", codec.encode(entry));
        files.put(COLLECTION + "/R-001.adoc", ("= " + title + "\nOwner: " + owner + "\nReason: \n")
                .getBytes(StandardCharsets.UTF_8));
        files.put(COLLECTION + "/R-002.yaml", codec.encode(entry));
        files.put(COLLECTION + "/.counter", "2\n".getBytes(StandardCharsets.UTF_8));
        commit(repository, files);

        var comparison = check(numbered(true)).compare(repository, BRANCH);

        assertEquals(FormsResult.ok(new OutputCheck.Comparison(
                List.of(COLLECTION + "/R-001.adoc", COLLECTION + "/R-002.adoc"),
                List.of(COLLECTION + "/R-002.adoc"))), comparison, "the matching and the missing output");
    }

    @Test
    @DisplayName("GDR-FORMS-4: data that cannot be read is MALFORMED, naming the path, never data")
    void unreadableData(@InMemory Repository repository) throws Exception {
        var document = Document.generate();
        Map<String, byte[]> files = new TreeMap<>(document.files(document.output()));
        files.put(document.directory() + DocumentLayout.DOCUMENT_FILE,
                ("title: [" + document.title()).getBytes(StandardCharsets.UTF_8));
        commit(repository, files);

        var result = check(perDocument()).check(repository, BRANCH);

        assertAll(
                () -> assertEquals(new FormsResult.PassedOn(StoreOutcome.MALFORMED), result.outcome(), "malformed"),
                () -> assertTrue(Objects.requireNonNull(result.detail())
                        .startsWith(document.directory() + DocumentLayout.DOCUMENT_FILE + ": "), "the path"),
                () -> assertFalse(Objects.requireNonNull(result.detail()).contains(document.title()), "no data"));
    }

    @Test
    @DisplayName("GDR-FORMS-4: a fragment that is not UTF-8 is MALFORMED, naming the path")
    void fragmentNotUtf8(@InMemory Repository repository) throws Exception {
        var document = Document.generate();
        Map<String, byte[]> files = new TreeMap<>(document.files(document.output()));
        files.put(document.directory() + "narrative/summary.adoc", new byte[]{(byte) 0xC3, (byte) 0x28});
        commit(repository, files);

        var result = check(perDocument()).check(repository, BRANCH);

        assertEquals(FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                document.directory() + "narrative/summary.adoc: not UTF-8")), result, "the fragment");
    }

    @Test
    @DisplayName("GDR-FORMS-4: a revision that cannot be resolved is passed on")
    void unknownRevision(@InMemory Repository repository) {
        var result = check(perDocument()).check(repository, "refs/heads/" + word());

        assertEquals(new FormsResult.PassedOn(new StoreResult.PassedOn(
                GitOutcome.NOT_FOUND)), result.outcome(), "the Git outcome of the snapshot");
    }

    @Test
    @DisplayName("GDR-GEN-3: a template must be given exactly when the kind declares one")
    void templatePresence() {
        var schema = schema();
        var template = DocumentTemplateTest.template(TEMPLATE);
        var perDocument = perDocument();
        var numbered = numbered(false);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> new OutputCheck(perDocument, schema, null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new OutputCheck(numbered, schema, template)));
    }

    private static void assertMismatch(Document document, FormsResult<List<String>> result) {
        assertAll(
                () -> assertEquals(FormsResult.of(FormsOutcome.MISMATCH, "output mismatch: " + document.outputPath()),
                        result, "the output path only"),
                () -> assertFalse(Objects.requireNonNull(result.detail()).contains(document.title()), "no data"));
    }
}
