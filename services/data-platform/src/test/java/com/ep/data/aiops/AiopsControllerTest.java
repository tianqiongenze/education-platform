package com.ep.data.aiops;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link AiopsController}. All four
 * collaborator beans are mocked.
 */
class AiopsControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private AnomalyDetector anomalyDetector;
    private CapacityForecaster capacityForecaster;
    private RootCauseAnalyzer rootCauseAnalyzer;
    private ScheduleOptimizer scheduleOptimizer;

    @BeforeEach
    void setUp() {
        anomalyDetector = mock(AnomalyDetector.class);
        capacityForecaster = mock(CapacityForecaster.class);
        rootCauseAnalyzer = mock(RootCauseAnalyzer.class);
        scheduleOptimizer = mock(ScheduleOptimizer.class);
        AiopsController controller = new AiopsController(
                anomalyDetector, capacityForecaster, rootCauseAnalyzer, scheduleOptimizer);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void anomalies_endpoint() throws Exception {
        var results = List.of(new AnomalyResult(0, 0.3, false, LocalDateTime.now()));
        when(anomalyDetector.detect(any(double[][].class))).thenReturn(results);

        mockMvc.perform(get("/api/v1/aiops/anomalies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void capacityForecast_endpoint() throws Exception {
        var forecast = new ForecastResult(
                new double[]{100.0, 110.0}, new double[]{95.0}, new double[]{115.0},
                10.0, 0.9);
        when(capacityForecaster.forecast(any(double[].class), anyInt())).thenReturn(forecast);

        mockMvc.perform(get("/api/v1/aiops/capacity/forecast")
                        .param("metric", "storage")
                        .param("days", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slope").value(10.0));
    }

    @Test
    void rootCause_endpoint() throws Exception {
        var report = new RootCauseReport(
                List.of("cdc_latency"), 0.85,
                List.of("scale CDC workers"), List.of("cdc→flink→trino"));
        when(rootCauseAnalyzer.analyze(any(Set.class))).thenReturn(report);

        mockMvc.perform(post("/api/v1/aiops/root-cause")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[\"cdc_latency\",\"trino_p99\"]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rootCauseCandidates[0]").value("cdc_latency"))
                .andExpect(jsonPath("$.confidence").value(0.85));
    }

    @Test
    void scheduleOptimize_endpoint() throws Exception {
        var profile = new FlinkJobProfile(
                "job-001", 1_000_000L, 5000L, 4, 8, 8192, 0.7);
        var suggestion = new OptimizationSuggestion(
                "job-001", "parallelism=4", "parallelism=8",
                35.0, "reduce backpressure");
        when(scheduleOptimizer.optimize(any(FlinkJobProfile.class))).thenReturn(suggestion);

        mockMvc.perform(post("/api/v1/aiops/schedule/optimize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(profile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-001"))
                .andExpect(jsonPath("$.estimatedCostSavingPct").value(35.0));
    }
}
