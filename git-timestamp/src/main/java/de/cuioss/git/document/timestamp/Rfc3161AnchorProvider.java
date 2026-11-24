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

import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;


import de.cuioss.git.document.store.AnchorProvider;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import de.cuioss.tools.logging.CuiLogger;
import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;

/**
 * The anchor provider {@value #PROVIDER_ID} (GDR-TSA-1, GDR-TSA-2): it obtains an RFC 3161 timestamp token for an
 * object id from the configured timestamp authority, and verifies a stored token.
 * <p>
 * An application builds the configured provider with {@link #from(Map)} from the section {@code timestamp} of the same
 * nested configuration map it hands to {@code StoreConfiguration.from}, and passes it to {@code StoreProviders.select}
 * as a candidate anchor provider; {@code anchor.provider = rfc3161} selects it. The instance
 * {@link java.util.ServiceLoader} creates through the public no-argument constructor is unconfigured and never reaches
 * the network: its {@code obtain} answers {@link StoreOutcome#REFUSED} and its {@code verify}
 * {@link StoreOutcome#SIGNATURE_INVALID}, each with the detail {@value #UNCONFIGURED}. An application that hands the
 * loaded providers to {@code select} as well passes the configured instance in place of the loaded one, since two
 * candidates with one id are refused.
 * <p>
 * The provider decides nothing about which commits are anchored and handles no notes or pushes; that is the anchoring
 * engine of {@code git-store}. No result detail, log message or {@link #toString()} carries the URL of the authority,
 * the bearer token of {@code timestamp.credentials} or the body of a reply (GDR-NFR-3).
 *
 * @since 0.1
 */
public final class Rfc3161AnchorProvider implements AnchorProvider {

    /** The id of the provider, the value of {@code anchor.provider} that selects it. */
    public static final String PROVIDER_ID = "rfc3161";

    /** The detail of an unconfigured instance. */
    static final String UNCONFIGURED = "rfc3161: the section timestamp is not configured";

    /** The detail of a token whose nonce differs from the request. */
    static final String NONCE_MISMATCH = "rfc3161: the token's nonce differs from the request";

    /** The detail of a token whose imprint differs from the request. */
    static final String IMPRINT_DIFFERS = "rfc3161: the token's imprint differs from the request";

    /** The detail of a stored token whose imprint is not that of the subject. */
    static final String IMPRINT_MISMATCH = "rfc3161: the token's imprint is not that of the subject";

    private static final CuiLogger LOGGER = new CuiLogger(Rfc3161AnchorProvider.class);

    private final @Nullable TimestampConfiguration configuration;
    private final @Nullable TimestampTransport transport;
    private final SecureRandom random;

    /**
     * Creates the unconfigured instance {@link java.util.ServiceLoader} needs: it never reaches the network, refuses
     * every {@code obtain} and reports every proof as an invalid signature, each with the detail
     * {@value #UNCONFIGURED}. Use {@link #from(Map)} for a configured provider.
     */
    public Rfc3161AnchorProvider() {
        this.configuration = null;
        this.transport = null;
        this.random = new SecureRandom();
    }

    /**
     * Creates a configured provider.
     *
     * @param configuration the section {@code timestamp}
     * @param transport     the transport to the authority of the configuration
     * @param random        the source of the nonces
     */
    Rfc3161AnchorProvider(TimestampConfiguration configuration, TimestampTransport transport, SecureRandom random) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Builds the configured provider from the section {@code timestamp} of a configuration: {@code timestamp.url}
     * (required, {@code https} without user information), {@code timestamp.trust} (required, a list of paths to PEM
     * files of X.509 certificates, read without following a symbolic link and at most 1 MiB each) and
     * {@code timestamp.credentials} (optional, a bearer token).
     *
     * @param configuration the root of the configuration, the nested map also handed to
     *                      {@code StoreConfiguration.from}
     * @return {@link StoreOutcome#OK} with the provider, or {@link StoreOutcome#MALFORMED} with a detail naming the
     *         key, never its value
     * @throws NullPointerException if {@code configuration} is {@code null}
     */
    public static StoreResult<Rfc3161AnchorProvider> from(Map<String, ?> configuration) {
        return from(configuration, null);
    }

    /**
     * Builds the configured provider on a given HTTP client, for the tests to trust their in-process authority.
     *
     * @param configuration the root of the configuration
     * @param client        the client, or {@code null} for the client of {@link TimestampTransport#clientBuilder()}
     * @return the provider or the refusal of {@link #from(Map)}
     */
    static StoreResult<Rfc3161AnchorProvider> from(Map<String, ?> configuration, @Nullable HttpClient client) {
        var read = TimestampConfiguration.from(configuration);
        TimestampConfiguration section = read.value();
        if (!read.isOk() || section == null) {
            return new StoreResult<>(read.outcome(), null, read.detail());
        }
        var transport = client == null
                ? new TimestampTransport(section.url(), section.credentials())
                : new TimestampTransport(section.url(), section.credentials(), client);
        return StoreResult.ok(new Rfc3161AnchorProvider(section, transport, new SecureRandom()));
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    /**
     * Obtains a timestamp token for the subject from the authority.
     * <p>
     * The request is a {@code TimeStampReq} with the imprint SHA-256 over the object id as lowercase ASCII hex, a
     * random 64-bit nonce and {@code certReq = TRUE}. The commit time and the previous proof are not used: the
     * authority keeps its own time, and {@value #PROVIDER_ID} forms no chain.
     *
     * @param repository the alias of the repository of the subject, for the log record
     * @param subject    the object id of the subject
     * @param commitTime the commit time of the subject, not used
     * @param previous   the previous proof, not used
     * @return {@link StoreOutcome#OK} with the DER encoding of the {@code TimeStampToken};
     *         {@link StoreOutcome#UNAVAILABLE} when the authority cannot be reached; {@link StoreOutcome#REFUSED} for
     *         an error response, another HTTP status than 200, another media type, a reply that cannot be read, a
     *         token whose nonce or imprint differs from the request, or an unconfigured instance
     * @throws NullPointerException if an argument is {@code null}
     */
    @Override
    public StoreResult<byte[]> obtain(String repository, ObjectId subject, Instant commitTime, byte[] previous) {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(commitTime, "commitTime");
        Objects.requireNonNull(previous, "previous");
        TimestampTransport sender = transport;
        if (configuration == null || sender == null) {
            return StoreResult.of(StoreOutcome.REFUSED, UNCONFIGURED);
        }
        var request = TimeStampRequests.request(subject, random);
        StoreResult<byte[]> reply = sender.post(request.encoded());
        byte[] replyBytes = reply.value();
        if (!reply.isOk() || replyBytes == null) {
            return refusal(repository, subject, reply);
        }
        StoreResult<byte[]> token = TimeStampRequests.token(replyBytes);
        byte[] tokenBytes = token.value();
        if (!token.isOk() || tokenBytes == null) {
            return refusal(repository, subject, token);
        }
        TimeStampRequests.TstInfo info;
        try {
            info = TimeStampRequests.tstInfo(tokenBytes);
        } catch (Der.DerException e) {
            return refusal(repository, subject,
                    StoreResult.of(StoreOutcome.REFUSED, TimeStampRequests.REPLY_UNREADABLE));
        }
        if (!request.nonceMatches(info)) {
            return refusal(repository, subject, StoreResult.of(StoreOutcome.REFUSED, NONCE_MISMATCH));
        }
        if (!request.imprintMatches(info)) {
            return refusal(repository, subject, StoreResult.of(StoreOutcome.REFUSED, IMPRINT_DIFFERS));
        }
        LOGGER.info(GitTimestampLogMessages.INFO.TOKEN_OBTAINED, repository, subject.name(), info.genTime());
        return StoreResult.ok(tokenBytes);
    }

    private static StoreResult<byte[]> refusal(String repository, ObjectId subject, StoreResult<byte[]> result) {
        String detail = Objects.requireNonNull(result.detail());
        if (result.outcome() == StoreOutcome.UNAVAILABLE) {
            LOGGER.warn(GitTimestampLogMessages.WARN.AUTHORITY_UNAVAILABLE, repository, subject.name(), detail);
            return StoreResult.of(StoreOutcome.UNAVAILABLE, detail);
        }
        LOGGER.warn(GitTimestampLogMessages.WARN.AUTHORITY_REFUSED, repository, subject.name(), detail);
        return StoreResult.of(StoreOutcome.REFUSED, detail);
    }

    /**
     * Verifies a stored token of the subject against {@code timestamp.trust} (GDR-TSA-2).
     * <p>
     * The token must be signed by a timestamping certificate with a valid path to {@code timestamp.trust} at the time
     * it states; the rules are those of {@link TimeStampTokens}. Its imprint must then be SHA-256 over the object id as
     * lowercase ASCII hex. {@value #PROVIDER_ID} forms no chain, so the previous proof is ignored and
     * {@link StoreOutcome#CHAIN_BROKEN} is never returned.
     *
     * @param subject  the object id of the subject
     * @param proof    the stored token, the DER encoding of the {@code TimeStampToken}
     * @param previous the previous proof, not used
     * @return {@link StoreOutcome#OK} with the time the token states; {@link StoreOutcome#IMPRINT_MISMATCH} with
     *         {@value #IMPRINT_MISMATCH} for a valid token about another object or of another hash algorithm;
     *         {@link StoreOutcome#SIGNATURE_INVALID} for a token that cannot be read, a signature that does not verify,
     *         a signer certificate that is not a timestamping certificate or has no valid path to
     *         {@code timestamp.trust}, or with {@value #UNCONFIGURED} for an unconfigured instance
     * @throws NullPointerException if an argument is {@code null}
     */
    @Override
    public StoreResult<Instant> verify(ObjectId subject, byte[] proof, byte[] previous) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(proof, "proof");
        Objects.requireNonNull(previous, "previous");
        TimestampConfiguration section = configuration;
        if (section == null) {
            return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, UNCONFIGURED);
        }
        StoreResult<TimeStampRequests.TstInfo> verified = TimeStampTokens.verify(proof, section.trust());
        TimeStampRequests.TstInfo info = verified.value();
        if (!verified.isOk() || info == null) {
            return rejected(subject, StoreOutcome.SIGNATURE_INVALID, Objects.requireNonNull(verified.detail()));
        }
        if (!TimeStampRequests.SHA_256.equals(info.hashAlgorithm())
                || !MessageDigest.isEqual(TimeStampRequests.imprint(subject), info.hashedMessage())) {
            return rejected(subject, StoreOutcome.IMPRINT_MISMATCH, IMPRINT_MISMATCH);
        }
        return StoreResult.ok(info.genTime());
    }

    private static StoreResult<Instant> rejected(ObjectId subject, StoreOutcome outcome, String detail) {
        LOGGER.warn(GitTimestampLogMessages.WARN.PROOF_REJECTED, subject.name(), outcome, detail);
        return StoreResult.of(outcome, detail);
    }

    /**
     * Renders the provider without the URL and the token (GDR-NFR-3).
     *
     * @return {@code Rfc3161AnchorProvider[configured=..., transport=...]}
     */
    @Override
    public String toString() {
        return "Rfc3161AnchorProvider[configured=" + (configuration != null) + ", transport=" + transport + "]";
    }
}
