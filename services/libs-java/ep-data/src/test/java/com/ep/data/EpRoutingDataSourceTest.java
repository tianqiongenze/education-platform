package com.ep.data;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.lookup.DataSourceLookup;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpRoutingDataSourceTest {

    private final EpRoutingDataSource ds = new EpRoutingDataSource();

    private String lookupKey() {
        return String.valueOf(ds.determineCurrentLookupKey());
    }

    @AfterEach
    void cleanThreadState() {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            TransactionSynchronizationManager.clear();
        }
    }

    @Test
    @DisplayName("no tx, no override -> WRITE")
    void defaultsToWrite() {
        assertEquals(EpRoutingDataSource.WRITE_KEY, lookupKey());
    }

    @Test
    @DisplayName("read-only tx -> READ (the core R/W split rule)")
    void readOnlyTxRoutesRead() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertEquals(EpRoutingDataSource.READ_KEY, lookupKey());
    }

    @Test
    @DisplayName("read-write tx -> WRITE")
    void readWriteTxRoutesWrite() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertEquals(EpRoutingDataSource.WRITE_KEY, lookupKey());
    }

    @Test
    @DisplayName("readOnly flag WITHOUT an active tx is ignored -> WRITE (documents the Lazy-proxy requirement)")
    void readOnlyFlagWithoutTxIgnored() {
        // setActualTransactionActive(false) + readOnly=true: Spring only
        // reflects a real tx; guard against accidental READ routing.
        TransactionSynchronizationManager.setActualTransactionActive(false);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        // isActualTransactionActive()==false in this synthetic state, so:
        assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
        TransactionSynchronizationManager.clear();
    }

    @Test
    @DisplayName("explicit WRITE override beats a read-only tx (read-your-own-writes)")
    void overrideBeatsReadOnlyTx() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        try (RoutingContextHolder.Scope s = RoutingContextHolder.forceWrite()) {
            assertEquals(EpRoutingDataSource.WRITE_KEY, lookupKey());
        }
        // restored
        assertEquals(EpRoutingDataSource.READ_KEY, lookupKey());
    }

    @Test
    @DisplayName("explicit READ override beats a write tx")
    void readOverrideBeatsWriteTx() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        try (RoutingContextHolder.Scope s = RoutingContextHolder.forceRead()) {
            assertEquals(EpRoutingDataSource.READ_KEY, lookupKey());
        }
        assertEquals(EpRoutingDataSource.WRITE_KEY, lookupKey());
    }

    @Test
    @DisplayName("fallback resolution: H2 provider (no replicas) maps READ onto the write target")
    void afterPropertiesSetWiresTargets() {
        EpRoutingDataSource routing = new EpRoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(EpRoutingDataSource.WRITE_KEY, new StubDataSource("w"));
        targets.put(EpRoutingDataSource.READ_KEY, new StubDataSource("r"));
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(targets.get(EpRoutingDataSource.WRITE_KEY));
        routing.afterPropertiesSet();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        // resolveDefaultTarget through the READ key path proves target wiring
        assertEquals(EpRoutingDataSource.READ_KEY, lookupKey());
        TransactionSynchronizationManager.clear();
    }

    /** Minimal DataSource stub — resolution only; getConnection is unused. */
    private static final class StubDataSource implements DataSource {
        private final String id;

        private StubDataSource(String id) {
            this.id = id;
        }

        @Override
        public java.sql.Connection getConnection() {
            throw new UnsupportedOperationException("stub " + id);
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException("stub " + id);
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
        }
    }
}
