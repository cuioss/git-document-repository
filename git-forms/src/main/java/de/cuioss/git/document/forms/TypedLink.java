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
package de.cuioss.git.document.forms;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;


import org.jspecify.annotations.Nullable;

/**
 * A typed link (GDR-FORMS-8): the value of a field that the schema of a kind marks with {@code "format": "gdr-link"}.
 * <p>
 * A link has one of two forms:
 * <ul>
 * <li>a {@link DocumentLink}, {@code <alias>:<kind>/<id>[#<fragment>]}, for example {@code register:risk/R-042}: the
 * document or entry {@code <id>} of the kind {@code <kind>} in the repository of the alias {@code <alias>}, optionally
 * naming a part of it by a fragment;</li>
 * <li>a {@link CatalogLink}, {@code <catalog>:<id>}, for example {@code team:payments}: the entry {@code <id>} of a
 * catalog, which a {@link CatalogResolver} resolves.</li>
 * </ul>
 * The two forms are told apart by the {@code /} after the first {@code :}. Each segment is checked against the name
 * rules of the kind configuration and the store: an alias, a kind and a catalog are of the form {@code [a-z0-9-]+}, as
 * a repository alias, a kind id and a collection are; an id is of the form {@code [A-Za-z0-9_-]+}, which holds every
 * ULID and every running number of a numbering ({@link Numbering}), so it has no {@code /}, no empty segment and no
 * control character. A fragment is everything after the first {@code #} of a document link; it must not be empty or hold
 * a control character, and it is kept as written and never interpreted. A catalog link has no fragment.
 * <p>
 * A link text is data of the document: {@link #parse(String)} names the rule a text breaks, never the text, and a
 * caller treats a link and its components as data and never logs them (GDR-NFR-3).
 *
 * @since 0.1
 */
public sealed interface TypedLink permits TypedLink.DocumentLink, TypedLink.CatalogLink {

    /** The form of an alias, a kind and a catalog: lower-case letters, digits and hyphens. */
    Pattern NAME = Pattern.compile("[a-z0-9-]+");

    /** The form of an id: letters, digits, underscores and hyphens. */
    Pattern ID = Pattern.compile("[A-Za-z0-9_-]+");

    /** The detail of a text of neither form. */
    String NEITHER_FORM = "link: not of the form <alias>:<kind>/<id>[#<fragment>] or <catalog>:<id>";

    /**
     * Parses the text of a link field.
     *
     * @param text the text
     * @return {@link FormsOutcome#OK} with the {@link DocumentLink} or the {@link CatalogLink}; or
     *         {@link FormsOutcome#INVALID} for a text of neither form, the detail naming the rule it breaks
     *         ({@code link: kind: not of the form [a-z0-9-]+}), never the text
     * @throws NullPointerException if {@code text} is {@code null}
     */
    static FormsResult<TypedLink> parse(String text) {
        Objects.requireNonNull(text, "text");
        int colon = text.indexOf(':');
        if (colon < 0) {
            return FormsResult.of(FormsOutcome.INVALID, NEITHER_FORM);
        }
        String prefix = text.substring(0, colon);
        String rest = text.substring(colon + 1);
        int slash = rest.indexOf('/');
        if (slash < 0) {
            Optional<String> refused = refusal(prefix, rest);
            return refused.<FormsResult<TypedLink>>map(detail -> FormsResult.of(FormsOutcome.INVALID, detail))
                    .orElseGet(() -> FormsResult.ok(new CatalogLink(prefix, rest)));
        }
        String kind = rest.substring(0, slash);
        String target = rest.substring(slash + 1);
        int hash = target.indexOf('#');
        String id = hash < 0 ? target : target.substring(0, hash);
        @Nullable String fragment = hash < 0 ? null : target.substring(hash + 1);
        Optional<String> refused = refusal(prefix, kind, id, fragment);
        return refused.<FormsResult<TypedLink>>map(detail -> FormsResult.of(FormsOutcome.INVALID, detail))
                .orElseGet(() -> FormsResult.ok(new DocumentLink(prefix, kind, id, fragment)));
    }

    /** The rule a catalog link breaks, or empty. */
    private static Optional<String> refusal(String catalog, String id) {
        if (!NAME.matcher(catalog).matches()) {
            return Optional.of("link: catalog: not of the form " + NAME.pattern());
        }
        if (!ID.matcher(id).matches()) {
            return Optional.of("link: id: not of the form " + ID.pattern());
        }
        return Optional.empty();
    }

    /** The rule a document link breaks, or empty. */
    private static Optional<String> refusal(String alias, String kind, String id, @Nullable String fragment) {
        if (!NAME.matcher(alias).matches()) {
            return Optional.of("link: alias: not of the form " + NAME.pattern());
        }
        if (!NAME.matcher(kind).matches()) {
            return Optional.of("link: kind: not of the form " + NAME.pattern());
        }
        if (!ID.matcher(id).matches()) {
            return Optional.of("link: id: not of the form " + ID.pattern());
        }
        if (fragment != null && fragment.isEmpty()) {
            return Optional.of("link: fragment: empty");
        }
        if (fragment != null && fragment.chars().anyMatch(Character::isISOControl)) {
            return Optional.of("link: fragment: a control character");
        }
        return Optional.empty();
    }

    /**
     * A link to a document or entry of a kind, {@code <alias>:<kind>/<id>[#<fragment>]}.
     *
     * @param alias    the alias of the repository, of the form {@code [a-z0-9-]+}
     * @param kind     the id of the kind, of the form {@code [a-z0-9-]+}
     * @param id       the id of the document or entry, of the form {@code [A-Za-z0-9_-]+}
     * @param fragment the part of the document the link names, kept and never interpreted; {@code null} for none
     * @since 0.1
     */
    record DocumentLink(String alias, String kind, String id, @Nullable String fragment) implements TypedLink {

        /**
         * Checks the components.
         *
         * @param alias    the alias
         * @param kind     the kind
         * @param id       the id
         * @param fragment the fragment, or {@code null}
         * @throws NullPointerException     if a component other than {@code fragment} is {@code null}
         * @throws IllegalArgumentException if a component breaks its rule; the message names the rule only
         */
        public DocumentLink {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            refusal(alias, kind, id, fragment).ifPresent(detail -> {
                throw new IllegalArgumentException(detail);
            });
        }
    }

    /**
     * A link to an entry of a catalog, {@code <catalog>:<id>}.
     *
     * @param catalog the name of the catalog, of the form {@code [a-z0-9-]+}
     * @param id      the id of the entry, of the form {@code [A-Za-z0-9_-]+}
     * @since 0.1
     */
    record CatalogLink(String catalog, String id) implements TypedLink {

        /**
         * Checks the components.
         *
         * @param catalog the catalog
         * @param id      the id
         * @throws NullPointerException     if a component is {@code null}
         * @throws IllegalArgumentException if a component breaks its rule; the message names the rule only
         */
        public CatalogLink {
            Objects.requireNonNull(catalog, "catalog");
            Objects.requireNonNull(id, "id");
            refusal(catalog, id).ifPresent(detail -> {
                throw new IllegalArgumentException(detail);
            });
        }
    }
}
