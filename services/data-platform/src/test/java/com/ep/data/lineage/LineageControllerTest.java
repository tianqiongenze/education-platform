package com.ep.data.lineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link LineageController}. The
 * {@link LineageParser}, {@link LineageGraph}, and
 * {@link GnnLineageInferrer} are all mocked.
 */
class LineageControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private LineageParser parser;
    private LineageGraph graph;
    private GnnLineageInferrer gnnInferrer;

    @BeforeEach
    void setUp() {
        parser = mock(LineageParser.class);
        graph = mock(LineageGraph.class);
        gnnInferrer = mock(GnnLineageInferrer.class);
        LineageController controller = new LineageController(parser, graph, gnnInferrer);
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

    // ─── D-188: GNN inference endpoints ────────────────────────────────

    @Test
    void gnnInfer_endpoint_returns_gnn_edges() throws Exception {
        var ast = new LineageParser.LineageParseResult(
                "ep_order", List.of("uid"), List.of("ep_user"), List.of("user_id"),
                false, List.of());
        var gnnEdges = List.of(new LineageEdge(
                "ep_user", "user_id", "ep_order", "uid", 0.85, "gnn"));

        when(parser.parseSqlAst(anyString())).thenReturn(ast);
        when(gnnInferrer.infer(any())).thenReturn(gnnEdges);

        var cmd = new LineageController.ParseCmd(
                "INSERT INTO ep_order (uid) SELECT user_id FROM ep_user");

        mockMvc.perform(post("/api/v1/lineage/gnn-infer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sourceTable").value("ep_user"))
                .andExpect(jsonPath("$[0].targetTable").value("ep_order"))
                .andExpect(jsonPath("$[0].inferredBy").value("gnn"));

        verify(graph).addEdges(gnnEdges);
        verify(graph).persistEdges(gnnEdges);
    }

    @Test
    void gnnInfer_blankSql_returns400() throws Exception {
        var cmd = new LineageController.ParseCmd("");

        mockMvc.perform(post("/api/v1/lineage/gnn-infer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void gnnInfer_nullAst_returns_empty_list() throws Exception {
        when(parser.parseSqlAst(anyString())).thenReturn(null);

        var cmd = new LineageController.ParseCmd("SELECT 1");

        mockMvc.perform(post("/api/v1/lineage/gnn-infer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void candidates_endpoint_returns_map() throws Exception {
        var candidates = Map.of(
                1L, new LineageEdge("ep_user", "email", "ep_order", "contact", 0.65, "gnn"));
        when(gnnInferrer.getCandidates()).thenReturn(candidates);

        mockMvc.perform(get("/api/v1/lineage/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['1'].sourceTable").value("ep_user"))
                .andExpect(jsonPath("$['1'].targetColumn").value("contact"));
    }

    @Test
    void confirmCandidate_endpoint_returns_confirmed_edge() throws Exception {
        var confirmed = new LineageEdge(
                "ep_user", "email", "ep_order", "contact", 1.0, "gnn");
        when(gnnInferrer.confirmCandidate(anyLong())).thenReturn(confirmed);

        mockMvc.perform(post("/api/v1/lineage/candidates/{id}/confirm", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceTable").value("ep_user"))
                .andExpect(jsonPath("$.confidence").value(1.0));

        verify(graph).addEdges(any());
        verify(graph).persistEdges(any());
    }

    @Test
    void confirmCandidate_unknown_id_returns404() throws Exception {
        when(gnnInferrer.confirmCandidate(anyLong())).thenReturn(null);

        mockMvc.perform(post("/api/v1/lineage/candidates/{id}/confirm", 999L))
                .andExpect(status().isNotFound());
    }
}
