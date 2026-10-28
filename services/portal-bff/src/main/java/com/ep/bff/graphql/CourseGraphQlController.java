package com.ep.bff.graphql;

import graphql.schema.DataFetchingEnvironment;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * D-graphql: read-only GraphQL resolvers for the course read model.
 * <p>
 * {@code course(id)} — single read through the port.
 * <p>
 * {@code coursesByTenant(tenantId)} — resolves the tenant's course ids, then
 * hands every id to the {@code Course} DataLoader (registered in
 * {@code com.ep.bff.config.GraphQlConfig} via {@code BatchLoaderRegistry}),
 * so the whole list is served by ONE batched port call — the Spring for
 * GraphQL documented N+1-safe pattern.
 * <p>
 * Nullability: {@code course} resolves to {@code null} for unknown ids
 * (schema allows it via a resolver error per field); the list path returns an
 * empty list for tenants with no courses or a degraded downstream read.
 */
@Controller
public class CourseGraphQlController {

    private final CourseReadModel courseReadModel;

    public CourseGraphQlController(CourseReadModel courseReadModel) {
        this.courseReadModel = courseReadModel;
    }

    @QueryMapping
    public CourseReadModel.Course course(@Argument String id) {
        return courseReadModel.findCourse(id).orElse(null);
    }

    @QueryMapping
    public CompletableFuture<List<CourseReadModel.Course>> coursesByTenant(
            @Argument String tenantId, DataFetchingEnvironment environment) {
        List<String> ids = courseReadModel.findCourseIdsByTenant(tenantId);
        if (ids.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        DataLoader<String, CourseReadModel.Course> loader =
            environment.getDataLoader(CourseReadModel.COURSE_BATCH_LOADER);
        if (loader == null) {
            // Defensive (CLC): the loader is registered by GraphQlConfig, but a
            // resolver must not NPE if wiring ever changes — fall back to a
            // single direct batch fetch instead.
            return CompletableFuture.completedFuture(courseReadModel.findCourses(ids));
        }
        List<CompletableFuture<CourseReadModel.Course>> futures =
            ids.stream().map(loader::load).toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
            .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }
}
