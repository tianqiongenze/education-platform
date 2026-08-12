package com.ep.order;

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
 * D-136: ArchUnit architecture tests for order-platform.
 *
 * <p>Enforces the DDD layering rules (controller -> service -> repository/model)
 * with the saga compensation infrastructure isolated in its own package:
 * <ul>
 *   <li><b>Layering</b> — controllers depend on services, services depend on
 *       repositories/model; repositories never reach back to controllers.</li>
 *   <li><b>No cycles</b> — no package slice may depend on itself transitively.</li>
 *   <li><b>Saga isolation</b> — saga action classes stay in the {@code saga}
 *       package and the model layer never imports saga internals.</li>
 *   <li><b>Domain purity</b> — the {@code model} package must not depend on
 *       Spring web or Pulsar/JPA infrastructure.</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.ep.order");
    }

    // ------------------------------------------------------------------
    // DDD layering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Controllers must only depend on service layer, not on repositories")
    void controllersMustNotDependOnRepositories() {
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .because("Controllers should go through the service layer, not reach into repositories directly")
                .check(classes);
    }

    @Test
    @DisplayName("Model layer must not depend on infrastructure (web/Pulsar/saga)")
    void modelMustNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage("..model..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "org.apache.pulsar..",
                        "..saga..",
                        "..controller..")
                .because("Domain models must stay free of infrastructure concerns (DDD)")
                .check(classes);
    }

    @Test
    @DisplayName("Saga action classes should reside in the saga package")
    void sagaActionsShouldResideInSagaPackage() {
        classes().that().haveSimpleNameEndingWith("CompensableAction")
                .should().resideInAPackage("..saga..")
                .because("Saga compensation actions belong to the saga orchestration layer")
                .check(classes);
    }

    @Test
    @DisplayName("Repositories must not depend on controllers or saga")
    void repositoriesMustNotDependOnPresentationOrSaga() {
        noClasses().that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..saga..")
                .because("Repository layer must not reach back to presentation or saga orchestration")
                .check(classes);
    }

    // ------------------------------------------------------------------
    // No cycles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No package slice should have cyclic dependencies")
    void noCycles() {
        slices().matching("com.ep.order.(*)..")
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
