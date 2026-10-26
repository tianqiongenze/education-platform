package com.ep.web.common.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * D-234: generic transactional-outbox properties ({@code ep.outbox.*}).
 *
 * <p>The outbox closes the dual-write gap for services whose domain events are
 * published best-effort to Pulsar after commit: instead of a fire-and-forget
 * broker send (lost on broker hiccup), the event row is persisted to the
 * {@code ep_outbox} table first and a background relay drains it to the broker
 * with bounded retries.</p>
 *
 * <p>Hosts opt in by (1) having this library on the classpath, (2) providing an
 * {@link OutboxPublisherPort} bean (the broker adapter), and (3) enabling via
 * {@code ep.outbox.enabled=true}. Everything else is automatic — including
 * table creation on first boot ({@code create-table=true} default, H2/TiDB/CRDB
 * compatible DDL).</p>
 */
@ConfigurationProperties(prefix = "ep.outbox")
public class OutboxProperties {

    /** Master switch — outbox components are registered only when true. */
    private boolean enabled = false;

    /** Relay drain interval in milliseconds. */
    private long relayIntervalMillis = 5000L;

    /** Max outbox rows drained per relay tick. */
    private int batchSize = 50;

    /** Bounded publish retries before a row is given up (marked published). */
    private int maxRetries = 5;

    /** CREATE TABLE IF NOT EXISTS on startup (dev/test friendly; prod DBAs may prefer migrations). */
    private boolean createTable = true;

    /** Relay worker thread name prefix (daemon). */
    private String relayThreadName = "ep-outbox-relay";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getRelayIntervalMillis() {
        return relayIntervalMillis;
    }

    public void setRelayIntervalMillis(long relayIntervalMillis) {
        this.relayIntervalMillis = relayIntervalMillis;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public boolean isCreateTable() {
        return createTable;
    }

    public void setCreateTable(boolean createTable) {
        this.createTable = createTable;
    }

    public String getRelayThreadName() {
        return relayThreadName;
    }

    public void setRelayThreadName(String relayThreadName) {
        this.relayThreadName = relayThreadName;
    }
}
