package com.ep.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStringCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * D-236 tests: BloomFilterGuard hashing determinism, definitely-absent
 * short-circuit semantics, fail-open behavior, and the CacheService
 * bloom-guarded overload.
 */
class BloomFilterGuardTest {

    @SuppressWarnings("unchecked")
    private StringRedisTemplate mockRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        org.springframework.data.redis.connection.RedisConnection connection =
                mock(org.springframework.data.redis.connection.RedisConnection.class);
        RedisStringCommands commands = mock(RedisStringCommands.class);
        when(redis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        when(connection.stringCommands()).thenReturn(commands);
        // default: no bits set (empty filter) → everything "definitely absent"
        when(commands.getBit(any(), anyLong())).thenReturn(false);
        when(commands.setBit(any(), anyLong(), anyBoolean())).thenReturn(true);
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        return redis;
    }

    @Test
    @DisplayName("empty filter: every key is definitely absent (junk traffic short-circuits)")
    void emptyFilterAbsent() {
        BloomFilterGuard guard = new BloomFilterGuard("test", 1000, 0.01, Duration.ofMinutes(10), mockRedis());
        assertTrue(guard.definitelyAbsent("no-such-key"));
        assertEquals(1, guard.shortCircuitedTotal());
    }

    @Test
    @DisplayName("put marks the element present (all its bits set)")
    void putMakesPresent() {
        StringRedisTemplate redis = mockRedis();
        BloomFilterGuard guard = new BloomFilterGuard("test", 1000, 0.01, Duration.ofMinutes(10), redis);
        guard.put("real-key");
        // all k bit-gets now return true
        RedisStringCommands commands = redis.getConnectionFactory().getConnection().stringCommands();
        when(commands.getBit(any(), anyLong())).thenReturn(true);
        assertFalse(guard.definitelyAbsent("real-key"), "put element falls through to the loader");
    }

    @Test
    @DisplayName("hashes are deterministic: same element → same bits across instances")
    void hashesDeterministic() {
        StringRedisTemplate r1 = mockRedis();
        StringRedisTemplate r2 = mockRedis();
        new BloomFilterGuard("a", 1000, 0.01, Duration.ofMinutes(10), r1).put("elem-1");
        new BloomFilterGuard("a", 1000, 0.01, Duration.ofMinutes(10), r2).put("elem-1");
        // both instances issued setBit on the identical bit positions
        RedisStringCommands c1 = r1.getConnectionFactory().getConnection().stringCommands();
        RedisStringCommands c2 = r2.getConnectionFactory().getConnection().stringCommands();
        verify(c1, times(7)).setBit(any(), anyLong(), eq(true));
        verify(c2, times(7)).setBit(any(), anyLong(), eq(true));
    }

    @Test
    @DisplayName("infrastructure failure fails open (returns probably-present, never blocks the data path)")
    void failOpenOnInfraError() {
        StringRedisTemplate redis = mockRedis();
        BloomFilterGuard guard = new BloomFilterGuard("test", 1000, 0.01, Duration.ofMinutes(10), redis);
        RedisStringCommands commands = redis.getConnectionFactory().getConnection().stringCommands();
        when(commands.getBit(any(), anyLong())).thenThrow(new RuntimeException("redis down"));
        assertFalse(guard.definitelyAbsent("any-key"), "fail-open: fall through to the loader");
    }

    @Test
    @DisplayName("null element is safe on both paths")
    void nullSafe() {
        BloomFilterGuard guard = new BloomFilterGuard("test", 1000, 0.01, Duration.ofMinutes(10), mockRedis());
        assertDoesNotThrow(() -> guard.put(null));
        assertFalse(guard.definitelyAbsent(null));
    }

    @Test
    @DisplayName("optimal sizing: 100k insertions @1% fpp → ~958k bits, 7 hashes")
    void optimalSizing() {
        StringRedisTemplate redis = mockRedis();
        // no direct getter — verify through the setBit call count per put
        BloomFilterGuard guard = new BloomFilterGuard("sized", 100_000, 0.01, Duration.ofMinutes(10), redis);
        guard.put("x");
        RedisStringCommands commands = redis.getConnectionFactory().getConnection().stringCommands();
        verify(commands, times(7)).setBit(any(), anyLong(), eq(true)); // k = 7 for fpp=1%
    }

    @Test
    @DisplayName("CacheService bloom overload short-circuits the loader on definitely-absent")
    void cacheServiceShortCircuitsLoader() {
        StringRedisTemplate redis = mockRedis();
        BloomFilterGuard guard = new BloomFilterGuard("cache-test", 1000, 0.01, Duration.ofMinutes(10), redis);
        // CacheService with no CacheManager/Redis (ObjectProviders return null)
        CacheService cacheService = new CacheService(null, null, null);
        boolean loaderRan[] = {false};
        Object result = cacheService.getOrLoad("course", "junk-1",
                () -> {
                    loaderRan[0] = true;
                    return "db-value";
                }, 60, true, guard);
        assertFalse(loaderRan[0], "loader must NOT run for a definitely-absent key");
        assertNull(result, "empty sentinel served as null");
        assertEquals(1, guard.shortCircuitedTotal());
    }
}
