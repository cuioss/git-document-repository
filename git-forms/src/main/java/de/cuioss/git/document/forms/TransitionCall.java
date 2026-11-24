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
package de.cuioss.git.document.forms;

import de.cuioss.git.document.forms.DocumentTransitions.Change;
import de.cuioss.git.document.store.Author;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.IdentityClaims;

/**
 * The input of one transition of {@link DocumentTransitions} whose step-up check passed, handed to
 * {@link TransitionSave}.
 *
 * @param author       the author
 * @param claims       the claims of the author's authentication
 * @param key          the document or entry
 * @param transition   the transition
 * @param change       the caller's edits, expected blob ids and message
 * @param documentPath the path of the file of the document or entry
 */
record TransitionCall(Author author, IdentityClaims claims, DocumentKey key, Transition transition, Change change,
String documentPath) {
}
