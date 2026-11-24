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
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The typed section {@code timestamp} of the configuration, read from the same already-parsed nested map an
 * application hands to {@code StoreConfiguration.from} of {@code git-store}.
 * <ul>
 * <li>{@value #KEY_URL} is required: an absolute {@code https} URL with a host and without user information.</li>
 * <li>{@value #KEY_TRUST} is required: a non-empty list of paths to PEM files, each holding one or more X.509
 * certificates. A file is read without following a symbolic link, and at most {@value #MAX_TRUST_FILE_BYTES} octets
 * and one more are read from it.</li>
 * <li>{@value #KEY_CREDENTIALS} is optional: a single bearer token, sent as {@code Authorization: Bearer <token>}. It
 * must be a {@code token68} of <a href="https://www.rfc-editor.org/rfc/rfc6750#section-2.1">RFC 6750, 2.1</a>, so it
 * can never break the header.</li>
 * </ul>
 * Every refusal is {@link StoreOutcome#MALFORMED} with a detail naming the key, never its value, the content of a
 * certificate file or the token (GDR-NFR-3). The token is held in memory only and never rendered by
 * {@link #toString()}.
 */
final class TimestampConfiguration {

    /** The section of the configuration. */
    static final String SECTION = "timestamp";

    /** The key of the URL of the timestamp authority. */
    static final String KEY_URL = "timestamp.url";

    /** The key of the trusted certificates. */
    static final String KEY_TRUST = "timestamp.trust";

    /** The key of the bearer token. */
    static final String KEY_CREDENTIALS = "timestamp.credentials";

    /** The largest size of a certificate file of {@value #KEY_TRUST}: 1 MiB. */
    static final int MAX_TRUST_FILE_BYTES = 1 << 20;

    /** The {@code b64token} of RFC 6750, 2.1. */
    private static final Pattern TOKEN68 = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");

    /** The largest TCP port. */
    private static final int MAX_PORT = 0xFFFF;

    /** The encapsulation boundary of a PEM certificate (RFC 7468, 2 and 5.1). */
    private static final String PEM_CERTIFICATE = "-----BEGIN CERTIFICATE-----";

    private static final String IS_MISSING = " is missing";
    private static final String IS_MALFORMED = " is malformed";

    private final URI url;
    private final List<X509Certificate> trust;
    private final @Nullable String credentials;

    private TimestampConfiguration(URI url, List<X509Certificate> trust, @Nullable String credentials) {
        this.url = url;
        this.trust = List.copyOf(trust);
        this.credentials = credentials;
    }

    /**
     * Reads the section {@value #SECTION} of a configuration.
     *
     * @param configuration the root of the configuration, nested maps keyed by segment
     * @return {@link StoreOutcome#OK} with the section, or {@link StoreOutcome#MALFORMED} naming the key that is
     *         missing or malformed
     * @throws NullPointerException if {@code configuration} is {@code null}
     */
    static StoreResult<TimestampConfiguration> from(Map<String, ?> configuration) {
        Objects.requireNonNull(configuration, "configuration");
        if (!configuration.containsKey(SECTION)) {
            return missing(KEY_URL);
        }
        if (!(configuration.get(SECTION) instanceof Map<?, ?> section)) {
            return StoreResult.of(StoreOutcome.MALFORMED, SECTION + IS_MALFORMED);
        }
        var url = url(section);
        if (!url.isOk()) {
            return retype(url);
        }
        var trust = trust(section);
        if (!trust.isOk()) {
            return retype(trust);
        }
        var credentials = credentials(section);
        if (!credentials.isOk()) {
            return retype(credentials);
        }
        return StoreResult.ok(new TimestampConfiguration(Objects.requireNonNull(url.value()),
                Objects.requireNonNull(trust.value()), credentials.value()));
    }

    private static StoreResult<URI> url(Map<?, ?> section) {
        String key = leaf(KEY_URL);
        if (!section.containsKey(key)) {
            return missing(KEY_URL);
        }
        if (!(section.get(key) instanceof String text) || text.isBlank()) {
            return malformed(KEY_URL);
        }
        URI uri;
        try {
            uri = new URI(text.strip());
        } catch (URISyntaxException e) {
            return malformed(KEY_URL);
        }
        boolean https = uri.getScheme() != null && "https".equals(uri.getScheme().toLowerCase(Locale.ROOT));
        boolean userInformation = uri.getRawUserInfo() != null
                || uri.getRawAuthority() != null && uri.getRawAuthority().contains("@");
        // a port above 65535 parses as a URI but is refused by HttpClient at the first request
        if (!https || uri.getHost() == null || userInformation || uri.isOpaque() || uri.getPort() > MAX_PORT) {
            return malformed(KEY_URL);
        }
        return StoreResult.ok(uri);
    }

    private static StoreResult<List<X509Certificate>> trust(Map<?, ?> section) {
        String key = leaf(KEY_TRUST);
        if (!section.containsKey(key)) {
            return missing(KEY_TRUST);
        }
        if (!(section.get(key) instanceof List<?> paths) || paths.isEmpty()) {
            return malformed(KEY_TRUST);
        }
        CertificateFactory factory;
        try {
            factory = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            // every Java SE implementation must support the X.509 certificate type (CertificateFactory, "Java Security
            // Standard Algorithm Names"), so this is a broken platform, not an outcome
            throw new IllegalStateException("X.509 certificates are not available", e);
        }
        List<X509Certificate> certificates = new ArrayList<>();
        for (int index = 0; index < paths.size(); index++) {
            var read = certificates(paths.get(index), factory);
            if (read.isEmpty()) {
                return malformed(KEY_TRUST + "[" + index + "]");
            }
            certificates.addAll(read.get());
        }
        return StoreResult.ok(certificates);
    }

    /**
     * Reads the certificates of one entry of {@value #KEY_TRUST}.
     *
     * @param entry   the entry, a path
     * @param factory the factory of X.509 certificates
     * @return the certificates of the file, or empty for a missing, empty, linked, oversized or unparsable entry
     */
    private static Optional<List<X509Certificate>> certificates(@Nullable Object entry, CertificateFactory factory) {
        if (!(entry instanceof String text) || text.isBlank()) {
            return Optional.empty();
        }
        byte[] content;
        try {
            Path file = Path.of(text);
            var attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > MAX_TRUST_FILE_BYTES) {
                return Optional.empty();
            }
            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ,
                         LinkOption.NOFOLLOW_LINKS); InputStream in = Channels.newInputStream(channel)) {
                content = in.readNBytes(MAX_TRUST_FILE_BYTES + 1);
            }
        } catch (InvalidPathException | IOException e) {
            return Optional.empty();
        }
        if (content.length == 0 || content.length > MAX_TRUST_FILE_BYTES) {
            return Optional.empty();
        }
        // the contract is PEM: CertificateFactory also reads binary DER and PKCS #7, which are refused here
        if (!new String(content, StandardCharsets.US_ASCII).contains(PEM_CERTIFICATE)) {
            return Optional.empty();
        }
        List<X509Certificate> certificates = new ArrayList<>();
        try {
            for (Certificate certificate : factory.generateCertificates(new ByteArrayInputStream(content))) {
                if (!(certificate instanceof X509Certificate x509)) {
                    return Optional.empty();
                }
                certificates.add(x509);
            }
        } catch (CertificateException e) {
            return Optional.empty();
        }
        return certificates.isEmpty() ? Optional.empty() : Optional.of(certificates);
    }

    private static StoreResult<String> credentials(Map<?, ?> section) {
        String key = leaf(KEY_CREDENTIALS);
        if (!section.containsKey(key)) {
            return new StoreResult<>(StoreOutcome.OK, null, null);
        }
        if (!(section.get(key) instanceof String token) || !TOKEN68.matcher(token).matches()) {
            return malformed(KEY_CREDENTIALS);
        }
        return StoreResult.ok(token);
    }

    private static String leaf(String key) {
        return key.substring(SECTION.length() + 1);
    }

    private static <V> StoreResult<V> missing(String key) {
        return StoreResult.of(StoreOutcome.MALFORMED, key + IS_MISSING);
    }

    private static <V> StoreResult<V> malformed(String key) {
        return StoreResult.of(StoreOutcome.MALFORMED, key + IS_MALFORMED);
    }

    private static <V> StoreResult<V> retype(StoreResult<?> refusal) {
        return new StoreResult<>(refusal.outcome(), null, refusal.detail());
    }

    /**
     * Returns the URL of the timestamp authority.
     *
     * @return the {@code https} URL, without user information
     */
    URI url() {
        return url;
    }

    /**
     * Returns the trusted certificates of {@value #KEY_TRUST}, in the order of the files and within a file.
     *
     * @return the certificates, never empty
     */
    List<X509Certificate> trust() {
        return trust;
    }

    /**
     * Returns the bearer token of {@value #KEY_CREDENTIALS}, for the transport only.
     *
     * @return the token, or {@code null} when none is configured
     */
    @Nullable
    String credentials() {
        return credentials;
    }

    /**
     * Renders the section without the token and without the content of a certificate (GDR-NFR-3).
     *
     * @return {@code TimestampConfiguration[host=..., trust=<number>, credentials=<present>]}
     */
    @Override
    public String toString() {
        return "TimestampConfiguration[host=" + url.getHost() + ", trust=" + trust.size() + ", credentials="
                + (credentials != null) + "]";
    }
}
