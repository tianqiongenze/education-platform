package com.ep.senior.common;

/**
 * Snowflake-style 64-bit ID generator (same as core-business).
 * <p>
 * Replaces IDENTITY auto-increment to avoid TiDB single-Region write hotspot.
 * Layout: sign(1) + timestamp(41) + worker(5) + sequence(12) = 64 bits.
 */
public final class SnowflakeIdGenerator {

    private static final long EPOCH = 1704067200000L; // 2024-01-01T00:00:00Z
    private static final long WORKER_ID_BITS = 5L;
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS); // 31
    private static final long SEQUENCE_BITS = 12L;
    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final SnowflakeIdGenerator INSTANCE = new SnowflakeIdGenerator(resolveWorkerId());

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    private SnowflakeIdGenerator(long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "workerId must be between 0 and " + MAX_WORKER_ID + ": " + workerId);
        }
        this.workerId = workerId;
    }

    public static SnowflakeIdGenerator getInstance() {
        return INSTANCE;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            timestamp = lastTimestamp;
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
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

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    private static long resolveWorkerId() {
        String raw = System.getProperty("ep.snowflake.worker-id");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("EP_SNOWFLAKE_WORKER_ID");
        }
        if (raw == null || raw.isBlank()) {
            return 0L;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return Math.max(0, Math.min(parsed, MAX_WORKER_ID));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
