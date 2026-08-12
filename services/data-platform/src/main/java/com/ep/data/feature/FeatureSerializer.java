package com.ep.data.feature;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serialize/deserialize feature value maps to/from bytes using Jackson.
 *
 * <p>Used by the online feature store adapter to read/write Redis values.
 */
public final class FeatureSerializer {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper mapper;

    public FeatureSerializer() {
        this(new ObjectMapper());
    }

    public FeatureSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Serialize a feature map to JSON bytes.
     *
     * @param features feature values (may be empty, must not be null)
     * @return UTF-8 JSON bytes
     */
    public byte[] serialize(Map<String, Object> features) {
        if (features == null) {
            throw new IllegalArgumentException("features must not be null");
        }
        try {
            return mapper.writeValueAsBytes(features);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize features", e);
        }
    }

    /**
     * Deserialize JSON bytes back into a feature map.
     *
     * @param bytes JSON bytes
     * @return feature map (preserving insertion order)
     */
    public Map<String, Object> deserialize(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(bytes, MAP_TYPE);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize features", e);
        }
    }
}
