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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.ZoneOffset;


import de.cuioss.git.document.store.StoreConfiguration.Commit;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The commit identities of GDR-STORE-2 and GDR-NFR-3: both settings of {@code commit.email} with and without supplied
 * addresses, the address checks of {@link TechnicalUser} and {@link Author}, whose messages never carry the value,
 * and renderings of both that carry no address.
 * <p>
 * Names, pseudonyms, addresses, credentials and times are generated; the forbidden characters and the details are the
 * specified vocabulary and kept as literals.
 */
@EnableGeneratorController
@DisplayName("CommitIdentities (GDR-STORE-2, GDR-NFR-3)")
class CommitIdentitiesTest {

    private static String word() {
        return Generators.letterStrings(6, 16).next();
    }

    private static String address() {
        return word() + "@" + word() + ".example";
    }

    private static Instant time() {
        return Instant.ofEpochSecond(Generators.integers(1_000_000_000, 2_000_000_000).next());
    }

    private static CredentialsProvider credentials(String user, String password) {
        return new UsernamePasswordCredentialsProvider(user, password);
    }

    private static TechnicalUser user(@Nullable String email) {
        return new TechnicalUser(word(), email, credentials(word(), word()));
    }

    @Nested
    @DisplayName("commit.email")
    class Email {

        @Test
        @DisplayName("GDR-STORE-2, GDR-NFR-3: with commit.email false a supplied address is not written")
        void falseIgnoresAddresses() {
            var author = new Author(word(), address());
            var user = user(address());
            var when = time();
            var identities = new CommitIdentities(new Commit(false), user);

            var authorIdent = identities.author(author, when);
            var committerIdent = identities.committer(when);

            assertAll(
                    () -> assertEquals(author.pseudonym(), authorIdent.getName(), "the author is the pseudonym"),
                    () -> assertEquals("", authorIdent.getEmailAddress(), "no author address"),
                    () -> assertEquals(user.name(), committerIdent.getName(), "the committer is the technical user"),
                    () -> assertEquals("", committerIdent.getEmailAddress(), "no committer address"),
                    () -> assertEquals(when, authorIdent.getWhenAsInstant(), "the author time"),
                    () -> assertEquals(when, committerIdent.getWhenAsInstant(), "the commit time"),
                    () -> assertEquals(ZoneOffset.UTC, authorIdent.getZoneId(), "the author time is in UTC"),
                    () -> assertEquals(ZoneOffset.UTC, committerIdent.getZoneId(), "the commit time is in UTC"));
        }

        @Test
        @DisplayName("GDR-STORE-2: with commit.email false and no addresses both e-mail fields are empty")
        void falseWithoutAddresses() {
            var identities = new CommitIdentities(new Commit(false), user(null));
            var when = time();

            assertAll(
                    () -> assertEquals("", identities.author(Author.of(word()), when).getEmailAddress(), "author"),
                    () -> assertEquals("", identities.committer(when).getEmailAddress(), "committer"));
        }

        @Test
        @DisplayName("GDR-STORE-2: with commit.email true the supplied addresses are written")
        void trueWritesAddresses() {
            var author = new Author(word(), address());
            var user = user(address());
            var when = time();
            var identities = new CommitIdentities(new Commit(true), user);

            assertAll(
                    () -> assertEquals(author.email(), identities.author(author, when).getEmailAddress(), "author"),
                    () -> assertEquals(user.email(), identities.committer(when).getEmailAddress(), "committer"));
        }

        @Test
        @DisplayName("GDR-STORE-2: with commit.email true an absent address stays empty")
        void trueWithoutAddresses() {
            var identities = new CommitIdentities(new Commit(true), user(null));
            var when = time();

            assertAll(
                    () -> assertEquals("", identities.author(Author.of(word()), when).getEmailAddress(), "author"),
                    () -> assertEquals("", identities.committer(when).getEmailAddress(), "committer"));
        }
    }

    @Nested
    @DisplayName("Address checks")
    class AddressChecks {

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"<", ">", "\r", "\n", "\0"})
        @DisplayName("GDR-STORE-2, GDR-NFR-3: an address that breaks the identity line is refused without its value")
        void forbiddenCharacter(String character) {
            var invalid = word() + character + address();

            var forAuthor = assertThrows(IllegalArgumentException.class, () -> new Author(word(), invalid), "author");
            var forUser = assertThrows(IllegalArgumentException.class,
                    () -> new TechnicalUser(word(), invalid, credentials(word(), word())), "technical user");

            assertAll(
                    () -> assertEquals("email: invalid", forAuthor.getMessage(), "the author message names the component"),
                    () -> assertEquals("email: invalid", forUser.getMessage(), "the user message names the component"),
                    () -> assertFalse(forAuthor.getMessage().contains(invalid), "no value in the author message"),
                    () -> assertFalse(forUser.getMessage().contains(invalid), "no value in the user message"));
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("GDR-STORE-2, GDR-NFR-3: a blank address is refused")
        void blankAddress(String blank) {
            assertAll(
                    () -> assertEquals("email: invalid",
                            assertThrows(IllegalArgumentException.class, () -> new Author(word(), blank)).getMessage(),
                            "author"),
                    () -> assertEquals("email: invalid", assertThrows(IllegalArgumentException.class,
                                    () -> new TechnicalUser(word(), blank, credentials(word(), word()))).getMessage(),
                            "technical user"));
        }

        @Test
        @DisplayName("GDR-STORE-2: a valid address and an absent one are accepted")
        void validAddresses() {
            assertAll(
                    () -> assertDoesNotThrow(() -> new Author(word(), address()), "author with address"),
                    () -> assertDoesNotThrow(() -> Author.of(word()), "author without address"),
                    () -> assertDoesNotThrow(() -> user(address()), "user with address"),
                    () -> assertDoesNotThrow(() -> user(null), "user without address"));
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", " ", "\t"})
        @DisplayName("GDR-STORE-2: a blank name of the technical user is refused")
        void blankUserName(String blank) {
            var refused = assertThrows(IllegalArgumentException.class,
                    () -> new TechnicalUser(blank, address(), credentials(word(), word())));

            assertEquals("name: blank", refused.getMessage(), "the message names the component");
        }
    }

    @Nested
    @DisplayName("Rendering (GDR-NFR-3)")
    class Rendering {

        @Test
        @DisplayName("GDR-STORE-2, GDR-NFR-3: an author renders its pseudonym and no address")
        void authorRendering() {
            var author = new Author(word(), address());

            var rendered = author.toString();

            assertAll(
                    () -> assertEquals("Author[pseudonym=" + author.pseudonym() + "]", rendered, "the rendering"),
                    () -> assertFalse(rendered.contains(String.valueOf(author.email())), "no address"));
        }

        @Test
        @DisplayName("GDR-STORE-2, GDR-NFR-3: a technical user renders its name and neither address nor credentials")
        void userRendering() {
            var name = word();
            var email = address();
            var login = "~" + word() + "~";
            var password = "~" + word() + "~";
            var user = new TechnicalUser(name, email, credentials(login, password));

            var rendered = user.toString();

            assertAll(
                    () -> assertEquals("TechnicalUser[name=" + name + "]", rendered, "the rendering"),
                    () -> assertFalse(rendered.contains(email), "no address"),
                    () -> assertFalse(rendered.contains(login), "no user of the credentials"),
                    () -> assertFalse(rendered.contains(password), "no password of the credentials"));
        }
    }
}
