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
package de.cuioss.git.document.testing;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;

/**
 * The fixtures in a test class with the per-class lifecycle. The resolvers ask for the extension context of the test
 * method when they resolve a constructor parameter ({@code ExtensionContextScope.TEST_METHOD}); under the per-class
 * lifecycle Jupiter hands them the context of the test class instead (observed with junit-jupiter-api 6.1.3), so a
 * constructor fixture is shared by every test of the class and cleaned up when the class ends, while a
 * method-parameter fixture stays per test. The directories are asserted gone once the class has ended, and the
 * in-memory repository of the constructor is asserted closed exactly once, then and not before. This is the
 * behaviour the class Javadoc of {@link LocalRepositoryExtension}, {@link InMemoryRepositoryExtension} and
 * {@link LocalRemoteExtension} states for {@code Lifecycle.PER_CLASS}: one instance, created once in the context of
 * the class, whose constructor fixture lives for the whole class.
 */
@DisplayName("Fixtures under the per-class lifecycle")
class PerClassLifecycleTest {

    /** How many method invocations the scenario runs; the second looks back at the fixtures of the first. */
    private static final int INVOCATIONS = 2;

    /** Directories of the constructor fixtures, asserted gone once the per-class test class has ended. */
    private static final List<Path> CONSTRUCTOR_DIRECTORIES = new CopyOnWriteArrayList<>();

    /** Directories of the method-parameter fixtures, asserted gone once the per-class test class has ended. */
    private static final List<Path> METHOD_DIRECTORIES = new CopyOnWriteArrayList<>();

    /** How often Jupiter constructed the per-class test class. */
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    /** Whether {@link InMemoryClosingObserver} found the closing of the constructor's in-memory repository. */
    private static final AtomicBoolean IN_MEMORY_CLOSING_OBSERVED = new AtomicBoolean();

    /** How often Jupiter closed the stored closing of the constructor's in-memory repository. */
    private static final AtomicInteger IN_MEMORY_CLOSINGS = new AtomicInteger();

    @AfterAll
    static void everyFixtureIsCleanedUp() {
        assertAll("fixtures of the per-class test class",
                () -> assertEquals(2, CONSTRUCTOR_DIRECTORIES.size(),
                        "The constructor received one local repository and one remote"),
                () -> assertEquals(2 * INVOCATIONS, METHOD_DIRECTORIES.size(),
                        "Each invocation received one local repository and one remote as method parameters"),
                () -> assertTrue(IN_MEMORY_CLOSING_OBSERVED.get(),
                        "The closing of the constructor's in-memory repository was observed"),
                () -> assertEquals(1, IN_MEMORY_CLOSINGS.get(),
                        "The in-memory repository of the constructor is closed once when the class ends"));
        assertAll("deleted directories", List.of(CONSTRUCTOR_DIRECTORIES, METHOD_DIRECTORIES).stream()
                .flatMap(List::stream)
                .map(directory -> () -> assertFalse(Files.exists(directory),
                        "The directory is deleted once its fixture's context has closed: " + directory)));
    }

    /**
     * A test class with the per-class lifecycle whose constructor receives all three fixtures. Its one test runs the
     * whole scenario in two invocations, each with method fixtures of its own, so that the second invocation looks
     * back at the method fixtures of the first across a real boundary between two method contexts.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @ExtendWith(InMemoryClosingObserver.class)
    @DisplayName("Per-class test class with constructor and method fixtures")
    class PerClass {

        private final Repository local;

        private final Repository inMemory;

        private final LocalRemote remote;

        private final List<Path> previousMethodDirectories = new CopyOnWriteArrayList<>();

        private boolean recorded;

        PerClass(@LocalRepository Repository local, @InMemory Repository inMemory, @Remote LocalRemote remote) {
            this.local = local;
            this.inMemory = inMemory;
            this.remote = remote;
            CONSTRUCTIONS.incrementAndGet();
            CONSTRUCTOR_DIRECTORIES.add(local.getDirectory().toPath());
            CONSTRUCTOR_DIRECTORIES.add(remote.repository().getDirectory().toPath());
        }

        @BeforeEach
        void resetRecording() {
            recorded = false;
        }

        @AfterEach
        void fixtureTestRecordedItsFixtures(TestInfo testInfo) {
            if (LocalRepositoryExtensionTest.consumesFixture(testInfo, LocalRepository.class, InMemory.class,
                    Remote.class)) {
                assertTrue(recorded, "A test consuming a fixture asserts on what it received: "
                        + testInfo.getDisplayName());
            }
        }

        @AfterAll
        void constructorFixturesStayOpenUntilTheClassEnds() {
            assertAll("constructor fixtures after the last test",
                    () -> assertTrue(Files.isDirectory(local.getDirectory().toPath()),
                            "The local repository is still there after the last test"),
                    () -> assertTrue(Files.isDirectory(remote.repository().getDirectory().toPath()),
                            "The remote is still there after the last test"),
                    () -> assertTrue(IN_MEMORY_CLOSING_OBSERVED.get(),
                            "The closing of the constructor's in-memory repository is observed"),
                    () -> assertEquals(0, IN_MEMORY_CLOSINGS.get(),
                            "The in-memory repository of the constructor is not closed before the class ends"));
        }

        @RepeatedTest(INVOCATIONS)
        @DisplayName("Should share the constructor fixtures and clean up the method fixtures after each invocation")
        void shouldShareConstructorFixturesAndCleanUpMethodFixtures(RepetitionInfo repetition,
                @LocalRepository Repository methodLocal, @InMemory Repository methodInMemory,
                @Remote LocalRemote methodRemote) {
            var methodDirectories = List.of(methodLocal.getDirectory().toPath(),
                    methodRemote.repository().getDirectory().toPath());
            METHOD_DIRECTORIES.addAll(methodDirectories);
            var previous = List.copyOf(previousMethodDirectories);
            previousMethodDirectories.clear();
            previousMethodDirectories.addAll(methodDirectories);
            recorded = true;

            assertAll("constructor and method fixtures",
                    () -> assertEquals(1, CONSTRUCTIONS.get(), "The per-class test class is constructed once"),
                    () -> assertTrue(Files.isDirectory(local.getDirectory().toPath()),
                            "The local repository of the constructor is still there"),
                    () -> assertTrue(Files.isDirectory(remote.repository().getDirectory().toPath()),
                            "The remote of the constructor is still there"),
                    () -> assertEquals("refs/heads/master", inMemory.getFullBranch(),
                            "The in-memory repository of the constructor still answers"),
                    () -> assertEquals(0, IN_MEMORY_CLOSINGS.get(),
                            "The in-memory repository of the constructor is not closed between the tests"),
                    () -> assertNotSame(local, methodLocal, "The method receives its own local repository"),
                    () -> assertNotSame(inMemory, methodInMemory, "The method receives its own in-memory repository"),
                    () -> assertNotSame(remote.repository(), methodRemote.repository(),
                            "The method receives its own remote"),
                    () -> assertTrue(methodDirectories.stream().allMatch(Files::isDirectory),
                            "The method fixtures lie in directories"));
            if (repetition.getCurrentRepetition() == 1) {
                assertTrue(previous.isEmpty(), "The first invocation has no previous method fixtures");
            } else {
                assertEquals(2, previous.size(),
                        "The previous invocation recorded its local repository and its remote");
                assertAll("method fixtures of the previous invocation",
                        previous.stream().map(directory -> () -> {
                            assertFalse(methodDirectories.contains(directory),
                                    "Each invocation receives its own method fixtures: " + directory);
                            assertFalse(Files.exists(directory),
                                    "The method fixture of the previous invocation is deleted after it: "
                                            + directory);
                        }));
            }
        }
    }

    /**
     * Observes the closing of the constructor's in-memory repository, which no directory reveals and which the
     * repository's own answers do not show reliably. Once the per-class instance exists, the observer takes the closing the resolver stored
     * for that repository out of the extension context of the test class and puts a counting closing in its place,
     * which delegates to the stored one. Hand-written, because the resolver creates the repository itself, so no mock
     * can be handed to it, and the stored {@link ClosingRepository} is a record that cannot be subclassed.
     */
    static final class InMemoryClosingObserver implements TestInstancePostProcessor {

        @Override
        public void postProcessTestInstance(Object testInstance, ExtensionContext context) {
            var instance = (PerClass) testInstance;
            var constructor = PerClass.class.getDeclaredConstructors()[0];
            var store = context.getStore(InMemoryRepositoryExtension.NAMESPACE);
            for (var index = 0; index < constructor.getParameterCount(); index++) {
                var key = List.of(constructor, index);
                if (store.get(key) instanceof ClosingRepository closing && closing.repository() == instance.inMemory) {
                    var removed = store.remove(key, ClosingRepository.class);
                    assertSame(closing, removed, "The closing lies in the store of the test class itself");
                    assertNull(store.get(key), "Nothing is left under the key of the removed closing");
                    store.put(key, new CountingClosing(closing));
                    IN_MEMORY_CLOSING_OBSERVED.set(true);
                    return;
                }
            }
            throw new AssertionError("No closing of the constructor's in-memory repository in the class context");
        }
    }

    /**
     * A closing that counts how often Jupiter closes it and then closes the stored closing it stands in for. Like
     * {@link ClosingRepository} it is both an {@link AutoCloseable} and an
     * {@link ExtensionContext.Store.CloseableResource}, so Jupiter closes it whatever its auto-close setting.
     *
     * @param delegate the closing the resolver stored
     */
    // CloseableResource is deprecated, but it is the only store type Jupiter closes while
    // junit.jupiter.extensions.store.close.autocloseable.enabled=false (junit-jupiter-engine 6.1.3)
    @SuppressWarnings("deprecation")
    record CountingClosing(ClosingRepository delegate)
            implements AutoCloseable, ExtensionContext.Store.CloseableResource {

        @Override
        public void close() {
            IN_MEMORY_CLOSINGS.incrementAndGet();
            delegate.close();
        }
    }
}
