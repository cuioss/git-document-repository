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
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP transport of RFC 3161 (<a href="https://www.rfc-editor.org/rfc/rfc3161#section-3.4">RFC 3161, 3.4</a>):
 * one {@code POST} of the DER request with {@code Content-Type: application/timestamp-query}, answered by a reply of
 * {@code Content-Type: application/timestamp-reply}.
 * <p>
 * The transport runs on {@link HttpClient} of {@code java.net.http}: redirects are never followed, the connection
 * times out after {@link #CONNECT_TIMEOUT}, one deadline of {@link #REQUEST_TIMEOUT} runs from sending the request to
 * the end of the reply body, and at most {@value #MAX_REPLY_BYTES} octets of a reply are read. When a bearer token is
 * configured, it is sent as {@code Authorization: Bearer <token>}, unless the setting {@value #CLIENT_LOG_PROPERTY}
 * makes the client log request headers: then the request is refused without contacting the authority. The setting is
 * read on every credentialed request as a JVM start would read it, the system property when it is set and otherwise
 * the default of {@code conf/net.properties} under {@code java.home}. The JDK caches its logging flags when its
 * logging class initialises and exposes them through no public API, so a change of the setting after that is not seen:
 * a property cleared after the client began to log headers lets the request through. Without a token no
 * {@code Authorization} header is sent. No detail and no {@link #toString()} carries the token, the URL or the body of
 * a reply (GDR-NFR-3).
 * <p>
 * The body of a reply is read within the deadline; a close at the deadline, scheduled on a daemon thread the transport
 * owns, ends a read the authority stalls. An interrupt of the waiting thread, in the header wait or in the body read,
 * gives {@value #INTERRUPTED} with the interrupt flag kept.
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

    /** The detail of a credentialed request refused because the HTTP client would log its headers. */
    static final String HEADER_LOGGING = "rfc3161: credentialed requests are refused while HTTP client header logging"
            + " is enabled";

    /** The system property that enables the logging of the JDK {@link HttpClient}. */
    static final String CLIENT_LOG_PROPERTY = "jdk.httpclient.HttpClient.log";

    private static final int HTTP_OK = 200;

    /** How long the idle thread of the deadline scheduler waits for another close before it ends. */
    private static final Duration DEADLINE_THREAD_KEEP_ALIVE = Duration.ofSeconds(60);

    /** The scheduler of the close of a body at its deadline, owned by the transport. */
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlineScheduler();

    private final URI url;
    private final @Nullable String bearer;
    private final HttpClient client;
    private final Duration timeout;
    private final @Nullable Path javaHome;

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
        this(url, bearer, client, REQUEST_TIMEOUT);
    }

    /**
     * Creates the transport with a given deadline, for the tests not to wait {@link #REQUEST_TIMEOUT}.
     *
     * @param url     the URL of the authority
     * @param bearer  the bearer token, or {@code null} for none
     * @param client  the client
     * @param timeout the deadline from sending the request to the end of the reply body, positive
     */
    TimestampTransport(URI url, @Nullable String bearer, HttpClient client, Duration timeout) {
        this(url, bearer, client, timeout, javaHomeOfJvm());
    }

    /**
     * Creates the transport with a given {@code java.home}, for the tests to supply their own
     * {@code conf/net.properties}.
     *
     * @param url      the URL of the authority
     * @param bearer   the bearer token, or {@code null} for none
     * @param client   the client
     * @param timeout  the deadline from sending the request to the end of the reply body, positive
     * @param javaHome the directory whose {@code conf/net.properties} holds the defaults of the networking
     *                 properties, or {@code null} for none
     */
    TimestampTransport(URI url, @Nullable String bearer, HttpClient client, Duration timeout,
            @Nullable Path javaHome) {
        this.url = Objects.requireNonNull(url, "url");
        this.bearer = bearer;
        this.client = Objects.requireNonNull(client, "client");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.javaHome = javaHome;
    }

    private static @Nullable Path javaHomeOfJvm() {
        String home = System.getProperty("java.home");
        return home == null ? null : Path.of(home);
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
     *         {@value #UNREACHABLE} (also for a reply not complete within the deadline) or {@value #INTERRUPTED};
     *         {@link StoreOutcome#REFUSED} for another HTTP status than 200, with {@value #WRONG_CONTENT_TYPE}, with
     *         {@value #REPLY_TOO_LARGE}, or with {@value #HEADER_LOGGING} for a credentialed request while the HTTP
     *         client would log its headers, in which case the authority is not contacted
     */
    StoreResult<byte[]> post(byte[] request) {
        // the JDK HttpClient writes the complete request headers, Authorization included and not redacted, to its
        // logger when jdk.httpclient.HttpClient.log names headers or all (Log and Http1Request of java.net.http,
        // OpenJDK 21 and 25); a bearer token must never reach a log (GDR-NFR-3)
        if (bearer != null
                && headerLoggingEnabled(effectiveClientLog(System.getProperty(CLIENT_LOG_PROPERTY), javaHome))) {
            return StoreResult.of(StoreOutcome.REFUSED, HEADER_LOGGING);
        }
        var builder = HttpRequest.newBuilder(url)
                .timeout(timeout)
                .header("Content-Type", QUERY)
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.clone()));
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            HttpResponse<InputStream> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != HTTP_OK) {
                    return StoreResult.of(StoreOutcome.REFUSED, HTTP_STATUS_PREFIX + response.statusCode());
                }
                if (!isReply(response.headers().firstValue("Content-Type").orElse(""))) {
                    return StoreResult.of(StoreOutcome.REFUSED, WRONG_CONTENT_TYPE);
                }
                return readBody(body, deadline, DEADLINES);
            }
        } catch (IOException e) {
            if (interruption(e)) {
                // ResponseSubscribers.HttpResponseInputStream turns an interrupt of the blocking body read into an
                // IOException caused by the InterruptedException (OpenJDK 21); the interrupt is the caller's
                Thread.currentThread().interrupt();
                return StoreResult.of(StoreOutcome.UNAVAILABLE, INTERRUPTED);
            }
            return StoreResult.of(StoreOutcome.UNAVAILABLE, UNREACHABLE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StoreResult.of(StoreOutcome.UNAVAILABLE, INTERRUPTED);
        }
    }

    /**
     * Reads the reply body within the deadline of the request. {@link HttpRequest.Builder#timeout(Duration)} covers
     * the wait for the response headers only (its Javadoc in OpenJDK 21 and 25: "If the response is not received
     * within the specified timeout"), and a body read through {@link HttpResponse.BodyHandlers#ofInputStream()} blocks
     * as long as the authority stalls; so the body is closed when the deadline passes, which ends the blocked read.
     * <p>
     * The close is scheduled on the given scheduler, in production the transport's own single daemon thread, so a busy
     * common pool cannot delay it, and it is cancelled once the read ends. Exactly one side settles the read: the close,
     * or the end of the read; and a read that ends after the deadline is not accepted even when the close has not run.
     *
     * @param body      the body
     * @param deadline  the deadline in {@link System#nanoTime()}
     * @param scheduler the scheduler of the close at the deadline
     * @return the reply, {@value #REPLY_TOO_LARGE} or {@value #UNREACHABLE} for a body not complete in time
     * @throws IOException if the body cannot be read
     */
    static StoreResult<byte[]> readBody(InputStream body, long deadline, ScheduledExecutorService scheduler)
            throws IOException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            return StoreResult.of(StoreOutcome.UNAVAILABLE, UNREACHABLE);
        }
        var settled = new AtomicBoolean();
        ScheduledFuture<?> close = scheduler.schedule(() -> {
            if (settled.compareAndSet(false, true)) {
                closeQuietly(body);
            }
        }, remaining, TimeUnit.NANOSECONDS);
        byte[] reply;
        try {
            reply = body.readNBytes(MAX_REPLY_BYTES + 1);
        } finally {
            close.cancel(false);
        }
        if (!settled.compareAndSet(false, true) || System.nanoTime() - deadline > 0) {
            // the deadline closed the body while it was read, or passed before the read ended
            return StoreResult.of(StoreOutcome.UNAVAILABLE, UNREACHABLE);
        }
        if (reply.length > MAX_REPLY_BYTES) {
            return StoreResult.of(StoreOutcome.REFUSED, REPLY_TOO_LARGE);
        }
        return StoreResult.ok(reply);
    }

    private static ScheduledThreadPoolExecutor deadlineScheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            var thread = new Thread(task, "rfc3161-deadline");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        // the single thread ends when idle; a pending close keeps it, since a worker leaves only an empty queue
        scheduler.setKeepAliveTime(DEADLINE_THREAD_KEEP_ALIVE.toNanos(), TimeUnit.NANOSECONDS);
        scheduler.allowCoreThreadTimeOut(true);
        return scheduler;
    }

    /**
     * Tells whether an I/O failure is an interrupt of the waiting thread: an {@link InterruptedIOException} other than
     * a socket timeout, or an exception caused by an {@link InterruptedException}.
     */
    private static boolean interruption(IOException failure) {
        if (failure instanceof InterruptedIOException) {
            return !(failure instanceof SocketTimeoutException);
        }
        return failure.getCause() instanceof InterruptedException;
    }

    /**
     * Returns the value of {@value #CLIENT_LOG_PROPERTY} the JDK {@link HttpClient} reads when it initialises its
     * logging: {@code jdk.internal.net.http.common.Log} takes it through {@code sun.net.NetProperties}, which returns
     * the system property when it is set and otherwise the default of {@code conf/net.properties} under
     * {@code java.home} (OpenJDK 21). A missing or unreadable file gives no default.
     *
     * @param systemValue the system property, or {@code null} when it is not set
     * @param javaHome    the {@code java.home} directory, or {@code null} for none
     * @return the effective value, or {@code null} for none
     */
    static @Nullable String effectiveClientLog(@Nullable String systemValue, @Nullable Path javaHome) {
        if (systemValue != null || javaHome == null) {
            return systemValue;
        }
        var defaults = new Properties();
        try (InputStream in = Files.newInputStream(javaHome.resolve("conf").resolve("net.properties"))) {
            defaults.load(in);
        } catch (IOException | IllegalArgumentException e) {
            // a file the JDK cannot read either holds no default
            return null;
        }
        return defaults.getProperty(CLIENT_LOG_PROPERTY);
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException e) {
            // the read that waits on the body ends either way; the outcome is UNAVAILABLE
        }
    }

    /**
     * Tells whether a value of {@value #CLIENT_LOG_PROPERTY} makes the JDK {@link HttpClient} log request headers:
     * one of its comma-separated items is {@code headers} or {@code all}, compared case-insensitively. The JDK
     * compares the items without stripping them; they are stripped here, which can only refuse more.
     *
     * @param value the value of the property, or {@code null} when it is not set
     * @return whether request headers are logged
     */
    static boolean headerLoggingEnabled(@Nullable String value) {
        if (value == null) {
            return false;
        }
        for (String item : value.split(",")) {
            String normalized = item.strip().toLowerCase(Locale.US);
            if ("headers".equals(normalized) || "all".equals(normalized)) {
                return true;
            }
        }
        return false;
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
