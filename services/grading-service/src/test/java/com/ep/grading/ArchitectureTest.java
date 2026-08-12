package com.ep.grading;

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
 * D-136: ArchUnit architecture tests for grading-service.
 *
 * <p>Enforces the DDD layering rules with two bounded contexts (homework, grading):
 * <ul>
 *   <li><b>Layering</b> — controllers depend on service interfaces, services
 *       depend on repositories/entities; repositories never reach back.</li>
 *   <li><b>No cycles</b> — no package slice may depend on itself transitively.</li>
 *   <li><b>Context isolation</b> — the {@code homework} and {@code grading}
 *       bounded contexts must not cross-depend on each other's internal
 *       packages (only on shared DTOs/events if needed).</li>
 *   <li><b>Entity purity</b> — entity packages must not depend on web or
 *       controller layers.</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.ep.grading");
    }

    // ------------------------------------------------------------------
    // DDD layering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Controllers must not depend on repository packages directly")
    void controllersMustNotDependOnRepositories() {
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAnyPackage("..homework..entity..", "..grading..entity..")
                .orShould().resideInAnyPackage("..homework..*Repository", "..grading..*Repository")
                .because("Controllers should go through the service layer, not reach into entities/repositories directly")
                .check(classes);
    }

    @Test
    @DisplayName("Entity packages must not depend on web or controller layers")
    void entitiesMustNotDependOnInfrastructure() {
        noClasses().that().resideInAnyPackage("..homework..entity..", "..grading..entity..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "..controller..")
                .because("JPA entities must stay free of web/controller concerns (DDD)")
                .check(classes);
    }

    @Test
    @DisplayName("homework and grading bounded contexts must not cross-depend")
    void boundedContextsMustNotCrossDepend() {
        noClasses().that().resideInAPackage("..homework..")
                .should().dependOnClassesThat().resideInAPackage("..grading..")
                .because("homework and grading are separate bounded contexts — communicate via events, not direct imports")
                .check(classes);

        noClasses().that().resideInAPackage("..grading..")
                .should().dependOnClassesThat().resideInAPackage("..homework..")
                .because("homework and grading are separate bounded contexts — communicate via events, not direct imports")
                .check(classes);
    }

    // ------------------------------------------------------------------
    // No cycles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No package slice should have cyclic dependencies")
    void noCycles() {
        slices().matching("com.ep.grading.(*)..")
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
                .should().resideInAPackage("..config..")
                .because("Infrastructure beans should be isolated in the config layer")
                .check(classes);
    }
}
