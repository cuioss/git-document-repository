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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;


import de.cuioss.git.document.store.SignatureVerification.Reason;
import de.cuioss.git.document.store.SignatureVerification.UnverifiedCommit;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A commit larger than the read bound in the history of a verified reference (GDR-STORE-14): it is one
 * {@link Reason#MALFORMED} finding, never loaded, and the commits behind it are verified as well. The repository streams
 * every object above 1 MiB (JGit 7.8.0 {@code DfsReaderOptions.setStreamFileThreshold}), so a walk that loaded the
 * oversized commit would fail with JGit's {@code LargeObjectException} instead of reporting it. The reference names are
 * the specified layout and kept as literals; names and contents are generated.
 */
@EnableGeneratorController
@DisplayName("Signature verification of an oversized commit (GDR-STORE-14)")
class SignatureVerificationOversizedTest {

    @TempDir
    Path temp;

    private static String word() {
        return Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
    }

    private static ObjectId commit(Repository repository, String message, long time, ObjectId... parents)
            throws IOException {
        try (ObjectInserter inserter = repository.newObjectInserter()) {
            var tree = new TreeFormatter();
            tree.append(word(), FileMode.REGULAR_FILE,
                    inserter.insert(Constants.OBJ_BLOB, word().getBytes(StandardCharsets.UTF_8)));
            var commit = new CommitBuilder();
            var ident = new PersonIdent(word(), "", time * 1000, 0);
            commit.setTreeId(inserter.insert(tree));
            commit.setParentIds(parents);
            commit.setAuthor(ident);
            commit.setCommitter(ident);
            commit.setMessage(message);
            var id = inserter.insert(commit);
            inserter.flush();
            return id;
        }
    }

    @Test
    @DisplayName("GDR-STORE-14: an oversized commit is MALFORMED and the verification goes on behind it")
    void oversizedCommitIsOneFinding() throws Exception {
        var options = new DfsReaderOptions().setStreamFileThreshold(1024 * 1024);
        try (Repository repository = new InMemoryRepository.Builder()
                     .setRepositoryDescription(new DfsRepositoryDescription(word())).setReaderOptions(options).build()) {
            long time = Generators.integers(1_000_000_000, 1_500_000_000).next();
            ObjectId root = commit(repository, word(), time);
            ObjectId oversized = commit(repository, "x".repeat(ExplicitSave.BLOB_LIMIT + 1), time + 1, root);
            ObjectId tip = commit(repository, word(), time + 2, oversized);
            String reference = StoreReferences.MAIN;
            RefUpdate update = repository.updateRef(reference);
            update.setNewObjectId(tip);
            assertEquals(RefUpdate.Result.NEW, update.update(), "the reference is written");

            var signersFile = Files.writeString(temp.resolve(word()), "# no signer\n");
            var noSigners = AllowedSigners.read(signersFile);
            assertTrue(noSigners.isOk(), "an empty allowed signers list is valid");
            var result = SignatureVerification.verify(repository, reference,
                    Objects.requireNonNull(noSigners.value()));

            assertTrue(result.isOk(), () -> "the verification ends with a report: " + result.detail());
            assertEquals(List.of(new UnverifiedCommit(tip.name(), Reason.UNSIGNED),
                            new UnverifiedCommit(oversized.name(), Reason.MALFORMED),
                            new UnverifiedCommit(root.name(), Reason.UNSIGNED)), result.value(),
                    "the oversized commit is one finding, and the commit behind it is verified");
        }
    }
}
