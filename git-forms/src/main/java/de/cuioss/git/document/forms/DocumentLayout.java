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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The data layout of a kind of document (GDR-FORMS-3): the paths of its files and the split of its data into parts.
 * <p>
 * A per-document kind stores a document in the directory {@code <collection>/<ulid>/} of its working branch: the
 * structured fields in {@value #DOCUMENT_FILE}, every free-text fragment as {@code narrative/<name>.adoc}, and every part
 * the schema marks with {@value FormSchema#SPLIT} as {@code <property>.yaml}. A numbered kind stores an entry as
 * {@code <collection>/<number>.yaml} on its collection branch, beside the counter file {@code <collection>/}{@value
 * #COUNTER_FILE}. A kind with a template has generated output (GDR-FORMS-4): {@code <collection>/<ulid>/}{@value
 * #OUTPUT_FILE} for a per-document kind, {@code <collection>/<number>.adoc} for a numbered kind. The output is the
 * one file of the layout that is never merged: a save regenerates it from the data of its commit
 * ({@link #isOutput(String)}). The allocation of running numbers is not part of the layout.
 * <p>
 * Every name segment a caller supplies (a ULID, a fragment name, a running number) is checked: it is not empty and
 * holds no {@code /} and no leading {@code .}, which excludes {@code .} and {@code ..}; a refused segment is
 * {@link FormsOutcome#INVALID}, its detail naming the argument, never its value. Calling a per-document path on a
 * numbered kind, or the reverse, is a programming error. The collection of the kind and every split property of the
 * schema are checked when the layout is created: each must be a name segment, and no split property may be stored as
 * {@value #DOCUMENT_FILE}. A layout is immutable and may serve every thread.
 *
 * @since 0.1
 */
public final class DocumentLayout {

    /** The file of the structured fields of a document of a per-document kind. */
    public static final String DOCUMENT_FILE = "document.yaml";

    /** The directory of the free-text fragments of a document of a per-document kind. */
    public static final String NARRATIVE_DIRECTORY = "narrative";

    /** The counter file of a numbered kind. */
    public static final String COUNTER_FILE = ".counter";

    /** The generated output of a document of a per-document kind. */
    public static final String OUTPUT_FILE = "report.adoc";

    private static final String YAML = ".yaml";
    private static final String ADOC = ".adoc";

    private final Kind kind;
    private final FormSchema schema;

    /**
     * Creates the layout of a kind.
     *
     * @param kind   the kind of document
     * @param schema the schema of the kind, which declares the split parts
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if the collection of the kind is no name segment, or a split property of the
     *                                  schema is no name segment or would be stored as {@value #DOCUMENT_FILE}
     */
    public DocumentLayout(Kind kind, FormSchema schema) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.schema = Objects.requireNonNull(schema, "schema");
        if (!isSegment(kind.collection())) {
            throw new IllegalArgumentException("collection: not a name segment");
        }
        for (String property : schema.splitProperties()) {
            if (!isSegment(property)) {
                throw new IllegalArgumentException(FormSchema.SPLIT + ": a split property that is no name segment");
            }
            if (DOCUMENT_FILE.equals(property + YAML)) {
                throw new IllegalArgumentException(
                        FormSchema.SPLIT + ": a split property stored as " + DOCUMENT_FILE);
            }
        }
    }

    /**
     * The path of the structured fields of a document.
     *
     * @param ulid the ULID of the document
     * @return {@link FormsOutcome#OK} with {@code <collection>/<ulid>/document.yaml}, or {@link FormsOutcome#INVALID}
     *         for a ULID that is no name segment
     * @throws NullPointerException  if {@code ulid} is {@code null}
     * @throws IllegalStateException if the kind is numbered
     */
    public FormsResult<String> document(String ulid) {
        requirePerDocument();
        return directory(ulid, DOCUMENT_FILE);
    }

    /**
     * The path of a free-text fragment of a document.
     *
     * @param ulid the ULID of the document
     * @param name the name of the fragment, without the extension
     * @return {@link FormsOutcome#OK} with {@code <collection>/<ulid>/narrative/<name>.adoc}, or
     *         {@link FormsOutcome#INVALID} for an argument that is no name segment
     * @throws NullPointerException  if an argument is {@code null}
     * @throws IllegalStateException if the kind is numbered
     */
    public FormsResult<String> narrative(String ulid, String name) {
        requirePerDocument();
        Objects.requireNonNull(name, "name");
        if (!isSegment(name)) {
            return invalid("name");
        }
        return directory(ulid, NARRATIVE_DIRECTORY + "/" + name + ADOC);
    }

    /**
     * The path of the generated output of a document.
     *
     * @param ulid the ULID of the document
     * @return {@link FormsOutcome#OK} with {@code <collection>/<ulid>/report.adoc}, or {@link FormsOutcome#INVALID}
     *         for a ULID that is no name segment
     * @throws NullPointerException  if {@code ulid} is {@code null}
     * @throws IllegalStateException if the kind is numbered
     */
    public FormsResult<String> output(String ulid) {
        requirePerDocument();
        return directory(ulid, OUTPUT_FILE);
    }

    /**
     * The path of the generated output of an entry of a numbered kind with a template.
     *
     * @param number the running number of the entry, for example {@code R-042}
     * @return {@link FormsOutcome#OK} with {@code <collection>/<number>.adoc}, or {@link FormsOutcome#INVALID} for a
     *         number that is no name segment
     * @throws NullPointerException  if {@code number} is {@code null}
     * @throws IllegalStateException if the kind is per-document, or numbered without a template, which has no output
     */
    public FormsResult<String> entryOutput(String number) {
        requireNumbered();
        Objects.requireNonNull(number, "number");
        if (!hasOutput()) {
            throw new IllegalStateException("a kind without a template has no generated output");
        }
        if (!isSegment(number)) {
            return invalid("number");
        }
        return FormsResult.ok(kind.collection() + "/" + number + ADOC);
    }

    /**
     * Tells whether the kind has generated output: whether it has a template.
     *
     * @return whether the kind declares a template
     */
    public boolean hasOutput() {
        return kind.template() != null;
    }

    /**
     * Tells whether a path is the generated output of a document or entry of the kind, the one file of the layout
     * that is never merged but regenerated by every save (GDR-FORMS-4).
     *
     * @param path a repository-relative path
     * @return whether the path is {@code <collection>/<ulid>/report.adoc} of a per-document kind or
     *         {@code <collection>/<number>.adoc} of a numbered kind with a template; always {@code false} for a kind
     *         without a template
     * @throws NullPointerException if {@code path} is {@code null}
     */
    public boolean isOutput(String path) {
        Objects.requireNonNull(path, "path");
        String prefix = kind.collection() + "/";
        if (!hasOutput() || !path.startsWith(prefix)) {
            return false;
        }
        String rest = path.substring(prefix.length());
        if (kind.isNumbered()) {
            return rest.endsWith(ADOC) && isSegment(rest.substring(0, rest.length() - ADOC.length()));
        }
        String suffix = "/" + OUTPUT_FILE;
        return rest.endsWith(suffix) && isSegment(rest.substring(0, rest.length() - suffix.length()));
    }

    /**
     * The path of a split part of a document.
     *
     * @param ulid     the ULID of the document
     * @param property a property the schema marks with {@value FormSchema#SPLIT}
     * @return {@link FormsOutcome#OK} with {@code <collection>/<ulid>/<property>.yaml}, or
     *         {@link FormsOutcome#INVALID} for a ULID that is no name segment or a property that is no split part
     * @throws NullPointerException  if an argument is {@code null}
     * @throws IllegalStateException if the kind is numbered
     */
    public FormsResult<String> part(String ulid, String property) {
        requirePerDocument();
        Objects.requireNonNull(property, "property");
        if (!schema.splitProperties().contains(property)) {
            return invalid("property");
        }
        return directory(ulid, property + YAML);
    }

    /**
     * The path of an entry of a numbered kind.
     *
     * @param number the running number of the entry, for example {@code R-042}
     * @return {@link FormsOutcome#OK} with {@code <collection>/<number>.yaml}, or {@link FormsOutcome#INVALID} for a
     *         number that is no name segment
     * @throws NullPointerException  if {@code number} is {@code null}
     * @throws IllegalStateException if the kind is per-document
     */
    public FormsResult<String> entry(String number) {
        requireNumbered();
        Objects.requireNonNull(number, "number");
        if (!isSegment(number)) {
            return invalid("number");
        }
        return FormsResult.ok(kind.collection() + "/" + number + YAML);
    }

    /**
     * The path of the counter file of a numbered kind.
     *
     * @return {@code <collection>/.counter}
     * @throws IllegalStateException if the kind is per-document
     */
    public String counter() {
        requireNumbered();
        return kind.collection() + "/" + COUNTER_FILE;
    }

    /**
     * Splits the data of a document into the content of {@value #DOCUMENT_FILE} and its parts: every split property the data
     * holds as an object becomes a part; every other value stays in the document.
     *
     * @param data the data of the document
     * @return the split, the document keeping the order of the data, the parts in declaration order
     * @throws NullPointerException if {@code data} is {@code null}
     */
    public Split split(Map<String, ?> data) {
        Objects.requireNonNull(data, "data");
        Map<String, @Nullable Object> document = new LinkedHashMap<>(data);
        Map<String, Map<String, @Nullable Object>> parts = new LinkedHashMap<>();
        for (String property : schema.splitProperties()) {
            if (document.get(property) instanceof Map<?, ?> part) {
                document.remove(property);
                parts.put(property, copy(part));
            }
        }
        return new Split(document, parts);
    }

    /**
     * Joins the content of {@value #DOCUMENT_FILE} and its parts back into the data of a document.
     *
     * @param document the content of {@value #DOCUMENT_FILE}
     * @param parts    the parts by property
     * @return {@link FormsOutcome#OK} with the data, the parts after the fields of the document in declaration order;
     *         the passed-on {@link StoreOutcome#MALFORMED} when the document holds a property that is also given as a
     *         part
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if a part is given for a property that is no split part
     */
    public FormsResult<Map<String, @Nullable Object>> join(Map<String, ?> document,
            Map<String, ? extends Map<String, ?>> parts) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(parts, "parts");
        for (String property : parts.keySet()) {
            if (!schema.splitProperties().contains(property)) {
                throw new IllegalArgumentException("parts: a property that is no split part");
            }
            if (document.containsKey(property)) {
                return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED,
                        DOCUMENT_FILE + ": holds a property that is stored as a part"));
            }
        }
        Map<String, @Nullable Object> data = new LinkedHashMap<>(document);
        for (String property : schema.splitProperties()) {
            Map<String, ?> part = parts.get(property);
            if (part != null) {
                data.put(property, copy(part));
            }
        }
        return FormsResult.ok(data);
    }

    private FormsResult<String> directory(String ulid, String file) {
        Objects.requireNonNull(ulid, "ulid");
        if (!isSegment(ulid)) {
            return invalid("ulid");
        }
        return FormsResult.ok(kind.collection() + "/" + ulid + "/" + file);
    }

    private void requirePerDocument() {
        if (kind.isNumbered()) {
            throw new IllegalStateException("a numbered kind stores entries, not documents");
        }
    }

    private void requireNumbered() {
        if (!kind.isNumbered()) {
            throw new IllegalStateException("a per-document kind has no entries and no counter");
        }
    }

    /** Whether a text is a name segment: not empty, no {@code /}, no leading {@code .}. */
    private static boolean isSegment(String text) {
        return !text.isEmpty() && text.indexOf('/') < 0 && text.charAt(0) != '.';
    }

    private static <T> FormsResult<T> invalid(String argument) {
        return FormsResult.of(FormsOutcome.INVALID, argument + ": not a name segment");
    }

    private static Map<String, @Nullable Object> copy(Map<?, ?> map) {
        Map<String, @Nullable Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> copy.put((String) key, value));
        return copy;
    }

    /**
     * The data of a document split into files.
     *
     * @param document the content of {@value #DOCUMENT_FILE}
     * @param parts    the parts by property, in declaration order
     * @since 0.1
     */
    public record Split(Map<String, @Nullable Object> document, Map<String, Map<String, @Nullable Object>> parts) {

        /**
         * Checks and keeps the components; the maps keep their order.
         *
         * @param document the content of {@value #DOCUMENT_FILE}
         * @param parts    the parts by property
         * @throws NullPointerException if an argument is {@code null}
         */
        public Split {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(parts, "parts");
            document = Collections.unmodifiableMap(new LinkedHashMap<>(document));
            parts = Collections.unmodifiableMap(new LinkedHashMap<>(parts));
        }
    }
}
