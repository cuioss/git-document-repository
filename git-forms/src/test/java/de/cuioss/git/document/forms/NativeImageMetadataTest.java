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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * The native-image metadata of {@code git-forms} (GDR-NFR-4) lists only what exists: {@code resource-config.json} is
 * on the class path, every resource pattern it lists names a resource that resolves and every resource bundle it lists
 * resolves; {@code reflect-config.json} is absent, because no code path of {@code git-forms} uses reflection. A
 * json-schema upgrade that moves the meta-schema or the message bundle therefore fails this build instead of the native
 * image of a consumer. The names are taken from the file by plain string extraction, without a JSON library.
 */
@DisplayName("Native-image metadata of git-forms (GDR-NFR-4)")
class NativeImageMetadataTest {

    /** The directory of the metadata files on the class path. */
    private static final String METADATA_DIRECTORY = "META-INF/native-image/de.cuioss/git-forms/";

    /** The file listing the resources and resource bundles. */
    private static final String RESOURCE_CONFIG = METADATA_DIRECTORY + "resource-config.json";

    /** The file listing reflectively reached types, which git-forms does not need. */
    private static final String REFLECT_CONFIG = METADATA_DIRECTORY + "reflect-config.json";

    /** A {@code "pattern"} entry of the form {@code \\Q<resource>\\E}, a literal resource path. */
    private static final Pattern LITERAL_PATTERN = Pattern.compile("\"pattern\"\\s*:\\s*\"\\\\\\\\Q(.+?)\\\\\\\\E\"");

    /** A {@code "name"} entry whose value is a qualified name, as a bundle is listed. */
    private static final Pattern QUALIFIED_NAME = Pattern.compile("\"name\"\\s*:\\s*\"([\\w$]+(?:\\.[\\w$]+)+)\"");

    private static final ClassLoader LOADER = NativeImageMetadataTest.class.getClassLoader();

    @Test
    @DisplayName("GDR-NFR-4: resource-config.json is shipped, reflect-config.json is not")
    void shipsResourceConfigOnly() {
        assertAll("Metadata files under " + METADATA_DIRECTORY,
                () -> assertNotNull(LOADER.getResource(RESOURCE_CONFIG), RESOURCE_CONFIG + " must be on the class path"),
                () -> assertNull(LOADER.getResource(REFLECT_CONFIG), REFLECT_CONFIG + " must not be shipped"));
    }

    @Test
    @DisplayName("GDR-NFR-4: every resource listed in resource-config.json resolves")
    void resolvesEveryListedResource() throws Exception {
        var resources = matches(LITERAL_PATTERN);

        assertFalse(resources.isEmpty(), RESOURCE_CONFIG + " must list at least one resource");
        assertAll("Resources of " + RESOURCE_CONFIG, resources.stream()
                .map(name -> (Executable) () -> assertNotNull(LOADER.getResource(name),
                        "Listed resource must resolve: " + name)));
    }

    @Test
    @DisplayName("GDR-NFR-4: every resource bundle listed in resource-config.json resolves")
    void resolvesEveryListedBundle() throws Exception {
        var bundles = matches(QUALIFIED_NAME);

        assertFalse(bundles.isEmpty(), RESOURCE_CONFIG + " must list at least one resource bundle");
        assertAll("Bundles of " + RESOURCE_CONFIG, bundles.stream()
                .map(name -> (Executable) () -> assertDoesNotThrow(
                        () -> ResourceBundle.getBundle(name, Locale.ROOT, LOADER),
                        "Listed resource bundle must resolve: " + name)));
    }

    /** Reads {@code resource-config.json} from the class path and extracts the first group of every match. */
    private static List<String> matches(Pattern pattern) throws IOException {
        String content;
        try (InputStream in = LOADER.getResourceAsStream(RESOURCE_CONFIG)) {
            assertNotNull(in, RESOURCE_CONFIG + " must be on the class path");
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        return pattern.matcher(content).results().map(match -> match.group(1)).toList();
    }
}
