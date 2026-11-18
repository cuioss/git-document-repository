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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;


import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.ProcessResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

@EnableGeneratorController
@DisplayName("RefusingFS (GDR-CORE-1)")
class RefusingFSTest {

    /** The name of the hook git runs before a commit: part of git's hook contract. */
    private static final String PRE_COMMIT = "pre-commit";

    /** The value JGit 7.8.0 {@link FS_POSIX} keeps in its umask field until it has read the umask. */
    private static final int UMASK_NOT_READ = -1;

    private final RefusingFS fs = new RefusingFS();

    private static String word() {
        return Generators.letterStrings(1, 12).next();
    }

    /** A word too long to occur by chance in a refusal message. */
    private static String longWord() {
        return Generators.letterStrings(24, 32).next();
    }

    @Test
    @DisplayName("GDR-CORE-1: refuses every process entry point (the refusal reason and prefix are the contract)")
    void refusesProcesses() {
        var builder = new ProcessBuilder(word(), word());
        var out = new ByteArrayOutputStream();
        var input = word();
        var command = word();
        var noArgs = new String[0];
        var emptyStream = new ByteArrayInputStream(new byte[0]);

        var shell = assertThrows(CapabilityRefusedException.class, () -> fs.runInShell(command, noArgs),
                "a shell command is refused");

        assertAll(
                () -> assertThrows(CapabilityRefusedException.class,
                        () -> fs.runProcess(builder, out, out, input), "a process with text input is refused"),
                () -> assertThrows(CapabilityRefusedException.class,
                        () -> fs.runProcess(builder, out, out, emptyStream),
                        "a process with stream input is refused"),
                () -> assertThrows(CapabilityRefusedException.class,
                        () -> fs.execute(builder, emptyStream),
                        "an executed process is refused"),
                () -> assertEquals("process_execution", shell.getReason(), "the refusal names process execution"),
                () -> assertTrue(shell.getMessage().startsWith("refused_by_policy{reason: process_execution}"),
                        "the message starts with the outcome code and the refused capability"));
    }

    @Test
    @DisplayName("GDR-CORE-1: a refusal names no command and no argument, which can carry a secret")
    void refusalCarriesNoCommandArgument() {
        var command = longWord();
        var secret = longWord();
        var builder = new ProcessBuilder(command, secret);
        var out = new ByteArrayOutputStream();
        var emptyStream = new ByteArrayInputStream(new byte[0]);
        var shellArgs = new String[]{secret};
        var textInput = word();

        var refusals = List.of(
                assertThrows(CapabilityRefusedException.class, () -> fs.runInShell(command, shellArgs),
                        "a shell command is refused"),
                assertThrows(CapabilityRefusedException.class, () -> fs.runProcess(builder, out, out, textInput),
                        "a process with text input is refused"),
                assertThrows(CapabilityRefusedException.class, () -> fs.runProcess(builder, out, out, emptyStream),
                        "a process with stream input is refused"),
                assertThrows(CapabilityRefusedException.class, () -> fs.execute(builder, emptyStream),
                        "an executed process is refused"));

        assertAll(refusals.stream().<Executable>map(refusal -> () -> assertAll(
                () -> assertEquals("process_execution", refusal.getReason(), "the refusal names process execution"),
                () -> assertFalse(refusal.getMessage().contains(command), "the message names no command"),
                () -> assertFalse(refusal.getMessage().contains(secret), "the message names no argument"))));
    }

    @Test
    @DisplayName("GDR-CORE-1: grants the execute permission in process, without reading the umask through a shell")
    void setExecuteStartsNoProcess(@TempDir Path directory) throws Exception {
        assumePosix();
        var ownerOnly = fileWithMode(directory.resolve("owner-" + word()), "rw-------");
        var readable = fileWithMode(directory.resolve("readable-" + word()), "rw-r--r--");

        assertAll(
                () -> assertTrue(fs.setExecute(ownerOnly.toFile(), true), "the owner-only file is made executable"),
                () -> assertTrue(fs.setExecute(readable.toFile(), true), "the readable file is made executable"));

        assertAll(
                () -> assertEquals(PosixFilePermissions.fromString("rwx------"),
                        Files.getPosixFilePermissions(ownerOnly), "only who may read may execute: the owner"),
                () -> assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"),
                        Files.getPosixFilePermissions(readable), "only who may read may execute: everyone"),
                () -> assertEquals(UMASK_NOT_READ, readUmaskField(),
                        "the umask JGit reads through sh -c umask is never read"));
    }

    @Test
    @DisplayName("GDR-CORE-1: clears the execute permission and leaves a missing file alone")
    void clearExecute(@TempDir Path directory) throws Exception {
        assumePosix();
        var executable = fileWithMode(directory.resolve("executable-" + word()), "rwxr-xr-x");
        var missing = directory.resolve("missing-" + word()).toFile();

        assertAll(
                () -> assertTrue(fs.setExecute(executable.toFile(), false), "the execute permission is cleared"),
                () -> assertFalse(fs.setExecute(missing, true), "a missing file is not made executable"));

        assertAll(
                () -> assertEquals(PosixFilePermissions.fromString("rw-r--r--"),
                        Files.getPosixFilePermissions(executable), "nobody may execute the file any more"),
                () -> assertEquals(UMASK_NOT_READ, readUmaskField(), "the umask is never read"));
    }

    @Test
    @DisplayName("GDR-CORE-1: finds no git executable and stays refusing when copied")
    void noGitExecutable() {
        assertAll(
                () -> assertNull(fs.discoverGitExe(), "no git executable is discovered"),
                () -> assertInstanceOf(RefusingFS.class, fs.newInstance(), "a copy of the file system still refuses"));
    }

    @Test
    @DisplayName("GDR-CORE-1: reports an absent hook as not present on both entry points")
    void absentHook(@LocalRepository Repository repository) throws Exception {
        useLocalHooksDirectory(repository);
        var out = new ByteArrayOutputStream();

        ProcessResult viaPublic = fs.runHookIfPresent(repository, PRE_COMMIT, new String[0], out, out, null);
        ProcessResult viaInternal = fs.internalRunHookIfPresent(repository, PRE_COMMIT, new String[0], out, out,
                null);

        assertAll(
                () -> assertEquals(ProcessResult.Status.NOT_PRESENT, viaPublic.getStatus(),
                        "the public entry finds no hook"),
                () -> assertEquals(ProcessResult.Status.NOT_PRESENT, viaInternal.getStatus(),
                        "the internal entry finds no hook"),
                () -> assertFalse(viaPublic.isExecutedWithError(), "an absent hook reports no execution error"));
    }

    @Test
    @DisplayName("GDR-CORE-1: refuses a present hook on both entry points instead of running it")
    void presentHook(@LocalRepository Repository repository) throws Exception {
        assumePosix();
        installHook(useLocalHooksDirectory(repository), PRE_COMMIT);
        var out = new ByteArrayOutputStream();
        var noArgs = new String[0];

        var viaPublic = assertThrows(CapabilityRefusedException.class,
                () -> fs.runHookIfPresent(repository, PRE_COMMIT, noArgs, out, out, null),
                "the public entry refuses the present hook");
        var viaInternal = assertThrows(CapabilityRefusedException.class,
                () -> fs.internalRunHookIfPresent(repository, PRE_COMMIT, noArgs, out, out, null),
                "the internal entry refuses the present hook");

        assertAll(
                () -> assertEquals("repository_hook", viaPublic.getReason(), "the refusal names the hook capability"),
                () -> assertEquals("repository_hook", viaInternal.getReason(),
                        "the internal refusal names the hook capability"),
                () -> assertTrue(viaPublic.getMessage().endsWith(PRE_COMMIT), "the message names the refused hook"),
                () -> assertEquals(0, out.size(), "nothing of the hook ran"));
    }

    /**
     * Points the repository's {@code core.hooksPath} at an empty directory of its own, so neither the developer's nor
     * the system's {@code core.hooksPath} decides which hooks the test finds.
     *
     * @param repository the repository whose hooks directory is set
     * @return the repository-local hooks directory, empty
     * @throws IOException if the directory cannot be created or the configuration cannot be saved
     */
    private static Path useLocalHooksDirectory(Repository repository) throws IOException {
        Path hooks = Files.createDirectories(repository.getDirectory().toPath().resolve(Constants.HOOKS));
        StoredConfig config = repository.getConfig();
        config.setString(ConfigConstants.CONFIG_CORE_SECTION, null, ConfigConstants.CONFIG_KEY_HOOKS_PATH,
                hooks.toAbsolutePath().toString());
        config.save();
        return hooks;
    }

    /**
     * Places an executable hook script in a hooks directory.
     *
     * @param hooks    the hooks directory receiving the hook
     * @param hookName the hook's name
     * @throws IOException if the hook cannot be written
     */
    private static void installHook(Path hooks, String hookName) throws IOException {
        Path hook = hooks.resolve(hookName);
        Files.writeString(hook, "#!/bin/sh\necho " + word() + "\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    /**
     * Creates a file with exactly the given mode. The mode is set after the creation: a mode passed to the creation
     * loses the bits the process umask masks, so under {@code 077} the group and others bits would be missing.
     *
     * @param file the file to create
     * @param mode the mode, e.g. {@code rw-r--r--}
     * @return the created file
     * @throws IOException if the file cannot be created or its mode set
     */
    private static Path fileWithMode(Path file, String mode) throws IOException {
        Files.createFile(file);
        return Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(mode));
    }

    private static void assumePosix() {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
                "The default file system supports the posix file attribute view");
    }

    /**
     * Reads the umask JGit 7.8.0 {@link FS_POSIX} caches after reading it through {@code sh -c umask}.
     *
     * @return the cached umask, {@link #UMASK_NOT_READ} as long as it was never read
     * @throws ReflectiveOperationException if JGit no longer declares the field
     */
    private int readUmaskField() throws ReflectiveOperationException {
        Field umask = FS_POSIX.class.getDeclaredField("umask");
        umask.setAccessible(true);
        return umask.getInt(fs);
    }
}
