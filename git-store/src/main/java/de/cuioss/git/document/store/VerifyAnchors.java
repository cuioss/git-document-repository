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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.RefusingFS;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * The main class of the anchor verification of the provider {@value SignedAnchorProvider#PROVIDER_ID} (GDR-STORE-17,
 * GDR-STORE-18), runnable from CI without the application:
 *
 * <pre>
 * java ... de.cuioss.git.document.store.VerifyAnchors &lt;git-dir&gt; &lt;anchor-allowed-signers&gt;
 * </pre>
 *
 * It opens the repository at {@code <git-dir>} on a {@link RefusingFS}, reads the anchor allowed signers from the file
 * {@code <anchor-allowed-signers>} ({@link AllowedSigners#read}), runs {@link AnchorVerification#verifyAnchors} with
 * {@link SignedAnchorProvider#verifier(AllowedSigners)}, and prints one line per finding to standard output:
 * {@code <kind> <subject>}, followed by the listed reference for a reference finding and by the outcome for a rejected
 * proof. It exits with
 * <ul>
 * <li>{@code 0} when the anchoring is complete and every proof verifies,</li>
 * <li>{@code 1} when there is a finding, and</li>
 * <li>{@code 2} for a usage error, an argument that is no valid path, a repository that cannot be opened, or any other
 * non-success, printed to standard error as {@code <outcome> <detail>}; a repository with a shallow boundary is such a
 * non-success ({@link StoreOutcome#INCOMPLETE}).</li>
 * </ul>
 * Nothing printed names a key, a principal or a path. Standard output and standard error are written in UTF-8 and
 * flushed per line, as {@link VerifySignatures} does.
 * <p>
 * The anchor allowed-signers file must come from the trusted CI configuration, never from the checkout of the
 * repository being verified: a file taken from that checkout would let a change of the repository add a trusted key.
 *
 * @since 0.1
 */
public final class VerifyAnchors {

    /** The exit code of a verification without findings. */
    public static final int VERIFIED = 0;

    /** The exit code of a verification with findings. */
    public static final int FINDINGS = 1;

    /** The exit code of a usage error or a non-success. */
    public static final int FAILED = 2;

    /** The usage line printed for a usage error. */
    static final String USAGE = "usage: VerifyAnchors <git-dir> <anchor-allowed-signers>"
            + " (anchor-allowed-signers from the trusted CI configuration, never from the checkout under verification)";

    /** The diagnostic of an argument that is no valid path; it names neither the argument nor the path. */
    static final String INVALID_PATH = "MALFORMED arguments: not a valid path";

    private VerifyAnchors() {
    }

    /**
     * Runs the verification and exits the virtual machine with its exit code.
     *
     * @param args {@code <git-dir> <anchor-allowed-signers>}
     */
    public static void main(String[] args) {
        int code;
        try (var out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
             var err = new PrintStream(new FileOutputStream(FileDescriptor.err), true, UTF_8)) {
            code = run(args, out, err);
        }
        System.exit(code);
    }

    /**
     * Runs the verification.
     *
     * @param args {@code <git-dir> <anchor-allowed-signers>}
     * @param out  where the findings are printed
     * @param err  where a usage error or a non-success is printed
     * @return {@value #VERIFIED}, {@value #FINDINGS} or {@value #FAILED}
     * @throws NullPointerException if an argument is {@code null}
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Objects.requireNonNull(args, "args");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
        if (args.length != 2) {
            err.println(USAGE);
            return FAILED;
        }
        Path gitDir;
        Path allowedSignersFile;
        try {
            gitDir = Path.of(args[0]);
            allowedSignersFile = Path.of(args[1]);
        } catch (InvalidPathException e) {
            err.println(INVALID_PATH);
            return FAILED;
        }
        var allowedSigners = AllowedSigners.read(allowedSignersFile);
        if (!allowedSigners.isOk()) {
            return failed(err, allowedSigners);
        }
        try (Repository repository = new FileRepositoryBuilder().setGitDir(gitDir.toFile())
                     .setFS(new RefusingFS()).setMustExist(true).build()) {
            var verified = AnchorVerification.verifyAnchors(repository,
                    SignedAnchorProvider.verifier(Objects.requireNonNull(allowedSigners.value())));
            if (!verified.isOk()) {
                return failed(err, verified);
            }
            var report = Objects.requireNonNull(verified.value());
            for (AnchorVerification.Finding finding : report.findings()) {
                out.println(line(finding));
            }
            return report.clean() ? VERIFIED : FINDINGS;
        } catch (IOException | IllegalArgumentException e) {
            err.println(GitOutcome.NOT_A_REPOSITORY + " git-dir: not a repository");
            return FAILED;
        }
    }

    /**
     * Renders one finding as a line of the output.
     *
     * @param finding the finding
     * @return {@code <kind> <subject>}, then the reference or the outcome when the finding carries one
     */
    static String line(AnchorVerification.Finding finding) {
        var line = new StringBuilder(finding.kind().name()).append(' ').append(finding.subject());
        String reference = finding.reference();
        if (reference != null) {
            line.append(' ').append(reference);
        }
        StoreOutcome outcome = finding.outcome();
        if (outcome != null) {
            line.append(' ').append(outcome.name());
        }
        return line.toString();
    }

    private static int failed(PrintStream err, StoreResult<?> result) {
        err.println(StoreResult.name(result.outcome()) + " " + result.detail());
        return FAILED;
    }
}
