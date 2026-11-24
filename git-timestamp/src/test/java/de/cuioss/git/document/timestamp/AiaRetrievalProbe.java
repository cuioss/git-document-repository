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
import java.io.OutputStream;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;


import com.sun.net.httpserver.HttpServer;

/**
 * The main class of the child JVM of {@code Rfc3161VerifyTest}: it is started with
 * {@code -Dcom.sun.security.enableAIAcaIssuers=true}, which the PKIX {@code CertPathBuilder} of OpenJDK reads once per
 * JVM, so the property cannot be set in the JVM of the test.
 * <p>
 * It serves the certificate of an intermediate CA over plain HTTP on the loopback address, issues a timestamp signer
 * below that intermediate whose Authority Information Access extension names the served URI, admits that URI through
 * {@value #ALLOWED_AIA_LOCATIONS} for the OpenJDK updates that deny every AIA location by default, and then
 * <ol>
 * <li>verifies a token that carries the signer alone against the root CA with {@link TimeStampTokens#verify}, and
 * counts the requests the server saw;</li>
 * <li>as the control that proves the setup can fetch, builds a path over the same certificates with a plain
 * {@code CertPathBuilder}, and counts the requests again.</li>
 * </ol>
 * The results are written as properties to the file named by the single argument.
 */
final class AiaRetrievalProbe {

    /** The system property that enables the retrieval of issuers over AIA in OpenJDK. */
    static final String ENABLE_AIA = "com.sun.security.enableAIAcaIssuers";

    /** The system property that admits AIA locations in the OpenJDK updates that deny them all by default. */
    static final String ALLOWED_AIA_LOCATIONS = "com.sun.security.allowedAIALocations";

    /** The property of the detail of the verification. */
    static final String VERIFY_DETAIL = "verify.detail";

    /** The property of the number of requests after the verification. */
    static final String VERIFY_REQUESTS = "verify.requests";

    /** The property of whether the control built a path. */
    static final String CONTROL_BUILT = "control.built";

    /** The property of the number of requests after the control. */
    static final String CONTROL_REQUESTS = "control.requests";

    private static final String PATH = "/issuer.cer";

    private AiaRetrievalProbe() {
        // main class only
    }

    /**
     * Runs the probe.
     *
     * @param args the path of the report file
     * @throws IOException              if the server cannot be bound or the report cannot be written
     * @throws GeneralSecurityException if the control cannot be set up
     */
    public static void main(String[] args) throws IOException, GeneralSecurityException {
        Path report = Path.of(args[0]);
        var ca = TestCertificates.ca("aia ca");
        var intermediate = TestCertificates.intermediate(ca, "aia intermediate");
        byte[] served = intermediate.certificate().getEncoded();
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 0);
        server.createContext(PATH, exchange -> {
            requests.incrementAndGet();
            try (exchange; OutputStream out = exchange.getResponseBody()) {
                exchange.getResponseHeaders().set("Content-Type", "application/pkix-cert");
                exchange.sendResponseHeaders(200, served.length);
                out.write(served);
            }
        });
        server.start();
        try {
            URI location = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PATH);
            // newer OpenJDK updates deny every AIA location unless this property admits it (observed in OpenJDK
            // 25.0.3); it is read when the first URI certificate store is created, which happens after this line
            System.setProperty(ALLOWED_AIA_LOCATIONS, location.toString());
            var signer = TestCertificates.signer(intermediate, "aia tsa", location);
            byte[] tstInfo = Der.sequence(Der.integer(1), Der.oid(TestTimestampAuthority.POLICY),
                    TimeStampRequests.messageImprint(TimeStampRequests.sha256(new byte[]{1})), Der.integer(1),
                    Der.generalizedTime(Instant.now()));
            byte[] token = TestTimestampAuthority.token(signer, tstInfo, true);

            var verified = TimeStampTokens.verify(token, List.of(ca.certificate()));
            int afterVerify = requests.get();
            boolean built = control(signer, ca);
            int afterControl = requests.get();

            var properties = new Properties();
            properties.setProperty(VERIFY_DETAIL, String.valueOf(verified.detail()));
            properties.setProperty(VERIFY_REQUESTS, Integer.toString(afterVerify));
            properties.setProperty(CONTROL_BUILT, Boolean.toString(built));
            properties.setProperty(CONTROL_REQUESTS, Integer.toString(afterControl));
            try (Writer writer = Files.newBufferedWriter(report)) {
                properties.store(writer, null);
            }
        } finally {
            server.stop(0);
        }
    }

    private static boolean control(TestCertificates.Issued signer, TestCertificates.Issued ca)
            throws GeneralSecurityException {
        var target = new X509CertSelector();
        target.setCertificate(signer.certificate());
        var parameters = new PKIXBuilderParameters(Set.of(new TrustAnchor(ca.certificate(), null)), target);
        parameters.setRevocationEnabled(false);
        parameters.addCertStore(CertStore.getInstance("Collection",
                new CollectionCertStoreParameters(List.of(signer.certificate()))));
        try {
            CertPathBuilder.getInstance("PKIX").build(parameters);
            return true;
        } catch (CertPathBuilderException e) {
            return false;
        }
    }
}
