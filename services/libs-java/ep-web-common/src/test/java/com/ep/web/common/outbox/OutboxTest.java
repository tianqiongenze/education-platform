package com.ep.web.common.outbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-234 tests: writer persistence, relay drain/retry/give-up, table DDL —
 * end-to-end against an in-memory H2 (the same way Testcontainers ITs run
 * against a real DB in EP_LIVE mode).
 */
class OutboxTest {

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private OutboxWriter writer;
    private OutboxProperties properties;

    @BeforeEach
    void setup() {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:outbox_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(OutboxAutoConfiguration.DDL);
        var txManager = new DataSourceTransactionManager(dataSource);
        writer = new OutboxWriter(jdbc, txManager);
        properties = new OutboxProperties();
        properties.setEnabled(true);
        properties.setRelayIntervalMillis(60_000); // manual drain in tests
    }

    @AfterEach
    void cleanup() {
        // nothing persistent — in-memory DB dies with the connection
    }

    @Test
    @DisplayName("writer persists a row with deterministic eventId format")
    void writerPersistsRow() {
        String eventId = writer.write("Course", "c-1", "CoursePublishedEvent", "{\"id\":\"c-1\"}");

        assertTrue(eventId.startsWith("CoursePublishedEvent:c-1:"), "eventId = type:aggregateId:uuid");
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ep_outbox", Long.class);
        assertEquals(1, count);
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM ep_outbox WHERE published_at IS NULL", Long.class);
        assertEquals(1, pending);
        assertEquals(1, writer.pendingCount());
    }

    @Test
    @DisplayName("relay publishes pending rows and stamps published_at")
    void relayPublishesAndStamps() {
        writer.write("Course", "c-1", "CoursePublishedEvent", "{\"id\":\"c-1\"}");
        writer.write("Course", "c-2", "CoursePublishedEvent", "{\"id\":\"c-2\"}");

        List<String> published = new ArrayList<>();
        var relay = new OutboxRelay(jdbc, (type, payload) -> published.add(type + ":" + payload), properties);
        try {
            relay.drain();
        } finally {
            relay.close();
        }

        assertEquals(2, published.size());
        assertEquals(0, writer.pendingCount(), "all rows stamped after successful publish");
    }

    @Test
    @DisplayName("relay retries failed publishes and gives up after maxRetries with ERROR stamp")
    void relayRetriesThenGivesUp() {
        writer.write("Order", "o-9", "OrderPaidEvent", "{\"id\":\"o-9\"}");
        properties.setMaxRetries(3);

        AtomicInteger attempts = new AtomicInteger();
        var relay = new OutboxRelay(jdbc, (type, payload) -> {
            attempts.incrementAndGet();
            throw new RuntimeException("broker down");
        }, properties);
        try {
            relay.drain(); // retry 1
            relay.drain(); // retry 2
            relay.drain(); // retry 3 → give up
        } finally {
            relay.close();
        }

        assertEquals(3, attempts.get(), "exactly maxRetries attempts");
        Integer retryCount = jdbc.queryForObject(
                "SELECT retry_count FROM ep_outbox WHERE event_id LIKE 'OrderPaidEvent:o-9:%'", Integer.class);
        assertEquals(3, retryCount);
        Integer published = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ep_outbox WHERE event_id LIKE 'OrderPaidEvent:o-9:%' AND published_at IS NOT NULL",
                Integer.class);
        assertEquals(1, published, "give-up rows are stamped to stop the infinite loop");
    }

    @Test
    @DisplayName("relay skips already-published rows on next tick")
    void relaySkipsPublished() {
        String eventId = writer.write("Course", "c-1", "CoursePublishedEvent", "{}");

        AtomicInteger attempts = new AtomicInteger();
        var relay = new OutboxRelay(jdbc, (type, payload) -> attempts.incrementAndGet(), properties);
        try {
            relay.drain();
            relay.drain();
        } finally {
            relay.close();
        }

        assertEquals(1, attempts.get(), "second tick must not re-publish a stamped row");
    }

    @Test
    @DisplayName("writer joins the ambient transaction when one is active (textbook outbox)")
    void writerJoinsAmbientTransaction() {
        var txManager = new DataSourceTransactionManager(dataSource);
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            // ambient tx: the write must NOT use REQUIRES_NEW — verify by
            // checking the row is visible (same tx) before commit
            writer.write("Course", "c-tx", "CoursePublishedEvent", "{}");
            Integer inside = jdbc.queryForObject("SELECT COUNT(*) FROM ep_outbox", Integer.class);
            assertEquals(1, inside, "row visible inside the ambient transaction");
        });
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM ep_outbox", Integer.class);
        assertEquals(1, after, "row committed with the ambient transaction");
    }

    @Test
    @DisplayName("writer without transaction manager still persists (direct JDBC)")
    void writerWithoutTxManager() {
        var bare = new OutboxWriter(jdbc, null);
        assertNotNull(bare.write("Course", "c-1", "CoursePublishedEvent", "{}"));
        assertEquals(1, bare.pendingCount());
    }

    @Test
    @DisplayName("properties defaults follow the documented values")
    void propertiesDefaults() {
        var p = new OutboxProperties();
        assertFalse(p.isEnabled(), "explicit opt-in (D-223 lesson: shipped-disabled is documented)");
        assertEquals(5000L, p.getRelayIntervalMillis());
        assertEquals(50, p.getBatchSize());
        assertEquals(5, p.getMaxRetries());
        assertTrue(p.isCreateTable());
    }
}
