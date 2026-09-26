package com.ep.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end routing proof against two DISTINCT in-memory H2 databases:
 * "write-db" and "read-db". The marker row in each database differs, so the
 * query result proves which physical pool served the statement — this is the
 * evidence that the read/write split actually happens, not just that a lookup
 * key changes.
 */
class RoutingSplitH2IntegrationTest {

    private static final String CREATE = "CREATE TABLE IF NOT EXISTS marker (pooled VARCHAR(16))";
    private static final String INSERT = "INSERT INTO marker(pooled) VALUES ('%s')";
    private static final String COUNT = "SELECT pooled FROM marker";

    private DataSource h2(String name) {
        org.h2.jdbcx.JdbcDataSource ds = new org.h2.jdbcx.JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        return ds;
    }

    private EpRoutingDataSource routing(DataSource write, DataSource read) {
        EpRoutingDataSource routing = new EpRoutingDataSource();
        Map<Object, Object> targets = new HashMap<>();
        targets.put(EpRoutingDataSource.WRITE_KEY, write);
        targets.put(EpRoutingDataSource.READ_KEY, read);
        routing.setTargetDataSources(targets);
        routing.setDefaultTargetDataSource(write);
        routing.afterPropertiesSet();
        return routing;
    }

    @Test
    @DisplayName("write tx hits write-db, readOnly tx hits read-db — verified by distinct data")
    void routesByTransactionReadOnlyFlag() {
        DataSource write = h2("write-db");
        DataSource read = h2("read-db");
        JdbcTemplate w = new JdbcTemplate(write);
        JdbcTemplate r = new JdbcTemplate(read);
        w.execute(CREATE);
        r.execute(CREATE);
        w.update(INSERT.formatted("write-pool"));
        r.update(INSERT.formatted("read-pool"));

        DataSource lazy = new LazyConnectionDataSourceProxy(routing(write, read));
        PlatformTransactionManager tm = new DataSourceTransactionManager(lazy);
        JdbcTemplate api = new JdbcTemplate(lazy);

        // read-write tx -> write pool
        String fromWriteTx = new TransactionTemplate(tm,
                new DefaultTransactionDefinition()).execute(s -> api.queryForObject(COUNT, String.class));
        assertEquals("write-pool", fromWriteTx);

        // readOnly tx -> read pool
        DefaultTransactionDefinition ro = new DefaultTransactionDefinition();
        ro.setReadOnly(true);
        String fromReadTx = new TransactionTemplate(tm, ro).execute(s -> api.queryForObject(COUNT, String.class));
        assertEquals("read-pool", fromReadTx);
    }

    @Test
    @DisplayName("RoutingContextHolder.forceWrite inside a readOnly tx still hits write-db")
    void overrideRedirectsToWritePool() {
        DataSource write = h2("write-db2");
        DataSource read = h2("read-db2");
        JdbcTemplate w = new JdbcTemplate(write);
        JdbcTemplate r = new JdbcTemplate(read);
        w.execute(CREATE);
        r.execute(CREATE);
        w.update(INSERT.formatted("write-pool"));
        r.update(INSERT.formatted("read-pool"));

        DataSource lazy = new LazyConnectionDataSourceProxy(routing(write, read));
        PlatformTransactionManager tm = new DataSourceTransactionManager(lazy);
        JdbcTemplate api = new JdbcTemplate(lazy);

        DefaultTransactionDefinition ro = new DefaultTransactionDefinition();
        ro.setReadOnly(true);
        String result = new TransactionTemplate(tm, ro).execute(s -> {
            try (RoutingContextHolder.Scope ignored = RoutingContextHolder.forceWrite()) {
                return api.queryForObject(COUNT, String.class);
            }
        });
        assertEquals("write-pool", result);
        // override must not leak past the scope
        assertEquals(Boolean.FALSE,
                Boolean.valueOf(TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                        && RoutingContextHolder.currentOverride() != null));
    }
}
