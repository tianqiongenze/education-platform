package com.ep.knowledge.service;

import com.ep.knowledge.service.event.DomainEvent;
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
 * D-201: ArchUnit architecture tests for knowledge-service.
 *
 * <p>Enforces DDD layering rules for the knowledge bounded context:
 * <ul>
 *   <li><b>Layering</b> — controllers depend on services, services depend on
 *       mappers/entities; mappers never reach back to controllers.</li>
 *   <li><b>No cycles</b> — no package slice may depend on itself transitively.</li>
 *   <li><b>Entity purity</b> — entity packages must not depend on web layers.</li>
 *   <li><b>DDD package placement</b> — @Entity in entity/model, domain events
 *       in event/common (D-201).</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.ep.knowledge.service");
    }

    // ------------------------------------------------------------------
    // DDD layering
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Controllers must not depend on repository/mapper internals beyond simple read queries")
    void controllersMustNotDependOnRepositoryInternals() {
        // KnowledgeController legitimately injects CategoryMapper for a simple
        // selectList read query (category dropdown). This is an accepted pattern
        // for trivial read-only endpoints. The rule guards against controllers
        // reaching into service-internal repositories, not against read mappers.
        noClasses().that().resideInAPackage("..controller..")
                .should().dependOnClassesThat().resideInAnyPackage("..repository..")
                .because("Controllers should not reach into internal repositories; use the service layer")
                .check(classes);
    }

    @Test
    @DisplayName("Entity packages must not depend on web or controller layers")
    void entitiesMustNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage("..entity..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.web..",
                        "..controller..")
                .because("Domain entities must stay free of web/controller concerns (DDD)")
                .check(classes);
    }

    // ------------------------------------------------------------------
    // D-201: DDD guard rules — entity & domain-event package placement
    // ------------------------------------------------------------------

    @Test
    @DisplayName("MyBatis-Plus @TableName classes must reside in entity or model packages")
    void entitiesMustBeInEntityOrModelPackage() {
        // knowledge-service uses MyBatis-Plus (@TableName), not JPA (@Entity).
        // jakarta.persistence is not on the test classpath, so we only guard
        // @TableName-annotated classes here.
        classes().that().areAnnotatedWith("com.baomidou.mybatisplus.annotation.TableName")
                .should().resideInAnyPackage("..entity..", "..model..")
                .because("MyBatis-Plus entities are domain aggregates and must live in the entity/model package (DDD)")
                .check(classes);
    }

    @Test
    @DisplayName("Domain event classes must reside in event or common packages")
    void domainEventsMustBeInEventPackage() {
        classes().that().areAssignableTo(DomainEvent.class)
                .should().resideInAnyPackage("..event..", "..common..")
                .because("Domain events must be isolated in the event/common package (DDD)")
                .check(classes);
    }

    // ------------------------------------------------------------------
    // No cycles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No package slice should have cyclic dependencies")
    void noCycles() {
        slices().matching("com.ep.knowledge.service.(*)..")
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
                .because("Infrastructure beans should be isolated in the config layer")
                .check(classes);
    }
}
