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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;


import de.cuioss.git.document.testing.LocalRepository;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.ProcessResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@EnableGeneratorController
@DisplayName("RefusingFS (GDR-CORE-1)")
class RefusingFSTest {

    /** The name of the hook git runs before a commit: part of git's hook contract. */
    private static final String PRE_COMMIT = "pre-commit";

    private final RefusingFS fs = new RefusingFS();

    private static String word() {
        return Generators.letterStrings(1, 12).next();
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
    @DisplayName("GDR-CORE-1: finds no git executable and stays refusing when copied")
    void noGitExecutable() {
        assertAll(
                () -> assertNull(fs.discoverGitExe(), "no git executable is discovered"),
                () -> assertInstanceOf(RefusingFS.class, fs.newInstance(), "a copy of the file system still refuses"));
    }

    @Test
    @DisplayName("GDR-CORE-1: reports an absent hook as not present on both entry points")
    void absentHook(@LocalRepository Repository repository) {
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
        installHook(repository, PRE_COMMIT);
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
     * Places an executable hook script in the repository's hooks directory.
     *
     * @param repository the repository receiving the hook
     * @param hookName   the hook's name
     * @throws IOException if the hook cannot be written
     */
    private static void installHook(Repository repository, String hookName) throws IOException {
        Path hooks = repository.getDirectory().toPath().resolve(Constants.HOOKS);
        Files.createDirectories(hooks);
        Path hook = hooks.resolve(hookName);
        Files.writeString(hook, "#!/bin/sh\necho " + word() + "\n");
        Files.setPosixFilePermissions(hook, PosixFilePermissions.fromString("rwxr-xr-x"));
    }
}
