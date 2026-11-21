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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * GDR-NFR-3 for schema validation and the codecs of {@code git-forms}: no violation message, no violation pointer, no
 * result detail and no log line repeats a value of the instance. The library's own messages would quote it (for example
 * the value that is not one of an {@code enum}); the messages of {@link SchemaValidator} are composed from keyword and
 * constraint instead.
 * <p>
 * Every string value is generated between tildes, which no fixed text contains; every number is generated six digits
 * long, beyond every constraint of the schema, which itself is a literal.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@DisplayName("Validation and codecs repeat no instance value (GDR-NFR-3)")
class ValidationSecretsTest {

    /** A schema whose every keyword fails for the generated values. */
    private static final String SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "properties": {
                "name": { "type": "string", "maxLength": 3 },
                "count": { "type": "integer", "maximum": 5 },
                "kind": { "enum": ["a", "b"] },
                "code": { "type": "string", "pattern": "^[0-9]+$" },
                "fixed": { "const": "x" },
                "list": { "type": "array", "uniqueItems": true },
                "flag": { "type": "boolean" }
              }
            }
            """;

    private static String secret() {
        return "~" + Generators.letterStrings(4, 16).next() + "~";
    }

    private static long number() {
        return Generators.integers(100_000, 999_999).next();
    }

    private static void assertNoValue(String what, @Nullable String text, List<String> values) {
        assertAll(values.stream().<Executable>map(value -> () -> assertFalse(String.valueOf(text).contains(value),
                () -> what + " repeats no value of the instance: " + text)));
    }

    private static void assertNoLogLine(List<String> values) {
        assertAll(TestLoggerFactory.getTestHandler().getRecords().stream()
                .<Executable>map(logRecord -> () -> assertNoValue("a log line", String.valueOf(logRecord.getMessage()),
                        values)));
    }

    @Test
    @DisplayName("GDR-NFR-3: no violation message, pointer or detail of validate repeats an instance value")
    void validate() {
        var secret = secret();
        var count = number();
        Map<String, @Nullable Object> data = new LinkedHashMap<>();
        data.put("name", secret);
        data.put("count", count);
        data.put("kind", secret);
        data.put("code", secret);
        data.put("fixed", secret);
        data.put("list", List.of(secret, secret));
        data.put("flag", secret);
        data.put("extra", secret);
        var values = List.of(secret, Long.toString(count));

        var result = new SchemaValidator().validate(SchemaValidatorTest.schema(SCHEMA), data);

        var invalid = assertInstanceOf(ValidationResult.Invalid.class, result, "every property is invalid");
        List<Executable> checks = new ArrayList<>();
        for (Violation violation : invalid.violations()) {
            checks.add(() -> assertNoValue("a message", violation.message(), values));
            checks.add(() -> assertNoValue("a pointer", violation.pointer(), values));
        }
        checks.add(() -> assertNoValue("the detail", result.detail().orElseThrow(), values));
        checks.add(() -> assertEquals(8, invalid.violations().size(), "one violation per property"));
        checks.add(() -> assertNoLogLine(values));
        assertAll(checks);
    }

    @Test
    @DisplayName("GDR-NFR-3: the detail of a YAML refusal of the codec repeats no content")
    void yamlCodec() {
        var secret = secret();
        var key = Generators.strings("abcdefghijklmnopqrstuvwxyz", 12, 20).next();
        var codec = new YamlDocumentCodec(SchemaValidatorTest.schema(SCHEMA));

        var result = codec.decode((key + ": \"" + secret + "\"\n" + key + ": 1\n").getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertEquals(StoreOutcome.MALFORMED, result.outcome(), "a duplicate key is refused"),
                () -> assertNoValue("the detail", result.detail(), List.of(secret, key)),
                () -> assertNoLogLine(List.of(secret, key)));
    }

    @Test
    @DisplayName("GDR-NFR-3: the detail of a counter refusal repeats no content")
    void counterCodec() {
        var secret = secret();

        var result = new CounterCodec().decode((secret + "\n").getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertEquals(StoreOutcome.MALFORMED, result.outcome(), "the counter is refused"),
                () -> assertNoValue("the detail", result.detail(), List.of(secret)));
    }

    @Test
    @DisplayName("GDR-NFR-3: the detail of a refused schema repeats no content of the schema")
    void refusedSchema() {
        var secret = secret();

        var result = FormSchema.parse(SchemaValidatorTest.PATH,
                ("{ \"$schema\": \"" + secret + "\" }").getBytes(StandardCharsets.UTF_8));

        assertAll(
                () -> assertFalse(result.isOk(), "another dialect is refused"),
                () -> assertNoValue("the detail", result.detail(), List.of(secret)));
    }
}
