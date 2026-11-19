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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Pattern;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * The native-image metadata of {@code git-core} lists only what exists: every class of {@code reflect-config.json}
 * loads and every resource bundle of {@code resource-config.json} resolves on the class path. A JGit upgrade that
 * moves or renames a listed class or translation bundle therefore fails this build instead of the native image of a
 * consumer. The names are taken from the files by plain string extraction, without a JSON library.
 */
@DisplayName("Native-image metadata of git-core")
class NativeImageMetadataTest {

    /** The directory of the metadata files on the class path. */
    private static final String METADATA_DIRECTORY = "META-INF/native-image/de.cuioss/git-core/";

    /** The file listing the types reached reflectively. */
    private static final String REFLECT_CONFIG = METADATA_DIRECTORY + "reflect-config.json";

    /** The file listing the resource bundles. */
    private static final String RESOURCE_CONFIG = METADATA_DIRECTORY + "resource-config.json";

    /**
     * A {@code "name"} entry whose value is a qualified Java name. A member entry of {@code reflect-config.json}, such
     * as the constructor {@code <init>}, carries no dot and is not matched.
     */
    private static final Pattern QUALIFIED_NAME = Pattern
            .compile("\"name\"\\s*:\\s*\"([\\w$]+(?:\\.[\\w$]+)+)\"");

    private static final ClassLoader LOADER = NativeImageMetadataTest.class.getClassLoader();

    @Test
    @DisplayName("Should ship both metadata files")
    void shouldShipBothMetadataFiles() {
        assertAll("Metadata files under " + METADATA_DIRECTORY,
                () -> assertNotNull(LOADER.getResource(REFLECT_CONFIG), REFLECT_CONFIG + " must be on the class path"),
                () -> assertNotNull(LOADER.getResource(RESOURCE_CONFIG),
                        RESOURCE_CONFIG + " must be on the class path"));
    }

    @Test
    @DisplayName("Should load every class listed in reflect-config.json")
    void shouldLoadEveryListedClass() throws Exception {
        var classNames = qualifiedNames(REFLECT_CONFIG);

        assertFalse(classNames.isEmpty(), REFLECT_CONFIG + " must list at least one class");
        assertAll("Classes of " + REFLECT_CONFIG, classNames.stream()
                .map(name -> (Executable) () -> assertDoesNotThrow(() -> Class.forName(name, false, LOADER),
                        "Listed class must exist: " + name)));
    }

    @Test
    @DisplayName("Should resolve every resource bundle listed in resource-config.json")
    void shouldResolveEveryListedBundle() throws Exception {
        var bundleNames = qualifiedNames(RESOURCE_CONFIG);

        assertFalse(bundleNames.isEmpty(), RESOURCE_CONFIG + " must list at least one resource bundle");
        assertAll("Bundles of " + RESOURCE_CONFIG, bundleNames.stream()
                .map(name -> (Executable) () -> assertDoesNotThrow(
                        () -> ResourceBundle.getBundle(name, Locale.ROOT, LOADER),
                        "Listed resource bundle must resolve: " + name)));
    }

    /**
     * Reads a metadata file from the class path and extracts its qualified names.
     *
     * @param resource the class-path location of the file
     * @return the qualified names in the order of the file, never {@code null}
     */
    private static List<String> qualifiedNames(String resource) throws IOException {
        String content;
        try (InputStream in = LOADER.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the class path");
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return QUALIFIED_NAME.matcher(content).results().map(match -> match.group(1)).toList();
    }
}
