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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Objects;
import java.util.Set;


import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.ProcessResult;
import org.eclipse.jgit.util.SystemReader;
import org.jspecify.annotations.Nullable;

/**
 * A JGit file system that never starts a process (GDR-CORE-1).
 * <p>
 * It extends JGit's {@link FS_POSIX} and refuses every process entry point of {@link FS} with
 * {@link CapabilityRefusedException}, which the repository operations report as
 * {@link GitOutcome#REFUSED_BY_POLICY}: repository hooks that are present, shell commands (filter drivers, external
 * diff), and process execution in general. The discovery of the {@code git} executable, which JGit uses to locate the
 * system configuration by running {@code git}, reports none, so the system configuration is never read through a
 * process. An absent hook is reported as not present, as JGit does, so operations of a repository without hooks run
 * unchanged. Setting the execute permission of a file, which JGit's {@link FS_POSIX} does by reading the umask
 * through a shell, is done in process. A refusal names the refused capability, never the refused command or its
 * arguments, which can carry a secret.
 *
 * @since 0.1
 */
public class RefusingFS extends FS_POSIX {

    /** The refused capability of every process entry point. */
    private static final String PROCESS_EXECUTION = "process_execution";

    /** The fixed refusal detail of a process, in place of its command and arguments. */
    private static final String PROCESS = "process";

    /** Creates the refusing file system. */
    public RefusingFS() {
    }

    private RefusingFS(FS src) {
        super(src);
    }

    @Override
    public FS newInstance() {
        return new RefusingFS(this);
    }

    /**
     * Wraps a JGit {@link SystemReader} so that it locates and reads the system, user and JGit configuration through a
     * {@code RefusingFS}, in process.
     * <p>
     * JGit 7.8.0 reads the user configuration, and with it the system configuration, through the process-wide
     * {@link SystemReader#getInstance()} whenever it constructs a {@code FileRepository} or runs {@code InitCommand};
     * the default reader locates the system configuration with {@code FS.DETECTED}, which runs
     * {@code git --version} and {@code git config --system} once per JVM. JGit 7.8.0 offers no way to pass another
     * configuration source to a single repository, so {@link JGitRepositoryOperations} cannot prevent that process for
     * the repositories it opens without changing the process-wide reader, and it does not change it. An application
     * that needs the guarantee for its whole JVM installs the returned reader itself, before any JGit use:
     * {@snippet :
     * SystemReader.setInstance(RefusingFS.systemReader(SystemReader.getInstance()));
     * }
     * With it, the system configuration is skipped by design, because it cannot be located without running
     * {@code git}; the user configuration ({@code ~/.gitconfig} or {@code $XDG_CONFIG_HOME/git/config}) and the JGit
     * configuration are read as before, in process.
     *
     * @param delegate the reader every other answer comes from, usually the current {@link SystemReader#getInstance()}
     * @return a reader that opens the configuration files through a {@code RefusingFS}
     */
    public static SystemReader systemReader(SystemReader delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return new SystemReader.Delegate(delegate) {

            @Override
            public FileBasedConfig openSystemConfig(Config parent, FS fs) {
                return super.openSystemConfig(parent, new RefusingFS());
            }

            @Override
            public FileBasedConfig openUserConfig(Config parent, FS fs) {
                return super.openUserConfig(parent, new RefusingFS());
            }

            @Override
            public FileBasedConfig openJGitConfig(Config parent, FS fs) {
                return super.openJGitConfig(parent, new RefusingFS());
            }
        };
    }

    /**
     * Reports no {@code git} executable, so JGit never runs one to locate the system configuration.
     *
     * @return always {@code null}, JGit's answer for a file system that discovers no executable
     */
    @Override
    protected @Nullable File discoverGitExe() {
        return null;
    }

    @Override
    public ProcessResult runHookIfPresent(Repository repository, String hookName, String[] args,
            OutputStream outRedirect, OutputStream errRedirect, String stdinArgs) {
        return refuseHook(repository, hookName);
    }

    @Override
    protected ProcessResult internalRunHookIfPresent(Repository repository, String hookName, String[] args,
            OutputStream outRedirect, OutputStream errRedirect, String stdinArgs) {
        return refuseHook(repository, hookName);
    }

    private ProcessResult refuseHook(Repository repository, String hookName) {
        if (findHook(repository, hookName) == null) {
            return new ProcessResult(ProcessResult.Status.NOT_PRESENT);
        }
        throw new CapabilityRefusedException("repository_hook", hookName);
    }

    /**
     * Sets or clears the execute permission of a file without starting a process.
     * <p>
     * JGit 7.8.0 {@code FS_POSIX.setExecute(file, true)} derives the group and others execute bits from the umask,
     * which it reads on first use by running {@code sh -c umask} through {@code Runtime.exec}, bypassing every process
     * entry point of {@link FS}. This file system derives them in process instead: the owner may always execute, and
     * the group and others may execute exactly when they may read, which is the umask's effect on the file as it was
     * created. Clearing the permission is JGit's own path, {@link File#setExecutable(boolean, boolean)}, which starts
     * no process either.
     *
     * @param f          the file whose permission is changed
     * @param canExecute {@code true} to grant the execute permission, {@code false} to remove it
     * @return {@code true} if the permission was changed, {@code false} if the file is no regular file or its
     *         permissions cannot be read or written
     */
    @Override
    public boolean setExecute(File f, boolean canExecute) {
        if (!canExecute || !isFile(f)) {
            return super.setExecute(f, canExecute);
        }
        try {
            Path path = f.toPath();
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            mirror(permissions, PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE);
            mirror(permissions, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(path, permissions);
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    private static void mirror(Set<PosixFilePermission> permissions, PosixFilePermission read,
            PosixFilePermission execute) {
        if (permissions.contains(read)) {
            permissions.add(execute);
        } else {
            permissions.remove(execute);
        }
    }

    @Override
    public ProcessBuilder runInShell(String cmd, String[] args) {
        throw new CapabilityRefusedException(PROCESS_EXECUTION, "shell command");
    }

    @Override
    public int runProcess(ProcessBuilder processBuilder, OutputStream outRedirect, OutputStream errRedirect,
            String stdinArgs) {
        throw new CapabilityRefusedException(PROCESS_EXECUTION, PROCESS);
    }

    @Override
    public int runProcess(ProcessBuilder processBuilder, OutputStream outRedirect, OutputStream errRedirect,
            InputStream inRedirect) {
        throw new CapabilityRefusedException(PROCESS_EXECUTION, PROCESS);
    }

    @Override
    public ExecutionResult execute(ProcessBuilder pb, InputStream in) {
        throw new CapabilityRefusedException(PROCESS_EXECUTION, PROCESS);
    }
}
