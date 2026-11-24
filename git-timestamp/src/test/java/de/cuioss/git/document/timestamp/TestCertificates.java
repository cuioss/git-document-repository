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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * Test-side certificates, made with the module's own DER writer at run time, so no test resource with a private key is
 * committed: a self-signed CA, a timestamping certificate (extended key usage {@code id-kp-timeStamping}, critical, as
 * RFC 3161, 2.3 requires) or a signer without it, and the TLS server certificate of the in-process test authority for
 * the loopback address, with the key store and the {@link SSLContext}s of server and client.
 * <p>
 * The keys are EC on the curve P-256, the signatures ECDSA with SHA-256.
 */
final class TestCertificates {

    /** The object identifier of {@code ecdsa-with-SHA256} (RFC 5758, 3.2). */
    static final String ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2";

    /** The JCA name of the signature algorithm. */
    static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    /** The object identifier of {@code id-kp-timeStamping} (RFC 5280, 4.2.1.12). */
    static final String ID_KP_TIME_STAMPING = "1.3.6.1.5.5.7.3.8";

    private static final String ID_KP_SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final String COMMON_NAME = "2.5.4.3";
    private static final String BASIC_CONSTRAINTS = "2.5.29.19";
    private static final String KEY_USAGE = "2.5.29.15";
    private static final String EXTENDED_KEY_USAGE = "2.5.29.37";
    private static final String SUBJECT_ALT_NAME = "2.5.29.17";
    private static final char[] STORE_PASSWORD = "test-store".toCharArray();
    private static final AtomicLong SERIALS = new AtomicLong(1);
    private static final SecureRandom RANDOM = new SecureRandom();

    private TestCertificates() {
        // static helpers only
    }

    /**
     * A key pair and the certificate issued for its public key.
     *
     * @param keys        the key pair
     * @param certificate the certificate
     */
    record Issued(KeyPair keys, X509Certificate certificate) {
    }

    /**
     * Creates a self-signed CA certificate.
     *
     * @param name the common name
     * @return the CA
     */
    static Issued ca(String name) {
        KeyPair keys = keyPair();
        byte[] extensions = Der.sequence(
                extension(BASIC_CONSTRAINTS, true, Der.sequence(Der.bool(true))),
                // keyCertSign (bit 5) and cRLSign (bit 6): one octet 0000 0110, one unused bit
                extension(KEY_USAGE, true, Der.tlv(Der.BIT_STRING, new byte[]{1, 0x06})));
        return new Issued(keys, sign(name(name), name(name), keys, keys, extensions));
    }

    /**
     * Issues a timestamp signer certificate.
     *
     * @param issuer        the issuing CA
     * @param name          the common name
     * @param timeStamping  whether the certificate carries the critical extended key usage {@code id-kp-timeStamping}
     * @return the signer
     */
    static Issued signer(Issued issuer, String name, boolean timeStamping) {
        KeyPair keys = keyPair();
        List<byte[]> extensions = new ArrayList<>();
        // digitalSignature (bit 0) and nonRepudiation (bit 1): one octet 1100 0000, six unused bits
        extensions.add(extension(KEY_USAGE, true, Der.tlv(Der.BIT_STRING, new byte[]{6, (byte) 0xC0})));
        if (timeStamping) {
            extensions.add(extension(EXTENDED_KEY_USAGE, true, Der.sequence(Der.oid(ID_KP_TIME_STAMPING))));
        }
        return new Issued(keys, sign(name(issuer), name(name), keys, issuer.keys(),
                Der.sequence(extensions.toArray(byte[][]::new))));
    }

    /**
     * Issues the TLS server certificate of the loopback address {@code 127.0.0.1}.
     *
     * @param issuer the issuing CA
     * @return the TLS certificate
     */
    static Issued tls(Issued issuer) {
        KeyPair keys = keyPair();
        byte[] ipAddress = Der.tlv(Der.contextPrimitive(7), new byte[]{127, 0, 0, 1});
        byte[] extensions = Der.sequence(
                extension(SUBJECT_ALT_NAME, false, Der.sequence(ipAddress)),
                extension(EXTENDED_KEY_USAGE, false, Der.sequence(Der.oid(ID_KP_SERVER_AUTH))));
        return new Issued(keys, sign(name(issuer), name("test authority tls"), keys, issuer.keys(), extensions));
    }

    /**
     * Encodes certificates as PEM, one block after another.
     *
     * @param certificates the certificates
     * @return the PEM text
     */
    static String pem(X509Certificate... certificates) {
        var text = new StringBuilder();
        var encoder = Base64.getMimeEncoder(64, "\n".getBytes(US_ASCII));
        for (X509Certificate certificate : certificates) {
            try {
                text.append("-----BEGIN CERTIFICATE-----\n")
                        .append(encoder.encodeToString(certificate.getEncoded()))
                        .append("\n-----END CERTIFICATE-----\n");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("the certificate cannot be encoded", e);
            }
        }
        return text.toString();
    }

    /**
     * Creates the server context of the test authority.
     *
     * @param tls    the TLS certificate
     * @param issuer its CA
     * @return the context
     */
    static SSLContext serverContext(Issued tls, Issued issuer) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setKeyEntry("tls", tls.keys().getPrivate(), STORE_PASSWORD,
                    new X509Certificate[]{tls.certificate(), issuer.certificate()});
            KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(store, STORE_PASSWORD);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keyManagers.getKeyManagers(), null, RANDOM);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("the server context cannot be created", e);
        }
    }

    /**
     * Creates a client context that trusts one CA only.
     *
     * @param issuer the CA
     * @return the context
     */
    static SSLContext clientContext(Issued issuer) {
        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);
            store.setCertificateEntry("ca", issuer.certificate());
            TrustManagerFactory trustManagers = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustManagers.getTrustManagers(), RANDOM);
            return context;
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("the client context cannot be created", e);
        }
    }

    /**
     * Signs data with ECDSA and SHA-256.
     *
     * @param keys the signing key pair
     * @param data the data
     * @return the DER signature
     */
    static byte[] signature(KeyPair keys, byte[] data) {
        try {
            Signature signer = Signature.getInstance(SIGNATURE_ALGORITHM);
            signer.initSign(keys.getPrivate());
            signer.update(data);
            return signer.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the data cannot be signed", e);
        }
    }

    /**
     * Returns the encoded name of a certificate's subject.
     *
     * @param issued the certificate
     * @return the DER of the subject name
     */
    static byte[] name(Issued issued) {
        return issued.certificate().getSubjectX500Principal().getEncoded();
    }

    private static byte[] name(String commonName) {
        return Der.sequence(Der.setOf(Der.sequence(Der.oid(COMMON_NAME), Der.utf8String(commonName))));
    }

    private static byte[] extension(String oid, boolean critical, byte[] value) {
        return critical
                ? Der.sequence(Der.oid(oid), Der.bool(true), Der.octetString(value))
                : Der.sequence(Der.oid(oid), Der.octetString(value));
    }

    private static X509Certificate sign(byte[] issuer, byte[] subject, KeyPair subjectKeys, KeyPair issuerKeys,
            byte[] extensions) {
        Instant now = Instant.now();
        byte[] algorithm = Der.sequence(Der.oid(ECDSA_WITH_SHA256));
        byte[] tbs = Der.sequence(
                Der.explicit(0, Der.integer(2)),
                Der.integer(BigInteger.valueOf(SERIALS.getAndIncrement())),
                algorithm,
                issuer,
                Der.sequence(Der.utcTime(now.minus(Duration.ofDays(1))), Der.utcTime(now.plus(Duration.ofDays(30)))),
                subject,
                subjectKeys.getPublic().getEncoded(),
                Der.explicit(3, extensions));
        byte[] certificate = Der.sequence(tbs, algorithm, Der.bitString(signature(issuerKeys, tbs)));
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certificate));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("the generated certificate cannot be read", e);
        }
    }

    private static KeyPair keyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no EC key pair", e);
        }
    }
}
