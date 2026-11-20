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
 * Repository registry, typed configuration, codec interface, outcomes and service interfaces of the store.
 * <p>
 * The module requires {@code de.cuioss.git.document.core} transitively, because its public API exposes types of that
 * module and of JGit.
 */
module de.cuioss.git.document.store {
    exports de.cuioss.git.document.store;

    requires transitive de.cuioss.git.document.core;
    requires de.cuioss.java.tools;
    requires static lombok;
    requires static org.jspecify;
}
