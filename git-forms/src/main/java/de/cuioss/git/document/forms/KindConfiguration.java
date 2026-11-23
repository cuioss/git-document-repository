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
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
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
import org.eclipse.jgit.lib.ObjectLoader;
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
 * A configuration the loader does not accept is the passed-on {@link StoreOutcome#MALFORMED}:
 * <ul>
 * <li>a file that is a symbolic link, or that is larger than {@value #MAX_FILE_BYTES} bytes; neither is read;</li>
 * <li>text that is not UTF-8, or not YAML of the JSON data model (see the position in the detail);</li>
 * <li>a missing or unknown key, or a value of the wrong type;</li>
 * <li>an id or collection outside {@code [a-z0-9-]+}, or a duplicate id;</li>
 * <li>a repository alias the registry does not know;</li>
 * <li>a second kind with the repository and the collection of an earlier kind, whatever the numbering of either: the
 * two would share the branch namespace of the collection, and the branch of a numbered collection would conflict with
 * the document branches below the same name of a per-document collection;</li>
 * <li>a {@code schema}, {@code template} or {@code workflow} that is not a relative path: a leading {@code /}, an
 * empty, {@code .} or {@code ..} segment, a backslash, or a control character;</li>
 * <li>a per-document kind without {@code template};</li>
 * <li>a {@code prefix} outside {@code [A-Za-z0-9_-]{0,16}}, which keeps a number safe in a reference name and a link,
 * or {@code digits} outside 1 to {@value #MAX_DIGITS}, the number of digits of the largest counter, a {@code long}.</li>
 * </ul>
 * The detail names the file and the key path and the rule ({@code kinds.yaml: kinds[1].template: missing key},
 * positions counted from 0), never a configured value (GDR-NFR-3). An I/O failure is the passed-on
 * {@link GitOutcome#FAILED}, naming the exception class only.
 *
 * @since 0.1
 */
public final class KindConfiguration {

    /** The name of the configuration file at the root of the directory or the commit. */
    public static final String FILE = "kinds.yaml";

    /** The largest configuration file the loader reads, in bytes (1 MiB); a larger file is refused unread. */
    public static final int MAX_FILE_BYTES = 1_048_576;

    /** The largest number of digits of a numbering: a counter is a {@code long}, whose largest value has 19 digits. */
    public static final int MAX_DIGITS = 19;

    private static final CuiLogger LOGGER = new CuiLogger(KindConfiguration.class);

    /** The form of an id and a collection: lower-case letters, digits and hyphens. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9-]+");

    /** The form of a prefix: safe in a reference name and in a link, at most 16 characters. */
    private static final Pattern PREFIX_FORM = Pattern.compile("[A-Za-z0-9_-]{0,16}");

    /** The detail of a file larger than {@link #MAX_FILE_BYTES}. */
    private static final String TOO_LARGE = "too large, more than " + MAX_FILE_BYTES + " bytes";

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
    private final Map<String, Kind> byId;

    private KindConfiguration(Map<String, Kind> byId) {
        this.byId = byId;
    }

    /**
     * Loads the kinds of documents from the file {@value #FILE} at the root of a directory. The file is not followed
     * when it is a symbolic link, and at most {@value #MAX_FILE_BYTES} bytes and one more are read from it.
     *
     * @param directory    the configuration directory
     * @param repositories the repository registry the alias of every kind must be found in
     * @return {@link FormsOutcome#OK} with the configuration; the passed-on {@link StoreOutcome#MALFORMED} for a
     *         missing file, a symbolic link, a file larger than {@value #MAX_FILE_BYTES} bytes or a configuration the
     *         loader does not accept; the passed-on {@link GitOutcome#FAILED} for an I/O failure
     * @throws NullPointerException if an argument is {@code null}
     */
    public static FormsResult<KindConfiguration> fromDirectory(Path directory, RepositoryRegistry repositories) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(repositories, "repositories");
        Path file = directory.resolve(FILE);
        if (Files.isSymbolicLink(file)) {
            return malformed("a symbolic link, not read");
        }
        byte[] content;
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ,
                     LinkOption.NOFOLLOW_LINKS); InputStream in = Channels.newInputStream(channel)) {
            content = in.readNBytes(MAX_FILE_BYTES + 1);
        } catch (NoSuchFileException e) {
            return malformed("missing file");
        } catch (IOException e) {
            return failed(e);
        }
        if (content.length > MAX_FILE_BYTES) {
            return malformed(TOO_LARGE);
        }
        return load(content, repositories, "directory " + directory);
    }

    /**
     * Loads the kinds of documents from the file {@value #FILE} at the root of a commit of a configuration repository.
     * The revision is resolved with {@link RepositoryReader#snapshot(Repository, String)} of {@code git-core}, and the
     * blob is read through the repository's JGit {@link ObjectReader}, only after its size, from
     * {@link ObjectLoader#getSize()}, is found to be at most {@value #MAX_FILE_BYTES} bytes.
     *
     * @param repository   the configuration repository
     * @param revision     a reference, by its short or full name, or a full commit id
     * @param repositories the repository registry the alias of every kind must be found in
     * @return {@link FormsOutcome#OK} with the configuration; the Git outcome of the snapshot passed on when the
     *         revision cannot be resolved; the passed-on {@link StoreOutcome#MALFORMED} for a missing file, a file
     *         larger than {@value #MAX_FILE_BYTES} bytes or a configuration the loader does not accept; the passed-on
     *         {@link GitOutcome#FAILED} for an I/O failure
     * @throws NullPointerException if an argument is {@code null}
     */
    public static FormsResult<KindConfiguration> fromRepository(Repository repository, String revision,
            RepositoryRegistry repositories) {
        Objects.requireNonNull(repository, REPOSITORY);
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
            ObjectLoader loader = reader.open(ObjectId.fromString(file.get().blobId()), Constants.OBJ_BLOB);
            // JGit 7.8.0 ObjectLoader.getBytes(int) refuses only a large object above the limit and returns a cached
            // one whole, so the size is checked first.
            if (loader.getSize() > MAX_FILE_BYTES) {
                return malformed(TOO_LARGE);
            }
            content = loader.getBytes(MAX_FILE_BYTES);
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
            LOGGER.info(GitFormsLogMessages.INFO.KINDS_LOADED, configuration.byId.size(), source);
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
        Set<List<String>> namespaces = new HashSet<>();
        for (int index = 0; index < list.size(); index++) {
            String path = KINDS + "[" + index + "]";
            Kind kind = kind(list.get(index), path, repositories);
            if (byId.putIfAbsent(kind.id(), kind) != null) {
                throw new Malformed(path + "." + ID + ": duplicate id");
            }
            if (!namespaces.add(List.of(kind.repository(), kind.collection()))) {
                throw new Malformed(path + "." + COLLECTION + ": a collection another kind of the same repository uses");
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
        String schema = relativePath(keys, SCHEMA, path);
        String workflow = relativePath(keys, WORKFLOW, path);
        @Nullable String template = keys.containsKey(TEMPLATE) ? relativePath(keys, TEMPLATE, path) : null;
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
        String prefix = anyString(keys, PREFIX, path);
        if (!PREFIX_FORM.matcher(prefix).matches()) {
            throw new Malformed(path + "." + PREFIX + ": not of the form " + PREFIX_FORM.pattern());
        }
        Object digits = require(keys, DIGITS, path + "." + DIGITS);
        if (!(digits instanceof Long count) || count < 1 || count > MAX_DIGITS) {
            throw new Malformed(path + "." + DIGITS + ": not an integer between 1 and " + MAX_DIGITS);
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

    /** The value of a required key that must be a string, which may be empty or blank. */
    private static String anyString(Map<String, @Nullable Object> keys, String key, String path) {
        String keyPath = path + "." + key;
        if (!(require(keys, key, keyPath) instanceof String text)) {
            throw new Malformed(keyPath + ": not a string");
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

    /**
     * The value of a required key that must be a relative path: not blank, no leading {@code /}, no empty, {@code .}
     * or {@code ..} segment, no backslash and no control character.
     */
    private static String relativePath(Map<String, @Nullable Object> keys, String key, String path) {
        String text = string(keys, key, path);
        String keyPath = path + "." + key;
        if (text.startsWith("/")) {
            throw new Malformed(keyPath + ": not a relative path, leading /");
        }
        if (text.indexOf('\\') >= 0) {
            throw new Malformed(keyPath + ": not a relative path, backslash");
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new Malformed(keyPath + ": not a relative path, control character");
        }
        for (String segment : text.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new Malformed(keyPath + ": not a relative path, empty, . or .. segment");
            }
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
        return List.copyOf(byId.values());
    }

    /**
     * Finds a kind of document by its id.
     *
     * @param id the id
     * @return the kind, or empty when no kind has that id
     */
    public Optional<Kind> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** A configuration the loader does not accept, carrying the detail: the key path and the problem. */
    private static final class Malformed extends RuntimeException {

        private static final long serialVersionUID = 1L;

        Malformed(String detail) {
            super(detail, null, false, false);
        }
    }
}
