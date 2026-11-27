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

import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.forms.DocumentTransitions.Definition;
import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.Ulid;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * What one kind brings to the transitions of {@link DocumentTransitions}: the reader of the writer's bare clone, the
 * layout, the codec and the output check of the kind, the resolution of link targets of the application, and the
 * lookups per key that the transitions share (the
 * collection check, the path of the file, the branch the current state is read from, the current state, the
 * transition and the step of a save, which gets the layout and the numbering of a numbered kind for a new entry whose
 * key is the ULID of its draft, GDR-FORMS-9).
 */
final class TransitionContext {

    private final Definition definition;
    private final CloneReader clone;
    private final DocumentLayout layout;
    private final YamlDocumentCodec codec;
    private final OutputCheck output;
    private final LinkTargets links;

    /**
     * Creates the context of a kind.
     *
     * @param repository the bare clone of the writer
     * @param definition the kind, its schema, its workflow and its template
     * @param links      the resolution of the targets of the link fields, handed to every step
     * @throws NullPointerException     if {@code repository} is {@code null}
     * @throws IllegalArgumentException if the layout of the kind cannot be created ({@link DocumentLayout})
     */
    TransitionContext(Repository repository, Definition definition, LinkTargets links) {
        this.clone = new CloneReader(repository);
        this.definition = definition;
        this.layout = new DocumentLayout(definition.kind(), definition.schema());
        this.codec = new YamlDocumentCodec(definition.schema());
        this.output = new OutputCheck(definition.kind(), definition.schema(), definition.template());
        this.links = links;
    }

    /**
     * The reader of the writer's bare clone.
     *
     * @return the reader
     */
    CloneReader reader() {
        return clone;
    }

    /**
     * The codec of the file of a document or entry.
     *
     * @return the codec
     */
    YamlDocumentCodec codec() {
        return codec;
    }

    /**
     * Checks that a key is of the collection of the kind.
     *
     * @param key the document or entry
     * @throws IllegalArgumentException if the key is not of the collection of the kind
     */
    void requireCollection(DocumentKey key) {
        Kind kind = definition.kind();
        DocumentCollection collection = key.collection();
        DocumentCollection.Kind expected = kind.isNumbered() ? DocumentCollection.Kind.NUMBERED
                : DocumentCollection.Kind.PER_DOCUMENT;
        if (!collection.name().equals(kind.collection()) || collection.kind() != expected) {
            throw new IllegalArgumentException("key: not of the collection of the kind");
        }
    }

    /**
     * The path of the file of a document or entry.
     *
     * @param key the document or entry, of the collection of the kind
     * @return the repository-relative path of {@value DocumentLayout#DOCUMENT_FILE} or of the entry file
     */
    String documentPath(DocumentKey key) {
        FormsResult<String> path = definition.kind().isNumbered() ? layout.entry(key.id()) : layout.document(key.id());
        return Objects.requireNonNull(path.value(), "a key holds a valid name segment");
    }

    /**
     * The initial state of the workflow of the kind.
     *
     * @return the initial state
     */
    String initialState() {
        return definition.workflow().initialState();
    }

    /**
     * The branch the current state is read from: the local target branch of the key, else {@code main}.
     *
     * @param key the document or entry
     * @return the full name of the branch
     * @throws CloneReader.ReadFailure for an I/O failure while reading the clone
     */
    String branchOf(DocumentKey key) {
        String target = StoreReferences.target(key);
        return clone.exists(target) ? target : StoreReferences.MAIN;
    }

    /**
     * Reads the current state of a document or entry from its file on {@link #branchOf(DocumentKey)}.
     *
     * @param key          the document or entry
     * @param documentPath the path of its file
     * @return the state, see {@link TransitionStep#readState}
     * @throws CloneReader.ReadFailure for an I/O failure while reading the clone
     */
    FormsResult<String> currentState(DocumentKey key, String documentPath) {
        return TransitionStep.readState(codec, clone.read(branchOf(key), documentPath), documentPath,
                initialState());
    }

    /**
     * Looks a transition of the workflow of the kind up.
     *
     * @param name  the name of the transition
     * @param state the current state of the document or entry
     * @return the transition, see {@link Workflow#transition(String, String)}
     */
    FormsResult<Transition> transition(String name, String state) {
        return definition.workflow().transition(name, state);
    }

    /**
     * The step of a save of a call, merging the file against a base.
     *
     * @param call the transition
     * @param base the decoded file the draft started from, empty when there was none
     * @return a fresh step
     */
    TransitionStep step(TransitionCall call, Map<String, @Nullable Object> base) {
        return new TransitionStep(call.transition(), initialState(), definition.schema(), output, links,
                call.key().id(), call.documentPath(), base, allocation(call.key()));
    }

    /**
     * What the step needs to allocate the running number of an entry (GDR-FORMS-9): the layout and the numbering of a
     * numbered kind, when the id of the key is the ULID of a draft that carries no running number yet.
     *
     * @param key the document or entry
     * @return the allocation, or {@code null} for a per-document kind or an entry whose key carries its number
     */
    private TransitionStep.@Nullable Allocation allocation(DocumentKey key) {
        Kind kind = definition.kind();
        @Nullable Numbering numbering = kind.numbering();
        if (numbering == null || !Ulid.isValid(key.id()) || RunningNumbers.isAssigned(kind, key)) {
            return null;
        }
        return new TransitionStep.Allocation(layout, numbering);
    }
}
