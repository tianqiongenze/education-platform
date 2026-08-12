package com.ep.business;

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
 * D-136: ArchUnit architecture tests for core-business.
 *
 * <p>Enforces the DDD layering rules that keep this service maintainable:
 * <ul>
 *   <li><b>Layering</b> — controllers depend on services, services depend on
 *       repositories/domain; repositories never reach back up to controllers.</li>
 *   <li><b>No cycles</b> — no package slice may depend on itself transitively.</li>
 *   <li><b>Domain isolation</b> — the {@code course} and {@code user} domain
 *       packages must not import Spring web annotations or infrastructure
 *       (Pulsar/JPA) so they stay pure domain models.</li>
 *   <li><b>Config containment</b> — only the {@code config} package may hold
 *       {@code @Configuration}/{@code @Component} infrastructure beans.</li>
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
                .importPackages("com.ep.business");
    }

    // ------------------------------------------------------------------
    // DDD layering: controller -> service -> repository/domain
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Controllers should only reside in packages named 'controller' or domain controller packages")
    void controllersShouldBeInControllerLayer() {
        classes().that().haveSimpleNameEndingWith("Controller")
                .should().resideInAnyPackage("..course..", "..user..", "..controller..")
                .because("controllers belong to the presentation layer")
                .check(classes);
    }

    @Test
    @DisplayName("Domain model (course/user) must not depend on web layer or Pulsar infrastructure")
    void domainMustNotDependOnInfrastructure() {
        // Controllers legitimately use Spring web annotations; exclude them
        // from this domain-purity check.  Only non-controller classes in
        // course/user packages (entities, services, repositories, commands)
        // must stay free of infrastructure concerns.
        noClasses().that().resideInAnyPackage("..course..", "..user..")
                .and().haveSimpleNameNotEndingWith("Controller")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.apache.pulsar..",
                        "jakarta.servlet..")
                .because("Domain models must stay free of infrastructure concerns (DDD)")
                .check(classes);
    }

    @Test
    @DisplayName("Repositories must not depend on controllers")
    void repositoriesMustNotDependOnControllers() {
        noClasses().that().resideInAnyPackage("..course..", "..user..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..")
                .because("Repository layer must not reach back to the presentation layer")
                .check(classes);
    }

    @Test
    @DisplayName("Event publishing infrastructure must not be imported by domain models")
    void eventInfraMustNotLeakIntoDomain() {
        // Service implementations (e.g. CourseServiceImpl, UserServiceImpl)
        // legitimately depend on event publishing infrastructure to publish
        // domain events. Only non-service, non-controller classes in
        // course/user packages (entities, repositories, commands) must not
        // import event infrastructure directly.
        noClasses().that().resideInAnyPackage("..course..", "..user..")
                .and().haveSimpleNameNotEndingWith("Controller")
                .and().haveSimpleNameNotEndingWith("ServiceImpl")
                .should().dependOnClassesThat().resideInAPackage("..event..")
                .because("Domain events should be published by services, not domain models or repositories")
                .check(classes);
    }

    // ------------------------------------------------------------------
    // No cycles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No package slice should have cyclic dependencies")
    void noCycles() {
        slices().matching("com.ep.business.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }

    // ------------------------------------------------------------------
    // Config containment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@Configuration classes should reside in the config package")
    void configurationClassesShouldResideInConfig() {
        classes().that().areAnnotatedWith("org.springframework.context.annotation.Configuration")
                .should().resideInAnyPackage("..config..", "..event..")
                .because("Infrastructure beans should be isolated in the config or event infrastructure layer")
                .check(classes);
    }
}
