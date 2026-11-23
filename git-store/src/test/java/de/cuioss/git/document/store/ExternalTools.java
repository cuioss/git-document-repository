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

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;


import org.jspecify.annotations.Nullable;

/**
 * The external tools some tests cross-check the store against ({@code ssh-keygen}, {@code git}): finding an executable
 * on the {@code PATH}, running a process with a deadline, and probing the selected {@code ssh-keygen} for the
 * operations a test needs, so a test skips only when the tool really lacks them.
 * <p>
 * A process writes its standard output and standard error into a file, never into a pipe, so a child that keeps a
 * pipe open cannot block the test before its deadline; a process that outlives the deadline is destroyed and fails the
 * test, and a process still running when the wait ends otherwise, for example by an interrupt, is destroyed as well. A
 * probe generates its key with {@code ssh-keygen -t ed25519} and makes its signature with {@code ssh-keygen -Y sign}
 * itself, so its verdict says what the tool can do and never depends on the encoders under test.
 */
final class ExternalTools {

    /** The deadline of every process. */
    static final Duration DEADLINE = Duration.ofSeconds(60);

    private static final String PRINCIPAL = "probe@example.invalid";

    private ExternalTools() {
    }

    /**
     * The exit code and the output of a finished process.
     *
     * @param code   the exit code
     * @param output standard output and standard error, decoded as UTF-8
     */
    record Finished(int code, String output) {
    }

    /**
     * Finds an executable on the {@code PATH}.
     *
     * @param name the name of the executable
     * @return its path, or empty when no directory of the {@code PATH} holds it
     */
    static Optional<Path> onPath(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        return Stream.of(path.split(File.pathSeparator)).filter(entry -> !entry.isBlank())
                .map(entry -> Path.of(entry, name)).filter(Files::isExecutable).findFirst();
    }

    /**
     * Runs a process to its end: its output goes to a file, its standard input is the given file or closed, and a
     * process that outlives {@link #DEADLINE} is destroyed and fails the test.
     *
     * @param builder the process, its command, directory and environment set
     * @param output  the file its standard output and standard error are written to
     * @param input   the file its standard input is read from; {@code null} for none
     * @return the exit code and the output, read after the process ended
     * @throws IOException          if the process cannot be started or its output not read
     * @throws InterruptedException if the wait is interrupted
     */
    static Finished run(ProcessBuilder builder, Path output, @Nullable Path input)
            throws IOException, InterruptedException {
        builder.redirectErrorStream(true).redirectOutput(output.toFile());
        if (input != null) {
            builder.redirectInput(input.toFile());
        }
        Process process = builder.start();
        try {
            if (input == null) {
                process.getOutputStream().close();
            }
            if (!process.waitFor(DEADLINE.toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(DEADLINE.toSeconds(), TimeUnit.SECONDS);
                fail("the process " + builder.command().getFirst() + " did not end within " + DEADLINE);
            }
        } finally {
            // an interrupted wait or a failed close leaves no child running past the test
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
        return new Finished(process.exitValue(), new String(Files.readAllBytes(output), UTF_8));
    }

    /**
     * Whether the file system of a directory supports POSIX permissions, which {@code ssh-keygen} checks on a private
     * key file.
     *
     * @param directory the directory
     * @return whether it has a {@link PosixFileAttributeView}
     */
    static boolean posix(Path directory) {
        return Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null;
    }

    /**
     * Whether an {@code ssh-keygen} signs and verifies SSH signatures ({@code -Y sign}, {@code -Y verify}), as OpenSSH
     * 8.0 and later does.
     *
     * @param keygen    the executable
     * @param directory a directory for the probe's files
     * @return whether it verifies a signature it made itself
     * @throws IOException          if a file of the probe cannot be written or read
     * @throws InterruptedException if a wait is interrupted
     */
    static boolean verifiesSignatures(Path keygen, Path directory) throws IOException, InterruptedException {
        return probe(keygen, directory, false);
    }

    /**
     * Whether an {@code ssh-keygen} also finds the principals of a signature ({@code -Y find-principals}) and checks
     * the {@code valid-before} of a key at a given time ({@code -Overify-time}), as OpenSSH 8.8 and later does and Git
     * 2.35 and later relies on.
     *
     * @param keygen    the executable
     * @param directory a directory for the probe's files
     * @return whether it finds the principal and honours the lifetime at the verify time, accepting inside and
     *         rejecting after it
     * @throws IOException          if a file of the probe cannot be written or read
     * @throws InterruptedException if a wait is interrupted
     */
    static boolean verifiesWithLifetimes(Path keygen, Path directory) throws IOException, InterruptedException {
        return probe(keygen, directory, true);
    }

    private static boolean probe(Path keygen, Path directory, boolean lifetimes)
            throws IOException, InterruptedException {
        if (!posix(directory)) {
            return false;
        }
        Path dir = Files.createTempDirectory(directory, "probe");
        Path keyFile = dir.resolve("key");
        // the probed tool generates its own key, so a fault of the store's key encoding cannot make the probe fail
        var generated = run(new ProcessBuilder(keygen.toString(), "-t", "ed25519", "-N", "", "-q", "-f",
                keyFile.toString()), dir.resolve("keygen.out"), null);
        Path publicKeyFile = dir.resolve("key.pub");
        if (generated.code() != 0 || !Files.exists(keyFile) || !Files.exists(publicKeyFile)) {
            return false;
        }
        Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        String publicKeyLine = Files.readString(publicKeyFile, US_ASCII).strip();
        Path message = Files.writeString(dir.resolve("message"), "probe\n", US_ASCII);
        var signed = run(new ProcessBuilder(keygen.toString(), "-Y", "sign", "-f", keyFile.toString(), "-n", "git",
                message.toString()), dir.resolve("sign.out"), null);
        Path signature = dir.resolve("message.sig");
        if (signed.code() != 0 || !Files.exists(signature)) {
            return false;
        }
        // timestamps without the suffix Z, which OpenSSH 8.8 and 8.9 refuse; local time, days apart from each other
        String options = lifetimes ? "valid-before=\"20200101\" " : "";
        Path allowed = Files.writeString(dir.resolve("allowed"), PRINCIPAL + " " + options + publicKeyLine + "\n",
                US_ASCII);
        if (!lifetimes) {
            return verify(keygen, dir, allowed, signature, message, null).code() == 0;
        }
        var found = run(new ProcessBuilder(keygen.toString(), "-Y", "find-principals", "-s", signature.toString(),
                "-f", allowed.toString(), "-Overify-time=20190601"), dir.resolve("find.out"), null);
        return found.code() == 0 && found.output().contains(PRINCIPAL)
                && verify(keygen, dir, allowed, signature, message, "20190601").code() == 0
                && verify(keygen, dir, allowed, signature, message, "20200601").code() != 0;
    }

    private static Finished verify(Path keygen, Path dir, Path allowed, Path signature, Path message,
            @Nullable String time) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(keygen.toString(), "-Y", "verify", "-f", allowed.toString(),
                "-I", PRINCIPAL, "-n", "git", "-s", signature.toString()));
        if (time != null) {
            command.add("-Overify-time=" + time);
        }
        return run(new ProcessBuilder(command), Files.createTempFile(dir, "verify", ".out"), message);
    }
}
