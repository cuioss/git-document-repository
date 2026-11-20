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

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A test person directory: {@link java.util.ServiceLoader} finds it under the id {@value #ID} through
 * {@code META-INF/services}, and a test creates it under any id. It behaves as a provider over the operator's own
 * source that knows nobody: lookups are empty, writes are not supported and an erasure is managed by the source.
 */
public class TestPersonDirectory implements PersonDirectory {

    /** The id of the instance {@link java.util.ServiceLoader} creates. */
    public static final String ID = "test-directory";

    private static final String NOT_SUPPORTED = "the test directory is maintained by its source";

    private final String id;

    /** Creates the directory under the id {@value #ID}, as {@link java.util.ServiceLoader} does. */
    public TestPersonDirectory() {
        this(ID);
    }

    /**
     * Creates the directory under an id.
     *
     * @param id the id
     */
    public TestPersonDirectory(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public StoreResult<String> pseudonym(String issuer, String subject) {
        return StoreResult.of(StoreOutcome.UNAVAILABLE, "the test directory assigns no pseudonym");
    }

    @Override
    public StoreResult<PersonEntry> entry(String pseudonym) {
        return StoreResult.ok(new PersonEntry.FormerMember(pseudonym, Set.of()));
    }

    @Override
    public StoreResult<List<String>> holders(String team, String role) {
        return StoreResult.ok(List.of());
    }

    @Override
    public StoreResult<PersonEntry.Member> save(PersonEntry.Member entry) {
        return StoreResult.of(StoreOutcome.NOT_SUPPORTED, NOT_SUPPORTED);
    }

    @Override
    public StoreResult<String> saveUnbound(String name, Set<Membership> memberships) {
        return StoreResult.of(StoreOutcome.NOT_SUPPORTED, NOT_SUPPORTED);
    }

    @Override
    public StoreResult<List<PersonEntry.Member>> unbound(Set<Membership> memberships) {
        return StoreResult.of(StoreOutcome.NOT_SUPPORTED, NOT_SUPPORTED);
    }

    @Override
    public StoreResult<String> bind(String pseudonym, String issuer, String subject) {
        return StoreResult.of(StoreOutcome.NOT_SUPPORTED, NOT_SUPPORTED);
    }

    @Override
    public StoreResult<String> erase(String pseudonym) {
        return StoreResult.of(StoreOutcome.MANAGED_BY_SOURCE, NOT_SUPPORTED);
    }
}
