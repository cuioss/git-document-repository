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

import java.util.Objects;
import java.util.Optional;


import de.cuioss.git.document.core.CommitSigning;
import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The single signing seam of the store (GDR-STORE-12): every commit the store signs takes its {@link CommitSigning}
 * from here, the save commit ({@link ExplicitSave}) and the release commit ({@link DocumentRelease}) today, the
 * checkpoint and notes commits of GDR-STORE-16 and GDR-STORE-17 once the store writes them. Draft commits are never
 * signed.
 * <p>
 * {@link #prepare(String)} runs once per save or release, before its first attempt: it fetches the public key line
 * from the active {@link SigningKeySource} once, refuses a key of another type than
 * {@value SshSignatures#KEY_TYPE}, and returns the signing of a fresh {@link SourceSigner}, which every attempt of the
 * operation re-uses. Only the signature itself is made per attempt, because the signed commit depends on the fetched
 * base. A signing failure while a commit is written surfaces from {@code InCoreWriter} as the passed-on
 * {@link GitOutcome#FAILED}; {@link #outcomeOf} maps it back to the outcome of the source.
 * <p>
 * Every non-success is logged as {@code GIT_STORE-110} with the alias, the reference and the outcome. No detail and no
 * log record names the key reference, key material or the detail of the source (GDR-NFR-3).
 */
final class CommitSignatures {

    private static final CuiLogger LOGGER = new CuiLogger(CommitSignatures.class);

    /** The detail of a key of another type than Ed25519. */
    static final String KEY_TYPE_NOT_SUPPORTED = "signing: key type not supported";

    /** The detail of a source that could not be reached. */
    static final String SOURCE_UNAVAILABLE = "signing: key source unavailable";

    /** The detail of a source that refused, or failed in another way. */
    static final String SOURCE_REFUSED = "signing: key source refused";

    /** The detail of a commit whose signing failed, after the reference. */
    static final String NOT_SIGNED = ": signing: not signed";

    private final String alias;
    private final SigningKeySource source;
    private final String keyReference;

    /**
     * Creates the signing seam of a clone.
     *
     * @param alias        the alias of the repository, for the log record
     * @param source       the active signing key source
     * @param keyReference the key reference ({@code signing.key}) passed to the source
     * @throws NullPointerException if an argument is {@code null}
     */
    CommitSignatures(String alias, SigningKeySource source, String keyReference) {
        this.alias = Objects.requireNonNull(alias, "alias");
        this.source = Objects.requireNonNull(source, "source");
        this.keyReference = Objects.requireNonNull(keyReference, "keyReference");
    }

    /**
     * Prepares the signing of one save or release: fetches the public key line once and checks its type.
     *
     * @param reference the reference the operation writes, for the log record
     * @return {@link StoreOutcome#OK} with the prepared signing; {@link StoreOutcome#REFUSED} with
     *         {@value #KEY_TYPE_NOT_SUPPORTED} for a key of another type, or with {@value #SOURCE_REFUSED} for a source
     *         that refused; {@link StoreOutcome#UNAVAILABLE} with {@value #SOURCE_UNAVAILABLE} for a source that cannot
     *         be reached
     */
    StoreResult<Prepared> prepare(String reference) {
        StoreResult<String> line = source.publicKey(keyReference);
        if (!line.isOk()) {
            return refused(reference, sourceFailure(line.outcome()));
        }
        String publicKeyLine = Objects.requireNonNull(line.value());
        if (!(SshSignatures.parsePublicKeyLine(publicKeyLine) instanceof SshSignatures.KeyLine.Accepted)) {
            return refused(reference, StoreResult.of(StoreOutcome.REFUSED, KEY_TYPE_NOT_SUPPORTED));
        }
        return StoreResult.ok(new Prepared(new SourceSigner(source, keyReference, publicKeyLine)));
    }

    /**
     * Maps a write that failed because the source did not sign to the outcome of the source.
     *
     * @param written   the result of the write
     * @param prepared  the signing the write used
     * @param reference the reference the operation writes, named by the detail
     * @return the outcome of the source with the detail {@code <reference>: signing: not signed}, or empty when the
     *         write did not fail because of the signing
     */
    <V> Optional<StoreResult<V>> outcomeOf(GitResult<?> written, Prepared prepared, String reference) {
        if (written.outcome() != GitOutcome.FAILED) {
            return Optional.empty();
        }
        return prepared.signer().failure().map(failure -> {
            StoreResult<V> mapped = new StoreResult<>(sourceOutcome(failure.outcome()), null,
                    reference + NOT_SIGNED);
            log(reference, mapped);
            return mapped;
        });
    }

    private <V> StoreResult<V> refused(String reference, StoreResult<V> result) {
        log(reference, result);
        return result;
    }

    private void log(String reference, StoreResult<?> result) {
        LOGGER.warn(GitStoreLogMessages.WARN.SIGNING_FAILED, alias, reference, name(result.outcome()));
    }

    /** The result of a source that handed out no public key; the source's own detail is never passed on. */
    private static <V> StoreResult<V> sourceFailure(StoreResult.Outcome outcome) {
        return sourceOutcome(outcome) == StoreOutcome.UNAVAILABLE
                ? StoreResult.of(StoreOutcome.UNAVAILABLE, SOURCE_UNAVAILABLE)
                : StoreResult.of(StoreOutcome.REFUSED, SOURCE_REFUSED);
    }

    /** The outcome a failure of the source ends the operation with: {@code UNAVAILABLE} as it is, else {@code REFUSED}. */
    private static StoreOutcome sourceOutcome(StoreResult.Outcome outcome) {
        return outcome == StoreOutcome.UNAVAILABLE ? StoreOutcome.UNAVAILABLE : StoreOutcome.REFUSED;
    }

    private static String name(StoreResult.Outcome outcome) {
        return switch (outcome) {
            case StoreOutcome store -> store.name();
            case StoreResult.PassedOn(GitOutcome git) -> git.name();
        };
    }

    /**
     * The signing of one save or release, re-used by every attempt.
     *
     * @param signer the signer over the active source and the public key fetched once
     */
    record Prepared(SourceSigner signer) {

        /**
         * Returns the signing to hand to {@code InCoreWriter}.
         *
         * @return the signing, without signing key and credentials: the key reference stays inside the signer
         */
        CommitSigning signing() {
            return new CommitSigning(signer, null, null);
        }
    }
}
