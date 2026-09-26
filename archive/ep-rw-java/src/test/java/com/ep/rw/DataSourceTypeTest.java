package com.ep.rw;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DataSourceTypeTest {
    @Test void hasWriteReadAndFollowerReadValues() {
        assertSame(DataSourceType.WRITE, DataSourceType.valueOf("WRITE"));
        assertSame(DataSourceType.READ, DataSourceType.valueOf("READ"));
        // D-041: FOLLOWER_READ 用于跨 cell TiDB follower read
        assertSame(DataSourceType.FOLLOWER_READ, DataSourceType.valueOf("FOLLOWER_READ"));
        assertEquals(3, DataSourceType.values().length);
    }
}
