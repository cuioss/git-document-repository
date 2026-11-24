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
package de.cuioss.git.document.timestamp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * The fixtures a test class creates in a static {@code @BeforeAll} and closes in its {@code @AfterAll}. JUnit runs the
 * {@code @AfterAll} method also when {@code @BeforeAll} failed, so only the fixtures that were actually created are
 * registered and closed, each on its own and in the reverse order of creation: a fixture that fails to close does not
 * keep the others open, and a setup failure is not hidden behind a teardown failure on a fixture that never existed.
 */
final class ClosingFixtures {

    private final Deque<Runnable> closers = new ArrayDeque<>();

    /**
     * Registers a created fixture.
     *
     * @param fixture the fixture
     * @param close   how it is closed
     * @param <T>     the type of the fixture
     * @return the fixture
     */
    <T> T add(T fixture, Consumer<? super T> close) {
        closers.push(() -> close.accept(fixture));
        return fixture;
    }

    /**
     * Closes every registered fixture, the last created first, and forgets them.
     *
     * @throws RuntimeException the first failure of a close, with the later ones suppressed
     */
    void closeAll() {
        RuntimeException first = null;
        while (!closers.isEmpty()) {
            try {
                closers.pop().run();
            }
            /*TODO: Catch specific not RuntimeException. Suppress: // cui-rewrite:disable InvalidExceptionUsageRecipe*/
            catch (RuntimeException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }
}
