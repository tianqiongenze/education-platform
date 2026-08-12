package com.ep.boardgame.common;

/**
 * Snowflake ID generator (same pattern as core-business / senior-care).
 * <p>
 * Worker ID from system property {@code ep.game.worker-id} or env var
 * {@code GAME_WORKER_ID}. Default 1 for dev-local.
 */
public final class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01 00:00:00 UTC
    private static final long WORKER_ID_BITS = 10L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS); // 1023
    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    private static final SnowflakeIdGenerator INSTANCE = new SnowflakeIdGenerator();

    private SnowflakeIdGenerator() {
        String widStr = System.getProperty("ep.game.worker-id");
        if (widStr == null || widStr.isBlank()) {
            widStr = System.getenv("GAME_WORKER_ID");
        }
        long wid = 1L;
        if (widStr != null && !widStr.isBlank()) {
            try {
                wid = Long.parseLong(widStr);
            } catch (NumberFormatException e) {
                wid = 1L;
            }
        }
        if (wid < 0 || wid > MAX_WORKER_ID) {
            throw new IllegalArgumentException("workerId out of range: " + wid);
        }
        this.workerId = wid;
    }

    public static SnowflakeIdGenerator getInstance() {
        return INSTANCE;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            throw new IllegalStateException("Clock moved backwards");
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTs) {
        long ts = System.currentTimeMillis();
        while (ts <= lastTs) {
            ts = System.currentTimeMillis();
        }
        return ts;
    }
}
