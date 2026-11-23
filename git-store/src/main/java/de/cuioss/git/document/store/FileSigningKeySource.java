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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * The signing key source {@value #SOURCE_ID} the store ships (GDR-STORE-12): it signs with an unencrypted OpenSSH Ed25519
 * private key from a file, the key reference ({@code signing.key}) being the path of that file.
 * <p>
 * The file must be in the format {@code openssh-key-v1} of
 * <a href="https://github.com/openssh/openssh-portable/blob/master/PROTOCOL.key">PROTOCOL.key</a>, as
 * {@code ssh-keygen -t ed25519 -N ""} writes it: cipher {@code none}, KDF {@code none}, exactly one key of type
 * {@value SshSignatures#KEY_TYPE}, and matching check integers. The file is read on every call; no key is kept between
 * calls, and every byte array of the source that held key material is zeroed before the call returns. The signature is
 * made with the JDK provider {@code Ed25519}; the key specification and the private key the JCA builds from the seed
 * copy it into buffers the source does not own, and the source does not promise to clear those copies.
 * <p>
 * The outcomes of {@link #sign(String, byte[])} and {@link #publicKey(String)}:
 * <ul>
 * <li>{@link StoreOutcome#UNAVAILABLE} {@value #UNAVAILABLE}: the file cannot be read;</li>
 * <li>{@link StoreOutcome#REFUSED} {@value #TYPE_NOT_SUPPORTED}: the file holds another key type;</li>
 * <li>{@link StoreOutcome#REFUSED} {@value #ENCRYPTED}: the key is encrypted;</li>
 * <li>{@link StoreOutcome#REFUSED} {@value #MALFORMED}: the file is no valid {@code openssh-key-v1} key.</li>
 * </ul>
 * No detail names the path or a key byte, and the source logs nothing (GDR-NFR-3).
 *
 * @since 0.1
 */
public final class FileSigningKeySource implements SigningKeySource {

    /** The id of the source, the value {@code signing.key-source} names it by. */
    public static final String SOURCE_ID = "file";

    /** The detail of a file that cannot be read. */
    static final String UNAVAILABLE = "signing: key unavailable";

    /** The detail of a key of another type than Ed25519. */
    static final String TYPE_NOT_SUPPORTED = "signing: key type not supported";

    /** The detail of an encrypted key. */
    static final String ENCRYPTED = "signing: encrypted key not supported";

    /** The detail of a file that is no valid {@code openssh-key-v1} key. */
    static final String MALFORMED = "signing: key malformed";

    /** The largest key file read; an Ed25519 key file has a few hundred bytes. */
    private static final long MAX_FILE_SIZE = 64L * 1024;

    private static final byte[] BEGIN = "-----BEGIN OPENSSH PRIVATE KEY-----".getBytes(US_ASCII);
    private static final byte[] END = "-----END OPENSSH PRIVATE KEY-----".getBytes(US_ASCII);
    private static final byte[] PEM_BEGIN = "-----BEGIN ".getBytes(US_ASCII);
    private static final byte[] AUTH_MAGIC = "openssh-key-v1\0".getBytes(US_ASCII);
    private static final byte[] NONE = "none".getBytes(US_ASCII);
    private static final byte[] KEY_TYPE = SshSignatures.KEY_TYPE.getBytes(US_ASCII);

    /** The DER prefix of the PKCS #8 encoding of an Ed25519 private key (RFC 8410, section 7), followed by the seed. */
    private static final byte[] PKCS8_PREFIX = HexFormat.of().parseHex("302e020100300506032b657004220420");

    private static final int KEY_LENGTH = 32;

    /** Creates the source, as {@link java.util.ServiceLoader} does. It holds no state. */
    public FileSigningKeySource() {
        // stateless: the key is read on every call
    }

    @Override
    public String id() {
        return SOURCE_ID;
    }

    @Override
    public StoreResult<byte[]> sign(String keyReference, byte[] data) {
        Objects.requireNonNull(data, "data");
        KeyMaterial key = new KeyMaterial();
        try {
            StoreResult<Void> read = read(keyReference, key);
            if (!read.isOk()) {
                return new StoreResult<>(read.outcome(), null, read.detail());
            }
            return StoreResult.ok(ed25519Sign(key.seed, data));
        } finally {
            key.clear();
        }
    }

    @Override
    public StoreResult<String> publicKey(String keyReference) {
        KeyMaterial key = new KeyMaterial();
        try {
            StoreResult<Void> read = read(keyReference, key);
            if (!read.isOk()) {
                return new StoreResult<>(read.outcome(), null, read.detail());
            }
            return StoreResult.ok(SshSignatures.KEY_TYPE + " "
                    + Base64.getEncoder().encodeToString(SshSignatures.publicKeyBlob(key.publicKey)));
        } finally {
            key.clear();
        }
    }

    /**
     * Reads and parses the key file into the key material; every array it allocates for key material is handed to
     * {@code key}, so the caller's {@code finally} zeroes it.
     */
    private static StoreResult<Void> read(String keyReference, KeyMaterial key) {
        Objects.requireNonNull(keyReference, "keyReference");
        try {
            Path file = Path.of(keyReference);
            if (Files.size(file) > MAX_FILE_SIZE) {
                return StoreResult.of(StoreOutcome.REFUSED, MALFORMED);
            }
            key.file = Files.readAllBytes(file);
        } catch (IOException | InvalidPathException | SecurityException e) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, UNAVAILABLE);
        }
        int begin = indexOf(key.file, BEGIN, 0);
        if (begin < 0) {
            // a PEM block of another format holds another key type, for example an RSA key in PKCS #1
            return StoreResult.of(StoreOutcome.REFUSED,
                    indexOf(key.file, PEM_BEGIN, 0) >= 0 ? TYPE_NOT_SUPPORTED : MALFORMED);
        }
        int end = indexOf(key.file, END, begin + BEGIN.length);
        if (end < 0) {
            return StoreResult.of(StoreOutcome.REFUSED, MALFORMED);
        }
        key.body = Arrays.copyOfRange(key.file, begin + BEGIN.length, end);
        try {
            key.decoded = Base64.getMimeDecoder().decode(key.body);
        } catch (IllegalArgumentException e) {
            return StoreResult.of(StoreOutcome.REFUSED, MALFORMED);
        }
        return parse(key);
    }

    private static StoreResult<Void> parse(KeyMaterial key) {
        var reader = new SshSignatures.Reader(key.decoded);
        if (!reader.expect(AUTH_MAGIC)) {
            return StoreResult.of(StoreOutcome.REFUSED, MALFORMED);
        }
        byte[] cipher = reader.string();
        byte[] kdf = reader.string();
        byte[] kdfOptions = reader.string();
        Integer keys = reader.uint32();
        byte[] publicBlob = reader.string();
        if (cipher == null || kdf == null || kdfOptions == null || keys == null || publicBlob == null) {
            return StoreResult.of(StoreOutcome.REFUSED, MALFORMED);
        }
        if (!Arrays.equals(cipher, NONE) || !Arrays.equals(kdf, NONE)) {
            return StoreResult.of(StoreOutcome.REFUSED, ENCRYPTED);
        }
        if (keys != 1) {
            return StoreResult.of(StoreOutcome.REFUSED, MALFORMED);
        }
        byte[] publicType = new SshSignatures.Reader(publicBlob).string();
        if (publicType != null && !Arrays.equals(publicType, KEY_TYPE)) {
            return StoreResult.of(StoreOutcome.REFUSED, TYPE_NOT_SUPPORTED);
        }
        var publicKey = SshSignatures.ed25519Key(publicBlob);
        byte[] section = reader.string();
        if (section != null) {
            key.section = section;
        }
        if (publicKey.isEmpty() || section == null || !reader.atEnd()) {
            return StoreResult.of(StoreOutcome.REFUSED, MALFORMED);
        }
        return parseSection(key, publicKey.get());
    }

    /** Parses the unencrypted private section: check integers, key type, public key, private key, comment, padding. */
    private static StoreResult<Void> parseSection(KeyMaterial key, byte[] publicKey) {
        var reader = new SshSignatures.Reader(key.section);
        Integer check1 = reader.uint32();
        Integer check2 = reader.uint32();
        byte[] type = reader.string();
        byte[] sectionPublicKey = reader.string();
        byte[] privateKey = reader.string();
        if (privateKey != null) {
            key.privateKey = privateKey;
        }
        byte[] comment = reader.string();
        if (check1 == null || !check1.equals(check2) || type == null || sectionPublicKey == null
                || privateKey == null || comment == null || !validPadding(reader.rest())) {
            return StoreResult.of(StoreOutcome.REFUSED, MALFORMED);
        }
        if (!Arrays.equals(type, KEY_TYPE)) {
            return StoreResult.of(StoreOutcome.REFUSED, TYPE_NOT_SUPPORTED);
        }
        // the private key of the format is the 32-byte seed followed by the 32-byte public key
        if (key.privateKey.length != 2 * KEY_LENGTH || !Arrays.equals(sectionPublicKey, publicKey)
                || !Arrays.equals(key.privateKey, KEY_LENGTH, 2 * KEY_LENGTH, publicKey, 0, KEY_LENGTH)) {
            return StoreResult.of(StoreOutcome.REFUSED, MALFORMED);
        }
        key.seed = Arrays.copyOf(key.privateKey, KEY_LENGTH);
        key.publicKey = publicKey;
        return new StoreResult<>(StoreOutcome.OK, null, null);
    }

    /** The padding of the private section is the bytes 1, 2, 3, ... up to the block size. */
    private static boolean validPadding(byte[] padding) {
        for (int i = 0; i < padding.length; i++) {
            if (padding[i] != (byte) (i + 1)) {
                return false;
            }
        }
        return padding.length < 256;
    }

    private static byte[] ed25519Sign(byte[] seed, byte[] data) {
        byte[] encoded = Arrays.copyOf(PKCS8_PREFIX, PKCS8_PREFIX.length + KEY_LENGTH);
        try {
            System.arraycopy(seed, 0, encoded, PKCS8_PREFIX.length, KEY_LENGTH);
            var signature = Signature.getInstance("Ed25519");
            signature.initSign(KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded)));
            signature.update(data);
            return signature.sign();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform from Java 15 on provides Ed25519", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("a 32-byte Ed25519 seed is always a valid key", e);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static int indexOf(byte[] data, byte[] part, int from) {
        for (int i = from; i <= data.length - part.length; i++) {
            if (Arrays.equals(data, i, i + part.length, part, 0, part.length)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "FileSigningKeySource[" + SOURCE_ID + "]";
    }

    /**
     * The arrays a call holds key material in, zeroed together by {@link #clear()}; each is empty until the parse
     * reaches it.
     */
    private static final class KeyMaterial {

        private byte[] file = new byte[0];
        private byte[] body = new byte[0];
        private byte[] decoded = new byte[0];
        private byte[] section = new byte[0];
        private byte[] privateKey = new byte[0];
        private byte[] seed = new byte[0];
        private byte[] publicKey = new byte[0];

        void clear() {
            for (byte[] array : List.of(file, body, decoded, section, privateKey, seed)) {
                Arrays.fill(array, (byte) 0);
            }
        }
    }
}
