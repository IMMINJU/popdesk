package io.popdesk.ops;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Module rules for reading customer data (S0 premortem 3 and 10): a read without the viewer's
 * scope happens only in the system-job package, and only the scoped attachment service reads file
 * contents from the attachment storage.
 *
 * <p>The rules name packages and classes as text and import nothing from {@code server/src/main}.
 * The names are what the ops tab proposes (its report); the implementation puts its classes there:
 *
 * <ul>
 *   <li>{@value #SYSTEM}: system jobs (cleanup, the send loops' checks) and every repository method
 *       that reads by ID without a viewer's scope. Only this package calls them.
 *   <li>{@value #ACCOUNT}: accounts, sign-in, and sessions. Reading the caller's own account by ID
 *       is not reading customer data in another person's scope, so this package is left out of the
 *       by-ID rule.
 *   <li>{@value #STORAGE}: the attachment storage (file contents). Its {@code read*} and {@code open*}
 *       methods are called only from {@value #ATTACHMENT_SERVICE}.
 * </ul>
 *
 * Each rule first checks that the classes it is about exist, so it cannot pass on an empty package.
 */
class ScopedReadRulesTest {

    static final String ROOT = "io.popdesk";

    static final String SYSTEM = "io.popdesk.helpdesk.system";

    static final String ACCOUNT = "io.popdesk.helpdesk.account";

    static final String STORAGE = "io.popdesk.helpdesk.attachment.storage";

    static final String ATTACHMENT_SERVICE = "io.popdesk.helpdesk.attachment.AttachmentService";

    /** Spring Data methods that read by ID or read everything, without a scope. */
    static final Set<String> UNSCOPED_READS = Set.of("findById", "findAllById", "getById", "getReferenceById",
            "getOne", "existsById", "findAll");

    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages(ROOT);

    private static boolean inPackage(JavaClass c, String pkg) {
        return c.getPackageName().equals(pkg) || c.getPackageName().startsWith(pkg + ".");
    }

    private static List<JavaClass> classesIn(String pkg) {
        return PRODUCTION.stream().filter(c -> inPackage(c, pkg)).toList();
    }

    private static final DescribedPredicate<JavaCall<?>> UNSCOPED_REPOSITORY_READ =
            DescribedPredicate.describe("an unscoped Spring Data read " + UNSCOPED_READS, call ->
                    UNSCOPED_READS.contains(call.getName())
                            && call.getTargetOwner().isAssignableTo("org.springframework.data.repository.Repository"));

    private static final DescribedPredicate<JavaCall<?>> STORAGE_READ =
            DescribedPredicate.describe("a read* or open* method of " + STORAGE, call ->
                    inPackage(call.getTargetOwner(), STORAGE)
                            && (call.getName().startsWith("read") || call.getName().startsWith("open")));

    @Test
    @DisplayName("S0-AC-84 only the system-job package reads repositories by ID or in full, without a scope")
    void unscopedReadsOnlyInSystemPackage() {
        assertThat(classesIn(SYSTEM)).as("classes in %s", SYSTEM).isNotEmpty();

        noClasses()
                .that().resideInAPackage(ROOT + "..")
                .and().resideOutsideOfPackages(SYSTEM + "..", ACCOUNT + "..")
                .should().callCodeUnitWhere(UNSCOPED_REPOSITORY_READ)
                .because("customer data is read with the viewer's scope; reads by ID alone belong to system jobs")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("S0-AC-84 customer and staff code does not call the system-job package")
    void systemPackageOnlyCalledFromItself() {
        assertThat(classesIn(SYSTEM)).as("classes in %s", SYSTEM).isNotEmpty();

        classes()
                .that().resideInAPackage(SYSTEM + "..")
                .should().onlyHaveDependentClassesThat().resideInAPackage(SYSTEM + "..")
                .because("services for customers and agents must not reach the unscoped readers")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("S0-AC-84 file contents are read from the attachment storage only by the scoped attachment service")
    void storageReadOnlyFromAttachmentService() {
        assertThat(classesIn(STORAGE)).as("classes in %s", STORAGE).isNotEmpty();
        List<JavaClass> service = PRODUCTION.stream()
                .filter(c -> c.getFullName().equals(ATTACHMENT_SERVICE)).toList();
        assertThat(service).as("class %s", ATTACHMENT_SERVICE).hasSize(1);
        assertThat(service.getFirst().getMethodCallsFromSelf().stream().anyMatch(STORAGE_READ::test))
                .as("%s reads file contents from %s", ATTACHMENT_SERVICE, STORAGE).isTrue();

        noClasses()
                .that().resideOutsideOfPackage(STORAGE + "..")
                .and().doNotHaveFullyQualifiedName(ATTACHMENT_SERVICE)
                .should().callCodeUnitWhere(STORAGE_READ)
                .because("only the attachment service checks the viewer's scope before reading a file")
                .check(PRODUCTION);
    }
}
