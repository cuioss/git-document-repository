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
import static org.junit.jupiter.api.Assertions.assertFalse;

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
 * {@link SchemaValidator#validate(FormSchema, Map)}; a reference that selects no resource of the file stays refused,
 * as do a noncanonical array index, a reference into a literal value, a reference cycle applied to the same value, and
 * a misplaced split annotation in a schema only a reference selects.
 * <p>
 * The schemas are literals, being the contract.
 */
@DisplayName("References within embedded schema resources (GDR-FORMS-2)")
class SchemaResourceReferencesTest {

    private static final FormsResult.Outcome MALFORMED = new FormsResult.PassedOn(StoreOutcome.MALFORMED);

    private static final String CYCLE = "a reference cycle that returns to the same value without descending into a "
            + "child value";

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

    static Stream<Arguments> unknownKeywordTargets() {
        return Stream.of(
                Arguments.of("a $ref to a schema under an unknown keyword",
                        "{ \"$ref\": \"#/x-contact\", \"x-contact\": { \"required\": [\"name\"] } }", Map.of(),
                        List.of("/name")),
                Arguments.of("a $dynamicRef to a schema under an unknown keyword",
                        "{ \"$dynamicRef\": \"#/x-person\", \"x-person\": { \"required\": [\"id\"], "
                                + "\"properties\": { \"id\": { \"type\": \"string\" } } } }",
                        Map.of(), List.of("/id")),
                Arguments.of("a $ref from a property into an object nested under an unknown keyword",
                        "{ \"properties\": { \"c\": { \"$ref\": \"#/x-forms/contact\" } }, "
                                + "\"x-forms\": { \"contact\": { \"required\": [\"name\"] } } }",
                        Map.of("c", Map.of()), List.of("/c/name")));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: {0}: validate stays valid, required reports the field")
    @MethodSource("unknownKeywordTargets")
    void unknownKeywordTarget(String description, String text, Map<String, @Nullable Object> data,
            List<String> missing) {
        var schema = schema(text);

        assertAll(
                () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, data),
                        "validate does not evaluate required in the target"),
                () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(missing),
                        validator.required(schema, data), "required follows the same target"));
    }

    @Test
    @DisplayName("GDR-FORMS-2: a key named required in the value of const is data, not removed for validate")
    void requiredInConstUnchanged() {
        var schema = schema("{ \"properties\": { \"a\": { \"const\": { \"required\": [\"x\"] } } } }");

        assertAll(
                () -> assertEquals(new ValidationResult.Valid(),
                        validator.validate(schema, Map.of("a", Map.of("required", List.of("x")))),
                        "the value of const keeps the key"),
                () -> assertEquals(new ValidationResult.Invalid(
                                List.of(new Violation("/a", "must be the value the schema prescribes"))),
                        validator.validate(schema, Map.of("a", Map.of())), "and another value differs"));
    }

    static Stream<Arguments> refusedSelections() {
        return Stream.of(
                Arguments.of("a $ref to a missing fragment", "{ \"$ref\": \"#/$defs/missing\", \"$defs\": {} }",
                        "$ref: a reference that selects no schema of the file"),
                Arguments.of("a $ref to a string", "{ \"properties\": { \"a\": { \"$ref\": \"#/x-label\" } }, "
                        + "\"x-label\": \"secret text\" }",
                        "$ref: a reference that selects no schema of the file"),
                Arguments.of("a $dynamicRef to an anchor no schema declares", "{ \"$dynamicRef\": \"#secretAnchor\" }",
                        "$dynamicRef: a reference that selects no schema of the file"),
                Arguments.of("required that is no array in a target under an unknown keyword",
                        "{ \"$ref\": \"#/x-contact\", \"x-contact\": { \"required\": \"secretName\" } }",
                        "required: not an array of unique strings"),
                Arguments.of("required under anyOf in a target under an unknown keyword",
                        "{ \"$ref\": \"#/x-contact\", \"x-contact\": { \"anyOf\": [ { \"required\": [\"secret\"] } ] } }",
                        "required: not allowed under anyOf, where it cannot be deferred"),
                Arguments.of("a $ref whose fragment holds a plus sign",
                        "{ \"$ref\": \"#/$defs/secret+name\", \"$defs\": { \"secret+name\": {}, \"secret name\": {} } }",
                        "$ref: a fragment with a plus sign, which the validator decodes as a space"),
                Arguments.of("a $dynamicRef whose fragment holds a plus sign",
                        "{ \"$dynamicRef\": \"#secret+anchor\", \"$defs\": { \"a\": { \"$anchor\": \"secret anchor\" } } }",
                        "$dynamicRef: a fragment with a plus sign, which the validator decodes as a space"),
                Arguments.of("a split annotation that is no boolean in a target under an unknown keyword",
                        "{ \"$ref\": \"#/x\", \"x\": { \"gdr-split\": \"secret\" } }", "gdr-split: not a boolean"),
                Arguments.of("a split annotation in a target under an unknown keyword",
                        "{ \"$ref\": \"#/x\", \"x\": { \"type\": \"object\", \"gdr-split\": true } }",
                        "gdr-split: only on a top-level property of type object"),
                Arguments.of("a $ref with an array index that has a leading zero",
                        "{ \"$ref\": \"#/allOf/00\", \"allOf\": [ {} ] }",
                        "$ref: a reference that selects no schema of the file"),
                Arguments.of("a $ref with an array index that has a minus sign",
                        "{ \"$ref\": \"#/allOf/-0\", \"allOf\": [ {} ] }",
                        "$ref: a reference that selects no schema of the file"),
                Arguments.of("a $ref into the value of const",
                        "{ \"properties\": { \"q\": { \"$ref\": \"#/$defs/h/const\" } }, "
                                + "\"$defs\": { \"h\": { \"const\": { \"secret\": 1 } } } }",
                        "$ref: a reference into the value of const, enum, default or examples"),
                Arguments.of("a $dynamicRef into an object nested in the value of enum",
                        "{ \"$dynamicRef\": \"#/$defs/h/enum/0/secret\", "
                                + "\"$defs\": { \"h\": { \"enum\": [ { \"secret\": {} } ] } } }",
                        "$dynamicRef: a reference into the value of const, enum, default or examples"));
    }

    static Stream<Arguments> acceptedNeighbours() {
        return Stream.of(
                Arguments.of("a split annotation on a top-level property of type object",
                        "{ \"properties\": { \"part\": { \"type\": \"object\", \"gdr-split\": true } } }",
                        List.of("part")),
                Arguments.of("a $ref with the canonical array index 0",
                        "{ \"$ref\": \"#/allOf/0\", \"allOf\": [ { \"type\": \"object\" } ] }", List.of()),
                Arguments.of("a $ref with the canonical array index 10",
                        "{ \"$ref\": \"#/prefixItems/10\", \"prefixItems\": [ {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, "
                                + "{ \"type\": \"object\" } ] }",
                        List.of()));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: {0} stays accepted")
    @MethodSource("acceptedNeighbours")
    void acceptedNeighbour(String description, String text, List<String> splitProperties) {
        var schema = schema(text);

        assertAll(
                () -> assertEquals(splitProperties, schema.splitProperties(), "the split properties as declared"),
                () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, Map.of()),
                        "validate follows the reference"));
    }

    @Test
    @DisplayName("GDR-FORMS-2: a const that no reference selects is compared as written")
    void constNoReferenceSelects() {
        var schema = schema("{ \"properties\": { \"q\": { \"$ref\": \"#/$defs/h\" } }, "
                + "\"$defs\": { \"h\": { \"const\": {} } } }");

        assertAll(
                () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, Map.of("q", Map.of())),
                        "the empty object equals the literal"),
                () -> assertEquals(new ValidationResult.Invalid(
                                List.of(new Violation("/q", "must be the value the schema prescribes"))),
                        validator.validate(schema, Map.of("q", Map.of("required", List.of("x")))),
                        "an object with a key named required differs from it"));
    }

    static Stream<Arguments> percentEncodedFragments() {
        return Stream.of(
                Arguments.of("an encoded space", "#/$defs/a%20b", "a b"),
                Arguments.of("an encoded plus sign", "#/$defs/a%2Bb", "a+b"));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: a fragment with {0} is accepted and resolved")
    @MethodSource("percentEncodedFragments")
    void percentEncodedFragment(String description, String reference, String name) {
        var schema = schema("{ \"properties\": { \"p\": { \"$ref\": \"" + reference + "\" } }, \"$defs\": { \""
                + name + "\": { \"required\": [\"name\"], \"properties\": { \"name\": { \"type\": \"string\" } } } } }");

        assertAll(
                () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/p/name")),
                        validator.required(schema, Map.of("p", Map.of())), "required follows the decoded fragment"),
                () -> assertEquals(new ValidationResult.Invalid(
                                List.of(new Violation("/p/name", "must be of type string"))),
                        validator.validate(schema, Map.of("p", Map.of("name", 5))),
                        "validate follows the same target"));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: {0} is MALFORMED with a fixed reason")
    @MethodSource("refusedSelections")
    void refusedSelection(String description, String text, String reason) {
        var result = FormSchema.parse(PATH, text.getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertEquals(MALFORMED, result.outcome(), "the schema is refused"),
                () -> assertEquals(PATH + ": " + reason, result.detail(),
                        "the detail names the file and the fixed reason"),
                () -> assertFalse(String.valueOf(result.detail()).contains("secret"),
                        "the detail quotes nothing of the schema"));
    }

    static Stream<Arguments> descendingRecursion() {
        return Stream.of(
                Arguments.of("a definition whose property references it again",
                        "{ \"$ref\": \"#/$defs/node\", \"$defs\": { \"node\": { \"type\": \"object\", "
                                + "\"required\": [\"name\"], "
                                + "\"properties\": { \"child\": { \"$ref\": \"#/$defs/node\" } } } } }",
                        Map.of("name", "a", "child", Map.of("child", Map.of())),
                        List.of("/child/name", "/child/child/name")),
                Arguments.of("a tree of items that reference the root",
                        "{ \"properties\": { \"v\": { \"$ref\": \"#\" } }, \"items\": { \"$ref\": \"#\" } }",
                        Map.of("v", List.of(List.of(Map.of("v", List.of())))), List.of()),
                Arguments.of("a property under allOf that references the root",
                        "{ \"required\": [\"a\"], \"allOf\": [ { \"properties\": { \"next\": { \"$ref\": \"#\" } } } ] }",
                        Map.of("a", 1, "next", Map.of()), List.of("/next/a")),
                Arguments.of("a tree whose items bind a $dynamicRef to the root by its $dynamicAnchor",
                        "{ \"$id\": \"https://example.invalid/tree\", \"$dynamicAnchor\": \"node\", "
                                + "\"type\": \"object\", \"properties\": { \"children\": { \"type\": \"array\", "
                                + "\"items\": { \"$dynamicRef\": \"#node\" } } } }",
                        Map.of("children", List.of(Map.of("children", List.of()))), List.of()),
                Arguments.of("a $dynamicRef whose every candidate target holds no cycle",
                        "{ \"$ref\": \"#/$defs/b\", \"$defs\": { \"b\": { \"$dynamicRef\": \"#node\" }, "
                                + "\"c\": { \"$dynamicAnchor\": \"node\", \"required\": [\"name\"] } } }",
                        Map.of(), List.of("/name")));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: {0} stays valid")
    @MethodSource("descendingRecursion")
    void descendingRecursionStaysValid(String description, String text, Map<String, @Nullable Object> data,
            List<String> missing) {
        var schema = schema(text);

        assertAll(
                () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, data),
                        "validate follows the recursion as deep as the data goes"),
                () -> assertEquals(missing.isEmpty() ? new ValidationResult.Valid()
                                : new ValidationResult.MandatoryFieldsMissing(missing),
                        validator.required(schema, data), "required follows it likewise"));
    }

    static Stream<Arguments> inPlaceCycles() {
        return Stream.of(
                Arguments.of("allOf with a $ref to the root",
                        "{ \"allOf\": [ { \"$ref\": \"#\" } ], \"unevaluatedProperties\": { \"required\": [\"x\"] } }"),
                Arguments.of("a $ref to the root", "{ \"$ref\": \"#\" }"),
                Arguments.of("two definitions that reference each other, never referenced from the root",
                        "{ \"$defs\": { \"secretFirst\": { \"$ref\": \"#/$defs/secretSecond\" }, "
                                + "\"secretSecond\": { \"$ref\": \"#/$defs/secretFirst\" } } }"),
                Arguments.of("anyOf with a $ref to the root", "{ \"anyOf\": [ { \"$ref\": \"#\" } ] }"),
                Arguments.of("if with a $ref to the root", "{ \"if\": { \"$ref\": \"#\" } }"),
                Arguments.of("dependentSchemas with a $ref to the root",
                        "{ \"dependentSchemas\": { \"secretName\": { \"$ref\": \"#\" } } }"),
                Arguments.of("a $dynamicRef to the root", "{ \"allOf\": [ { \"$dynamicRef\": \"#\" } ] }"),
                Arguments.of("a $dynamicRef whose dynamic scope binds it to the outer resource that references it",
                        "{ \"$id\": \"https://example.invalid/a\", \"$dynamicAnchor\": \"node\", \"$ref\": \"b\", "
                                + "\"$defs\": { \"b\": { \"$id\": \"b\", \"$dynamicRef\": \"c#node\" }, "
                                + "\"c\": { \"$id\": \"c\", \"$dynamicAnchor\": \"node\" } } }"));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: {0} is MALFORMED with a fixed reason")
    @MethodSource("inPlaceCycles")
    void inPlaceCycleRefused(String description, String text) {
        var result = FormSchema.parse(PATH, text.getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertEquals(MALFORMED, result.outcome(), "the schema is refused"),
                () -> assertEquals(PATH + ": " + CYCLE, result.detail(),
                        "the detail names the file and the fixed reason"),
                () -> assertFalse(String.valueOf(result.detail()).contains("secret"),
                        "the detail quotes no name of the schema"));
    }
}
