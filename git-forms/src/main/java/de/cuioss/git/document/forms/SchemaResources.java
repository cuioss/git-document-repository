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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


import dev.harrel.jsonschema.JsonNode;
import org.jspecify.annotations.Nullable;

/**
 * The schema resources of one schema file and the resolution of the references within it, as JSON Schema Draft 2020-12
 * resolves them (Core, section 8.2), checked against the source of {@code dev.harrel:json-schema} 1.9.1
 * ({@code JsonParser}, {@code SchemaRegistry}, {@code UriUtil}).
 * <p>
 * The root is a resource under {@link #BASE}, and under its {@code $id} resolved against {@link #BASE} when it declares
 * one. Every object within the file that declares an {@code $id} is an embedded resource, its URI the {@code $id}
 * resolved against the URI of the resource that contains it; like the validator, every object of the file counts, not
 * only those at a keyword that holds a schema. A reference is resolved against the URI of the resource that contains the
 * schema holding it (for a schema that declares an {@code $id} itself, its own): the part before {@code #} selects a
 * resource of the file, the fragment, URL-decoded with {@link URLDecoder} as the validator decodes it (a {@code +}
 * would become a space, see {@link #fragmentHoldsPlus(String)}), is empty for the resource itself, a JSON Pointer
 * (RFC 6901) from the root of that resource, or the name of an {@code $anchor} or {@code $dynamicAnchor} declared
 * within that resource. A
 * {@code $dynamicRef} is resolved like a {@code $ref} by {@link #resolve(Map, String)}; the schemas its dynamic scope
 * may bind it to at validation time are given separately by {@link #dynamicScopeTargets(String)}, and both together by
 * {@link #targets(Map, String)}. An instance is immutable.
 */
final class SchemaResources {

    /**
     * The base URI of a schema file; the validator registers the schema under it, so both resolve the same way.
     */
    static final URI BASE = URI.create("https://gdr.invalid/schema");

    private static final String ID = "$id";
    private static final String DYNAMIC_REF = "$dynamicRef";

    /** The canonical form of an array index in a JSON Pointer (RFC 6901, section 4). */
    private static final Pattern CANONICAL_INDEX = Pattern.compile("0|[1-9][0-9]*");

    private final Map<URI, Map<?, ?>> resources = new HashMap<>();
    private final Map<URI, Map<String, Map<?, ?>>> anchors = new HashMap<>();
    private final Map<URI, Map<String, Map<?, ?>>> dynamicAnchors = new HashMap<>();
    private final Map<String, List<Map<?, ?>>> dynamicAnchorHolders = new HashMap<>();
    private final Map<Object, URI> baseOf = new IdentityHashMap<>();
    private final Map<Object, String> pointerOf = new IdentityHashMap<>();
    private final URI root;
    private final Map<?, ?> document;

    private SchemaResources(Map<?, ?> document) {
        this.document = document;
        URI rootUri = BASE;
        if (document.get(ID) instanceof String id) {
            @Nullable URI declared = resolveUri(BASE, id);
            if (declared != null) {
                rootUri = declared;
            }
        }
        this.root = rootUri;
        resources.put(BASE, document);
        resources.put(root, document);
        index(document, root, "", true);
    }

    /**
     * Indexes the resources of a schema file.
     *
     * @param document the schema as read
     * @return the resources of the file
     */
    static SchemaResources of(Map<?, ?> document) {
        return new SchemaResources(document);
    }

    private void index(@Nullable Object node, URI base, String pointer, boolean isRoot) {
        if (node instanceof Map<?, ?> map) {
            URI own = base;
            if (!isRoot && map.get(ID) instanceof String id) {
                @Nullable URI embedded = resolveUri(base, id);
                if (embedded != null) {
                    own = embedded;
                    resources.putIfAbsent(own, map);
                }
            }
            baseOf.put(map, own);
            pointerOf.put(map, pointer);
            addAnchor(anchors, own, map.get("$anchor"), map);
            addAnchor(dynamicAnchors, own, map.get("$dynamicAnchor"), map);
            if (map.get("$dynamicAnchor") instanceof String name) {
                dynamicAnchorHolders.computeIfAbsent(name, key -> new ArrayList<>()).add(map);
            }
            URI childBase = own;
            map.forEach((key, value) -> index(value, childBase, pointer + "/" + JsonNode.encodeJsonPointer((String) key),
                    false));
        } else if (node instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                index(list.get(index), base, pointer + "/" + index, false);
            }
        }
    }

    private static void addAnchor(Map<URI, Map<String, Map<?, ?>>> byResource, URI resource, @Nullable Object name,
            Map<?, ?> schema) {
        if (name instanceof String anchor) {
            byResource.computeIfAbsent(resource, key -> new HashMap<>()).putIfAbsent(anchor, schema);
        }
    }

    /**
     * The schema objects of the file that a validator may apply: the root, every object at a keyword that holds a
     * sub-schema, and every object a {@code $ref} or {@code $dynamicRef} may select ({@link #targets(Map, String)}),
     * the objects the dynamic scope of a {@code $dynamicRef} may bind it to included, wherever it sits in the file (also
     * under a keyword the dialect does not define, such as {@code x-contact}), with their sub-schemas and targets in
     * turn. The schema checks, the search for in-place cycles, the check of {@code required} and the removal of
     * {@code required} for validation therefore see the same schemas. Each object is listed once, compared by identity;
     * the walk uses an explicit stack, so a deeply nested schema cannot overflow it.
     *
     * @return the schema objects, the root first
     */
    List<Map<?, ?>> schemaObjects() {
        List<Map<?, ?>> found = new ArrayList<>();
        Set<Object> seen = FormSchema.identitySet();
        Deque<Map<?, ?>> pending = new ArrayDeque<>();
        pending.push(document);
        while (!pending.isEmpty()) {
            Map<?, ?> schema = pending.pop();
            if (seen.add(schema)) {
                found.add(schema);
                schema.forEach((keyword, value) -> FormSchema.subSchemas(keyword, value).forEach(pending::push));
                FormSchema.targets(this, schema).forEach(pending::push);
            }
        }
        return found;
    }

    /** A URI resolved against a base, without its fragment, or {@code null} when it is no URI. */
    private static @Nullable URI resolveUri(URI base, String reference) {
        int hash = reference.indexOf('#');
        String uri = hash < 0 ? reference : reference.substring(0, hash);
        if (uri.isEmpty()) {
            return base;
        }
        try {
            return base.resolve(URI.create(uri));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Tells whether a reference selects a resource of the file; its fragment is not looked at.
     *
     * @param containing the schema that holds the reference
     * @param reference  the value of {@code $ref} or {@code $dynamicRef}
     * @return {@code true} when the reference stays within the file
     */
    boolean withinFile(Map<?, ?> containing, String reference) {
        @Nullable URI target = resolveUri(baseOf(containing), reference);
        return target != null && resources.containsKey(target);
    }

    /**
     * Resolves a reference within the file.
     *
     * @param containing the schema that holds the reference
     * @param reference  the value of {@code $ref} or {@code $dynamicRef}
     * @return the referenced value, or {@code null} when the reference leaves the file or finds nothing
     */
    @Nullable
    Object resolve(Map<?, ?> containing, String reference) {
        URI base = baseOf(containing);
        @Nullable URI target = resolveUri(base, reference);
        @Nullable Map<?, ?> resource = target == null ? null : resources.get(target);
        @Nullable String fragment = resource == null ? null : decodedFragment(reference);
        if (resource == null || fragment == null) {
            return null;
        }
        if (fragment.isEmpty()) {
            return resource;
        }
        if (fragment.startsWith("/")) {
            return pointer(resource, fragment);
        }
        @Nullable Map<?, ?> anchored = anchors.getOrDefault(target, Map.of()).get(fragment);
        return anchored != null ? anchored : dynamicAnchors.getOrDefault(target, Map.of()).get(fragment);
    }

    /**
     * The schema objects that one reference keyword of a schema may select: the object its value resolves to
     * ({@link #resolve(Map, String)}), and for {@code $dynamicRef} also every object its dynamic scope may bind it to
     * ({@link #dynamicScopeTargets(String)}). A boolean schema and any other value are not listed.
     *
     * @param containing the schema that holds the reference
     * @param keyword    {@code $ref} or {@code $dynamicRef}
     * @return the objects, empty when the schema holds no such reference or it selects no object
     */
    List<Map<?, ?>> targets(Map<?, ?> containing, String keyword) {
        List<Map<?, ?>> targets = new ArrayList<>();
        if (containing.get(keyword) instanceof String reference) {
            FormSchema.addSchema(targets, resolve(containing, reference));
            if (DYNAMIC_REF.equals(keyword)) {
                targets.addAll(dynamicScopeTargets(reference));
            }
        }
        return targets;
    }

    /**
     * The schema objects a {@code $dynamicRef} may bind to through its dynamic scope, an over-approximation of what the
     * validator does. {@code dev.harrel:json-schema} 1.9.1 ({@code EvaluationContext.resolveDynamicSchema},
     * {@code SchemaRegistry}) first looks the reference up statically, by a JSON Pointer or an {@code $anchor} of the
     * target resource; a {@code $dynamicAnchor} is registered for the dynamic lookup only. When that finds nothing, it
     * takes the first resource of the evaluation scope, outermost first, that declares a {@code $dynamicAnchor} of the
     * name in the fragment, whatever resource the reference names before the {@code #}; so a reference can bind to a
     * schema of an outer resource that its static target does not show. As the scope depends on the data, every schema
     * object of the file that declares a {@code $dynamicAnchor} of that name is taken.
     *
     * @param reference the value of {@code $dynamicRef}
     * @return the objects that declare a {@code $dynamicAnchor} named by the fragment; empty
     *         for a fragment that is empty, a JSON Pointer, or not decodable
     */
    private List<Map<?, ?>> dynamicScopeTargets(String reference) {
        @Nullable String fragment = decodedFragment(reference);
        if (fragment == null || fragment.isEmpty() || fragment.startsWith("/")) {
            return List.of();
        }
        return List.copyOf(dynamicAnchorHolders.getOrDefault(fragment, List.of()));
    }

    /** The fragment of a reference, URL-decoded as the validator decodes it, or {@code null} when it does not decode. */
    private static @Nullable String decodedFragment(String reference) {
        int hash = reference.indexOf('#');
        try {
            return hash < 0 ? "" : URLDecoder.decode(fragmentOf(reference, hash), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Tells whether the fragment of a reference holds a {@code +}. The validator decodes a fragment with
     * {@link URLDecoder}, form-decoding it ({@code UriUtil} of {@code dev.harrel:json-schema} 1.9.1), so a {@code +}
     * becomes a space, which RFC 6901 and RFC 3986 do not do; {@link #resolve(Map, String)} decodes the same way, to
     * follow the target the validator follows, and {@link FormSchema#parse(String, byte[])} refuses such a reference, so
     * that no schema depends on the difference. A {@code +} encoded as {@code %2B} stays a {@code +}.
     *
     * @param reference the value of {@code $ref} or {@code $dynamicRef}
     * @return {@code true} when the fragment holds a {@code +}
     */
    static boolean fragmentHoldsPlus(String reference) {
        int hash = reference.indexOf('#');
        return hash >= 0 && fragmentOf(reference, hash).indexOf('+') >= 0;
    }

    /** The fragment of a reference up to a further {@code #}, as the validator splits it. */
    private static String fragmentOf(String reference, int hash) {
        int next = reference.indexOf('#', hash + 1);
        return next < 0 ? reference.substring(hash + 1) : reference.substring(hash + 1, next);
    }

    private URI baseOf(Map<?, ?> schema) {
        return baseOf.getOrDefault(schema, root);
    }

    /**
     * The JSON Pointer of an object within the file, from the root, as the validator registers it.
     *
     * @param schema an object of the file
     * @return the pointer, or {@code null} for an object that is not part of the file
     */
    @Nullable
    String pointerOf(Map<?, ?> schema) {
        return pointerOf.get(schema);
    }

    /**
     * The value at a JSON Pointer (RFC 6901) within a schema.
     *
     * @param pointer the pointer, empty or starting with {@code /}, its escapes not yet resolved
     * @return the value, or {@code null} when the pointer finds nothing
     */
    static @Nullable Object pointer(Map<?, ?> schema, String pointer) {
        @Nullable Object node = schema;
        if (pointer.isEmpty()) {
            return node;
        }
        for (String segment : pointer.substring(1).split("/", -1)) {
            String name = segment.replace("~1", "/").replace("~0", "~");
            node = switch (node) {
                case Map<?, ?> map -> map.get(name);
                case List<?> list -> index(list, name);
                case null, default -> null;
            };
        }
        return node;
    }

    /**
     * The item of an array at a segment of a JSON Pointer. Only the canonical form of RFC 6901, section 4, selects an
     * item, {@code 0} or a digit from 1 to 9 followed by digits, because the validator registers each sub-schema under
     * its exact pointer ({@code SchemaRegistry} of {@code dev.harrel:json-schema} 1.9.1): {@code /allOf/00} or
     * {@code /allOf/+1} would otherwise select an item here that the validator does not find.
     */
    private static @Nullable Object index(List<?> list, String segment) {
        if (!CANONICAL_INDEX.matcher(segment).matches()) {
            return null;
        }
        try {
            int index = Integer.parseInt(segment);
            return index >= 0 && index < list.size() ? list.get(index) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
