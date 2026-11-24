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

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.RepositoryOperations;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.jspecify.annotations.Nullable;

/**
 * A hand-written test double of {@link RepositoryOperations}: it delegates to {@link JGitRepositoryOperations} under
 * the local-only policy, records the inputs of every clone, fetch and push, and can script the next results of fetch
 * and push, for example {@link GitOutcome#CREDENTIALS_INVALID}, {@link GitOutcome#UNREACHABLE} or
 * {@link GitOutcome#LEASE_REFUSED}, also for the push of one named remote reference only, or run an action right before
 * the next push, such as a concurrent writer moving the remote branch.
 * <p>
 * A scripted result is returned instead of calling the delegate, so nothing is sent. The double is safe for use by
 * several threads.
 */
final class ScriptedRepositoryOperations implements RepositoryOperations {

    /** The detail of every scripted result. */
    static final String SCRIPTED = "scripted";

    private final RepositoryOperations delegate = new JGitRepositoryOperations(RemotePolicy.localOnly());
    private final Deque<GitOutcome> fetchScript = new ArrayDeque<>();
    private final Deque<GitOutcome> pushScript = new ArrayDeque<>();
    private final Deque<Runnable> beforePush = new ArrayDeque<>();
    private final Map<String, Deque<GitOutcome>> pushScriptByRef = new HashMap<>();
    private final Map<String, Deque<Consumer<PushInput>>> beforePushByRef = new HashMap<>();
    private final List<CloneInput> clones = new ArrayList<>();
    private final List<FetchInput> fetches = new ArrayList<>();
    private final List<PushInput> pushes = new ArrayList<>();

    /**
     * Scripts the result of the next fetches, one per outcome, in order.
     *
     * @param outcomes the outcomes, each a non-success
     * @return this double
     */
    synchronized ScriptedRepositoryOperations failFetch(GitOutcome... outcomes) {
        fetchScript.addAll(List.of(outcomes));
        return this;
    }

    /**
     * Scripts the result of the next pushes, one per outcome, in order.
     *
     * @param outcomes the outcomes, each a non-success
     * @return this double
     */
    synchronized ScriptedRepositoryOperations failPush(GitOutcome... outcomes) {
        pushScript.addAll(List.of(outcomes));
        return this;
    }

    /**
     * Scripts the result of the next pushes to one remote reference, one per outcome, in order; such a scripted result
     * is taken before one of {@link #failPush(GitOutcome...)}, and the push of every other reference is unaffected.
     *
     * @param remoteRef the full name of the remote reference, for example {@code refs/heads/main} or a tag
     * @param outcomes  the outcomes, each a non-success
     * @return this double
     */
    synchronized ScriptedRepositoryOperations failPushTo(String remoteRef, GitOutcome... outcomes) {
        pushScriptByRef.computeIfAbsent(remoteRef, ref -> new ArrayDeque<>()).addAll(List.of(outcomes));
        return this;
    }

    /**
     * Runs an action right before the next push reaches the delegate, once.
     *
     * @param action the action
     * @return this double
     */
    synchronized ScriptedRepositoryOperations beforeNextPush(Runnable action) {
        beforePush.add(action);
        return this;
    }

    /**
     * Runs an action with the input of the next push to one remote reference, right before it reaches the delegate,
     * once; such an action is taken before one of {@link #beforeNextPush(Runnable)}, and the push of every other
     * reference is unaffected.
     *
     * @param remoteRef the full name of the remote reference, for example a release tag
     * @param action    the action, given the input of the push
     * @return this double
     */
    synchronized ScriptedRepositoryOperations beforePushTo(String remoteRef, Consumer<PushInput> action) {
        beforePushByRef.computeIfAbsent(remoteRef, ref -> new ArrayDeque<>()).add(action);
        return this;
    }

    /** @return the inputs of every fetch, scripted ones included, in order */
    synchronized List<FetchInput> fetches() {
        return List.copyOf(fetches);
    }

    /** @return the inputs of every push, scripted ones included, in order */
    synchronized List<PushInput> pushes() {
        return List.copyOf(pushes);
    }

    /** @return the credentials of every clone, fetch and push, in that order of the kinds */
    synchronized List<@Nullable CredentialsProvider> credentials() {
        List<@Nullable CredentialsProvider> all = new ArrayList<>();
        clones.forEach(input -> all.add(input.credentials()));
        fetches.forEach(input -> all.add(input.credentials()));
        pushes.forEach(input -> all.add(input.credentials()));
        return all;
    }

    @Override
    public GitResult<RepositoryInfo> open(Path root) {
        return delegate.open(root);
    }

    @Override
    public GitResult<RepositoryInfo> init(Path root, String initialBranch) {
        return delegate.init(root, initialBranch);
    }

    @Override
    public GitResult<RepositoryInfo> cloneIfMissing(CloneInput input) {
        synchronized (this) {
            clones.add(input);
        }
        return delegate.cloneIfMissing(input);
    }

    @Override
    public GitResult<List<CommitInfo>> log(Path root, String revision, int maxCount) {
        return delegate.log(root, revision, maxCount);
    }

    @Override
    public GitResult<List<RefUpdate>> fetch(FetchInput input) {
        GitOutcome scripted;
        synchronized (this) {
            fetches.add(input);
            scripted = fetchScript.poll();
        }
        return scripted == null ? delegate.fetch(input) : GitResult.of(scripted, SCRIPTED);
    }

    @Override
    public GitResult<RefUpdate> push(PushInput input) {
        GitOutcome scripted;
        @Nullable Runnable action = null;
        @Nullable Consumer<PushInput> refAction = null;
        synchronized (this) {
            pushes.add(input);
            Deque<GitOutcome> byRef = pushScriptByRef.get(input.remoteRef());
            scripted = byRef == null || byRef.isEmpty() ? pushScript.poll() : byRef.poll();
            if (scripted == null) {
                Deque<Consumer<PushInput>> actions = beforePushByRef.get(input.remoteRef());
                refAction = actions == null ? null : actions.poll();
                action = refAction == null ? beforePush.poll() : null;
            }
        }
        if (scripted != null) {
            return GitResult.of(scripted, SCRIPTED);
        }
        if (refAction != null) {
            refAction.accept(input);
        }
        if (action != null) {
            action.run();
        }
        return delegate.push(input);
    }

    @Override
    public GitResult<Path> initCache(Path cache) {
        return delegate.initCache(cache);
    }

    @Override
    public GitResult<String> fetchCommit(FetchCommitInput input) {
        return delegate.fetchCommit(input);
    }

    @Override
    public GitResult<byte[]> readFile(ReadFileInput input) {
        return delegate.readFile(input);
    }
}
