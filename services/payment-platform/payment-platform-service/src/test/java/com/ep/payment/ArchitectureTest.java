package com.ep.payment;

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
 * D-136: ArchUnit architecture tests for payment-platform.
 *
 * <p>Enforces the DDD layering rules (controller -> service -> repository/entity)
 * with payment channel strategy classes isolated in their own package:
 * <ul>
 *   <li><b>Layering</b> — controllers depend on services, services depend on
 *       repositories/entities; repositories never reach back to controllers.</li>
 *   <li><b>No cycles</b> — no package slice may depend on itself transitively.</li>
 *   <li><b>Entity purity</b> — the {@code entity} package must not depend on
 *       Spring web or controller layers.</li>
 *   <li><b>Channel isolation</b> — payment channel implementations stay in the
 *       {@code channel} package.</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.ep.payment");
    }

    // ------------------------------------------------------------------
    // DDD layering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Controllers must not depend on repositories directly")
    void controllersMustNotDependOnRepositories() {
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .because("Controllers should go through the service layer, not reach into repositories directly")
                .check(classes);
    }

    @Test
    @DisplayName("Entity layer must not depend on web or controller layers")
    void entityMustNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage("..entity..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "..controller..",
                        "..service..")
                .because("JPA entities must stay free of web/service concerns (DDD)")
                .check(classes);
    }

    @Test
    @DisplayName("Repositories must not depend on controllers or services")
    void repositoriesMustNotDependOnPresentationOrService() {
        noClasses().that().resideInAPackage("..repository..")
                .should().dependOnClassesThat().resideInAnyPackage("..controller..", "..service..")
                .because("Repository layer must not reach back to presentation or service layers")
                .check(classes);
    }

    @Test
    @DisplayName("Payment channel implementations should reside in the channel package")
    void channelImplsShouldResideInChannelPackage() {
        // The strategy interface is PaymentChannelSpi (in api package); concrete
        // implementations like MockPaymentChannel must live in the channel package.
        // We do NOT use simple-name matching because the enum PaymentChannel
        // legitimately lives in the api package and would otherwise trip the rule.
        classes().that().implement(com.ep.payment.api.PaymentChannelSpi.class)
                .should().resideInAPackage("..channel..")
                .because("Payment channel strategy implementations belong in the channel package")
                .allowEmptyShould(true)
                .check(classes);
    }

    // ------------------------------------------------------------------
    // No cycles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No package slice should have cyclic dependencies")
    void noCycles() {
        slices().matching("com.ep.payment.(*)..")
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
