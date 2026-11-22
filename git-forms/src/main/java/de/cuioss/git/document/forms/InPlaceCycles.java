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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;


import org.jspecify.annotations.Nullable;

/**
 * The check of {@link FormSchema#parse(String, byte[])} for a cycle of schemas that JSON Schema Draft 2020-12 applies
 * to the same value without descending into a child value. Applying such a schema never ends: the validator
 * {@code dev.harrel:json-schema} 1.9.1 resolves the reference and validates the same value again without a repeat check
 * ({@code RefEvaluator} and {@code DynamicRefEvaluator} in {@code Applicators.java}), so the schema is refused instead.
 * <p>
 * An in-place edge leads from a schema object to a schema that Draft 2020-12 applies to the same value: a member of
 * {@code allOf}, {@code anyOf} and {@code oneOf}, the sub-schema of {@code not}, {@code if}, {@code then} and
 * {@code else}, the schema of an entry of {@code dependentSchemas}, and the target of a {@code $ref} or
 * {@code $dynamicRef} within the file, resolved through {@link SchemaResources}. The keywords that descend into a child
 * value ({@code properties}, {@code items}, {@code unevaluatedProperties} and the others) are no edges, so a recursion
 * that descends stays valid. The search is depth-first with the three states unvisited, on the path and done, over the
 * schema objects compared by identity, started from every schema object of the file
 * ({@link SchemaResources#schemaObjects()}), those under {@code $defs} and {@code definitions} included, so that a cycle
 * declared but never referenced is refused as well. Both the enumeration of the schema objects and the search use an
 * explicit stack, so that the check cannot overflow on a deeply nested schema.
 */
final class InPlaceCycles {

    /** The keywords whose sub-schemas are applied to the same value; the references are added separately. */
    private static final List<String> IN_PLACE = List.of("allOf", "anyOf", "oneOf", "not", "if", "then", "else",
            "dependentSchemas");

    private final SchemaResources resources;

    /** The state of a schema object: absent while unvisited, {@code true} on the path, {@code false} done. */
    private final Map<Object, Boolean> onPath = new IdentityHashMap<>();

    private InPlaceCycles(SchemaResources resources) {
        this.resources = resources;
    }

    /**
     * Tells whether a schema file holds a cycle of in-place edges.
     *
     * @param resources the resources of the file, against which references are resolved
     * @param schemas   the schema objects of the file, {@link SchemaResources#schemaObjects()}, each a start
     * @return {@code true} when some schema object of the file reaches itself through in-place edges only
     */
    static boolean present(SchemaResources resources, List<Map<?, ?>> schemas) {
        var search = new InPlaceCycles(resources);
        for (Map<?, ?> start : schemas) {
            if (!search.onPath.containsKey(start) && search.cycleFrom(start)) {
                return true;
            }
        }
        return false;
    }

    /** Searches depth-first from one unvisited schema object; {@code true} when an edge returns onto the path. */
    private boolean cycleFrom(Map<?, ?> start) {
        Deque<Frame> path = new ArrayDeque<>();
        enter(path, start);
        while (!path.isEmpty()) {
            Frame top = path.peek();
            if (!top.edges().hasNext()) {
                onPath.put(top.schema(), false);
                path.pop();
                continue;
            }
            Map<?, ?> next = top.edges().next();
            @Nullable Boolean state = onPath.get(next);
            if (Boolean.TRUE.equals(state)) {
                return true;
            }
            if (state == null) {
                enter(path, next);
            }
        }
        return false;
    }

    private void enter(Deque<Frame> path, Map<?, ?> schema) {
        onPath.put(schema, true);
        path.push(new Frame(schema, edges(schema).iterator()));
    }

    /** The schema objects a schema applies to the same value. */
    private List<Map<?, ?>> edges(Map<?, ?> schema) {
        List<Map<?, ?>> edges = new ArrayList<>();
        for (String keyword : IN_PLACE) {
            edges.addAll(FormSchema.subSchemas(keyword, schema.get(keyword)));
        }
        edges.addAll(FormSchema.targets(resources, schema));
        return edges;
    }

    /** A schema object on the path and the edges not yet followed from it. */
    private record Frame(Map<?, ?> schema, Iterator<Map<?, ?>> edges) {
    }
}
