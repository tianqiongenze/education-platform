package com.ep.business.common;

/**
 * D-132: Snowflake-style 64-bit ID generator.
 * <p>
 * Replaces {@code @GeneratedValue(strategy = GenerationType.IDENTITY)} (monotonic
 * auto-increment) which causes a single-Region write hotspot on TiDB. PingCAP best
 * practice: application-assigned, time-ordered, non-monotonic IDs so inserts spread
 * across Regions.
 * <p>
 * Layout (Twitter Snowflake-compatible): sign(1) + timestamp(41) + worker(5) +
 * sequence(12) = 64 bits. The {@code workerId} distinguishes JVM instances running
 * concurrently; the sequence distinguishes IDs minted within the same millisecond on
 * the same worker. Defaults to workerId {@code 0} — overridable via the
 * {@code ep.snowflake.worker-id} system property so each replica gets a distinct id.
 * <p>
 * Thread-safe: {@link #nextId()} synchronises on the instance (IDs are minted under
 * contention only on the create path; the lock is never held across I/O).
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

    /** Singleton accessor — the application-wide generator. */
    public static SnowflakeIdGenerator getInstance() {
        return INSTANCE;
    }

    /**
     * Mint the next id. Blocks until the next millisecond when the per-ms
     * sequence is exhausted (4096 ids/ms/worker) or when the clock moves
     * backwards (treated as a brief stall rather than a fatal error).
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();
        if (timestamp < lastTimestamp) {
            // Clock moved backwards — wait until it catches up rather than risk a dup.
            timestamp = lastTimestamp;
        }
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0L) {
                // Sequence exhausted in this ms — spin until the next ms.
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
