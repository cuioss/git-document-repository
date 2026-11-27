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
import static de.cuioss.git.document.forms.DocumentTransitionsTest.PASSWORD;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.commit;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.data;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.directory;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.document;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.files;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.perDocumentDefinition;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.seedMain;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.state;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.tip;
import static de.cuioss.git.document.forms.DocumentTransitionsTest.value;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.forms.DocumentTransitionsTest.Fixture;
import de.cuioss.git.document.forms.DocumentTransitionsTest.TestSigning;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.git.document.testing.LocalRemote;
import de.cuioss.git.document.testing.Remote;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The resumption of a release transition whose save succeeded and whose release did not (GDR-FORMS-5), against a
 * {@code git-testing} local remote reached through {@link ScriptedOperations}: for each failure of the first release
 * that needs a scripted transport (the push of {@code main} unreachable, its lease lost on every attempt, its push
 * rejected, and an incomplete push of the tag) a retry through {@link DocumentTransitions} ends with the release on
 * {@code main} and its tag; a pinned release that only completes an earlier, incomplete release of an older state is
 * {@link FormsOutcome#SAVED_NOT_RELEASED}, and the retry then releases the saved state. The refused signing and the pin
 * refusal of a moved branch are in {@link DocumentTransitionsTest}.
 * <p>
 * The workflow, the states and the outcomes are the contract and kept as literals; ULIDs are generated.
 */
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@DisplayName("DocumentTransitions, the retry of a release (GDR-FORMS-5)")
class DocumentTransitionsReleaseRetryTest {

    /** The attempts of a save and a release in the configuration of the fixture. */
    private static final int ATTEMPTS = 3;

    private static final Author AUTHOR = Author.of("p-author");

    @TempDir
    Path temp;

    private Fixture open(LocalRemote remote, ScriptedOperations operations) {
        return DocumentTransitionsTest.open(temp, remote, perDocumentDefinition(Workflow.defaultWorkflow()),
                new TestSigning(Integer.MAX_VALUE), DocumentTransitionsTest.STEP_UP, operations);
    }

    private static DocumentTransitions.Result.Performed performed(DocumentTransitions.Result result) {
        return assertInstanceOf(DocumentTransitions.Result.Performed.class, result,
                () -> "performed: " + result.outcome() + " " + result.detail());
    }

    /** A document in review on the remote, with {@code main} seeded; returns its key. */
    private static DocumentKey inReview(LocalRemote remote) {
        var main = seedMain(remote);
        var key = document();
        commit(remote.repository(), StoreReferences.target(key),
                Map.of(directory(key) + DocumentLayout.DOCUMENT_FILE, data("First", "owner", "review")), main);
        return key;
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(ScriptedOperations.Call.PUSH_MAIN, GitOutcome.UNREACHABLE, 1,
                        new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.UNREACHABLE))),
                Arguments.of(ScriptedOperations.Call.PUSH_MAIN, GitOutcome.LEASE_REFUSED, ATTEMPTS,
                        new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.REJECTED))),
                Arguments.of(ScriptedOperations.Call.PUSH_MAIN, GitOutcome.REJECTED, 1,
                        new FormsResult.PassedOn(new StoreResult.PassedOn(GitOutcome.REJECTED))),
                Arguments.of(ScriptedOperations.Call.PUSH_TAG, GitOutcome.UNREACHABLE, 1,
                        new FormsResult.PassedOn(StoreOutcome.INCOMPLETE)));
    }

    @ParameterizedTest(name = "{0} {1} x{2}")
    @MethodSource("failures")
    @DisplayName("GDR-FORMS-5: a release that failed after its save is released by the retry, without a second save")
    void retryReleasesTheSavedState(ScriptedOperations.Call call, GitOutcome failure, int times,
            FormsResult.Outcome expected, @Remote LocalRemote remote) {
        var key = inReview(remote);
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        var operations = new ScriptedOperations().fail(call, failure, times);
        try (var fixture = open(remote, operations)) {
            value(fixture.writer().fetch());

            var first = fixture.perform(AUTHOR, MFA, key, "approve", List.of());
            var saved = tip(remote.repository(), target);
            var tagAfterFirst = tip(remote.repository(), StoreReferences.releaseTag(key, 1));
            var retried = performed(fixture.perform(AUTHOR, MFA, key, "approve", List.of()));

            var mainTip = Objects.requireNonNull(tip(remote.repository(), StoreReferences.MAIN));
            assertAll(
                    () -> assertEquals(times, operations.failed(call), "the scripted failures happened"),
                    () -> assertEquals(expected, first.outcome(), "the failure of the first release, passed on"),
                    () -> assertNull(tagAfterFirst, "no tag after the first release"),
                    () -> assertEquals(saved, tip(remote.repository(), target), "no second save"),
                    () -> assertEquals("approved", state(remote.repository(), StoreReferences.MAIN, document),
                            "the saved state is on main"),
                    () -> assertEquals(mainTip.name(), retried.commitId(), "the release commit is the tip of main"),
                    () -> assertEquals(mainTip, tip(remote.repository(), StoreReferences.releaseTag(key, 1)),
                            "the tag v1 points at it"));
        }
    }

    @Test
    @DisplayName("GDR-FORMS-5: a release that only completes an earlier one is SAVED_NOT_RELEASED, the retry releases")
    void savedNotReleased(@Remote LocalRemote remote) {
        var key = inReview(remote);
        var target = StoreReferences.target(key);
        var document = directory(key) + DocumentLayout.DOCUMENT_FILE;
        var operations = new ScriptedOperations().fail(ScriptedOperations.Call.PUSH_TAG, GitOutcome.UNREACHABLE, 1);
        try (var fixture = open(remote, operations)) {
            value(fixture.writer().fetch());
            var incomplete = fixture.perform(AUTHOR, MFA, key, "approve", List.of());
            var firstRelease = Objects.requireNonNull(tip(remote.repository(), StoreReferences.MAIN));
            performed(fixture.perform(AUTHOR, PASSWORD, key, "revise",
                    List.of(new TreeEdit.Put(document, data("Second", "owner", null)))));
            performed(fixture.perform(AUTHOR, PASSWORD, key, "submit", List.of()));

            var notReleased = fixture.perform(AUTHOR, MFA, key, "approve", List.of());
            var saved = tip(remote.repository(), target);
            var tagV1 = tip(remote.repository(), StoreReferences.releaseTag(key, 1));
            var retried = performed(fixture.perform(AUTHOR, MFA, key, "approve", List.of()));

            assertAll(
                    () -> assertEquals(new FormsResult.PassedOn(StoreOutcome.INCOMPLETE), incomplete.outcome(),
                            "the first release lacks its tag"),
                    () -> assertEquals(new DocumentTransitions.Result.NotPerformed(FormsOutcome.SAVED_NOT_RELEASED,
                                    DocumentTransitions.SAVED_NOT_RELEASED_DETAIL), notReleased,
                            "the pinned release only completed the earlier one"),
                    () -> assertEquals(firstRelease, tagV1, "v1 holds the earlier state"),
                    () -> assertEquals(saved, tip(remote.repository(), target), "the retry writes no second save"),
                    () -> assertEquals(Objects.requireNonNull(tip(remote.repository(), StoreReferences.MAIN)).name(),
                            retried.commitId(), "the retry releases onto main"),
                    () -> assertEquals(ObjectId.fromString(retried.commitId()),
                            tip(remote.repository(), StoreReferences.releaseTag(key, 2)), "as v2"),
                    () -> assertEquals(files(remote.repository(), target).get(document),
                            files(remote.repository(), StoreReferences.MAIN).get(document),
                            "main holds the saved state"));
        }
    }
}
