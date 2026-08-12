package com.ep.cache;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CacheEvictionListener tests: parses InvalidateMsg bytes and evicts the
 * matching L1 entry. No real Redis.
 */
class CacheEvictionListenerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    @SuppressWarnings("unchecked")
    void onMessageEvictsL1Entry() throws Exception {
        Cache cache = new ConcurrentMapCache("course:detail");
        cache.put("c-1", "v1");
        assertNotNull(cache.get("c-1"));

        CacheManager cm = mock(CacheManager.class);
        when(cm.getCache("course:detail")).thenReturn(cache);
        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        when(cmP.getIfAvailable()).thenReturn(cm);

        CacheEvictionListener l = new CacheEvictionListener(cmP);
        byte[] payload = MAPPER.writeValueAsBytes(new InvalidateMsg("course:detail", "c-1"));

        l.onMessage(payload);

        assertNull(cache.get("c-1"), "L1 entry should be evicted by broadcast");
    }

    @Test
    @SuppressWarnings("unchecked")
    void onMessageToleratesGarbagePayload() {
        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        CacheEvictionListener l = new CacheEvictionListener(cmP);
        assertDoesNotThrow(() -> l.onMessage("not-json".getBytes()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void onMessageNoOpWhenCacheManagerUnavailable() throws Exception {
        ObjectProvider<CacheManager> cmP = mock(ObjectProvider.class);
        when(cmP.getIfAvailable()).thenReturn(null);
        CacheEvictionListener l = new CacheEvictionListener(cmP);
        byte[] payload = MAPPER.writeValueAsBytes(new InvalidateMsg("x", "y"));
        assertDoesNotThrow(() -> l.onMessage(payload));
    }
}
