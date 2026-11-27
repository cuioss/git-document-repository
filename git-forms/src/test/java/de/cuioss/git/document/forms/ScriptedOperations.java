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
package de.cuioss.git.document.forms;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;


import de.cuioss.git.document.core.GitOutcome;
import de.cuioss.git.document.core.GitResult;
import de.cuioss.git.document.core.JGitRepositoryOperations;
import de.cuioss.git.document.core.RemotePolicy;
import de.cuioss.git.document.core.RepositoryOperations;
import de.cuioss.git.document.store.StoreReferences;
import org.jspecify.annotations.Nullable;

/**
 * A test double of the {@link RepositoryOperations} of {@code git-core}, reached through the public seam
 * {@code StoreWriter.open}: it delegates every call to the real {@link JGitRepositoryOperations} of a local-only policy
 * and fails the calls of a scripted kind with a scripted outcome, without touching the remote, as often as scripted.
 * <p>
 * The kinds are those a release makes and a save does not: the push of {@code main} and the push of a release tag, and
 * the fetch of one tag a release makes when the remote refused its tag. A save pushes its working or collection branch
 * only, so a script never fails the save before the release. A double serves the thread of one test.
 */
final class ScriptedOperations implements RepositoryOperations {

    /** The kinds of calls a script fails. */
    enum Call {
        /** The push of {@code refs/heads/main}. */
        PUSH_MAIN,
        /** The push of a tag. */
        PUSH_TAG,
        /** The fetch of one tag, after the remote refused the push of that tag. */
        FETCH_TAG
    }

    private final RepositoryOperations delegate = new JGitRepositoryOperations(RemotePolicy.localOnly());
    private final Map<Call, Deque<GitOutcome>> scripted = new EnumMap<>(Call.class);
    private final Map<Call, Integer> failed = new EnumMap<>(Call.class);

    /**
     * Fails the next calls of a kind.
     *
     * @param call    the kind of call
     * @param outcome the outcome each failed call returns, never {@link GitOutcome#OK}
     * @param times   how many of the next calls of that kind fail
     * @return this double
     */
    ScriptedOperations fail(Call call, GitOutcome outcome, int times) {
        Deque<GitOutcome> queue = scripted.computeIfAbsent(call, ignored -> new ArrayDeque<>());
        for (int index = 0; index < times; index++) {
            queue.add(outcome);
        }
        return this;
    }

    /**
     * How many calls of a kind the script failed.
     *
     * @param call the kind of call
     * @return the number of failed calls
     */
    int failed(Call call) {
        return failed.getOrDefault(call, 0);
    }

    /** The scripted outcome of the next call of a kind, consumed, or {@code null} to delegate it. */
    private @Nullable GitOutcome next(Call call) {
        Deque<GitOutcome> queue = scripted.get(call);
        GitOutcome outcome = queue == null ? null : queue.poll();
        if (outcome != null) {
            failed.merge(call, 1, Integer::sum);
        }
        return outcome;
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
        return delegate.cloneIfMissing(input);
    }

    @Override
    public GitResult<List<CommitInfo>> log(Path root, String revision, int maxCount) {
        return delegate.log(root, revision, maxCount);
    }

    @Override
    public GitResult<List<RefUpdate>> fetch(FetchInput input) {
        boolean oneTag = input.refSpecs().size() == 1
                && input.refSpecs().getFirst().startsWith("+" + StoreReferences.TAGS);
        @Nullable GitOutcome outcome = oneTag ? next(Call.FETCH_TAG) : null;
        return outcome == null ? delegate.fetch(input) : GitResult.of(outcome, "scripted fetch of a tag");
    }

    @Override
    public GitResult<RefUpdate> push(PushInput input) {
        @Nullable GitOutcome outcome = null;
        if (StoreReferences.MAIN.equals(input.remoteRef())) {
            outcome = next(Call.PUSH_MAIN);
        } else if (input.remoteRef().startsWith(StoreReferences.TAGS)) {
            outcome = next(Call.PUSH_TAG);
        }
        return outcome == null ? delegate.push(input) : GitResult.of(outcome, "scripted push of " + input.remoteRef());
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
