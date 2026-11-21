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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


import dev.harrel.jsonschema.JsonNode;
import dev.harrel.jsonschema.JsonNodeFactory;
import dev.harrel.jsonschema.SimpleType;
import org.jspecify.annotations.Nullable;

/**
 * A node of the JSON data model of ADR-0008, as {@code dev.harrel:json-schema} 1.9.1 reads it through its public
 * interface {@link JsonNode}: so the validator needs no JSON provider and no further artifact.
 * <p>
 * The value is one of the types {@link YamlReader} produces: a {@link Map} with {@link String} keys, a {@link List}, a
 * {@link String}, a {@link Long}, {@link Integer} or {@link BigInteger}, a {@link BigDecimal}, a {@link Boolean} or
 * {@code null}. A {@link BigDecimal} without fraction is an integer, as in the providers of the validator. Equality
 * ignores the JSON Pointer and compares numbers by value, which the keywords {@code const}, {@code enum} and
 * {@code uniqueItems} rely on.
 */
final class DataNode implements JsonNode {

    /** The factory of nodes for schemas and data: {@code create} reads JSON (as YAML) through {@link YamlReader}. */
    static final JsonNodeFactory FACTORY = new Factory();

    private final @Nullable Object value;
    private final String pointer;
    private final SimpleType type;
    private @Nullable List<JsonNode> array;
    private @Nullable Map<String, JsonNode> object;

    private DataNode(@Nullable Object value, String pointer) {
        this.value = value;
        this.pointer = pointer;
        this.type = typeOf(value);
    }

    /**
     * Wraps a value of the data model as a root node.
     *
     * @param value the value
     * @return the node, whose JSON Pointer is the empty string
     * @throws IllegalArgumentException if the value or a value inside it is outside the data model
     */
    static DataNode root(@Nullable Object value) {
        requireDataModel(value);
        return new DataNode(value, "");
    }

    /**
     * Checks a value and every value inside it against the data model, at the boundary: the validator descends only
     * where a keyword of the schema leads it, so a value it never reaches would otherwise pass unchecked.
     */
    private static void requireDataModel(@Nullable Object value) {
        typeOf(value);
        if (value instanceof List<?> list) {
            list.forEach(DataNode::requireDataModel);
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    throw new IllegalArgumentException("not of the JSON data model: a key that is not a string");
                }
                requireDataModel(entry.getValue());
            }
        }
    }

    private static SimpleType typeOf(@Nullable Object value) {
        if (value == null) {
            return SimpleType.NULL;
        }
        if (value instanceof Boolean) {
            return SimpleType.BOOLEAN;
        }
        if (value instanceof String) {
            return SimpleType.STRING;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof BigInteger) {
            return SimpleType.INTEGER;
        }
        if (value instanceof BigDecimal decimal) {
            return isIntegral(decimal) ? SimpleType.INTEGER : SimpleType.NUMBER;
        }
        if (value instanceof List) {
            return SimpleType.ARRAY;
        }
        if (value instanceof Map) {
            return SimpleType.OBJECT;
        }
        throw new IllegalArgumentException("not of the JSON data model: " + value.getClass().getName());
    }

    private static boolean isIntegral(BigDecimal number) {
        // a scale of at most 0 is integral as it is; stripping it could leave the range of int and throw
        return number.scale() <= 0 || number.signum() == 0 || number.stripTrailingZeros().scale() <= 0;
    }

    @Override
    public String getJsonPointer() {
        return pointer;
    }

    @Override
    public SimpleType getNodeType() {
        return type;
    }

    @Override
    public boolean asBoolean() {
        return Boolean.TRUE.equals(value);
    }

    @Override
    public String asString() {
        return String.valueOf(value);
    }

    @Override
    public BigInteger asInteger() {
        return switch (value) {
            case BigInteger integer -> integer;
            case BigDecimal decimal -> decimal.toBigInteger();
            case Number number -> BigInteger.valueOf(number.longValue());
            case null, default -> BigInteger.ZERO;
        };
    }

    @Override
    public BigDecimal asNumber() {
        return switch (value) {
            case BigDecimal decimal -> decimal;
            case BigInteger integer -> new BigDecimal(integer);
            case Number number -> BigDecimal.valueOf(number.longValue());
            case null, default -> BigDecimal.ZERO;
        };
    }

    @Override
    public List<JsonNode> asArray() {
        List<JsonNode> items = array;
        if (items == null) {
            List<JsonNode> built = new ArrayList<>();
            if (value instanceof List<?> list) {
                for (int index = 0; index < list.size(); index++) {
                    built.add(new DataNode(list.get(index), pointer + "/" + index));
                }
            }
            items = Collections.unmodifiableList(built);
            array = items;
        }
        return items;
    }

    @Override
    public Map<String, JsonNode> asObject() {
        Map<String, JsonNode> entries = object;
        if (entries == null) {
            Map<String, JsonNode> built = new LinkedHashMap<>();
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    // every key is a string: the root checked the whole value
                    String key = (String) entry.getKey();
                    built.put(key, new DataNode(entry.getValue(), pointer + "/" + JsonNode.encodeJsonPointer(key)));
                }
            }
            entries = Collections.unmodifiableMap(built);
            object = entries;
        }
        return entries;
    }

    /** The comparable form of the value: numbers by value, containers by their nodes. */
    private @Nullable Object comparable() {
        return switch (type) {
            case INTEGER -> asInteger();
            case NUMBER -> asNumber().stripTrailingZeros();
            case ARRAY -> asArray();
            case OBJECT -> asObject();
            default -> value;
        };
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof DataNode node && type == node.type && Objects.equals(comparable(), node.comparable());
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, comparable());
    }

    /** The factory of nodes for schemas and data. */
    private static final class Factory implements JsonNodeFactory {

        private static final YamlReader YAML = new YamlReader();

        @Override
        public JsonNode wrap(Object node) {
            return switch (node) {
                case DataNode data -> new DataNode(data.value, "");
                case JsonNode foreign -> throw new IllegalArgumentException(
                        "not a node of git-forms: " + foreign.getClass().getName());
                default -> root(node);
            };
        }

        @Override
        public JsonNode create(String rawJson) {
            FormsResult<Object> read = YAML.read(rawJson);
            if (!read.isOk()) {
                throw new IllegalArgumentException("not well-formed JSON: " + read.detail());
            }
            return root(read.value());
        }
    }
}
