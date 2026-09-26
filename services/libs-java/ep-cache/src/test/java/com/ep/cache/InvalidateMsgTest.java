package com.ep.cache;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InvalidateMsgTest {
    @Test
    void holdsCacheNameAndKey() {
        InvalidateMsg m = new InvalidateMsg("course:detail", "c-123");
        assertEquals("course:detail", m.getCacheName());
        assertEquals("c-123", m.getKey());
    }

    @Test
    void settersMutateFields() {
        InvalidateMsg m = new InvalidateMsg();
        m.setCacheName("course:detail");
        m.setKey("c-456");
        assertEquals("course:detail", m.getCacheName());
        assertEquals("c-456", m.getKey());
    }

    @Test
    void defaultConstructorYieldsNulls() {
        InvalidateMsg m = new InvalidateMsg();
        assertNull(m.getCacheName());
        assertNull(m.getKey());
    }
}
