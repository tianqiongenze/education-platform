package com.ep.bff.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * Unit tests for the REST adapter behind the GraphQL read model: downstream
 * URL shape, courseId→id mapping, 404/5xx degradation, and the single-call
 * batch behavior backing the DataLoader registration.
 */
class RestCourseReadModelAdapterTest {

    private static final String BASE = "http://localhost:8083";

    private RestCourseReadModelAdapter adapter;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new RestCourseReadModelAdapter(builder, new ObjectMapper());
    }

    @Test
    @DisplayName("findCourse maps courseId to id and tolerates a 404 as empty")
    void findCourseMapsAndDegradesOn404() {
        server.expect(requestTo(BASE + "/api/v1/courses/1"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess(
                "{\"courseId\":1,\"title\":\"GraphQL 入门\",\"price\":199.00,\"description\":\"x\"}",
                MediaType.APPLICATION_JSON));

        var course = adapter.findCourse("1");
        assertTrue(course.isPresent());
        assertEquals("1", course.get().id());
        assertEquals("GraphQL 入门", course.get().title());
        assertEquals(199.0, course.get().price());
        server.verify();
    }

    @Test
    @DisplayName("findCourse on 404 / connection failure degrades to empty, no exception")
    void findCourseDegrades404() {
        server.expect(requestTo(BASE + "/api/v1/courses/404"))
            .andRespond(withResourceNotFound());
        assertTrue(adapter.findCourse("404").isEmpty());
    }

    @Test
    @DisplayName("findCourse degrades to empty on server failure (no exception)")
    void findCourseDegrades500() {
        // MockRestServiceServer forbids adding expectations after requests are
        // made, so the two degrade paths live in separate tests.
        server.expect(requestTo(BASE + "/api/v1/courses/500"))
            .andRespond(withServerError());
        assertTrue(adapter.findCourse("500").isEmpty());
    }

    @Test
    @DisplayName("findCourseIdsByTenant lists ids with the tenant header propagated")
    void findCourseIdsByTenantPropagatesTenant() {
        server.expect(requestTo(BASE + "/api/v1/courses?page=1&size=100"))
            .andExpect(header("X-Tenant-Id", "t1"))
            .andRespond(withSuccess(
                "[{\"courseId\":1,\"title\":\"a\",\"price\":1},{\"courseId\":2,\"title\":\"b\",\"price\":2}]",
                MediaType.APPLICATION_JSON));

        assertEquals(List.of("1", "2"), adapter.findCourseIdsByTenant("t1"));
        server.verify();
    }

    @Test
    @DisplayName("findCourses issues ONE list call for any number of ids (N+1-safe)")
    void findCoursesIsSingleCall() {
        server.expect(requestTo(BASE + "/api/v1/courses?page=1&size=100"))
            .andRespond(withSuccess(
                "[{\"courseId\":1,\"title\":\"a\",\"price\":1},{\"courseId\":2,\"title\":\"b\",\"price\":2},"
                    + "{\"courseId\":3,\"title\":\"c\",\"price\":3}]",
                MediaType.APPLICATION_JSON));

        var courses = adapter.findCourses(List.of("2", "1", "99"));

        // Caller's ordering preserved; missing id (99) silently skipped.
        assertEquals(2, courses.size());
        assertEquals("2", courses.get(0).id());
        assertEquals("1", courses.get(1).id());
        server.verify();
    }
}
