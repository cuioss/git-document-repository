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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.Isolated;

@DisplayName("Local repository fixture")
class LocalRepositoryExtensionTest {

    /** Directories handed to the tests, asserted gone once the extension's per-test cleanup has run. */
    private static final List<Path> RECEIVED_DIRECTORIES = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final ParameterResolver EXTENSION_CONTEXT = new ExtensionContextResolver();

    private final LocalRepositoryExtension extension = new LocalRepositoryExtension();

    @AfterAll
    static void everyDirectoryIsDeleted() {
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

    /**
     * A test class with the default per-method lifecycle whose constructor receives the fixture: every test gets its
     * own repository, and each one's directory is deleted after its test, asserted by
     * {@link #everyDirectoryIsDeleted()}.
     */
    @Nested
    @DisplayName("Constructor fixture of a per-method test class")
    class ConstructorFixture {

        private final Repository repository;

        ConstructorFixture(@LocalRepository Repository repository) {
            this.repository = repository;
        }

        @Test
        @DisplayName("Should provide the first test its own repository")
        void shouldProvideFirstTestItsRepository() {
            assertReceivedOwnRepository();
        }

        @Test
        @DisplayName("Should provide the second test its own repository")
        void shouldProvideSecondTestItsRepository() {
            assertReceivedOwnRepository();
        }

        private void assertReceivedOwnRepository() {
            var directory = repository.getDirectory().toPath();
            assertFalse(RECEIVED_DIRECTORIES.contains(directory), "No other test received this repository");
            RECEIVED_DIRECTORIES.add(directory);
            assertTrue(Files.isDirectory(directory), "The repository lies in an existing directory");
        }
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
     * A creation that fails after the temporary directory exists: the directory is deleted again and the failure
     * names the annotation. JGit reads the user configuration and the platform through its replaceable
     * {@link SystemReader}, so a reader that fails at one of these points drives the failure; it is installed for the
     * one call only, and the class runs isolated from every other test because the reader is global.
     */
    @Nested
    @Isolated
    @DisplayName("Failed creation of the bare repository")
    class FailedCreation {

        private static final String FAILURE_PREFIX = "@LocalRepository could not create a bare repository in ";

        @Test
        @DisplayName("Should delete the directory when the repository cannot be constructed")
        void shouldDeleteDirectoryWhenRepositoryCannotBeConstructed() {
            var thrown = createBareRepositoryWith(new UnreadableUserConfiguration(SystemReader.getInstance()));

            assertAll("failure before the repository exists",
                    () -> assertInstanceOf(IOException.class, thrown.getCause(),
                            "The checked failure of JGit is the cause"),
                    () -> assertInstanceOf(ConfigInvalidException.class, thrown.getCause().getCause(),
                            "JGit reports the unreadable user configuration"),
                    () -> assertFalse(Files.exists(failedDirectory(thrown)), "The temporary directory is deleted"));
        }

        @Test
        @DisplayName("Should close the repository and delete its directory on an unchecked failure of the creation")
        void shouldRollBackOnUncheckedFailureOfCreation() {
            var reader = new FailingPlatformQuery(SystemReader.getInstance());

            var thrown = createBareRepositoryWith(reader);

            assertAll("failure while the repository is created",
                    () -> assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                            "The unchecked failure of JGit is the cause"),
                    () -> assertTrue(reader.failed, "The creation reached the failing platform query"),
                    () -> assertFalse(Files.exists(failedDirectory(thrown)),
                            "The directory, already holding the repository's files, is deleted"));
        }

        private static ParameterResolutionException createBareRepositoryWith(SystemReader reader) {
            var previous = SystemReader.getInstance();
            SystemReader.setInstance(reader);
            try {
                if (reader instanceof FailingPlatformQuery failing) {
                    // armed only now: installing the reader queries the platform itself
                    failing.armed = true;
                }
                return assertThrows(ParameterResolutionException.class,
                        () -> LocalRepositoryExtension.createBareRepository(LocalRepository.class));
            } finally {
                SystemReader.setInstance(previous);
            }
        }

        private static Path failedDirectory(ParameterResolutionException thrown) {
            var message = thrown.getMessage();
            assertTrue(message.startsWith(FAILURE_PREFIX), "The failure names the annotation: " + message);
            return Path.of(message.substring(FAILURE_PREFIX.length()));
        }
    }

    /**
     * A system reader whose user configuration cannot be loaded, so that JGit fails to construct the repository.
     */
    static final class UnreadableUserConfiguration extends SystemReader.Delegate {

        UnreadableUserConfiguration(SystemReader delegate) {
            super(delegate);
        }

        @Override
        public FileBasedConfig openUserConfig(Config parent, FS fs) {
            return new FileBasedConfig(parent, new File("unreadable-gitconfig"), fs) {

                @Override
                public void load() throws ConfigInvalidException {
                    throw new ConfigInvalidException("The user configuration is unreadable");
                }

                @Override
                public boolean isOutdated() {
                    return true;
                }
            };
        }
    }

    /**
     * A system reader whose platform query fails once armed, which JGit asks while it creates the repository's files.
     */
    static final class FailingPlatformQuery extends SystemReader.Delegate {

        volatile boolean armed;

        volatile boolean failed;

        FailingPlatformQuery(SystemReader delegate) {
            super(delegate);
        }

        @Override
        public boolean isMacOS() {
            if (armed) {
                failed = true;
                throw new IllegalStateException("The platform cannot be queried");
            }
            return super.isMacOS();
        }
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
