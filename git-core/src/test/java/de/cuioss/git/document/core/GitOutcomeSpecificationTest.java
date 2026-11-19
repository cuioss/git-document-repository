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
 * The GDR-GEN-3 table of {@code doc/specification/general.adoc} lists exactly the constants of {@link GitOutcome}, in
 * their order: a constant added to the enum without a row, a row without a constant, a renamed or a reordered one fails
 * this test.
 */
@DisplayName("GitOutcome against the GDR-GEN-3 table")
class GitOutcomeSpecificationTest {

    /** The specification document, relative to the reactor root. */
    private static final Path GENERAL = Path.of("doc", "specification", "general.adoc");

    /**
     * The end of the sentence that introduces the {@code GitOutcome} table, part of GDR-GEN-3; the sentence breaks
     * before it, so the line holding it is the last line before the table.
     */
    private static final String TABLE_INTRODUCTION = "lists exactly the constants of `GitOutcome`";

    /** The delimiter of an AsciiDoc table. */
    private static final String TABLE_DELIMITER = "|===";

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
    @DisplayName("GDR-GEN-3: the table lists exactly the GitOutcome constants, in order")
    void tableMatchesEnum() throws Exception {
        var lines = Files.readAllLines(reactorRoot().resolve(GENERAL));

        var constants = tableConstants(lines);

        assertEquals(Arrays.stream(GitOutcome.values()).map(Enum::name).toList(), constants,
                "the GDR-GEN-3 table and GitOutcome name the same constants in the same order");
    }

    @Test
    @DisplayName("the extraction reads the rows of the introduced table only, so a missing row is seen")
    void extractionDetectsDifference() {
        var lines = List.of("|`OTHER` |a row before the introduction", "the first column names the constant, and "
                + TABLE_INTRODUCTION + ":", "", "[cols=\"2,2,4\"]", TABLE_DELIMITER, "|Constant |Outcome |Raised by",
                "", "|`OK` |success |every operation", "continued cell text", "|`CONFLICT` |conflict |merge",
                TABLE_DELIMITER, "|`AFTER` |a row after the table");

        var constants = tableConstants(lines);

        assertAll(
                () -> assertEquals(List.of("OK", "CONFLICT"), constants, "the rows of the table, in order"),
                () -> assertFalse(constants.contains("OTHER"), "no row before the table"),
                () -> assertTrue(tableConstants(List.of()).isEmpty(), "no table, no constants"));
    }
}
