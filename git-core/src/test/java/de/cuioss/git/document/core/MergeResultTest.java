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
package de.cuioss.git.document.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The variant invariants of the merge result (GDR-CORE-6) and its GDR-GEN-3 shape: {@link MergeResult.Merged} carries
 * a full tree id, {@link MergeResult.Conflicting} at least one path, sorted, distinct and copied, with a detail that
 * names their number but no path, and {@link MergeResult.NotMerged} a non-success with a detail; {@link
 * MergeResult#toResult()} maps each variant to the matching {@link GitResult}.
 */
@EnableGeneratorController
@DisplayName("MergeResult (GDR-CORE-6, GDR-GEN-3)")
class MergeResultTest {

    /** Generates a full object id, as the hex name of the blob id of generated content. */
    private static String fullId() {
        return InCoreWriterTest.blobId(InCoreWriterTest.content()).name();
    }

    /** Generates repository-relative paths, with a nested one among them. */
    private static List<String> paths() {
        List<String> paths = new ArrayList<>();
        int count = Generators.integers(1, 5).next();
        while (paths.size() < count) {
            paths.add(TestCommits.name());
        }
        paths.add(TestCommits.name() + "/" + TestCommits.name());
        return paths;
    }

    /** Every outcome except {@link GitOutcome#OK}. */
    private static Stream<GitOutcome> nonSuccesses() {
        return Arrays.stream(GitOutcome.values()).filter(outcome -> outcome != GitOutcome.OK);
    }

    /** Passes {@code null} where the signature forbids it, to exercise the null checks. */
    @SuppressWarnings("unchecked")
    private static <T> T nullValue() {
        return (T) null;
    }

    @Nested
    @DisplayName("Merged")
    class Merged {

        @Test
        @DisplayName("GDR-CORE-6: a clean merge carries the full tree id, outcome OK and no detail")
        void carriesTreeId() {
            var treeId = fullId();

            var merged = new MergeResult.Merged(treeId);

            assertAll(
                    () -> assertEquals(treeId, merged.treeId(), "the tree id is kept"),
                    () -> assertEquals(GitOutcome.OK, merged.outcome(), "a clean merge is OK"),
                    () -> assertEquals(Optional.empty(), merged.detail(), "a success carries no detail"));
        }

        @Test
        @DisplayName("GDR-CORE-6: a clean merge refuses an abbreviated, non-hex or null tree id")
        void refusesNonId() {
            var abbreviated = fullId().substring(0, 12);
            var nonHex = TestCommits.name();
            String nullId = nullValue();

            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> new MergeResult.Merged(abbreviated),
                            "an abbreviated id"),
                    () -> assertThrows(IllegalArgumentException.class, () -> new MergeResult.Merged(nonHex),
                            "a name that is no id"),
                    () -> assertThrows(NullPointerException.class, () -> new MergeResult.Merged(nullId),
                            "a null id"));
        }
    }

    @Nested
    @DisplayName("Conflicting")
    class Conflicting {

        @Test
        @DisplayName("GDR-CORE-6: the conflicting paths are sorted and distinct, outcome CONFLICT")
        void sortsAndDeduplicates() {
            var paths = paths();
            List<String> input = new ArrayList<>(paths);
            input.addAll(paths);
            input = input.reversed();

            var conflicting = new MergeResult.Conflicting(input);

            assertAll(
                    () -> assertEquals(List.copyOf(new TreeSet<>(paths)), conflicting.paths(),
                            "the paths are sorted, each once"),
                    () -> assertEquals(GitOutcome.CONFLICT, conflicting.outcome(), "a conflict is CONFLICT"));
        }

        @Test
        @DisplayName("GDR-CORE-6: the paths are copied; the caller's list and the returned list change nothing")
        void copiesDefensively() {
            var paths = paths();
            List<String> input = new ArrayList<>(paths);
            var conflicting = new MergeResult.Conflicting(input);
            var expected = List.copyOf(new TreeSet<>(paths));

            input.add(TestCommits.name());
            var returned = conflicting.paths();
            var added = TestCommits.name();

            assertAll(
                    () -> assertEquals(expected, conflicting.paths(), "a change of the input changes nothing"),
                    () -> assertThrows(UnsupportedOperationException.class, () -> returned.add(added),
                            "the returned list is unmodifiable"),
                    () -> assertThrows(UnsupportedOperationException.class, returned::clear,
                            "the returned list cannot be cleared"));
        }

        @Test
        @DisplayName("GDR-NFR-3: the detail names the number of conflicting paths and no path")
        void detailCarriesCountOnly() {
            var paths = paths();

            var conflicting = new MergeResult.Conflicting(paths);

            var detail = conflicting.detail().orElseThrow();
            assertAll(
                    () -> assertEquals(paths.size() + " conflicting paths", detail, "the detail names the count"),
                    () -> assertFalse(paths.stream().anyMatch(detail::contains), "the detail names no path"));
        }

        @Test
        @DisplayName("GDR-CORE-6: a conflict refuses no path, an empty or absolute path and null")
        void refusesInvalidPaths() {
            List<String> withNull = new ArrayList<>(paths());
            withNull.add(null);
            List<String> noPaths = List.of();
            List<String> emptyPath = List.of("");
            List<String> absolutePath = List.of("/" + TestCommits.name());
            List<String> nullList = nullValue();

            assertAll(Stream.<Executable>of(
                    () -> assertThrows(IllegalArgumentException.class, () -> new MergeResult.Conflicting(noPaths),
                            "an empty list"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new MergeResult.Conflicting(emptyPath), "an empty path"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new MergeResult.Conflicting(absolutePath), "an absolute path"),
                    () -> assertThrows(NullPointerException.class, () -> new MergeResult.Conflicting(withNull),
                            "a null path"),
                    () -> assertThrows(NullPointerException.class, () -> new MergeResult.Conflicting(nullList),
                            "a null list")));
        }

        @Test
        @DisplayName("GDR-CORE-6: a path of whitespace only is a valid file name and is kept")
        void keepsWhitespaceOnlyPath() {
            var conflicting = new MergeResult.Conflicting(List.of(" ", "\t"));

            assertEquals(List.of("\t", " "), conflicting.paths(), "a whitespace-only path is a path");
        }
    }

    @Nested
    @DisplayName("NotMerged")
    class NotMerged {

        @Test
        @DisplayName("GDR-GEN-3: a merge that did not succeed carries its outcome and its detail")
        void carriesOutcomeAndDetail() {
            var reason = TestCommits.name();

            assertAll(nonSuccesses().<Executable>map(outcome -> () -> {
                var notMerged = new MergeResult.NotMerged(outcome, reason);
                assertAll(
                        () -> assertEquals(outcome, notMerged.outcome(), "the outcome is kept"),
                        () -> assertEquals(Optional.of(reason), notMerged.detail(), "the detail is the reason"));
            }));
        }

        @Test
        @DisplayName("GDR-GEN-3: a merge that did not succeed refuses OK, a blank detail and null")
        void refusesOkAndBlank() {
            var reason = TestCommits.name();
            GitOutcome nullOutcome = nullValue();
            String nullDetail = nullValue();

            assertAll(Stream.<Executable>of(
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new MergeResult.NotMerged(GitOutcome.OK, reason), "OK"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new MergeResult.NotMerged(GitOutcome.FAILED, " "), "a blank detail"),
                    () -> assertThrows(IllegalArgumentException.class,
                            () -> new MergeResult.NotMerged(GitOutcome.FAILED, ""), "an empty detail"),
                    () -> assertThrows(NullPointerException.class,
                            () -> new MergeResult.NotMerged(nullOutcome, reason), "a null outcome"),
                    () -> assertThrows(NullPointerException.class,
                            () -> new MergeResult.NotMerged(GitOutcome.FAILED, nullDetail), "a null detail")));
        }
    }

    @Nested
    @DisplayName("toResult")
    class ToResult {

        @Test
        @DisplayName("GDR-GEN-3: a clean merge maps to OK with the tree id as value")
        void merged() {
            var treeId = fullId();

            var result = new MergeResult.Merged(treeId).toResult();

            assertAll(
                    () -> assertEquals(GitOutcome.OK, result.outcome(), "the outcome is OK"),
                    () -> assertEquals(treeId, result.value(), "the value is the tree id"),
                    () -> assertNull(result.detail(), "a success carries no detail"));
        }

        @Test
        @DisplayName("GDR-GEN-3: a conflict maps to CONFLICT with its detail and without the paths")
        void conflicting() {
            var paths = paths();
            var conflicting = new MergeResult.Conflicting(paths);

            var result = conflicting.toResult();

            assertAll(
                    () -> assertEquals(GitOutcome.CONFLICT, result.outcome(), "the outcome is CONFLICT"),
                    () -> assertNull(result.value(), "a non-success carries no value"),
                    () -> assertEquals(paths.size() + " conflicting paths", result.detail(),
                            "the detail is the conflict's detail"));
        }

        @Test
        @DisplayName("GDR-GEN-3: a merge that did not succeed maps to its outcome and its detail, without value")
        void notMerged() {
            var reason = TestCommits.name();

            assertAll(nonSuccesses().<Executable>map(outcome -> () -> {
                var result = new MergeResult.NotMerged(outcome, reason).toResult();
                assertAll(
                        () -> assertEquals(outcome, result.outcome(), "the outcome is kept"),
                        () -> assertNull(result.value(), "a non-success carries no value"),
                        () -> assertEquals(reason, result.detail(), "the detail is the reason"));
            }));
        }
    }
}
