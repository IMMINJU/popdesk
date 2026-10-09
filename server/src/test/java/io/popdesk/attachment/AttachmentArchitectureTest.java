package io.popdesk.attachment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.CompositeArchRule;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rules on the production classes (S0-AC-56, architecture.md "첨부 저장"): file content never goes
 * through a JPA entity, and JDBC code streams it instead of handing over a {@code byte[]}.
 *
 * <p>The classes are read by package name from the compiled production code; this test imports no
 * production class. A rule whose classes are all missing fails (ArchUnit's
 * {@code failOnEmptyShould}), so it fails until there are entities and JDBC code to check.
 */
class AttachmentArchitectureTest {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.popdesk");

    @Test
    @DisplayName("S0-AC-56 no JPA entity has a byte[] field, and nothing is annotated @Lob")
    void noFileContentInEntities() {
        CompositeArchRule.of(noFields().that().areDeclaredInClassesThat().areAnnotatedWith(Entity.class)
                        .should().haveRawType(byte[].class)
                        .orShould().haveRawType(Byte[].class)
                        .because("file content lives in attachment_content and is streamed, not loaded into an entity"))
                .and(noFields().that().areDeclaredInClassesThat().resideInAPackage("io.popdesk..")
                        .should().beAnnotatedWith(Lob.class))
                .and(noMethods().that().areDeclaredInClassesThat().resideInAPackage("io.popdesk..")
                        .should().beAnnotatedWith(Lob.class))
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("S0-AC-56 code that uses a JDBC PreparedStatement never calls setBytes")
    void jdbcCodeDoesNotCallSetBytes() {
        noClasses().that(callMethodsOn(PreparedStatement.class))
                .should().callMethodWhere(setBytes())
                .because("attachment content is written with setBinaryStream(in, length)")
                .check(PRODUCTION);
    }

    private static DescribedPredicate<JavaClass> callMethodsOn(Class<?> type) {
        return DescribedPredicate.describe("call methods of " + type.getSimpleName(),
                c -> c.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().isAssignableTo(type)));
    }

    private static DescribedPredicate<JavaMethodCall> setBytes() {
        return DescribedPredicate.describe("PreparedStatement.setBytes",
                call -> call.getName().equals("setBytes")
                        && call.getTargetOwner().isAssignableTo(PreparedStatement.class));
    }
}
