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

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;

/**
 * The time-stamp request and the reading of the reply of
 * <a href="https://www.rfc-editor.org/rfc/rfc3161">RFC 3161</a>.
 * <p>
 * A request ({@code TimeStampReq}, RFC 3161, 2.4.1) is version 1, its {@code messageImprint} SHA-256 over the object
 * id as lowercase ASCII hex, a random 64-bit nonce and {@code certReq = TRUE}, without a policy and without extensions.
 * The algorithm identifier of SHA-256 carries no parameters, as
 * <a href="https://www.rfc-editor.org/rfc/rfc5754#section-2">RFC 5754, 2</a> asks of an encoder.
 * <p>
 * A reply ({@code TimeStampResp}, RFC 3161, 2.4.2) is read for its {@code PKIStatusInfo} and its
 * {@code TimeStampToken}, a CMS {@code ContentInfo} of {@code SignedData}
 * (<a href="https://www.rfc-editor.org/rfc/rfc5652">RFC 5652</a>) encapsulating a {@code TSTInfo}. The token is
 * granted only with the status {@code granted} (0) or {@code grantedWithMods} (1).
 */
final class TimeStampRequests {

    /** The object identifier of SHA-256 (RFC 5754, 2). */
    static final String SHA_256 = "2.16.840.1.101.3.4.2.1";

    /** The object identifier {@code id-signedData} (RFC 5652, 5.1). */
    static final String ID_SIGNED_DATA = "1.2.840.113549.1.7.2";

    /** The object identifier {@code id-ct-TSTInfo} (RFC 3161, 2.4.2). */
    static final String ID_CT_TST_INFO = "1.2.840.113549.1.9.16.1.4";

    /** The status {@code granted} of {@code PKIStatus}. */
    static final int GRANTED = 0;

    /** The status {@code grantedWithMods} of {@code PKIStatus}. */
    static final int GRANTED_WITH_MODS = 1;

    /** The number of bits of the random nonce. */
    static final int NONCE_BITS = 64;

    /** The detail of a reply that cannot be read. */
    static final String REPLY_UNREADABLE = "rfc3161: reply unreadable";

    /** The detail of a reply without a token although its status grants one. */
    static final String NO_TOKEN = "rfc3161: reply without a token";

    /** The prefix of the detail of a status that grants no token; the status number follows. */
    static final String STATUS_PREFIX = "rfc3161: the authority refused with status ";

    private TimeStampRequests() {
        // static helpers only
    }

    /**
     * Computes the message imprint of an object id: SHA-256 over its lowercase ASCII hex.
     *
     * @param subject the object id
     * @return the 32 octets of the digest
     */
    static byte[] imprint(ObjectId subject) {
        return sha256(subject.name().getBytes(US_ASCII));
    }

    /**
     * Computes SHA-256.
     *
     * @param data the octets
     * @return the digest
     */
    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            // every Java SE implementation must support SHA-256 (MessageDigest, "Java Security Standard Algorithm
            // Names"), so this is a broken platform, not an outcome
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Builds the request for an object id.
     *
     * @param subject the object id
     * @param random  the source of the nonce
     * @return the request
     */
    static Request request(ObjectId subject, SecureRandom random) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(random, "random");
        byte[] imprint = imprint(subject);
        var nonce = new BigInteger(NONCE_BITS, random);
        byte[] encoded = Der.sequence(
                Der.integer(1),
                messageImprint(imprint),
                Der.integer(nonce),
                Der.bool(true));
        return new Request(encoded, imprint, nonce);
    }

    /**
     * Encodes a {@code MessageImprint} of SHA-256.
     *
     * @param digest the hashed message
     * @return the encoding
     */
    static byte[] messageImprint(byte[] digest) {
        return Der.sequence(Der.sequence(Der.oid(SHA_256)), Der.octetString(digest));
    }

    /**
     * Reads the token from a reply.
     *
     * @param reply the {@code TimeStampResp}
     * @return {@link StoreOutcome#OK} with the DER encoding of the {@code TimeStampToken}; {@link StoreOutcome#REFUSED}
     *         with {@value #REPLY_UNREADABLE}, {@value #NO_TOKEN} or the status that grants no token
     */
    static StoreResult<byte[]> token(byte[] reply) {
        try {
            List<Der.Element> response = Der.parse(reply.clone()).children(Der.SEQUENCE);
            if (response.isEmpty() || response.size() > 2) {
                return StoreResult.of(StoreOutcome.REFUSED, REPLY_UNREADABLE);
            }
            List<Der.Element> statusInfo = response.getFirst().children(Der.SEQUENCE);
            if (statusInfo.isEmpty()) {
                return StoreResult.of(StoreOutcome.REFUSED, REPLY_UNREADABLE);
            }
            BigInteger status = statusInfo.getFirst().integer();
            if (!BigInteger.valueOf(GRANTED).equals(status) && !BigInteger.valueOf(GRANTED_WITH_MODS).equals(status)) {
                // the status is a small INTEGER of the closed list PKIStatus (RFC 3161, 2.4.2); a larger value is named
                // as it is, it carries no secret
                return StoreResult.of(StoreOutcome.REFUSED, STATUS_PREFIX + status);
            }
            if (response.size() < 2) {
                return StoreResult.of(StoreOutcome.REFUSED, NO_TOKEN);
            }
            byte[] token = response.get(1).expect(Der.SEQUENCE).encoded();
            tstInfo(token);
            return StoreResult.ok(token);
        } catch (Der.DerException e) {
            return StoreResult.of(StoreOutcome.REFUSED, REPLY_UNREADABLE);
        }
    }

    /**
     * Reads the {@code TSTInfo} a token encapsulates, without checking its signature.
     *
     * @param token the DER encoding of the {@code TimeStampToken}
     * @return the {@code TSTInfo}
     * @throws Der.DerException if the token is no {@code SignedData} encapsulating a well-formed {@code TSTInfo}
     */
    static TstInfo tstInfo(byte[] token) throws Der.DerException {
        List<Der.Element> contentInfo = Der.parse(token.clone()).children(Der.SEQUENCE);
        if (contentInfo.size() != 2 || !ID_SIGNED_DATA.equals(contentInfo.getFirst().oid())) {
            throw new Der.DerException("not a signed data content info");
        }
        List<Der.Element> signedData = contentInfo.get(1).expect(Der.contextConstructed(0)).inner()
                .children(Der.SEQUENCE);
        if (signedData.size() < 4) {
            throw new Der.DerException("signed data incomplete");
        }
        List<Der.Element> encapsulated = signedData.get(2).children(Der.SEQUENCE);
        if (encapsulated.size() != 2 || !ID_CT_TST_INFO.equals(encapsulated.getFirst().oid())) {
            throw new Der.DerException("not a TSTInfo");
        }
        byte[] content = encapsulated.get(1).expect(Der.contextConstructed(0)).inner().octets();
        return TstInfo.parse(content);
    }

    /**
     * A built request: its encoding and the two values the reply must repeat.
     */
    static final class Request {

        private final byte[] encoded;
        private final byte[] imprint;
        private final BigInteger nonce;

        private Request(byte[] encoded, byte[] imprint, BigInteger nonce) {
            this.encoded = encoded;
            this.imprint = imprint;
            this.nonce = nonce;
        }

        /** @return a copy of the DER encoding of the {@code TimeStampReq} */
        byte[] encoded() {
            return encoded.clone();
        }

        /** @return a copy of the hashed message of the imprint */
        byte[] imprint() {
            return imprint.clone();
        }

        /** @return the nonce */
        BigInteger nonce() {
            return nonce;
        }

        /**
         * Tells whether a {@code TSTInfo} answers this request: the imprint of SHA-256 over the same octets and the same
         * nonce (RFC 3161, 2.4.2).
         *
         * @param info the {@code TSTInfo} of the reply
         * @return whether the imprint matches
         */
        boolean imprintMatches(TstInfo info) {
            return SHA_256.equals(info.hashAlgorithm()) && Arrays.equals(imprint, info.hashedMessage());
        }

        /**
         * Tells whether a {@code TSTInfo} carries the nonce of this request.
         *
         * @param info the {@code TSTInfo} of the reply
         * @return whether the nonce matches
         */
        boolean nonceMatches(TstInfo info) {
            return nonce.equals(info.nonce());
        }

        /**
         * Renders the request by its nonce only.
         *
         * @return {@code Request[nonce=...]}
         */
        @Override
        public String toString() {
            return "Request[nonce=" + nonce + "]";
        }
    }

    /**
     * The fields of a {@code TSTInfo} the provider checks (RFC 3161, 2.4.2), and its encoding.
     */
    static final class TstInfo {

        private final String hashAlgorithm;
        private final byte[] hashedMessage;
        private final @Nullable BigInteger nonce;
        private final Instant genTime;
        private final byte[] encoded;

        private TstInfo(String hashAlgorithm, byte[] hashedMessage, @Nullable BigInteger nonce, Instant genTime,
                byte[] encoded) {
            this.hashAlgorithm = hashAlgorithm;
            this.hashedMessage = hashedMessage;
            this.nonce = nonce;
            this.genTime = genTime;
            this.encoded = encoded;
        }

        /**
         * Reads a {@code TSTInfo}: version 1, policy, message imprint, serial number, {@code genTime}, then the
         * optional accuracy, ordering, nonce, TSA name and extensions.
         *
         * @param encoded the DER encoding
         * @return the fields
         * @throws Der.DerException if the encoding is no well-formed {@code TSTInfo}
         */
        static TstInfo parse(byte[] encoded) throws Der.DerException {
            List<Der.Element> fields = Der.parse(encoded.clone()).children(Der.SEQUENCE);
            if (fields.size() < 5 || !BigInteger.ONE.equals(fields.getFirst().integer())) {
                throw new Der.DerException("TSTInfo of an unknown version");
            }
            fields.get(1).oid();
            List<Der.Element> imprint = fields.get(2).children(Der.SEQUENCE);
            if (imprint.size() != 2) {
                throw new Der.DerException("message imprint malformed");
            }
            String algorithm = algorithm(imprint.getFirst());
            byte[] hashed = imprint.get(1).octets();
            fields.get(3).integer();
            Instant genTime = fields.get(4).generalizedTime();
            BigInteger nonce = null;
            for (Der.Element field : fields.subList(5, fields.size())) {
                if (field.tag() == Der.INTEGER) {
                    if (nonce != null) {
                        throw new Der.DerException("two nonces");
                    }
                    nonce = field.integer();
                }
            }
            return new TstInfo(algorithm, hashed, nonce, genTime, encoded.clone());
        }

        /**
         * Reads an {@code AlgorithmIdentifier} whose parameters are absent or NULL.
         *
         * @param identifier the element
         * @return the dotted object identifier of the algorithm
         * @throws Der.DerException if the element is no such identifier
         */
        static String algorithm(Der.Element identifier) throws Der.DerException {
            List<Der.Element> parts = identifier.children(Der.SEQUENCE);
            if (parts.isEmpty() || parts.size() > 2) {
                throw new Der.DerException("algorithm identifier malformed");
            }
            if (parts.size() == 2 && parts.get(1).tag() != Der.NULL) {
                throw new Der.DerException("algorithm parameters not supported");
            }
            return parts.getFirst().oid();
        }

        /** @return the dotted object identifier of the hash algorithm of the imprint */
        String hashAlgorithm() {
            return hashAlgorithm;
        }

        /** @return a copy of the hashed message of the imprint */
        byte[] hashedMessage() {
            return hashedMessage.clone();
        }

        /** @return the nonce, or {@code null} when the token carries none */
        @Nullable
        BigInteger nonce() {
            return nonce;
        }

        /** @return the time the token states */
        Instant genTime() {
            return genTime;
        }

        /** @return a copy of the DER encoding of the {@code TSTInfo} */
        byte[] encoded() {
            return encoded.clone();
        }

        /**
         * Renders the fields that carry no secret.
         *
         * @return {@code TstInfo[hashAlgorithm=..., genTime=...]}
         */
        @Override
        public String toString() {
            return "TstInfo[hashAlgorithm=" + hashAlgorithm + ", genTime=" + genTime + "]";
        }
    }
}
