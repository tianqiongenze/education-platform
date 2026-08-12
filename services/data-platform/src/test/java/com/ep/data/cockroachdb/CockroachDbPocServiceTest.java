package com.ep.data.cockroachdb;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CockroachDbPocServiceTest {

    private JdbcTemplate jdbc;
    private CockroachDbPocService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        // Use the package-private constructor that accepts a JdbcTemplate directly.
        service = new CockroachDbPocService(jdbc);
    }

    @Test
    void verifyRegionalByRow_passes_whenTableAndRowsWork() {
        doNothing().when(jdbc).execute(anyString());
        when(jdbc.queryForObject(eq("SELECT count(*) FROM crdb_poc_regional WHERE region = 'ap-southeast-1'"), eq(Integer.class)))
                .thenReturn(1);

        CockroachDbPocResult result = service.verifyRegionalByRow();

        assertEquals(CockroachDbPocResult.Status.PASS, result.status());
        assertEquals("REGIONAL_BY_ROW", result.verificationItem());
        assertTrue(result.details().contains("REGIONAL BY ROW"));
    }

    @Test
    void verifyRegionalByRow_fails_whenCountMismatch() {
        doNothing().when(jdbc).execute(anyString());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        CockroachDbPocResult result = service.verifyRegionalByRow();

        assertEquals(CockroachDbPocResult.Status.FAIL, result.status());
        assertTrue(result.details().contains("Unexpected row count"));
    }

    @Test
    void verifyRegionalByRow_skips_whenJdbcThrows() {
        doThrow(new RuntimeException("connection refused"))
                .when(jdbc).execute(anyString());

        CockroachDbPocResult result = service.verifyRegionalByRow();

        assertEquals(CockroachDbPocResult.Status.SKIP, result.status());
        assertTrue(result.details().contains("connection refused"));
    }

    @Test
    void verifyCrossRegionLatency_passes_whenWritesSucceed() {
        when(jdbc.update(anyString(), anyInt(), anyString())).thenReturn(1);

        CockroachDbPocResult result = service.verifyCrossRegionLatency();

        assertEquals(CockroachDbPocResult.Status.PASS, result.status());
        assertEquals("CROSS_REGION_LATENCY", result.verificationItem());
        assertTrue(result.latencyMs() >= 0);
    }

    @Test
    void verifyCrossRegionLatency_skips_whenWriteFails() {
        when(jdbc.update(anyString(), anyInt(), anyString()))
                .thenThrow(new RuntimeException("timeout"));

        CockroachDbPocResult result = service.verifyCrossRegionLatency();

        assertEquals(CockroachDbPocResult.Status.SKIP, result.status());
        assertTrue(result.details().contains("timeout"));
    }

    @Test
    void verifyChangefeedsAdapter_passes_whenCdcAccepted() {
        when(jdbc.update(anyString())).thenReturn(0);

        CockroachDbPocResult result = service.verifyChangefeedsAdapter();

        assertEquals(CockroachDbPocResult.Status.PASS, result.status());
        assertEquals("CHANGEFEEDS_ADAPTER", result.verificationItem());
    }

    @Test
    void verifyChangefeedsAdapter_skips_whenSinkUnavailable() {
        when(jdbc.update(anyString()))
                .thenThrow(new RuntimeException("unknown sink type"));

        CockroachDbPocResult result = service.verifyChangefeedsAdapter();

        assertEquals(CockroachDbPocResult.Status.SKIP, result.status());
        assertTrue(result.details().contains("unknown sink type"));
    }

    @Test
    void verifyOneMillionConnections_passes_whenPoolSizeSufficient() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(5000);

        CockroachDbPocResult result = service.verifyOneMillionConnections();

        assertEquals(CockroachDbPocResult.Status.PASS, result.status());
        assertTrue(result.details().contains("5000"));
    }

    @Test
    void verifyOneMillionConnections_fails_whenPoolSizeTooLow() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(100);

        CockroachDbPocResult result = service.verifyOneMillionConnections();

        assertEquals(CockroachDbPocResult.Status.FAIL, result.status());
        assertTrue(result.details().contains("< 1000"));
    }

    @Test
    void runFullPoc_returnsAllFourItems() {
        doNothing().when(jdbc).execute(anyString());
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.update(anyString(), any(), any())).thenReturn(1);
        when(jdbc.update(anyString())).thenReturn(0);

        List<CockroachDbPocResult> results = service.runFullPoc();

        assertEquals(4, results.size());
        assertEquals("REGIONAL_BY_ROW", results.get(0).verificationItem());
        assertEquals("CROSS_REGION_LATENCY", results.get(1).verificationItem());
        assertEquals("CHANGEFEEDS_ADAPTER", results.get(2).verificationItem());
        assertEquals("ONE_MILLION_CONNECTIONS", results.get(3).verificationItem());
    }
}
