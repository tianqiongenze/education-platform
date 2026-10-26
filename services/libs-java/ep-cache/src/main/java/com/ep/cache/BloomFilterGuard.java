package com.ep.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * D-236 (v7 P2): Redis-bitmap Bloom filter guard against cache-penetration
 * storms on non-existent keys.
 *
 * <p><b>The gap it closes</b>: the {@code CacheService} already defends
 * penetration with the empty-value sentinel — but the sentinel only exists
 * AFTER the first (real) DB query returned null. A burst of one million
 * requests for an id that does not exist in the DB still produces one DB
 * round-trip per singleflight window. The Bloom filter short-circuits that
 * first DB query entirely: when it answers "definitely not present" the
 * loader is skipped and the empty sentinel is served — the DB is never
 * touched for junk traffic.</p>
 *
 * <p><b>Honest semantics</b> (Bloom filter law):</p>
 * <ul>
 *   <li>{@code mightContain(key) == false} → the key is <b>definitely</b>
 *       absent from the guarded set → safe to short-circuit.</li>
 *   <li>{@code mightContain(key) == true} → <i>probably</i> present (false
 *       positives at {@code fpp}) → fall through to the normal loader. No
 *       correctness risk, only a missed optimization.</li>
 * </ul>
 *
 * <p><b>Data-source discipline (who calls {@code put})</b>: the guard is only
 * correct when the guarded set is fed by the same source of truth the loader
 * reads — i.e. services must {@code put(id)} on their create/write path (or
 * warm the filter at startup). A filter that is never fed short-circuits
 * everything, which is why this component is <b>opt-in per cache</b> and never
 * wired implicitly: {@code CacheService.getOrLoad(..., bloom)} only
 * short-circuits when the caller passes a guard.</p>
 *
 * <p><b>Redis layout</b>: one bitmap key per guard instance
 * ({@code ep:cache:bloom:<name>}) with {@code numHashFunctions} bits per
 * element set via double hashing ({@code h_i = h1 + i·h2}). The bitmap key
 * carries a TTL of {@code 2× rebuildTtl}: when it expires the filter is
 * rebuilt from traffic (bounded memory; the window of higher false-positive
 * rate near expiry is a documented trade-off, not a correctness one).</p>
 */
public class BloomFilterGuard {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterGuard.class);

    /** Redis key for the guard bitmap. */
    private final String redisKey;

    /** Number of bits in the bitmap (derived from expected insertions + fpp). */
    private final int numBits;

    /** Number of hash functions per element (optimal for fpp). */
    private final int numHashFunctions;

    /** TTL applied to the bitmap key (2× on every put — sliding rebuild). */
    private final Duration rebuildTtl;

    private final StringRedisTemplate redis;

    /** Diagnostics: how many junk lookups this guard short-circuited. */
    private final AtomicLong shortCircuited = new AtomicLong();

    public BloomFilterGuard(String name, long expectedInsertions, double fpp,
                            Duration rebuildTtl, StringRedisTemplate redis) {
        if (expectedInsertions < 1) {
            throw new IllegalArgumentException("expectedInsertions must be positive");
        }
        if (fpp <= 0.0 || fpp >= 1.0) {
            throw new IllegalArgumentException("fpp must be in (0, 1)");
        }
        this.redisKey = "ep:cache:bloom:" + name;
        // Standard optimal sizing: m = -n·ln(p)/(ln2)^2, k = (m/n)·ln2
        double bits = Math.ceil(-expectedInsertions * Math.log(fpp) / (Math.log(2) * Math.log(2)));
        this.numBits = (int) Math.min(bits, Integer.MAX_VALUE - 8);
        this.numHashFunctions = Math.max(1, (int) Math.round((double) numBits / expectedInsertions * Math.log(2)));
        this.rebuildTtl = rebuildTtl;
        this.redis = redis;
        log.info("D-236: BloomFilterGuard '{}' ready: bits={}, hashes={}, fpp={}, rebuildTtl={}",
                name, numBits, numHashFunctions, fpp, rebuildTtl);
    }

    /**
     * Marks the element as present in the guarded set. Called from the data
     * write path (create/update) or a startup warm-up loop.
     */
    public void put(String element) {
        if (element == null) {
            return;
        }
        long[] hashes = hashes(element);
        try {
            for (int i = 0; i < numHashFunctions; i++) {
                long bit = (hashes[0] + (long) i * hashes[1]) % numBits;
                redis.getConnectionFactory().getConnection().stringCommands()
                        .setBit(redisKey.getBytes(StandardCharsets.UTF_8), bit, true);
            }
            // sliding rebuild window: every put refreshes the TTL
            redis.expire(redisKey, rebuildTtl);
        } catch (Exception e) {
            // Guard failure must never break the data path.
            log.warn("D-236: bloom put failed (guard degraded): {}", e.getMessage());
        }
    }

    /**
     * Bloom verdict for the element.
     *
     * @return {@code true} → definitely absent (safe to short-circuit the
     *         loader); {@code false} → probably present (fall through).
     *         Infrastructure failure returns {@code false} (fail-open — the
     *         guard optimizes, the loader decides).
     */
    public boolean definitelyAbsent(String element) {
        if (element == null) {
            return false;
        }
        try {
            long[] hashes = hashes(element);
            for (int i = 0; i < numHashFunctions; i++) {
                long bit = (hashes[0] + (long) i * hashes[1]) % numBits;
                Boolean set = redis.getConnectionFactory().getConnection().stringCommands()
                        .getBit(redisKey.getBytes(StandardCharsets.UTF_8), bit);
                if (!Boolean.TRUE.equals(set)) {
                    shortCircuited.incrementAndGet();
                    return true; // at least one bit unset → definitely absent
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("D-236: bloom check failed (fail-open): {}", e.getMessage());
            return false;
        }
    }

    /** How many junk lookups this guard short-circuited since start. */
    public long shortCircuitedTotal() {
        return shortCircuited.get();
    }

    /**
     * Double hashing over the element: FNV-1a 64-bit as h1 and a rotated
     * variant as h2. Deterministic across JVMs (pure arithmetic, no
     * String.hashCode seed variance).
     */
    private long[] hashes(String element) {
        byte[] bytes = element.getBytes(StandardCharsets.UTF_8);
        long h1 = 0xcbf29ce484222325L; // FNV offset basis
        for (byte b : bytes) {
            h1 ^= (b & 0xff);
            h1 *= 0x100000001b3L; // FNV prime
        }
        long h2 = Long.rotateLeft(h1, 21) ^ bytes.length;
        if (h2 == 0) {
            h2 = 0x9e3779b97f4a7c15L; // golden ratio — keep h2 non-zero
        }
        return new long[]{h1 & 0x7fffffffffffffffL, h2 & 0x7fffffffffffffffL};
    }
}
