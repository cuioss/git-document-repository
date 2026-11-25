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
package de.cuioss.git.document.store;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cache of the reachability check of the listed references of a checkpoint (GDR-STORE-16): it is keyed by the
 * listed id, the reference name and the tip the reference is observed at, so a reference that moves between two
 * checkpoints of one verification is decided again at its new tip. The reference names are the specified layout and
 * kept as literals; ids, names and contents are generated.
 */
@EnableGeneratorController
@DisplayName("Reachability cache of the anchor verification (GDR-STORE-16)")
class AnchorVerificationReachabilityTest {

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    @Test
    @DisplayName("GDR-STORE-16: a reference that moves between two checkpoints listing the same pair is decided again")
    void movedReferenceIsDecidedAgainAtItsNewTip() throws Exception {
        try (Repository repository = new InMemoryRepository(new DfsRepositoryDescription(word()))) {
            String branch = StoreReferences.HEADS + word();
            ObjectId listed = commit(repository, word(), word());
            move(repository, branch, listed);
            ObjectId checkpoint = commit(repository, Checkpoints.REFS_FILE, listed.name() + " " + branch + "\n");
            Map<String, Boolean> reached = new HashMap<>();
            List<AnchorVerification.Finding> first = new ArrayList<>();
            List<AnchorVerification.Finding> second = new ArrayList<>();

            try (RevWalk walk = new RevWalk(repository)) {
                AnchorVerification.listedReferences(repository, walk, walk.parseCommit(checkpoint), reached, first);
                // the branch is forced onto a root commit that does not reach the listed id
                move(repository, branch, commit(repository, word(), word()));
                AnchorVerification.listedReferences(repository, walk, walk.parseCommit(checkpoint), reached, second);
            }

            assertAll(
                    () -> assertTrue(first.isEmpty(), "at the listed tip the listed id is reached"),
                    () -> assertEquals(List.of(new AnchorVerification.Finding(
                                    AnchorVerification.Kind.REFERENCE_UNREACHABLE, checkpoint.name(), branch, null)),
                            second, "at the new tip the listed id is no longer reached"),
                    () -> assertEquals(2, reached.size(), "one decision per observed tip"));
        }
    }

    /** Writes a root commit whose tree holds one file. */
    private static ObjectId commit(Repository repository, String path, String content) throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(path, FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "");
            commit.setTreeId(inserter.insert(tree));
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(word());
            var id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }

    private static void move(Repository repository, String ref, ObjectId id) throws IOException {
        RefUpdate update = repository.updateRef(ref);
        update.setNewObjectId(id);
        update.setForceUpdate(true);
        var result = update.update();
        assertTrue(result == RefUpdate.Result.NEW || result == RefUpdate.Result.FORCED, () -> "moved: " + result);
    }
}
