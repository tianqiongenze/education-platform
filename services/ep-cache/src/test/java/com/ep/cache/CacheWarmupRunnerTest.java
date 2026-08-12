package com.ep.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.stream.Stream;

import static org.mockito.Mockito.*;

class CacheWarmupRunnerTest {

    private CacheService cacheService;
    private CacheWarmupRunner runner;

    @BeforeEach
    void setup() {
        cacheService = mock(CacheService.class);
        when(cacheService.isEnabled()).thenReturn(true);

        @SuppressWarnings("unchecked")
        ObjectProvider<CacheService> csP = mock(ObjectProvider.class);
        when(csP.getIfAvailable()).thenReturn(cacheService);

        @SuppressWarnings("unchecked")
        ObjectProvider<CacheWarmer> warmersP = mock(ObjectProvider.class);

        runner = new CacheWarmupRunner(csP, warmersP);
        // stash warmers provider for per-test stubbing
        this.warmersProvider = warmersP;
    }

    private ObjectProvider<CacheWarmer> warmersProvider;

    private void warmers(CacheWarmer... warmers) {
        when(warmersProvider.orderedStream()).thenAnswer(inv -> Stream.of(warmers));
    }

    @Test
    void test_runner_calls_warm_on_all_warmers() {
        CacheWarmer w1 = mock(CacheWarmer.class);
        when(w1.cacheName()).thenReturn("qa");
        CacheWarmer w2 = mock(CacheWarmer.class);
        when(w2.cacheName()).thenReturn("course");
        warmers(w1, w2);

        runner.run(null);

        verify(w1).warm(cacheService);
        verify(w2).warm(cacheService);
    }

    @Test
    void test_runner_continues_when_warmer_throws() {
        CacheWarmer w1 = mock(CacheWarmer.class);
        when(w1.cacheName()).thenReturn("qa");
        doThrow(new RuntimeException("boom")).when(w1).warm(cacheService);
        CacheWarmer w2 = mock(CacheWarmer.class);
        when(w2.cacheName()).thenReturn("course");
        warmers(w1, w2);

        runner.run(null);

        verify(w1).warm(cacheService);
        verify(w2).warm(cacheService);
    }

    @Test
    void test_runner_skips_when_cache_disabled() {
        when(cacheService.isEnabled()).thenReturn(false);
        CacheWarmer w1 = mock(CacheWarmer.class);
        when(w1.cacheName()).thenReturn("qa");
        warmers(w1);

        runner.run(null);

        verify(w1, never()).warm(any());
    }
}
