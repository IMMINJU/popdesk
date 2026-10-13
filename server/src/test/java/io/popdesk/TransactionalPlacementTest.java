package io.popdesk;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;

/**
 * {@code @Transactional} is not put on a controller or a repository (ADR D-57): a transaction
 * starts in a service. Spring's and Jakarta's annotation both count, on the class or on a method.
 *
 * <p>Not seen here: a transaction started in code ({@code TransactionTemplate}, {@code Transactions}).
 */
class TransactionalPlacementTest {

    private static final Set<String> TRANSACTIONAL = Set.of(
            "org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.popdesk");

    @Test
    void noTransactionalOnAControllerOrRepository() {
        Set<String> checked = new TreeSet<>();
        Set<String> found = new TreeSet<>();
        for (JavaClass type : PRODUCTION) {
            if (!isControllerOrRepository(type)) {
                continue;
            }
            checked.add(type.getName());
            if (TRANSACTIONAL.stream().anyMatch(type::isAnnotatedWith)) {
                found.add(type.getName());
            }
            for (JavaMethod method : type.getMethods()) {
                if (TRANSACTIONAL.stream().anyMatch(method::isAnnotatedWith)) {
                    found.add(type.getName() + "#" + method.getName());
                }
            }
        }

        assertThat(checked).as("production controllers and repositories").isNotEmpty();
        assertThat(found).as("@Transactional on a controller or repository; start the transaction in a service")
                .isEmpty();
    }

    private static boolean isControllerOrRepository(JavaClass type) {
        return type.isAnnotatedWith(Controller.class) || type.isMetaAnnotatedWith(Controller.class)
                || type.isAnnotatedWith(Repository.class) || type.isMetaAnnotatedWith(Repository.class)
                || type.getSimpleName().endsWith("Repository")
                || type.isAssignableTo("org.springframework.data.repository.Repository");
    }
}
