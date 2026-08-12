package com.ep.data.ingest;

import com.ep.data.cdc.CdcEnvelope;
import com.ep.data.cdc.OpType;
import com.ep.disruptor.DisruptorStats;
import com.ep.disruptor.PublishResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for DisruptorIngestController.
 */
class DisruptorIngestControllerTest {

    private MockMvc mockMvc;
    private DisruptorIngestService mockService;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockService = mock(DisruptorIngestService.class);
        DisruptorIngestController controller = new DisruptorIngestController(mockService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("POST /api/v1/ingest/cdc returns 202 when published")
    void ingestCdcReturns202WhenPublished() throws Exception {
        when(mockService.publish(any())).thenReturn(PublishResult.PUBLISHED);
        CdcEnvelope env = new CdcEnvelope(OpType.CREATE, System.currentTimeMillis(),
                "cell-1", "users", null, "{\"id\":1}");
        mockMvc.perform(post("/api/v1/ingest/cdc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(env)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("POST /api/v1/ingest/cdc returns 429 when degraded")
    void ingestCdcReturns429WhenDropped() throws Exception {
        when(mockService.publish(any())).thenReturn(PublishResult.DROPPED);
        CdcEnvelope env = new CdcEnvelope(OpType.CREATE, System.currentTimeMillis(),
                "cell-1", "users", null, "{\"id\":1}");
        mockMvc.perform(post("/api/v1/ingest/cdc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(env)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("POST /api/v1/ingest/cdc returns 400 for null table")
    void ingestCdcReturns400ForNullTable() throws Exception {
        CdcEnvelope env = new CdcEnvelope(OpType.CREATE, System.currentTimeMillis(),
                "cell-1", null, null, "{}");
        mockMvc.perform(post("/api/v1/ingest/cdc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(env)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/ingest/stats returns DisruptorStats")
    void statsReturnsStats() throws Exception {
        when(mockService.stats()).thenReturn(
                new DisruptorStats("ingest-pipeline", 65536L, 100L, 5L, 95L));
        mockMvc.perform(post("/api/v1/ingest/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ingest-pipeline"))
                .andExpect(jsonPath("$.bufferSize").value(65536));
    }
}
