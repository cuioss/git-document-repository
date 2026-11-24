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

import static de.cuioss.git.document.forms.DocumentTransitionsTest.ISSUER;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.NOW;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.bytes;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.data;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.directory;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.LogRecord;


import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.IdentityClaims;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No secret reaches a log record or a detail of a transition (GDR-NFR-3): with every log record captured down to
 * trace, a transition performed, refused for missing fields, refused as invalid and refused for step-up names no
 * pseudonym, no {@code amr} or {@code acr} value, no value of the data and no JSON Pointer; the findings travel as data
 * of the result only.
 * <p>
 * The pseudonym, the claim values and the data values are generated, long enough to appear nowhere by chance; the
 * outcomes are the specified vocabulary.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.TRACE)
@DisplayName("Transitions keep secrets out of logs and details (GDR-NFR-3)")
class TransitionSecretsTest {

    private static final TypedGenerator<String> WORDS =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz", 16, 24));

    @TempDir
    Path temp;

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
    @DisplayName("GDR-NFR-3: no transition outcome or log line carries a pseudonym, a claim, a data value or a pointer")
    void nothingLeaks(@Remote LocalRemote remote) {
        DocumentTransitionsTest.seedMain(remote);
        var key = DocumentTransitionsTest.document();
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        var pseudonym = "p" + WORDS.next();
        var author = Author.of(pseudonym);
        var title = WORDS.next();
        var owner = WORDS.next();
        var tooLong = WORDS.next() + "x".repeat(40);
        var method = WORDS.next();
        var acr = "urn:" + WORDS.next();
        var weak = new IdentityClaims(ISSUER, NOW.minusSeconds(60), null, List.of(method));
        var strong = new IdentityClaims(ISSUER, NOW.minusSeconds(60), acr, List.of(method, "mfa"));
        List<String> details = new ArrayList<>();

        try (var fixture = DocumentTransitionsTest.open(temp, remote,
                     DocumentTransitionsTest.perDocumentDefinition(Workflow.defaultWorkflow()),
                     new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE))) {
            var missing = fixture.perform(author, weak, key, "submit",
                    List.of(new TreeEdit.Put(document, data(title, null, null))));
            var invalid = fixture.perform(author, weak, key, "submit",
                    List.of(new TreeEdit.Put(document, data(tooLong, owner, null))));
            var edited = fixture.perform(author, weak, key, "edit", List.of(new TreeEdit.Put(document,
                    data(title, owner, null)), new TreeEdit.Put(directory(key) + "narrative/summary.adoc",
                    bytes(WORDS.next() + "\n"))));
            var submitted = fixture.perform(author, weak, key, "submit", List.of());
            var stepUp = fixture.perform(author, weak, key, "approve", List.of());
            var approved = fixture.perform(author, strong, key, "approve", List.of());
            for (DocumentTransitions.Result result : List.of(missing, invalid, edited, submitted, stepUp, approved)) {
                result.detail().ifPresent(details::add);
            }

            List<String> texts = new ArrayList<>(logTexts());
            texts.addAll(details);
            assertAll(
                    () -> assertEquals(FormsOutcome.MANDATORY_FIELDS_MISSING, missing.outcome(), "missing"),
                    () -> assertEquals(FormsOutcome.INVALID, invalid.outcome(), "invalid"),
                    () -> assertEquals(FormsOutcome.OK, edited.outcome(), "edited"),
                    () -> assertEquals(FormsOutcome.OK, submitted.outcome(), "submitted"),
                    () -> assertEquals(FormsOutcome.STEP_UP_REQUIRED, stepUp.outcome(), "step-up"),
                    () -> assertEquals(FormsOutcome.OK, approved.outcome(), "approved"),
                    () -> assertFalse(details.isEmpty(), "the refusals carry details"),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            GitFormsLogMessages.INFO.TRANSITION_NOT_PERFORMED.resolveIdentifierString()),
                    () -> LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                            GitFormsLogMessages.INFO.TRANSITION_PERFORMED.resolveIdentifierString()));
            for (String secret : List.of(pseudonym, title, owner, tooLong, method, acr, "/owner", "/title")) {
                assertTrue(texts.stream().noneMatch(text -> text.contains(secret)),
                        () -> "a log record or a detail carries a pseudonym, a claim, a data value or a pointer");
            }
        }
    }

    @Test
    @DisplayName("GDR-NFR-3: a refused transition is logged with the mechanism and the outcome only")
    void refusalLoggedByOutcome(@Remote LocalRemote remote) {
        DocumentTransitionsTest.seedMain(remote);
        DocumentKey key = DocumentTransitionsTest.document();
        var author = Author.of("p" + WORDS.next());
        try (var fixture = DocumentTransitionsTest.open(temp, remote,
                     DocumentTransitionsTest.perDocumentDefinition(Workflow.defaultWorkflow()),
                     new DocumentTransitionsTest.TestSigning(Integer.MAX_VALUE))) {
            fixture.perform(author, DocumentTransitionsTest.PASSWORD, key, "approve", List.of());

            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.INFO,
                    "Did not perform a transition on the mechanism none: TRANSITION_REFUSED");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.INFO,
                    GitFormsLogMessages.INFO.TRANSITION_NOT_PERFORMED.resolveIdentifierString());
        }
    }
}
