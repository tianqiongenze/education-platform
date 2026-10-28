package com.ep.bff.graphql;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * D-graphql: read-model port for course read aggregation at the BFF.
 * <p>
 * Hexagonal port: the GraphQL layer ({@link CourseGraphQlController}) depends
 * only on this interface; the adapter ({@link RestCourseReadModelAdapter})
 * owns the downstream HTTP details. Keeps the resolver trivially stubbable in
 * tests (see {@code GraphQlAggregationTest}) — the same test seam style the
 * warm-up clients use.
 * <p>
 * Read-only by design: the GraphQL surface exposes course queries only;
 * mutations keep flowing through the existing D-106 proxy / Dubbo paths.
 */
public interface CourseReadModel {

    /**
     * Name under which the course DataLoader is registered with the
     * Spring GraphQL {@code BatchLoaderRegistry} and looked up via
     * {@code DataFetchingEnvironment#getDataLoader(String)}. Matches the
     * GraphQL type name so batch loading follows the Spring for GraphQL
     * convention (loader named after the entity type).
     */
    String COURSE_BATCH_LOADER = "Course";

    /** GraphQL-facing course read model (schema type {@code Course}). */
    record Course(String id, String title, double price) {
    }

    /**
     * Fetch a single course by id; empty when the downstream service does not
     * know the id (surfaced as GraphQL {@code null} rather than an error).
     */
    Optional<Course> findCourse(String id);

    /**
     * Ids of the courses visible to the given tenant. Tenant scoping at the
     * BFF follows the platform convention of propagating the tenant context
     * downstream ({@code X-Tenant-Id}); the downstream core-business service
     * exposes no tenant-scoped endpoint, so this is the paged list fetch
     * scoped by that header (first-page bounded — see adapter javadoc).
     */
    List<String> findCourseIdsByTenant(String tenantId);

    /**
     * Batch fetch courses by ids. MUST issue a bounded number of downstream
     * calls independent of {@code ids.size()} — this backs the DataLoader
     * registration in {@code com.ep.bff.config.GraphQlConfig} and is the
     * N+1-safe backbone of the {@code coursesByTenant} list path.
     */
    List<Course> findCourses(Collection<String> ids);
}
