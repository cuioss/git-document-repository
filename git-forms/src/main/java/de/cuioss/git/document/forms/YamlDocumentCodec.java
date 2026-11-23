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

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.IntStream;


import de.cuioss.git.document.forms.FormSchema.Shape;
import de.cuioss.git.document.store.Codec;
import de.cuioss.git.document.store.StoreOutcome;
import de.cuioss.git.document.store.StoreResult;
import org.jspecify.annotations.Nullable;

/**
 * The codec of the structured data of a document (GDR-FORMS-3, GDR-STORE-11): YAML in the key order of the schema of
 * its kind.
 * <p>
 * The keys of an object are written in the declaration order of the {@code properties} of its schema, a nested object
 * and the items of an array by their sub-schema ({@code prefixItems} by position for the leading items, {@code items}
 * for the others); a property declared through a branch of {@code allOf} or a {@code $ref} within the schema file
 * counts as declared, in the declaration order of the keywords that lead to it, each name at its first position, while
 * {@code $dynamicRef}, {@code anyOf}, {@code oneOf} and the conditionals contribute nothing, because whether they apply
 * depends on the data. The keys the schema does not declare follow in lexicographic order. The text is the canonical
 * form of {@link YamlWriter}, so equal data yields byte-identical content, and for every value {@code encode} accepts,
 * {@code decode(encode(v))} succeeds and {@code encode(decode(encode(v)))} equals {@code encode(v)} byte for byte;
 * {@code encode} refuses a value whose YAML would be longer than the reader takes. Decoding reads YAML within the JSON data
 * model of ADR-0008 through {@link YamlReader}: content that is not UTF-8, not YAML of that data model (an anchor, an
 * alias, an explicit tag, a key that is not a string, a duplicate key, ...) or whose root is not a mapping is
 * {@link StoreOutcome#MALFORMED}, its detail naming the position, never the content. A codec is immutable and may
 * serve every thread.
 *
 * @since 0.1
 */
public final class YamlDocumentCodec implements Codec<Map<String, @Nullable Object>> {

    private static final YamlReader READER = new YamlReader();
    private static final YamlWriter WRITER = new YamlWriter();

    private final FormSchema schema;
    private final Shape shape;

    /**
     * Creates the codec of the documents or entries of a kind: {@code document.yaml} or {@code <number>.yaml}.
     *
     * @param schema the schema of the kind
     * @throws NullPointerException if {@code schema} is {@code null}
     */
    public YamlDocumentCodec(FormSchema schema) {
        this(schema, Objects.requireNonNull(schema, "schema").shape());
    }

    private YamlDocumentCodec(FormSchema schema, Shape shape) {
        this.schema = schema;
        this.shape = shape;
    }

    /**
     * Returns the codec of a split part, ordered by the sub-schema of its property.
     *
     * @param property a property the schema marks with {@value FormSchema#SPLIT}
     * @return the codec of the part file {@code <property>.yaml}
     * @throws NullPointerException     if {@code property} is {@code null}
     * @throws IllegalArgumentException if the property is no split part of the schema
     */
    public YamlDocumentCodec part(String property) {
        Objects.requireNonNull(property, "property");
        if (!schema.splitProperties().contains(property)) {
            throw new IllegalArgumentException("property: not a split part of the schema");
        }
        return new YamlDocumentCodec(schema, schema.shape().property(property));
    }

    /**
     * Encodes data as YAML in schema order.
     *
     * @param value the data, a value of the JSON data model
     * @return the canonical UTF-8 content
     * @throws NullPointerException     if {@code value} is {@code null}
     * @throws IllegalArgumentException if the data holds a value outside the JSON data model, is nested deeper than
     *                                  128 levels (the nesting limit {@link #decode(byte[])} applies, checked before
     *                                  anything is ordered or written), or its YAML would be longer than
     *                                  {@link #decode(byte[])} reads: 3,145,728 code points
     */
    @Override
    public byte[] encode(Map<String, @Nullable Object> value) {
        Objects.requireNonNull(value, "value");
        DataNode.requireDataModel(value);
        String yaml = WRITER.write(ordered(value, shape));
        if (yaml.codePointCount(0, yaml.length()) > YamlReader.CODE_POINT_LIMIT) {
            throw new IllegalArgumentException("value: the YAML would be longer than " + YamlReader.CODE_POINT_LIMIT
                    + " code points, which decode refuses");
        }
        return yaml.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decodes YAML content.
     *
     * @param content the content
     * @return {@link StoreOutcome#OK} with the data, or {@link StoreOutcome#MALFORMED} whose detail names the position
     *         and the problem
     * @throws NullPointerException if {@code content} is {@code null}
     */
    @Override
    public StoreResult<Map<String, @Nullable Object>> decode(byte[] content) {
        Objects.requireNonNull(content, "content");
        String text = YamlReader.decodeUtf8(content);
        if (text == null) {
            return StoreResult.of(StoreOutcome.MALFORMED, "not UTF-8");
        }
        FormsResult<Map<String, @Nullable Object>> read = READER.readMapping(text,
                "line 1, column 1: a document that is not a mapping");
        if (!read.isOk()) {
            return StoreResult.of(StoreOutcome.MALFORMED, Objects.requireNonNull(read.detail()));
        }
        return StoreResult.ok(Objects.requireNonNull(read.value()));
    }

    /** A copy of a value with every mapping in schema order, the undeclared keys after the declared ones, sorted. */
    private static @Nullable Object ordered(@Nullable Object value, Shape shape) {
        if (value instanceof Map<?, ?> map) {
            Map<String, @Nullable Object> sorted = new LinkedHashMap<>();
            for (String name : shape.order()) {
                if (map.containsKey(name)) {
                    sorted.put(name, ordered(map.get(name), shape.property(name)));
                }
            }
            Map<String, @Nullable Object> undeclared = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("not of the JSON data model: a key that is not a string");
                }
                if (!sorted.containsKey(key)) {
                    undeclared.put(key, ordered(entry.getValue(), Shape.EMPTY));
                }
            }
            sorted.putAll(undeclared);
            return sorted;
        }
        if (value instanceof List<?> list) {
            return IntStream.range(0, list.size()).mapToObj(index -> ordered(list.get(index), shape.item(index)))
                    .toList();
        }
        return value;
    }
}
