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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;


import de.cuioss.git.document.core.TreeEdit;
import de.cuioss.git.document.store.DocumentCollection;
import de.cuioss.git.document.store.DocumentKey;
import de.cuioss.git.document.store.StoreReferences;
import de.cuioss.git.document.store.Ulid;
import de.cuioss.git.document.testing.InMemory;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The read side of the bare clone of a writer for the transitions: a saved commit read as an attempt reports the mode
 * of a path as the view of the store does (GDR-STORE-3, the contract of {@code SaveStep.Attempt.mode}), a regular file,
 * an executable file and a symbolic link by their mode and a gitlink or a directory by none; the tip of a reference
 * and the release tags of a key, the reads the resumption of a release needs (GDR-FORMS-5).
 * <p>
 * The modes and the reference layout are the contract and kept as literals; paths and contents are generated.
 */
@EnableGeneratorController
@DisplayName("CloneReader (GDR-STORE-3, GDR-FORMS-5)")
class CloneReaderTest {

    private static final TypedGenerator<String> WORDS =
            Generators.uniqueValues(Generators.strings("abcdefghijklmnopqrstuvwxyz", 8, 16));

    private static String word() {
        return WORDS.next();
    }

    /** One tree entry of a test commit. */
    private record Entry(String path, FileMode mode, ObjectId id) {
    }

    /** Writes a commit of the entries and returns its id, without moving a reference. */
    private static ObjectId commit(Repository repository, List<Entry> entries) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            DirCache index = DirCache.newInCore();
            DirCacheBuilder builder = index.builder();
            entries.stream().sorted((one, other) -> one.path().compareTo(other.path())).forEach(entry -> {
                var cached = new DirCacheEntry(entry.path());
                cached.setFileMode(entry.mode());
                cached.setObjectId(entry.id());
                builder.add(cached);
            });
            builder.finish();
            var commit = new CommitBuilder();
            var ident = new PersonIdent("test", "");
            commit.setTreeId(index.writeTree(inserter));
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage("test");
            ObjectId id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }

    private static ObjectId blob(Repository repository, String content) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            ObjectId id = inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
            inserter.flush();
            return id;
        }
    }

    private static void point(Repository repository, String ref, ObjectId id) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(id);
        update.forceUpdate();
    }

    @Test
    @DisplayName("GDR-STORE-3: a saved commit reports the mode of a regular, executable and symbolic-link file, none"
            + " for a gitlink, a directory or an absent path")
    void modeOfASavedCommit(@InMemory Repository repository) throws Exception {
        var directory = word();
        var regular = directory + "/" + word();
        var executable = directory + "/" + word();
        var link = directory + "/" + word();
        var gitlink = word();
        var commit = commit(repository, List.of(
                new Entry(regular, FileMode.REGULAR_FILE, blob(repository, word())),
                new Entry(executable, FileMode.EXECUTABLE_FILE, blob(repository, word())),
                new Entry(link, FileMode.SYMLINK, blob(repository, word())),
                new Entry(gitlink, FileMode.GITLINK, commit(repository, List.of()))));

        var attempt = new CloneReader(repository).savedCommit(commit.name());

        assertAll(
                () -> assertEquals(Optional.of(TreeEdit.Mode.REGULAR_FILE), attempt.mode(regular), "a regular file"),
                () -> assertEquals(Optional.of(TreeEdit.Mode.EXECUTABLE_FILE), attempt.mode(executable),
                        "an executable file"),
                () -> assertEquals(Optional.of(TreeEdit.Mode.SYMLINK), attempt.mode(link), "a symbolic link"),
                () -> assertEquals(Optional.empty(), attempt.mode(gitlink), "a gitlink has no mode"),
                () -> assertEquals(Optional.empty(), attempt.mode(directory), "a directory has no mode"),
                () -> assertEquals(Optional.empty(), attempt.mode(word()), "an absent path has no mode"));
    }

    @Test
    @DisplayName("GDR-FORMS-5: the tip of a reference and the release tags of a key, other tags ignored")
    void tipAndReleaseTags(@InMemory Repository repository) throws Exception {
        var key = new DocumentKey(new DocumentCollection(word(), DocumentCollection.Kind.PER_DOCUMENT), Ulid.next());
        var other = new DocumentKey(key.collection(), Ulid.next());
        var first = commit(repository, List.of(new Entry(word(), FileMode.REGULAR_FILE, blob(repository, word()))));
        var second = commit(repository, List.of(new Entry(word(), FileMode.REGULAR_FILE, blob(repository, word()))));
        point(repository, StoreReferences.target(key), second);
        point(repository, StoreReferences.releaseTag(key, 1), first);
        point(repository, StoreReferences.releaseTag(key, 2), second);
        point(repository, StoreReferences.releaseTag(other, 1), first);
        point(repository, StoreReferences.releaseTags(key) + "latest", second);

        var reader = new CloneReader(repository);

        assertAll(
                () -> assertEquals(Optional.of(second.name()), reader.tip(StoreReferences.target(key)), "the tip"),
                () -> assertEquals(Optional.empty(), reader.tip(StoreReferences.target(other)), "an absent branch"),
                () -> assertEquals(List.of(first.name(), second.name()), reader.releaseTagTargets(key),
                        "the release versions of the key only, in the order of their names"));
    }
}
