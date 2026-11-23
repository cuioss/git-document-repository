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
package de.cuioss.git.document.core;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;


import org.eclipse.jgit.lib.GpgConfig;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.Signer;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * A JGit {@link Signer} for tests (checked against JGit 7.8.0): it signs the raw bytes it is handed with a
 * deterministic, secret-free scheme, the SHA-256 digest of the fixed key name {@value #KEY} followed by the payload,
 * and verifies a commit against its {@code gpgsig} header. It proves which bytes were signed and that the signature
 * reached the commit, nothing about cryptographic strength.
 * <p>
 * Every call to {@link #sign(Repository, GpgConfig, byte[], PersonIdent, String, CredentialsProvider)} is recorded, so a
 * test can compare the signed payload with the payload it reconstructs from the commit, and see the identity and key
 * the signer was called with.
 * <p>
 * It stays a hand-written fake instead of an EasyMock double: it computes a signature that the written commit carries
 * and that {@link #verify(RevCommit)} checks against the commit's own bytes, which an expectation-based double does not
 * produce. A signer that only fails is an EasyMock double in the test that needs it.
 */
final class TestSigner implements Signer {

    /** The fixed name of the test key: part of the signed bytes, no secret. */
    static final String KEY = "git-core-test-key";

    /**
     * The start of the header a signed commit carries its signature in, continued on the following lines that start
     * with a space: part of git's commit format.
     */
    private static final byte[] GPGSIG_HEADER = "gpgsig ".getBytes(US_ASCII);

    private final List<byte[]> payloads = new ArrayList<>();
    private final List<PersonIdent> committers = new ArrayList<>();
    private final List<Optional<String>> signingKeys = new ArrayList<>();

    private TestSigner() {
        // created by signing() only
    }

    /**
     * Creates a signer that signs every payload.
     *
     * @return the signer
     */
    static TestSigner signing() {
        return new TestSigner();
    }

    @Override
    public GpgSignature sign(Repository repository, GpgConfig config, byte[] data, PersonIdent committer,
            @Nullable String signingKey, @Nullable CredentialsProvider credentialsProvider) {
        payloads.add(data.clone());
        committers.add(committer);
        signingKeys.add(Optional.ofNullable(signingKey));
        return new GpgSignature(signature(data).getBytes(US_ASCII));
    }

    @Override
    public boolean canLocateSigningKey(Repository repository, GpgConfig config, PersonIdent committer,
            @Nullable String signingKey, @Nullable CredentialsProvider credentialsProvider) {
        return true;
    }

    /**
     * Verifies a commit: its {@code gpgsig} header must be this scheme's signature of the commit without that header.
     *
     * @param commit the parsed commit
     * @return whether the commit carries a valid test signature; {@code false} for an unsigned commit
     */
    static boolean verify(RevCommit commit) {
        byte[] raw = commit.getRawGpgSignature();
        if (raw == null) {
            return false;
        }
        return signature(signedPayload(commit)).strip().equals(new String(raw, US_ASCII).strip());
    }

    /**
     * Reconstructs the bytes a commit's signature covers: the raw commit with its {@code gpgsig} header, continuation
     * lines included, removed. The header section ends at the first empty line, so a message line is never taken for
     * a header.
     *
     * @param commit the parsed commit
     * @return the signed payload; the raw commit when it carries no signature
     */
    static byte[] signedPayload(RevCommit commit) {
        byte[] raw = commit.getRawBuffer();
        var payload = new ByteArrayOutputStream(raw.length);
        boolean inSignature = false;
        int line = 0;
        while (line < raw.length) {
            if (raw[line] == '\n') {
                // the empty line ends the headers; the message follows unchanged
                payload.write(raw, line, raw.length - line);
                break;
            }
            int next = nextLine(raw, line);
            if (startsWith(raw, line, GPGSIG_HEADER)) {
                inSignature = true;
            } else if (raw[line] != ' ') {
                // a line not starting with a space is the next header, not a continuation of the signature
                inSignature = false;
            }
            if (!inSignature) {
                payload.write(raw, line, next - line);
            }
            line = next;
        }
        return payload.toByteArray();
    }

    private static int nextLine(byte[] raw, int start) {
        for (int i = start; i < raw.length; i++) {
            if (raw[i] == '\n') {
                return i + 1;
            }
        }
        return raw.length;
    }

    private static boolean startsWith(byte[] raw, int start, byte[] prefix) {
        return raw.length - start >= prefix.length
                && Arrays.equals(raw, start, start + prefix.length, prefix, 0, prefix.length);
    }

    /**
     * Returns copies of the payloads signed so far, in order.
     *
     * @return the signed payloads
     */
    List<byte[]> payloads() {
        return payloads.stream().map(byte[]::clone).toList();
    }

    /**
     * Returns the identities the signer was called with, in order.
     *
     * @return the signing identities
     */
    List<PersonIdent> committers() {
        return List.copyOf(committers);
    }

    /**
     * Returns the signing keys the signer was called with, in order; empty where it was called with none.
     *
     * @return the signing keys
     */
    List<Optional<String>> signingKeys() {
        return List.copyOf(signingKeys);
    }

    /** The signature of a payload: a multi-line block, so the {@code gpgsig} header spans continuation lines. */
    private static String signature(byte[] payload) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY.getBytes(US_ASCII));
            digest.update((byte) 0);
            digest.update(payload);
            return "-----BEGIN TEST SIGNATURE-----\nkey " + KEY + "\nsha256 "
                    + HexFormat.of().formatHex(digest.digest()) + "\n-----END TEST SIGNATURE-----\n";
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every Java platform provides SHA-256", e);
        }
    }
}
