package com.ep.cache;

/**
 * Abstraction for broadcasting cache-eviction events across nodes.
 * <p>
 * P1-5 (audit D-048): replaces the hard-wired Pulsar-only broadcast with a
 * pluggable strategy. Two implementations are provided:
 * <ul>
 *   <li>{@link RedisCacheBroadcast} - default, uses Redis Pub/Sub (no extra broker needed)</li>
 *   <li>{@code PulsarCacheBroadcast} - optional, activated by {@code ep.cache.broadcast=pulsar}</li>
 * </ul>
 * Contract: broadcast is best-effort. If the underlying transport is unavailable
 * the implementation must degrade to a no-op without throwing.
 */
public interface CacheBroadcast {

    /**
     * Publish an eviction notification for the given cache name and key.
     *
     * @param cacheName logical cache name (e.g. {@code "course:detail"})
     * @param key       cache entry key within that cache
     */
    void publishEvict(String cacheName, String key);

    /**
     * D-216: broadcast a whole-cache eviction (all keys under cacheName).
     * Implemented via the reserved key {@code "*"} on the existing wire
     * format so no new payload schema is needed.
     *
     * @param cacheName logical cache name to clear cluster-wide
     */
    default void publishEvictAll(String cacheName) {
        publishEvict(cacheName, "*");
    }
}
