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
/**
 * Generators and fixtures for local and in-memory Git repositories, a local remote and a git configuration isolated
 * from the host.
 */
module de.cuioss.git.document.testing {
    exports de.cuioss.git.document.testing;

    requires transitive org.eclipse.jgit;
    requires transitive de.cuioss.test.generator;
    requires transitive org.junit.jupiter.api;
    requires static org.jspecify;
}
