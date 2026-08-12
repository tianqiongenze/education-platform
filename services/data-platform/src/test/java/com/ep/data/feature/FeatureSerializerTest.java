package com.ep.data.feature;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureSerializerTest {

    private final FeatureSerializer serializer = new FeatureSerializer();

    @Test
    void serializeDeserializeRoundtrip_preservesValues() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("play_cnt_7d", 12L);
        features.put("avg_dur", 45.5);
        features.put("active", true);
        features.put("name", "alice");

        byte[] bytes = serializer.serialize(features);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        Map<String, Object> back = serializer.deserialize(bytes);
        assertEquals(12, ((Number) back.get("play_cnt_7d")).intValue());
        assertEquals(45.5, ((Number) back.get("avg_dur")).doubleValue());
        assertEquals(true, back.get("active"));
        assertEquals("alice", back.get("name"));
    }

    @Test
    void deserializeEmptyBytes_returnsEmptyMap() {
        Map<String, Object> result = serializer.deserialize(new byte[0]);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void serializeNullMap_throws() {
        assertThrows(IllegalArgumentException.class, () -> serializer.serialize(null));
    }

    @Test
    void serializeEmptyMap_works() {
        byte[] bytes = serializer.serialize(new LinkedHashMap<>());
        assertEquals("{}", new String(bytes).trim());
    }
}
