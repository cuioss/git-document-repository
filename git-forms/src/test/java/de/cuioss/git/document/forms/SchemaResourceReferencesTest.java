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

import static de.cuioss.git.document.forms.SchemaValidatorTest.PATH;
import static de.cuioss.git.document.forms.SchemaValidatorTest.schema;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


import de.cuioss.git.document.store.StoreOutcome;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The resolution of a reference against the base URI of the schema resource that contains it (GDR-FORMS-2): a sub-schema
 * that declares an {@code $id} is an embedded resource, a reference within it resolves inside it, and
 * {@link SchemaValidator#required(FormSchema, Map)} follows the same target as
 * {@link SchemaValidator#validate(FormSchema, Map)}; a reference that selects no resource of the file stays refused.
 * <p>
 * The schemas are literals, being the contract.
 */
@DisplayName("References within embedded schema resources (GDR-FORMS-2)")
class SchemaResourceReferencesTest {

    private static final FormsResult.Outcome MALFORMED = new FormsResult.PassedOn(StoreOutcome.MALFORMED);

    /** A relative reference inside an embedded resource and a definition of the same name at the root. */
    private static final String EMBEDDED_CONTACT = """
            { "type": "object",
              "properties": {
                "contact": { "$id": "https://example.com/contact", "$ref": "#/$defs/contact",
                  "$defs": { "contact": { "type": "object", "required": ["name"],
                                          "properties": { "name": { "type": "string" } } } } } },
              "$defs": { "contact": { "type": "object" } } }
            """;

    private final SchemaValidator validator = new SchemaValidator();

    static Stream<Arguments> references() {
        return Stream.of(
                Arguments.of("a relative reference resolves inside the embedded resource that holds it",
                        EMBEDDED_CONTACT, Map.of("contact", Map.of()), List.of("/contact/name")),
                Arguments.of("a schema without any embedded $id resolves against the root, as before",
                        "{ \"properties\": { \"contact\": { \"$ref\": \"#/$defs/contact\" } }, "
                                + "\"$defs\": { \"contact\": { \"required\": [\"name\"] } } }",
                        Map.of("contact", Map.of()), List.of("/contact/name")),
                Arguments.of("a nested embedded resource resolves inside itself, its parent inside the parent",
                        "{ \"properties\": { \"p\": { \"$id\": \"https://example.com/outer\", "
                                + "\"$defs\": { \"x\": { \"required\": [\"b\"] } }, "
                                + "\"properties\": { \"q\": { \"$id\": \"inner\", \"$ref\": \"#/$defs/x\", "
                                + "\"$defs\": { \"x\": { \"required\": [\"a\"] } } }, "
                                + "\"r\": { \"$ref\": \"#/$defs/x\" } } } } }",
                        Map.of("p", Map.of("q", Map.of(), "r", Map.of())), List.of("/p/q/a", "/p/r/b")),
                Arguments.of("a reference out of an embedded resource back to the root by its $id",
                        "{ \"$id\": \"https://example.com/root\", "
                                + "\"$defs\": { \"person\": { \"required\": [\"name\"] } }, "
                                + "\"properties\": { \"contact\": { \"$id\": \"https://example.com/contact\", "
                                + "\"$ref\": \"https://example.com/root#/$defs/person\" } } }",
                        Map.of("contact", Map.of()), List.of("/contact/name")),
                Arguments.of("a reference to an embedded resource by its relative $id",
                        "{ \"properties\": { \"a\": { \"$ref\": \"contact.json\" } }, "
                                + "\"$defs\": { \"c\": { \"$id\": \"contact.json\", \"required\": [\"name\"] } } }",
                        Map.of("a", Map.of()), List.of("/a/name")),
                Arguments.of("an anchor resolves within the embedded resource that declares it",
                        "{ \"$ref\": \"c.json\", \"$defs\": { \"c\": { \"$id\": \"c.json\", \"$ref\": \"#person\", "
                                + "\"$defs\": { \"p\": { \"$anchor\": \"person\", \"required\": [\"name\"] } } } } }",
                        Map.of(), List.of("/name")));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: {0}")
    @MethodSource("references")
    void reference(String description, String text, Map<String, @Nullable Object> data, List<String> missing) {
        var schema = schema(text);

        assertAll(
                () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(missing),
                        validator.required(schema, data), "required follows the reference into its resource"),
                () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, data),
                        "validate does not evaluate required"));
    }

    @Test
    @DisplayName("GDR-FORMS-2: validate and required follow the same target of a reference in an embedded resource")
    void validateAndRequiredAgree() {
        var schema = schema(EMBEDDED_CONTACT);

        assertAll(
                () -> assertEquals(new ValidationResult.Invalid(
                                List.of(new Violation("/contact/name", "must be of type string"))),
                        validator.validate(schema, Map.of("contact", Map.of("name", 5))),
                        "validate applies the definition of the embedded resource"),
                () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/contact/name")),
                        validator.required(schema, Map.of("contact", Map.of())),
                        "required applies the same definition"));
    }

    static Stream<Arguments> outside() {
        return Stream.of(
                Arguments.of("an absolute URI no resource of the file has",
                        "{ \"$ref\": \"https://example.com/other\" }"),
                Arguments.of("a relative URI no resource of the file has",
                        "{ \"properties\": { \"a\": { \"$ref\": \"other.json#/$defs/a\" } } }"),
                Arguments.of("the URI of a resource of another file inside an embedded resource",
                        "{ \"$defs\": { \"c\": { \"$id\": \"https://example.com/c\", \"$ref\": \"d.json\" } } }"));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: {0} is MALFORMED, naming the schema file")
    @MethodSource("outside")
    void outsideTheFile(String description, String text) {
        var result = FormSchema.parse(PATH, text.getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertEquals(MALFORMED, result.outcome(), "the schema is refused"),
                () -> assertEquals(PATH + ": $ref: a reference outside the schema file", result.detail(),
                        "the detail names the file and the fixed reason"));
    }
}
