package com.ep.bff.graphql;

import com.ep.web.common.tenant.TenantContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * REST adapter for {@link CourseReadModel}: calls the core-business course
 * endpoints (verified: {@code core-business} serves courses on port 8083 via
 * {@code CourseController} — GET {@code /api/v1/courses/{id}} and the paged
 * GET {@code /api/v1/courses}; the same base-url convention the BFF's
 * {@code HttpCourseWarmupClient} already uses).
 * <p>
 * Downstream responses are mapped to the GraphQL-facing {@link CourseReadModel.Course}
 * record ({@code courseId → id}); unknown downstream fields are ignored so the
 * contract stays stable as the domain entity evolves.
 * <p>
 * Read-path error policy: downstream failures are logged and degrade to
 * empty/absent data instead of propagating a 500 — GraphQL resolvers turn a
 * {@code null} into a field error for the affected field only, never a whole
 * request failure. This mirrors the warm-up clients' "optimization, never a
 * correctness path" posture for read aggregation.
 * <p>
 * List-fetch bound: the downstream API is paged (no batch endpoint), so the
 * adapter fetches the FIRST page ({@code size=100}) for both id listing and
 * id-based batch filtering. For tenants with more than 100 courses the batch
 * loader would silently miss tail courses — large-tenant pagination is
 * DEFERRED (follow-up: downstream batch endpoint or keyset paging).
 */
@Component
public class RestCourseReadModelAdapter implements CourseReadModel {

    private static final Logger log = LoggerFactory.getLogger(RestCourseReadModelAdapter.class);

    /** Page size for list-based fetches (see class javadoc for the bound). */
    static final int LIST_PAGE_SIZE = 100;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // D-241: with two constructors present Spring does NOT apply implicit
    // single-constructor autowiring — without @Autowired it falls back to the
    // no-arg path and fails with "No default constructor found" (same family
    // as the r9/r10 multi-constructor bean fixes).
    @org.springframework.beans.factory.annotation.Autowired
    public RestCourseReadModelAdapter(
            @Value("${ep.bff.course-base-url:http://localhost:8083}") String baseUrl,
            ObjectMapper objectMapper) {
        this(RestClient.builder().baseUrl(baseUrl), objectMapper);
    }

    /** Test constructor — inject a builder so MockRestServiceServer can bind. */
    RestCourseReadModelAdapter(RestClient.Builder builder, ObjectMapper objectMapper) {
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<Course> findCourse(String id) {
        try {
            return restClient.get()
                .uri("/api/v1/courses/{id}", id)
                .exchange((req, resp) -> {
                    if (!resp.getStatusCode().is2xxSuccessful()) {
                        return Optional.<Course>empty();
                    }
                    DownstreamCourse dto = objectMapper.readValue(resp.getBody(),
                        DownstreamCourse.class);
                    return Optional.ofNullable(toCourse(dto));
                });
        } catch (Exception e) {
            log.warn("course read failed (degraded to absent): id={} error={}", id, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public List<String> findCourseIdsByTenant(String tenantId) {
        return fetchTenantList(tenantId).stream().map(d -> String.valueOf(d.courseId())).toList();
    }

    @Override
    public List<Course> findCourses(Collection<String> ids) {
        // Batch path: one list call for ANY number of ids (N+1-safe), then
        // filter to the requested keys. Ordering follows the caller's ids.
        List<DownstreamCourse> all = fetchTenantList(TenantContext.getTenantIdOrDefault());
        var byId = new java.util.HashMap<String, DownstreamCourse>();
        for (DownstreamCourse dto : all) {
            byId.put(String.valueOf(dto.courseId()), dto);
        }
        List<Course> out = new ArrayList<>(ids.size());
        for (String id : ids) {
            DownstreamCourse dto = byId.get(id);
            if (dto != null) {
                out.add(toCourse(dto));
            }
        }
        return out;
    }

    private List<DownstreamCourse> fetchTenantList(String tenantId) {
        try {
            return restClient.get()
                .uri(uri -> uri.path("/api/v1/courses")
                    .queryParam("page", 1)
                    .queryParam("size", LIST_PAGE_SIZE)
                    .build())
                .header(com.ep.web.common.tenant.TenantContextFilter.TENANT_ID_HEADER, tenantId)
                .exchange((req, resp) -> {
                    if (!resp.getStatusCode().is2xxSuccessful()) {
                        return List.<DownstreamCourse>of();
                    }
                    return objectMapper.readValue(resp.getBody(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class,
                            DownstreamCourse.class));
                });
        } catch (Exception e) {
            log.warn("course list read failed (degraded to empty): tenant={} error={}",
                tenantId, e.toString());
            return List.of();
        }
    }

    private static Course toCourse(DownstreamCourse dto) {
        if (dto == null || dto.courseId() == null || dto.title() == null || dto.price() == null) {
            return null;
        }
        return new Course(String.valueOf(dto.courseId()), dto.title(), dto.price().doubleValue());
    }

    /** Subset of the core-business {@code Course} entity the read model needs. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record DownstreamCourse(Long courseId, String title, java.math.BigDecimal price) {
    }
}
