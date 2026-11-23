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

import java.time.Instant;


import org.eclipse.jgit.lib.ObjectId;

/**
 * The service interface of an anchor provider (GDR-STORE-15): it obtains a proof that an object existed at a time, and
 * verifies a stored proof.
 * <p>
 * At most one provider is active, named by {@code anchor.provider}; without one the store anchors nothing. A provider
 * only obtains and verifies proofs: which subjects are anchored is decided by the store (GDR-STORE-16). A proof is
 * bytes the store does not interpret; the store keeps it as a note under {@code refs/notes/anchors/<id>}
 * (GDR-STORE-17). A failing or unreachable provider never blocks or fails a save, a release or a fetch.
 * <p>
 * Every provider uses the outcomes of {@link StoreOutcome} identically, always in a {@link StoreResult}, never as an
 * exception.
 *
 * @since 0.1
 */
public interface AnchorProvider {

    /**
     * Returns the id of the provider, the value {@code anchor.provider} names it by; it also names the notes reference
     * {@code refs/notes/anchors/<id>} of its proofs.
     * <p>
     * The id must match {@code [a-z0-9-]+}, the alphabet of a repository alias, to be selectable: a configured
     * {@code anchor.provider} outside that alphabet is refused at start as {@link StoreOutcome#MALFORMED}, so no
     * configured id can match a provider whose id lies outside it, and the start stops with the refusal that no
     * provider has the id of {@code anchor.provider}.
     *
     * @return the id, never blank; of the form {@code [a-z0-9-]+} to be selectable
     */
    String id();

    /**
     * Obtains a proof that the subject exists now.
     * <p>
     * The store reads the commit time of the subject, the committer time of the anchored commit, from the commit and
     * passes it along: the provider {@code signed} refuses when its clock is more than
     * {@code anchor.signed.max-clock-skew} behind it (GDR-STORE-18). A provider whose authority keeps its own time, such
     * as {@code rfc3161}, may ignore it.
     *
     * @param repository the alias of the repository of the subject
     * @param subject    the object id of the subject
     * @param commitTime the commit time of the subject
     * @param previous   the previous proof of this provider in the repository; empty for the first
     * @return {@link StoreOutcome#OK} with the proof, {@link StoreOutcome#UNAVAILABLE} when the provider cannot be
     * reached, or {@link StoreOutcome#REFUSED} when it refuses or answers with an error; the subject stays pending
     * on a non-success
     */
    StoreResult<byte[]> obtain(String repository, ObjectId subject, Instant commitTime, byte[] previous);

    /**
     * Verifies a stored proof of the subject.
     *
     * @param subject  the object id of the subject
     * @param proof    the stored proof
     * @param previous the proof preceding it in the chain of this provider; empty for the first
     * @return {@link StoreOutcome#OK} with the time the proof establishes, {@link StoreOutcome#IMPRINT_MISMATCH} for a
     * proof about another object id, {@link StoreOutcome#SIGNATURE_INVALID} also for a proof that cannot be read,
     * or {@link StoreOutcome#CHAIN_BROKEN}
     */
    StoreResult<Instant> verify(ObjectId subject, byte[] proof, byte[] previous);
}
