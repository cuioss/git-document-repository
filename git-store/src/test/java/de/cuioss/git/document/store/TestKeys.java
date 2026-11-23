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
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test keys of the SSH signature format (GDR-STORE-12), generated per call with the JDK {@link KeyPairGenerator}: an
 * Ed25519 key pair with its OpenSSH public key line, its unencrypted {@code openssh-key-v1} private key file and an
 * in-memory {@link SigningKeySource} over it; and, for refusal tests, an {@code openssh-key-v1} file of type
 * {@code ssh-rsa} and one of an Ed25519 key under the cipher {@code aes256-ctr}.
 * <p>
 * The private key file format is the one of
 * <a href="https://github.com/openssh/openssh-portable/blob/master/PROTOCOL.key">PROTOCOL.key</a>. Nothing here prints
 * or logs a key byte.
 */
final class TestKeys {

    /** The first line of an OpenSSH private key file. */
    static final String PRIVATE_BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----";

    /** The last line of an OpenSSH private key file. */
    static final String PRIVATE_END = "-----END OPENSSH PRIVATE KEY-----";

    private static final byte[] AUTH_MAGIC = "openssh-key-v1\0".getBytes(US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();

    private TestKeys() {
    }

    /**
     * Generates an Ed25519 key pair.
     *
     * @return the new key
     */
    static Ed25519 ed25519() {
        try {
            return new Ed25519(KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every Java platform from Java 15 on provides Ed25519", e);
        }
    }

    /**
     * Writes an unencrypted {@code openssh-key-v1} private key file of a newly generated RSA key.
     *
     * @param file the file to write
     * @return the OpenSSH public key line of the key
     * @throws IOException if writing fails
     */
    static String writeRsa(Path file) throws IOException {
        KeyPair pair;
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            pair = generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("every Java platform provides RSA", e);
        }
        var publicKey = (RSAPublicKey) pair.getPublic();
        var privateKey = (RSAPrivateCrtKey) pair.getPrivate();
        var publicBlob = new ByteArrayOutputStream();
        writeString(publicBlob, "ssh-rsa".getBytes(US_ASCII));
        writeMpint(publicBlob, publicKey.getPublicExponent());
        writeMpint(publicBlob, publicKey.getModulus());

        var section = new ByteArrayOutputStream();
        writeString(section, "ssh-rsa".getBytes(US_ASCII));
        writeMpint(section, publicKey.getModulus());
        writeMpint(section, publicKey.getPublicExponent());
        writeMpint(section, privateKey.getPrivateExponent());
        writeMpint(section, privateKey.getCrtCoefficient());
        writeMpint(section, privateKey.getPrimeP());
        writeMpint(section, privateKey.getPrimeQ());
        writeString(section, "test".getBytes(US_ASCII));

        Files.writeString(file, armor(keyFile("none", "none", new byte[0], publicBlob.toByteArray(),
                pad(withCheck(section.toByteArray()), 8))), US_ASCII);
        return "ssh-rsa " + Base64.getEncoder().encodeToString(publicBlob.toByteArray()) + " test";
    }

    /**
     * Writes an {@code openssh-key-v1} file of a newly generated Ed25519 key whose header names the cipher
     * {@code aes256-ctr} and the KDF {@code bcrypt}. Its private section is random bytes of the length an encrypted
     * section has: a source must refuse the file by its header, before any decryption.
     *
     * @param file the file to write
     * @throws IOException if writing fails
     */
    static void writeEncrypted(Path file) throws IOException {
        Ed25519 key = ed25519();
        var kdfOptions = new ByteArrayOutputStream();
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        writeString(kdfOptions, salt);
        kdfOptions.writeBytes(uint32(16));
        byte[] encrypted = new byte[160];
        RANDOM.nextBytes(encrypted);
        Files.writeString(file, armor(keyFile("aes256-ctr", "bcrypt", kdfOptions.toByteArray(), key.publicKeyBlob(),
                encrypted)), US_ASCII);
    }

    private static byte[] keyFile(String cipher, String kdf, byte[] kdfOptions, byte[] publicBlob,
            byte[] privateSection) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(AUTH_MAGIC);
        writeString(out, cipher.getBytes(US_ASCII));
        writeString(out, kdf.getBytes(US_ASCII));
        writeString(out, kdfOptions);
        out.writeBytes(uint32(1));
        writeString(out, publicBlob);
        writeString(out, privateSection);
        return out.toByteArray();
    }

    /** Prepends the two equal check integers to a private section. */
    private static byte[] withCheck(byte[] section) {
        byte[] check = uint32(RANDOM.nextInt());
        var out = new ByteArrayOutputStream();
        out.writeBytes(check);
        out.writeBytes(check);
        out.writeBytes(section);
        return out.toByteArray();
    }

    /** Pads a private section with the bytes 1, 2, 3, ... to a multiple of the block size. */
    private static byte[] pad(byte[] section, int blockSize) {
        int padding = (blockSize - section.length % blockSize) % blockSize;
        byte[] padded = Arrays.copyOf(section, section.length + padding);
        for (int i = 0; i < padding; i++) {
            padded[section.length + i] = (byte) (i + 1);
        }
        return padded;
    }

    private static String armor(byte[] content) {
        String base64 = Base64.getEncoder().encodeToString(content);
        var armored = new StringBuilder(PRIVATE_BEGIN).append('\n');
        for (int start = 0; start < base64.length(); start += 70) {
            armored.append(base64, start, Math.min(base64.length(), start + 70)).append('\n');
        }
        return armored.append(PRIVATE_END).append('\n').toString();
    }

    private static byte[] uint32(int value) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(value).array();
    }

    private static void writeString(ByteArrayOutputStream out, byte[] value) {
        out.writeBytes(uint32(value.length));
        out.writeBytes(value);
    }

    private static void writeMpint(ByteArrayOutputStream out, BigInteger value) {
        writeString(out, value.toByteArray());
    }

    /**
     * A generated Ed25519 key pair.
     */
    static final class Ed25519 {

        private final KeyPair pair;
        private final byte[] publicKey;

        private Ed25519(KeyPair pair) {
            this.pair = pair;
            byte[] encoded = pair.getPublic().getEncoded();
            // the X.509 SubjectPublicKeyInfo of an Ed25519 key ends with the 32 key bytes (RFC 8410, section 4)
            this.publicKey = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        }

        /**
         * Returns the raw public key.
         *
         * @return a copy of the 32 key bytes
         */
        byte[] publicKey() {
            return publicKey.clone();
        }

        /**
         * Returns the SSH wire form of the public key.
         *
         * @return the public key blob
         */
        byte[] publicKeyBlob() {
            return SshSignatures.publicKeyBlob(publicKey);
        }

        /**
         * Returns the OpenSSH public key line {@code ssh-ed25519 <base64> test}.
         *
         * @return the line
         */
        String publicKeyLine() {
            return "ssh-ed25519 " + Base64.getEncoder().encodeToString(publicKeyBlob()) + " test";
        }

        /**
         * Signs bytes with the private key.
         *
         * @param data the bytes to sign
         * @return the raw 64-byte Ed25519 signature
         */
        byte[] sign(byte[] data) {
            try {
                var signature = Signature.getInstance("Ed25519");
                signature.initSign(pair.getPrivate());
                signature.update(data);
                return signature.sign();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Ed25519 signing with a generated key", e);
            }
        }

        /**
         * Writes the key as an unencrypted {@code openssh-key-v1} private key file.
         *
         * @param file the file to write
         * @throws IOException if writing fails
         */
        void writePrivateKey(Path file) throws IOException {
            byte[] seed = ((EdECPrivateKey) pair.getPrivate()).getBytes()
                    .orElseThrow(() -> new IllegalStateException("a generated key has its seed"));
            byte[] privateKey = Arrays.copyOf(seed, 64);
            System.arraycopy(publicKey, 0, privateKey, 32, 32);
            var section = new ByteArrayOutputStream();
            writeString(section, SshSignatures.KEY_TYPE.getBytes(US_ASCII));
            writeString(section, publicKey);
            writeString(section, privateKey);
            writeString(section, "test".getBytes(US_ASCII));
            Arrays.fill(seed, (byte) 0);
            Arrays.fill(privateKey, (byte) 0);
            Files.writeString(file, armor(keyFile("none", "none", new byte[0], publicKeyBlob(),
                    pad(withCheck(section.toByteArray()), 8))), US_ASCII);
        }

        /**
         * Creates an in-memory signing key source over this key, accepting any key reference.
         *
         * @return the source
         */
        Source source() {
            return new Source(this);
        }
    }

    /**
     * An in-memory signing key source over a generated Ed25519 key; it counts its calls.
     */
    static final class Source implements SigningKeySource {

        /** The id of the source. */
        static final String ID = "test-keys";

        private final Ed25519 key;
        private final AtomicInteger signCalls = new AtomicInteger();
        private final AtomicInteger publicKeyCalls = new AtomicInteger();

        private Source(Ed25519 key) {
            this.key = key;
        }

        @Override
        public String id() {
            return ID;
        }

        @Override
        public StoreResult<byte[]> sign(String keyReference, byte[] data) {
            signCalls.incrementAndGet();
            return StoreResult.ok(key.sign(data));
        }

        @Override
        public StoreResult<String> publicKey(String keyReference) {
            publicKeyCalls.incrementAndGet();
            return StoreResult.ok(key.publicKeyLine());
        }

        /**
         * Returns the key the source signs with.
         *
         * @return the key
         */
        Ed25519 key() {
            return key;
        }

        /**
         * Returns how often {@link #sign(String, byte[])} was called.
         *
         * @return the number of calls
         */
        int signCalls() {
            return signCalls.get();
        }

        /**
         * Returns how often {@link #publicKey(String)} was called.
         *
         * @return the number of calls
         */
        int publicKeyCalls() {
            return publicKeyCalls.get();
        }
    }
}
