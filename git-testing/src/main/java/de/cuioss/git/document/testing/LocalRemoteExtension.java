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

import java.net.URISyntaxException;
import org.eclipse.jgit.transport.URIish;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestInstantiationAwareExtension.ExtensionContextScope;

/**
 * Resolves a test parameter annotated with {@link Remote}: a {@link LocalRemote} holding a new bare repository in a
 * fresh temporary directory and its {@code file:} URL, one per annotated parameter. The repository is closed and the
 * directory deleted when the extension context of the test closes, also when the test fails.
 * <p>
 * The extension only resolves parameters that carry {@link Remote}; the parameter type alone never selects it. It is
 * registered by the annotation and needs no {@code @ExtendWith} of its own.
 *
 * @since 0.1
 */
public final class LocalRemoteExtension implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(LocalRemoteExtension.class);

    /**
     * Creates the extension; JUnit Jupiter instantiates it for {@link Remote}.
     */
    public LocalRemoteExtension() {
        // stateless: every resolved parameter keeps its state in the extension context's store
    }

    /**
     * Supports a parameter exactly when it is annotated with {@link Remote}.
     *
     * @param parameterContext the context of the parameter to resolve
     * @param extensionContext the context of the test
     * @return {@code true} when the parameter carries {@link Remote}
     */
    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.isAnnotated(Remote.class);
    }

    /**
     * Resolves a constructor parameter in the extension context of the test method, so that a constructor fixture of
     * a test class with the default per-method lifecycle is created and cleaned up per test, not once in the class
     * context where the next test's fixture would replace its cleanup.
     *
     * @param rootContext the context of the test class
     * @return {@link ExtensionContextScope#TEST_METHOD}
     */
    @Override
    public ExtensionContextScope getTestInstantiationExtensionContextScope(ExtensionContext rootContext) {
        return ExtensionContextScope.TEST_METHOD;
    }

    /**
     * Creates a new bare repository in a fresh temporary directory, registers its cleanup in the extension context's
     * store and returns it with its {@code file:} URL.
     *
     * @param parameterContext the context of the parameter to resolve
     * @param extensionContext the context of the test
     * @return the new local remote
     * @throws ParameterResolutionException when the parameter is not of type {@link LocalRemote} or the remote
     *                                      cannot be created
     */
    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        LocalRepositoryExtension.requireType(parameterContext, Remote.class, LocalRemote.class);
        var temporary = LocalRepositoryExtension.createBareRepository(Remote.class);
        extensionContext.getStore(NAMESPACE).put(LocalRepositoryExtension.storeKey(parameterContext), temporary);
        try {
            return new LocalRemote(temporary.repository(), new URIish(temporary.directory().toUri().toString()));
        } catch (URISyntaxException e) {
            throw new ParameterResolutionException(
                    "@Remote could not derive a file: URL for " + temporary.directory(), e);
        }
    }
}
