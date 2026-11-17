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

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.URIish;

/**
 * A local remote for fetch and push tests: a bare repository in a temporary directory together with the URL a client
 * reaches it by. Resolved for a parameter annotated with {@link Remote}.
 *
 * @param repository the bare repository of the remote; closed by the fixture after the test
 * @param uri        the {@code file:} URL of the bare repository, the form the local-only remote policy admits
 * @since 0.1
 */
public record LocalRemote(Repository repository, URIish uri) {
}
