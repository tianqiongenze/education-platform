package com.ep.data.profile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link DataAssetProfileController}. The
 * controller has no injected dependencies (uses an in-memory store), so it
 * is instantiated directly.
 */
class DataAssetProfileControllerTest {

    private MockMvc mockMvc;
    private DataAssetProfileController controller;

    @BeforeEach
    void setUp() {
        controller = new DataAssetProfileController();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getProfile_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/profile/tables/{table}", "nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void refresh_thenGetProfile() throws Exception {
        mockMvc.perform(post("/api/v1/profile/tables/{table}/refresh", "ep_user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableName").value("ep_user"))
                .andExpect(jsonPath("$.qualityScore").isNumber())
                .andExpect(jsonPath("$.database").value("default"));

        // Now getProfile should return the refreshed profile
        mockMvc.perform(get("/api/v1/profile/tables/{table}", "ep_user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableName").value("ep_user"));
    }

    @Test
    void refresh_qualifiedTable() throws Exception {
        mockMvc.perform(post("/api/v1/profile/tables/{table}/refresh", "analytics.ep_events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableName").value("ep_events"))
                .andExpect(jsonPath("$.database").value("analytics"));
    }

    @Test
    void listTables_afterRefresh() throws Exception {
        mockMvc.perform(post("/api/v1/profile/tables/{table}/refresh", "ep_order"));

        mockMvc.perform(get("/api/v1/profile/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("default.ep_order"));
    }

    @Test
    void computeQualityScore_perfectData() {
        int score = controller.computeQualityScore(10000, 0.0, 1.0);
        assertEquals(100, score);
    }

    @Test
    void computeQualityScore_minimalData() {
        int score = controller.computeQualityScore(0, 1.0, 0.0);
        // completeness=0, nullPenalty=0, uniqueness=0, stability=20
        assertEquals(20, score);
    }

    @Test
    void computeQualityScore_partialData() {
        int score = controller.computeQualityScore(5000, 0.1, 0.8);
        // completeness = 0.5*30 = 15, null = 0.9*30 = 27, uniqueness = 0.8*20 = 16, stability = 20
        // total = 78
        assertTrue(score > 70 && score < 85,
                "Expected score around 78, got " + score);
    }
}
