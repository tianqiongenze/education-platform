package com.ep.rw;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class RoutingHolderTest {
    @AfterEach void clear() { RoutingHolder.clear(); }

    @Test void defaultIsNullWhichMeansWrite() {
        assertNull(RoutingHolder.get());
    }
    @Test void setThenGetReturnsValue() {
        RoutingHolder.set(DataSourceType.READ);
        assertSame(DataSourceType.READ, RoutingHolder.get());
    }
    @Test void clearResetsToNull() {
        RoutingHolder.set(DataSourceType.READ);
        RoutingHolder.clear();
        assertNull(RoutingHolder.get());
    }
}
