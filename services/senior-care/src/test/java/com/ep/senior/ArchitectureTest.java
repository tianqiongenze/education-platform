package com.ep.senior;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * D-148: ArchUnit architectural guardrails for senior-care.
 * <p>
 * Enforces DDD layering rules adapted to the flat-package convention used
 * across the platform (domain entities, services, controllers, and repositories
 * co-locate in a single business package — same as core-business). The rules
 * target only the JPA entity / aggregate-root classes, which must remain free
 * of web, Pulsar, and servlet infrastructure. Controllers and services are
 * explicitly excluded from the domain-isolation rules.
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.ep.senior");
    }

    // ------------------------------------------------------------------
    // Domain isolation — only @Entity aggregate roots must be infrastructure-free
    // ------------------------------------------------------------------

    @Test
    @DisplayName("JPA entity classes must not depend on web/Pulsar/servlet infrastructure")
    void entitiesMustNotDependOnInfrastructure() {
        noClasses().that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.apache.pulsar..",
                        "jakarta.servlet..")
                .because("JPA entity / aggregate roots must stay free of infrastructure concerns (DDD)")
                .check(classes);
    }

    @Test
    @DisplayName("JPA entity classes must not import the event publishing package")
    void entitiesMustNotDependOnEventInfra() {
        noClasses().that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().dependOnClassesThat().resideInAPackage("..event..")
                .because("Domain events should be published by services, not entity aggregate roots")
                .check(classes);
    }

    @Test
    @DisplayName("JPA entity classes must not depend on controllers")
    void entitiesMustNotDependOnControllers() {
        noClasses().that().areAnnotatedWith("jakarta.persistence.Entity")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..")
                .because("Entity aggregate roots must not reach back to the presentation layer")
                .check(classes);
    }

    // ------------------------------------------------------------------
    // No cycles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No package slice should have cyclic dependencies")
    void noCycles() {
        slices().matching("com.ep.senior.(*)..")
                .should().beFreeOfCycles()
                .check(classes);
    }

    // ------------------------------------------------------------------
    // Config containment
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@Configuration classes should reside in the config or event package")
    void configurationClassesShouldResideInConfig() {
        classes().that().areAnnotatedWith("org.springframework.context.annotation.Configuration")
                .should().resideInAnyPackage("..config..", "..event..")
                .because("Infrastructure beans should be isolated in the config layer")
                .check(classes);
    }
}
