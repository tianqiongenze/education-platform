package com.ep.senior.profile;

import com.ep.senior.event.PulsarClientProperties;
import com.ep.senior.event.PulsarEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D-148: ElderProfileServiceImpl unit tests.
 */
class ElderProfileServiceImplTest {

    private ElderProfileRepository repository;
    private ElderProfileServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ElderProfileRepository.class);
        when(repository.save(any(ElderProfile.class))).thenAnswer(inv -> {
            ElderProfile e = inv.getArgument(0);
            if (e.getElderId() == null) e.setElderId(System.nanoTime() % 100000L);
            return e;
        });
        service = new ElderProfileServiceImpl(repository, noopPublisher());
    }

    private static PulsarEventPublisher noopPublisher() {
        ObjectProvider<org.apache.pulsar.client.api.PulsarClient> cp = mock(ObjectProvider.class);
        when(cp.getIfAvailable()).thenReturn(null);
        PulsarClientProperties props = new PulsarClientProperties();
        PulsarEventPublisher pub = new PulsarEventPublisher(cp, props, false);
        pub.init();
        return pub;
    }

    private CreateElderCmd cmd(String name) {
        return new CreateElderCmd(100L, 10L, name, "male", "1945-03-15", "13800001111");
    }

    @Test
    void createElder_success() {
        ElderProfile e = service.createElder(cmd("王奶奶"));
        assertNotNull(e.getElderId());
        assertEquals("王奶奶", e.getName());
        assertEquals(ElderStatus.ACTIVE.getCode(), e.getStatus());
        assertNotNull(e.getCreatedAt());
    }

    @Test
    void createElder_blankName_throws() {
        CreateElderCmd c = cmd("");
        assertThrows(IllegalArgumentException.class, () -> service.createElder(c));
    }

    @Test
    void createElder_nullFacilityId_throws() {
        CreateElderCmd c = cmd("test");
        c.setFacilityId(null);
        assertThrows(NullPointerException.class, () -> service.createElder(c));
    }

    @Test
    void getElderById_found() {
        ElderProfile e = newElder(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(e));
        ElderProfile result = service.getElderById(1L);
        assertNotNull(result);
        assertEquals(1L, result.getElderId());
    }

    @Test
    void getElderById_notFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());
        assertNull(service.getElderById(999L));
    }

    @Test
    void listElders_defaultPage() {
        when(repository.findAllByOrderByElderIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(newElder(1L))));
        List<ElderProfile> result = service.listElders(null, null);
        assertEquals(1, result.size());
    }

    @Test
    void searchElders_success() {
        when(repository.findByNameContainingOrderByElderIdDesc("王"))
                .thenReturn(List.of(newElder(1L)));
        List<ElderProfile> result = service.searchElders("王");
        assertEquals(1, result.size());
    }

    @Test
    void pauseElder_success() {
        ElderProfile e = newElder(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(e));
        service.pauseElder(1L);
        assertEquals(ElderStatus.PAUSED.getCode(), e.getStatus());
    }

    @Test
    void activateElder_fromPaused_success() {
        ElderProfile e = newElder(1L);
        e.setStatus(ElderStatus.PAUSED.getCode());
        when(repository.findById(1L)).thenReturn(Optional.of(e));
        service.activateElder(1L);
        assertEquals(ElderStatus.ACTIVE.getCode(), e.getStatus());
    }

    @Test
    void dischargeElder_success() {
        ElderProfile e = newElder(1L);
        when(repository.findById(1L)).thenReturn(Optional.of(e));
        service.dischargeElder(1L);
        assertEquals(ElderStatus.DISCHARGED.getCode(), e.getStatus());
    }

    @Test
    void pauseElder_notFound_throws() {
        when(repository.findById(999L)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> service.pauseElder(999L));
    }

    private ElderProfile newElder(Long id) {
        ElderProfile e = new ElderProfile();
        e.setElderId(id);
        e.setFacilityId(100L);
        e.setTenantId(10L);
        e.setName("test-" + id);
        e.setStatus(ElderStatus.ACTIVE.getCode());
        e.setCreatedAt(System.currentTimeMillis());
        e.setUpdatedAt(System.currentTimeMillis());
        return e;
    }
}
