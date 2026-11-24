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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;


import org.eclipse.jgit.lib.ObjectId;
import org.jspecify.annotations.Nullable;

/**
 * A hand-written test double of {@link AnchorProvider}: it records every call of {@link #obtain}, returns the proof
 * {@link #proofOf(ObjectId)} of the subject unless a scripted result or a permanent outcome is set, and can run an
 * action right before the next obtain, such as a concurrent save on the same clone.
 * <p>
 * A scripted result is a non-success with the detail {@value #SCRIPTED}, or a {@link IllegalStateException} thrown by
 * the provider. {@link #verify} accepts exactly the proof {@link #proofOf(ObjectId)} of the subject, at
 * {@link Instant#EPOCH}. The double is safe for use by several threads.
 */
final class ScriptedAnchorProvider implements AnchorProvider {

    /** The detail of every scripted result. */
    static final String SCRIPTED = "scripted";

    /**
     * One call of {@link #obtain}.
     *
     * @param repository the alias the store passed
     * @param subject    the subject
     * @param commitTime the commit time of the subject
     * @param previous   a copy of the previous proof the store passed
     */
    record Call(String repository, ObjectId subject, Instant commitTime, byte[] previous) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Call call && repository.equals(call.repository) && subject.equals(call.subject)
                    && commitTime.equals(call.commitTime) && Arrays.equals(previous, call.previous);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repository, subject, commitTime, Arrays.hashCode(previous));
        }

        @Override
        public String toString() {
            return "Call[" + subject.name() + "]";
        }
    }

    private final String id;
    private final Deque<Supplier<StoreResult<byte[]>>> script = new ArrayDeque<>();
    private final Deque<Runnable> beforeObtain = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();
    private @Nullable StoreOutcome always;

    /**
     * Creates the double under an id.
     *
     * @param id the id, of the form {@code [a-z0-9-]+}
     */
    ScriptedAnchorProvider(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    /**
     * The proof the double returns for a subject when nothing is scripted.
     *
     * @param subject the subject
     * @return {@code proof <subject id>} in US-ASCII
     */
    static byte[] proofOf(ObjectId subject) {
        return ("proof " + subject.name()).getBytes(US_ASCII);
    }

    /**
     * Scripts the result of the next calls, one per outcome, in order.
     *
     * @param outcomes the outcomes, each a non-success
     * @return this double
     */
    synchronized ScriptedAnchorProvider fail(StoreOutcome... outcomes) {
        for (StoreOutcome outcome : outcomes) {
            script.add(() -> StoreResult.of(outcome, SCRIPTED));
        }
        return this;
    }

    /**
     * Scripts the next call to throw an {@link IllegalStateException}.
     *
     * @return this double
     */
    synchronized ScriptedAnchorProvider throwNext() {
        script.add(() -> {
            throw new IllegalStateException(SCRIPTED);
        });
        return this;
    }

    /**
     * Scripts the next call to throw an {@link Error}, such as an {@link AssertionError} of a broken provider or a
     * {@link StackOverflowError}, a {@link VirtualMachineError}.
     *
     * @param error the error to throw
     * @return this double
     */
    synchronized ScriptedAnchorProvider errorNext(Error error) {
        Objects.requireNonNull(error, "error");
        script.add(() -> {
            throw error;
        });
        return this;
    }

    /**
     * Makes every further call answer with an outcome until {@link #recover()}.
     *
     * @param outcome the outcome, a non-success
     * @return this double
     */
    synchronized ScriptedAnchorProvider always(StoreOutcome outcome) {
        always = Objects.requireNonNull(outcome, "outcome");
        return this;
    }

    /**
     * Ends {@link #always(StoreOutcome)}.
     *
     * @return this double
     */
    synchronized ScriptedAnchorProvider recover() {
        always = null;
        return this;
    }

    /**
     * Runs an action right before the next call answers, once, on the thread of the call.
     *
     * @param action the action
     * @return this double
     */
    synchronized ScriptedAnchorProvider beforeNextObtain(Runnable action) {
        beforeObtain.add(Objects.requireNonNull(action, "action"));
        return this;
    }

    /** @return every call so far, in order */
    synchronized List<Call> calls() {
        return List.copyOf(calls);
    }

    /** @return the subjects of every call so far, in order */
    synchronized List<ObjectId> subjects() {
        return calls.stream().map(Call::subject).toList();
    }

    /**
     * Waits until at least a number of calls happened.
     *
     * @param count   the number of calls
     * @param timeout the deadline of the wait
     * @return whether that many calls happened within the deadline
     * @throws InterruptedException if the waiting thread is interrupted
     */
    synchronized boolean awaitCalls(int count, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (calls.size() < count) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);
        }
        return true;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public StoreResult<byte[]> obtain(String repository, ObjectId subject, Instant commitTime, byte[] previous) {
        Supplier<StoreResult<byte[]>> scripted;
        Runnable action;
        StoreOutcome permanent;
        synchronized (this) {
            calls.add(new Call(repository, subject.copy(), commitTime, previous.clone()));
            notifyAll();
            scripted = script.poll();
            action = beforeObtain.poll();
            permanent = always;
        }
        if (action != null) {
            action.run();
        }
        if (scripted != null) {
            return scripted.get();
        }
        if (permanent != null) {
            return StoreResult.of(permanent, SCRIPTED);
        }
        return StoreResult.ok(proofOf(subject));
    }

    @Override
    public StoreResult<Instant> verify(ObjectId subject, byte[] proof, byte[] previous) {
        return Arrays.equals(proofOf(subject), proof) ? StoreResult.ok(Instant.EPOCH)
                : StoreResult.of(StoreOutcome.IMPRINT_MISMATCH, SCRIPTED);
    }
}
