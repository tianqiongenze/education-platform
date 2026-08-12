package com.ep.grading.ide.yjs;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * D-186: Persists incremental Yjs document snapshots to Redis.
 * <p>
 * Snapshots are stored under {@code ide:yjs:snapshot:{docId}} as a JSON object
 * containing the base64-encoded document state and a sequence number. A
 * snapshot is taken every {@link #SNAPSHOT_INTERVAL} updates so reconnecting
 * clients can fetch a compact state instead of replaying every update.
 * <p>
 * Uses {@link ObjectProvider<StringRedisTemplate>} so tests can run without a
 * Redis connection (methods degrade to no-ops).
 * <p>
 * Iron Law 1: durable session state is externalised to Redis, not held in a
 * {@code ConcurrentHashMap}. The {@link YjsDocument} in-memory object is a
 * cache; this store is the durable truth.
 */
@Component
public class YjsSnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(YjsSnapshotStore.class);

    /** Redis key prefix for snapshots. */
    public static final String SNAPSHOT_KEY_PREFIX = "ide:yjs:snapshot:";

    /** Snapshot TTL — 7 days. */
    public static final Duration SNAPSHOT_TTL = Duration.ofDays(7);

    /** Take a snapshot every N updates. */
    public static final int SNAPSHOT_INTERVAL = 100;

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectMapper objectMapper;

    /** Per-doc update counter — decides when to snapshot. Reset after each snapshot. */
    private final Map<String, AtomicLong> updateCounters = new HashMap<>();

    public YjsSnapshotStore(ObjectProvider<StringRedisTemplate> redisProvider,
                            ObjectMapper objectMapper) {
        this.redisProvider = redisProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * Persist a snapshot of the given document to Redis (base64-encoded).
     * Called incrementally — only every {@link #SNAPSHOT_INTERVAL}-th update
     * triggers an actual Redis write.
     *
     * @param docId    the Yjs document id
     * @param document the in-memory document to snapshot
     * @return {@code true} if a snapshot was actually written this call
     */
    public boolean saveSnapshot(String docId, YjsDocument document) {
        if (docId == null || docId.isBlank()) {
            return false;
        }
        AtomicLong counter = updateCounters.computeIfAbsent(docId, k -> new AtomicLong(0));
        long n = counter.incrementAndGet();
        if (n % SNAPSHOT_INTERVAL != 0) {
            return false;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            log.debug("yjs snapshot: redis unavailable, skipping snapshot for doc {}", docId);
            return false;
        }
        try {
            byte[] snapshot = document.getSnapshot();
            String b64 = Base64.getEncoder().encodeToString(snapshot);
            Map<String, Object> payload = new HashMap<>();
            payload.put("docId", docId);
            payload.put("seq", n);
            payload.put("updateCount", document.getUpdateCount());
            payload.put("snapshot", b64);
            String json = objectMapper.writeValueAsString(payload);
            redis.opsForValue().set(SNAPSHOT_KEY_PREFIX + docId, json, SNAPSHOT_TTL);
            log.debug("yjs snapshot: saved snapshot for doc {} (seq={}, bytes={})",
                    docId, n, snapshot.length);
            return true;
        } catch (Exception e) {
            log.error("yjs snapshot: saveSnapshot failed for doc {}", docId, e);
            return false;
        }
    }

    /**
     * Load the most recent snapshot for a doc. Returns empty if Redis is
     * unavailable or no snapshot exists.
     */
    @SuppressWarnings("unchecked")
    public Optional<LoadedSnapshot> loadSnapshot(String docId) {
        if (docId == null || docId.isBlank()) {
            return Optional.empty();
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(SNAPSHOT_KEY_PREFIX + docId);
            if (json == null) {
                return Optional.empty();
            }
            Map<String, Object> payload = objectMapper.readValue(json, Map.class);
            String b64 = (String) payload.get("snapshot");
            if (b64 == null) {
                return Optional.empty();
            }
            byte[] snapshot = Base64.getDecoder().decode(b64);
            long seq = ((Number) payload.getOrDefault("seq", 0L)).longValue();
            long updateCount = ((Number) payload.getOrDefault("updateCount", 0L)).longValue();
            return Optional.of(new LoadedSnapshot(docId, seq, updateCount, snapshot));
        } catch (Exception e) {
            log.error("yjs snapshot: loadSnapshot failed for doc {}", docId, e);
            return Optional.empty();
        }
    }

    /** Decode a UTF-8 byte array to a String (test convenience). */
    static String utf8(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Result of loading a snapshot. */
    public record LoadedSnapshot(String docId, long seq, long updateCount, byte[] snapshot) {
    }
}
