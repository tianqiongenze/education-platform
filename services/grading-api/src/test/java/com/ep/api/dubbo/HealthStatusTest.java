package com.ep.api.dubbo;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HealthStatusTest {
    @Test void holdsValues() {
        HealthStatus s = new HealthStatus(true, "portal-bff", 1234567890L);
        assertTrue(s.isHealthy());
        assertEquals("portal-bff", s.getService());
        assertEquals(1234567890L, s.getTimestamp());
    }
    @Test void defaultConstructorSetsDefaults() {
        HealthStatus s = new HealthStatus();
        assertFalse(s.isHealthy());
        assertNull(s.getService());
        assertEquals(0L, s.getTimestamp());
    }
    @Test void settersWork() {
        HealthStatus s = new HealthStatus();
        s.setHealthy(true);
        s.setService("learning-java");
        s.setTimestamp(99L);
        assertTrue(s.isHealthy());
        assertEquals("learning-java", s.getService());
        assertEquals(99L, s.getTimestamp());
    }
}
