package com.ep.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataSourceProviderTest {

    @Test
    @DisplayName("TiDB/CRDB/MySQL advertise replica support; H2 does not")
    void replicaSupportMatrix() {
        assertTrue(DataSourceProvider.TIDB.supportsReadReplicas());
        assertTrue(DataSourceProvider.COCKROACHDB.supportsReadReplicas());
        assertTrue(DataSourceProvider.MYSQL.supportsReadReplicas());
        assertFalse(DataSourceProvider.H2.supportsReadReplicas());
    }

    @Test
    @DisplayName("CRDB speaks PostgreSQL wire protocol; TiDB/MySQL speak MySQL")
    void wireProtocols() {
        assertEquals("org.postgresql.Driver", DataSourceProvider.COCKROACHDB.driverClassName());
        assertEquals("com.mysql.cj.jdbc.Driver", DataSourceProvider.TIDB.driverClassName());
        assertEquals("com.mysql.cj.jdbc.Driver", DataSourceProvider.MYSQL.driverClassName());
        assertEquals("org.h2.Driver", DataSourceProvider.H2.driverClassName());
    }

    @Test
    @DisplayName("URL templates compose from host + database")
    void urlTemplates() {
        assertEquals("jdbc:postgresql://crdb:26257/ep",
                DataSourceProvider.COCKROACHDB.urlTemplate().formatted("crdb:26257", "ep"));
        assertEquals("jdbc:mysql://tidb:4000/ep_business",
                DataSourceProvider.TIDB.urlTemplate().formatted("tidb:4000", "ep_business"));
    }
}
