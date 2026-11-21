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
 * Schema-driven documents on top of the store: kinds of documents, schema validation, data layout and codec.
 * <p>
 * The module requires {@code de.cuioss.git.document.store} transitively, because its public result
 * {@code FormsResult} exposes the outcome type {@code StoreResult.Outcome} of that module when it passes on a
 * non-success of the store, and its {@code KindConfiguration} reads a JGit {@code Repository}. The YAML library is
 * the module {@code org.snakeyaml.engine} (named by the {@code module-info.class} of snakeyaml-engine 3.2); no type of
 * it appears in the public API.
 */
module de.cuioss.git.document.forms {
    exports de.cuioss.git.document.forms;

    requires transitive de.cuioss.git.document.store;
    requires org.snakeyaml.engine;
    requires static lombok;
    requires static org.jspecify;
}
