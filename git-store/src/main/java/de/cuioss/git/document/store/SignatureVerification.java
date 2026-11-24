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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
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
 * one from the signed payload, so a second header would travel along unverified. The walk retains no commit body
 * (JGit 7.8.0 {@code RevWalk.setRetainBody(false)}); each commit is read again, bounded by
 * {@link ExplicitSave#BLOB_LIMIT}, checked and released, so the memory of the verification does not grow with the
 * history, and a larger commit is {@link Reason#MALFORMED}.
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
                // JGit 7.8.0 RevWalk.setRetainBody(false) drops the raw buffer of every commit once the walk parsed its
                // parents, so the walk holds no commit body; each body is read again, checked and released in turn
                walk.setRetainBody(false);
                walk.markStart(walk.parseCommit(ref.getObjectId()));
                ObjectReader reader = walk.getObjectReader();
                for (RevCommit commit : walk) {
                    if (shallow.contains(commit)) {
                        return StoreResult.of(StoreOutcome.INCOMPLETE, reference + ": " + SHALLOW);
                    }
                    commits++;
                    Reason reason = check(reader, walk, commit, allowedSigners);
                    if (reason != null) {
                        unverified.add(new UnverifiedCommit(commit.getId().name(), reason));
                    }
                }
            }
            // the walk reads the shallow boundary itself when it parses the start commit; a boundary that changed in
            // between may have let it skip parents the check above did not see, so the verification fails closed
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

    /**
     * The reason a commit has no valid signature; {@code null} for a valid one. The raw commit is read bounded by
     * {@link ExplicitSave#BLOB_LIMIT}, parsed apart from the walk and released after the check; a larger commit is
     * {@link Reason#MALFORMED}.
     */
    private static @Nullable Reason check(ObjectReader reader, RevWalk walk, RevCommit walked,
            AllowedSigners allowedSigners) throws IOException {
        ObjectLoader loader = reader.open(walked, Constants.OBJ_COMMIT);
        if (loader.getSize() > ExplicitSave.BLOB_LIMIT) {
            return Reason.MALFORMED;
        }
        byte[] raw = loader.getBytes(ExplicitSave.BLOB_LIMIT);
        if (gpgsigHeaders(raw) > 1) {
            // Git verifies the first gpgsig header and strips every one from the payload, so a second header would be
            // carried along unverified
            return Reason.INVALID;
        }
        RevCommit commit = RevCommit.parse(walk, raw);
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
        if (!SshSignatures.verify(decoded, SshSignatures.NAMESPACE_GIT, unsignedPayload(commit.getRawBuffer()))) {
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
}
