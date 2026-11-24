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
package de.cuioss.git.document.timestamp;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;


import de.cuioss.git.document.store.Membership;
import de.cuioss.git.document.store.PersonDirectory;
import de.cuioss.git.document.store.PersonEntry;
import de.cuioss.git.document.store.SigningKeySource;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;

/**
 * Test doubles of the two store providers the end-to-end anchoring test needs besides the anchor provider, since the
 * test doubles of {@code git-store} are not published: a {@link SigningKeySource} over an Ed25519 key pair the JDK
 * generates, and a {@link PersonDirectory} that knows nobody.
 */
final class TestStoreProviders {

    /** The id of the signing key source, the value of {@code signing.key-source}. */
    static final String SIGNING_ID = "test-keys";

    /** The id of the person directory, the value of {@code directory.persons.provider}. */
    static final String DIRECTORY_ID = "test-directory";

    /** The SSH key type of Ed25519 (RFC 8709, 4). */
    private static final String KEY_TYPE = "ssh-ed25519";

    /** The length of a raw Ed25519 public key. */
    private static final int KEY_LENGTH = 32;

    private TestStoreProviders() {
        // static factories only
    }

    /**
     * Creates a signing key source over a newly generated Ed25519 key pair; it accepts any key reference.
     *
     * @return the source
     */
    static SigningKeySource signing() {
        try {
            return new Ed25519Source(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every Java platform from Java 15 on provides Ed25519", e);
        }
    }

    /**
     * Creates a person directory that knows nobody: lookups are empty and writes are not supported.
     *
     * @return the directory
     */
    static PersonDirectory directory() {
        return new EmptyDirectory();
    }

    /** A signing key source over one Ed25519 key pair. */
    private static final class Ed25519Source implements SigningKeySource {

        private final KeyPair keys;
        private final String publicKeyLine;

        Ed25519Source(KeyPair keys) {
            this.keys = keys;
            byte[] encoded = keys.getPublic().getEncoded();
            // the X.509 SubjectPublicKeyInfo of an Ed25519 key ends with the 32 key bytes (RFC 8410, 4)
            byte[] raw = Arrays.copyOfRange(encoded, encoded.length - KEY_LENGTH, encoded.length);
            var blob = new ByteArrayOutputStream();
            writeString(blob, KEY_TYPE.getBytes(US_ASCII));
            writeString(blob, raw);
            this.publicKeyLine = KEY_TYPE + " " + Base64.getEncoder().encodeToString(blob.toByteArray()) + " test";
        }

        private static void writeString(ByteArrayOutputStream out, byte[] value) {
            out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
            out.writeBytes(value);
        }

        @Override
        public String id() {
            return SIGNING_ID;
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            try {
                var signature = Signature.getInstance("Ed25519");
                signature.initSign(keys.getPrivate());
                signature.update(data);
                return StoreResult.ok(signature.sign());
            } catch (GeneralSecurityException e) {
                return StoreResult.of(StoreOutcome.REFUSED, "the test source cannot sign");
            }
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            return StoreResult.ok(publicKeyLine);
        }
    }

    /** A person directory over the operator's own source that knows nobody. */
    private static final class EmptyDirectory implements PersonDirectory {

        private static final String NOT_SUPPORTED = "the test directory is maintained by its source";

        @Override
        public String id() {
            return DIRECTORY_ID;
        }

        @Override
        public StoreResult<String> pseudonym(String issuer, String subject) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, "the test directory assigns no pseudonym");
        }

        @Override
        public StoreResult<PersonEntry> entry(String pseudonym) {
            return StoreResult.ok(new PersonEntry.FormerMember(pseudonym, Set.of()));
        }

        @Override
        public StoreResult<List<String>> holders(String team, String role) {
            return StoreResult.ok(List.of());
        }

        @Override
        public StoreResult<PersonEntry.Member> save(PersonEntry.Member entry) {
            return StoreResult.of(StoreOutcome.NOT_SUPPORTED, NOT_SUPPORTED);
        }

        @Override
        public StoreResult<String> saveUnbound(String name, Set<Membership> memberships) {
            return StoreResult.of(StoreOutcome.NOT_SUPPORTED, NOT_SUPPORTED);
        }

        @Override
        public StoreResult<List<PersonEntry.Member>> unbound(Set<Membership> memberships) {
            return StoreResult.of(StoreOutcome.NOT_SUPPORTED, NOT_SUPPORTED);
        }

        @Override
        public StoreResult<String> bind(String pseudonym, String issuer, String subject) {
            return StoreResult.of(StoreOutcome.NOT_SUPPORTED, NOT_SUPPORTED);
        }

        @Override
        public StoreResult<String> erase(String pseudonym) {
            return StoreResult.of(StoreOutcome.MANAGED_BY_SOURCE, NOT_SUPPORTED);
        }
    }
}
