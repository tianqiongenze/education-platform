package com.ep.data.feature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FeatureKeyTest {

    @Test
    void buildKey_returnsExpectedFormat() {
        assertEquals("feat:learner_activity:42", FeatureKey.buildKey("learner_activity", 42L));
    }

    @Test
    void buildPattern_returnsExpectedFormat() {
        assertEquals("feat:learner_activity:*", FeatureKey.buildPattern("learner_activity"));
    }

    @Test
    void buildKey_throwsOnBlankGroup() {
        assertThrows(IllegalArgumentException.class, () -> FeatureKey.buildKey("", 1L));
    }

    @Test
    void buildKey_throwsOnNullUserId() {
        assertThrows(IllegalArgumentException.class, () -> FeatureKey.buildKey("g", null));
    }

    @Test
    void buildPattern_throwsOnBlankGroup() {
        assertThrows(IllegalArgumentException.class, () -> FeatureKey.buildPattern("  "));
    }
}
