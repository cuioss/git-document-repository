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
 * Selects the in-memory-repository fixture for a test parameter of type {@link org.eclipse.jgit.lib.Repository}: the
 * parameter receives a new repository of JGit's in-memory DFS storage, typed only as {@code Repository}, without any
 * directory. The repository starts like the bare fixtures, with {@code HEAD} linked to the unborn branch
 * {@code refs/heads/master}. It is closed after the test.
 * <p>
 * The annotation registers {@link InMemoryRepositoryExtension} for the parameter it is placed on. It may be used on
 * several parameters of one test, each of which receives its own repository, and combined with
 * {@link LocalRepository} and {@link Remote} on other parameters.
 *
 * @since 0.1
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(InMemoryRepositoryExtension.class)
public @interface InMemory {
}
