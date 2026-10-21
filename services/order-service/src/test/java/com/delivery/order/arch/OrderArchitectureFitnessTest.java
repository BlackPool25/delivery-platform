package com.delivery.order.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Phase 6 ArchUnit: Order Service Architectural Fitness Tests")
public class OrderArchitectureFitnessTest {

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.delivery.order");

    @Test
    @DisplayName("Architectural Fitness: Controllers must NEVER bypass Service layer to access Repositories")
    void controllersMustNotAccessRepositoriesDirectly() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..controller..")
                .should().dependOnClassesThat()
                .resideInAPackage("..repository..")
                .because("Controllers must encapsulate all business logic and state mutations through the Service layer");

        rule.check(classes);
    }

    @Test
    @DisplayName("Architectural Fitness: Services must not depend on Presentation/Web Controllers")
    void servicesMustNotDependOnControllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat()
                .resideInAPackage("..controller..")
                .because("Domain and application services must remain decoupled from HTTP/Web presentation concerns");

        rule.check(classes);
    }

    @Test
    @DisplayName("Architectural Fitness: Repositories must NOT use forbidden session-state SQL (SET LOCAL, pg_advisory_lock, LISTEN/NOTIFY)")
    void repositoriesMustNotUseForbiddenSessionStateSql() {
        List<String> forbiddenTokens = List.of(
                "SET LOCAL",
                "pg_advisory_lock",
                "pg_try_advisory_lock",
                "LISTEN",
                "NOTIFY"
        );

        classes.stream()
                .filter(clazz -> clazz.getPackageName().contains(".repository"))
                .flatMap(clazz -> clazz.getMethods().stream())
                .forEach(method -> {
                    if (method.isAnnotatedWith(Query.class)) {
                        Query query = method.getAnnotationOfType(Query.class);
                        String sqlValue = query.value().toUpperCase();

                        for (String token : forbiddenTokens) {
                            assertThat(sqlValue)
                                    .as("Repository method %s contains forbidden session-state command '%s' which corrupts PgBouncer transaction pooling",
                                            method.getFullName(), token)
                                    .doesNotContain(token.toUpperCase());
                        }
                    }
                });
    }

    @Test
    @DisplayName("Architectural Fitness: Controllers must be annotated with @RestController")
    void controllersMustBeAnnotatedWithRestController() {
        ArchRule rule = classes()
                .that().resideInAPackage("..controller..")
                .and().haveSimpleNameEndingWith("Controller")
                .should().beAnnotatedWith(RestController.class)
                .because("REST API controllers must be consistently annotated with @RestController");

        rule.check(classes);
    }
}
