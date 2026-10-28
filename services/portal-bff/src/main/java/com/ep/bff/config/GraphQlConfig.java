package com.ep.bff.config;

import com.ep.bff.graphql.CourseReadModel;
import graphql.analysis.MaxQueryDepthInstrumentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.graphql.autoconfigure.GraphQlSourceBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * D-graphql: GraphQL infrastructure wiring (infrastructure beans belong in
 * the {@code config} package per the D-136 ArchUnit rule).
 * <p>
 * <b>Depth guard:</b> registers graphql-java's {@code MaxQueryDepthInstrumentation}
 * on the auto-configured {@code GraphQlSource} — a query whose field depth
 * exceeds {@code ep.bff.graphql-max-depth} (default 10) is aborted before
 * execution. APIs verified against the offline jars (ASV):
 * {@code GraphQlSource.Builder#instrumentation(List)} (spring-graphql 2.0.4)
 * and {@code MaxQueryDepthInstrumentation(int)} (graphql-java 25.0, the
 * version spring-boot-starter-graphql 4.0.7 manages).
 * <p>
 * <b>Course batch loader:</b> registers the {@code Course} DataLoader so the
 * {@code coursesByTenant} list path resolves N ids with ONE batched port call
 * (Spring for GraphQL batch-loading pattern). Registration happens at context
 * startup; the registry is consulted per-execution by the auto-configured
 * {@code ExecutionGraphQlService}, so creation order with the source bean is
 * irrelevant.
 */
@Configuration
public class GraphQlConfig {

    @Bean
    public GraphQlSourceBuilderCustomizer graphqlMaxQueryDepthCustomizer(
            @Value("${ep.bff.graphql-max-depth:10}") int maxDepth) {
        return builder -> builder.instrumentation(
            List.of(new MaxQueryDepthInstrumentation(maxDepth)));
    }

    public GraphQlConfig(BatchLoaderRegistry batchLoaderRegistry, CourseReadModel courseReadModel) {
        batchLoaderRegistry
            .<String, CourseReadModel.Course>forName(CourseReadModel.COURSE_BATCH_LOADER)
            .registerBatchLoader((ids, environment) ->
                Flux.fromIterable(courseReadModel.findCourses(ids)));
    }
}
