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
package de.cuioss.git.document.core;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.TypedGenerator;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jspecify.annotations.Nullable;

/**
 * Writes blobs, trees, commits, references and notes through JGit's {@link ObjectInserter} into a fixture repository,
 * without a working tree, and configures remotes. Every name and content is generated.
 */
final class TestCommits {

    /** The reference git keeps the default notes under: part of git's notes layout. */
    static final String NOTES_REF = Constants.R_NOTES_COMMITS;

    // names from letters outside the hex digits, so no name reads as an abbreviated commit id
    private static final TypedGenerator<String> NAMES =
            Generators.uniqueValues(Generators.strings("ghijklmnopqrstuvwxyz", 4, 12));

    private TestCommits() {
        // static helpers only
    }

    /**
     * Generates a name that is a valid reference and path name and never reads as a commit id.
     *
     * @return a new name
     */
    static String name() {
        return NAMES.next();
    }

    /**
     * Writes a commit with one generated file and moves the reference to it; the reference's current commit, when it
     * has one, becomes the parent.
     *
     * @param repository the repository receiving the commit
     * @param ref        the full reference to move, e.g. {@code refs/heads/main}
     * @return the id of the new commit
     * @throws IOException if the objects or the reference cannot be written
     */
    static ObjectId commitOn(Repository repository, String ref) throws IOException {
        var current = repository.exactRef(ref);
        ObjectId parent = current == null ? null : current.getObjectId();
        ObjectId commit = commit(repository, parent);
        setRef(repository, ref, commit);
        return commit;
    }

    /**
     * Writes a commit with one generated file, without moving any reference.
     *
     * @param repository the repository receiving the commit
     * @param parent     the parent; {@code null} for a root commit
     * @return the id of the new commit
     * @throws IOException if the objects cannot be inserted
     */
    static ObjectId commit(Repository repository, @Nullable ObjectId parent) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var content = Generators.letterStrings(1, 40).next().getBytes(StandardCharsets.UTF_8);
            ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content);
            var tree = new TreeFormatter();
            tree.append(name(), FileMode.REGULAR_FILE, blob);
            ObjectId treeId = inserter.insert(tree);
            var ident = new PersonIdent(name(), name() + "@example.invalid");
            var commit = new CommitBuilder();
            commit.setTreeId(treeId);
            if (parent != null) {
                commit.setParentId(parent);
            }
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(Generators.letterStrings(3, 30).next());
            ObjectId commitId = inserter.insert(commit);
            inserter.flush();
            return commitId;
        }
    }

    /**
     * Points a reference at an object, creating or force-moving it.
     *
     * @param repository the repository
     * @param ref        the full reference
     * @param target     the object id
     * @throws IOException if the reference cannot be written
     */
    static void setRef(Repository repository, String ref, ObjectId target) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(target);
        update.setForceUpdate(true);
        var result = update.update();
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED
                && result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.NO_CHANGE) {
            throw new IOException("the fixture reference " + ref + " was not written: " + result);
        }
    }

    /**
     * Adds a note with generated text to a commit under {@link #NOTES_REF}.
     *
     * @param repository the repository
     * @param commit     the annotated commit
     * @return the id of the new notes commit
     * @throws IOException if the note cannot be written
     */
    static ObjectId note(Repository repository, ObjectId commit) throws IOException {
        var current = repository.exactRef(NOTES_REF);
        try (ObjectInserter inserter = repository.newObjectInserter(); RevWalk walk = new RevWalk(repository)) {
            NoteMap notes = current == null ? NoteMap.newEmptyMap()
                    : NoteMap.read(walk.getObjectReader(), walk.parseCommit(current.getObjectId()));
            notes.set(commit, Generators.letterStrings(3, 30).next(), inserter);
            var ident = new PersonIdent(name(), name() + "@example.invalid");
            var notesCommit = new CommitBuilder();
            notesCommit.setTreeId(notes.writeTree(inserter));
            if (current != null) {
                notesCommit.setParentId(current.getObjectId());
            }
            notesCommit.setAuthor(ident);
            notesCommit.setCommitter(ident);
            notesCommit.setMessage(Generators.letterStrings(3, 30).next());
            ObjectId id = inserter.insert(notesCommit);
            inserter.flush();
            setRef(repository, NOTES_REF, id);
            return id;
        }
    }

    /**
     * Configures a remote of the repository with the given URL and the default tracking fetch specification.
     *
     * @param repository the repository
     * @param remote     the remote name
     * @param url        the URL
     * @throws IOException        if the configuration cannot be saved
     * @throws URISyntaxException if the URL cannot be parsed
     */
    static void addRemote(Repository repository, String remote, String url) throws IOException, URISyntaxException {
        StoredConfig config = repository.getConfig();
        var remoteConfig = new RemoteConfig(config, remote);
        remoteConfig.addURI(new URIish(url));
        remoteConfig.addFetchRefSpec(
                new RefSpec("+" + Constants.R_HEADS + "*:" + Constants.R_REMOTES + remote + "/*"));
        remoteConfig.update(config);
        config.save();
    }
}
