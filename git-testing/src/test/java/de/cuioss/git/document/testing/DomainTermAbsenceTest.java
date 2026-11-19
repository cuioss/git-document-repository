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
package de.cuioss.git.document.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GDR-GEN-1: the libraries carry no domain term. No public type of {@code git-testing}, by its simple name or its
 * package name, contains one of the domain terms of the application, compared case-insensitively. The scan reads the
 * main output directory of the module, located through the code source of {@link GitGenerators}, and loads each class
 * without initialising it.
 */
@DisplayName("git-testing public types carry no domain term (GDR-GEN-1)")
class DomainTermAbsenceTest {

    /** The domain terms of GDR-GEN-1, in lower case. */
    private static final List<String> DOMAIN_TERMS = List.of("assessment", "threat", "incident", "risk");

    /** The file suffix of a compiled class. */
    private static final String CLASS_SUFFIX = ".class";

    @Test
    @DisplayName("Should find public types and none of them names a domain term")
    void shouldCarryNoDomainTermInPublicTypes() throws Exception {
        var publicTypes = publicTypesOfMainOutput();

        var violations = new ArrayList<String>();
        for (Class<?> type : publicTypes) {
            var simpleName = type.getSimpleName().toLowerCase(Locale.ROOT);
            var packageName = type.getPackageName().toLowerCase(Locale.ROOT);
            for (String term : DOMAIN_TERMS) {
                if (simpleName.contains(term) || packageName.contains(term)) {
                    violations.add(type.getName() + " contains '" + term + "'");
                }
            }
        }

        assertFalse(publicTypes.isEmpty(), "The scan must find at least one public type of git-testing");
        assertEquals(List.of(), violations, "Public types of git-testing must not carry a domain term of GDR-GEN-1");
    }

    /**
     * Loads every class of the main output directory without initialising it and keeps the public ones.
     *
     * @return the public types of the main output directory, never {@code null}
     */
    private static List<Class<?>> publicTypesOfMainOutput()
            throws IOException, URISyntaxException, ClassNotFoundException {
        var root = Path.of(GitGenerators.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var loader = GitGenerators.class.getClassLoader();
        List<String> classNames;
        try (Stream<Path> files = Files.walk(root)) {
            classNames = files.filter(Files::isRegularFile)
                    .map(root::relativize)
                    .map(Path::toString)
                    .filter(name -> name.endsWith(CLASS_SUFFIX))
                    .map(name -> name.substring(0, name.length() - CLASS_SUFFIX.length()))
                    .filter(name -> !name.endsWith("module-info") && !name.endsWith("package-info"))
                    .map(name -> name.replace(root.getFileSystem().getSeparator(), "."))
                    .toList();
        }
        var publicTypes = new ArrayList<Class<?>>();
        for (String className : classNames) {
            Class<?> type = Class.forName(className, false, loader);
            if (Modifier.isPublic(type.getModifiers())) {
                publicTypes.add(type);
            }
        }
        return publicTypes;
    }
}
