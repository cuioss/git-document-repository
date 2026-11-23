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
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;


import org.jspecify.annotations.Nullable;

/**
 * The codec of the SSH signature format of the store (GDR-STORE-12): the signed blob, the signature blob with its
 * armor, the parse of an OpenSSH public key line, and the verification of an Ed25519 signature with the JDK provider.
 * <p>
 * The format is the one of
 * <a href="https://github.com/openssh/openssh-portable/blob/master/PROTOCOL.sshsig">PROTOCOL.sshsig</a>, the format
 * {@code ssh-keygen -Y sign} writes and Git stores as the {@code gpgsig} header of a commit signed with
 * {@code gpg.format=ssh}. The store signs with the hash algorithm {@value #HASH_SHA512} and only with keys of the type
 * {@value #KEY_TYPE}; verification also accepts {@value #HASH_SHA256}, the second hash algorithm the format allows.
 * <p>
 * Every method is pure byte handling: no I/O, and no byte of a key, a message or a signature is logged. Malformed input
 * is reported as a value ({@link Decoding.Malformed}, {@link KeyLine.Refused}, {@code false}), never as an exception.
 */
final class SshSignatures {

    /** The namespace Git signs commits and tags in. */
    static final String NAMESPACE_GIT = "git";

    /** The key type the store signs and verifies with: Ed25519 in the SSH wire format. */
    static final String KEY_TYPE = "ssh-ed25519";

    /** The hash algorithm the store signs with. */
    static final String HASH_SHA512 = "sha512";

    /** The second hash algorithm the format allows; accepted by verification only. */
    static final String HASH_SHA256 = "sha256";

    /** The first line of an armored signature. */
    static final String BEGIN = "-----BEGIN SSH SIGNATURE-----";

    /** The last line of an armored signature. */
    static final String END = "-----END SSH SIGNATURE-----";

    /** The length of a base64 line of the armor, as {@code ssh-keygen} writes it. */
    static final int LINE_LENGTH = 70;

    /** The reason of a public key line that is not a valid Ed25519 key. */
    static final String KEY_MALFORMED = "key malformed";

    /** The only version of the signature blob. */
    private static final int VERSION = 1;

    /** The magic preamble of the signed blob and of the signature blob; not length-prefixed. */
    private static final byte[] MAGIC = "SSHSIG".getBytes(US_ASCII);

    /** The length of an Ed25519 public key. */
    private static final int KEY_LENGTH = 32;

    /** The length of an Ed25519 signature. */
    private static final int SIGNATURE_LENGTH = 64;

    /**
     * The DER prefix of the X.509 SubjectPublicKeyInfo of an Ed25519 key (RFC 8410, section 4), followed by the 32 key
     * bytes.
     */
    private static final byte[] SPKI_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private SshSignatures() {
    }

    /**
     * Builds the blob a signature of the format covers, hashing the message with {@value #HASH_SHA512}:
     * {@code "SSHSIG" || string(namespace) || string("") || string("sha512") || string(SHA-512(message))}.
     *
     * @param namespace the namespace, {@value #NAMESPACE_GIT} for commits
     * @param message   the signed message, the raw commit without its {@code gpgsig} header
     * @return the blob to hand to the signing key source
     */
    static byte[] signedData(String namespace, byte[] message) {
        return signedData(namespace, HASH_SHA512, message);
    }

    private static byte[] signedData(String namespace, String hashAlgorithm, byte[] message) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        writeString(out, namespace.getBytes(UTF_8));
        writeString(out, new byte[0]);
        writeString(out, hashAlgorithm.getBytes(US_ASCII));
        writeString(out, digest(hashAlgorithm, message));
        return out.toByteArray();
    }

    /**
     * Builds the SSH wire form of an Ed25519 public key: {@code string("ssh-ed25519") || string(key)}.
     *
     * @param key the 32 bytes of the key
     * @return the public key blob
     */
    static byte[] publicKeyBlob(byte[] key) {
        var out = new ByteArrayOutputStream();
        writeString(out, KEY_TYPE.getBytes(US_ASCII));
        writeString(out, key);
        return out.toByteArray();
    }

    /**
     * Encodes and armors a signature: the blob
     * {@code "SSHSIG" || uint32(1) || string(publickey) || string(namespace) || string("") || string("sha512")
     * || string(string("ssh-ed25519") || string(signature))}, in base64 lines of {@value #LINE_LENGTH} characters
     * between {@value #BEGIN} and {@value #END}.
     *
     * @param publicKeyBlob the public key blob of the signer, see {@link #publicKeyBlob(byte[])}
     * @param namespace     the namespace the signed blob was built with
     * @param rawSignature  the raw Ed25519 signature of the signed blob
     * @return the armored signature, ending with a line break
     */
    static String encode(byte[] publicKeyBlob, String namespace, byte[] rawSignature) {
        var signature = new ByteArrayOutputStream();
        writeString(signature, KEY_TYPE.getBytes(US_ASCII));
        writeString(signature, rawSignature);

        var blob = new ByteArrayOutputStream();
        blob.writeBytes(MAGIC);
        blob.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(VERSION).array());
        writeString(blob, publicKeyBlob);
        writeString(blob, namespace.getBytes(UTF_8));
        writeString(blob, new byte[0]);
        writeString(blob, HASH_SHA512.getBytes(US_ASCII));
        writeString(blob, signature.toByteArray());

        String base64 = Base64.getEncoder().encodeToString(blob.toByteArray());
        var armored = new StringBuilder(BEGIN).append('\n');
        for (int start = 0; start < base64.length(); start += LINE_LENGTH) {
            armored.append(base64, start, Math.min(base64.length(), start + LINE_LENGTH)).append('\n');
        }
        return armored.append(END).append('\n').toString();
    }

    /**
     * Parses an OpenSSH public key line {@code ssh-ed25519 <base64> [comment]}.
     *
     * @param line the public key line, as a signing key source hands it out
     * @return the 32 key bytes, or a refusal for another key type or a malformed line; the reason never contains a
     * part of the line
     */
    static KeyLine parsePublicKeyLine(String line) {
        String[] parts = line.strip().split("\\s+", 3);
        if (parts.length < 2) {
            return new KeyLine.Refused(KEY_MALFORMED);
        }
        if (!KEY_TYPE.equals(parts[0])) {
            return new KeyLine.Refused("key type not supported");
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            return new KeyLine.Refused(KEY_MALFORMED);
        }
        return ed25519Key(blob)
                .<KeyLine>map(AcceptedKey::new)
                .orElseGet(() -> new KeyLine.Refused(KEY_MALFORMED));
    }

    /**
     * Extracts the key of an Ed25519 public key blob.
     *
     * @param blob the public key blob
     * @return the 32 key bytes, or empty when the blob is not exactly an Ed25519 public key
     */
    static Optional<byte[]> ed25519Key(byte[] blob) {
        var reader = new Reader(blob);
        byte[] type = reader.string();
        byte[] key = reader.string();
        if (type == null || key == null || !reader.atEnd() || !Arrays.equals(type, KEY_TYPE.getBytes(US_ASCII))
                || key.length != KEY_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(key);
    }

    /**
     * Parses an armored signature back into its fields.
     *
     * @param armored the armored signature, as stored in the {@code gpgsig} header; lines may carry surrounding
     *                whitespace, such as the indentation of a commit header, but {@value #BEGIN} and {@value #END}
     *                must each stand on a line of their own, as OpenSSH requires
     * @return the decoded signature, or {@link Decoding.Malformed} naming the reason, never an exception
     */
    static Decoding decode(String armored) {
        String[] lines = armored.strip().split("\\R");
        if (lines.length < 2 || !BEGIN.equals(lines[0].strip()) || !END.equals(lines[lines.length - 1].strip())) {
            return new Decoding.Malformed("armor missing");
        }
        var body = new StringBuilder();
        for (int index = 1; index < lines.length - 1; index++) {
            body.append(lines[index].strip());
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(body.toString());
        } catch (IllegalArgumentException e) {
            return new Decoding.Malformed("base64 malformed");
        }
        var reader = new Reader(blob);
        if (!reader.expect(MAGIC)) {
            return new Decoding.Malformed("preamble missing");
        }
        Integer version = reader.uint32();
        byte[] publicKey = reader.string();
        byte[] namespace = reader.string();
        byte[] reserved = reader.string();
        byte[] hashAlgorithm = reader.string();
        byte[] signatureBlob = reader.string();
        if (version == null || publicKey == null || namespace == null || reserved == null || hashAlgorithm == null
                || signatureBlob == null || !reader.atEnd()) {
            return new Decoding.Malformed("blob truncated");
        }
        if (version != VERSION) {
            return new Decoding.Malformed("version not supported");
        }
        var signatureReader = new Reader(signatureBlob);
        byte[] signatureType = signatureReader.string();
        byte[] signature = signatureReader.string();
        if (signatureType == null || signature == null || !signatureReader.atEnd()) {
            return new Decoding.Malformed("signature truncated");
        }
        return new DecodedSignature(publicKey, new String(namespace, UTF_8), new String(hashAlgorithm, US_ASCII),
                new String(signatureType, US_ASCII), signature);
    }

    /**
     * Verifies a decoded signature over a message: the namespace must match, the hash algorithm be
     * {@value #HASH_SHA512} or {@value #HASH_SHA256}, key and signature be Ed25519, and the signature verify with the
     * key it embeds.
     * <p>
     * Whether that key is one the verifier trusts is not checked here; that is the caller's decision.
     *
     * @param decoded   the decoded signature
     * @param namespace the namespace the signature must have been made in
     * @param message   the signed message
     * @return whether the signature is valid
     */
    static boolean verify(DecodedSignature decoded, String namespace, byte[] message) {
        if (!namespace.equals(decoded.namespace()) || !KEY_TYPE.equals(decoded.signatureType())
                || !(HASH_SHA512.equals(decoded.hashAlgorithm()) || HASH_SHA256.equals(decoded.hashAlgorithm()))) {
            return false;
        }
        Optional<byte[]> key = ed25519Key(decoded.publicKeyBlob());
        byte[] signature = decoded.signature();
        if (key.isEmpty() || signature.length != SIGNATURE_LENGTH) {
            return false;
        }
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey(key.get()));
            verifier.update(signedData(namespace, decoded.hashAlgorithm(), message));
            return verifier.verify(signature);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform from Java 15 on provides Ed25519", e);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static PublicKey publicKey(byte[] key) throws GeneralSecurityException {
        byte[] encoded = Arrays.copyOf(SPKI_PREFIX, SPKI_PREFIX.length + key.length);
        System.arraycopy(key, 0, encoded, SPKI_PREFIX.length, key.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static byte[] digest(String hashAlgorithm, byte[] message) {
        String jdkName = HASH_SHA256.equals(hashAlgorithm) ? "SHA-256" : "SHA-512";
        try {
            return MessageDigest.getInstance(jdkName).digest(message);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides " + jdkName, e);
        }
    }

    private static void writeString(ByteArrayOutputStream out, byte[] value) {
        out.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        out.writeBytes(value);
    }

    /**
     * The result of parsing a public key line: an {@link AcceptedKey} or a {@link KeyLine.Refused}.
     */
    sealed interface KeyLine permits AcceptedKey, KeyLine.Refused {

        /**
         * A refused public key line.
         *
         * @param reason why the line was refused, never a part of it
         */
        record Refused(String reason) implements KeyLine {
        }
    }

    /**
     * An accepted Ed25519 public key line. The key is copied on the way in and on the way out, and equality compares
     * its bytes.
     *
     * @param key the 32 bytes of the key
     */
    record AcceptedKey(byte[] key) implements KeyLine {

        AcceptedKey {
            key = key.clone();
        }

        /**
         * Returns the key.
         *
         * @return a copy of the 32 key bytes
         */
        @Override
        public byte[] key() {
            return key.clone();
        }

        /**
         * Returns the public key blob of the key.
         *
         * @return the blob, see {@link SshSignatures#publicKeyBlob(byte[])}
         */
        byte[] blob() {
            return publicKeyBlob(key);
        }

        /**
         * Compares the key bytes.
         *
         * @param other the object to compare with
         * @return whether {@code other} is an accepted key with the same bytes
         */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof AcceptedKey(var otherKey) && Arrays.equals(key, otherKey);
        }

        /**
         * Hashes the key bytes.
         *
         * @return the hash of the content of the key
         */
        @Override
        public int hashCode() {
            return Arrays.hashCode(key);
        }

        /**
         * Names the key type only, never a byte of the key.
         *
         * @return {@code AcceptedKey[ssh-ed25519]}
         */
        @Override
        public String toString() {
            return "AcceptedKey[" + KEY_TYPE + "]";
        }
    }

    /**
     * The result of decoding an armored signature: a {@link DecodedSignature} or a {@link Decoding.Malformed}.
     */
    sealed interface Decoding permits DecodedSignature, Decoding.Malformed {

        /**
         * A signature that could not be decoded.
         *
         * @param reason why, never a part of the input
         */
        record Malformed(String reason) implements Decoding {
        }
    }

    /**
     * A decoded signature. The byte arrays are copied on the way in and on the way out, and equality compares their
     * content.
     *
     * @param publicKeyBlob the public key blob the signature embeds
     * @param namespace     the namespace the signature was made in
     * @param hashAlgorithm the hash algorithm of the signed blob
     * @param signatureType the type of the signature
     * @param signature     the raw signature bytes
     */
    record DecodedSignature(byte[] publicKeyBlob, String namespace, String hashAlgorithm, String signatureType,
    byte[] signature) implements Decoding {

        DecodedSignature {
            publicKeyBlob = publicKeyBlob.clone();
            signature = signature.clone();
        }

        /**
         * Returns the public key blob the signature embeds.
         *
         * @return a copy of the blob
         */
        @Override
        public byte[] publicKeyBlob() {
            return publicKeyBlob.clone();
        }

        /**
         * Returns the raw signature.
         *
         * @return a copy of the signature bytes
         */
        @Override
        public byte[] signature() {
            return signature.clone();
        }

        /**
         * Compares every component, the byte arrays by content.
         *
         * @param other the object to compare with
         * @return whether {@code other} is a decoded signature with equal components
         */
        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof DecodedSignature(var otherBlob, var otherNamespace, var otherHash,
                    var otherType, var otherSignature)
                    && Arrays.equals(publicKeyBlob, otherBlob) && namespace.equals(otherNamespace)
                    && hashAlgorithm.equals(otherHash) && signatureType.equals(otherType)
                    && Arrays.equals(signature, otherSignature);
        }

        /**
         * Hashes every component, the byte arrays by content.
         *
         * @return the hash
         */
        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(publicKeyBlob), namespace, hashAlgorithm, signatureType,
                    Arrays.hashCode(signature));
        }

        /**
         * Names namespace, hash algorithm and signature type, never a byte of key or signature.
         *
         * @return {@code DecodedSignature[namespace=..., hash=..., type=...]}
         */
        @Override
        public String toString() {
            return "DecodedSignature[namespace=" + namespace + ", hash=" + hashAlgorithm + ", type=" + signatureType
                    + "]";
        }
    }

    /**
     * A bounds-checked reader of the SSH wire format (RFC 4251, section 5); every read past the end yields
     * {@code null} or {@code false}, never an exception.
     */
    static final class Reader {

        private final ByteBuffer buffer;

        /**
         * Creates a reader over bytes, without copying them.
         *
         * @param data the bytes to read
         */
        Reader(byte[] data) {
            this.buffer = ByteBuffer.wrap(data);
        }

        /**
         * Reads a fixed, not length-prefixed preamble.
         *
         * @param preamble the expected bytes
         * @return whether the next bytes equal the preamble; they are consumed either way
         */
        boolean expect(byte[] preamble) {
            if (buffer.remaining() < preamble.length) {
                return false;
            }
            byte[] read = new byte[preamble.length];
            buffer.get(read);
            return Arrays.equals(read, preamble);
        }

        /**
         * Reads a {@code uint32}.
         *
         * @return the value, or {@code null} past the end
         */
        @Nullable
        Integer uint32() {
            if (buffer.remaining() < Integer.BYTES) {
                return null;
            }
            return buffer.getInt();
        }

        /**
         * Reads a {@code string}: a {@code uint32} length followed by that many bytes.
         *
         * @return the bytes, or {@code null} when the length or the bytes run past the end
         */
        byte @Nullable [] string() {
            Integer length = uint32();
            if (length == null || length < 0 || length > buffer.remaining()) {
                return null;
            }
            byte[] value = new byte[length];
            buffer.get(value);
            return value;
        }

        /**
         * Reads every remaining byte.
         *
         * @return the remaining bytes, possibly none
         */
        byte[] rest() {
            byte[] value = new byte[buffer.remaining()];
            buffer.get(value);
            return value;
        }

        /**
         * Tells whether every byte was read.
         *
         * @return whether nothing remains
         */
        boolean atEnd() {
            return !buffer.hasRemaining();
        }
    }
}
