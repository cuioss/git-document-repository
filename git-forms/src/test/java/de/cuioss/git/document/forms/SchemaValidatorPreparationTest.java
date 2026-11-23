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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * One preparation of the validator per schema (GDR-FORMS-2): {@link FormSchema#parse(String, byte[])} prepares the
 * validator of {@code dev.harrel:json-schema} once, {@link SchemaValidator#validate(FormSchema, Map)} and
 * {@link SchemaValidator#required(FormSchema, Map)} only read it, also where {@code unevaluatedProperties} holds
 * {@code required}, and the one validator serves several threads with the sequential result.
 * <p>
 * The schema is a literal, being the contract; the strings of the data are generated, the length that violates
 * {@code maxLength} follows from the constraint.
 */
@EnableGeneratorController
@DisplayName("SchemaValidator: one preparation per schema (GDR-FORMS-2)")
class SchemaValidatorPreparationTest {

    /**
     * A schema whose {@code unevaluatedProperties} holds {@code required}, so {@code required} applies sub-schemas by the
     * references the preparation registered.
     */
    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": { "title": { "type": "string", "maxLength": 10 } },
              "allOf": [ { "properties": { "entry": { "type": "object" } } } ],
              "unevaluatedProperties": { "type": "object", "required": ["name"] }
            }
            """;

    private static final int CALLS = 10;
    private static final int THREADS = 4;
    private static final Duration DEADLINE = Duration.ofSeconds(30);

    private final SchemaValidator validator = new SchemaValidator();

    /** Data with a title too long, an evaluated {@code entry} and an unevaluated {@code other} that lacks its name. */
    private static Map<String, @Nullable Object> data() {
        Map<String, @Nullable Object> data = new LinkedHashMap<>();
        data.put("title", Generators.letterStrings(11, 30).next());
        data.put("entry", Map.of());
        data.put("other", Map.of());
        return data;
    }

    @Test
    @DisplayName("GDR-FORMS-2: parsing prepares once, validate and required prepare nothing")
    void preparedOnce() {
        int beforeParse = SchemaValidator.preparations();
        var schema = SchemaValidatorTest.schema(SCHEMA);
        int afterParse = SchemaValidator.preparations();
        var data = data();

        for (int call = 0; call < CALLS; call++) {
            validator.validate(schema, data);
            validator.required(schema, data);
        }

        assertAll(
                () -> assertEquals(1, afterParse - beforeParse, "parse prepares exactly once"),
                () -> assertEquals(afterParse, SchemaValidator.preparations(),
                        "ten validate and ten required calls add no preparation"));
    }

    @Test
    @DisplayName("GDR-FORMS-2: one schema serves several threads with the sequential result")
    void concurrentUse() throws Exception {
        var schema = SchemaValidatorTest.schema(SCHEMA);
        var data = data();
        var expectedValidate = validator.validate(schema, data);
        var expectedRequired = validator.required(schema, data);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<List<ValidationResult>>> tasks = new ArrayList<>();
        for (int thread = 0; thread < THREADS; thread++) {
            tasks.add(() -> {
                start.await();
                List<ValidationResult> results = new ArrayList<>();
                for (int call = 0; call < CALLS; call++) {
                    results.add(validator.validate(schema, data));
                    results.add(validator.required(schema, data));
                }
                return results;
            });
        }
        List<ValidationResult> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(THREADS);
        List<Future<List<ValidationResult>>> futures = new ArrayList<>();
        try {
            for (Callable<List<ValidationResult>> task : tasks) {
                futures.add(executor.submit(task));
            }
            start.countDown();
            long deadline = System.nanoTime() + DEADLINE.toNanos();
            for (Future<List<ValidationResult>> future : futures) {
                results.addAll(future.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
            }
        } finally {
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(DEADLINE.toSeconds(), TimeUnit.SECONDS), "the threads end");
        }

        List<ValidationResult> expected = new ArrayList<>();
        for (int index = 0; index < THREADS * CALLS; index++) {
            expected.add(expectedValidate);
            expected.add(expectedRequired);
        }
        assertAll(
                () -> assertEquals(new ValidationResult.Invalid(
                                List.of(new Violation("/title", "must have at most 10 characters"))), expectedValidate,
                        "the sequential result of validate"),
                () -> assertEquals(new ValidationResult.MandatoryFieldsMissing(List.of("/other/name")),
                        expectedRequired, "the sequential result of required"),
                () -> assertEquals(expected, results, "every concurrent call agrees with the sequential result"));
    }
}
