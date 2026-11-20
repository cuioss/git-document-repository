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
 * The service interface of a signing key source (GDR-STORE-12): it signs bytes with a private key it holds.
 * <p>
 * The store uses the private key of a commit signature, and the anchor key of the provider {@code signed}, only
 * through the active source; the private key never leaves the source. The store ships the source {@code file}; an
 * application supplies another one (a KMS or an HSM) through {@link java.util.ServiceLoader} without a change of the
 * store. {@code signing.key-source} names the active source, {@code signing.key} is the key reference passed to it.
 * <p>
 * An expected non-success is a {@link StoreResult}, never an exception: {@link StoreOutcome#UNAVAILABLE} when the
 * source cannot be reached, {@link StoreOutcome#REFUSED} when it refuses to sign or to hand out a public key. A detail
 * never carries key material, a key reference or a credential of the source (GDR-NFR-3).
 *
 * @since 0.1
 */
public interface SigningKeySource {

    /**
     * Returns the id of the source, the value {@code signing.key-source} names it by.
     *
     * @return the id, never blank
     */
    String id();

    /**
     * Signs bytes with the private key the key reference names.
     *
     * @param keyReference the key reference ({@code signing.key}); for the source {@code file} the path of an SSH
     *                     private key
     * @param data         the bytes to sign
     * @return {@link StoreOutcome#OK} with the raw signature, {@link StoreOutcome#UNAVAILABLE} or
     * {@link StoreOutcome#REFUSED}
     */
    StoreResult<byte[]> sign(String keyReference, byte[] data);

    /**
     * Returns the public key of the private key the key reference names.
     * <p>
     * The store needs it because the SSH signature format embeds the public key of the signer in every signature (the
     * field {@code publickey} of the signature blob,
     * <a href="https://github.com/openssh/openssh-portable/blob/master/PROTOCOL.sshsig">PROTOCOL.sshsig</a>).
     *
     * @param keyReference the key reference ({@code signing.key})
     * @return {@link StoreOutcome#OK} with the public key as an OpenSSH public key line,
     * {@link StoreOutcome#UNAVAILABLE} or {@link StoreOutcome#REFUSED}
     */
    StoreResult<String> publicKey(String keyReference);
}
