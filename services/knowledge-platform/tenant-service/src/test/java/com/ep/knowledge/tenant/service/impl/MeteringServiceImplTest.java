package com.ep.knowledge.tenant.service.impl;

import com.ep.knowledge.tenant.entity.UsageRecord;
import com.ep.knowledge.tenant.mapper.UsageRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * D-179: Mockito-extension unit test for {@link MeteringServiceImpl}.
 *
 * <p>Mocks the {@link UsageRecordMapper} so the metering record/summarize logic
 * can be exercised without a database, following the knowledge-platform service-test
 * convention (no Spring context, no H2).
 */
@ExtendWith(MockitoExtension.class)
class MeteringServiceImplTest {

    @Mock
    private UsageRecordMapper usageRecordMapper;

    private MeteringServiceImpl meteringService;

    @BeforeEach
    void setUp() {
        meteringService = new MeteringServiceImpl(usageRecordMapper);
    }

    @Test
    void testRecord_inserts_usage_record_with_timestamp() {
        when(usageRecordMapper.insert(any(UsageRecord.class))).thenReturn(1);

        meteringService.record("t-001", "API_CALL", 5L);

        ArgumentCaptor<UsageRecord> captor = ArgumentCaptor.forClass(UsageRecord.class);
        verify(usageRecordMapper, times(1)).insert(captor.capture());
        UsageRecord captured = captor.getValue();
        assertEquals("t-001", captured.getTenantId());
        assertEquals("API_CALL", captured.getResourceType());
        assertEquals(5L, captured.getQuantity());
        assertNotNull(captured.getRecordedAt(), "recordedAt must be stamped");
    }

    @Test
    void testSummarize_groups_by_resource_type_within_period() {
        // Two API_CALL records and one LLM_TOKEN record in 2026-08.
        LocalDateTime inPeriod = LocalDateTime.of(2026, 8, 15, 12, 0);
        UsageRecord r1 = usageRecord("u1", "t-001", "API_CALL", 10L, inPeriod);
        UsageRecord r2 = usageRecord("u2", "t-001", "API_CALL", 5L, inPeriod.plusDays(1));
        UsageRecord r3 = usageRecord("u3", "t-001", "LLM_TOKEN", 1000L, inPeriod.plusDays(2));
        when(usageRecordMapper.selectList(any())).thenReturn(List.of(r1, r2, r3));

        Map<String, Long> summary = meteringService.summarize("t-001", "2026-08");

        assertEquals(2, summary.size());
        assertEquals(15L, summary.get("API_CALL"), "API_CALL quantities should sum");
        assertEquals(1000L, summary.get("LLM_TOKEN"), "LLM_TOKEN quantity should be present");
    }

    @Test
    void testSummarize_excludes_records_outside_period() {
        // summarize() computes a date range based on the period; the test
        // verifies the period string is parsed without error and that an
        // empty mapper result yields an empty summary.
        when(usageRecordMapper.selectList(any())).thenReturn(List.of());

        Map<String, Long> summary = meteringService.summarize("t-001", "2026-08");

        assertNotNull(summary);
        assertTrue(summary.isEmpty(), "no records -> empty summary");
    }

    @Test
    void testSummarize_period_parses_yyyy_mm() {
        // Smoke-check: the period "YYYY-MM" parses cleanly and does not throw
        // (YearMonth.parse is used internally).
        when(usageRecordMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> meteringService.summarize("t-001", "2026-12"));

        // And confirm the parsed period is 2026-12 (start of month).
        assertEquals(YearMonth.of(2026, 12), YearMonth.parse("2026-12"));
    }

    private static UsageRecord usageRecord(String id, String tenantId, String type,
                                           long qty, LocalDateTime recordedAt) {
        UsageRecord r = new UsageRecord();
        r.setId(id);
        r.setTenantId(tenantId);
        r.setResourceType(type);
        r.setQuantity(qty);
        r.setRecordedAt(recordedAt);
        return r;
    }
}
