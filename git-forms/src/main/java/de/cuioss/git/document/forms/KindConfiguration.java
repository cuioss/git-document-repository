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
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.RepositoryReader;
import de.cuioss.git.document.core.RepositoryReader.Snapshot;
import de.cuioss.git.document.core.RepositoryReader.SnapshotFile;
import de.cuioss.git.document.store.RepositoryRegistry;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * The kinds of documents of an application, loaded from configuration only (GDR-FORMS-1): adding a kind of document
 * changes no code.
 * <p>
 * The configuration is the file {@value #FILE} at the root of a configuration directory
 * ({@link #fromDirectory(Path, RepositoryRegistry)}) or of a commit of a configuration repository
 * ({@link #fromRepository(Repository, String, RepositoryRegistry)}). Its single key {@code kinds} lists the kinds in
 * order; each kind has the keys {@code id}, {@code repository}, {@code collection}, {@code schema}, {@code workflow},
 * the key {@code template} (required for a per-document kind, optional for a numbered one) and the optional key
 * {@code numbering} with {@code prefix} and {@code digits}. A kind with {@code numbering} is a numbered kind.
 * <p>
 * A configuration the loader does not accept is the passed-on {@link StoreOutcome#MALFORMED}: text that is not YAML of
 * the JSON data model (see the position in the detail), a missing or unknown key, a value of the wrong type, an id or
 * collection outside {@code [a-z0-9-]+}, a duplicate id, a repository alias the registry does not know, a per-document
 * kind without {@code template}, or {@code digits} less than 1. The detail names the file and the key path
 * ({@code kinds.yaml: kinds[1].template: missing key}, positions counted from 0), never a configured value
 * (GDR-NFR-3). An I/O failure is the passed-on {@link GitOutcome#FAILED}, naming the exception class only.
 *
 * @since 0.1
 */
public final class KindConfiguration {

    /** The name of the configuration file at the root of the directory or the commit. */
    public static final String FILE = "kinds.yaml";

    private static final CuiLogger LOGGER = new CuiLogger(KindConfiguration.class);

    /** The form of an id and a collection: lower-case letters, digits and hyphens. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]+");

    /** The top-level key. */
    private static final String KINDS = "kinds";

    private static final String ID = "id";
    private static final String REPOSITORY = "repository";
    private static final String COLLECTION = "collection";
    private static final String SCHEMA = "schema";
    private static final String TEMPLATE = "template";
    private static final String WORKFLOW = "workflow";
    private static final String NUMBERING = "numbering";
    private static final String PREFIX = "prefix";
    private static final String DIGITS = "digits";

    /** The keys of a kind. */
    private static final Set<String> KIND_KEYS = Set.of(ID, REPOSITORY, COLLECTION, SCHEMA, TEMPLATE, WORKFLOW,
            NUMBERING);

    /** The keys of a numbering. */
    private static final Set<String> NUMBERING_KEYS = Set.of(PREFIX, DIGITS);

    private static final YamlReader YAML = new YamlReader();

    /** The kinds by id, in configuration order. */
    private final Map<String, Kind> kinds;

    private KindConfiguration(Map<String, Kind> kinds) {
        this.kinds = kinds;
    }

    /**
     * Loads the kinds of documents from the file {@value #FILE} at the root of a directory.
     *
     * @param directory    the configuration directory
     * @param repositories the repository registry the alias of every kind must be found in
     * @return {@link FormsOutcome#OK} with the configuration; the passed-on {@link StoreOutcome#MALFORMED} for a
     *         missing file or a configuration the loader does not accept; the passed-on {@link GitOutcome#FAILED} for
     *         an I/O failure
     * @throws NullPointerException if an argument is {@code null}
     */
    public static FormsResult<KindConfiguration> fromDirectory(Path directory, RepositoryRegistry repositories) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(repositories, "repositories");
        byte[] content;
        try {
            content = Files.readAllBytes(directory.resolve(FILE));
        } catch (NoSuchFileException e) {
            return malformed("missing file");
        } catch (IOException e) {
            return failed(e);
        }
        return load(content, repositories, "directory " + directory);
    }

    /**
     * Loads the kinds of documents from the file {@value #FILE} at the root of a commit of a configuration repository.
     * The revision is resolved with {@link RepositoryReader#snapshot(Repository, String)} of {@code git-core}, and the
     * blob is read through the repository's JGit {@link ObjectReader}.
     *
     * @param repository   the configuration repository
     * @param revision     a reference, by its short or full name, or a full commit id
     * @param repositories the repository registry the alias of every kind must be found in
     * @return {@link FormsOutcome#OK} with the configuration; the Git outcome of the snapshot passed on when the
     *         revision cannot be resolved; the passed-on {@link StoreOutcome#MALFORMED} for a missing file or a
     *         configuration the loader does not accept; the passed-on {@link GitOutcome#FAILED} for an I/O failure
     * @throws NullPointerException if an argument is {@code null}
     */
    public static FormsResult<KindConfiguration> fromRepository(Repository repository, String revision,
            RepositoryRegistry repositories) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(repositories, "repositories");
        GitResult<Snapshot> snapshot = new RepositoryReader().snapshot(repository, revision);
        if (!snapshot.isOk()) {
            return FormsResult.passOn(StoreResult.passOn(snapshot));
        }
        Snapshot commit = Objects.requireNonNull(snapshot.value());
        Optional<SnapshotFile> file = commit.files().stream().filter(found -> FILE.equals(found.path())).findFirst();
        if (file.isEmpty()) {
            return malformed("missing file");
        }
        byte[] content;
        try (ObjectReader reader = repository.newObjectReader()) {
            content = reader.open(ObjectId.fromString(file.get().blobId()), Constants.OBJ_BLOB)
                    .getBytes(Integer.MAX_VALUE);
        } catch (IOException e) {
            return failed(e);
        }
        return load(content, repositories, "commit " + commit.commitId());
    }

    /** Decodes, reads and checks the configuration, and logs the loaded kinds. */
    private static FormsResult<KindConfiguration> load(byte[] content, RepositoryRegistry repositories,
            String source) {
        String text = YamlReader.decodeUtf8(content);
        if (text == null) {
            return malformed("not UTF-8");
        }
        FormsResult<Object> read = YAML.read(text);
        if (!read.isOk()) {
            return malformed(Objects.requireNonNull(read.detail()));
        }
        try {
            KindConfiguration configuration = new KindConfiguration(kinds(read.value(), repositories));
            LOGGER.info(GitFormsLogMessages.INFO.KINDS_LOADED, configuration.kinds.size(), source);
            return FormsResult.ok(configuration);
        } catch (Malformed e) {
            return malformed(Objects.requireNonNull(e.getMessage()));
        }
    }

    /** Checks the root of the configuration and builds the kinds by id. */
    private static Map<String, Kind> kinds(@Nullable Object root, RepositoryRegistry repositories) {
        Map<String, @Nullable Object> top = mapping(root, "the root", Set.of(KINDS));
        if (!(require(top, KINDS, KINDS) instanceof List<?> list)) {
            throw new Malformed(KINDS + ": not a list");
        }
        Map<String, Kind> byId = new LinkedHashMap<>();
        for (int index = 0; index < list.size(); index++) {
            String path = KINDS + "[" + index + "]";
            Kind kind = kind(list.get(index), path, repositories);
            if (byId.putIfAbsent(kind.id(), kind) != null) {
                throw new Malformed(path + "." + ID + ": duplicate id");
            }
        }
        return byId;
    }

    /** Checks one kind. */
    private static Kind kind(@Nullable Object entry, String path, RepositoryRegistry repositories) {
        Map<String, @Nullable Object> keys = mapping(entry, path, KIND_KEYS);
        String id = name(keys, ID, path);
        String repository = string(keys, REPOSITORY, path);
        if (repositories.find(repository).isEmpty()) {
            throw new Malformed(path + "." + REPOSITORY + ": unknown alias");
        }
        String collection = name(keys, COLLECTION, path);
        String schema = string(keys, SCHEMA, path);
        String workflow = string(keys, WORKFLOW, path);
        @Nullable String template = keys.containsKey(TEMPLATE) ? string(keys, TEMPLATE, path) : null;
        @Nullable Numbering numbering = keys.containsKey(NUMBERING)
                ? numbering(keys.get(NUMBERING), path + "." + NUMBERING)
                : null;
        if (numbering == null && template == null) {
            throw new Malformed(path + "." + TEMPLATE + ": missing key, required for a per-document kind");
        }
        return new Kind(id, repository, collection, schema, template, workflow, numbering);
    }

    /** Checks the numbering of a kind. */
    private static Numbering numbering(@Nullable Object entry, String path) {
        Map<String, @Nullable Object> keys = mapping(entry, path, NUMBERING_KEYS);
        String prefix = string(keys, PREFIX, path);
        Object digits = require(keys, DIGITS, path + "." + DIGITS);
        if (!(digits instanceof Long count) || count < 1 || count > Integer.MAX_VALUE) {
            throw new Malformed(path + "." + DIGITS + ": not an integer between 1 and " + Integer.MAX_VALUE);
        }
        return new Numbering(prefix, count.intValue());
    }

    /** Checks that a value is a mapping that holds no key outside the allowed ones. */
    @SuppressWarnings("unchecked")
    private static Map<String, @Nullable Object> mapping(@Nullable Object value, String path, Set<String> allowed) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new Malformed(path + ": not a mapping");
        }
        for (Object key : map.keySet()) {
            if (!allowed.contains(key)) {
                throw new Malformed(path + ": unknown key");
            }
        }
        return (Map<String, @Nullable Object>) map;
    }

    /** The value of a required key; a key with the value {@code null} counts as missing. */
    private static Object require(Map<String, @Nullable Object> keys, String key, String path) {
        Object value = keys.get(key);
        if (value == null) {
            throw new Malformed(path + ": missing key");
        }
        return value;
    }

    /** The value of a required key that must be a string that is not blank. */
    private static String string(Map<String, @Nullable Object> keys, String key, String path) {
        String keyPath = path + "." + key;
        if (!(require(keys, key, keyPath) instanceof String text) || text.isBlank()) {
            throw new Malformed(keyPath + ": not a non-blank string");
        }
        return text;
    }

    /** The value of a required key that must be a name: lower-case letters, digits and hyphens. */
    private static String name(Map<String, @Nullable Object> keys, String key, String path) {
        String text = string(keys, key, path);
        if (!NAME.matcher(text).matches()) {
            throw new Malformed(path + "." + key + ": not of the form [a-z0-9-]+");
        }
        return text;
    }

    /** The passed-on {@link StoreOutcome#MALFORMED}, the detail starting with the file. */
    private static FormsResult<KindConfiguration> malformed(String problem) {
        return FormsResult.passOn(StoreResult.of(StoreOutcome.MALFORMED, FILE + ": " + problem));
    }

    /** The passed-on {@link GitOutcome#FAILED}, naming the exception class only. */
    private static FormsResult<KindConfiguration> failed(IOException e) {
        return FormsResult.passOn(StoreResult.passOn(GitResult.of(GitOutcome.FAILED,
                FILE + ": " + e.getClass().getSimpleName())));
    }

    /**
     * Returns the kinds of documents.
     *
     * @return the kinds in configuration order, unmodifiable
     */
    public List<Kind> kinds() {
        return List.copyOf(kinds.values());
    }

    /**
     * Finds a kind of document by its id.
     *
     * @param id the id
     * @return the kind, or empty when no kind has that id
     */
    public Optional<Kind> find(String id) {
        return Optional.ofNullable(kinds.get(id));
    }

    /** A configuration the loader does not accept, carrying the detail: the key path and the problem. */
    private static final class Malformed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Malformed(String detail) {
            super(detail, null, false, false);
        }
    }
}
