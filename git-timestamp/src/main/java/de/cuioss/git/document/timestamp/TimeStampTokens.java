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

import java.io.ByteArrayInputStream;
import java.io.Serial;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The verification of a DER {@code TimeStampToken} of <a href="https://www.rfc-editor.org/rfc/rfc3161">RFC 3161</a>
 * against the trusted certificates of {@code timestamp.trust}, on {@code java.security} alone.
 * <p>
 * A token is accepted only when every rule below holds; each names the section it follows:
 * <ol>
 * <li>The token is a CMS {@code ContentInfo} of {@code id-signedData} whose {@code SignedData} encapsulates content of
 * the type {@code id-ct-TSTInfo} (RFC 3161, 2.4.2; <a href="https://www.rfc-editor.org/rfc/rfc5652">RFC 5652</a>, 3 and
 * 5.1).</li>
 * <li>The {@code SignedData} carries exactly one {@code SignerInfo} (RFC 3161, 2.4.2: "there MUST be only one
 * signer").</li>
 * <li>The {@code SignerInfo} has signed attributes (RFC 5652, 5.3: required when the content type is not
 * {@code id-data}) with exactly one {@code contentType} attribute of the single value {@code id-ct-TSTInfo} (RFC 5652,
 * 11.1) and exactly one {@code messageDigest} attribute of the single value equal to the digest of the encapsulated
 * {@code TSTInfo} under the {@code digestAlgorithm} of the {@code SignerInfo} (RFC 5652, 5.4 and 11.2).</li>
 * <li>The signed attributes carry exactly one ESS {@code signingCertificate} (RFC 2634, 5.4, its certificate hash
 * SHA-1) or {@code signingCertificateV2} (<a href="https://www.rfc-editor.org/rfc/rfc5035">RFC 5035</a>, 3, its hash
 * algorithm SHA-256 by default), as RFC 3161, 2.4.2 and <a href="https://www.rfc-editor.org/rfc/rfc5816">RFC 5816</a>,
 * 2.2 require; its first certificate identifier names the signer certificate by hash (RFC 2634, 5.4; RFC 5035, 3). The
 * signer certificate is looked up by that hash among the certificates the token carries and those of
 * {@code timestamp.trust}, and must also match the {@code sid} of the {@code SignerInfo} (RFC 5652, 5.3).</li>
 * <li>The signature verifies with {@link Signature} over the DER of the signed attributes under the explicit SET OF tag
 * (RFC 5652, 5.4), with ECDSA or RSA PKCS #1 v1.5 and SHA-256, SHA-384 or SHA-512
 * (<a href="https://www.rfc-editor.org/rfc/rfc5754">RFC 5754</a>, 2, 3.2 and 3.3); any other algorithm is
 * refused.</li>
 * <li>The signer certificate carries the extended key usage extension, critical, with the single purpose
 * {@code id-kp-timeStamping} (RFC 3161, 2.3).</li>
 * <li>The signer certificate is one of {@code timestamp.trust} and valid at the time the token states, or has a
 * certification path to a certificate of {@code timestamp.trust} that {@link CertPathValidator} of the type PKIX
 * accepts at that time (<a href="https://www.rfc-editor.org/rfc/rfc5280#section-6">RFC 5280, 6</a>). The path is
 * formed from the certificates the token carries. Revocation checking is off: the module reaches no network for a
 * verification, and a revocation status of the time of stamping is not part of the token.</li>
 * </ol>
 * A token that cannot be read, or that breaks any rule, is {@link StoreOutcome#SIGNATURE_INVALID} with a fixed detail
 * that names the rule, never a value of the token. The imprint is not compared here: the provider compares it with the
 * subject.
 */
final class TimeStampTokens {

    /** The detail of a token that cannot be read. */
    static final String UNREADABLE = "rfc3161: the token cannot be read";

    /** The detail of a token with another number of signers than one. */
    static final String NOT_ONE_SIGNER = "rfc3161: the token does not have exactly one signer";

    /** The detail of signed attributes that do not match the content. */
    static final String ATTRIBUTES_MISMATCH = "rfc3161: the token's signed attributes do not match its content";

    /** The detail of a token whose signer certificate cannot be found. */
    static final String SIGNER_UNKNOWN = "rfc3161: the token's signer certificate is not known";

    /** The detail of an algorithm this verifier does not support. */
    static final String ALGORITHM_UNSUPPORTED = "rfc3161: the token uses an unsupported algorithm";

    /** The detail of a signature that does not verify. */
    static final String SIGNATURE_MISMATCH = "rfc3161: the token's signature does not verify";

    /** The detail of a signer certificate without the timestamping purpose. */
    static final String NOT_TIMESTAMPING = "rfc3161: the signer certificate is not a timestamping certificate";

    /** The detail of a signer certificate without a valid path to {@code timestamp.trust}. */
    static final String UNTRUSTED = "rfc3161: the signer certificate has no valid path to timestamp.trust";

    /** The object identifier of the attribute {@code contentType} (RFC 5652, 11.1). */
    static final String CONTENT_TYPE = "1.2.840.113549.1.9.3";

    /** The object identifier of the attribute {@code messageDigest} (RFC 5652, 11.2). */
    static final String MESSAGE_DIGEST = "1.2.840.113549.1.9.4";

    /** The object identifier of the attribute {@code signingCertificate} (RFC 2634, 5.4). */
    static final String SIGNING_CERTIFICATE = "1.2.840.113549.1.9.16.2.12";

    /** The object identifier of the attribute {@code signingCertificateV2} (RFC 5035, 3). */
    static final String SIGNING_CERTIFICATE_V2 = "1.2.840.113549.1.9.16.2.47";

    /** The object identifier of {@code id-kp-timeStamping} (RFC 5280, 4.2.1.12). */
    static final String ID_KP_TIME_STAMPING = "1.3.6.1.5.5.7.3.8";

    /** The object identifier of the extended key usage extension (RFC 5280, 4.2.1.12). */
    static final String EXTENDED_KEY_USAGE = "2.5.29.37";

    /** The object identifier of the subject key identifier extension (RFC 5280, 4.2.1.2). */
    static final String SUBJECT_KEY_IDENTIFIER = "2.5.29.14";

    /** The object identifier of {@code rsaEncryption} (RFC 5754, 3.2). */
    static final String RSA_ENCRYPTION = "1.2.840.113549.1.1.1";

    /** The JCA name of SHA-1, the certificate hash of {@code signingCertificate} only (RFC 2634, 5.4). */
    private static final String SHA_1 = "SHA-1";

    /** The digest algorithms by object identifier (RFC 5754, 2). */
    private static final Map<String, String> DIGESTS = Map.of(
            TimeStampRequests.SHA_256, "SHA-256",
            "2.16.840.1.101.3.4.2.2", "SHA-384",
            "2.16.840.1.101.3.4.2.3", "SHA-512");

    /** The signature algorithms by object identifier: the digest they imply and the JCA name (RFC 5754, 3.2, 3.3). */
    private static final Map<String, List<String>> SIGNATURES = Map.of(
            "1.2.840.10045.4.3.2", List.of("SHA-256", "SHA256withECDSA"),
            "1.2.840.10045.4.3.3", List.of("SHA-384", "SHA384withECDSA"),
            "1.2.840.10045.4.3.4", List.of("SHA-512", "SHA512withECDSA"),
            "1.2.840.113549.1.1.11", List.of("SHA-256", "SHA256withRSA"),
            "1.2.840.113549.1.1.12", List.of("SHA-384", "SHA384withRSA"),
            "1.2.840.113549.1.1.13", List.of("SHA-512", "SHA512withRSA"));

    /** The JCA names of RSA PKCS #1 v1.5 by digest, for {@code rsaEncryption} as signature algorithm (RFC 5754, 3.2). */
    private static final Map<String, String> RSA_BY_DIGEST = Map.of(
            "SHA-256", "SHA256withRSA",
            "SHA-384", "SHA384withRSA",
            "SHA-512", "SHA512withRSA");

    private TimeStampTokens() {
        // static helpers only
    }

    /**
     * Verifies a token against trusted certificates.
     *
     * @param token the DER encoding of the {@code TimeStampToken}
     * @param trust the certificates of {@code timestamp.trust}, never empty
     * @return {@link StoreOutcome#OK} with the {@code TSTInfo}, whose time and imprint the caller reads, or
     *         {@link StoreOutcome#SIGNATURE_INVALID} with the detail of the broken rule
     * @throws NullPointerException if an argument is {@code null}
     */
    static StoreResult<TimeStampRequests.TstInfo> verify(byte[] token, List<X509Certificate> trust) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(trust, "trust");
        try {
            return StoreResult.ok(check(token.clone(), trust));
        } catch (Der.DerException e) {
            return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, UNREADABLE);
        } catch (Rejected e) {
            return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, e.detail());
        }
    }

    private static TimeStampRequests.TstInfo check(byte[] token, List<X509Certificate> trust)
            throws Der.DerException, Rejected {
        // RFC 5652, 5.1: SignedData ::= SEQUENCE { version, digestAlgorithms, encapContentInfo,
        // certificates [0] IMPLICIT OPTIONAL, crls [1] IMPLICIT OPTIONAL, signerInfos }
        List<Der.Element> signedData = TimeStampRequests.signedData(token);
        signedData.getFirst().integer();
        signedData.get(1).children(Der.SET);
        // RFC 5652, 5.2 and RFC 3161, 2.4.2: the encapsulated content is a TSTInfo
        byte[] content = TimeStampRequests.tstContent(signedData);
        TimeStampRequests.TstInfo info = TimeStampRequests.TstInfo.parse(content);
        List<X509Certificate> carried = new ArrayList<>();
        for (Der.Element field : signedData.subList(3, signedData.size() - 1)) {
            if (field.tag() == Der.contextConstructed(0)) {
                carried.addAll(certificates(field));
            } else if (field.tag() != Der.contextConstructed(1)) {
                throw new Der.DerException("unexpected field of signed data");
            }
        }
        // RFC 3161, 2.4.2: there MUST be only one signer
        List<Der.Element> signerInfos = signedData.getLast().children(Der.SET);
        if (signerInfos.size() != 1) {
            throw new Rejected(NOT_ONE_SIGNER);
        }
        var signer = SignerInfo.parse(signerInfos.getFirst());
        String digest = algorithmName(DIGESTS, signer.digestAlgorithm());
        EssCertId identifier = checkAttributes(signer, content, digest);
        X509Certificate certificate = signerCertificate(signer, identifier, carried, trust);
        checkSignature(signer, certificate, digest);
        checkTimeStamping(certificate);
        checkPath(certificate, carried, trust, info.genTime());
        return info;
    }

    // ---------------------------------------------------------------- certificates of the token

    private static List<X509Certificate> certificates(Der.Element field) throws Der.DerException {
        // RFC 5652, 10.2.3: CertificateChoices; only the plain Certificate, a SEQUENCE, is read, the obsolete and
        // other forms carry no certificate this verifier uses
        CertificateFactory factory = x509Factory();
        List<X509Certificate> certificates = new ArrayList<>();
        for (Der.Element choice : field.children()) {
            if (choice.tag() != Der.SEQUENCE) {
                continue;
            }
            try {
                if (factory.generateCertificate(new ByteArrayInputStream(choice.encoded()))
                        instanceof X509Certificate x509) {
                    certificates.add(x509);
                }
            } catch (CertificateException e) {
                throw new Der.DerException("certificate unreadable");
            }
        }
        return certificates;
    }

    private static CertificateFactory x509Factory() {
        try {
            return CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            // every Java SE implementation must support the X.509 certificate type (CertificateFactory, "Java Security
            // Standard Algorithm Names"), so this is a broken platform, not an outcome
            throw new IllegalStateException("X.509 certificates are not available", e);
        }
    }

    // ---------------------------------------------------------------- signed attributes

    private static EssCertId checkAttributes(SignerInfo signer, byte[] content, String digest)
            throws Der.DerException, Rejected {
        byte[] contentType = null;
        byte[] messageDigest = null;
        Der.Element essAttribute = null;
        String essType = null;
        // RFC 5652, 5.3: Attribute ::= SEQUENCE { attrType, attrValues SET OF }
        for (Der.Element attribute : signer.signedAttributes().children()) {
            List<Der.Element> parts = attribute.children(Der.SEQUENCE);
            if (parts.size() != 2) {
                throw new Der.DerException("attribute malformed");
            }
            String type = parts.getFirst().oid();
            List<Der.Element> values = parts.get(1).children(Der.SET);
            switch (type) {
                case CONTENT_TYPE -> contentType = single(contentType, values).encoded();
                case MESSAGE_DIGEST -> messageDigest = single(messageDigest, values).octets();
                case SIGNING_CERTIFICATE, SIGNING_CERTIFICATE_V2 -> {
                    essAttribute = single(essAttribute, values);
                    essType = type;
                }
                default -> {
                    // other signed attributes are covered by the signature and not interpreted
                }
            }
        }
        // RFC 5652, 11.1: the content type attribute names the type of the encapsulated content
        if (contentType == null
                || !Arrays.equals(contentType, Der.oid(TimeStampRequests.ID_CT_TST_INFO))) {
            throw new Rejected(ATTRIBUTES_MISMATCH);
        }
        // RFC 5652, 11.2 and 5.4: the message digest is the digest of the content octets of eContent
        if (messageDigest == null || !MessageDigest.isEqual(messageDigest, digest(digest, content))) {
            throw new Rejected(ATTRIBUTES_MISMATCH);
        }
        // RFC 3161, 2.4.2 and RFC 5816, 2.2: the signing certificate attribute must be present
        if (essAttribute == null || essType == null) {
            throw new Rejected(SIGNER_UNKNOWN);
        }
        return EssCertId.parse(essAttribute, SIGNING_CERTIFICATE_V2.equals(essType));
    }

    private static Der.Element single(@Nullable Object previous, List<Der.Element> values) throws Rejected {
        // RFC 5652, 11.1 and 11.2: each attribute MUST occur once and have a single value; RFC 3161, 2.4.2 asks the
        // same of the signing certificate attribute
        if (previous != null || values.size() != 1) {
            throw new Rejected(ATTRIBUTES_MISMATCH);
        }
        return values.getFirst();
    }

    // ---------------------------------------------------------------- signer certificate

    private static X509Certificate signerCertificate(SignerInfo signer, EssCertId identifier,
            List<X509Certificate> carried, List<X509Certificate> trust) throws Rejected {
        List<X509Certificate> candidates = new ArrayList<>(carried);
        candidates.addAll(trust);
        for (X509Certificate candidate : candidates) {
            byte[] encoded;
            try {
                encoded = candidate.getEncoded();
            } catch (CertificateException e) {
                continue;
            }
            // RFC 2634, 5.4 and RFC 5035, 3: the first identifier names the signer certificate by its hash
            if (MessageDigest.isEqual(identifier.hash(), digest(identifier.algorithm(), encoded))
                    && signer.identifies(candidate)) {
                return candidate;
            }
        }
        throw new Rejected(SIGNER_UNKNOWN);
    }

    // ---------------------------------------------------------------- signature

    private static void checkSignature(SignerInfo signer, X509Certificate certificate, String digest)
            throws Rejected {
        String algorithm = signatureName(signer.signatureAlgorithm(), digest);
        // RFC 5652, 5.4: the signature covers the DER of the signed attributes with the explicit SET OF tag in place of
        // the implicit [0] of the SignerInfo
        byte[] signed = signer.signedAttributes().encoded();
        signed[0] = (byte) Der.SET;
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(signed);
            if (!verifier.verify(signer.signature())) {
                throw new Rejected(SIGNATURE_MISMATCH);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new Rejected(ALGORITHM_UNSUPPORTED);
        } catch (GeneralSecurityException e) {
            // a key that does not fit the algorithm, or a signature value that is no valid encoding
            throw new Rejected(SIGNATURE_MISMATCH);
        }
    }

    private static String signatureName(String oid, String digest) throws Rejected {
        // RFC 5754, 3.2: rsaEncryption names RSA PKCS #1 v1.5 with the digest of the SignerInfo
        if (RSA_ENCRYPTION.equals(oid)) {
            return algorithmName(RSA_BY_DIGEST, digest);
        }
        List<String> named = SIGNATURES.get(oid);
        // RFC 5754, 3.2 and 3.3: the hash of the signature algorithm is the digest algorithm of the SignerInfo
        if (named == null || !named.getFirst().equals(digest)) {
            throw new Rejected(ALGORITHM_UNSUPPORTED);
        }
        return named.get(1);
    }

    // ---------------------------------------------------------------- purpose and path

    private static void checkTimeStamping(X509Certificate certificate) throws Rejected {
        // RFC 3161, 2.3: the certificate MUST contain only one instance of the extended key usage extension, with the
        // single purpose id-kp-timeStamping, and the extension MUST be critical
        Set<String> critical = certificate.getCriticalExtensionOIDs();
        List<String> purposes;
        try {
            purposes = certificate.getExtendedKeyUsage();
        } catch (CertificateParsingException e) {
            throw new Rejected(NOT_TIMESTAMPING);
        }
        if (critical == null || !critical.contains(EXTENDED_KEY_USAGE)
                || purposes == null || !List.of(ID_KP_TIME_STAMPING).equals(purposes)) {
            throw new Rejected(NOT_TIMESTAMPING);
        }
    }

    private static void checkPath(X509Certificate signer, List<X509Certificate> carried, List<X509Certificate> trust,
            Instant at) throws Rejected {
        Date date = Date.from(at);
        if (trust.contains(signer)) {
            try {
                signer.checkValidity(date);
                return;
            } catch (CertificateException e) {
                throw new Rejected(UNTRUSTED);
            }
        }
        try {
            var path = x509Factory().generateCertPath(chain(signer, carried, trust));
            Set<TrustAnchor> anchors = new HashSet<>();
            for (X509Certificate certificate : trust) {
                anchors.add(new TrustAnchor(certificate, null));
            }
            var parameters = new PKIXParameters(anchors);
            // no network is reached for a verification; the token holds no revocation status of its time
            parameters.setRevocationEnabled(false);
            parameters.setDate(date);
            CertPathValidator.getInstance("PKIX").validate(path, parameters);
        } catch (CertPathValidatorException | CertificateException | InvalidAlgorithmParameterException e) {
            throw new Rejected(UNTRUSTED);
        } catch (NoSuchAlgorithmException e) {
            // every Java SE implementation must support the PKIX CertPathValidator ("Java Security Standard Algorithm
            // Names"), so this is a broken platform, not an outcome
            throw new IllegalStateException("PKIX is not available", e);
        }
    }

    /**
     * Forms the path from the signer towards {@code timestamp.trust} out of the carried certificates: each next
     * certificate is the issuer of the one before, until a certificate is issued by a trusted one.
     */
    private static List<X509Certificate> chain(X509Certificate signer, List<X509Certificate> carried,
            List<X509Certificate> trust) {
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(signer);
        X509Certificate current = signer;
        while (chain.size() <= carried.size() && !issuedByTrusted(current, trust)) {
            X509Certificate issuer = null;
            for (X509Certificate candidate : carried) {
                if (!chain.contains(candidate)
                        && candidate.getSubjectX500Principal().equals(current.getIssuerX500Principal())) {
                    issuer = candidate;
                    break;
                }
            }
            if (issuer == null) {
                break;
            }
            chain.add(issuer);
            current = issuer;
        }
        return chain;
    }

    private static boolean issuedByTrusted(X509Certificate certificate, List<X509Certificate> trust) {
        for (X509Certificate anchor : trust) {
            if (anchor.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    private static String algorithmName(Map<String, String> names, String oid) throws Rejected {
        String name = names.get(oid);
        if (name == null) {
            throw new Rejected(ALGORITHM_UNSUPPORTED);
        }
        return name;
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            // SHA-1, SHA-256, SHA-384 and SHA-512 are required of every Java SE implementation (MessageDigest, "Java
            // Security Standard Algorithm Names"), so this is a broken platform, not an outcome
            throw new IllegalStateException("digest not available: " + algorithm, e);
        }
    }

    /**
     * The fields of a {@code SignerInfo} (RFC 5652, 5.3) the verification needs.
     */
    private static final class SignerInfo {

        private final Der.Element sid;
        private final String digestAlgorithm;
        private final Der.Element signedAttributes;
        private final String signatureAlgorithm;
        private final byte[] signature;

        private SignerInfo(Der.Element sid, String digestAlgorithm, Der.Element signedAttributes,
                String signatureAlgorithm, byte[] signature) {
            this.sid = sid;
            this.digestAlgorithm = digestAlgorithm;
            this.signedAttributes = signedAttributes;
            this.signatureAlgorithm = signatureAlgorithm;
            this.signature = signature;
        }

        /**
         * Reads a {@code SignerInfo ::= SEQUENCE { version, sid, digestAlgorithm, signedAttrs [0] IMPLICIT OPTIONAL,
         * signatureAlgorithm, signature, unsignedAttrs [1] IMPLICIT OPTIONAL }} (RFC 5652, 5.3).
         */
        static SignerInfo parse(Der.Element element) throws Der.DerException, Rejected {
            List<Der.Element> fields = element.children(Der.SEQUENCE);
            if (fields.size() < 5 || fields.size() > 7) {
                throw new Der.DerException("signer info malformed");
            }
            fields.getFirst().integer();
            Der.Element sid = fields.get(1);
            String digest = TimeStampRequests.TstInfo.algorithm(fields.get(2));
            // RFC 5652, 5.3: signed attributes MUST be present when the content type is not id-data
            if (fields.get(3).tag() != Der.contextConstructed(0)) {
                throw new Rejected(ATTRIBUTES_MISMATCH);
            }
            if (fields.size() < 6) {
                throw new Der.DerException("signer info malformed");
            }
            String algorithm = TimeStampRequests.TstInfo.algorithm(fields.get(4));
            byte[] signature = fields.get(5).octets();
            if (fields.size() == 7 && fields.get(6).tag() != Der.contextConstructed(1)) {
                throw new Der.DerException("signer info malformed");
            }
            return new SignerInfo(sid, digest, fields.get(3), algorithm, signature);
        }

        String digestAlgorithm() {
            return digestAlgorithm;
        }

        Der.Element signedAttributes() {
            return signedAttributes;
        }

        String signatureAlgorithm() {
            return signatureAlgorithm;
        }

        byte[] signature() {
            return signature.clone();
        }

        /**
         * Tells whether the {@code sid} names a certificate: {@code IssuerAndSerialNumber} by issuer and serial number,
         * or {@code [0] SubjectKeyIdentifier} by the subject key identifier extension (RFC 5652, 5.3 and 10.2.4).
         */
        boolean identifies(X509Certificate certificate) {
            try {
                if (sid.tag() == Der.SEQUENCE) {
                    List<Der.Element> parts = sid.children();
                    return parts.size() == 2
                            && Arrays.equals(parts.getFirst().encoded(),
                            certificate.getIssuerX500Principal().getEncoded())
                            && certificate.getSerialNumber().equals(parts.get(1).integer());
                }
                if (sid.tag() == Der.contextPrimitive(0)) {
                    byte[] extension = certificate.getExtensionValue(SUBJECT_KEY_IDENTIFIER);
                    // the extension value is an OCTET STRING holding the DER of the KeyIdentifier, an OCTET STRING
                    return extension != null
                            && Arrays.equals(Der.parse(Der.parse(extension).octets()).octets(), sid.content());
                }
                return false;
            } catch (Der.DerException e) {
                return false;
            }
        }
    }

    /**
     * The first certificate identifier of a signing certificate attribute: the digest algorithm and the hash of the
     * signer certificate.
     *
     * @param algorithm the JCA name of the hash algorithm
     * @param hash      the hash of the DER of the certificate
     */
    private record EssCertId(String algorithm, byte[] hash) {

        /**
         * Reads {@code SigningCertificate ::= SEQUENCE { certs SEQUENCE OF ESSCertID, policies OPTIONAL }} with
         * {@code ESSCertID ::= SEQUENCE { certHash OCTET STRING, issuerSerial OPTIONAL }}, the hash SHA-1 (RFC 2634,
         * 5.4), or {@code SigningCertificateV2} with {@code ESSCertIDv2 ::= SEQUENCE { hashAlgorithm DEFAULT sha256,
         * certHash, issuerSerial OPTIONAL }} (RFC 5035, 3).
         */
        static EssCertId parse(Der.Element value, boolean v2) throws Der.DerException, Rejected {
            List<Der.Element> attribute = value.children(Der.SEQUENCE);
            if (attribute.isEmpty()) {
                throw new Der.DerException("signing certificate malformed");
            }
            List<Der.Element> certs = attribute.getFirst().children(Der.SEQUENCE);
            if (certs.isEmpty()) {
                throw new Rejected(SIGNER_UNKNOWN);
            }
            List<Der.Element> first = certs.getFirst().children(Der.SEQUENCE);
            if (first.isEmpty()) {
                throw new Der.DerException("certificate identifier malformed");
            }
            if (!v2) {
                return new EssCertId(SHA_1, first.getFirst().octets());
            }
            if (first.getFirst().tag() == Der.SEQUENCE) {
                if (first.size() < 2) {
                    throw new Der.DerException("certificate identifier malformed");
                }
                return new EssCertId(algorithmName(DIGESTS, TimeStampRequests.TstInfo.algorithm(first.getFirst())),
                        first.get(1).octets());
            }
            return new EssCertId("SHA-256", first.getFirst().octets());
        }

        @Override
        public boolean equals(@Nullable Object other) {
            return other instanceof EssCertId that && algorithm.equals(that.algorithm)
                    && Arrays.equals(hash, that.hash);
        }

        @Override
        public int hashCode() {
            return 31 * algorithm.hashCode() + Arrays.hashCode(hash);
        }

        @Override
        public String toString() {
            return "EssCertId[algorithm=" + algorithm + "]";
        }
    }

    /**
     * A token that breaks a rule of the verification, carrying the fixed detail of the rule.
     */
    private static final class Rejected extends Exception {

        @Serial
        private static final long serialVersionUID = 1L;

        private final String detail;

        Rejected(String detail) {
            super(detail, null, false, false);
            this.detail = detail;
        }

        String detail() {
            return detail;
        }
    }
}
