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
package de.cuioss.git.document.store;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RefusingFS;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * The main class of the signature verification (GDR-STORE-14), runnable from CI without the application:
 *
 * <pre>
 * java ... de.cuioss.git.document.store.VerifySignatures &lt;git-dir&gt; &lt;allowed-signers&gt; &lt;reference&gt;...
 * </pre>
 *
 * It opens the repository at {@code <git-dir>} on a {@link RefusingFS}, reads the allowed signers from the file
 * {@code <allowed-signers>} ({@link AllowedSigners#read}), runs {@link SignatureVerification} for each full reference
 * name, and prints one line {@code <commit id> <reason>} per commit without a valid signature to standard output. It
 * exits with
 * <ul>
 * <li>{@code 0} when every commit of every reference is valid,</li>
 * <li>{@code 1} when a commit is not, and</li>
 * <li>{@code 2} for a usage error, a repository that cannot be opened, or any other non-success, printed to standard
 * error as {@code <outcome> <detail>}.</li>
 * </ul>
 * Nothing printed names a key, a principal or a path.
 *
 * @since 0.1
 */
public final class VerifySignatures {

    /** The exit code of a verification without unverified commits. */
    public static final int VERIFIED = 0;

    /** The exit code of a verification that found unverified commits. */
    public static final int UNVERIFIED = 1;

    /** The exit code of a usage error or a non-success. */
    public static final int FAILED = 2;

    /** The usage line printed for a usage error. */
    static final String USAGE = "usage: VerifySignatures <git-dir> <allowed-signers> <reference>...";

    private VerifySignatures() {
    }

    /**
     * Runs the verification and exits the virtual machine with its exit code.
     *
     * @param args {@code <git-dir> <allowed-signers> <reference>...}
     */
    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Runs the verification.
     *
     * @param args {@code <git-dir> <allowed-signers> <reference>...}
     * @param out  where the unverified commits are printed
     * @param err  where a usage error or a non-success is printed
     * @return {@value #VERIFIED}, {@value #UNVERIFIED} or {@value #FAILED}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (args.length < 3) {
            err.println(USAGE);
            return FAILED;
        }
        var allowedSigners = AllowedSigners.read(Path.of(args[1]));
        if (!allowedSigners.isOk()) {
            return failed(err, allowedSigners);
        }
        List<String> references = Arrays.asList(args).subList(2, args.length);
        try (Repository repository = new FileRepositoryBuilder().setGitDir(Path.of(args[0]).toFile())
                     .setFS(new RefusingFS()).setMustExist(true).build()) {
            int code = VERIFIED;
            for (String reference : references) {
                var verified = SignatureVerification.verify(repository, reference,
                        Objects.requireNonNull(allowedSigners.value()));
                if (!verified.isOk()) {
                    return failed(err, verified);
                }
                for (SignatureVerification.UnverifiedCommit commit : Objects.requireNonNull(verified.value())) {
                    out.println(commit.commitId() + " " + commit.reason());
                    code = UNVERIFIED;
                }
            }
            return code;
        } catch (IOException | IllegalArgumentException e) {
            err.println(GitOutcome.NOT_A_REPOSITORY + " git-dir: not a repository");
            return FAILED;
        }
    }

    private static int failed(PrintStream err, StoreResult<?> result) {
        String outcome = switch (result.outcome()) {
            case StoreOutcome store -> store.name();
            case StoreResult.PassedOn(GitOutcome git) -> git.name();
        };
        err.println(outcome + " " + result.detail());
        return FAILED;
    }
}
