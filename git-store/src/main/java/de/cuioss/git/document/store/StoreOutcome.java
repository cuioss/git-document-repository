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

/**
 * The closed list of outcomes of {@code git-store} (GDR-GEN-3).
 * <p>
 * An operation of {@code git-store}, of a provider of its service interfaces, or of {@code git-timestamp} for its
 * provider reports either one of these constants or a Git outcome it passes on ({@link StoreResult.PassedOn}), always
 * in a {@link StoreResult}; an expected non-success is never signalled by an exception. The list carries no domain
 * term.
 *
 * @since 0.1
 */
public enum StoreOutcome implements StoreResult.Outcome {

    /**
     * Success: the operation completed. A valid anchor proof is a success carrying the time it establishes.
     */
    OK,

    /** Incomplete: raised by a release whose tag is still missing after {@code main} moved. */
    INCOMPLETE,

    /**
     * Unavailable: raised when obtaining an anchor proof and the provider cannot be reached, the subject staying
     * pending, and by a signing key source that cannot be reached.
     */
    UNAVAILABLE,

    /**
     * Refused: raised when obtaining an anchor proof and the provider refuses it, the subject staying pending, by a
     * signing key source that refuses to sign or to hand out a public key, and by a save whose per-attempt step
     * refused, nothing being pushed.
     */
    REFUSED,

    /** No proof: raised by anchor verification for a subject that should be anchored and has no proof. */
    NO_PROOF,

    /** Imprint mismatch: raised by anchor verification when the proof is about another object id. */
    IMPRINT_MISMATCH,

    /**
     * Signature invalid: raised by anchor and commit-signature verification when the signature does not verify
     * against the allowed signers, or when the proof cannot be read.
     */
    SIGNATURE_INVALID,

    /**
     * Chain broken: raised by the verification of a chained proof for a gap in the sequence numbers, a wrong
     * predecessor or a decreasing time.
     */
    CHAIN_BROKEN,

    /**
     * Not supported: raised by a {@code PersonDirectory} provider whose source the operator maintains, for saving an
     * entry, for unbound entries and for binding.
     */
    NOT_SUPPORTED,

    /**
     * Managed by source: raised by a {@code PersonDirectory} provider whose source the operator maintains, for
     * erasing a pseudonym.
     */
    MANAGED_BY_SOURCE,

    /**
     * Malformed: raised at start for a configuration that lacks a required key, holds a malformed value, an invalid or
     * duplicate alias, a remote with user information or an anchor key equal to the commit-signing key, or that names
     * a provider that is missing or not unique; and by a {@code Codec} for a content it cannot decode. The detail
     * names the key, alias, path or provider id, never the value.
     */
    MALFORMED
}
