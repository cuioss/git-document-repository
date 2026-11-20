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

/**
 * A signing key source whose creation by {@link java.util.ServiceLoader} always fails, with the message a test sets in
 * {@link #failure}; it is registered only by a test that hands {@code ServiceLoader} its own services file.
 */
public class FailingSigningKeySource extends TestSigningKeySource {

    /** The message of the exception the constructor throws; set by the test before the loading. */
    static volatile String failure = "";

    /**
     * Fails, as a provider whose construction fails with a credential in its message.
     *
     * @throws IllegalStateException always, with the message {@link #failure}
     */
    public FailingSigningKeySource() {
        throw new IllegalStateException(failure);
    }
}
