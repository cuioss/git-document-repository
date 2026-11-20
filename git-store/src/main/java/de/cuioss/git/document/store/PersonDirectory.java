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
import java.util.Set;

/**
 * The service interface of the person directory (GDR-STORE-19): pseudonyms, person entries, holders of a role, and
 * erasure.
 * <p>
 * Exactly one provider is active, named by {@code directory.persons.provider} (default {@code git}); the author
 * pseudonym of every save comes from it. The key of a person is the issuer of the identity provider together with
 * {@code sub}, because a {@code sub} is unique only within its issuer.
 * <p>
 * A pseudonym is stable for the same issuer and {@code sub} as long as its entry exists. After an erasure the provider
 * no longer returns the person for that pseudonym and resolves it as a {@link PersonEntry.FormerMember}; a later login
 * with the same issuer and {@code sub} is a first login and receives a new pseudonym, not linked to the erased one.
 * Outside the provider's own entries, the store writes no data it obtains from the provider into a repository except
 * pseudonyms.
 * <p>
 * Every operation returns a {@link StoreResult}. A provider over the operator's own source (LDAP, SCIM, the identity
 * provider's user directory) returns {@link StoreOutcome#NOT_SUPPORTED} for saving an entry, for unbound entries and
 * for binding, and {@link StoreOutcome#MANAGED_BY_SOURCE} for an erasure, because the operator erases there. A detail
 * never carries a name, a {@code sub} or an e-mail address (GDR-NFR-3).
 *
 * @since 0.1
 */
public interface PersonDirectory {

    /**
     * Returns the id of the provider, the value {@code directory.persons.provider} names it by.
     *
     * @return the id, never blank
     */
    String id();

    /**
     * Returns the pseudonym of an identity-provider issuer and {@code sub}, assigning a new one at a first login.
     *
     * @param issuer  the issuer URL of the identity provider (token claim {@code iss})
     * @param subject the {@code sub} within that issuer
     * @return {@link StoreOutcome#OK} with the pseudonym, or a non-success
     */
    StoreResult<String> pseudonym(String issuer, String subject);

    /**
     * Returns the entry of a pseudonym: a member with name and memberships, or a former-member marker with the
     * memberships the provider still knows, possibly none.
     *
     * @param pseudonym the pseudonym
     * @return {@link StoreOutcome#OK} with the entry, or a non-success
     */
    StoreResult<PersonEntry> entry(String pseudonym);

    /**
     * Returns the pseudonyms holding a role in a team.
     *
     * @param team the team
     * @param role the role
     * @return {@link StoreOutcome#OK} with the pseudonyms, possibly empty, or a non-success
     */
    StoreResult<List<String>> holders(String team, String role);

    /**
     * Saves the entry of a member.
     *
     * @param entry the entry
     * @return {@link StoreOutcome#OK} with the saved entry, {@link StoreOutcome#NOT_SUPPORTED} for a provider over the
     * operator's own source, or another non-success
     */
    StoreResult<PersonEntry.Member> save(PersonEntry.Member entry);

    /**
     * Saves an entry not yet bound to an issuer and {@code sub}.
     *
     * @param name        the name of the person
     * @param memberships the memberships, possibly empty
     * @return {@link StoreOutcome#OK} with the pseudonym of the new entry, {@link StoreOutcome#NOT_SUPPORTED} for a
     * provider over the operator's own source, or another non-success
     */
    StoreResult<String> saveUnbound(String name, Set<Membership> memberships);

    /**
     * Returns the unbound entries holding one of the given memberships.
     *
     * @param memberships the memberships
     * @return {@link StoreOutcome#OK} with the entries, possibly empty, {@link StoreOutcome#NOT_SUPPORTED} for a
     * provider over the operator's own source, or another non-success
     */
    StoreResult<List<PersonEntry.Member>> unbound(Set<Membership> memberships);

    /**
     * Binds an unbound entry to an issuer and {@code sub}.
     *
     * @param pseudonym the pseudonym of the unbound entry
     * @param issuer    the issuer URL of the identity provider
     * @param subject   the {@code sub} within that issuer
     * @return {@link StoreOutcome#OK} with the pseudonym, {@link StoreOutcome#NOT_SUPPORTED} for a provider over the
     * operator's own source, or another non-success
     */
    StoreResult<String> bind(String pseudonym, String issuer, String subject);

    /**
     * Erases the person of a pseudonym; afterwards the pseudonym resolves as a former member.
     *
     * @param pseudonym the pseudonym
     * @return {@link StoreOutcome#OK} with the pseudonym, {@link StoreOutcome#MANAGED_BY_SOURCE} for a provider over
     * the operator's own source, or another non-success
     */
    StoreResult<String> erase(String pseudonym);
}
