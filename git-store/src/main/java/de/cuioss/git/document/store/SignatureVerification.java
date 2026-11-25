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

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jspecify.annotations.Nullable;

/**
 * The signature verification of GDR-STORE-14: lists every commit reachable from a reference that has no signature valid
 * at its committer time under the {@link AllowedSigners} (checked against JGit 7.8.0).
 * <p>
 * A commit is valid when its {@code gpgsig} header holds a signature in the SSH signature format
 * (<a href="https://github.com/openssh/openssh-portable/blob/master/PROTOCOL.sshsig">PROTOCOL.sshsig</a>) that
 * verifies, in the namespace {@value SshSignatures#NAMESPACE_GIT}, over the commit without that header, and whose key
 * the allowed signers list for commits with a validity that includes the committer time, as Git checks it
 * ({@code git verify-commit} with {@code gpg.ssh.allowedSignersFile}, Git 2.35 or later). Every other commit is listed
 * once, with the first {@link Reason} that applies when the number of headers, the header, the format, the decoding,
 * the signature over the
 * commit and then the key are checked in this order: {@link Reason#UNSIGNED}, {@link Reason#NOT_SSH},
 * {@link Reason#MALFORMED}, {@link Reason#INVALID}, then {@link Reason#UNKNOWN_KEY} or
 * {@link Reason#OUTSIDE_VALIDITY}. The allowed signers are those given, never a file of the repository.
 * <p>
 * A walk that reaches a shallow commit (JGit 7.8.0 {@code ObjectDatabase.getShallowCommits}, whose parents
 * {@code RevWalk} treats as absent) is {@link StoreOutcome#INCOMPLETE}, never a success, because the commits beyond
 * the boundary cannot be checked. The shallow commits are read again after the walk; a boundary that changed while the
 * walk ran is {@link StoreOutcome#INCOMPLETE} as well.
 * <p>
 * A commit with more than one {@code gpgsig} header is {@link Reason#INVALID}: Git verifies the first and removes every
 * one from the signed payload, so a second header would travel along unverified.
 * <p>
 * The verification walks the history itself, newest commit time first like {@code RevWalk}, and reads every commit
 * once: its size is asked first ({@link ObjectReader#getObjectSize}, the object header only in JGit 7.8.0), a commit
 * within {@link ExplicitSave#BLOB_LIMIT} is read bounded, parsed ({@code RevCommit.parse}), checked, and its body,
 * which that parse keeps in the pool of the walk, is released ({@code RevCommit.disposeBody}), so the memory of the
 * verification does not grow with the history. A larger commit is {@link Reason#MALFORMED} and is never loaded: its
 * header is read as a stream for its parents and its commit time only, so the commits behind it are still verified
 * and the verification goes on.
 * <p>
 * The verification reads only: it changes no reference and writes no object. A finished verification is logged as
 * {@code GIT_STORE-6} with the counts only, never the reference, whose name may carry a document id.
 *
 * @since 0.1
 */
public final class SignatureVerification {

    private static final CuiLogger LOGGER = new CuiLogger(SignatureVerification.class);

    private static final byte[] GPGSIG = "gpgsig ".getBytes(US_ASCII);

    /** The detail, after the reference, of a history that reaches a shallow boundary. */
    static final String SHALLOW = "history is shallow";

    /** The detail, after the reference, of a shallow boundary that changed while the walk ran. */
    static final String SHALLOW_CHANGED = "shallow boundary changed";

    private SignatureVerification() {
    }

    /**
     * Why a commit has no valid signature: the closed list of the verification.
     *
     * @since 0.1
     */
    public enum Reason {
        /** The commit carries no {@code gpgsig} header. */
        UNSIGNED,
        /** The signature is not in the SSH signature format. */
        NOT_SSH,
        /** The SSH signature cannot be decoded. */
        MALFORMED,
        /** The key that made a verifying signature is not listed for commits. */
        UNKNOWN_KEY,
        /** The key of a verifying signature is listed for commits, but not valid at the committer time. */
        OUTSIDE_VALIDITY,
        /**
         * The signature does not verify over the commit, or was made in another namespace, with another hash or with
         * another key type.
         */
        INVALID
    }

    /**
     * A commit without a valid signature.
     *
     * @param commitId the full id of the commit
     * @param reason   why its signature is not valid
     * @since 0.1
     */
    public record UnverifiedCommit(String commitId, Reason reason) {

        /**
         * Checks the components.
         *
         * @param commitId the full id of the commit
         * @param reason   the reason
         * @throws NullPointerException     if a component is {@code null}
         * @throws IllegalArgumentException if {@code commitId} is no full object id
         */
        public UnverifiedCommit {
            Objects.requireNonNull(commitId, "commitId");
            Objects.requireNonNull(reason, "reason");
            if (!ObjectId.isId(commitId)) {
                throw new IllegalArgumentException("commitId: not a full object id");
            }
        }
    }

    /**
     * Verifies the signature of every commit reachable from a reference.
     *
     * @param repository     the repository, read only
     * @param reference      the full name of the reference, for example {@code refs/heads/main}
     * @param allowedSigners the trusted keys
     * @return {@link StoreOutcome#OK} with the commits without a valid signature in the order of the walk, newest
     *         first, empty when every commit is valid; {@link StoreOutcome#INCOMPLETE} with
     *         {@code <reference>: history is shallow} when the walk reaches a shallow commit, whose parents the
     *         repository lacks, so that no commit beyond the boundary can pass unchecked, or with
     *         {@code <reference>: shallow boundary changed} when the shallow commits read again after the walk differ
     *         from those read before it; the passed-on
     *         {@link GitOutcome#NOT_FOUND} with
     *         {@code <reference>: not found} for an unknown reference; the passed-on {@link GitOutcome#FAILED} with
     *         {@code <reference>: <simple class name of the exception>} when the repository cannot be read
     * @throws NullPointerException if an argument is {@code null}
     */
    public static StoreResult<List<UnverifiedCommit>> verify(Repository repository, String reference,
            AllowedSigners allowedSigners) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(allowedSigners, "allowedSigners");
        try {
            Ref ref = repository.exactRef(reference);
            if (ref == null || ref.getObjectId() == null) {
                return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.NOT_FOUND), null,
                        reference + ": not found");
            }
            Set<ObjectId> shallow = Set.copyOf(repository.getObjectDatabase().getShallowCommits());
            List<UnverifiedCommit> unverified = new ArrayList<>();
            int commits = 0;
            try (RevWalk walk = new RevWalk(repository)) {
                // the walk only parses: JGit 7.8.0 RevWalk.setRetainBody(false) keeps no body of what parseAny reads
                walk.setRetainBody(false);
                ObjectReader reader = walk.getObjectReader();
                PriorityQueue<Walked> queue = new PriorityQueue<>(NEWEST_FIRST);
                Set<ObjectId> seen = new HashSet<>();
                ObjectId start = start(reader, walk, ref.getObjectId());
                long sequence = 0;
                seen.add(start);
                queue.add(read(reader, walk, start, allowedSigners, sequence++));
                while (!queue.isEmpty()) {
                    Walked commit = queue.poll();
                    if (shallow.contains(commit.id())) {
                        // the parents of a shallow commit are absent, so the commits beyond it cannot be checked
                        return StoreResult.of(StoreOutcome.INCOMPLETE, reference + ": " + SHALLOW);
                    }
                    commits++;
                    @Nullable Reason reason = commit.reason();
                    if (reason != null) {
                        unverified.add(new UnverifiedCommit(commit.id().name(), reason));
                    }
                    for (ObjectId parent : commit.parents()) {
                        if (seen.add(parent)) {
                            queue.add(read(reader, walk, parent, allowedSigners, sequence++));
                        }
                    }
                }
            }
            // the walk stops at the shallow boundary read before it; a boundary that changed in between may have let it
            // read parents a fetch has removed or skip ones it has added, so the verification fails closed
            if (!shallow.equals(Set.copyOf(repository.getObjectDatabase().getShallowCommits()))) {
                return StoreResult.of(StoreOutcome.INCOMPLETE, reference + ": " + SHALLOW_CHANGED);
            }
            LOGGER.info(GitStoreLogMessages.INFO.SIGNATURES_VERIFIED, commits, unverified.size());
            return StoreResult.ok(List.copyOf(unverified));
        }
        // an unexpected failure reading the repository ends the verification as a result, never as an exception,
        // naming the class of the failure only (GDR-STORE-14, GDR-NFR-3)
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        catch (IOException | RuntimeException e) {
            return new StoreResult<>(new StoreResult.PassedOn(GitOutcome.FAILED), null,
                    reference + ": " + e.getClass().getSimpleName());
        }
    }

    /** The order of the walk: the newest commit time first, then the order in which the commits were reached. */
    private static final Comparator<Walked> NEWEST_FIRST = Comparator.comparingLong(Walked::time).reversed()
            .thenComparingLong(Walked::sequence);

    /**
     * A commit the walk reached, checked when it was reached.
     *
     * @param id       the commit
     * @param time     its commit time in seconds, the key of the order of the walk
     * @param parents  its parents
     * @param reason   why its signature is not valid; {@code null} for a valid one
     * @param sequence the order in which the walk reached it
     */
    private record Walked(ObjectId id, long time, List<ObjectId> parents, @Nullable Reason reason, long sequence) {
    }

    /**
     * The commit the walk starts at: the target of the reference, an annotated tag peeled as {@code RevWalk.parseCommit}
     * peels it. An object larger than {@link ExplicitSave#BLOB_LIMIT} is not parsed but read as a commit header, so a
     * larger tag fails as no commit.
     */
    private static ObjectId start(ObjectReader reader, RevWalk walk, ObjectId target) throws IOException {
        if (reader.getObjectSize(target, ObjectReader.OBJ_ANY) > ExplicitSave.BLOB_LIMIT) {
            return target;
        }
        return walk.peel(walk.parseAny(target)).copy();
    }

    /**
     * Reads and checks one commit. Its size is asked first; a commit within {@link ExplicitSave#BLOB_LIMIT} is read
     * bounded, parsed, checked and its body released, a larger one is {@link Reason#MALFORMED}, and only its header is
     * read, as a stream, for its parents and its commit time.
     *
     * @throws IOException if the commit is missing, is no commit, or its header names a parent that is no object id
     */
    private static Walked read(ObjectReader reader, RevWalk walk, ObjectId id, AllowedSigners allowedSigners,
            long sequence) throws IOException {
        if (reader.getObjectSize(id, Constants.OBJ_COMMIT) > ExplicitSave.BLOB_LIMIT) {
            try (InputStream in = new BufferedInputStream(reader.open(id, Constants.OBJ_COMMIT).openStream())) {
                CommitHeader header = CommitHeader.read(id, in);
                return new Walked(id, header.time(), header.parents(), Reason.MALFORMED, sequence);
            }
        }
        byte[] raw = reader.open(id, Constants.OBJ_COMMIT).getBytes(ExplicitSave.BLOB_LIMIT);
        // JGit 7.8.0 RevCommit.parse looks the commit up in the pool of the walk and keeps the raw buffer on it,
        // whatever setRetainBody says; the body is released once the commit is checked
        RevCommit commit = RevCommit.parse(walk, raw);
        try {
            List<ObjectId> parents = Arrays.stream(commit.getParents()).map(AnyObjectId::copy).toList();
            return new Walked(id, commit.getCommitTime(), parents, check(raw, commit, allowedSigners), sequence);
        } finally {
            commit.disposeBody();
        }
    }

    /**
     * The reason a commit within {@link ExplicitSave#BLOB_LIMIT} has no valid signature; {@code null} for a valid one.
     *
     * @param raw    the raw commit
     * @param commit the parsed commit
     */
    private static @Nullable Reason check(byte[] raw, RevCommit commit, AllowedSigners allowedSigners) {
        if (gpgsigHeaders(raw) > 1) {
            // Git verifies the first gpgsig header and strips every one from the payload, so a second header would be
            // carried along unverified
            return Reason.INVALID;
        }
        byte[] signature = commit.getRawGpgSignature();
        if (signature == null) {
            return Reason.UNSIGNED;
        }
        String armored = new String(signature, US_ASCII).strip();
        if (!armored.startsWith(SshSignatures.BEGIN)) {
            return Reason.NOT_SSH;
        }
        if (!(SshSignatures.decode(armored) instanceof SshSignatures.DecodedSignature decoded)) {
            return Reason.MALFORMED;
        }
        if (!SshSignatures.verify(decoded, SshSignatures.NAMESPACE_GIT, unsignedPayload(raw))) {
            return Reason.INVALID;
        }
        return switch (allowedSigners.check(decoded.publicKeyBlob(),
                commit.getCommitterIdent().getWhenAsInstant())) {
            case VALID -> null;
            case UNKNOWN_KEY -> Reason.UNKNOWN_KEY;
            case OUTSIDE_VALIDITY -> Reason.OUTSIDE_VALIDITY;
        };
    }

    /**
     * The raw commit without its {@code gpgsig} header and the continuation lines of that header: the bytes the
     * signature covers.
     *
     * @param raw the raw commit
     * @return the signed payload
     */
    static byte[] unsignedPayload(byte[] raw) {
        var out = new ByteArrayOutputStream(raw.length);
        int position = 0;
        boolean inSignature = false;
        while (position < raw.length) {
            int end = position;
            while (end < raw.length && raw[end] != '\n') {
                end++;
            }
            int next = Math.min(end + 1, raw.length);
            if (end == position) {
                out.write(raw, position, raw.length - position);
                break;
            }
            boolean continuation = raw[position] == ' ';
            if (!continuation) {
                inSignature = startsWith(raw, position, GPGSIG);
            }
            if (!inSignature) {
                out.write(raw, position, next - position);
            }
            position = next;
        }
        return out.toByteArray();
    }

    /**
     * The number of {@code gpgsig} headers of a raw commit: the header lines before the first empty line that start
     * with {@code gpgsig }, continuation lines not counted.
     *
     * @param raw the raw commit
     * @return the number of {@code gpgsig} headers
     */
    static int gpgsigHeaders(byte[] raw) {
        int count = 0;
        int position = 0;
        while (position < raw.length && raw[position] != '\n') {
            if (startsWith(raw, position, GPGSIG)) {
                count++;
            }
            while (position < raw.length && raw[position] != '\n') {
                position++;
            }
            position++;
        }
        return count;
    }

    private static boolean startsWith(byte[] data, int offset, byte[] prefix) {
        return data.length - offset >= prefix.length
                && Arrays.equals(data, offset, offset + prefix.length, prefix, 0, prefix.length);
    }

    /**
     * The parents and the commit time of a commit, read from its header as a stream up to the first empty line. Of
     * every header line only its first and its last {@value #KEPT} bytes are kept, so a header line of any length, for
     * example a {@code gpgsig} header, allocates nothing: a {@code parent} line names its id in its first bytes, a
     * {@code committer} line its time in its last.
     */
    private static final class CommitHeader {

        private static final int KEPT = 64;
        private static final String PARENT = "parent ";
        private static final String COMMITTER = "committer ";

        private final ObjectId id;
        private final List<ObjectId> parents = new ArrayList<>();
        private final byte[] head = new byte[KEPT];
        private final byte[] tail = new byte[KEPT];
        private long length;
        private long time;

        private CommitHeader(ObjectId id) {
            this.id = id;
        }

        /**
         * Reads the header of a commit.
         *
         * @param id the commit, for the exception
         * @param in the raw commit
         * @return the parents and the commit time; the time 0 when the committer line carries none
         * @throws IOException if the stream fails or a {@code parent} line names no object id
         */
        static CommitHeader read(ObjectId id, InputStream in) throws IOException {
            var header = new CommitHeader(id);
            int next = in.read();
            while (next != -1 && header.accept(next)) {
                next = in.read();
            }
            return header;
        }

        List<ObjectId> parents() {
            return List.copyOf(parents);
        }

        long time() {
            return time;
        }

        /** Takes one byte of the header; {@code false} at the empty line that ends it. */
        private boolean accept(int value) throws CorruptObjectException {
            if (value != '\n') {
                if (length < KEPT) {
                    head[(int) length] = (byte) value;
                }
                tail[(int) (length % KEPT)] = (byte) value;
                length++;
                return true;
            }
            if (length == 0) {
                return false;
            }
            String start = new String(head, 0, (int) Math.min(length, KEPT), US_ASCII);
            if (start.startsWith(PARENT)) {
                String parent = start.substring(PARENT.length(),
                        Math.min(start.length(), PARENT.length() + Constants.OBJECT_ID_STRING_LENGTH));
                if (!ObjectId.isId(parent)) {
                    throw new CorruptObjectException(id, "parent");
                }
                parents.add(ObjectId.fromString(parent));
            } else if (start.startsWith(COMMITTER)) {
                time = committerTime();
            }
            length = 0;
            return true;
        }

        /** The commit time of the committer line just read: the second-to-last token of its last bytes. */
        private long committerTime() {
            int kept = (int) Math.min(length, KEPT);
            var end = new StringBuilder(kept);
            for (long index = length - kept; index < length; index++) {
                end.append((char) (tail[(int) (index % KEPT)] & 0xff));
            }
            String[] tokens = end.toString().strip().split(" ");
            try {
                return tokens.length < 2 ? 0 : Long.parseLong(tokens[tokens.length - 2]);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
