package com.ep.grading.controller;

import com.ep.grading.grading.dto.StartGradingCmd;
import com.ep.grading.grading.entity.GradePipeline;
import com.ep.grading.grading.entity.GradePipelineStatus;
import com.ep.grading.grading.entity.GradeStage;
import com.ep.grading.grading.entity.GradeStageStatus;
import com.ep.grading.grading.service.GradingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GradingControllerTest {

    private MockMvc mockMvc;
    private GradingService gradingService;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @BeforeEach
    void setUp() throws Exception {
        gradingService = mock(GradingService.class);
        GradingController controller = new GradingController();
        java.lang.reflect.Field f = GradingController.class.getDeclaredField("gradingService");
        f.setAccessible(true);
        f.set(controller, gradingService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void test_start_endpoint() throws Exception {
        GradePipeline pipeline = new GradePipeline();
        pipeline.setId(1L);
        pipeline.setSubmissionId(10L);
        pipeline.setStatus(GradePipelineStatus.COMPLETED);
        pipeline.setTotalScore(70);
        when(gradingService.startGrading(any(StartGradingCmd.class))).thenReturn(pipeline);

        StartGradingCmd cmd = new StartGradingCmd(10L, 5L, "gk");
        mockMvc.perform(post("/api/v1/grading/start")
                        .header("X-Role", "teacher")
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void test_find_by_id_endpoint() throws Exception {
        GradePipeline pipeline = new GradePipeline();
        pipeline.setId(1L);
        pipeline.setSubmissionId(10L);
        when(gradingService.findById(1L)).thenReturn(pipeline);

        mockMvc.perform(get("/api/v1/grading/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void test_find_by_id_not_found() throws Exception {
        when(gradingService.findById(99L)).thenReturn(null);
        mockMvc.perform(get("/api/v1/grading/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void test_get_stages_endpoint() throws Exception {
        GradeStage stage = new GradeStage();
        stage.setId(1L);
        stage.setStageName("auto_check");
        stage.setStatus(GradeStageStatus.PASSED);
        when(gradingService.getStages(1L)).thenReturn(List.of(stage));

        mockMvc.perform(get("/api/v1/grading/1/stages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stageName").value("auto_check"));
    }

    @Test
    void test_find_by_submission_id_endpoint() throws Exception {
        GradePipeline pipeline = new GradePipeline();
        pipeline.setId(1L);
        pipeline.setSubmissionId(10L);
        when(gradingService.findBySubmissionId(10L)).thenReturn(pipeline);

        mockMvc.perform(get("/api/v1/grading").param("submissionId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.submissionId").value(10));
    }

    @Test
    void test_find_by_submission_id_not_found() throws Exception {
        when(gradingService.findBySubmissionId(99L)).thenReturn(null);
        mockMvc.perform(get("/api/v1/grading").param("submissionId", "99"))
                .andExpect(status().isNotFound());
    }
}
