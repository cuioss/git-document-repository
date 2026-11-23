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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.cuioss.test.generator.junit.EnableGeneratorController;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

@DisplayName("Distinct text generator")
@EnableGeneratorController
class DistinctTextGeneratorTest {

    /** The documented shape of a value: marker, number from 1, separator, three to eight lower-case letters. */
    private static final Pattern VALUE = Pattern.compile("gdrdistinct([1-9]\\d*)-[a-z]{3,8}");

    private final DistinctTextGenerator generator = new DistinctTextGenerator();

    @RepeatedTest(10)
    @DisplayName("Should start every value with the marker, then its number, the separator and letters")
    void shouldStartEveryValueWithMarker() {
        var values = Stream.generate(generator::next).limit(5).toList();

        assertAll("value shape", values.stream().map(value -> () -> {
            var matcher = VALUE.matcher(value);
            assertTrue(matcher.matches(), "The value has the documented shape: " + value);
            assertTrue(value.startsWith(DistinctTextGenerator.MARKER), "The value starts with the marker: " + value);
        }));
    }

    @Test
    @DisplayName("Should number the values of one instance from 1 upwards")
    void shouldNumberValuesFromOne() {
        var numbers = Stream.generate(generator::next).limit(12)
                .map(value -> {
                    var matcher = VALUE.matcher(value);
                    assertTrue(matcher.matches(), "The value has the documented shape: " + value);
                    return Long.parseLong(matcher.group(1));
                })
                .toList();

        assertEquals(IntStream.rangeClosed(1, 12).mapToObj(Long::valueOf).toList(), numbers,
                "The values carry the numbers 1 to 12 in order");
    }

    @Test
    @DisplayName("Should never yield a value that starts another value of the same instance")
    void shouldYieldNoValueThatIsPrefixOfAnother() {
        var values = Stream.generate(generator::next).limit(120).toList();

        assertAll("distinct values",
                () -> assertEquals(values.size(), Set.copyOf(values).size(), "Every value differs from the others"),
                () -> assertFalse(values.stream().anyMatch(value -> values.stream()
                                .anyMatch(other -> other != value && other.startsWith(value))),
                        "No value is the start of another, so value 1 is found in no text holding only value 12"));
    }

    @Test
    @DisplayName("Should yield distinct values to concurrent callers of one instance")
    void shouldYieldDistinctValuesToConcurrentCallers() throws Exception {
        var drawn = ConcurrentHashMap.<String>newKeySet();
        List<Future<?>> draws;
        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            draws = IntStream.range(0, 4)
                    .<Future<?>>mapToObj(thread -> executor.submit(() -> {
                        for (var draw = 0; draw < 250; draw++) {
                            drawn.add(generator.next());
                        }
                    }))
                    .toList();
        }
        for (Future<?> draw : draws) {
            draw.get();
        }

        assertEquals(1000, drawn.size(), "1000 draws on four threads yield 1000 different values");
    }

    @Test
    @DisplayName("Should yield values usable as reference name component and that never read as a commit id")
    void shouldYieldValuesUsableAsNameComponent() {
        var values = Stream.generate(generator::next).limit(20).toList();

        assertAll("name use", values.stream().map(value -> () -> {
            assertTrue(Repository.isValidRefName("refs/heads/" + value),
                    "The value is a valid reference name component: " + value);
            assertFalse(ObjectId.isId(value), "The value is no commit id: " + value);
        }));
    }

    @Test
    @DisplayName("Should report the generated type and be registered by the module factory")
    void shouldBeRegisteredByModuleFactory() {
        var registered = GitGenerators.distinctTexts();

        assertAll("registration",
                () -> assertEquals(String.class, generator.getType(), "The generator names the text type"),
                () -> assertInstanceOf(DistinctTextGenerator.class, registered,
                        "The factory yields the distinct text generator"),
                () -> assertTrue(VALUE.matcher(registered.next()).matches(),
                        "A value of the factory's generator has the documented shape"));
    }
}
