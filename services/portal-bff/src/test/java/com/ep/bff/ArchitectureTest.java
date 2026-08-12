package com.ep.bff;

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
 * D-136: ArchUnit architecture tests for portal-bff.
 *
 * <p>Enforces the BFF (Backend-for-Frontend) layering rules:
 * <ul>
 *   <li><b>Proxy isolation</b> — the proxy package handles HTTP proxying and
 *       must not depend on auth/cellrouter internals beyond their public APIs.</li>
 *   <li><b>Auth isolation</b> — auth classes (JWT, JWKS) must not depend on
 *       proxy or cellrouter packages.</li>
 *   <li><b>CellRouter self-containment</b> — cell routing logic stays in the
 *       cellrouter package; proxy/filter can use it but not implement it.</li>
 *   <li><b>No cycles</b> — no package slice may depend on itself transitively.</li>
 *   <li><b>Config containment</b> — only the {@code config} package may hold
 *       {@code @Configuration} beans.</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
                .importPackages("com.ep.bff");
    }

    // ------------------------------------------------------------------
    // Layer isolation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Auth package must not depend on proxy or cellrouter internals")
    void authMustNotDependOnProxyOrCellRouter() {
        noClasses().that().resideInAPackage("..auth..")
                .should().dependOnClassesThat().resideInAnyPackage("..proxy..", "..cellrouter..", "..dubbo..")
                .because("JWT/auth logic should be independent of proxying and routing concerns")
                .check(classes);
    }

    @Test
    @DisplayName("Proxy package must not implement cell routing logic")
    void proxyMustNotImplementCellRouterLogic() {
        noClasses().that().resideInAPackage("..proxy..")
                .should().haveSimpleNameEndingWith("CellRouter")
                .orShould().haveSimpleNameEndingWith("RouteContext")
                .orShould().haveSimpleNameEndingWith("WriteHoldRegistry")
                .because("Cell routing logic belongs in the cellrouter package, not the proxy package")
                .check(classes);
    }

    @Test
    @DisplayName("Dubbo controllers should reside in the dubbo package")
    void dubboControllersShouldResideInDubboPackage() {
        classes().that().haveSimpleNameEndingWith("DubboController")
                .should().resideInAPackage("..dubbo..")
                .because("Dubbo RPC bridge controllers belong in the dubbo package")
                .check(classes);
    }

    // ------------------------------------------------------------------
    // No cycles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("No package slice should have cyclic dependencies")
    void noCycles() {
        slices().matching("com.ep.bff.(*)..")
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
