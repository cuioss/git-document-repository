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

    /** The status {@code rejection} of {@code PKIStatus}, the lowest that grants no token. */
    static final int REJECTION = 2;

    /** The status {@code revocationNotification} of {@code PKIStatus}, the highest defined. */
    static final int REVOCATION_NOTIFICATION = 5;

    /** The number of bits of the random nonce. */
    static final int NONCE_BITS = 64;

    /** The detail of a reply that cannot be read. */
    static final String REPLY_UNREADABLE = "rfc3161: reply unreadable";

    /** The detail of a reply without a token although its status grants one. */
    static final String NO_TOKEN = "rfc3161: reply without a token";

    /** The prefix of the detail of a defined status that grants no token; the status number, 2 to 5, follows. */
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
     *         with {@value #REPLY_UNREADABLE}, {@value #NO_TOKEN} or one of the defined statuses that grant no token
     */
    static StoreResult<byte[]> token(byte[] reply) {
        try {
            List<Der.Element> response = Der.parse(reply.clone()).children(Der.TAG_SEQUENCE);
            if (response.isEmpty() || response.size() > 2) {
                return StoreResult.of(StoreOutcome.REFUSED, REPLY_UNREADABLE);
            }
            BigInteger status = status(response.getFirst());
            if (!BigInteger.valueOf(GRANTED).equals(status) && !BigInteger.valueOf(GRANTED_WITH_MODS).equals(status)) {
                // RFC 3161, 2.4.2: PKIStatus is the closed list 0 to 5, and a client MUST generate an error for a value
                // it does not understand; only a defined value is named, any other is an unreadable reply
                return StoreResult.of(StoreOutcome.REFUSED, isDefinedRefusal(status) ? STATUS_PREFIX + status
                        : REPLY_UNREADABLE);
            }
            if (response.size() < 2) {
                return StoreResult.of(StoreOutcome.REFUSED, NO_TOKEN);
            }
            byte[] token = response.get(1).expect(Der.TAG_SEQUENCE).encoded();
            tstInfo(token);
            return StoreResult.ok(token);
        } catch (Der.DerException e) {
            return StoreResult.of(StoreOutcome.REFUSED, REPLY_UNREADABLE);
        }
    }

    /**
     * Reads a {@code PKIStatusInfo ::= SEQUENCE { status PKIStatus, statusString PKIFreeText OPTIONAL, failInfo
     * PKIFailureInfo OPTIONAL }} (RFC 3161, 2.4.2), with {@code PKIFreeText ::= SEQUENCE SIZE (1..MAX) OF UTF8String}
     * (RFC 4210, 5.1.1) and {@code PKIFailureInfo} a BIT STRING: the optional fields in that order, each at most once.
     *
     * @param statusInfo the element
     * @return the status
     * @throws Der.DerException if the element is no such {@code PKIStatusInfo}
     */
    private static BigInteger status(Der.Element statusInfo) throws Der.DerException {
        List<Der.Element> fields = statusInfo.children(Der.TAG_SEQUENCE);
        if (fields.isEmpty() || fields.size() > 3) {
            throw new Der.DerException("status info malformed");
        }
        BigInteger status = fields.getFirst().integer();
        int index = 1;
        if (index < fields.size() && fields.get(index).tag() == Der.TAG_SEQUENCE) {
            List<Der.Element> texts = fields.get(index).children();
            if (texts.isEmpty()) {
                throw new Der.DerException("status string empty");
            }
            for (Der.Element text : texts) {
                text.expect(Der.UTF8_STRING);
            }
            index++;
        }
        if (index < fields.size() && fields.get(index).tag() == Der.BIT_STRING) {
            fields.get(index).checkBitString();
            index++;
        }
        if (index != fields.size()) {
            throw new Der.DerException("status info with an unknown, duplicate or misplaced field");
        }
        return status;
    }

    /**
     * Tells whether a status is a defined {@code PKIStatus} that grants no token: {@code rejection} (2),
     * {@code waiting} (3), {@code revocationWarning} (4) or {@code revocationNotification} (5) (RFC 3161, 2.4.2).
     *
     * @param status the status
     * @return whether it is one of the four
     */
    private static boolean isDefinedRefusal(BigInteger status) {
        return status.compareTo(BigInteger.valueOf(REJECTION)) >= 0
                && status.compareTo(BigInteger.valueOf(REVOCATION_NOTIFICATION)) <= 0;
    }

    /**
     * Reads the {@code TSTInfo} a token encapsulates, without checking its signature.
     *
     * @param token the DER encoding of the {@code TimeStampToken}
     * @return the {@code TSTInfo}
     * @throws Der.DerException if the token is no {@code SignedData} encapsulating a well-formed {@code TSTInfo}
     */
    static TstInfo tstInfo(byte[] token) throws Der.DerException {
        return TstInfo.parse(tstContent(signedData(token)));
    }

    /**
     * Reads the fields of the {@code SignedData} a token wraps (RFC 5652, 3 and 5.1).
     *
     * @param token the DER encoding of the {@code TimeStampToken}
     * @return the fields of the {@code SignedData}, at least four
     * @throws Der.DerException if the token is no {@code ContentInfo} of {@code id-signedData}
     */
    static List<Der.Element> signedData(byte[] token) throws Der.DerException {
        List<Der.Element> contentInfo = Der.parse(token.clone()).children(Der.TAG_SEQUENCE);
        if (contentInfo.size() != 2 || !ID_SIGNED_DATA.equals(contentInfo.getFirst().oid())) {
            throw new Der.DerException("not a signed data content info");
        }
        List<Der.Element> signedData = contentInfo.get(1).expect(Der.contextConstructed(0)).inner()
                .children(Der.TAG_SEQUENCE);
        if (signedData.size() < 4) {
            throw new Der.DerException("signed data incomplete");
        }
        return signedData;
    }

    /**
     * Reads the content octets of the {@code TSTInfo} a {@code SignedData} encapsulates (RFC 5652, 5.2; RFC 3161,
     * 2.4.2).
     *
     * @param signedData the fields of the {@code SignedData}, as {@link #signedData(byte[])} returns them
     * @return the DER encoding of the {@code TSTInfo}
     * @throws Der.DerException if the encapsulated content is not a {@code TSTInfo}
     */
    static byte[] tstContent(List<Der.Element> signedData) throws Der.DerException {
        List<Der.Element> encapsulated = signedData.get(2).children(Der.TAG_SEQUENCE);
        if (encapsulated.size() != 2 || !ID_CT_TST_INFO.equals(encapsulated.getFirst().oid())) {
            throw new Der.DerException("not a TSTInfo");
        }
        return encapsulated.get(1).expect(Der.contextConstructed(0)).inner().octets();
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
     * The fields of a {@code TSTInfo} the provider checks (RFC 3161, 2.4.2).
     */
    static final class TstInfo {

        private final String hashAlgorithm;
        private final byte[] hashedMessage;
        private final @Nullable BigInteger nonce;
        private final Instant genTime;

        /** The rank of {@code accuracy} among the optional fields. */
        private static final int ACCURACY = 0;
        /** The rank of {@code ordering}. */
        private static final int ORDERING = 1;
        /** The rank of {@code nonce}. */
        private static final int NONCE = 2;
        /** The rank of {@code tsa}. */
        private static final int TSA = 3;

        /** The tags of the optional fields after {@code genTime}, in the order of RFC 3161, 2.4.2. */
        private static final List<Integer> OPTIONAL_TAGS = List.of(Der.TAG_SEQUENCE, Der.BOOLEAN, Der.TAG_INTEGER,
                Der.contextConstructed(0), Der.contextConstructed(1));

        /** The tags of the fields of {@code Accuracy}: {@code seconds}, {@code millis [0]}, {@code micros [1]}. */
        private static final List<Integer> ACCURACY_TAGS = List.of(Der.TAG_INTEGER, Der.contextPrimitive(0),
                Der.contextPrimitive(1));

        /** The largest value of {@code millis} and {@code micros}. */
        private static final BigInteger MAX_SUBSECOND = BigInteger.valueOf(999);

        private TstInfo(String hashAlgorithm, byte[] hashedMessage, @Nullable BigInteger nonce, Instant genTime) {
            this.hashAlgorithm = hashAlgorithm;
            this.hashedMessage = hashedMessage;
            this.nonce = nonce;
            this.genTime = genTime;
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
            List<Der.Element> fields = Der.parse(encoded.clone()).children(Der.TAG_SEQUENCE);
            if (fields.size() < 5 || !BigInteger.ONE.equals(fields.getFirst().integer())) {
                throw new Der.DerException("TSTInfo of an unknown version");
            }
            fields.get(1).oid();
            List<Der.Element> imprint = fields.get(2).children(Der.TAG_SEQUENCE);
            if (imprint.size() != 2) {
                throw new Der.DerException("message imprint malformed");
            }
            String algorithm = algorithm(imprint.getFirst());
            byte[] hashed = imprint.get(1).octets();
            fields.get(3).integer();
            Instant genTime = fields.get(4).generalizedTime();
            BigInteger nonce = optionalFields(fields.subList(5, fields.size()));
            return new TstInfo(algorithm, hashed, nonce, genTime);
        }

        /**
         * Reads the optional fields of a {@code TSTInfo} after {@code genTime} in the order of RFC 3161, 2.4.2:
         * {@code accuracy Accuracy}, {@code ordering BOOLEAN}, {@code nonce INTEGER}, {@code tsa [0] GeneralName} and
         * {@code extensions [1] IMPLICIT Extensions}, each at most once. The tag of each field is distinct, so a field
         * is placed by its tag; a tag that is not one of the five, or that does not come after the one before, is
         * refused.
         *
         * @param optional the fields after {@code genTime}
         * @return the nonce, or {@code null} when the token carries none
         * @throws Der.DerException if a field is unknown, duplicate, out of order or malformed
         */
        private static @Nullable BigInteger optionalFields(List<Der.Element> optional) throws Der.DerException {
            BigInteger nonce = null;
            int next = 0;
            for (Der.Element field : optional) {
                int rank = OPTIONAL_TAGS.indexOf(field.tag());
                if (rank < next) {
                    // an unknown tag yields -1, a duplicate or misplaced one a rank already passed
                    throw new Der.DerException("TSTInfo with an unknown, duplicate or misplaced field");
                }
                next = rank + 1;
                switch (rank) {
                    case ACCURACY -> accuracy(field);
                    case ORDERING -> field.bool();
                    case NONCE -> nonce = field.integer();
                    case TSA -> field.inner();
                    default -> extensions(field);
                }
            }
            return nonce;
        }

        /**
         * Checks {@code Accuracy ::= SEQUENCE { seconds INTEGER OPTIONAL, millis [0] INTEGER (1..999) OPTIONAL,
         * micros [1] INTEGER (1..999) OPTIONAL }} under the implicit tags of the module (RFC 3161, 2.4.2 and
         * appendix C).
         */
        private static void accuracy(Der.Element accuracy) throws Der.DerException {
            int next = 0;
            for (Der.Element part : accuracy.children(Der.TAG_SEQUENCE)) {
                int rank = ACCURACY_TAGS.indexOf(part.tag());
                if (rank < next) {
                    throw new Der.DerException("accuracy with an unknown, duplicate or misplaced field");
                }
                next = rank + 1;
                BigInteger value = Der.parse(Der.tlv(Der.TAG_INTEGER, part.content())).integer();
                boolean valid = rank == 0 ? value.signum() >= 0
                        : value.compareTo(BigInteger.ONE) >= 0 && value.compareTo(MAX_SUBSECOND) <= 0;
                if (!valid) {
                    throw new Der.DerException("accuracy out of range");
                }
            }
        }

        /**
         * Checks {@code extensions [1] IMPLICIT Extensions}, {@code Extensions ::= SEQUENCE SIZE (1..MAX) OF Extension}
         * (RFC 5280, 4.1); the extensions themselves are not interpreted.
         */
        private static void extensions(Der.Element extensions) throws Der.DerException {
            List<Der.Element> list = extensions.children();
            if (list.isEmpty()) {
                throw new Der.DerException("extensions empty");
            }
            for (Der.Element extension : list) {
                extension.expect(Der.TAG_SEQUENCE);
            }
        }

        /**
         * Reads an {@code AlgorithmIdentifier} whose parameters are absent or NULL; a NULL has no content octets
         * (X.690, 8.8.2).
         *
         * @param identifier the element
         * @return the dotted object identifier of the algorithm
         * @throws Der.DerException if the element is no such identifier
         */
        static String algorithm(Der.Element identifier) throws Der.DerException {
            List<Der.Element> parts = identifier.children(Der.TAG_SEQUENCE);
            if (parts.isEmpty() || parts.size() > 2) {
                throw new Der.DerException("algorithm identifier malformed");
            }
            if (parts.size() == 2 && (parts.get(1).tag() != Der.NULL || parts.get(1).content().length != 0)) {
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
