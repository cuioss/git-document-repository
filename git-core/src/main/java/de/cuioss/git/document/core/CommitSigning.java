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

import java.util.Objects;


import org.eclipse.jgit.lib.Signer;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * The signing of a commit written in core (GDR-CORE-4): the caller's JGit {@link Signer} (since JGit 7.0) and what it
 * is called with. {@code git-core} signs nothing itself.
 * <p>
 * {@link InCoreWriter} calls the signer once per commit, before the commit is inserted, through JGit 7.8.0
 * {@code Signer.signObject(Repository, GpgConfig, ObjectBuilder, PersonIdent, String, CredentialsProvider)}: with the
 * repository, a {@code GpgConfig} read from the repository's configuration, the commit builder, the committer as the
 * signing identity, the signing key and the credentials of this record.
 * <p>
 * The {@code GpgConfig} is read before the signer is called: an invalid {@code gpg.format} of the repository's
 * configuration ends the write as {@link GitOutcome#FAILED} with the class name of JGit's
 * {@link IllegalArgumentException}, and the signer is not called. The signer itself is the caller's code and runs on
 * the caller's contract: a checked exception it throws ({@code IOException} or a JGit {@code GitAPIException}) and a
 * {@code JGitInternalException} end the write as {@link GitOutcome#FAILED} with the class name; any other unchecked
 * exception propagates out of the writer unchanged, and nothing is inserted.
 * <p>
 * {@link #toString()} renders neither the signing key nor the credentials (GDR-NFR-3).
 *
 * @param signer      the signer
 * @param signingKey  the signing key, overriding {@code user.signingKey} of the configuration; {@code null} to use the
 *                    configuration
 * @param credentials the provider the signer asks for key credentials such as a passphrase; {@code null} for none
 * @since 0.1
 */
public record CommitSigning(Signer signer, @Nullable String signingKey, @Nullable CredentialsProvider credentials) {

    /**
     * Checks the components.
     *
     * @param signer      the signer
     * @param signingKey  the signing key, {@code null} to use the configuration
     * @param credentials the credentials provider, {@code null} for none
     * @throws NullPointerException if {@code signer} is {@code null}
     */
    public CommitSigning {
        Objects.requireNonNull(signer, "signer");
    }

    /**
     * Renders whether a signing key and credentials are present, never their values.
     *
     * @return the rendering
     */
    @Override
    public String toString() {
        return "CommitSigning[signingKey=" + (signingKey != null) + ", credentials=" + (credentials != null) + "]";
    }
}
