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
 * Schema-driven documents on top of the store: the closed outcome list {@link de.cuioss.git.document.forms.FormsOutcome}
 * with the result {@link de.cuioss.git.document.forms.FormsResult} and the log messages of {@code git-forms}; the kinds
 * of documents as configuration ({@link de.cuioss.git.document.forms.KindConfiguration}); the schema validation of a
 * JSON Schema of Draft 2020-12 ({@link de.cuioss.git.document.forms.FormSchema},
 * {@link de.cuioss.git.document.forms.SchemaValidator}); and the data layout and codec
 * ({@link de.cuioss.git.document.forms.DocumentLayout}, {@link de.cuioss.git.document.forms.YamlDocumentCodec},
 * {@link de.cuioss.git.document.forms.CounterCodec}).
 * <p>
 * The keyword {@code format} is an annotation only: the validation never asserts it.
 */
@NullMarked
package de.cuioss.git.document.forms;

import org.jspecify.annotations.NullMarked;
