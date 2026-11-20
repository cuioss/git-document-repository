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

import java.time.Instant;
import java.util.Objects;


import org.eclipse.jgit.lib.ObjectId;

/**
 * A test anchor provider: {@link java.util.ServiceLoader} finds it under the id {@value #ID} through
 * {@code META-INF/services}, and a test creates it under any id. It is never reachable and verifies no proof.
 */
public class TestAnchorProvider implements AnchorProvider {

    /** The id of the instance {@link java.util.ServiceLoader} creates. */
    public static final String ID = "test-anchor";

    private final String id;

    /** Creates the provider under the id {@value #ID}, as {@link java.util.ServiceLoader} does. */
    public TestAnchorProvider() {
        this(ID);
    }

    /**
     * Creates the provider under an id.
     *
     * @param id the id
     */
    public TestAnchorProvider(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public StoreResult<byte[]> obtain(String repository, ObjectId subject, byte[] previous) {
        return StoreResult.of(StoreOutcome.UNAVAILABLE, "the test provider is never reachable");
    }

    @Override
    public StoreResult<Instant> verify(ObjectId subject, byte[] proof, byte[] previous) {
        return StoreResult.of(StoreOutcome.SIGNATURE_INVALID, "the test provider verifies no proof");
    }
}
