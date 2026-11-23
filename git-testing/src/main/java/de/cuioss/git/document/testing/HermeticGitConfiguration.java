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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Isolates a test from the git configuration of the host: while each test method runs, JGit reads an empty user, system
 * and JGit configuration, so a setting in {@code ~/.gitconfig}, the system {@code gitconfig} or {@code ~/.jgitconfig}
 * of the machine that runs the build cannot change what the test observes. The configuration of a repository itself is
 * read as before.
 * <p>
 * Placed on a test class it applies to every test method of the class, its nested classes included; placed on a method
 * it applies to that method. {@link HermeticGitConfigurationExtension} installs the isolating JGit {@code SystemReader}
 * before each test method, its {@code @BeforeEach} methods included, and restores the previous reader after it.
 * {@code @BeforeAll} and {@code @AfterAll} methods run with the previous reader.
 * <p>
 * The {@code SystemReader} is one per JVM, so the fixture must not be used by tests that run concurrently in the same
 * JVM: a test running at the same time would read the empty configuration as well, and the restored reader of one test
 * could end the isolation of another.
 *
 * @since 0.1
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(HermeticGitConfigurationExtension.class)
public @interface HermeticGitConfiguration {
}
