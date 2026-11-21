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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;


import de.cuioss.tools.logging.LogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The catalogue {@code git-store/doc/LogMessages.adoc} mirrors {@link GitStoreLogMessages} by hand (GDR-GEN-4): every
 * declared {@link LogRecord} has a row with its identifier, the component {@value GitStoreLogMessages#PREFIX} and its
 * template verbatim, and the catalogue lists no identifier that is not declared. The prose descriptions are not
 * checked.
 */
@DisplayName("Log-message catalogue of git-store (GDR-GEN-4)")
class LogMessagesCatalogueTest {

    /** The catalogue, relative to the module directory Surefire runs the tests in. */
    private static final Path CATALOGUE = Path.of("doc", "LogMessages.adoc");

    /** The identifier of every declared message, mapped to its template. */
    private static Map<String, String> declared() throws IllegalAccessException {
        Map<String, String> declared = new TreeMap<>();
        for (Class<?> level : GitStoreLogMessages.class.getDeclaredClasses()) {
            for (Field field : level.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && LogRecord.class.isAssignableFrom(field.getType())) {
                    var logRecord = (LogRecord) field.get(null);
                    declared.put(logRecord.resolveIdentifierString(), logRecord.getTemplate());
                }
            }
        }
        return declared;
    }

    /**
     * The identifier of every row of the catalogue, mapped to its template: a row starts with
     * {@code |GIT_STORE-<identifier> |GIT_STORE |<template>}, the cells separated by {@code " |"}.
     */
    private static Map<String, String> documented(Map<String, String> components) throws IOException {
        Map<String, String> documented = new TreeMap<>();
        for (String line : Files.readAllLines(CATALOGUE)) {
            if (line.startsWith("|" + GitStoreLogMessages.PREFIX + "-")) {
                String[] cells = line.substring(1).split(" \\|");
                documented.put(cells[0].trim(), cells.length > 2 ? cells[2].trim() : "");
                components.put(cells[0].trim(), cells.length > 1 ? cells[1].trim() : "");
            }
        }
        return documented;
    }

    @Test
    @DisplayName("GDR-GEN-4: every declared message is catalogued with its identifier and template, and no other")
    void catalogueMatchesDeclarations() throws Exception {
        assertTrue(Files.isRegularFile(CATALOGUE), "the catalogue exists");
        Map<String, String> components = new TreeMap<>();

        var declared = declared();
        var documented = documented(components);

        assertAll(
                () -> assertFalse(declared.isEmpty(), "messages are declared"),
                () -> assertEquals(declared, documented, "identifiers and templates of the catalogue"),
                () -> assertTrue(components.values().stream().allMatch(GitStoreLogMessages.PREFIX::equals),
                        () -> "every row names the component " + GitStoreLogMessages.PREFIX + ": " + components));
    }
}
