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
package de.cuioss.git.document.timestamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GDR-GEN-1: the libraries carry no domain term. No public type of {@code git-timestamp}, by its simple name or its
 * package name, contains one of the domain terms of the application, compared case-insensitively. The scan reads the
 * main output directory of the module, located through the code source of {@link GitTimestampLogMessages}, and loads
 * each class without initialising it.
 * <p>
 * The terms are not kept here: they are read from the section GDR-GEN-1 of {@code doc/specification/general.adoc}, from
 * the sentence that states which terms {@code DomainTermAbsenceTest} looks for, and each must belong to a domain the
 * requirement names. The path is resolved against the module directory, the working directory of Maven Surefire. The
 * test fails, naming the specification, when the file, the section or the sentence cannot be read, or when it yields no
 * term, so it never passes on an empty list.
 */
@DisplayName("git-timestamp public types carry no domain term (GDR-GEN-1)")
class DomainTermAbsenceTest {

    /** The general specification, relative to the module directory. */
    private static final Path SPECIFICATION = Path.of("..", "doc", "specification", "general.adoc");

    /** The section GDR-GEN-1, up to the anchor of the next section. */
    private static final Pattern SECTION = Pattern.compile("(?s)\\[#GDR-GEN-1]\\R(.*?)\\R\\[#GDR-GEN-2]");

    /** The sentence of the section that lists the terms the test looks for, between backticks. */
    private static final Pattern TERM_SENTENCE = Pattern.compile(
            "`DomainTermAbsenceTest`[^*]*? contains ((?:`[^`]+`(?:, | or ))*`[^`]+`), compared case-insensitively");

    /** The requirement of the section that names the domains. */
    private static final Pattern REQUIREMENT = Pattern.compile("carry a term of a domain \\(([^)]+)\\)");

    /** A term between backticks. */
    private static final Pattern QUOTED = Pattern.compile("`([^`]+)`");

    /** The file suffix of a compiled class. */
    private static final String CLASS_SUFFIX = ".class";

    @Test
    @DisplayName("Should find public types of git-timestamp and none of them names a domain term of the specification")
    void shouldCarryNoDomainTermInPublicTypes() throws Exception {
        var domainTerms = domainTermsOfSpecification();
        var publicTypes = publicTypesOfMainOutput();

        var violations = new ArrayList<String>();
        for (Class<?> type : publicTypes) {
            var simpleName = type.getSimpleName().toLowerCase(Locale.ROOT);
            var packageName = type.getPackageName().toLowerCase(Locale.ROOT);
            for (String term : domainTerms) {
                if (simpleName.contains(term) || packageName.contains(term)) {
                    violations.add(type.getName() + " contains '" + term + "'");
                }
            }
        }

        assertFalse(publicTypes.isEmpty(), "The scan must find at least one public type of git-timestamp");
        assertEquals(List.of(), violations, "Public types of git-timestamp must not carry a domain term of GDR-GEN-1");
    }

    /**
     * Reads the domain terms from the section GDR-GEN-1 of the general specification.
     *
     * @return the terms in lower case, never empty
     */
    private static List<String> domainTermsOfSpecification() throws IOException {
        var file = SPECIFICATION.toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(SPECIFICATION), () -> "The specification must be readable at " + file);
        Matcher section = SECTION.matcher(Files.readString(SPECIFICATION));
        if (!section.find()) {
            fail("The section GDR-GEN-1 must be found in " + file);
        }
        var text = section.group(1).replaceAll("\\s+", " ");
        Matcher sentence = TERM_SENTENCE.matcher(text);
        if (!sentence.find()) {
            fail("GDR-GEN-1 in " + file + " must state the terms DomainTermAbsenceTest looks for");
        }
        Matcher requirement = REQUIREMENT.matcher(text);
        if (!requirement.find()) {
            fail("GDR-GEN-1 in " + file + " must name the domains whose terms no public type may carry");
        }
        var domains = requirement.group(1).toLowerCase(Locale.ROOT);
        var terms = QUOTED.matcher(sentence.group(1)).results()
                .map(match -> match.group(1).toLowerCase(Locale.ROOT))
                .toList();
        assertFalse(terms.isEmpty(), () -> "GDR-GEN-1 in " + file + " must list at least one domain term");
        for (String term : terms) {
            assertTrue(domains.contains(term),
                    () -> "The term '" + term + "' must belong to a domain GDR-GEN-1 names: " + domains);
        }
        return terms;
    }

    /**
     * Loads every class of the main output directory without initialising it and keeps the public ones.
     *
     * @return the public types of the main output directory, never {@code null}
     */
    private static List<Class<?>> publicTypesOfMainOutput()
            throws IOException, URISyntaxException, ClassNotFoundException {
        var root = Path.of(GitTimestampLogMessages.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        var loader = GitTimestampLogMessages.class.getClassLoader();
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
