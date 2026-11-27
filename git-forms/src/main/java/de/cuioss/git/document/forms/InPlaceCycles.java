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
 * {@code else}, the schema of an entry of {@code dependentSchemas}, the target of a {@code $ref} or
 * {@code $dynamicRef} within the file, resolved through {@link SchemaResources}, and, for a {@code $dynamicRef}, every
 * schema object of the file its dynamic scope may bind it to ({@link SchemaResources#targets(Map, String)}): the
 * validator resolves a {@code $dynamicRef} through the resources being evaluated, outermost first, so it can return to
 * an outer schema that no static target shows, and taking every candidate finds each cycle some scope could produce.
 * The keywords that descend into a child value ({@code properties}, {@code items}, {@code unevaluatedProperties} and
 * the others) are no edges, so a recursion that descends stays valid. The search is depth-first with the three states unvisited, on the path and done, over the
 * schema objects compared by identity, started from every schema object of the file
 * ({@link SchemaResources#schemaObjects()}), those under {@code $defs} and {@code definitions} included, so that a cycle
 * declared but never referenced is refused as well. Both the enumeration of the schema objects and the search use an
 * explicit stack, so that the check cannot overflow on a deeply nested schema.
 * <p>
 * The same search bounds the longest chain of schemas applied to the same value at {@value #MAX_IN_PLACE_CHAIN}
 * schemas, the first schema of the chain counting 1. A flat chain of references ({@code a0} referencing {@code a1},
 * {@code a1} referencing {@code a2}, and so on, all side by side under {@code $defs}) is nested only a few levels deep,
 * so no nesting limit bounds it; yet the {@code RefEvaluator} of the validator recurses once per link.
 * {@code SchemaChecks.holdsRequired} and {@code MandatoryFields} walk over explicit stacks and need no bound of their
 * own: the chain is bounded here for the validator only, and {@code MandatoryFields} meets at most the product of this
 * bound and the nesting limit {@value DataNode#MAX_DEPTH} of the data without recursing. The length of the longest chain from each schema object
 * is its height in the graph of in-place edges, computed after its edges, as each object leaves the path; as the graph
 * is acyclic once no cycle is found, every object is computed once, without recursion.
 */
final class InPlaceCycles {

    /** The most schemas a chain of schemas applied to the same value may hold, the first one counting 1. */
    static final int MAX_IN_PLACE_CHAIN = 128;

    /** The keywords whose sub-schemas are applied to the same value; the references are added separately. */
    private static final List<String> IN_PLACE = List.of("allOf", "anyOf", "oneOf", "not", "if", "then", "else",
            "dependentSchemas");

    private final SchemaResources resources;

    /** The state of a schema object: absent while unvisited, {@code true} on the path, {@code false} done. */
    private final Map<Object, Boolean> onPath = new IdentityHashMap<>();

    /** The length of the longest chain of in-place edges from each schema object that is done, itself included. */
    private final Map<Object, Integer> chain = new IdentityHashMap<>();

    private boolean chainTooLong;

    private InPlaceCycles(SchemaResources resources) {
        this.resources = resources;
    }

    /** What the search over the in-place edges of a schema file found. */
    enum Finding {

        /** No cycle, and no chain longer than {@value InPlaceCycles#MAX_IN_PLACE_CHAIN} schemas. */
        NONE,

        /** Some schema object reaches itself through in-place edges only. */
        CYCLE,

        /**
         * No cycle, but a chain of more than {@value InPlaceCycles#MAX_IN_PLACE_CHAIN} schemas applied to the same
         * value.
         */
        CHAIN_TOO_LONG
    }

    /**
     * Searches a schema file for a cycle of in-place edges and for a chain of them longer than
     * {@value #MAX_IN_PLACE_CHAIN} schemas; a cycle takes precedence.
     *
     * @param resources the resources of the file, against which references are resolved
     * @param schemas   the schema objects of the file, {@link SchemaResources#schemaObjects()}, each a start
     * @return what the search found
     */
    static Finding find(SchemaResources resources, List<Map<?, ?>> schemas) {
        var search = new InPlaceCycles(resources);
        for (Map<?, ?> start : schemas) {
            if (!search.onPath.containsKey(start) && search.cycleFrom(start)) {
                return Finding.CYCLE;
            }
        }
        return search.chainTooLong ? Finding.CHAIN_TOO_LONG : Finding.NONE;
    }

    /**
     * Searches depth-first from one unvisited schema object; {@code true} when an edge returns onto the path. Each
     * object that leaves the path records the length of its longest chain and offers it to the object below it.
     */
    private boolean cycleFrom(Map<?, ?> start) {
        Deque<Frame> path = new ArrayDeque<>();
        enter(path, start);
        while (!path.isEmpty()) {
            Frame top = path.peek();
            if (!top.edges.hasNext()) {
                leave(path, top);
                continue;
            }
            Map<?, ?> next = top.edges.next();
            @Nullable Boolean state = onPath.get(next);
            if (Boolean.TRUE.equals(state)) {
                return true;
            }
            if (state == null) {
                enter(path, next);
            } else {
                top.offer(chain.getOrDefault(next, 1));
            }
        }
        return false;
    }

    private void enter(Deque<Frame> path, Map<?, ?> schema) {
        onPath.put(schema, true);
        path.push(new Frame(schema, edges(schema).iterator()));
    }

    /** Marks the object on top of the path done, with the longest chain from it, and offers that chain below it. */
    private void leave(Deque<Frame> path, Frame top) {
        int length = top.longestEdge + 1;
        onPath.put(top.schema, false);
        chain.put(top.schema, length);
        if (length > MAX_IN_PLACE_CHAIN) {
            chainTooLong = true;
        }
        path.pop();
        Frame below = path.peek();
        if (below != null) {
            below.offer(length);
        }
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

    /**
     * A schema object on the path, the edges not yet followed from it, and the longest chain found so far behind the
     * edges already followed.
     */
    private static final class Frame {

        private final Map<?, ?> schema;
        private final Iterator<Map<?, ?>> edges;
        private int longestEdge;

        Frame(Map<?, ?> schema, Iterator<Map<?, ?>> edges) {
            this.schema = schema;
            this.edges = edges;
        }

        /** Takes the longest chain behind one edge into account. */
        void offer(int length) {
            longestEdge = Math.max(longestEdge, length);
        }
    }
}
