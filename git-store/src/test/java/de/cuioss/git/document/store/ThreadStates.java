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

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

/**
 * Observes a thread of a race scenario: whether it parked on a lock ({@link Thread.State#WAITING}, the state of an
 * untimed {@code ReentrantReadWriteLock} acquisition) or ended, so a test can release the other side of the race only
 * once the thread can no longer overtake it.
 */
final class ThreadStates {

    private ThreadStates() {
    }

    /**
     * Waits until the thread parks or ends, within a deadline.
     *
     * @param thread         the started thread
     * @param timeoutSeconds the deadline of the wait
     * @throws InterruptedException if the waiting thread is interrupted
     */
    static void awaitParkedOrEnded(Thread thread, long timeoutSeconds) throws InterruptedException {
        awaitParkedOrEnded(thread, timeoutSeconds, TimeUnit.SECONDS);
    }

    /**
     * Waits until the thread parks or ends, within a deadline given in any unit, for example what is left of the one
     * deadline of a test.
     *
     * @param thread  the started thread
     * @param timeout the deadline of the wait
     * @param unit    the unit of {@code timeout}
     * @throws InterruptedException if the waiting thread is interrupted
     */
    static void awaitParkedOrEnded(Thread thread, long timeout, TimeUnit unit) throws InterruptedException {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TERMINATED) {
            assertTrue(System.nanoTime() < deadline, "the thread parks on the lock or ends");
            thread.join(1);
        }
    }
}
