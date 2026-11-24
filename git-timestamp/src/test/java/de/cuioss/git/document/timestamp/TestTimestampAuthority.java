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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;


import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.jspecify.annotations.Nullable;

/**
 * An in-process RFC 3161 timestamp authority for the tests, on the JDK {@link HttpsServer} (module
 * {@code jdk.httpserver}, part of the JDK) bound to the loopback address, so no test needs network access.
 * <p>
 * It answers a {@code TimeStampReq} with a {@code TimeStampToken}: a CMS {@code SignedData} (RFC 5652) encapsulating a
 * {@code TSTInfo} (RFC 3161, 2.4.2) with the imprint and the nonce of the request, signed by its signer with ECDSA and
 * SHA-256 over signed attributes that carry the content type, the message digest and the ESS
 * {@code signingCertificateV2} (RFC 5035) of the signer; the signer certificate is included when the request asks for
 * it. It records the {@code Authorization} header and the parsed request of every call, and can be scripted to answer
 * otherwise ({@link Answer}).
 */
final class TestTimestampAuthority implements AutoCloseable {

    /** The object identifier of the attribute {@code contentType} (RFC 5652, 11.1). */
    static final String CONTENT_TYPE = "1.2.840.113549.1.9.3";

    /** The object identifier of the attribute {@code messageDigest} (RFC 5652, 11.2). */
    static final String MESSAGE_DIGEST = "1.2.840.113549.1.9.4";

    /** The object identifier of the attribute {@code signingCertificate} (RFC 2634, 5.4). */
    static final String SIGNING_CERTIFICATE = "1.2.840.113549.1.9.16.2.12";

    /** The object identifier of the attribute {@code signingCertificateV2} (RFC 5035, 3). */
    static final String SIGNING_CERTIFICATE_V2 = "1.2.840.113549.1.9.16.2.47";

    /** The policy the authority states in every token, an arc of the example OID tree. */
    static final String POLICY = "1.2.3.4.1";

    private static final String PATH = "/tsa";

    /** The scripted answers of the authority. */
    enum Answer {
        /** A granted token. */
        GRANT,
        /** The status {@code rejection} (2), without a token. */
        REJECTION,
        /** A granted token carrying another nonce than the request. */
        WRONG_NONCE,
        /** A granted token carrying the imprint of another message. */
        WRONG_IMPRINT,
        /** A body that is no DER. */
        UNREADABLE,
        /** The HTTP status 500. */
        HTTP_ERROR,
        /** A granted reply with the media type {@code application/octet-stream}. */
        WRONG_CONTENT_TYPE,
        /** A granted token signed by a timestamping certificate of a CA outside {@code timestamp.trust}. */
        UNTRUSTED_SIGNER,
        /** A granted token signed by a certificate of the trusted CA without {@code id-kp-timeStamping}. */
        SIGNER_WITHOUT_TIME_STAMPING
    }

    /**
     * A request the authority received.
     *
     * @param authorization the {@code Authorization} header, empty when none was sent
     * @param hashAlgorithm the dotted object identifier of the imprint's hash algorithm
     * @param imprint       the hashed message of the imprint
     * @param nonce         the nonce, or {@code null}
     * @param certReq       the value of {@code certReq}
     */
    record Received(Optional<String> authorization, String hashAlgorithm, byte[] imprint, @Nullable BigInteger nonce,
    boolean certReq) {
    }

    private final HttpsServer server;
    private final TestCertificates.Issued signer;
    private final TestCertificates.Issued untrustedSigner;
    private final TestCertificates.Issued signerWithoutTimeStamping;
    private final AtomicReference<Answer> answer = new AtomicReference<>(Answer.GRANT);
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong serials = new AtomicLong(1);
    private final SecureRandom random = new SecureRandom();
    private final AtomicReference<byte @Nullable []> raw = new AtomicReference<>();
    private final AtomicBoolean stall = new AtomicBoolean();
    private final CountDownLatch closing = new CountDownLatch(1);
    private final AtomicReference<CountDownLatch> stalledBody = new AtomicReference<>(new CountDownLatch(1));
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * Starts the authority. Besides the given signer it issues the signers of the scripted answers
     * {@link Answer#UNTRUSTED_SIGNER}, under a CA of its own, and {@link Answer#SIGNER_WITHOUT_TIME_STAMPING}, under
     * the given CA.
     *
     * @param tls    the TLS certificate of the loopback address
     * @param ca     the CA of the TLS certificate, which also issues the signer without the timestamping purpose
     * @param signer the signer of the tokens
     * @throws IOException if the server cannot be bound
     */
    TestTimestampAuthority(TestCertificates.Issued tls, TestCertificates.Issued ca, TestCertificates.Issued signer)
            throws IOException {
        this.signer = Objects.requireNonNull(signer, "signer");
        this.untrustedSigner = TestCertificates.signer(TestCertificates.ca("untrusted ca"), "untrusted tsa", true);
        this.signerWithoutTimeStamping = TestCertificates.signer(ca, "tsa without time stamping", false);
        // 127.0.0.1 itself, not getLoopbackAddress(), which may be ::1: url() and the SAN of the TLS certificate name it
        this.server = HttpsServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0),
                0);
        server.setHttpsConfigurator(new HttpsConfigurator(TestCertificates.serverContext(tls, ca)));
        server.createContext(PATH, this::handle);
        // a stalled answer blocks its handler thread, so each exchange gets one of its own
        server.setExecutor(executor);
        server.start();
    }

    /**
     * Returns the URL of the authority.
     *
     * @return {@code https://127.0.0.1:<port>/tsa}
     */
    URI url() {
        return URI.create("https://127.0.0.1:" + server.getAddress().getPort() + PATH);
    }

    /**
     * Sets the answer of the following requests.
     *
     * @param next the answer
     */
    void answer(Answer next) {
        answer.set(next);
    }

    /**
     * Returns the requests received so far.
     *
     * @return the requests in order
     */
    List<Received> received() {
        return List.copyOf(received);
    }

    /**
     * Returns the number of HTTP exchanges handled so far.
     *
     * @return the number of calls
     */
    long calls() {
        return calls.get();
    }

    /**
     * Answers the following requests with given octets as {@code application/timestamp-reply} and the status 200, in
     * place of the scripted answer.
     *
     * @param body  the octets of the reply
     * @param stalled whether the authority sends the headers and the first octet, then stalls the rest of the body
     *                until it is closed or ten seconds have passed
     */
    void rawReply(byte[] body, boolean stalled) {
        raw.set(body.clone());
        stall.set(stalled);
    }

    /**
     * Waits until a stalled raw reply ({@link #rawReply(byte[], boolean)}) has flushed its first body octet, so the
     * client is reading the body rather than waiting for the connection or the headers.
     *
     * @param timeout the longest wait
     * @return whether the first body octet was flushed since the last {@link #reset()}
     * @throws InterruptedException if the wait is interrupted
     */
    boolean awaitStalledBody(Duration timeout) throws InterruptedException {
        return stalledBody.get().await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Forgets the recorded requests, the raw reply and the signal of a stalled body, and sets the answer back to
     * {@link Answer#GRANT}.
     */
    void reset() {
        received.clear();
        answer.set(Answer.GRANT);
        raw.set(null);
        stall.set(false);
        stalledBody.set(new CountDownLatch(1));
    }

    @Override
    public void close() {
        closing.countDown();
        server.stop(0);
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        try (exchange; InputStream in = exchange.getRequestBody()) {
            byte[] body = in.readAllBytes();
            Optional<String> authorization = Optional.ofNullable(
                    exchange.getRequestHeaders().getFirst("Authorization"));
            Received request;
            try {
                request = parse(authorization, body);
            } catch (Der.DerException e) {
                send(exchange, 400, "text/plain", new byte[]{'?'});
                return;
            }
            received.add(request);
            byte[] rawBody = raw.get();
            if (rawBody != null) {
                sendRaw(exchange, rawBody, stall.get());
                return;
            }
            Answer current = answer.get();
            switch (current) {
                case HTTP_ERROR -> send(exchange, 500, TimestampTransport.REPLY, new byte[]{0});
                case UNREADABLE -> send(exchange, 200, TimestampTransport.REPLY, new byte[]{0x30, 0x05, 0x01});
                case REJECTION -> send(exchange, 200, TimestampTransport.REPLY,
                        Der.sequence(Der.sequence(Der.integer(2))));
                case WRONG_CONTENT_TYPE -> send(exchange, 200, "application/octet-stream", granted(request, current));
                default -> send(exchange, 200, TimestampTransport.REPLY, granted(request, current));
            }
        }
    }

    private void sendRaw(HttpExchange exchange, byte[] body, boolean stalled) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", TimestampTransport.REPLY);
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            if (!stalled) {
                out.write(body);
                return;
            }
            out.write(body, 0, 1);
            out.flush();
            stalledBody.get().countDown();
            try {
                if (closing.await(10, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            out.write(body, 1, body.length - 1);
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Received parse(Optional<String> authorization, byte[] body) throws Der.DerException {
        List<Der.Element> fields = Der.parse(body).children(Der.TAG_SEQUENCE);
        // a TimeStampReq holds at least its version and its messageImprint (RFC 3161, 2.4.1); a shorter shape is
        // malformed and answered with HTTP 400
        if (fields.size() < 2) {
            throw new Der.DerException("request too short");
        }
        // RFC 3161, 2.4.1: version INTEGER { v1(1) }; integer() refuses a first field that is no INTEGER
        if (!BigInteger.ONE.equals(fields.getFirst().integer())) {
            throw new Der.DerException("request of another version than 1");
        }
        List<Der.Element> imprint = fields.get(1).children(Der.TAG_SEQUENCE);
        if (imprint.size() != 2) {
            throw new Der.DerException("message imprint malformed");
        }
        String algorithm = TimeStampRequests.TstInfo.algorithm(imprint.getFirst());
        BigInteger nonce = null;
        boolean certReq = false;
        for (Der.Element field : fields.subList(2, fields.size())) {
            if (field.tag() == Der.TAG_INTEGER) {
                nonce = field.integer();
            } else if (field.tag() == Der.BOOLEAN) {
                certReq = field.bool();
            }
        }
        return new Received(authorization, algorithm, imprint.get(1).octets(), nonce, certReq);
    }

    private byte[] granted(Received request, Answer current) {
        byte[] imprint = current == Answer.WRONG_IMPRINT
                ? TimeStampRequests.sha256(new byte[]{1, 2, 3})
                : request.imprint();
        BigInteger nonce = current == Answer.WRONG_NONCE || request.nonce() == null
                ? new BigInteger(64, random)
                : request.nonce();
        byte[] tstInfo = Der.sequence(
                Der.integer(1),
                Der.oid(POLICY),
                TimeStampRequests.messageImprint(imprint),
                Der.integer(serials.getAndIncrement()),
                Der.generalizedTime(Instant.now()),
                Der.integer(nonce));
        TestCertificates.Issued by = switch (current) {
            case UNTRUSTED_SIGNER -> untrustedSigner;
            case SIGNER_WITHOUT_TIME_STAMPING -> signerWithoutTimeStamping;
            default -> signer;
        };
        byte[] token = token(by, tstInfo, request.certReq());
        return Der.sequence(Der.sequence(Der.integer(TimeStampRequests.GRANTED)), token);
    }

    /**
     * Builds a token over a {@code TSTInfo}, signed by the signer of this authority.
     *
     * @param tstInfo             the DER of the {@code TSTInfo}
     * @param includeCertificate whether the signer certificate is included
     * @return the DER of the {@code TimeStampToken}
     */
    byte[] token(byte[] tstInfo, boolean includeCertificate) {
        return token(signer, tstInfo, includeCertificate);
    }

    /**
     * Builds a token over a {@code TSTInfo}, signed by a given signer.
     *
     * @param by                 the signer
     * @param tstInfo            the DER of the {@code TSTInfo}
     * @param includeCertificate whether the signer certificate is included
     * @return the DER of the {@code TimeStampToken}
     */
    static byte[] token(TestCertificates.Issued by, byte[] tstInfo, boolean includeCertificate) {
        List<byte[]> middle = includeCertificate
                ? List.of(Der.tlv(Der.contextConstructed(0), encoded(by)))
                : List.of();
        return token(by, tstInfo, List.of(signingCertificateV2(by)), middle);
    }

    /**
     * Encodes the signed attribute {@code signingCertificateV2} (RFC 5035, 3) naming a certificate by its SHA-256.
     *
     * @param named the certificate
     * @return the DER of the attribute
     */
    static byte[] signingCertificateV2(TestCertificates.Issued named) {
        return Der.sequence(Der.oid(SIGNING_CERTIFICATE_V2), Der.setOf(Der.sequence(Der.sequence(Der.sequence(
                Der.octetString(TimeStampRequests.sha256(encoded(named))))))));
    }

    /**
     * Encodes the signed attribute {@code signingCertificate} (RFC 2634, 5.4) naming a certificate by its SHA-1.
     *
     * @param named the certificate
     * @return the DER of the attribute
     */
    static byte[] signingCertificate(TestCertificates.Issued named) {
        byte[] sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1").digest(encoded(named));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 is not available", e);
        }
        return Der.sequence(Der.oid(SIGNING_CERTIFICATE), Der.setOf(Der.sequence(Der.sequence(Der.sequence(
                Der.octetString(sha1))))));
    }

    /**
     * Builds a token over a {@code TSTInfo} with given signing certificate attributes and given fields between the
     * encapsulated content and the signer infos, such as {@code certificates [0]} and {@code crls [1]}.
     *
     * @param by          the signer
     * @param tstInfo     the DER of the {@code TSTInfo}
     * @param essAttributes the signing certificate attributes, each a complete attribute
     * @param middle      the encoded fields between the encapsulated content and the signer infos, in order
     * @return the DER of the {@code TimeStampToken}
     */
    static byte[] token(TestCertificates.Issued by, byte[] tstInfo, List<byte[]> essAttributes, List<byte[]> middle) {
        return token(by, tstInfo, essAttributes, middle, by.certificate().getIssuerX500Principal().getEncoded());
    }

    /**
     * Builds a token whose {@code sid} names the issuer of the signer with given octets, the serial number being the
     * signer's; the signer certificate is carried in {@code certificates [0]}.
     *
     * @param by        the signer
     * @param tstInfo   the DER of the {@code TSTInfo}
     * @param sidIssuer the octets written as the issuer of the {@code IssuerAndSerialNumber}, which need not be a
     *                  valid name
     * @return the DER of the {@code TimeStampToken}
     */
    static byte[] tokenWithSidIssuer(TestCertificates.Issued by, byte[] tstInfo, byte[] sidIssuer) {
        return token(by, tstInfo, List.of(signingCertificateV2(by)), List.of(certificatesField(by)), sidIssuer);
    }

    private static byte[] token(TestCertificates.Issued by, byte[] tstInfo, List<byte[]> essAttributes,
            List<byte[]> middle, byte[] sidIssuer) {
        byte[] sha256 = Der.sequence(Der.oid(TimeStampRequests.SHA_256));
        List<byte[]> attributeList = new ArrayList<>();
        attributeList.add(Der.sequence(Der.oid(CONTENT_TYPE), Der.setOf(Der.oid(TimeStampRequests.ID_CT_TST_INFO))));
        attributeList.add(Der.sequence(Der.oid(MESSAGE_DIGEST),
                Der.setOf(Der.octetString(TimeStampRequests.sha256(tstInfo)))));
        attributeList.addAll(essAttributes);
        byte[][] attributes = attributeList.toArray(byte[][]::new);
        // the signature covers the DER of the signed attributes as a SET OF, the explicit SET tag in place of the
        // implicit [0] of the SignerInfo (RFC 5652, 5.4)
        byte[] signature = TestCertificates.signature(by.keys(), Der.setOf(attributes));
        byte[] signerInfo = Der.sequence(
                Der.integer(1),
                Der.sequence(sidIssuer, Der.integer(by.certificate().getSerialNumber())),
                sha256,
                Der.taggedSetOf(Der.contextConstructed(0), attributes),
                Der.sequence(Der.oid(TestCertificates.ECDSA_WITH_SHA256)),
                Der.octetString(signature));
        byte[] encapsulated = Der.sequence(Der.oid(TimeStampRequests.ID_CT_TST_INFO),
                Der.explicit(0, Der.octetString(tstInfo)));
        List<byte[]> fields = new ArrayList<>(List.of(Der.integer(3), Der.setOf(sha256), encapsulated));
        fields.addAll(middle);
        fields.add(Der.setOf(signerInfo));
        byte[] signedData = Der.sequence(fields.toArray(byte[][]::new));
        return Der.sequence(Der.oid(TimeStampRequests.ID_SIGNED_DATA), Der.explicit(0, signedData));
    }

    /**
     * Encodes the field {@code certificates [0] IMPLICIT CertificateSet} of a {@code SignedData} (RFC 5652, 5.1).
     *
     * @param certificates the certificates in order
     * @return the DER of the field
     */
    static byte[] certificatesField(TestCertificates.Issued... certificates) {
        List<byte[]> encodings = new ArrayList<>();
        for (TestCertificates.Issued certificate : certificates) {
            encodings.add(encoded(certificate));
        }
        return Der.tlv(Der.contextConstructed(0), Der.concat(encodings.toArray(byte[][]::new)));
    }

    private static byte[] encoded(TestCertificates.Issued issued) {
        try {
            return issued.certificate().getEncoded();
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("the certificate cannot be encoded", e);
        }
    }
}
