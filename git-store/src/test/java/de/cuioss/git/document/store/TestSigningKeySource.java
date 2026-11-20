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

import java.util.Objects;

/**
 * A test signing key source: {@link java.util.ServiceLoader} finds it under the id {@value #ID} through
 * {@code META-INF/services}, and a test creates it under any id. It signs nothing and hands out no key.
 */
public class TestSigningKeySource implements SigningKeySource {

    /** The id of the instance {@link java.util.ServiceLoader} creates. */
    public static final String ID = "test-signing";

    private final String id;

    /** Creates the source under the id {@value #ID}, as {@link java.util.ServiceLoader} does. */
    public TestSigningKeySource() {
        this(ID);
    }

    /**
     * Creates the source under an id.
     *
     * @param id the id
     */
    public TestSigningKeySource(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public StoreResult<byte[]> sign(String keyReference, byte[] data) {
        return StoreResult.of(StoreOutcome.REFUSED, "the test source signs nothing");
    }

    @Override
    public StoreResult<String> publicKey(String keyReference) {
        return StoreResult.of(StoreOutcome.REFUSED, "the test source hands out no key");
    }
}
