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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code git-forms} table of GDR-GEN-3 in {@code doc/specification/general.adoc} lists exactly the constants of
 * {@link FormsOutcome}, in their order: a constant added to the enum without a row, a row without a constant, a
 * renamed or a reordered one fails this test. The introduction of {@code doc/specification/git-forms.adoc} names every
 * constant of {@link FormsOutcome} as returned by the code, none excepted, and none of its sentences that call
 * something planned names {@code MISMATCH}, {@code TRANSITION_REFUSED}, {@code STEP_UP_REQUIRED} or
 * {@code SAVED_NOT_RELEASED}, so the enum, the table and the introduction stay in step.
 */
@DisplayName("FormsOutcome against the git-forms table of GDR-GEN-3")
class FormsOutcomeSpecificationTest {

    /** The specification document, relative to the reactor root. */
    private static final Path GENERAL = Path.of("doc", "specification", "general.adoc");

    /**
     * The end of the sentence that introduces the {@code FormsOutcome} table, part of GDR-GEN-3; the sentence breaks
     * before it, so the line holding it is the last line before the table.
     */
    private static final String TABLE_INTRODUCTION = "lists exactly the constants of `FormsOutcome`";

    /** The delimiter of an AsciiDoc table. */
    private static final String TABLE_DELIMITER = "|===";

    /** The specification of {@code git-forms}, relative to the reactor root. */
    private static final Path GIT_FORMS = Path.of("doc", "specification", "git-forms.adoc");

    /** The start of the anchor line of a requirement of {@code git-forms}, which ends the introduction. */
    private static final String REQUIREMENT_ANCHOR = "[#GDR-FORMS-";

    /**
     * The outcomes GDR-FORMS-4, GDR-FORMS-5 and GDR-FORMS-7 raise, once listed as planned in the introduction, and
     * {@code SAVED_NOT_RELEASED} of the release of GDR-FORMS-5.
     */
    private static final List<String> RAISED_BY_THE_NEW_REQUIREMENTS =
            List.of("MISMATCH", "TRANSITION_REFUSED", "STEP_UP_REQUIRED", "SAVED_NOT_RELEASED");

    /** A row whose first cell is a constant in back quotes. */
    private static final Pattern CONSTANT_ROW = Pattern.compile("^\\|`([A-Z][A-Z0-9_]*)`");

    /** Finds the reactor root from the working directory of the test, the module directory under Maven. */
    private static Path reactorRoot() {
        @Nullable Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve(GENERAL))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("no " + GENERAL + " above the working directory");
    }

    /**
     * Extracts the first-column constants of the table that follows the introduction.
     *
     * @param lines the lines of the document
     * @return the constants in row order
     */
    static List<String> tableConstants(List<String> lines) {
        int index = 0;
        while (index < lines.size() && !lines.get(index).contains(TABLE_INTRODUCTION)) {
            index++;
        }
        while (index < lines.size() && !TABLE_DELIMITER.equals(lines.get(index).strip())) {
            index++;
        }
        List<String> constants = new ArrayList<>();
        for (index++; index < lines.size() && !TABLE_DELIMITER.equals(lines.get(index).strip()); index++) {
            Matcher row = CONSTANT_ROW.matcher(lines.get(index));
            if (row.find()) {
                constants.add(row.group(1));
            }
        }
        return constants;
    }

    @Test
    @DisplayName("GDR-GEN-3: the git-forms table lists exactly the FormsOutcome constants, in order")
    void tableMatchesEnum() throws Exception {
        var lines = Files.readAllLines(reactorRoot().resolve(GENERAL));

        var constants = tableConstants(lines);

        assertEquals(Arrays.stream(FormsOutcome.values()).map(Enum::name).toList(), constants,
                "the git-forms table of GDR-GEN-3 and FormsOutcome name the same constants in the same order");
    }

    /**
     * The introduction of the {@code git-forms} specification: its text before the first requirement anchor, the
     * whitespace of its line breaks collapsed.
     *
     * @param lines the lines of the document
     * @return the introduction
     */
    static String introduction(List<String> lines) {
        var text = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith(REQUIREMENT_ANCHOR)) {
                break;
            }
            text.append(line).append(' ');
        }
        return text.toString().replaceAll("\\s+", " ").strip();
    }

    /**
     * The outcomes an introduction does not name in back quotes.
     *
     * @param introduction the introduction
     * @param outcomes     the names of the outcomes it must name
     * @return the names it omits, in the given order
     */
    static List<String> outcomesNotNamed(String introduction, List<String> outcomes) {
        return outcomes.stream().filter(outcome -> !introduction.contains("`" + outcome + "`")).toList();
    }

    @Test
    @DisplayName("GDR-GEN-3: the introduction of git-forms names every FormsOutcome as returned, SAVED_NOT_RELEASED "
            + "included, and none of the newer outcomes in a sentence that calls something planned")
    void introductionReturnsEveryOutcome() throws Exception {
        var introduction = introduction(Files.readAllLines(reactorRoot().resolve(GIT_FORMS)));
        var everyOutcome = Arrays.stream(FormsOutcome.values()).map(Enum::name).toList();

        var plannedSentences = Arrays.stream(introduction.split("(?<=\\.) ")).filter(sentence -> sentence
                .contains("planned")).toList();

        assertAll(
                () -> assertFalse(plannedSentences.isEmpty(), "the introduction names the planned requirements"),
                () -> assertEquals(List.of(), outcomesNotNamed(introduction, everyOutcome),
                        "the introduction names every outcome the code returns"),
                () -> assertTrue(plannedSentences.stream().noneMatch(sentence -> RAISED_BY_THE_NEW_REQUIREMENTS
                        .stream().anyMatch(sentence::contains)), () -> "no outcome is called planned: "
                        + plannedSentences));
    }

    @Test
    @DisplayName("the introduction ends at the first requirement anchor, so a planned outcome in it is seen")
    void introductionExtraction() {
        var lines = List.of("= title", "", "`MISMATCH` belongs to the planned requirements.", "[#GDR-FORMS-1]",
                "`STEP_UP_REQUIRED` after the anchor");

        var introduction = introduction(lines);

        assertAll(
                () -> assertEquals("= title `MISMATCH` belongs to the planned requirements.", introduction,
                        "the text before the anchor"),
                () -> assertFalse(introduction.contains("STEP_UP_REQUIRED"), "nothing after the anchor"));
    }

    @Test
    @DisplayName("an introduction that omits an outcome fails the every-outcome check")
    void omittedOutcomeDetected() {
        var introduction = "The code returns `OK` and `INVALID`; `MISMATCH` alone is not quoted: MISMATCH.";

        assertEquals(List.of("STEP_UP_REQUIRED"), outcomesNotNamed(introduction, List.of("OK", "INVALID",
                "STEP_UP_REQUIRED")), "the omitted outcome is named");
    }

    @Test
    @DisplayName("the extraction reads the rows of the introduced table only, so a missing row is seen")
    void extractionDetectsDifference() {
        var lines = List.of("|`OTHER` |a row before the introduction", "the first column names the constant, and "
                + TABLE_INTRODUCTION + ":", "", "[cols=\"2,2,4\"]", TABLE_DELIMITER, "|Constant |Outcome |Raised by",
                "", "|`OK` |success |every operation", "continued cell text", "|`INVALID` |invalid |validation",
                TABLE_DELIMITER, "|`AFTER` |a row after the table");

        var constants = tableConstants(lines);

        assertAll(
                () -> assertEquals(List.of("OK", "INVALID"), constants, "the rows of the table, in order"),
                () -> assertFalse(constants.contains("OTHER"), "no row before the table"),
                () -> assertTrue(tableConstants(List.of()).isEmpty(), "no table, no constants"));
    }
}
