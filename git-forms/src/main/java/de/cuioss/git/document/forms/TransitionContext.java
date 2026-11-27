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

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


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
 * transition, the release transition a retry resumes with the commit it releases, and the step of a save, which gets the layout and the numbering of a numbered kind for a new entry whose
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
     * Finds the transition on {@code release} that a retry resumes (GDR-FORMS-5): a transition of that name whose
     * {@code from} is not the current state, but whose mechanism is {@code release} and whose {@code to} is the current
     * state, as after a release transition whose save succeeded and whose release did not.
     *
     * @param name  the name of the transition
     * @param state the current state of the document or entry
     * @return the transition, or empty when the workflow has no such transition
     */
    Optional<Transition> resumable(String name, String state) {
        return definition.workflow().transition(name)
                .filter(found -> found.mechanism() == Mechanism.RELEASE && found.to().equals(state));
    }

    /**
     * The commit a resumed release releases, when the clone shows the release of the saved state not complete: the tip
     * of the local target branch of the key, whose file of the document or entry carries the {@code to} of the
     * transition as its state, unless a release tag of the key points at a commit whose file is byte for byte the file
     * of that tip. The caller fetches first, so the clone mirrors the remote's branches and tags, an unpushed local tag
     * of a failed release attempt pruned; the release of the store fetches again and refuses, by its pin, a branch that
     * moved since.
     *
     * @param key          the document or entry
     * @param documentPath the path of its file
     * @param release      the transition on {@code release} that is resumed
     * @return the full id of the tip to release, or empty when there is no target branch, its tip holds no file of the
     *         document or entry or one in another state, or a release tag already holds that file
     * @throws CloneReader.ReadFailure for an I/O failure while reading the clone, a tagged commit the clone does not
     *                                 hold included
     */
    Optional<String> unreleasedTip(DocumentKey key, String documentPath, Transition release) {
        Optional<String> tip = clone.tip(StoreReferences.target(key));
        if (tip.isEmpty()) {
            return Optional.empty();
        }
        Optional<byte[]> saved = clone.readAt(tip.get(), documentPath);
        FormsResult<String> state = TransitionStep.readState(codec, saved, documentPath, initialState());
        if (saved.isEmpty() || !state.isOk() || !release.to().equals(state.value())) {
            return Optional.empty();
        }
        for (String tagged : clone.releaseTagTargets(key)) {
            Optional<byte[]> released = clone.readAt(tagged, documentPath);
            if (released.isPresent() && Arrays.equals(released.get(), saved.get())) {
                return Optional.empty();
            }
        }
        return tip;
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
