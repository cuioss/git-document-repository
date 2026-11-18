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
import java.io.InputStream;
import java.io.OutputStream;


import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.FS_POSIX;
import org.eclipse.jgit.util.ProcessResult;
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
 * unchanged.
 *
 * @since 0.1
 */
public class RefusingFS extends FS_POSIX {

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

    @Override
    public ProcessBuilder runInShell(String cmd, String[] args) {
        throw new CapabilityRefusedException("process_execution", "shell command");
    }

    @Override
    public int runProcess(ProcessBuilder processBuilder, OutputStream outRedirect, OutputStream errRedirect,
            String stdinArgs) {
        throw new CapabilityRefusedException("process_execution", String.valueOf(processBuilder.command()));
    }

    @Override
    public int runProcess(ProcessBuilder processBuilder, OutputStream outRedirect, OutputStream errRedirect,
            InputStream inRedirect) {
        throw new CapabilityRefusedException("process_execution", String.valueOf(processBuilder.command()));
    }

    @Override
    public ExecutionResult execute(ProcessBuilder pb, InputStream in) {
        throw new CapabilityRefusedException("process_execution", String.valueOf(pb.command()));
    }
}
