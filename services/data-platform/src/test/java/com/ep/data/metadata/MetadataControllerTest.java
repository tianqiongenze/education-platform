package com.ep.data.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone-setup MockMvc tests for {@link MetadataController}. Uses a real
 * {@link MetadataController} with a mocked {@link MetadataCollector} so the
 * in-memory {@code store} is exercised through the DDL-ingest endpoint.
 */
class MetadataControllerTest {

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();
    private MetadataCollector collector;

    @BeforeEach
    void setUp() {
        collector = mock(MetadataCollector.class);
        MetadataController controller = new MetadataController(collector);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private MetadataCollector.ColumnMetadata col(String table, String column) {
        return new MetadataCollector.ColumnMetadata(
                "default", "default", table, column,
                "VARCHAR(255)", true, "test column", LocalDateTime.now());
    }

    @Test
    void listTables_emptyInitially() throws Exception {
        mockMvc.perform(get("/api/v1/metadata/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void ingestDdl_thenListTables() throws Exception {
        var cols = List.of(col("ep_user", "user_id"), col("ep_user", "username"));
        when(collector.collectFromDdlEvent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(cols);

        var cmd = new MetadataController.DdlEventCmd(
                "default", "default", "ep_user",
                "CREATE TABLE ep_user (user_id BIGINT, username VARCHAR(64))");

        mockMvc.perform(post("/api/v1/metadata/ddl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tableName").value("ep_user"))
                .andExpect(jsonPath("$[0].columnName").value("user_id"))
                .andExpect(jsonPath("$[1].columnName").value("username"));

        // Now listTables should include the ingested table
        mockMvc.perform(get("/api/v1/metadata/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value("default.ep_user"));
    }

    @Test
    void ingestDdl_blankDdl_returns400() throws Exception {
        var cmd = new MetadataController.DdlEventCmd("default", "default", "t", "");

        mockMvc.perform(post("/api/v1/metadata/ddl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listColumns_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/metadata/tables/{table}/columns", "nonexistent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listColumns_found() throws Exception {
        var cols = List.of(col("ep_user", "user_id"));
        when(collector.collectFromDdlEvent(any(), any(), any(), any())).thenReturn(cols);

        var cmd = new MetadataController.DdlEventCmd("default", "default", "ep_user",
                "CREATE TABLE ep_user (user_id BIGINT)");
        mockMvc.perform(post("/api/v1/metadata/ddl")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/metadata/tables/{table}/columns", "default.ep_user"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tableName").value("ep_user"));
    }

    @Test
    void search_emptyQuery_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/metadata/search").param("q", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void search_findsByTableName() throws Exception {
        var cols = List.of(col("ep_order", "order_id"));
        when(collector.collectFromDdlEvent(any(), any(), any(), any())).thenReturn(cols);

        var cmd = new MetadataController.DdlEventCmd("default", "default", "ep_order",
                "CREATE TABLE ep_order (order_id BIGINT)");
        mockMvc.perform(post("/api/v1/metadata/ddl")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(cmd)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/metadata/search").param("q", "order"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tableName").value("ep_order"));
    }
}
