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

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;


import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Signer;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * The JGit {@link Signer} of the store (checked against JGit 7.8.0): it signs a commit in the SSH signature format of
 * <a href="https://github.com/openssh/openssh-portable/blob/master/PROTOCOL.sshsig">PROTOCOL.sshsig</a> through the
 * active {@link SigningKeySource} (GDR-STORE-12).
 * <p>
 * {@code InCoreWriter} of {@code git-core} hands it the raw commit through {@code CommitSigning}; the signer builds the
 * signed blob in the namespace {@value SshSignatures#NAMESPACE_GIT} ({@link SshSignatures#signedData(String, byte[])}),
 * has the source sign it under the key reference, and returns the armored signature, which JGit writes as the
 * {@code gpgsig} header. The {@link GpgConfig}, the signing key and the credentials provider JGit passes are ignored:
 * the key reference and the public key line, fetched once by the caller, are given at construction.
 * <p>
 * A non-success of the source is captured and surfaces to JGit as an {@link IOException} whose message carries no key
 * reference, no key material and no detail of the source (GDR-NFR-3); JGit reports the write as failed, and
 * {@link #failure()} hands the captured outcome to the commit path, so it can report {@link StoreOutcome#REFUSED} or
 * {@link StoreOutcome#UNAVAILABLE} instead. An instance serves one save or release on one thread.
 */
final class SourceSigner implements Signer {

    /** The message of every signing failure: names neither the key reference nor the source's detail. */
    static final String FAILURE_MESSAGE = "signing: the signing key source did not sign";

    private final SigningKeySource source;
    private final String keyReference;
    private final byte @Nullable [] publicKeyBlob;
    private @Nullable StoreResult<Void> failure;

    /**
     * Creates the signer.
     *
     * @param source        the active signing key source
     * @param keyReference  the key reference ({@code signing.key}) passed to the source
     * @param publicKeyLine the OpenSSH public key line of that key, as the source handed it out
     * @throws NullPointerException if an argument is {@code null}
     */
    SourceSigner(SigningKeySource source, String keyReference, String publicKeyLine) {
        this.source = Objects.requireNonNull(source, "source");
        this.keyReference = Objects.requireNonNull(keyReference, "keyReference");
        this.publicKeyBlob = SshSignatures.parsePublicKeyLine(Objects.requireNonNull(publicKeyLine, "publicKeyLine"))
                instanceof SshSignatures.KeyLine.Accepted accepted ? accepted.blob() : null;
    }

    @Override
    public GpgSignature sign(Repository repository, GpgConfig config, byte[] data, PersonIdent committer,
            @Nullable String signingKey, @Nullable CredentialsProvider credentialsProvider) throws IOException {
        if (publicKeyBlob == null) {
            failure = StoreResult.of(StoreOutcome.REFUSED, "signing: key type not supported");
            throw new IOException(FAILURE_MESSAGE);
        }
        StoreResult<byte[]> signed = source.sign(keyReference,
                SshSignatures.signedData(SshSignatures.NAMESPACE_GIT, data));
        byte[] signature = signed.value();
        if (!signed.isOk() || signature == null) {
            failure = new StoreResult<>(signed.outcome(), null, signed.detail());
            throw new IOException(FAILURE_MESSAGE);
        }
        return new GpgSignature(
                SshSignatures.encode(publicKeyBlob, SshSignatures.NAMESPACE_GIT, signature).getBytes(US_ASCII));
    }

    @Override
    public boolean canLocateSigningKey(Repository repository, GpgConfig config, PersonIdent committer,
            @Nullable String signingKey, @Nullable CredentialsProvider credentialsProvider) {
        return publicKeyBlob != null;
    }

    /**
     * Returns the non-success of the source captured by the last failed signing.
     *
     * @return the captured outcome with the source's detail, or empty when no signing failed
     */
    Optional<StoreResult<Void>> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public String toString() {
        return "SourceSigner[source=" + source.id() + "]";
    }
}
