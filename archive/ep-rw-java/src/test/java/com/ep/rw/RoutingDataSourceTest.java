package com.ep.rw;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingDataSourceTest {
    @AfterEach void clear() { RoutingHolder.clear(); }

    @Test void returnsWriteKeyWhenHolderNull() {
        var ds = new RoutingDataSource();
        assertEquals(DataSourceType.WRITE, ds.determineCurrentLookupKey());
    }
    @Test void returnsReadKeyWhenHolderSet() {
        RoutingHolder.set(DataSourceType.READ);
        var ds = new RoutingDataSource();
        assertEquals(DataSourceType.READ, ds.determineCurrentLookupKey());
    }
}
