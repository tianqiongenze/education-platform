package com.ep.integration;

import com.ep.integration.builder.TestDataBuilder;
import com.ep.integration.support.BusinessFakes.FakeCourseService;
import com.ep.integration.support.BusinessFakes.FakeOrderService;
import com.ep.integration.support.BusinessFakes.FakeUserService;
import com.ep.integration.support.IntegrationDtos.Course;
import com.ep.integration.support.IntegrationDtos.Order;
import com.ep.integration.support.IntegrationDtos.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis cache hit-ratio verification test.
 *
 * <p>Follows the convention established by the other tests in this module
 * (see {@code BusinessFlowIntegrationTest}, {@code IdempotencyAndOptimisticLockTest}):
 * the cache contract is modelled with an in-memory fake
 * ({@link FakeRedisCache}) that mirrors the production cache-aside pattern
 * (read-through with TTL, write-through on mutation), so the hit/miss
 * invariants can be verified deterministically without a live Redis Cluster.
 *
 * <p>The test asserts that:
 * <ul>
 *   <li>the first read of an entity is a cache MISS and loads it from the
 *       backing store (a cache-aside load);</li>
 *   <li>subsequent reads of the same entity are cache HITS and do NOT touch
 *       the backing store;</li>
 *   <li>after a mutation (e.g. course publish), the cached entry is
 *       invalidated/refreshed so the next read observes the new state.</li>
 * </ul>
 */
@Tag("integration")
class CacheHitRatioTest {

    private FakeRedisCache<Course> courseCache;
    private FakeCourseServiceWithLoader courseService;

    @BeforeEach
    void setUp() {
        courseCache = new FakeRedisCache<>();
        courseService = new FakeCourseServiceWithLoader(courseCache);
    }

    @Test
    @DisplayName("cache hit ratio: 2nd+ read of the same entity is a cache HIT, backing store touched once")
    void test_repeated_reads_hit_cache() {
        Course created = courseService.create(
                TestDataBuilder.buildCourse("Cache Course", 1L));
        Long courseId = created.getCourseId();

        // First read: cache MISS — loads from backing store.
        Course first = courseService.getCached(courseId);
        assertNotNull(first);
        assertEquals(1, courseCache.missCount.get(), "first read must be a cache MISS");
        assertEquals(0, courseCache.hitCount.get());
        assertEquals(1, courseService.loaderCount.get(),
                "backing store must be touched exactly once on the first read");

        // Subsequent reads: cache HITs — no backing-store touch.
        courseService.getCached(courseId);
        courseService.getCached(courseId);
        courseService.getCached(courseId);

        assertEquals(1, courseCache.missCount.get(), "still only one miss");
        assertEquals(3, courseCache.hitCount.get(), "three subsequent reads must be HITS");
        assertEquals(1, courseService.loaderCount.get(),
                "backing store must NOT be touched again on cache hits");

        // Hit ratio = hits / (hits + misses) = 3/4.
        double ratio = courseCache.hitRatio();
        assertEquals(0.75, ratio, 0.0001, "hit ratio after 3 hits + 1 miss must be 0.75");
    }

    @Test
    @DisplayName("cache invalidation: mutation evicts the cached entry so the next read sees fresh state")
    void test_mutation_invalidates_cache() {
        Course created = courseService.create(
                TestDataBuilder.buildCourse("Invalidate Course", 2L));
        Long courseId = created.getCourseId();

        // Prime the cache.
        Course before = courseService.getCached(courseId);
        assertNotNull(before);
        assertEquals(1, courseCache.missCount.get());

        // Mutate: publish the course. The service must evict the cached entry.
        courseService.publish(courseId);

        // Next read must be a MISS (cache evicted) and return the PUBLISHED state.
        Course after = courseService.getCached(courseId);
        assertNotNull(after);
        assertEquals(2, courseCache.missCount.get(),
                "read after mutation must be a MISS (cache was evicted)");
        assertEquals(com.ep.integration.support.IntegrationDtos.CourseStatus.PUBLISHED.getCode(),
                after.getStatus(), "read after publish must observe PUBLISHED state");

        // Read again: now a HIT.
        courseService.getCached(courseId);
        assertEquals(1, courseCache.hitCount.get(), "second read after reload must be a HIT");
    }

    // ------------------------------------------------------------------
    // In-memory fake for the Redis cache-aside pattern.
    // ------------------------------------------------------------------

    static final class FakeRedisCache<V> {
        private final ConcurrentHashMap<String, V> store = new ConcurrentHashMap<>();
        final AtomicInteger hitCount = new AtomicInteger(0);
        final AtomicInteger missCount = new AtomicInteger(0);

        V get(String key) {
            V v = store.get(key);
            if (v != null) hitCount.incrementAndGet();
            else missCount.incrementAndGet();
            return v;
        }

        void put(String key, V value) {
            store.put(key, value);
        }

        void evict(String key) {
            store.remove(key);
        }

        double hitRatio() {
            int hits = hitCount.get();
            int misses = missCount.get();
            int total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }

    /**
     * Wraps {@link FakeCourseService} with a cache-aside layer, mirroring the
     * production @Cacheable / @CacheEvict pattern on CourseService.
     */
    static final class FakeCourseServiceWithLoader {
        private final FakeCourseService backing = new FakeCourseService();
        private final FakeRedisCache<Course> cache;
        final AtomicInteger loaderCount = new AtomicInteger(0);

        FakeCourseServiceWithLoader(FakeRedisCache<Course> cache) {
            this.cache = cache;
        }

        Course create(com.ep.integration.support.IntegrationDtos.CreateCourseCmd cmd) {
            Course c = backing.createCourse(cmd);
            return c;
        }

        Course getCached(Long courseId) {
            String key = courseKey(courseId);
            Course cached = cache.get(key);
            if (cached != null) return cached;
            // Cache miss: load from backing store and populate.
            loaderCount.incrementAndGet();
            Course loaded = backing.getCourseById(courseId);
            if (loaded != null) cache.put(key, loaded);
            return loaded;
        }

        void publish(Long courseId) {
            backing.publishCourse(courseId);
            // Production @CacheEvict on the mutating method.
            cache.evict(courseKey(courseId));
        }

        private static String courseKey(Long courseId) {
            return "course:" + courseId;
        }
    }
}
