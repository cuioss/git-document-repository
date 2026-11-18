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

import java.util.Arrays;
import java.util.Objects;

/**
 * A credential for an authenticated Git transport, held as plain values: a user name and a token. The token is a
 * {@code char[]} so that a caller can clear it after use.
 * <p>
 * Equality is content-based: two credentials are equal when their user names are equal and their tokens hold the same
 * characters, even in distinct arrays. {@link #toString()} masks the token and never renders it.
 *
 * @param username the user name of the credential
 * @param token    the token of the credential; the record holds the array it is given, without copying it
 * @since 0.1
 */
public record GitCredential(String username, char[] token) {

    /**
     * Compares the user name and the content of the token.
     *
     * @param other the object to compare with
     * @return {@code true} when {@code other} is a {@code GitCredential} with an equal user name and a token of the
     *         same characters
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof GitCredential(String otherUsername, char[] otherToken)
                && Objects.equals(username, otherUsername)
                && Arrays.equals(token, otherToken);
    }

    /**
     * Computes the hash code from the user name and the content of the token, consistent with {@link #equals(Object)}.
     *
     * @return the hash code of this credential
     */
    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(username) + Arrays.hashCode(token);
    }

    /**
     * Renders the user name and a mask in place of the token, in the form {@code GitCredential[<username>, ***]}.
     *
     * @return a representation of this credential that never contains the token
     */
    @Override
    public String toString() {
        return "GitCredential[" + username + ", ***]";
    }
}
