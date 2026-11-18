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

import java.io.IOException;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstantiationAwareExtension.ExtensionContextScope;

/**
 * Resolves a test parameter annotated with {@link InMemory}: a new repository of JGit's in-memory DFS storage, one per
 * annotated parameter, returned only as {@link Repository}. No directory is created. The repository starts like the
 * bare fixtures, with {@code HEAD} linked to the unborn branch {@code refs/heads/master}. It is closed when the
 * extension context of the test closes.
 * <p>
 * The extension only resolves parameters that carry {@link InMemory}; the parameter type alone never selects it. It
 * is registered by the annotation and needs no {@code @ExtendWith} of its own.
 *
 * @since 0.1
 */
public final class InMemoryRepositoryExtension implements ParameterResolver {

    /**
     * The namespace under which the closing of every resolved parameter is stored; package-private so that a test can
     * read the stored closing back.
     */
    static final Namespace NAMESPACE = Namespace.create(InMemoryRepositoryExtension.class);

    /**
     * Creates the extension; JUnit Jupiter instantiates it for {@link InMemory}.
     */
    public InMemoryRepositoryExtension() {
        // stateless: every resolved parameter keeps its state in the extension context's store
    }

    /**
     * Supports a parameter exactly when it is annotated with {@link InMemory}.
     *
     * @param parameterContext the context of the parameter to resolve
     * @param extensionContext the context of the test
     * @return {@code true} when the parameter carries {@link InMemory}
     */
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.isAnnotated(InMemory.class);
    }

    /**
     * Resolves a constructor parameter in the extension context of the test method, so that a constructor fixture of
     * a test class with the default per-method lifecycle is created and closed per test, not once in the class
     * context where the next test's fixture would replace its closing.
     *
     * @param rootContext the context of the test class
     * @return {@link ExtensionContextScope#TEST_METHOD}
     */
    @Override
    public ExtensionContextScope getTestInstantiationExtensionContextScope(ExtensionContext rootContext) {
        return ExtensionContextScope.TEST_METHOD;
    }

    /**
     * Creates a new in-memory repository, initialises it like the bare fixtures so that {@code HEAD} is linked to the
     * unborn branch {@code refs/heads/master}, and registers its closing in the extension context's store as a
     * {@link ClosingRepository}, which Jupiter closes whatever its auto-close setting.
     *
     * @param parameterContext the context of the parameter to resolve
     * @param extensionContext the context of the test
     * @return the new in-memory repository, typed as {@link Repository}
     * @throws ParameterResolutionException when the parameter is not of type {@link Repository} or the repository
     *                                      cannot be initialised
     */
    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        LocalRepositoryExtension.requireType(parameterContext, InMemory.class, Repository.class);
        Repository repository = new InMemoryRepository(new DfsRepositoryDescription("in-memory-" + UUID.randomUUID()));
        initialise(repository);
        extensionContext.getStore(NAMESPACE).put(LocalRepositoryExtension.storeKey(parameterContext),
                new ClosingRepository(repository));
        return repository;
    }

    /**
     * Initialises a new repository like the bare fixtures, so that {@code HEAD} is linked to the unborn branch
     * {@code refs/heads/master}. A repository that cannot be initialised is closed again. Package-private so that a
     * test can drive the failure with a repository double, since a fresh in-memory repository never fails to
     * initialise.
     *
     * @param repository the new repository to initialise
     * @throws ParameterResolutionException when the repository cannot be initialised; the repository is closed
     */
    static void initialise(Repository repository) {
        try {
            // links HEAD to refs/heads/master, as FileRepository#create does for the bare fixtures (JGit 7.8.0)
            repository.create(true);
        } catch (IOException e) {
            repository.close();
            throw new ParameterResolutionException("@InMemory could not initialise the in-memory repository", e);
        }
    }
}
