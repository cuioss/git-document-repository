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

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.SystemReader;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
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

    /** Directories recorded by the running test alone; a fresh instance serves every test. */
    private final Set<Path> recordedByThisTest = ConcurrentHashMap.newKeySet();

    @AfterEach
    void fixtureTestRecordedItsDirectories(TestInfo testInfo) {
        assertEveryFixtureDirectoryRecorded(testInfo, recordedByThisTest, LocalRepository.class);
    }

    @AfterAll
    static void everyDirectoryIsDeleted() {
        assertAll("deleted directories", RECEIVED_DIRECTORIES.stream()
                .map(directory -> () -> assertFalse(Files.exists(directory),
                        "The directory of the repository is deleted after the test: " + directory)));
    }

    /**
     * Tells whether the running test consumes one of the given fixtures, through a parameter of its test method or
     * of a constructor of its test class. The annotations of all parameters are searched, regardless of their index,
     * so that the implicit outer instance of an inner class constructor does not shift them.
     *
     * @param testInfo    the running test
     * @param annotations the qualifier annotations of the fixtures
     * @return {@code true} when a parameter of the test method or of a constructor carries one of the annotations
     */
    @SafeVarargs
    static boolean consumesFixture(TestInfo testInfo, Class<? extends Annotation>... annotations) {
        return fixtureParameterCount(testInfo, annotations) > 0;
    }

    /**
     * Counts the parameters of the running test's method and of the constructors of its test class that carry one of
     * the given fixture annotations, searched as in {@link #consumesFixture(TestInfo, Class[])}.
     *
     * @param testInfo    the running test
     * @param annotations the qualifier annotations of the fixtures
     * @return how many parameters carry one of the annotations
     */
    @SafeVarargs
    static long fixtureParameterCount(TestInfo testInfo, Class<? extends Annotation>... annotations) {
        var executables = new ArrayList<Executable>();
        testInfo.getTestMethod().ifPresent(executables::add);
        testInfo.getTestClass().ifPresent(testClass -> executables.addAll(List.of(testClass.getDeclaredConstructors())));
        var wanted = List.of(annotations);
        return executables.stream()
                .flatMap(executable -> Arrays.stream(executable.getParameterAnnotations()))
                .filter(parameterAnnotations -> Arrays.stream(parameterAnnotations)
                        .anyMatch(annotation -> wanted.contains(annotation.annotationType())))
                .count();
    }

    /**
     * Asserts that a test consuming directory-backed fixtures recorded one distinct directory per fixture parameter,
     * counting only what the running test itself recorded, so that the recordings of another test cannot satisfy it.
     *
     * @param testInfo    the running test
     * @param recorded    the directories the running test recorded
     * @param annotations the qualifier annotations of the directory-backed fixtures
     */
    @SafeVarargs
    static void assertEveryFixtureDirectoryRecorded(TestInfo testInfo, Set<Path> recorded,
            Class<? extends Annotation>... annotations) {
        var expected = fixtureParameterCount(testInfo, annotations);
        if (expected > 0) {
            assertEquals(expected, recorded.size(),
                    "A test consuming directory-backed fixtures records one directory per fixture parameter: "
                            + testInfo.getDisplayName());
        }
    }

    /**
     * Records a directory the running test received, for the per-test guard and for the deletion check after all
     * tests.
     *
     * @param directory the received directory
     */
    private void recordDirectory(Path directory) {
        recordedByThisTest.add(directory);
        RECEIVED_DIRECTORIES.add(directory);
    }

    @Test
    @DisplayName("Should store the cleanup of the very repository it returns")
    @SuppressWarnings("deprecation") // CloseableResource is the store type closed with auto-closing switched off
    void shouldStoreCleanupOfReturnedRepository(ExtensionContext context) throws Exception {
        var declared = new ReflectedParameter(signatureParameter(2), 2);

        var resolved = extension.resolveParameter(declared, context);
        var stored = context.getStore(LocalRepositoryExtension.NAMESPACE)
                .get(LocalRepositoryExtension.storeKey(declared), LocalRepositoryExtension.TemporaryRepository.class);
        recordDirectory(stored.directory());

        assertAll("stored cleanup",
                () -> assertSame(resolved, stored.repository(), "The stored cleanup holds the returned repository"),
                () -> assertEquals(stored.directory(), ((Repository) resolved).getDirectory().toPath(),
                        "The stored cleanup deletes the directory of the returned repository"),
                () -> assertInstanceOf(AutoCloseable.class, stored, "Jupiter closes it with auto-closing on"),
                () -> assertInstanceOf(ExtensionContext.Store.CloseableResource.class, stored,
                        "Jupiter closes it with auto-closing off"));
    }

    @Test
    @DisplayName("Should provide a bare repository in an existing directory")
    void shouldProvideBareRepository(@LocalRepository Repository repository) {
        var directory = repository.getDirectory().toPath();
        recordDirectory(directory);

        assertAll("local repository",
                () -> assertTrue(repository.isBare(), "The repository is bare"),
                () -> assertTrue(Files.isDirectory(directory), "The repository lies in an existing directory"));
    }

    @Test
    @DisplayName("Should provide a distinct repository per annotated parameter")
    void shouldProvideDistinctRepositories(@LocalRepository Repository first, @LocalRepository Repository second) {
        var firstDirectory = first.getDirectory().toPath();
        var secondDirectory = second.getDirectory().toPath();
        recordDirectory(firstDirectory);
        recordDirectory(secondDirectory);

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
            recordDirectory(directory);
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
        @DisplayName("Should delete the directory on an unchecked failure of the creation")
        void shouldDeleteDirectoryOnUncheckedFailureOfCreation() {
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
     * The closing of a repository and the deletion of its directory, observed with an EasyMock repository that expects
     * exactly one closing: the cleanup stored for a test and the rollback of a failed creation.
     */
    @Nested
    @DisplayName("Closing of a temporary repository")
    class Closing {

        @Test
        @DisplayName("Should close the repository and delete its directory when the cleanup closes")
        void shouldCloseRepositoryAndDeleteDirectory() throws Exception {
            var repository = repositoryExpectingOneClosing(null);
            var directory = directoryWithContent();

            new LocalRepositoryExtension.TemporaryRepository(repository, directory).close();

            assertAll("closed cleanup",
                    () -> verify(repository),
                    () -> assertFalse(Files.exists(directory), "The directory is deleted"));
        }

        @Test
        @DisplayName("Should delete the directory also when closing the repository fails")
        void shouldDeleteDirectoryWhenClosingFails() throws Exception {
            var closingFailure = new IllegalStateException("The repository cannot be closed");
            var repository = repositoryExpectingOneClosing(closingFailure);
            var directory = directoryWithContent();
            var cleanup = new LocalRepositoryExtension.TemporaryRepository(repository, directory);

            var thrown = assertThrows(IllegalStateException.class, cleanup::close);

            assertAll("cleanup after a failed closing",
                    () -> assertSame(closingFailure, thrown, "The failure of the closing propagates"),
                    () -> verify(repository),
                    () -> assertFalse(Files.exists(directory), "The directory is deleted nevertheless"));
        }

        @Test
        @DisplayName("Should close the repository and delete its directory on the rollback of a failed creation")
        void shouldCloseRepositoryAndDeleteDirectoryOnRollBack() throws Exception {
            var repository = repositoryExpectingOneClosing(null);
            var directory = directoryWithContent();
            var failure = new ParameterResolutionException("The creation failed");

            LocalRepositoryExtension.rollBack(repository, directory, failure);

            assertAll("rolled back creation",
                    () -> verify(repository),
                    () -> assertFalse(Files.exists(directory), "The directory is deleted"),
                    () -> assertEquals(0, failure.getSuppressed().length,
                            "The closing and the deletion add no suppressed failure"));
        }

        @Test
        @DisplayName("Should delete the directory and keep the propagated failure when closing fails on the rollback")
        void shouldDeleteDirectoryWhenClosingFailsOnRollBack() throws Exception {
            var closingFailure = new IllegalStateException("The repository cannot be closed");
            var repository = repositoryExpectingOneClosing(closingFailure);
            var directory = directoryWithContent();
            var failure = new ParameterResolutionException("The creation failed");

            assertDoesNotThrow(() -> LocalRepositoryExtension.rollBack(repository, directory, failure),
                    "The failure of the closing does not replace the failure being propagated");

            assertAll("rolled back creation after a failed closing",
                    () -> verify(repository),
                    () -> assertFalse(Files.exists(directory), "The directory is deleted nevertheless"),
                    () -> assertArrayEquals(new Throwable[]{closingFailure}, failure.getSuppressed(),
                            "The failure of the closing is suppressed on the propagated failure"));
        }

        private static Path directoryWithContent() throws IOException {
            var directory = TemporaryDirectories.create();
            Files.writeString(directory.resolve("HEAD"), "ref: refs/heads/main\n");
            return directory;
        }
    }

    /**
     * A repository mock in replay state that expects exactly one {@code close()} and, when given a failure, throws it
     * from that call. Only the closing is expected: any other call on the mock fails the test.
     *
     * @param closingFailure the failure the closing throws, or {@code null} for a closing that succeeds
     * @return the mock; {@code verify(mock)} states that it was closed once
     */
    static InMemoryRepository repositoryExpectingOneClosing(@Nullable RuntimeException closingFailure) {
        InMemoryRepository repository = createMock(InMemoryRepository.class);
        repository.close();
        if (closingFailure == null) {
            expectLastCall().once();
        } else {
            expectLastCall().andThrow(closingFailure).once();
        }
        replay(repository);
        return repository;
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
     * @param declared    an annotated repository parameter, resolved directly
     */
    @SuppressWarnings("unused")
    static void signature(Repository unannotated, @LocalRepository String wrongType,
            @LocalRepository Repository declared) {
        // only the parameters are read, by reflection
    }

    static Parameter signatureParameter(int index) throws NoSuchMethodException {
        return LocalRepositoryExtensionTest.class
                .getDeclaredMethod("signature", Repository.class, String.class, Repository.class)
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
