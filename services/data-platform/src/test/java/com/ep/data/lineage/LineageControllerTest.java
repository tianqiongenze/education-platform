package com.ep.data.lineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link LineageController}. Both
 * {@link LineageParser} and {@link LineageGraph} are mocked.
 */
class LineageControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private LineageParser parser;
    private LineageGraph graph;

    @BeforeEach
    void setUp() {
        parser = mock(LineageParser.class);
        graph = mock(LineageGraph.class);
        LineageController controller = new LineageController(parser, graph);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void upstream_endpoint() throws Exception {
        when(graph.upstream(anyString(), anyInt()))
                .thenReturn(Set.of("ep_user", "ep_course"));

        mockMvc.perform(get("/api/v1/lineage/tables/{table}/upstream", "ep_order")
                        .param("depth", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void downstream_endpoint() throws Exception {
        when(graph.downstream("ep_user", 2))
                .thenReturn(Set.of("ep_order", "ep_payment"));

        mockMvc.perform(get("/api/v1/lineage/tables/{table}/downstream", "ep_user")
                        .param("depth", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void parse_endpoint() throws Exception {
        var edges = List.of(new LineageEdge(
                "ep_user", "user_id", "ep_order", "user_id",
                1.0, "DDL"));
        when(parser.parseSql(anyString())).thenReturn(edges);

        var cmd = new LineageController.ParseCmd(
                "INSERT INTO ep_order SELECT user_id FROM ep_user");

        mockMvc.perform(post("/api/v1/lineage/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceTable").value("ep_user"))
                .andExpect(jsonPath("$[0].targetTable").value("ep_order"));

        verify(graph).addEdges(edges);
    }

    @Test
    void parse_blankSql_returns400() throws Exception {
        var cmd = new LineageController.ParseCmd("");

        mockMvc.perform(post("/api/v1/lineage/parse")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isBadRequest());
    }
}
