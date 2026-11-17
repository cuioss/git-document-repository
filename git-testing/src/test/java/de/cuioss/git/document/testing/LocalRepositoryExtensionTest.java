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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.RegisterExtension;

@DisplayName("Local repository fixture")
class LocalRepositoryExtensionTest {

    /** Directories handed to the tests, asserted gone once the extension's per-test cleanup has run. */
    private static final List<Path> RECEIVED_DIRECTORIES = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final ParameterResolver EXTENSION_CONTEXT = new ExtensionContextResolver();

    private final LocalRepositoryExtension extension = new LocalRepositoryExtension();

    @AfterAll
    static void everyDirectoryIsDeleted() {
        assertFalse(RECEIVED_DIRECTORIES.isEmpty(), "The tests received at least one directory");
        assertAll("deleted directories", RECEIVED_DIRECTORIES.stream()
                .map(directory -> () -> assertFalse(Files.exists(directory),
                        "The directory of the repository is deleted after the test: " + directory)));
    }

    @Test
    @DisplayName("Should provide a bare repository in an existing directory")
    void shouldProvideBareRepository(@LocalRepository Repository repository) {
        var directory = repository.getDirectory().toPath();
        RECEIVED_DIRECTORIES.add(directory);

        assertAll("local repository",
                () -> assertTrue(repository.isBare(), "The repository is bare"),
                () -> assertTrue(Files.isDirectory(directory), "The repository lies in an existing directory"));
    }

    @Test
    @DisplayName("Should provide a distinct repository per annotated parameter")
    void shouldProvideDistinctRepositories(@LocalRepository Repository first, @LocalRepository Repository second) {
        var firstDirectory = first.getDirectory().toPath();
        var secondDirectory = second.getDirectory().toPath();
        RECEIVED_DIRECTORIES.add(firstDirectory);
        RECEIVED_DIRECTORIES.add(secondDirectory);

        assertAll("two local repositories",
                () -> assertNotSame(first, second, "Each parameter receives its own repository"),
                () -> assertNotEquals(firstDirectory, secondDirectory, "Each repository has its own directory"));
    }

    @Test
    @DisplayName("Should not support a Repository parameter without the annotation")
    void shouldNotSupportUnannotatedParameter(ExtensionContext context) throws Exception {
        var unannotated = new ReflectedParameter(signatureParameter(0), 0);

        assertFalse(extension.supportsParameter(unannotated, context),
                "The parameter type alone does not select the fixture");
    }

    @Test
    @DisplayName("Should reject an annotated parameter of another type")
    void shouldRejectAnnotatedParameterOfAnotherType(ExtensionContext context) throws Exception {
        var wrongType = new ReflectedParameter(signatureParameter(1), 1);

        var thrown = assertThrows(ParameterResolutionException.class,
                () -> extension.resolveParameter(wrongType, context));
        assertAll("failure message",
                () -> assertTrue(thrown.getMessage().contains("@LocalRepository"), "The message names the annotation"),
                () -> assertTrue(thrown.getMessage().contains(Repository.class.getName()),
                        "The message names the expected type"));
    }

    /**
     * Supplies the reflected parameters for the direct calls of the extension; never invoked.
     *
     * @param unannotated a repository parameter without the annotation
     * @param wrongType   an annotated parameter of a type the fixture does not provide
     */
    @SuppressWarnings("unused")
    static void signature(Repository unannotated, @LocalRepository String wrongType) {
        // only the parameters are read, by reflection
    }

    static Parameter signatureParameter(int index) throws NoSuchMethodException {
        return LocalRepositoryExtensionTest.class.getDeclaredMethod("signature", Repository.class, String.class)
                .getParameters()[index];
    }

    /**
     * A parameter context over a reflected method parameter, for calling an extension directly.
     *
     * @param parameter the reflected parameter
     * @param index     its index in the declaring method
     */
    record ReflectedParameter(Parameter parameter, int index) implements ParameterContext {

        @Override
        public Parameter getParameter() {
            return parameter;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public Optional<Object> getTarget() {
            return Optional.empty();
        }
    }

    /**
     * Hands the test its own extension context, so that the direct calls of the extension run against a real one.
     */
    static final class ExtensionContextResolver implements ParameterResolver {

        @Override
        public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            return parameterContext.getParameter().getType() == ExtensionContext.class;
        }

        @Override
        public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
            return extensionContext;
        }
    }
}
