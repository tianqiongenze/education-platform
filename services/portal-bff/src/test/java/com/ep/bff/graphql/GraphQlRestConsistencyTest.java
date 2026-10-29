package com.ep.bff.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * D-244 (v8 compatibility gap): GraphQL ↔ REST cross-protocol consistency
 * tests for the course read model.
 *
 * <p><b>The gap</b>: the same entity reaches clients over two protocols from
 * two different channels — REST aggregates it via the Dubbo contract class
 * ({@code com.ep.api.Course}), GraphQL projects it through
 * {@code RestCourseReadModelAdapter} → {@code CourseReadModel.Course}. Nothing
 * previously guarded the projection: a downstream field rename
 * ({@code title → courseName}) would silently break the GraphQL {@code title!}
 * non-null contract while REST kept working.</p>
 *
 * <p><b>What these tests pin</b>:</p>
 * <ol>
 *   <li><b>Field-projection identity</b> — the adapter's projection carries
 *       the exact downstream values (id via courseId mapping, title, price),
 *       so both protocols serve identical data for the same id.</li>
 *   <li><b>Cross-protocol nullability agreement</b> — a downstream record
 *       that REST would render as an empty body (null title) resolves to
 *       GraphQL "absent" (empty Optional), never a value that would violate
 *       the {@code title: String!} schema contract.</li>
 *   <li><b>Schema-projection guard</b> — every non-ID field declared in
 *       schema.graphqls has a corresponding source field on the downstream
 *       projection record, so schema evolution cannot silently outpace the
 *       adapter mapping.</li>
 * </ol>
 */
class GraphQlRestConsistencyTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private record Bound(RestCourseReadModelAdapter adapter, MockRestServiceServer server) {
    }

    /** Same-builder binding — the working pattern from RestCourseReadModelAdapterTest. */
    private Bound boundAdapter() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://core:8083");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Bound(new RestCourseReadModelAdapter(builder, mapper), server);
    }

    private static final String DOWNSTREAM_JSON = """
            {"courseId": 1, "title": "分布式系统入门", "price": 199.50,
             "description": "REST-only field that GraphQL must ignore"}
            """;

    @Test
    @DisplayName("field-projection identity: GraphQL projection == downstream REST values")
    void fieldProjectionIdentity() {
        Bound bound = boundAdapter();
        RestCourseReadModelAdapter adapter = bound.adapter();
        MockRestServiceServer server = bound.server();
        server.expect(requestTo("http://core:8083/api/v1/courses/1"))
                .andRespond(withSuccess(DOWNSTREAM_JSON, MediaType.APPLICATION_JSON));

        Optional<CourseReadModel.Course> resolved = adapter.findCourse("1");

        assertThat(resolved).isPresent();
        CourseReadModel.Course course = resolved.get();
        // the exact three assertions that guard cross-protocol identity
        assertThat(course.id()).isEqualTo("1"); // courseId → id mapping intact
        assertThat(course.title()).isEqualTo("分布式系统入门"); // no silent rename
        assertThat(course.price()).isEqualTo(199.50); // BigDecimal → double unboxing exact for 2dp
    }

    @Test
    @DisplayName("cross-protocol nullability: null title downstream → GraphQL absent, not schema-breaking")
    void nullTitleDownstreamYieldsAbsent() {
        Bound bound = boundAdapter();
        RestCourseReadModelAdapter adapter = bound.adapter();
        MockRestServiceServer server = bound.server();
        server.expect(requestTo("http://core:8083/api/v1/courses/2"))
                .andRespond(withSuccess(
                        "{\"courseId\": 2, \"title\": null, \"price\": 88.0}",
                        MediaType.APPLICATION_JSON));

        // REST would surface this record with a null title (or 404 at the
        // controller); GraphQL MUST resolve it as absent — a non-null Course
        // with title=null would violate `title: String!` at the schema layer.
        assertThat(adapter.findCourse("2")).isEmpty();
    }

    @Test
    @DisplayName("list consistency: findCourses preserves downstream order and values")
    void listConsistency() {
        Bound bound = boundAdapter();
        RestCourseReadModelAdapter adapter = bound.adapter();
        MockRestServiceServer server = bound.server();
        server.expect(requestTo("http://core:8083/api/v1/courses?page=1&size=100"))
                .andRespond(withSuccess(
                        "[{\"courseId\": 3, \"title\": \"A\", \"price\": 10.00},"
                                + "{\"courseId\": 5, \"title\": \"B\", \"price\": 20.00}]",
                        MediaType.APPLICATION_JSON));

        try (var ignoredTenant = new TenantContextGuard("t-1")) {
            List<CourseReadModel.Course> courses = adapter.findCourses(List.of("5", "3"));

            assertThat(courses).hasSize(2);
            // caller-defined ordering (5 first) — the DataLoader contract
            assertThat(courses.get(0).id()).isEqualTo("5");
            assertThat(courses.get(0).title()).isEqualTo("B");
            assertThat(courses.get(1).id()).isEqualTo("3");
        }
    }

    @Test
    @DisplayName("downstream 404 degrades to GraphQL absent — same as REST 404")
    void restNotFoundAgreesWithGraphQlAbsent() {
        Bound bound = boundAdapter();
        RestCourseReadModelAdapter adapter = bound.adapter();
        MockRestServiceServer server = bound.server();
        server.expect(requestTo("http://core:8083/api/v1/courses/404"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(adapter.findCourse("404")).isEmpty();
    }

    @Test
    @DisplayName("schema-projection guard: every schema Course field maps to a downstream source field")
    void schemaFieldsAllHaveDownstreamSources() throws Exception {
        // Parse the non-ID field names out of schema.graphqls' Course type —
        // the schema is the GraphQL contract; if a field is added there, this
        // test fails until the adapter (and this guard) grows the mapping.
        String schema = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Path.of("src/main/resources/graphql/schema.graphqls")),
                StandardCharsets.UTF_8);
        String courseBlock = schema.substring(schema.indexOf("type Course {"),
                schema.indexOf("type Query {"));
        List<String> schemaFields = courseBlock.lines()
                .map(String::trim)
                .filter(l -> l.contains(":") && !l.startsWith("#") && !l.startsWith("type"))
                .map(l -> l.split(":")[0].trim())
                .toList();

        // Downstream projection record fields (the REST-side source of truth)
        List<String> downstreamFields = java.util.Arrays.stream(
                        RestCourseReadModelAdapter.DownstreamCourse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        // id is the documented rename (courseId → id); every other schema
        // field must exist verbatim downstream.
        for (String field : schemaFields) {
            if (field.equals("id")) {
                assertThat(downstreamFields).contains("courseId");
            } else {
                assertThat(downstreamFields)
                        .as("schema field '%s' has no downstream source — the GraphQL "
                                + "contract has drifted ahead of the REST projection", field)
                        .contains(field);
            }
        }
    }

    /** AutoCloseable tenant-context guard for the list path. */
    private static final class TenantContextGuard implements AutoCloseable {
        TenantContextGuard(String tenantId) {
            com.ep.web.common.tenant.TenantContext.setTenantId(tenantId);
        }

        @Override
        public void close() {
            com.ep.web.common.tenant.TenantContext.clear();
        }
    }
}
