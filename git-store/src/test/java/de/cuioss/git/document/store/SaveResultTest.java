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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The invariants of the three variants of {@link SaveResult} and their conversion by {@link SaveResult#toResult()}
 * (GDR-STORE-3, GDR-STORE-4): a saved commit id is a full object id, the conflicting paths are sorted, distinct and
 * never empty and travel as data only, and a non-success carries an outcome other than OK and a detail.
 * <p>
 * Ids, paths and details are generated; the detail of a conflict is the specified vocabulary and kept as a literal.
 */
@EnableGeneratorController
@DisplayName("SaveResult (GDR-STORE-3, GDR-STORE-4)")
class SaveResultTest {

    private static String commitId() {
        return Generators.strings("0123456789abcdef", 40, 40).next();
    }

    private static String path() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 4, 12).next();
    }

    private static String detail() {
        return Generators.letterStrings(4, 24).next();
    }

    @Nested
    @DisplayName("Saved")
    class Saved {

        @Test
        @DisplayName("GDR-STORE-3: a saved result is OK without detail and converts to the commit id")
        void saved() {
            var commitId = commitId();

            var saved = new SaveResult.Saved(commitId);

            assertAll(
                    () -> assertEquals(StoreOutcome.OK, saved.outcome(), "OK"),
                    () -> assertEquals(Optional.empty(), saved.detail(), "no detail"),
                    () -> assertEquals(StoreResult.ok(commitId), saved.toResult(), "the commit id"));
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", "abc", "0123456789abcdef0123456789abcdef0123456", "refs/heads/main"})
        @DisplayName("GDR-STORE-3: a saved commit id must be a full object id")
        void refusesInvalidCommitId(String invalid) {
            assertThrows(IllegalArgumentException.class, () -> new SaveResult.Saved(invalid));
        }
    }

    @Nested
    @DisplayName("Conflicting")
    class Conflicting {

        @Test
        @DisplayName("GDR-STORE-4: the paths are sorted, distinct and copied; the detail names their number only")
        void sortedDistinctCopied() {
            var first = path() + "a";
            var second = first + "b";
            var input = new ArrayList<>(List.of(second, first, second));

            var conflicting = new SaveResult.Conflicting(input);
            input.clear();

            var detail = conflicting.detail().orElseThrow();
            var paths = conflicting.paths();
            assertAll(
                    () -> assertEquals(List.of(first, second), conflicting.paths(), "sorted and distinct"),
                    () -> assertEquals(new StoreResult.PassedOn(GitOutcome.CONFLICT), conflicting.outcome(),
                            "the passed-on CONFLICT"),
                    () -> assertEquals("2 conflicting paths", detail, "the number of paths"),
                    () -> assertFalse(detail.contains(first), "no path in the detail"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> paths.add(first), "unmodifiable"));
        }

        @Test
        @DisplayName("GDR-STORE-4: the conversion keeps outcome and detail and drops the paths")
        void toResultDropsPaths() {
            var conflicting = new SaveResult.Conflicting(List.of(path()));

            assertEquals(new StoreResult<String>(new StoreResult.PassedOn(GitOutcome.CONFLICT), null,
                    "1 conflicting paths"), conflicting.toResult(), "outcome and detail only");
        }

        @Test
        @DisplayName("GDR-STORE-4: a conflict names at least one repository-relative path")
        void refusesInvalidPaths() {
            List<String> withNull = new ArrayList<>();
            withNull.add(null);
            List<String> none = List.of();
            List<String> empty = List.of("");
            List<String> absolute = List.of("/" + path());
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> new SaveResult.Conflicting(none),
                            "no path"),
                    () -> assertThrows(IllegalArgumentException.class, () -> new SaveResult.Conflicting(empty),
                            "an empty path"),
                    () -> assertThrows(IllegalArgumentException.class, () -> new SaveResult.Conflicting(absolute),
                            "an absolute path"),
                    () -> assertThrows(NullPointerException.class, () -> new SaveResult.Conflicting(withNull),
                            "a null path"));
        }
    }

    @Nested
    @DisplayName("NotSaved")
    class NotSaved {

        @Test
        @DisplayName("GDR-STORE-3: a non-success keeps its outcome and detail and converts to them")
        void notSaved() {
            var detail = detail();
            var outcome = new StoreResult.PassedOn(GitOutcome.UNREACHABLE);

            var notSaved = new SaveResult.NotSaved(outcome, detail);

            assertAll(
                    () -> assertEquals(Optional.of(detail), notSaved.detail(), "the detail"),
                    () -> assertEquals(new StoreResult<String>(outcome, null, detail), notSaved.toResult(),
                            "the plain result"));
        }

        @Test
        @DisplayName("GDR-STORE-3: a non-success of a store result keeps its outcome and detail")
        void ofStoreResult() {
            var detail = detail();

            var notSaved = SaveResult.NotSaved.of(StoreResult.of(StoreOutcome.MALFORMED, detail));
            var success = StoreResult.ok(detail);

            assertAll(
                    () -> assertEquals(new SaveResult.NotSaved(StoreOutcome.MALFORMED, detail), notSaved, "kept"),
                    () -> assertThrows(IllegalArgumentException.class, () -> SaveResult.NotSaved.of(success),
                            "a success is refused"));
        }

        @Test
        @DisplayName("GDR-STORE-3: a non-success is never OK and needs a detail")
        void refusesInvalid() {
            var outcome = new StoreResult.PassedOn(GitOutcome.REJECTED);
            var detail = detail();
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new SaveResult.NotSaved(StoreOutcome.OK, detail), "OK"),
                    () -> assertThrows(IllegalArgumentException.class, () -> new SaveResult.NotSaved(outcome, " "),
                            "a blank detail"));
        }
    }
}
