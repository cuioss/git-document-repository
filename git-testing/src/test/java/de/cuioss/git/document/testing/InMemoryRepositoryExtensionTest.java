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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.easymock.EasyMock.createMockBuilder;
import static org.easymock.EasyMock.createStrictMock;
import static org.easymock.EasyMock.expectLastCall;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.git.document.testing.LocalRepositoryExtensionTest.ExtensionContextResolver;
import de.cuioss.git.document.testing.LocalRepositoryExtensionTest.ReflectedParameter;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import java.io.IOException;
import java.lang.reflect.Parameter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.RegisterExtension;

@DisplayName("In-memory repository fixture")
@EnableGeneratorController
class InMemoryRepositoryExtensionTest {

    /** Objects inserted by earlier repetitions, which a fresh repository must not hold. */
    private static final List<ObjectId> EARLIER_OBJECTS = new CopyOnWriteArrayList<>();

    /** Repositories handed to the constructor fixture, which must differ per test. */
    private static final List<Repository> CONSTRUCTOR_REPOSITORIES = new CopyOnWriteArrayList<>();

    @RegisterExtension
    static final ParameterResolver EXTENSION_CONTEXT = new ExtensionContextResolver();

    private final InMemoryRepositoryExtension extension = new InMemoryRepositoryExtension();

    private long directoriesCreatedBefore;

    @BeforeEach
    void captureTemporaryDirectories() {
        // runs before the test method's parameters are resolved
        directoriesCreatedBefore = TemporaryDirectories.createdCount();
    }

    @Test
    @DisplayName("Should provide a Repository that stores and reads back an object")
    void shouldStoreAndReadBackObject(@InMemory Repository repository) throws Exception {
        var content = Generators.letterStrings(1, 64).next().getBytes(UTF_8);

        ObjectId id;
        try (var inserter = repository.newObjectInserter()) {
            id = inserter.insert(Constants.OBJ_BLOB, content);
            inserter.flush();
        }
        byte[] readBack;
        try (var reader = repository.newObjectReader()) {
            readBack = reader.open(id, Constants.OBJ_BLOB).getBytes();
        }

        assertArrayEquals(content, readBack, "The object read back is the object inserted");
    }

    @RepeatedTest(3)
    @DisplayName("Should provide a fresh repository per test")
    void shouldProvideFreshRepository(@InMemory Repository repository) throws Exception {
        try (var reader = repository.newObjectReader()) {
            for (ObjectId earlier : EARLIER_OBJECTS) {
                assertFalse(reader.has(earlier), "A fresh repository holds no object of an earlier test");
            }
        }
        try (var inserter = repository.newObjectInserter()) {
            EARLIER_OBJECTS.add(inserter.insert(Constants.OBJ_BLOB,
                    Generators.letterStrings(8, 64).next().getBytes(UTF_8)));
            inserter.flush();
        }

        assertEquals(List.of(), repository.getRefDatabase().getRefs(), "A fresh repository has no references");
    }

    @Test
    @DisplayName("Should link HEAD to the unborn branch refs/heads/master")
    void shouldLinkHeadToMaster(@InMemory Repository repository) throws Exception {
        var head = repository.exactRef(Constants.HEAD);

        assertNotNull(head, "HEAD exists");
        assertAll("initial HEAD",
                () -> assertTrue(head.isSymbolic(), "HEAD is a symbolic reference"),
                () -> assertEquals(Constants.R_HEADS + Constants.MASTER, head.getTarget().getName(),
                        "HEAD is linked to refs/heads/master"));
    }

    @Test
    @DisplayName("Should start on the same branch as the bare local repository")
    void shouldStartOnSameBranchAsLocalRepository(@InMemory Repository inMemory, @LocalRepository Repository local)
            throws Exception {
        assertEquals(local.getFullBranch(), inMemory.getFullBranch(),
                "The in-memory and the bare local repository start on the same branch");
    }

    @Test
    @DisplayName("Should create no temporary directory")
    void shouldCreateNoDirectory(@InMemory Repository repository) {
        assertAll("no directory",
                () -> assertNull(repository.getDirectory(), "The in-memory repository has no directory"),
                () -> assertEquals(directoriesCreatedBefore, TemporaryDirectories.createdCount(),
                        "Resolving the parameter created no temporary directory"));
    }

    /**
     * A test class with the default per-method lifecycle whose constructor receives the fixture: every test gets its
     * own repository.
     */
    @Nested
    @DisplayName("Constructor fixture of a per-method test class")
    class ConstructorFixture {

        private final Repository repository;

        ConstructorFixture(@InMemory Repository repository) {
            this.repository = repository;
        }

        @RepeatedTest(2)
        @DisplayName("Should provide every test its own repository")
        void shouldProvideEveryTestItsOwnRepository() {
            assertFalse(CONSTRUCTOR_REPOSITORIES.stream().anyMatch(earlier -> earlier == repository),
                    "No other test received this repository");
            CONSTRUCTOR_REPOSITORIES.add(repository);
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
                () -> assertTrue(thrown.getMessage().contains("@InMemory"), "The message names the annotation"),
                () -> assertTrue(thrown.getMessage().contains(Repository.class.getName()),
                        "The message names the expected type"));
    }

    @Test
    @DisplayName("Should store a closing of the very repository it returns that Jupiter closes in either setting")
    @SuppressWarnings("deprecation") // CloseableResource is the store type closed with auto-closing switched off
    void shouldStoreClosingOfReturnedRepository(ExtensionContext context) throws Exception {
        var declared = new ReflectedParameter(signatureParameter(3), 3);

        var resolved = extension.resolveParameter(declared, context);
        var stored = context.getStore(InMemoryRepositoryExtension.NAMESPACE)
                .get(LocalRepositoryExtension.storeKey(declared));

        var closing = assertInstanceOf(ClosingRepository.class, stored, "The store holds a ClosingRepository");
        assertAll("stored closing",
                () -> assertSame(resolved, closing.repository(), "The stored closing holds the returned repository"),
                () -> assertInstanceOf(AutoCloseable.class, stored, "Jupiter closes it with auto-closing on"),
                () -> assertInstanceOf(ExtensionContext.Store.CloseableResource.class, stored,
                        "Jupiter closes it with auto-closing off"));
    }

    @Test
    @DisplayName("Should close its repository when the stored closing closes")
    void shouldCloseRepositoryWhenClosingCloses() {
        var repository = LocalRepositoryExtensionTest.repositoryExpectingOneClosing(null);

        new ClosingRepository(repository).close();

        verify(repository);
    }

    @Test
    @DisplayName("Should link HEAD of a repository it initialises to refs/heads/master")
    void shouldLinkHeadWhenInitialising() {
        // a partial mock: the real repository, except that close() is mocked and expected never to be called
        InMemoryRepository repository = createMockBuilder(InMemoryRepository.class)
                .withConstructor(new DfsRepositoryDescription("initialised"))
                .addMockedMethod("close")
                .createMock();
        replay(repository);

        InMemoryRepositoryExtension.initialise(repository);

        assertAll("initialised repository",
                () -> assertEquals(Constants.R_HEADS + Constants.MASTER, repository.getFullBranch(),
                        "HEAD is linked to refs/heads/master"),
                () -> verify(repository));
    }

    @Test
    @DisplayName("Should close the repository and name @InMemory when the initialisation fails")
    void shouldCloseRepositoryWhenInitialisationFails() throws Exception {
        var initialisationFailure = new IOException("The repository cannot be initialised");
        // a strict mock: the repository must be closed after the failed create(true), not before it
        InMemoryRepository repository = createStrictMock(InMemoryRepository.class);
        repository.create(true);
        expectLastCall().andThrow(initialisationFailure).once();
        repository.close();
        expectLastCall().once();
        replay(repository);

        var thrown = assertThrows(ParameterResolutionException.class,
                () -> InMemoryRepositoryExtension.initialise(repository),
                "A failed initialisation is reported as a parameter resolution failure");

        assertAll("failed initialisation",
                () -> assertSame(initialisationFailure, thrown.getCause(), "The failure of JGit is the cause"),
                () -> assertTrue(thrown.getMessage().contains("@InMemory"), "The message names the annotation"),
                () -> verify(repository));
    }

    @Test
    @DisplayName("Should reject an annotated parameter of the in-memory implementation type")
    void shouldRejectAnnotatedParameterOfImplementationType(ExtensionContext context) throws Exception {
        var implementationType = new ReflectedParameter(signatureParameter(2), 2);

        var thrown = assertThrows(ParameterResolutionException.class,
                () -> extension.resolveParameter(implementationType, context),
                "The DFS implementation type does not leak to the test");

        assertAll("failure message",
                () -> assertTrue(thrown.getMessage().contains("@InMemory"), "The message names the annotation"),
                () -> assertTrue(thrown.getMessage().contains(Repository.class.getName()),
                        "The message names the expected type"));
    }

    @Test
    @DisplayName("Should resolve an annotated parameter declared exactly as Repository")
    void shouldResolveParameterDeclaredAsRepository(ExtensionContext context) throws Exception {
        var declared = new ReflectedParameter(signatureParameter(3), 3);

        var resolved = extension.resolveParameter(declared, context);

        assertAll("resolved repository",
                () -> assertEquals(Repository.class, declared.getParameter().getType(),
                        "The parameter is declared exactly as Repository"),
                () -> assertInstanceOf(Repository.class, resolved, "The resolved value is a Repository"));
    }

    /**
     * Supplies the reflected parameters for the direct calls of the extension; never invoked.
     *
     * @param unannotated        a repository parameter without the annotation
     * @param wrongType          an annotated parameter of a type the fixture does not provide
     * @param implementationType an annotated parameter of the in-memory implementation type, which must not leak
     * @param declared           an annotated parameter declared exactly as {@link Repository}
     */
    @SuppressWarnings("unused")
    static void signature(Repository unannotated, @InMemory String wrongType,
            @InMemory InMemoryRepository implementationType, @InMemory Repository declared) {
        // only the parameters are read, by reflection
    }

    static Parameter signatureParameter(int index) throws NoSuchMethodException {
        return InMemoryRepositoryExtensionTest.class.getDeclaredMethod("signature", Repository.class, String.class,
                InMemoryRepository.class, Repository.class).getParameters()[index];
    }
}
