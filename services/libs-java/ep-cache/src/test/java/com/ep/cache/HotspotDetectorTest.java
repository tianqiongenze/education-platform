package com.ep.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * v7 (D-233) tests for the hotspot key detector.
 */
class HotspotDetectorTest {

    /** Monotonic controllable clock (millis). */
    private static final class FakeClock implements HotspotDetector.ClockMillis {
        private final AtomicLong now = new AtomicLong(System.currentTimeMillis());

        void advance(long millis) {
            now.addAndGet(millis);
        }

        void set(long millis) {
            now.set(millis);
        }

        @Override
        public long now() {
            return now.get();
        }
    }

    @Test
    @DisplayName("key below threshold is not promoted")
    void belowThresholdNotPromoted() {
        FakeClock clock = new FakeClock();
        HotspotDetector detector = new HotspotDetector(5, 60_000, clock);

        for (int i = 0; i < 4; i++) {
            assertFalse(detector.recordFallThrough("course", "c-1"),
                    "4 fall-throughs < threshold 5 must not promote");
        }
        assertEquals(0, detector.promotedTotal());
    }

    @Test
    @DisplayName("key reaching threshold is promoted exactly once per window")
    void promoteOncePerWindow() {
        FakeClock clock = new FakeClock();
        HotspotDetector detector = new HotspotDetector(5, 60_000, clock);

        for (int i = 0; i < 5; i++) {
            detector.recordFallThrough("course", "c-1");
        }
        assertEquals(1, detector.promotedTotal(), "5th fall-through promotes once");

        // more traffic in the same window — no double promotion
        detector.recordFallThrough("course", "c-1");
        detector.recordFallThrough("course", "c-1");
        assertEquals(1, detector.promotedTotal());
    }

    @Test
    @DisplayName("promotion re-arms after traffic drops and window slides")
    void reArmAfterWindowSlides() {
        FakeClock clock = new FakeClock();
        HotspotDetector detector = new HotspotDetector(4, 60_000, clock);

        for (int i = 0; i < 4; i++) {
            detector.recordFallThrough("order", "o-9");
        }
        assertEquals(1, detector.promotedTotal());

        // 2 full windows pass with no traffic → next burst can promote again
        clock.advance(120_001);
        for (int i = 0; i < 4; i++) {
            detector.recordFallThrough("order", "o-9");
        }
        assertEquals(2, detector.promotedTotal(), "a new burst after the window slid promotes again");
    }

    @Test
    @DisplayName("different keys are tracked independently")
    void independentKeys() {
        HotspotDetector detector = new HotspotDetector(3, 60_000, new FakeClock());

        detector.recordFallThrough("course", "a");
        detector.recordFallThrough("course", "a");
        assertFalse(detector.recordFallThrough("course", "b"), "other key starts from zero");
        assertTrue(detector.recordFallThrough("course", "a"), "first key reaches threshold here");
    }

    @Test
    @DisplayName("hotKeys snapshot lists currently-hot entries with counts")
    void hotKeysSnapshot() {
        FakeClock clock = new FakeClock();
        HotspotDetector detector = new HotspotDetector(3, 60_000, clock);

        for (int i = 0; i < 3; i++) {
            detector.recordFallThrough("grading", "g-1");
        }
        assertEquals(1, detector.hotKeys().size());
        assertEquals("grading:g-1", detector.hotKeys().get(0).getKey());
        assertTrue(detector.hotKeys().get(0).getValue() >= 3);
    }

    @Test
    @DisplayName("null arguments are safe no-ops")
    void nullSafe() {
        HotspotDetector detector = new HotspotDetector(3, 60_000, new FakeClock());
        assertFalse(detector.recordFallThrough(null, "k"));
        assertFalse(detector.recordFallThrough("cache", null));
        assertTrue(detector.promotedTotal() == 0);
    }

    @Test
    @DisplayName("composite hit rate reflects served-from-cache ratio")
    void compositeHitRate() {
        // build a CacheStats directly: 950 L1 hits, 50 L1 misses (30 L2 hits + 20 loads)
        var stats = new CacheService.CacheStats(950, 50, 30, 20, 20, 0);
        assertEquals(0.98, stats.compositeHitRate(), 1e-9, "(950+30)/1000 = 98%");
        // empty stats are NaN-free
        assertEquals(0.0, new CacheService.CacheStats(0, 0, 0, 0, 0, 0).compositeHitRate());
    }
}
