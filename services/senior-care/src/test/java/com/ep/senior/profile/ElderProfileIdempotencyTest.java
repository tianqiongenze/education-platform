package com.ep.senior.profile;

import com.ep.cache.CacheService;
import com.ep.senior.event.PulsarClientProperties;
import com.ep.senior.event.PulsarEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D-221: idempotent elder-profile creation — a retried create with the same
 * business key returns the original profile instead of duplicating it.
 */
class ElderProfileIdempotencyTest {

    private ElderProfileRepository repository;
    private CacheService cacheService;
    private ElderProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ElderProfileRepository.class);
        when(repository.save(any(ElderProfile.class))).thenAnswer(inv -> inv.getArgument(0));
        cacheService = mock(CacheService.class);
        service = new ElderProfileServiceImpl(repository, noopPublisher(), cacheService);
    }

    private static PulsarEventPublisher noopPublisher() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(null);
        PulsarClientProperties props = new PulsarClientProperties();
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props, false);
        pub.init();
        return pub;
    }

    private CreateElderCmd cmd(String key) {
        CreateElderCmd cmd = new CreateElderCmd(100L, 10L, "王奶奶", "female",
                "1945-03-15", "13800001111");
        cmd.setIdempotencyKey(key);
        return cmd;
    }

    @Test
    @DisplayName("first create with a key saves a profile that carries the key")
    void firstCreatePersistsKey() {
        ElderProfile elder = service.createElder(cmd("idem-e1"));

        assertEquals("idem-e1", elder.getIdempotencyKey());
        verify(repository, times(1)).save(any(ElderProfile.class));
    }

    @Test
    @DisplayName("second create with the same key returns the original profile without saving")
    void replayReturnsOriginal() {
        ElderProfile original = new ElderProfile();
        original.setElderId(555L);
        original.setTenantId(10L);
        original.setFacilityId(100L);
        original.setName("王奶奶");
        when(repository.findByTenantIdAndIdempotencyKey(10L, "idem-e1"))
                .thenReturn(Optional.of(original));

        ElderProfile result = service.createElder(cmd("idem-e1"));

        assertSame(original, result);
        assertEquals(555L, result.getElderId());
        verify(repository, never()).save(any(ElderProfile.class));
    }

    @Test
    @DisplayName("different keys create different profiles")
    void differentKeysCreateSeparately() {
        when(repository.findByTenantIdAndIdempotencyKey(eq(10L), any())).thenReturn(Optional.empty());

        ElderProfile first = service.createElder(cmd("idem-a"));
        ElderProfile second = service.createElder(cmd("idem-b"));

        verify(repository, times(2)).save(any(ElderProfile.class));
        assertEquals("idem-a", first.getIdempotencyKey());
        assertEquals("idem-b", second.getIdempotencyKey());
    }

    @Test
    @DisplayName("null key keeps legacy create-always behaviour (no probe)")
    void nullKeyKeepsLegacyBehaviour() {
        service.createElder(cmd(null));

        verify(repository, never()).findByTenantIdAndIdempotencyKey(eq(10L), any());
        verify(repository, times(1)).save(any(ElderProfile.class));
    }
}
