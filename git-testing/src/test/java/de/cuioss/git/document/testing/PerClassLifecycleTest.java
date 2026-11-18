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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The fixtures in a test class with the per-class lifecycle. The resolvers ask for the extension context of the test
 * method when they resolve a constructor parameter ({@code ExtensionContextScope.TEST_METHOD}); under the per-class
 * lifecycle Jupiter hands them the context of the test class instead (observed with junit-jupiter-api 6.1.3), so a
 * constructor fixture is shared by every test of the class and cleaned up when the class ends, while a
 * method-parameter fixture stays per test. The directories are asserted gone once the class has ended.
 */
@DisplayName("Fixtures under the per-class lifecycle")
class PerClassLifecycleTest {

    /** Directories of the constructor fixtures, asserted gone once the per-class test class has ended. */
    private static final List<Path> CONSTRUCTOR_DIRECTORIES = new CopyOnWriteArrayList<>();

    /** Directories of the method-parameter fixtures, asserted gone once the per-class test class has ended. */
    private static final List<Path> METHOD_DIRECTORIES = new CopyOnWriteArrayList<>();

    /** How often Jupiter constructed the per-class test class. */
    private static final AtomicInteger CONSTRUCTIONS = new AtomicInteger();

    @AfterAll
    static void everyDirectoryIsDeleted() {
        assertAll("fixtures of the per-class test class",
                () -> assertEquals(2, CONSTRUCTOR_DIRECTORIES.size(),
                        "The constructor received one local repository and one remote"),
                () -> assertFalse(METHOD_DIRECTORIES.isEmpty(), "The method parameters received directories"));
        assertAll("deleted directories", List.of(CONSTRUCTOR_DIRECTORIES, METHOD_DIRECTORIES).stream()
                .flatMap(List::stream)
                .map(directory -> () -> assertFalse(Files.exists(directory),
                        "The directory is deleted once its fixture's context has closed: " + directory)));
    }

    /**
     * A test class with the per-class lifecycle whose constructor receives all three fixtures. Its tests run in a
     * fixed order, so that a later test can look back at the method fixture of an earlier one.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @DisplayName("Per-class test class with constructor and method fixtures")
    class PerClass {

        private final Repository local;

        private final Repository inMemory;

        private final LocalRemote remote;

        private @Nullable Path previousMethodDirectory;

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
                            "The remote is still there after the last test"));
        }

        @Test
        @Order(1)
        @DisplayName("Should provide the constructor fixtures to the first test")
        void shouldProvideConstructorFixturesToFirstTest() throws Exception {
            assertConstructorFixturesShared();
        }

        @Test
        @Order(2)
        @DisplayName("Should keep the same constructor fixtures open for the second test")
        void shouldKeepConstructorFixturesOpenForSecondTest() throws Exception {
            assertConstructorFixturesShared();
        }

        @Test
        @Order(3)
        @DisplayName("Should provide method fixtures of their own beside the constructor fixtures")
        void shouldProvideOwnMethodFixtures(@LocalRepository Repository methodLocal,
                @InMemory Repository methodInMemory, @Remote LocalRemote methodRemote) {
            var methodDirectory = methodLocal.getDirectory().toPath();
            METHOD_DIRECTORIES.add(methodDirectory);
            METHOD_DIRECTORIES.add(methodRemote.repository().getDirectory().toPath());
            previousMethodDirectory = methodDirectory;
            recorded = true;

            assertAll("method fixtures",
                    () -> assertNotSame(local, methodLocal, "The method receives its own local repository"),
                    () -> assertNotSame(inMemory, methodInMemory, "The method receives its own in-memory repository"),
                    () -> assertNotSame(remote.repository(), methodRemote.repository(),
                            "The method receives its own remote"),
                    () -> assertTrue(Files.isDirectory(methodDirectory), "The method fixture lies in a directory"));
        }

        @Test
        @Order(4)
        @DisplayName("Should clean up the method fixture of the previous test before the next one")
        void shouldCleanUpPreviousMethodFixture(@LocalRepository Repository methodLocal) {
            var methodDirectory = methodLocal.getDirectory().toPath();
            METHOD_DIRECTORIES.add(methodDirectory);
            recorded = true;
            var previous = previousMethodDirectory;

            assertNotNull(previous, "The previous test recorded its method fixture");
            assertAll("method fixture per test",
                    () -> assertNotEquals(previous, methodDirectory, "Each test receives its own method fixture"),
                    () -> assertFalse(Files.exists(previous),
                            "The method fixture of the previous test is deleted after that test"),
                    () -> assertTrue(Files.isDirectory(local.getDirectory().toPath()),
                            "The constructor fixture is still there"));
        }

        private void assertConstructorFixturesShared() throws Exception {
            recorded = true;
            assertAll("shared constructor fixtures",
                    () -> assertEquals(1, CONSTRUCTIONS.get(), "The per-class test class is constructed once"),
                    () -> assertTrue(Files.isDirectory(local.getDirectory().toPath()),
                            "The local repository of the constructor is still there"),
                    () -> assertTrue(Files.isDirectory(remote.repository().getDirectory().toPath()),
                            "The remote of the constructor is still there"),
                    () -> assertEquals("refs/heads/master", inMemory.getFullBranch(),
                            "The in-memory repository of the constructor still answers"));
        }
    }
}
