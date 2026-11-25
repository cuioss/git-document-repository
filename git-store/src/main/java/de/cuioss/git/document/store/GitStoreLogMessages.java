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

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;
import lombok.experimental.UtilityClass;

/**
 * The {@link LogRecord} holder of {@code git-store} (GDR-GEN-4). Identifiers carry the prefix {@value #PREFIX} and
 * follow the CUI ranges: INFO 1-99, WARN 100-199, ERROR 200-299. The catalogue is
 * {@code git-store/doc/LogMessages.adoc}.
 * <p>
 * No message carries anything GDR-NFR-3 forbids: a message names a provider id, a configuration key, a repository
 * alias, a path, a reference, a commit id, an outcome or a number, never a configured value, a key reference, key
 * material, a token, a password, the user information of a remote, a content, an e-mail address or a person name. The
 * messages of a save name no path of the saved document, only the number of conflicting paths.
 *
 * @since 0.1
 */
@UtilityClass
public final class GitStoreLogMessages {

    /** The prefix of every identifier of {@code git-store}. */
    public static final String PREFIX = "GIT_STORE";

    /**
     * Messages at level INFO.
     *
     * @since 0.1
     */
    @UtilityClass
    public static final class INFO {

        /**
         * The providers of the store were selected: the ids of the signing key source, the person directory and the
         * anchor provider.
         */
        public static final LogRecord PROVIDERS_SELECTED = LogRecordModel.builder()
                .template("Selected the providers: signing key source %s, person directory %s, anchor provider %s")
                .prefix(PREFIX)
                .identifier(1)
                .build();

        /** A save was accepted by the remote: the repository alias, the target reference and the commit id. */
        public static final LogRecord SAVED = LogRecordModel.builder()
                .template("Saved repository %s, reference %s at commit %s")
                .prefix(PREFIX)
                .identifier(2)
                .build();

        /**
         * A save conflicted and pushed nothing: the repository alias, the target reference and the number of
         * conflicting paths, never a path.
         */
        public static final LogRecord SAVE_CONFLICT = LogRecordModel.builder()
                .template("Did not save repository %s, reference %s: %s conflicting paths")
                .prefix(PREFIX)
                .identifier(3)
                .build();

        /** The expired drafts were deleted and the garbage collected: the repository alias and the number deleted. */
        public static final LogRecord DRAFTS_EXPIRED = LogRecordModel.builder()
                .template("Expired the drafts of repository %s: %s deleted")
                .prefix(PREFIX)
                .identifier(4)
                .build();

        /**
         * A release completed, {@code main} and the release tag both on the remote: the repository alias, the release
         * tag and the release commit id.
         */
        public static final LogRecord RELEASED = LogRecordModel.builder()
                .template("Released repository %s, tag %s at commit %s")
                .prefix(PREFIX)
                .identifier(5)
                .build();

        /**
         * A signature verification of a reference finished: the number of commits walked and the number of commits
         * without a valid signature, never the reference, whose name may carry a document id, a principal, a key or a
         * commit message.
         */
        public static final LogRecord SIGNATURES_VERIFIED = LogRecordModel.builder()
                .template("Verified the signatures of a reference: %s commits, %s unverified")
                .prefix(PREFIX)
                .identifier(6)
                .build();

        /**
         * The anchor provider {@code signed} signed a proof (GDR-STORE-18): the repository alias, the object id of the
         * subject, the time of the statement, its sequence number and the SHA-256 of the proof, nothing else. It records
         * the signing, before the note is written: a proof whose push failed while its notes tip stayed the same is kept
         * and published by a later due check without the provider signing it again, while a proof whose notes tip moved is dropped and a
         * later due check signs a new one, so a subject may have several of these records; its publication is
         * {@code GIT_STORE-9}.
         */
        public static final LogRecord PROOF_SIGNED = LogRecordModel.builder()
                .template("Signed an anchor proof of repository %s for object %s at %s, sequence %s, SHA-256 %s")
                .prefix(PREFIX)
                .identifier(7)
                .build();

        /** A checkpoint commit was written (GDR-STORE-16): the repository alias, its number and its commit id. */
        public static final LogRecord CHECKPOINT_CREATED = LogRecordModel.builder()
                .template("Created a checkpoint of repository %s: number %s at commit %s")
                .prefix(PREFIX)
                .identifier(8)
                .build();

        /** The note of an anchor proof is on the remote (GDR-STORE-17): the repository alias and the anchored commit. */
        public static final LogRecord SUBJECT_ANCHORED = LogRecordModel.builder()
                .template("Anchored a commit of repository %s: %s")
                .prefix(PREFIX)
                .identifier(9)
                .build();

        /**
         * An anchor verification finished (GDR-STORE-17, GDR-STORE-18): the provider id, the number of checkpoints, of
         * subjects, of proofs verified and of findings, never a commit id, a reference or a proof.
         */
        public static final LogRecord ANCHORS_VERIFIED = LogRecordModel.builder()
                .template("Verified the anchors of provider %s: %s checkpoints, %s subjects, %s proofs, %s findings")
                .prefix(PREFIX)
                .identifier(10)
                .build();
    }

    /**
     * Messages at level WARN.
     *
     * @since 0.1
     */
    @UtilityClass
    public static final class WARN {

        /**
         * The start was refused: the detail of the result, which names a key, an alias or a service interface, never
         * a value.
         */
        public static final LogRecord START_REFUSED = LogRecordModel.builder()
                .template("Refused the start: %s")
                .prefix(PREFIX)
                .identifier(100)
                .build();

        /**
         * A save did not complete, for a reason other than a conflict: the repository alias, the target reference and
         * the outcome.
         */
        public static final LogRecord SAVE_NOT_COMPLETED = LogRecordModel.builder()
                .template("Did not complete the save of repository %s, reference %s: %s")
                .prefix(PREFIX)
                .identifier(101)
                .build();

        /**
         * The garbage collection after the draft expiry failed: the repository alias and the class of the exception,
         * never its message.
         */
        public static final LogRecord GARBAGE_COLLECTION_FAILED = LogRecordModel.builder()
                .template("The garbage collection of repository %s failed: %s")
                .prefix(PREFIX)
                .identifier(102)
                .build();

        /**
         * The remote accepted a save, but the local target branch could not be moved: the repository alias, the
         * target reference and the class of the exception, never its message; the next fetch mirrors the remote branch.
         */
        public static final LogRecord LOCAL_BRANCH_NOT_MOVED = LogRecordModel.builder()
                .template("Did not move the local branch of repository %s, reference %s after the accepted save: %s")
                .prefix(PREFIX)
                .identifier(103)
                .build();

        /**
         * The garbage collection after the draft expiry was not run because another collection holds the clone's
         * {@code gc.pid}: the repository alias.
         */
        public static final LogRecord GARBAGE_COLLECTION_SKIPPED = LogRecordModel.builder()
                .template("Skipped the garbage collection of repository %s: another collection holds gc.pid")
                .prefix(PREFIX)
                .identifier(104)
                .build();

        /**
         * A release moved {@code main} on the remote, but its tag is missing there: the repository alias, the missing
         * release tag and the release commit id; a repeated release creates the tag on that commit.
         */
        public static final LogRecord RELEASE_INCOMPLETE = LogRecordModel.builder()
                .template("Released repository %s onto main, but the tag %s is missing for commit %s")
                .prefix(PREFIX)
                .identifier(105)
                .build();

        /**
         * A release did not complete, nothing of it on the remote: the repository alias, the branch of the document or
         * entry and the outcome.
         */
        public static final LogRecord RELEASE_NOT_COMPLETED = LogRecordModel.builder()
                .template("Did not complete the release of repository %s, reference %s: %s")
                .prefix(PREFIX)
                .identifier(106)
                .build();

        /**
         * A periodic fetch failed, the schedule goes on: the repository alias and the outcome, or the class of the
         * exception, never its message.
         */
        public static final LogRecord PERIODIC_FETCH_FAILED = LogRecordModel.builder()
                .template("The periodic fetch of repository %s failed: %s")
                .prefix(PREFIX)
                .identifier(107)
                .build();

        /**
         * An index contributor threw, the document is left out of the index: the repository alias, the collection and
         * the class of the exception, never its message.
         */
        public static final LogRecord CONTRIBUTOR_FAILED = LogRecordModel.builder()
                .template("Left a document of repository %s, collection %s out of the index: the contributor threw %s")
                .prefix(PREFIX)
                .identifier(108)
                .build();

        /**
         * A commit listener threw, the save, release or fetch that triggered it is unaffected: the repository alias, the
         * branch and the class of the exception, never its message.
         */
        public static final LogRecord LISTENER_FAILED = LogRecordModel.builder()
                .template("A commit listener of repository %s, branch %s threw %s")
                .prefix(PREFIX)
                .identifier(109)
                .build();

        /**
         * A save, release, checkpoint or anchor note was refused because its commit could not be signed, nothing of it
         * on the remote, also when the signing key source threw: the repository alias, the class of the reference
         * ({@code main}, {@code branch}, {@code tag}, {@code checkpoint tag}, {@code notes reference}) and the outcome,
         * never the reference name, the key reference, key material, the detail of the signing key source or a
         * throwable.
         */
        public static final LogRecord SIGNING_FAILED = LogRecordModel.builder()
                .template("Did not sign the commit of repository %s for a %s: %s")
                .prefix(PREFIX)
                .identifier(110)
                .build();

        /**
         * A due check of the anchoring left subjects pending, to be retried by the next one (GDR-STORE-15): the
         * repository alias and the outcome, never a reference name, a proof or the detail of the provider.
         */
        public static final LogRecord ANCHORING_PENDING = LogRecordModel.builder()
                .template("Left anchor subjects of repository %s pending: %s")
                .prefix(PREFIX)
                .identifier(111)
                .build();

        /**
         * The push of a checkpoint tag or an anchor note failed; it is kept locally and pushed by the next due check
         * (GDR-STORE-16, GDR-STORE-17): the repository alias and the outcome.
         */
        public static final LogRecord ANCHOR_PUSH_INCOMPLETE = LogRecordModel.builder()
                .template("Did not push an anchoring reference of repository %s, kept for the next due check: %s")
                .prefix(PREFIX)
                .identifier(112)
                .build();

        /**
         * A refresh of the index could not read one source; it keeps the previous entries of that source, the other
         * sources are published fresh, and the next operation of the writer retries it (GDR-STORE-9): the repository
         * alias, the class of the source ({@code main}, {@code working branch} or {@code collection branch}), never
         * its reference name, and the class name of the exception, never its message.
         */
        public static final LogRecord INDEX_SOURCE_UNREAD = LogRecordModel.builder()
                .template("Kept the previous index entries of repository %s for a %s that could not be read: %s")
                .prefix(PREFIX)
                .identifier(113)
                .build();

        /**
         * A close of the writer was refused because it was issued inside a callback that holds a lock of the clone; the
         * writer stays open and is closed by a close after the callback returned: the repository alias and the detail
         * of the refusal.
         */
        public static final LogRecord CLOSE_REFUSED = LogRecordModel.builder()
                .template("Did not close the writer of repository %s, close it after the callback returned: %s")
                .prefix(PREFIX)
                .identifier(114)
                .build();
    }
}
