package com.ep.rw;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceTypeTest {
    @Test void hasWriteAndReadValues() {
        assertSame(DataSourceType.WRITE, DataSourceType.valueOf("WRITE"));
        assertSame(DataSourceType.READ, DataSourceType.valueOf("READ"));
        assertEquals(2, DataSourceType.values().length);
    }
}
