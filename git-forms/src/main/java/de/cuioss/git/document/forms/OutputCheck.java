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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RepositoryReader;
import de.cuioss.git.document.core.RepositoryReader.Snapshot;
import de.cuioss.git.document.core.RepositoryReader.SnapshotFile;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The check of the generated output of a kind (GDR-FORMS-4): it regenerates the output of every document or entry of a
 * commit from the data and the fragments of that commit and compares it byte for byte with the committed output file.
 * <p>
 * A per-document kind is checked for every {@code <collection>/<ulid>/}{@value DocumentLayout#DOCUMENT_FILE} of the
 * commit: its data are {@value DocumentLayout#DOCUMENT_FILE} joined with its split parts, its fragments every
 * {@code narrative/<name>.adoc} of its directory, and its output {@code <collection>/<ulid>/}{@value
 * DocumentLayout#OUTPUT_FILE}. A numbered kind is checked for every entry {@code <collection>/<number>.yaml}, without
 * fragments, against {@code <collection>/<number>.adoc}. A missing output file mismatches. A kind without a template,
 * which only a numbered kind may be, generates nothing and checks nothing.
 * <p>
 * The check reads the commit through {@link RepositoryReader#snapshot(Repository, String)} of {@code git-core} and
 * writes nothing; it needs neither the application nor a network. A file larger than {@value #MAX_FILE_BYTES} bytes is
 * refused unread. A detail names a path of the commit and the problem, never data (GDR-NFR-3); a mismatch names the
 * output paths only. A check is immutable and may serve every thread.
 *
 * @since 0.1
 */
public final class OutputCheck {

    /** The largest file of a document the check reads, in bytes (16 MiB); a larger one is refused unread. */
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

    private static final String YAML = ".yaml";
    private static final String ADOC = ".adoc";

    private final Kind kind;
    private final FormSchema schema;
    private final DocumentLayout layout;
    private final YamlDocumentCodec codec;
    private final @Nullable DocumentTemplate template;

    /**
     * Creates the check of a kind.
     *
     * @param kind     the kind of document
     * @param schema   the schema of the kind
     * @param template the template of the kind, {@code null} exactly when the kind declares none
     * @throws NullPointerException     if {@code kind} or {@code schema} is {@code null}
     * @throws IllegalArgumentException if a template is given for a kind without one or none for a kind with one, or
     *                                  the layout of the kind cannot be created ({@link DocumentLayout})
     */
    public OutputCheck(Kind kind, FormSchema schema, @Nullable DocumentTemplate template) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.schema = Objects.requireNonNull(schema, "schema");
        if ((kind.template() == null) != (template == null)) {
            throw new IllegalArgumentException("template: given exactly when the kind declares one");
        }
        this.template = template;
        this.layout = new DocumentLayout(kind, schema);
        this.codec = new YamlDocumentCodec(schema);
    }

    /**
     * Checks the generated output of every document or entry of the kind in a commit.
     *
     * @param repository the repository
     * @param revision   a reference, by its short or full name, or a full commit id
     * @return {@link FormsOutcome#OK} with the checked output paths, sorted, when every output matches (none for a
     *         kind without a template); {@link FormsOutcome#MISMATCH} whose detail names the mismatching output paths;
     *         the Git outcome of the snapshot passed on when the revision cannot be resolved; the passed-on
     *         {@link StoreOutcome#MALFORMED} for data, a part or a fragment that cannot be read; the passed-on
     *         {@link GitOutcome#FAILED} for an I/O failure
     * @throws NullPointerException if an argument is {@code null}
     */
    public FormsResult<List<String>> check(Repository repository, String revision) {
        FormsResult<Comparison> compared = compare(repository, revision);
        if (!compared.isOk()) {
            return new FormsResult<>(compared.outcome(), null, compared.detail());
        }
        Comparison comparison = Objects.requireNonNull(compared.value());
        if (!comparison.mismatching().isEmpty()) {
            return FormsResult.of(FormsOutcome.MISMATCH,
                    "output mismatch: " + String.join(", ", comparison.mismatching()));
        }
        return FormsResult.ok(comparison.checked());
    }

    /**
     * Compares the generated output of every document or entry of the kind in a commit.
     *
     * @param repository the repository
     * @param revision   the revision
     * @return {@link FormsOutcome#OK} with the checked and the mismatching output paths, or the refusal of
     *         {@link #check(Repository, String)}
     */
    FormsResult<Comparison> compare(Repository repository, String revision) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(revision, "revision");
        if (template == null) {
            return FormsResult.ok(new Comparison(List.of(), List.of()));
        }
        GitResult<Snapshot> snapshot = new RepositoryReader().snapshot(repository, revision);
        if (!snapshot.isOk()) {
            return FormsResult.passOn(StoreResult.passOn(snapshot));
        }
        NavigableMap<String, String> blobs = new TreeMap<>();
        for (SnapshotFile file : Objects.requireNonNull(snapshot.value()).files()) {
            blobs.put(file.path(), file.blobId());
        }
        try (ObjectReader reader = repository.newObjectReader()) {
            DocumentFiles files = new SnapshotFiles(blobs, reader);
            List<String> checked = new ArrayList<>();
            List<String> mismatching = new ArrayList<>();
            for (String unit : units(blobs.keySet())) {
                String output = Objects.requireNonNull(outputPath(unit).value());
                FormsResult<byte[]> generated = generate(files, unit);
                if (!generated.isOk()) {
                    return new FormsResult<>(generated.outcome(), null, generated.detail());
                }
                Optional<byte[]> committed = files.content(output);
                checked.add(output);
                if (committed.isEmpty() || !Arrays.equals(committed.get(), generated.value())) {
                    mismatching.add(output);
                }
            }
            return FormsResult.ok(new Comparison(checked.stream().sorted().toList(),
                    mismatching.stream().sorted().toList()));
        } catch (Unreadable e) {
            return malformed(e.path, e.problem);
        } catch (ReadFailure e) {
            return FormsResult.passOn(StoreResult.passOn(GitResult.of(GitOutcome.FAILED,
                    "read: " + e.getCause().getClass().getName())));
        }
    }

    /**
     * Generates the output of one document or entry from the files of a commit or of an attempt of a save.
     *
     * @param files the files
     * @param unit  the ULID of a document of a per-document kind, or the running number of an entry
     * @return {@link FormsOutcome#OK} with the UTF-8 content of the output; {@link FormsOutcome#INVALID} for a unit
     *         that is no name segment; the passed-on {@link StoreOutcome#MALFORMED} for data, a part or a fragment that
     *         is missing or cannot be read, for a rendering that reached a limit of {@link DocumentTemplate}, and for
     *         an output larger than {@value #MAX_FILE_BYTES} bytes, which the check could not read back, its detail
     *         naming the output path and the bound
     * @throws IllegalStateException if the kind has no template
     */
    FormsResult<byte[]> generate(DocumentFiles files, String unit) {
        DocumentTemplate active = template;
        if (active == null) {
            throw new IllegalStateException("a kind without a template generates no output");
        }
        FormsResult<Map<String, @Nullable Object>> data = data(files, unit);
        if (!data.isOk()) {
            return new FormsResult<>(data.outcome(), null, data.detail());
        }
        FormsResult<Map<String, String>> fragments = fragments(files, unit);
        if (!fragments.isOk()) {
            return new FormsResult<>(fragments.outcome(), null, fragments.detail());
        }
        FormsResult<String> output = active.render(Objects.requireNonNull(data.value()),
                Objects.requireNonNull(fragments.value()));
        if (!output.isOk()) {
            return new FormsResult<>(output.outcome(), null, output.detail());
        }
        byte[] bytes = Objects.requireNonNull(output.value()).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            return malformed(Objects.requireNonNull(outputPath(unit).value()), tooLarge());
        }
        return FormsResult.ok(bytes);
    }

    /**
     * Whether the kind generates an output: exactly when it has a template.
     *
     * @return whether {@link #generate(DocumentFiles, String)} may be called
     */
    boolean generates() {
        return template != null;
    }

    /**
     * The path of the output of one document or entry.
     *
     * @param unit the ULID or the running number
     * @return the path, or {@link FormsOutcome#INVALID} for a unit that is no name segment
     * @throws IllegalStateException if the kind has no template
     */
    FormsResult<String> outputPath(String unit) {
        return kind.isNumbered() ? layout.entryOutput(unit) : layout.output(unit);
    }

    /** The ULIDs of the documents or the numbers of the entries of the kind among the paths of a commit, sorted. */
    private List<String> units(Iterable<String> paths) {
        String prefix = kind.collection() + "/";
        String document = "/" + DocumentLayout.DOCUMENT_FILE;
        TreeSet<String> units = new TreeSet<>();
        for (String path : paths) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String rest = path.substring(prefix.length());
            String suffix = kind.isNumbered() ? YAML : document;
            if (rest.endsWith(suffix)) {
                String unit = rest.substring(0, rest.length() - suffix.length());
                if (!unit.isEmpty() && unit.indexOf('/') < 0 && unit.charAt(0) != '.') {
                    units.add(unit);
                }
            }
        }
        return List.copyOf(units);
    }

    /**
     * Reads the data of one document, joined with its parts, or of one entry, from the files of a commit or of an
     * attempt of a save.
     *
     * @param files the files
     * @param unit  the ULID of a document of a per-document kind, or the running number of an entry
     * @return {@link FormsOutcome#OK} with the data; {@link FormsOutcome#INVALID} for a unit that is no name segment;
     *         the passed-on {@link StoreOutcome#MALFORMED} for data or a part that is missing or cannot be read
     */
    FormsResult<Map<String, @Nullable Object>> data(DocumentFiles files, String unit) {
        FormsResult<String> path = kind.isNumbered() ? layout.entry(unit) : layout.document(unit);
        if (!path.isOk()) {
            return new FormsResult<>(path.outcome(), null, path.detail());
        }
        FormsResult<Map<String, @Nullable Object>> document = decode(files, Objects.requireNonNull(path.value()), codec);
        if (!document.isOk() || kind.isNumbered()) {
            return document;
        }
        Map<String, Map<String, @Nullable Object>> parts = new LinkedHashMap<>();
        for (String property : schema.splitProperties()) {
            String partPath = Objects.requireNonNull(layout.part(unit, property).value());
            if (files.content(partPath).isPresent()) {
                FormsResult<Map<String, @Nullable Object>> part = decode(files, partPath, codec.part(property));
                if (!part.isOk()) {
                    return part;
                }
                parts.put(property, Objects.requireNonNull(part.value()));
            }
        }
        return layout.join(Objects.requireNonNull(document.value()), parts);
    }

    private static FormsResult<Map<String, @Nullable Object>> decode(DocumentFiles files, String path,
            YamlDocumentCodec codec) {
        Optional<byte[]> content = files.content(path);
        if (content.isEmpty()) {
            return malformed(path, "missing file");
        }
        StoreResult<Map<String, @Nullable Object>> decoded = codec.decode(content.get());
        if (!decoded.isOk()) {
            return malformed(path, Objects.requireNonNull(decoded.detail()));
        }
        return FormsResult.ok(Objects.requireNonNull(decoded.value()));
    }

    /** The narrative fragments of one document by name; none for an entry. */
    private FormsResult<Map<String, String>> fragments(DocumentFiles files, String unit) {
        Map<String, String> fragments = new TreeMap<>();
        if (kind.isNumbered()) {
            return FormsResult.ok(fragments);
        }
        String directory = kind.collection() + "/" + unit + "/" + DocumentLayout.NARRATIVE_DIRECTORY;
        String prefix = directory + "/";
        for (String path : files.paths(directory)) {
            String name = path.substring(prefix.length());
            if (!name.endsWith(ADOC) || name.indexOf('/') >= 0) {
                continue;
            }
            Optional<byte[]> content = files.content(path);
            String text = content.map(YamlReader::decodeUtf8).orElse(null);
            if (text == null) {
                return malformed(path, "not UTF-8");
            }
            fragments.put(name.substring(0, name.length() - ADOC.length()), text);
        }
        return FormsResult.ok(fragments);
    }

    private static <T> FormsResult<T> malformed(String path, String problem) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, path + ": " + problem));
    }

    /** The problem of a file larger than {@link #MAX_FILE_BYTES}, the same for a read file and a generated output. */
    private static String tooLarge() {
        return "too large, more than " + MAX_FILE_BYTES + " bytes";
    }

    /**
     * The checked and the mismatching output paths of a commit.
     *
     * @param checked     every checked output path, sorted
     * @param mismatching the output paths whose committed content differs from the regenerated one, or that are
     *                    missing, sorted
     */
    record Comparison(List<String> checked, List<String> mismatching) {

        Comparison {
            checked = List.copyOf(checked);
            mismatching = List.copyOf(mismatching);
        }
    }

    /**
     * The files a document is generated from: those of a commit, or the tree of an attempt of a save.
     */
    interface DocumentFiles {

        /**
         * The content of a file.
         *
         * @param path the repository-relative path
         * @return the content, or empty when there is no file at the path
         */
        Optional<byte[]> content(String path);

        /**
         * The paths of every file below a directory, at any depth.
         *
         * @param directory the repository-relative directory
         * @return the file paths, sorted and distinct
         */
        List<String> paths(String directory);
    }

    /** The files of a snapshot, read through an object reader. */
    private static final class SnapshotFiles implements DocumentFiles {

        private final NavigableMap<String, String> blobs;
        private final ObjectReader reader;

        SnapshotFiles(NavigableMap<String, String> blobs, ObjectReader reader) {
            this.blobs = blobs;
            this.reader = reader;
        }

        @Override
        public Optional<byte[]> content(String path) {
            String blobId = blobs.get(path);
            if (blobId == null) {
                return Optional.empty();
            }
            try {
                ObjectLoader loader = reader.open(ObjectId.fromString(blobId), Constants.OBJ_BLOB);
                // JGit 7.8.0 ObjectLoader.getBytes(int) returns a cached object whole, so the size is checked first.
                if (loader.getSize() > MAX_FILE_BYTES) {
                    throw new Unreadable(path, tooLarge());
                }
                return Optional.of(loader.getBytes(MAX_FILE_BYTES));
            } catch (IOException e) {
                throw new ReadFailure(e);
            }
        }

        @Override
        public List<String> paths(String directory) {
            String prefix = directory + "/";
            // The keys of a sorted map that start with a prefix follow each other from the prefix on.
            return blobs.tailMap(prefix, true).keySet().stream().takeWhile(path -> path.startsWith(prefix)).toList();
        }
    }

    /** A file that is refused unread; its message is unused. */
    private static final class Unreadable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String path;
        private final String problem;

        Unreadable(String path, String problem) {
            super(null, null, false, false);
            this.path = path;
            this.problem = problem;
        }
    }

    /** An I/O failure while reading a blob. */
    private static final class ReadFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        ReadFailure(IOException cause) {
            super(null, cause, false, false);
        }
    }
}
