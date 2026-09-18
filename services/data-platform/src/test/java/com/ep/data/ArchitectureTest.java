package com.ep.data;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * D-136: ArchUnit architecture tests for data-platform.
 *
 * <p>Enforces the DDD layering rules that keep this service maintainable.
 * data-platform uses a flat-package convention: each business package
 * (aiops, cdc, drift, feature, ingest, lineage, metadata, nlquery, pipeline,
 * profile) co-locates its controllers, services, and domain models. The rules
 * therefore target the cross-cutting invariants that hold across that layout:
 * <ul>
 *   <li><b>Controller location</b> — {@code @RestController} classes must live
 *       in a known business package, never in {@code cdc}, {@code drift},
 *       {@code pipeline} (pure data/domain packages) or in {@code config}/
 *       {@code common} (infrastructure packages).</li>
 *   <li><b>Domain isolation</b> — the pure data/domain packages ({@code cdc},
 *       {@code drift}, {@code pipeline}) must not import Spring web, Pulsar,
 *       or servlet infrastructure so they stay free of transport concerns.</li>
 *   <li><b>Repository isolation</b> — repository-style classes must not reach
 *       back up to controller classes.</li>
 *   <li><b>No cycles</b> — no package slice may depend on itself transitively.</li>
 *   <li><b>Config containment</b> — {@code @Configuration} classes must live
 *       in the {@code config} or {@code common} infrastructure packages.</li>
 * </ul>
 *
 * <p>These rules run as plain JUnit 5 tests (no Spring context needed) so they
 * execute in milliseconds during the fast unit-test phase.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.ep.data");
    }

    // ------------------------------------------------------------------
    // DDD layering: controllers belong to the presentation layer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Controllers should reside in business packages that expose REST endpoints")
    void controllersShouldBeInBusinessPackages() {
        // data-platform uses a flat-package convention: controllers co-locate
        // with their domain in packages like aiops, ingest, lineage, metadata,
        // nlquery, profile. They must NOT appear in pure data/domain packages
        // (cdc, drift, pipeline) or infrastructure packages (config, common).
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAnyPackage(
                        "..aiops..", "..ingest..", "..lineage..",
                        "..metadata..", "..nlquery..", "..profile..")
                .because("controllers belong to the presentation layer of a business package")
                .check(classes);
    }

    @Test
    @DisplayName("Controllers must not reside in pure data/domain or infrastructure packages")
    void controllersMustNotBeInDataOrInfrastructurePackages() {
        noClasses().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAnyPackage("..cdc..", "..drift..", "..pipeline..",
                        "..config..", "..common..")
                .because("cdc/drift/pipeline are pure data/domain packages; "
                        + "config/common are infrastructure packages — neither holds controllers")
                .check(classes);
    }

    // ------------------------------------------------------------------
    // Domain isolation — pure data/domain packages must stay infrastructure-free
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Pure domain packages (cdc, drift, pipeline) must not depend on web/Pulsar/servlet infrastructure")
    void domainPackagesMustNotDependOnInfrastructure() {
        // cdc, drift, and pipeline hold CDC envelopes, drift-detection models,
        // and pipeline configuration builders — pure data/value objects with no
        // transport concerns. They must not import Spring web, Pulsar client,
        // or servlet APIs.
        noClasses().that().resideInAnyPackage("..cdc..", "..drift..", "..pipeline..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.apache.pulsar..",
                        "jakarta.servlet..")
                .because("Pure data/domain packages must stay free of infrastructure concerns (DDD)")
                .check(classes);
    }

    @Test
    @DisplayName("Repository-style classes must not depend on controllers")
    void repositoriesMustNotDependOnControllers() {
        // data-platform currently has no *Repository classes (it uses direct
        // JDBC/MyBatis-style access). allowEmptyShould(true) keeps this guard
        // meaningful when repositories are introduced without failing today.
        noClasses().that().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .because("Repository layer must not reach back to the presentation layer")
                .allowEmptyShould(true)
                .check(classes);
    }

    @Test
    @DisplayName("Domain models must not depend on controllers")
    void domainMustNotDependOnControllers() {
        // Pure data/domain packages must not reference controller classes.
        noClasses().that().resideInAnyPackage("..cdc..", "..drift..", "..pipeline..")
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Controller")
                .because("Domain models must not reach back to the presentation layer")
                .check(classes);
    }

    // ------------------------------------------------------------------
    // No cycles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No package slice should have cyclic dependencies")
    void noCycles() {
        slices().matching("com.ep.data.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }

    // ------------------------------------------------------------------
    // Config containment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@Configuration classes should reside in the config or common package")
    void configurationClassesShouldResideInConfig() {
        // data-platform co-locates DualDbConfig in the cockroachdb package
        // alongside the RoutingDataSource it configures. The accepted homes
        // for @Configuration are the dedicated config/common infrastructure
        // packages plus the cockroachdb package that owns the dual-datasource
        // beans. (NlQueryConfig/TrinoConfig are records, not @Configuration.)
        classes().that().areAnnotatedWith("org.springframework.context.annotation.Configuration")
                .should().resideInAnyPackage("..config..", "..common..", "..cockroachdb..")
                .because("Infrastructure beans should be isolated in config, common, or the "
                        + "feature-specific package that owns the datasource/bean they configure")
                .check(classes);
    }
}
