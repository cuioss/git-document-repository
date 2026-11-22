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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The schemas a reference may select and the removal of {@code required} for validation (GDR-FORMS-2): a reference to
 * an object that is no schema, the value of {@code dependentRequired} or {@code $vocabulary} or a map of named
 * sub-schemas, is refused, while a reference to the schema that holds it stays valid; and the objects the dynamic scope
 * of a {@code $dynamicRef} may bind it to are schema objects, so {@link SchemaValidator#validate(FormSchema, Map)} does
 * not evaluate their {@code required} and {@link SchemaValidator#required(FormSchema, Map)} does.
 * <p>
 * The schemas are literals, being the contract.
 */
@DisplayName("Reference targets that are no schema and the dynamic scope of $dynamicRef (GDR-FORMS-2)")
class SchemaDynamicScopeRequiredTest {

    private static final FormsResult.Outcome MALFORMED = new FormsResult.PassedOn(StoreOutcome.MALFORMED);

    private static final String KEYWORD_VALUE = "$ref: a reference into the value of dependentRequired or "
            + "$vocabulary, or to a map of named sub-schemas";

    /** A $dynamicRef whose static target is c, while its dynamic scope may bind it to x of the outer resource. */
    private static final String DYNAMIC_SCOPE = """
            { "$id": "https://example.invalid/a", "$ref": "b",
              "x": { "$dynamicAnchor": "node", "required": ["name"] },
              "$defs": { "b": { "$id": "b", "$dynamicRef": "c#node" },
                         "c": { "$id": "c", "$dynamicAnchor": "node" } } }
            """;

    private final SchemaValidator validator = new SchemaValidator();

    static Stream<Arguments> refusedTargets() {
        return Stream.of(
                Arguments.of("a $ref to the map of dependentRequired",
                        "{ \"properties\": { \"p\": { \"$ref\": \"#/$defs/h\" }, "
                                + "\"q\": { \"$ref\": \"#/$defs/h/dependentRequired\" } }, "
                                + "\"$defs\": { \"h\": { \"dependentRequired\": { \"required\": [\"x\"] } } } }",
                        KEYWORD_VALUE),
                Arguments.of("a $ref to the map of $vocabulary",
                        "{ \"$vocabulary\": { \"https://json-schema.org/draft/2020-12/vocab/core\": true }, "
                                + "\"properties\": { \"q\": { \"$ref\": \"#/$vocabulary\" } } }",
                        KEYWORD_VALUE),
                Arguments.of("a $ref to the map of properties",
                        "{ \"properties\": { \"required\": { \"type\": \"string\" }, "
                                + "\"q\": { \"$ref\": \"#/properties\" } } }",
                        KEYWORD_VALUE),
                Arguments.of("a $ref to the map of $defs",
                        "{ \"$ref\": \"#/$defs\", \"$defs\": { \"secretName\": { \"type\": \"object\" } } }",
                        KEYWORD_VALUE),
                Arguments.of("a $ref to an array of dependentRequired",
                        "{ \"properties\": { \"q\": { \"$ref\": \"#/dependentRequired/secretName\" } }, "
                                + "\"dependentRequired\": { \"secretName\": [\"x\"] } }",
                        "$ref: a reference that selects no schema of the file"),
                Arguments.of("a $dynamicRef whose dynamic scope may bind it into a const",
                        "{ \"$id\": \"https://example.invalid/a\", \"$ref\": \"b\", "
                                + "\"$defs\": { \"b\": { \"$id\": \"b\", \"$dynamicRef\": \"c#node\" }, "
                                + "\"c\": { \"$id\": \"c\", \"$dynamicAnchor\": \"node\" }, "
                                + "\"k\": { \"const\": { \"$dynamicAnchor\": \"node\", \"required\": [\"secret\"] } } } }",
                        "$dynamicRef: a reference into the value of const, enum, default or examples"));
    }

    @ParameterizedTest(name = "GDR-FORMS-2: {0} is MALFORMED with a fixed reason")
    @MethodSource("refusedTargets")
    void refusedTarget(String description, String text, String reason) {
        var result = FormSchema.parse(PATH, text.getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertEquals(MALFORMED, result.outcome(), "the schema is refused"),
                () -> assertEquals(PATH + ": " + reason, result.detail(),
                        "the detail names the file and the fixed reason"),
                () -> assertFalse(String.valueOf(result.detail()).contains("secret"),
                        "the detail quotes nothing of the schema"));
    }

    @Test
    @DisplayName("GDR-FORMS-2: a reference to the schema that holds dependentRequired keeps the map as written")
    void referenceToHolderOfDependentRequired() {
        var schema = schema("{ \"properties\": { \"p\": { \"$ref\": \"#/$defs/h\" } }, "
                + "\"$defs\": { \"h\": { \"dependentRequired\": { \"required\": [\"x\"] } } } }");

        assertAll(
                () -> assertEquals(new ValidationResult.Invalid(
                                List.of(new Violation("/p", "must have the properties the schema requires together"))),
                        validator.validate(schema, Map.of("p", Map.of("required", true))),
                        "a property named required still requires x"),
                () -> assertEquals(new ValidationResult.Valid(),
                        validator.validate(schema, Map.of("p", Map.of("required", true, "x", 1))),
                        "with x the object is valid"));
    }

    @Test
    @DisplayName("GDR-FORMS-2: a reference to the schema that holds properties keeps a property named required")
    void referenceToHolderOfProperties() {
        var schema = schema("{ \"properties\": { \"p\": { \"$ref\": \"#/$defs/h\" } }, "
                + "\"$defs\": { \"h\": { \"properties\": { \"required\": { \"type\": \"string\" } } } } }");

        assertEquals(new ValidationResult.Invalid(List.of(new Violation("/p/required", "must be of type string"))),
                validator.validate(schema, Map.of("p", Map.of("required", 5))),
                "the property named required is validated as written");
    }

    @Test
    @DisplayName("GDR-FORMS-2: required of a schema the dynamic scope may bind a $dynamicRef to is deferred")
    void dynamicScopeTargetRequiredDeferred() {
        var schema = schema(DYNAMIC_SCOPE);

        assertAll(
                () -> assertEquals(new ValidationResult.Valid(), validator.validate(schema, Map.of()),
                        "validate does not evaluate required of the dynamic-scope target"),
                () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/name")),
                        validator.required(schema, Map.of()), "required follows the dynamic-scope target"),
                () -> assertEquals(new ValidationResult.Valid(), validator.required(schema, Map.of("name", "a")),
                        "with the field present nothing is missing"));
    }
}
