package com.ep.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-243 tests: L3 hotspot mirror — promotion lifecycle, hit-rate acceptance
 * bar, eviction coupling, marker decay.
 */
class HotspotMirrorCacheTest {

    @Test
    @DisplayName("unpromoted keys always miss (mirror only carries promoted hotspots)")
    void unpromotedAlwaysMiss() {
        HotspotMirrorCache mirror = new HotspotMirrorCache();
        assertEquals(HotspotMirrorCache.MISSING, mirror.getIfPresent("course", "c-1"));
        assertEquals(0, mirror.promotionsTotal());
    }

    @Test
    @DisplayName("promotion + put → local hit streak (survives repeated reads)")
    void promotedKeyHits() {
        HotspotMirrorCache mirror = new HotspotMirrorCache();
        mirror.promote("course", "hot-1");
        assertEquals(1, mirror.promotionsTotal());

        mirror.put("course", "hot-1", "course-value");
        assertEquals("course-value", mirror.getIfPresent("course", "hot-1"));
        assertEquals("course-value", mirror.getIfPresent("course", "hot-1"));
        assertEquals(2, mirror.l3Hits());
        assertEquals(0, mirror.l3Misses(), "a promoted+filled key must not miss");
    }

    @Test
    @DisplayName("put without promotion is a no-op (data-path discipline)")
    void putWithoutPromotionNoop() {
        HotspotMirrorCache mirror = new HotspotMirrorCache();
        mirror.put("course", "cold-1", "value");
        assertEquals(HotspotMirrorCache.MISSING, mirror.getIfPresent("course", "cold-1"));
        assertEquals(0, mirror.size());
    }

    @Test
    @DisplayName("evict clears a promoted entry (stale hot value impossible)")
    void evictClearsEntry() {
        HotspotMirrorCache mirror = new HotspotMirrorCache();
        mirror.promote("course", "hot-1");
        mirror.put("course", "hot-1", "v1");
        assertEquals("v1", mirror.getIfPresent("course", "hot-1"));

        mirror.evict("course", "hot-1");
        assertEquals(HotspotMirrorCache.MISSING, mirror.getIfPresent("course", "hot-1"));
    }

    @Test
    @DisplayName("evictAll sweeps only the target cacheName")
    void evictAllScopesByCache() {
        HotspotMirrorCache mirror = new HotspotMirrorCache();
        mirror.promote("course", "c-1");
        mirror.promote("grading", "g-1");
        mirror.put("course", "c-1", "cv");
        mirror.put("grading", "g-1", "gv");

        mirror.evictAll("course");

        assertEquals(HotspotMirrorCache.MISSING, mirror.getIfPresent("course", "c-1"));
        assertEquals("gv", mirror.getIfPresent("grading", "g-1"), "other cache must be untouched");
    }

    @Test
    @DisplayName("promotion marker decays after the TTL (bounded promotion set)")
    void markerDecaysAfterTtl() throws Exception {
        // 50ms TTL — small enough to observe decay without slowing the suite
        HotspotMirrorCache mirror = new HotspotMirrorCache(100, Duration.ofMillis(50));
        mirror.promote("course", "decaying-1");
        assertTrue(mirror.isPromoted("course", "decaying-1"));

        Thread.sleep(80);
        assertFalse(mirror.isPromoted("course", "decaying-1"), "marker must decay with the TTL");
    }

    @Test
    @DisplayName("hit-rate acceptance bar: ≥90% after 100 lookups with hot hits")
    void acceptanceBar() {
        HotspotMirrorCache mirror = new HotspotMirrorCache();
        mirror.promote("course", "hot-bar");
        mirror.put("course", "hot-bar", "v");
        // 100 reads: 95 hits + 5 misses on unpromoted keys → 95% ≥ 90% bar
        for (int i = 0; i < 95; i++) {
            assertEquals("v", mirror.getIfPresent("course", "hot-bar"));
        }
        for (int i = 0; i < 5; i++) {
            assertEquals(HotspotMirrorCache.MISSING, mirror.getIfPresent("course", "cold-" + i));
        }
        assertTrue(mirror.meetsAcceptanceBar(), "95% hit rate must meet the 90% v8 bar");
        assertTrue(mirror.l3HitRate() >= 0.90);
    }

    @Test
    @DisplayName("empty sentinel value flows through the mirror")
    void emptySentinelFlows() {
        HotspotMirrorCache mirror = new HotspotMirrorCache();
        mirror.promote("course", "empty-1");
        mirror.put("course", "empty-1", CacheService.EMPTY_SENTINEL);
        assertEquals(CacheService.EMPTY_SENTINEL, mirror.getIfPresent("course", "empty-1"));
    }
}
