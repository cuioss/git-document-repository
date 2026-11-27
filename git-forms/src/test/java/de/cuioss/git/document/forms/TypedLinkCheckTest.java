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

import static de.cuioss.git.document.forms.DocumentTransitionsTest.MFA;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.NOW;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.bytes;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.directory;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.document;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.state;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.tip;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.LogRecord;
import java.util.stream.Collectors;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.store.Ulid;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The typed links of GDR-FORMS-8 at the requirement level, checked by a save through {@link DocumentTransitions} over a
 * {@code git-testing} local remote: a link to an existing document passes and one to a missing document is refused
 * {@code INVALID} at its field with nothing saved; a fragment is stripped, so a link with one passes when its document
 * exists and is refused when it does not; a retired catalog entry resolves and a missing one is refused; a link field
 * declared under {@code $ref} and under {@code allOf} is checked like a plain one; and no link text appears in a detail
 * of a result or in a log record.
 * <p>
 * The schema, the catalog and the link forms are literals, being the contract; the ids of the documents are generated
 * ULIDs, unique enough to appear in no text by chance.
 */
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("Typed links checked on save (GDR-FORMS-8)")
class TypedLinkCheckTest {

    /**
     * The schema of the item with three link fields: {@code related} declared in place, {@code parent} through a
     * {@code $ref} and {@code reviewer} in a member of {@code allOf}.
     */
    private static final String SCHEMA = """
            {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object",
             "$defs": {"link": {"type": "string", "format": "gdr-link"}},
             "properties": {"title": {"type": "string"}, "owner": {"type": "string"}, "state": {"type": "string"},
                            "related": {"type": "string", "format": "gdr-link"},
                            "parent": {"$ref": "#/$defs/link"}},
             "allOf": [{"properties": {"reviewer": {"type": "string", "format": "gdr-link"}}}],
             "required": ["title", "owner"]}
            """;

    /** The catalog of the tests: {@code team} holds {@code payments} and the retired {@code legacy}. */
    private static final Map<String, Boolean> TEAMS = Map.of("payments", false, "legacy", true);

    /** Resolves an entry of {@link #TEAMS}, a retired one included, as the contract of the resolver requires. */
    private static final CatalogResolver CATALOGS = (catalog, id) -> StoreResult.ok("team".equals(catalog)
            && TEAMS.containsKey(id));

    @TempDir
    Path temp;

    private static FormSchema schema() {
        return Objects.requireNonNull(FormSchema.parse("schemas/item.schema.json", bytes(SCHEMA)).value());
    }

    private static DocumentTransitions.Definition definition() {
        return new DocumentTransitions.Definition(DocumentTransitionsTest.perDocument(), schema(),
                Workflow.defaultWorkflow(), DocumentTransitionsTest.template());
    }

    /** A writer over the remote and the transitions of the kind whose links resolve over its index and {@link #CATALOGS}. */
    private record Linking(DocumentTransitionsTest.Fixture fixture, DocumentTransitions transitions)
            implements AutoCloseable {

        /** Saves a complete item with the given link fields by {@code submit}, the save that checks the links. */
        DocumentTransitions.Result submit(DocumentKey key, Map<String, String> links) {
            Map<String, @Nullable Object> data = new LinkedHashMap<>();
            data.put("title", "Linking");
            data.put("owner", "owner");
            data.putAll(links);
            List<TreeEdit> edits = List.of(
                    new TreeEdit.Put(directory(key) + DocumentLayout.DOCUMENT_FILE,
                            new YamlDocumentCodec(schema()).encode(data)),
                    new TreeEdit.Put(directory(key) + "narrative/summary.adoc", bytes("A summary.\n")));
            return transitions.perform(Author.of("p-author"), MFA, key, "submit",
                    DocumentTransitions.Change.of(edits, "Submit"));
        }

        @Override
        public void close() {
            fixture.close();
        }
    }

    private Linking open(LocalRemote remote) {
        var definition = definition();
        var fixture = DocumentTransitionsTest.open(temp, remote, definition,
                new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE));
        var links = DocumentTransitionsTest.links(temp, remote, definition.kind(), fixture.index(), CATALOGS);
        return new Linking(fixture, new DocumentTransitions(fixture.writer(), fixture.bareClone(), definition, links,
                DocumentTransitionsTest.STEP_UP, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    private static DocumentTransitions.Result.Performed performed(DocumentTransitions.Result result) {
        return assertInstanceOf(DocumentTransitions.Result.Performed.class, result,
                () -> "performed: " + result.outcome() + " " + result.detail());
    }

    private static DocumentTransitions.Result.Refused refused(DocumentTransitions.Result result) {
        return assertInstanceOf(DocumentTransitions.Result.Refused.class, result,
                () -> "refused: " + result.outcome() + " " + result.detail());
    }

    /** Saves a document that later links target, so that it exists on its working branch and in the index. */
    private static DocumentKey existing(Linking linking) {
        var target = document();
        performed(linking.submit(target, Map.of()));
        return target;
    }

    private static String saved(LocalRemote remote, DocumentKey key) {
        return String.valueOf(state(remote.repository(), StoreReferences.target(key),
                directory(key) + DocumentLayout.DOCUMENT_FILE));
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

    @Test
    @DisplayName("GDR-FORMS-8: a link to an existing document passes, one to a missing document is refused INVALID")
    void documentLinks(@Remote LocalRemote remote) {
        DocumentTransitionsTest.seedMain(remote);
        var linking = document();
        var dangling = document();
        var missing = Ulid.next();
        try (var fixture = open(remote)) {
            var target = existing(fixture);

            var passes = fixture.submit(linking, Map.of("related", "register:item/" + target.id()));
            var refusal = refused(fixture.submit(dangling, Map.of("related", "register:item/" + missing)));

            assertAll(
                    () -> performed(passes),
                    () -> assertEquals("review", saved(remote, linking), "the linking document is saved"),
                    () -> assertEquals(FormsOutcome.INVALID, refusal.outcome(), "invalid"),
                    () -> assertEquals(new ValidationResult.Invalid(List.of(new Violation("/related",
                            TransitionStep.NO_SUCH_TARGET))), refusal.findings(), "the pointer of the field"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.target(dangling)), "nothing saved"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-8: a link with a fragment passes when its document exists and is refused when it does not")
    void fragmentLinks(@Remote LocalRemote remote) {
        DocumentTransitionsTest.seedMain(remote);
        var linking = document();
        var dangling = document();
        var missing = Ulid.next();
        try (var fixture = open(remote)) {
            var target = existing(fixture);

            var passes = fixture.submit(linking, Map.of("related", "register:item/" + target.id()
                    + "#narrative/summary"));
            var refusal = refused(fixture.submit(dangling, Map.of("related", "register:item/" + missing
                    + "#narrative/summary")));

            assertAll(
                    () -> performed(passes),
                    () -> assertEquals(new ValidationResult.Invalid(List.of(new Violation("/related",
                            TransitionStep.NO_SUCH_TARGET))), refusal.findings(), "the document of the fragment"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.target(dangling)), "nothing saved"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-8: a retired catalog entry resolves, a catalog entry that does not exist is refused")
    void catalogLinks(@Remote LocalRemote remote) {
        DocumentTransitionsTest.seedMain(remote);
        var retired = document();
        var active = document();
        var dangling = document();
        try (var fixture = open(remote)) {
            var toRetired = fixture.submit(retired, Map.of("related", "team:legacy"));
            var toActive = fixture.submit(active, Map.of("related", "team:payments"));
            var refusal = refused(fixture.submit(dangling, Map.of("related", "team:unknown")));

            assertAll(
                    () -> assertTrue(TEAMS.get("legacy"), "the entry is retired"),
                    () -> performed(toRetired),
                    () -> performed(toActive),
                    () -> assertEquals(new ValidationResult.Invalid(List.of(new Violation("/related",
                            TransitionStep.NO_SUCH_TARGET))), refusal.findings(), "the missing entry"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.target(dangling)), "nothing saved"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-8: link fields under $ref and allOf are checked, existing targets pass, missing are refused")
    void linksUnderReferenceAndAllOf(@Remote LocalRemote remote) {
        DocumentTransitionsTest.seedMain(remote);
        var linking = document();
        var dangling = document();
        try (var fixture = open(remote)) {
            var target = existing(fixture);

            var passes = fixture.submit(linking, Map.of("parent", "register:item/" + target.id(),
                    "reviewer", "team:payments"));
            var refusal = refused(fixture.submit(dangling, Map.of("parent", "register:item/" + Ulid.next(),
                    "reviewer", "team:unknown")));

            var invalid = assertInstanceOf(ValidationResult.Invalid.class, refusal.findings());
            assertAll(
                    () -> performed(passes),
                    () -> assertEquals(Set.of("/parent", "/reviewer"), invalid.violations().stream()
                            .map(Violation::pointer).collect(Collectors.toSet()), "both fields, by their pointers"),
                    () -> assertTrue(invalid.violations().stream().allMatch(violation -> TransitionStep.NO_SUCH_TARGET
                            .equals(violation.message())), "each a missing target"),
                    () -> assertNull(tip(remote.repository(), StoreReferences.target(dangling)), "nothing saved"));
        }
    }

    /** The default states with a self-loop {@code keep} on {@code review}, whose save of the unchanged state runs no step. */
    private static final String SELF_LOOP_WORKFLOW = """
            states: [draft, review, approved]
            transitions:
              - { name: edit,    from: draft,  to: draft,    mechanism: intermediate-save }
              - { name: submit,  from: draft,  to: review,   mechanism: save }
              - { name: keep,    from: review, to: review,   mechanism: save }
              - { name: approve, from: review, to: approved, mechanism: release }
            """;

    @Test
    @DisplayName("GDR-FORMS-8: a catalog resolver throwing on an unchanged save ends it FAILED, naming the class only")
    void throwingResolverOnUnchangedSave(@Remote LocalRemote remote) {
        DocumentTransitionsTest.seedMain(remote);
        var key = document();
        var secret = "resolver-" + Ulid.next();
        AtomicBoolean throwing = new AtomicBoolean();
        CatalogResolver resolver = (catalog, id) -> {
            if (throwing.get()) {
                throw new IllegalStateException(secret);
            }
            return CATALOGS.resolve(catalog, id);
        };
        var workflow = Objects.requireNonNull(Workflow.parse("workflows/self-loop.yaml", bytes(SELF_LOOP_WORKFLOW))
                .value());
        var definition = new DocumentTransitions.Definition(DocumentTransitionsTest.perDocument(), schema(), workflow,
                DocumentTransitionsTest.template());
        try (var fixture = DocumentTransitionsTest.open(temp, remote, definition,
                     new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE))) {
            var links = DocumentTransitionsTest.links(temp, remote, definition.kind(), fixture.index(), resolver);
            var transitions = new DocumentTransitions(fixture.writer(), fixture.bareClone(), definition, links,
                    DocumentTransitionsTest.STEP_UP, Clock.fixed(NOW, ZoneOffset.UTC));
            var linking = new Linking(fixture, transitions);
            performed(linking.submit(key, Map.of("related", "team:payments")));
            var savedTip = tip(remote.repository(), StoreReferences.target(key));
            throwing.set(true);

            var result = transitions.perform(Author.of("p-author"), MFA, key, "keep",
                    DocumentTransitions.Change.of(List.of(), "Keep"));

            var notPerformed = assertInstanceOf(DocumentTransitions.Result.NotPerformed.class, result,
                    () -> "not performed: " + result.outcome() + " " + result.detail());
            assertAll(
                    () -> assertEquals(new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.FAILED)),
                            notPerformed.outcome(), "the passed-on FAILED, as for a changed save"),
                    () -> assertEquals(Optional.of(StoreReferences.target(key) + ": step: "
                                    + IllegalStateException.class.getName()), result.detail(),
                            "the detail names the class of the exception only"),
                    () -> assertTrue(logTexts().stream().noneMatch(text -> text.contains(secret)),
                            "no log record carries the message of the exception"),
                    () -> assertEquals(savedTip, tip(remote.repository(), StoreReferences.target(key)),
                            "the target branch stays at the submitted save"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-8: no link text appears in a detail of a result or in a log record")
    void linkTextsStayOutOfDetailsAndLogs(@Remote LocalRemote remote) {
        DocumentTransitionsTest.seedMain(remote);
        var missing = Ulid.next();
        List<String> linkTexts = new ArrayList<>();
        List<String> details = new ArrayList<>();
        try (var fixture = open(remote)) {
            var target = existing(fixture);
            linkTexts.add("register:item/" + target.id());
            linkTexts.add("register:item/" + missing + "#narrative/summary");
            linkTexts.add("team:unknown");
            linkTexts.add("register:other/" + Ulid.next());

            List<DocumentTransitions.Result> results = List.of(
                    fixture.submit(document(), Map.of("related", linkTexts.getFirst())),
                    fixture.submit(document(), Map.of("related", linkTexts.get(1))),
                    fixture.submit(document(), Map.of("reviewer", linkTexts.get(2))),
                    fixture.submit(document(), Map.of("parent", linkTexts.get(3))));
            results.forEach(result -> result.detail().ifPresent(details::add));

            List<String> texts = new ArrayList<>(logTexts());
            texts.addAll(details);
            // the id of the existing target is the key of a saved document, which the store may name; the missing id
            // is no key of any document, so it appears only as part of a link
            List<String> secrets = new ArrayList<>(linkTexts);
            secrets.add(missing);
            assertAll(
                    () -> performed(results.getFirst()),
                    () -> assertEquals(FormsOutcome.INVALID, results.get(1).outcome(), "a missing document"),
                    () -> assertEquals(FormsOutcome.INVALID, results.get(2).outcome(), "a missing catalog entry"),
                    () -> assertEquals(FormsOutcome.INVALID, results.get(3).outcome(), "a kind the configuration lacks"),
                    () -> assertFalse(details.isEmpty(), "the refusals carry details"),
                    () -> assertFalse(logTexts().isEmpty(), "log records were captured"),
                    () -> assertTrue(secrets.stream().noneMatch(secret -> texts.stream()
                                    .anyMatch(text -> text.contains(secret))),
                            "no detail and no log record carries a link text or the id of a link"));
        }
    }
}
