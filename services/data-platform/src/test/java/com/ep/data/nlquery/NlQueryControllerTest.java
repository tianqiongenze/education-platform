package com.ep.data.nlquery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link NlQueryController}. Both the
 * {@link TextToSqlService} and {@link ConversationContext} are mocked.
 */
class NlQueryControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private TextToSqlService textToSqlService;
    private ConversationContext conversationContext;

    @BeforeEach
    void setUp() {
        textToSqlService = mock(TextToSqlService.class);
        conversationContext = mock(ConversationContext.class);
        NlQueryController controller = new NlQueryController(textToSqlService, conversationContext);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private NlQueryResult result(String question) {
        return new NlQueryResult(
                question, "SELECT", "SELECT 1", true,
                List.of(), 42L, "sess-001");
    }

    @Test
    void query_endpoint() throws Exception {
        when(textToSqlService.query(anyString(), anyString(), anyString()))
                .thenReturn(result("show me users"));

        var cmd = new NlQueryController.QueryCmd("user-1", "show me users", "sess-001");

        mockMvc.perform(post("/api/v1/nlquery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.question").value("show me users"))
                .andExpect(jsonPath("$.intent").value("SELECT"))
                .andExpect(jsonPath("$.isValid").value(true))
                .andExpect(jsonPath("$.sessionId").value("sess-001"));
    }

    @Test
    void query_blankQuestion_returns400() throws Exception {
        var cmd = new NlQueryController.QueryCmd("user-1", "", "sess-001");

        mockMvc.perform(post("/api/v1/nlquery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void query_nullBody_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/nlquery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void history_endpoint() throws Exception {
        var turns = List.of(
                new ChatTurn("q1", "SELECT 1", "ok", LocalDateTime.now()));
        when(conversationContext.getHistory("sess-001")).thenReturn(turns);

        mockMvc.perform(get("/api/v1/nlquery/sessions/{sessionId}/history", "sess-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].question").value("q1"));
    }

    @Test
    void clearSession_endpoint() throws Exception {
        mockMvc.perform(delete("/api/v1/nlquery/sessions/{sessionId}", "sess-001"))
                .andExpect(status().isNoContent());

        verify(conversationContext).clearSession("sess-001");
    }

    @Test
    void auditLog_endpoint() throws Exception {
        when(textToSqlService.getAuditLog()).thenReturn(List.of("entry-1", "entry-2"));

        mockMvc.perform(get("/api/v1/nlquery/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("entry-1"));
    }
}
