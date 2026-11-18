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
 * Selects the local-remote fixture for a test parameter of type {@link LocalRemote}: the parameter receives a bare
 * repository in a fresh temporary directory together with its {@code file:} URL, for fetch and push tests without
 * network access. The repository is closed and its directory deleted after the test, also when the test fails.
 * <p>
 * The annotation registers {@link LocalRemoteExtension} for the parameter it is placed on. It may be used on several
 * parameters of one test, each of which receives its own remote, and combined with {@link LocalRepository} and
 * {@link InMemory} on other parameters.
 *
 * @since 0.1
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(LocalRemoteExtension.class)
public @interface Remote {
}
