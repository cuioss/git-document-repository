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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP transport of RFC 3161 (<a href="https://www.rfc-editor.org/rfc/rfc3161#section-3.4">RFC 3161, 3.4</a>):
 * one {@code POST} of the DER request with {@code Content-Type: application/timestamp-query}, answered by a reply of
 * {@code Content-Type: application/timestamp-reply}.
 * <p>
 * The transport runs on {@link HttpClient} of {@code java.net.http}: redirects are never followed, the connection
 * times out after {@link #CONNECT_TIMEOUT} and the request after {@link #REQUEST_TIMEOUT}, and at most
 * {@value #MAX_REPLY_BYTES} octets of a reply are read. When a bearer token is configured, it is sent as
 * {@code Authorization: Bearer <token>}; otherwise no {@code Authorization} header is sent. No detail and no
 * {@link #toString()} carries the token, the URL or the body of a reply (GDR-NFR-3).
 */
final class TimestampTransport {

    /** The media type of a request. */
    static final String QUERY = "application/timestamp-query";

    /** The media type of a reply. */
    static final String REPLY = "application/timestamp-reply";

    /** The time to establish the connection. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** The time to wait for the reply once the request is sent. */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** The largest reply read: 1 MiB. */
    static final int MAX_REPLY_BYTES = 1 << 20;

    /** The detail of an authority that cannot be reached: connection refused, timeout or another I/O failure. */
    static final String UNREACHABLE = "rfc3161: authority unreachable";

    /** The detail of a transport interrupted while it waited. */
    static final String INTERRUPTED = "rfc3161: interrupted while waiting for the authority";

    /** The prefix of the detail of a reply with another HTTP status than 200; the status follows. */
    static final String HTTP_STATUS_PREFIX = "rfc3161: the authority answered with HTTP status ";

    /** The detail of a reply of another media type. */
    static final String WRONG_CONTENT_TYPE = "rfc3161: the reply is not " + REPLY;

    /** The detail of a reply larger than {@link #MAX_REPLY_BYTES}. */
    static final String REPLY_TOO_LARGE = "rfc3161: the reply is larger than the limit";

    private static final int HTTP_OK = 200;

    private final URI url;
    private final @Nullable String bearer;
    private final HttpClient client;

    /**
     * Creates the transport on a client of {@link #clientBuilder()}.
     *
     * @param url    the URL of the authority
     * @param bearer the bearer token, or {@code null} for none
     */
    TimestampTransport(URI url, @Nullable String bearer) {
        this(url, bearer, clientBuilder().build());
    }

    /**
     * Creates the transport on a given client, for the tests to trust their in-process authority.
     *
     * @param url    the URL of the authority
     * @param bearer the bearer token, or {@code null} for none
     * @param client the client
     */
    TimestampTransport(URI url, @Nullable String bearer, HttpClient client) {
        this.url = Objects.requireNonNull(url, "url");
        this.bearer = bearer;
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Returns the builder of the client this transport uses: redirects never followed, HTTP/1.1, the connect timeout
     * {@link #CONNECT_TIMEOUT}.
     *
     * @return a new builder
     */
    static HttpClient.Builder clientBuilder() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT);
    }

    /**
     * Sends a request and reads the reply.
     *
     * @param request the DER encoding of the {@code TimeStampReq}
     * @return {@link StoreOutcome#OK} with the octets of the reply; {@link StoreOutcome#UNAVAILABLE} with
     *         {@value #UNREACHABLE} or {@value #INTERRUPTED}; {@link StoreOutcome#REFUSED} for another HTTP status than
     *         200, with {@value #WRONG_CONTENT_TYPE} or with {@value #REPLY_TOO_LARGE}
     */
    StoreResult<byte[]> post(byte[] request) {
        var builder = HttpRequest.newBuilder(url)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", QUERY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.clone()));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != HTTP_OK) {
                    return StoreResult.of(StoreOutcome.REFUSED, HTTP_STATUS_PREFIX + response.statusCode());
                }
                if (!isReply(response.headers().firstValue("Content-Type").orElse(""))) {
                    return StoreResult.of(StoreOutcome.REFUSED, WRONG_CONTENT_TYPE);
                }
                byte[] reply = body.readNBytes(MAX_REPLY_BYTES + 1);
                if (reply.length > MAX_REPLY_BYTES) {
                    return StoreResult.of(StoreOutcome.REFUSED, REPLY_TOO_LARGE);
                }
                return StoreResult.ok(reply);
            }
        } catch (IOException e) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StoreResult.of(StoreOutcome.UNAVAILABLE, INTERRUPTED);
        }
    }

    /**
     * Tells whether a {@code Content-Type} names {@value #REPLY}, its parameters ignored and compared
     * case-insensitively.
     *
     * @param contentType the header value
     * @return whether it is the media type of a reply
     */
    static boolean isReply(String contentType) {
        int parameters = contentType.indexOf(';');
        String type = parameters < 0 ? contentType : contentType.substring(0, parameters);
        return REPLY.equals(type.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * Renders the transport by the host of the authority and whether a token is configured, never the token
     * (GDR-NFR-3).
     *
     * @return {@code TimestampTransport[host=..., credentials=<present>]}
     */
    @Override
    public String toString() {
        return "TimestampTransport[host=" + url.getHost() + ", credentials=" + (bearer != null) + "]";
    }
}
