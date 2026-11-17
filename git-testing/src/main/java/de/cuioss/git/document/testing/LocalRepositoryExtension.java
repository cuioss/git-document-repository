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
import java.lang.annotation.Annotation;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * Resolves a test parameter annotated with {@link LocalRepository}: a new bare repository in a fresh temporary
 * directory, one per annotated parameter. The repository is closed and the directory deleted when the extension
 * context of the test closes, also when the test fails.
 * <p>
 * The extension only resolves parameters that carry {@link LocalRepository}; the parameter type alone never selects
 * it. It is registered by the annotation and needs no {@code @ExtendWith} of its own.
 *
 * @since 0.1
 */
public final class LocalRepositoryExtension implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(LocalRepositoryExtension.class);

    /**
     * Creates the extension; JUnit Jupiter instantiates it for {@link LocalRepository}.
     */
    public LocalRepositoryExtension() {
        // stateless: every resolved parameter keeps its state in the extension context's store
    }

    /**
     * Supports a parameter exactly when it is annotated with {@link LocalRepository}.
     *
     * @param parameterContext the context of the parameter to resolve
     * @param extensionContext the context of the test
     * @return {@code true} when the parameter carries {@link LocalRepository}
     */
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.isAnnotated(LocalRepository.class);
    }

    /**
     * Creates a new bare repository in a fresh temporary directory and registers its cleanup in the extension
     * context's store.
     *
     * @param parameterContext the context of the parameter to resolve
     * @param extensionContext the context of the test
     * @return the new bare repository
     * @throws ParameterResolutionException when the parameter is not of type {@link Repository} or the repository
     *                                      cannot be created
     */
    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        requireType(parameterContext, LocalRepository.class, Repository.class);
        var temporary = createBareRepository(LocalRepository.class);
        extensionContext.getStore(NAMESPACE).put(storeKey(parameterContext), temporary);
        return temporary.repository();
    }

    /**
     * Fails unless the parameter is of the type the fixture provides.
     *
     * @param parameterContext the context of the parameter to resolve
     * @param annotation       the qualifier annotation that selected the fixture
     * @param expectedType     the type the fixture provides
     * @throws ParameterResolutionException naming the annotation and the expected type when the type differs
     */
    static void requireType(ParameterContext parameterContext, Class<? extends Annotation> annotation,
            Class<?> expectedType) {
        var actualType = parameterContext.getParameter().getType();
        if (!expectedType.equals(actualType)) {
            throw new ParameterResolutionException("@" + annotation.getSimpleName() + " requires a parameter of type "
                    + expectedType.getName() + ", but " + parameterContext.getParameter().getName() + " is of type "
                    + actualType.getName());
        }
    }

    /**
     * Derives the store key of a parameter: its index, qualified by its executable so that a parameter of a lifecycle
     * method and one of the test method with the same index do not replace each other's cleanup.
     *
     * @param parameterContext the context of the resolved parameter
     * @return the key under which the parameter's cleanup is stored
     */
    static Object storeKey(ParameterContext parameterContext) {
        return List.of(parameterContext.getDeclaringExecutable(), parameterContext.getIndex());
    }

    /**
     * Creates a bare repository in a fresh temporary directory. A directory created before a failure is deleted
     * again.
     *
     * @param annotation the qualifier annotation the repository is created for, named in a failure
     * @return the repository together with its directory, closed and deleted by {@link TemporaryRepository#close()}
     * @throws ParameterResolutionException when the directory or the repository cannot be created
     */
    static TemporaryRepository createBareRepository(Class<? extends Annotation> annotation) {
        Path directory;
        try {
            directory = TemporaryDirectories.create();
        } catch (IOException e) {
            throw new ParameterResolutionException(
                    "@" + annotation.getSimpleName() + " could not create a temporary directory", e);
        }
        try {
            Repository repository = new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build();
            try {
                repository.create(true);
            } catch (IOException e) {
                repository.close();
                throw e;
            }
            return new TemporaryRepository(repository, directory);
        } catch (IOException e) {
            var failure = new ParameterResolutionException(
                    "@" + annotation.getSimpleName() + " could not create a bare repository in " + directory, e);
            try {
                TemporaryDirectories.delete(directory);
            } catch (IOException deletionFailure) {
                failure.addSuppressed(deletionFailure);
            }
            throw failure;
        }
    }

    /**
     * A repository in a temporary directory, stored in the extension context so that JUnit Jupiter closes it after
     * the test.
     *
     * @param repository the repository handed to the test
     * @param directory  the directory of the repository, deleted on {@link #close()}
     */
    record TemporaryRepository(Repository repository, Path directory) implements AutoCloseable {

        /**
         * Closes the repository and deletes its directory, also when closing fails.
         *
         * @throws IOException if the directory cannot be deleted
         */
        @Override
        public void close() throws IOException {
            try {
                repository.close();
            } finally {
                TemporaryDirectories.delete(directory);
            }
        }
    }
}
