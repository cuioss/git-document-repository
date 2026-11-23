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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;

/**
 * The anchor provider {@code signed}, the own timestamp of the store (GDR-STORE-18): it signs a statement of the
 * object id, the time, a sequence number and the SHA-256 of the previous proof with an anchor key of its own key
 * source, never the commit-signing key.
 * <p>
 * The statement is the one of {@link AnchorStatement}; the proof is the statement followed by an armored SSH signature
 * over it in the namespace {@value #NAMESPACE}, in the format of
 * <a href="https://github.com/openssh/openssh-portable/blob/master/PROTOCOL.sshsig">PROTOCOL.sshsig</a>, so a single
 * proof can be checked with {@code ssh-keygen -Y verify -n git-document-anchor}. {@link StoreProviders} builds the
 * provider from {@code anchor.signed.key-source} and {@code anchor.signed.key} when {@code anchor.provider} is
 * {@value #PROVIDER_ID}, reading the public key of the anchor key once at start; {@link #verifier(AllowedSigners)} builds an
 * instance that only verifies, for CI.
 * <p>
 * {@link #obtain(String, ObjectId, Instant, byte[])} refuses ({@link StoreOutcome#REFUSED}, nothing signed) when the
 * clock of the provider is before the time of the previous proof, or more than {@code anchor.signed.max-clock-skew}
 * behind the commit time of the subject. Every proof is logged once as {@code GIT_STORE-7} with the repository alias,
 * the object id, the time, the sequence number and the SHA-256 of the proof, nothing else: no detail and no log record
 * names the key reference, key material or the detail of the key source (GDR-NFR-3).
 * <p>
 * {@link #verify(ObjectId, byte[], byte[])} checks the statement against the subject, the signature in the namespace
 * {@value #NAMESPACE} against {@code anchor.signed.allowed-signers} at the time of the statement, and the chain: the
 * sequence without a gap, the {@code previous} hash equal to the SHA-256 of the previous proof, the time never before
 * the previous time.
 *
 * @since 0.1
 */
public final class SignedAnchorProvider implements AnchorProvider {

    /** The id of the provider, the value of {@code anchor.provider} that selects it. */
    public static final String PROVIDER_ID = "signed";

    /** The namespace of the SSH signature of a proof. */
    public static final String NAMESPACE = "git-document-anchor";

    /** The detail of {@code obtain} on an instance that only verifies. */
    static final String VERIFY_ONLY = "signed: no anchor key, the provider only verifies";

    /** The detail of a previous proof that {@code obtain} or the chain check of {@code verify} cannot read. */
    static final String PREVIOUS_UNREADABLE = "signed: previous proof unreadable";

    /** The detail of a clock before the time of the previous proof. */
    static final String CLOCK_BEHIND_PREVIOUS = "signed: clock behind the previous proof";

    /** The detail of a clock more than the largest skew behind the commit time of the subject. */
    static final String CLOCK_BEHIND_COMMIT = "signed: clock behind the commit time of the subject";

    /** The detail of a chain whose sequence numbers are exhausted. */
    static final String SEQUENCE_EXHAUSTED = "signed: sequence exhausted";

    /** The detail of an anchor key source that could not be reached. */
    static final String SOURCE_UNAVAILABLE = "signed: key source unavailable";

    /** The detail of an anchor key source that refused, or failed in another way. */
    static final String SOURCE_REFUSED = "signed: key source refused";

    /** The detail of a signature that does not verify with the public key read at start. */
    static final String KEY_MISMATCH = "signed: signature does not match the public key";

    /** The detail of a proof that cannot be read. */
    static final String PROOF_UNREADABLE = "signed: proof unreadable";

    /** The detail of a proof about another object id. */
    static final String OTHER_OBJECT = "signed: proof about another object";

    /** The detail of a verification without the anchor allowed signers. */
    static final String NO_ALLOWED_SIGNERS = "signed: anchor.signed.allowed-signers is missing";

    /** The detail of a signature that does not verify over the statement. */
    static final String SIGNATURE_INVALID = "signed: signature invalid";

    /** The detail of a key the anchor allowed signers do not list for the namespace at the time of the statement. */
    static final String KEY_NOT_ALLOWED = "signed: key not allowed at the statement time";

    /** The detail of a sequence number that does not follow the previous one. */
    static final String CHAIN_SEQUENCE_GAP = "signed: sequence does not follow the previous proof";

    /** The detail of a previous hash that is not the SHA-256 of the previous proof. */
    static final String CHAIN_PREVIOUS_MISMATCH = "signed: previous hash does not match the previous proof";

    /** The detail of a time before the time of the previous proof. */
    static final String CHAIN_TIME_DECREASES = "signed: time before the previous proof";

    private static final CuiLogger LOGGER = new CuiLogger(SignedAnchorProvider.class);

    private final @Nullable AnchorKey key;
    private final @Nullable AllowedSigners allowedSigners;
    private final Duration maxClockSkew;
    private final Clock clock;

    /**
     * Creates the provider.
     *
     * @param key            the anchor key, or {@code null} for an instance that only verifies
     * @param allowedSigners the anchor allowed signers, or {@code null} when {@code anchor.signed.allowed-signers} is
     *                       absent, so no proof verifies
     * @param maxClockSkew   the largest delay of the clock behind the commit time of a subject
     * @param clock          the clock of the statements
     * @throws NullPointerException if {@code maxClockSkew} or {@code clock} is {@code null}
     */
    SignedAnchorProvider(@Nullable AnchorKey key, @Nullable AllowedSigners allowedSigners, Duration maxClockSkew,
            Clock clock) {
        this.key = key;
        this.allowedSigners = allowedSigners;
        this.maxClockSkew = Objects.requireNonNull(maxClockSkew, "maxClockSkew");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Creates an instance that only verifies, for CI: {@link #obtain(String, ObjectId, Instant, byte[])} refuses.
     *
     * @param allowedSigners the anchor allowed signers ({@code anchor.signed.allowed-signers})
     * @return the verifying provider
     * @throws NullPointerException if {@code allowedSigners} is {@code null}
     */
    public static SignedAnchorProvider verifier(AllowedSigners allowedSigners) {
        return new SignedAnchorProvider(null, Objects.requireNonNull(allowedSigners, "allowedSigners"),
                StoreConfiguration.Anchor.Signed.DEFAULT_MAX_CLOCK_SKEW, Clock.systemUTC());
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    /**
     * Signs the statement of the subject.
     * <p>
     * The statement takes the time of the clock in whole seconds, the sequence 1 and {@code previous none} for the
     * first proof, otherwise the sequence of the previous proof plus one and its SHA-256. The signed blob is
     * {@link SshSignatures#signedData(String, byte[])} in the namespace {@value #NAMESPACE}; the signature of the key
     * source is verified against the public key read at start before it is encoded.
     *
     * @param repository the alias of the repository of the subject, for the log record
     * @param subject    the object id of the subject
     * @param commitTime the commit time of the subject
     * @param previous   the previous proof of this provider in the repository; empty for the first
     * @return {@link StoreOutcome#OK} with the statement followed by the armored signature;
     *         {@link StoreOutcome#REFUSED} with {@value #CLOCK_BEHIND_PREVIOUS}, {@value #CLOCK_BEHIND_COMMIT},
     *         {@value #PREVIOUS_UNREADABLE}, {@value #SEQUENCE_EXHAUSTED}, {@value #KEY_MISMATCH},
     *         {@value #SOURCE_REFUSED} or {@value #VERIFY_ONLY}, nothing signed; {@link StoreOutcome#UNAVAILABLE} with
     *         {@value #SOURCE_UNAVAILABLE}
     * @throws NullPointerException if an argument is {@code null}
     */
    @Override
    public StoreResult<byte[]> obtain(String repository, ObjectId subject, Instant commitTime, byte[] previous) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(commitTime, "commitTime");
        Objects.requireNonNull(previous, "previous");
        AnchorKey anchorKey = key;
        if (anchorKey == null) {
            return StoreResult.of(StoreOutcome.REFUSED, VERIFY_ONLY);
        }
        Optional<AnchorStatement.Proof> split = Optional.empty();
        if (previous.length > 0) {
            split = AnchorStatement.split(previous.clone());
            if (split.isEmpty()) {
                return StoreResult.of(StoreOutcome.REFUSED, PREVIOUS_UNREADABLE);
            }
            if (split.get().statement().sequence() == Long.MAX_VALUE) {
                return StoreResult.of(StoreOutcome.REFUSED, SEQUENCE_EXHAUSTED);
            }
        }
        var before = split.orElse(null);
        var statement = AnchorStatement.next(subject, clock.instant(), before);
        if (before != null && statement.time().isBefore(before.statement().time())) {
            return StoreResult.of(StoreOutcome.REFUSED, CLOCK_BEHIND_PREVIOUS);
        }
        if (statement.time().plus(maxClockSkew).isBefore(commitTime)) {
            return StoreResult.of(StoreOutcome.REFUSED, CLOCK_BEHIND_COMMIT);
        }
        byte[] statementBytes = statement.encode();
        StoreResult<byte[]> signed = anchorKey.source()
                .sign(anchorKey.keyReference(), SshSignatures.signedData(NAMESPACE, statementBytes));
        byte[] signature = signed.value();
        if (!signed.isOk() || signature == null) {
            return signed.outcome() == StoreOutcome.UNAVAILABLE
                    ? StoreResult.of(StoreOutcome.UNAVAILABLE, SOURCE_UNAVAILABLE)
                    : StoreResult.of(StoreOutcome.REFUSED, SOURCE_REFUSED);
        }
        byte[] publicKeyBlob = anchorKey.publicKey().blob();
        var made = new SshSignatures.DecodedSignature(publicKeyBlob, NAMESPACE, SshSignatures.HASH_SHA512,
                SshSignatures.KEY_TYPE, signature);
        if (!SshSignatures.verify(made, NAMESPACE, statementBytes)) {
            return StoreResult.of(StoreOutcome.REFUSED, KEY_MISMATCH);
        }
        var proof = new ByteArrayOutputStream();
        proof.writeBytes(statementBytes);
        proof.writeBytes(SshSignatures.encode(publicKeyBlob, NAMESPACE, made.signature()).getBytes(US_ASCII));
        byte[] bytes = proof.toByteArray();
        LOGGER.info(GitStoreLogMessages.INFO.PROOF_SIGNED, repository, subject.name(), statement.time(),
                statement.sequence(), HexFormat.of().formatHex(AnchorStatement.sha256(bytes)));
        return StoreResult.ok(bytes);
    }

    /**
     * Verifies a proof of the subject and its link to the previous proof.
     *
     * @param subject  the object id of the subject
     * @param proof    the stored proof
     * @param previous the proof preceding it in the chain; empty for the first
     * @return {@link StoreOutcome#OK} with the time of the statement; {@link StoreOutcome#SIGNATURE_INVALID} with
     *         {@value #PROOF_UNREADABLE}, {@value #NO_ALLOWED_SIGNERS}, {@value #SIGNATURE_INVALID} or
     *         {@value #KEY_NOT_ALLOWED}; {@link StoreOutcome#IMPRINT_MISMATCH} with {@value #OTHER_OBJECT};
     *         {@link StoreOutcome#CHAIN_BROKEN} with {@value #PREVIOUS_UNREADABLE}, {@value #CHAIN_SEQUENCE_GAP},
     *         {@value #CHAIN_PREVIOUS_MISMATCH} or {@value #CHAIN_TIME_DECREASES}
     * @throws NullPointerException if an argument is {@code null}
     */
    @Override
    public StoreResult<Instant> verify(ObjectId subject, byte[] proof, byte[] previous) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(previous, "previous");
        // the proof is copied once and only the copy is read, so a caller changing its buffer changes nothing
        var split = AnchorStatement.split(proof.clone());
        if (split.isEmpty()) {
            return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, PROOF_UNREADABLE);
        }
        var read = split.get();
        var statement = read.statement();
        if (!statement.object().equals(subject)) {
            return StoreResult.of(StoreOutcome.IMPRINT_MISMATCH, OTHER_OBJECT);
        }
        var signatureCheck = checkSignature(read);
        if (signatureCheck != null) {
            return signatureCheck;
        }
        var chainCheck = checkChain(statement, previous.clone());
        if (chainCheck != null) {
            return chainCheck;
        }
        return StoreResult.ok(statement.time());
    }

    private @Nullable StoreResult<Instant> checkSignature(AnchorStatement.Proof read) {
        AllowedSigners signers = allowedSigners;
        if (signers == null) {
            return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, NO_ALLOWED_SIGNERS);
        }
        if (!(SshSignatures.decode(read.signature()) instanceof SshSignatures.DecodedSignature decoded)
                || !SshSignatures.verify(decoded, NAMESPACE, read.statementBytes())) {
            return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, SIGNATURE_INVALID);
        }
        if (signers.check(decoded.publicKeyBlob(), read.statement().time(), NAMESPACE)
                != AllowedSigners.Match.VALID) {
            return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, KEY_NOT_ALLOWED);
        }
        return null;
    }

    private static @Nullable StoreResult<Instant> checkChain(AnchorStatement statement, byte[] previous) {
        if (previous.length == 0) {
            return statement.sequence() == 1 ? null
                    : StoreResult.of(StoreOutcome.CHAIN_BROKEN, CHAIN_SEQUENCE_GAP);
        }
        var split = AnchorStatement.split(previous);
        if (split.isEmpty()) {
            return StoreResult.of(StoreOutcome.CHAIN_BROKEN, PREVIOUS_UNREADABLE);
        }
        var before = split.get().statement();
        if (before.sequence() == Long.MAX_VALUE || statement.sequence() != before.sequence() + 1) {
            return StoreResult.of(StoreOutcome.CHAIN_BROKEN, CHAIN_SEQUENCE_GAP);
        }
        if (!Arrays.equals(statement.previous(), AnchorStatement.sha256(previous))) {
            return StoreResult.of(StoreOutcome.CHAIN_BROKEN, CHAIN_PREVIOUS_MISMATCH);
        }
        if (statement.time().isBefore(before.time())) {
            return StoreResult.of(StoreOutcome.CHAIN_BROKEN, CHAIN_TIME_DECREASES);
        }
        return null;
    }

    /**
     * Renders the provider without the key reference (GDR-NFR-3).
     *
     * @return {@code SignedAnchorProvider[signs=..., verifies=...]}
     */
    @Override
    public String toString() {
        return "SignedAnchorProvider[signs=" + (key != null) + ", verifies=" + (allowedSigners != null) + "]";
    }

    /**
     * The anchor key of the provider: the key source, the key reference passed to it and the public key read once at
     * start.
     *
     * @param source       the anchor key source ({@code anchor.signed.key-source})
     * @param keyReference the key reference ({@code anchor.signed.key})
     * @param publicKey    the public key of the anchor key
     */
    record AnchorKey(SigningKeySource source, String keyReference, SshSignatures.AcceptedKey publicKey) {

        AnchorKey {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(keyReference, "keyReference");
            Objects.requireNonNull(publicKey, "publicKey");
        }

        /**
         * Renders the key without the key reference (GDR-NFR-3).
         *
         * @return {@code AnchorKey[source=<id>]}
         */
        @Override
        public String toString() {
            return "AnchorKey[source=" + source.id() + "]";
        }
    }
}
