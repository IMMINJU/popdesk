package io.popdesk.ops;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.ArrayList;
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
 *   <li>Customer data lives in {@value #ISSUE}, {@value #COMMENT}, {@value #ATTACHMENT}, and
 *       {@value #COMPANY} (companies and who belongs to them). The rules about scope apply to the
 *       repositories there only (tasks.md section 7): the outbox, alert, and cleanup-record
 *       repositories may read by ID.
 *   <li>A repository is a type that extends Spring Data's {@code Repository}, carries
 *       {@code @Repository}, or has a simple name ending in {@code Repository} or
 *       {@code RepositoryImpl} (a Spring Data fragment). Which it is does not depend on its name alone.
 *   <li>{@value #SYSTEM}: system jobs (cleanup, the send loops' checks) and every repository method
 *       that reads customer data by ID without a viewer's scope. Only this package calls them.
 *   <li>{@value #ACCOUNT}: accounts, sign-in, and sessions. It reads the account repository by ID
 *       like any non-customer repository; it does not read customer-data repositories that way.
 *   <li>{@value #VIEWER}: the viewer's scope. Every public method of a customer-data repository that
 *       is not a write (its name starts with one of {@link #WRITE_PREFIXES}) takes one.
 *   <li>In the {@value #HELPDESK} module, which owns the customer tables, only repositories, the
 *       system package, and the storage package use JPA or JDBC directly ({@link #RAW_ACCESS}).
 *       A service cannot step around the repository rules with an {@code EntityManager}.
 *   <li>{@value #STORAGE}: the attachment storage (file contents). Outside it, only
 *       {@value #ATTACHMENT_SERVICE} and the system package use it, and only
 *       {@value #ATTACHMENT_SERVICE} calls its methods that are not writes or removals
 *       ({@link #STORAGE_WRITE_PREFIXES}).
 * </ul>
 *
 * Each rule first checks that the classes it is about exist, so it cannot pass on an empty package.
 */
class ScopedReadRulesTest {

    static final String ROOT = "io.popdesk";

    static final String HELPDESK = "io.popdesk.helpdesk";

    static final String SYSTEM = "io.popdesk.helpdesk.system";

    static final String ACCOUNT = "io.popdesk.helpdesk.account";

    static final String STORAGE = "io.popdesk.helpdesk.attachment.storage";

    static final String VIEWER = "io.popdesk.helpdesk.Viewer";

    static final String ISSUE = "io.popdesk.helpdesk.issue";

    static final String COMMENT = "io.popdesk.helpdesk.comment";

    static final String ATTACHMENT = "io.popdesk.helpdesk.attachment";

    static final String COMPANY = "io.popdesk.helpdesk.company";

    static final List<String> CUSTOMER_DATA = List.of(ISSUE, COMMENT, ATTACHMENT, COMPANY);

    /** Method names of a repository that write rather than read. Every other public method reads. */
    static final List<String> WRITE_PREFIXES = List.of("save", "delete", "insert", "update", "lock", "remove",
            "persist", "flush");

    static final String ATTACHMENT_SERVICE = "io.popdesk.helpdesk.attachment.AttachmentService";

    /** Method names of the storage that write or remove contents. Every other method reads. */
    static final List<String> STORAGE_WRITE_PREFIXES = List.of("write", "store", "save", "put", "delete", "remove",
            "purge");

    /** Spring Data methods that read by ID, by example, or read everything, without a scope. */
    static final Set<String> UNSCOPED_READS = Set.of("findById", "findAllById", "getById", "getReferenceById",
            "getOne", "existsById", "findAll", "findOne", "findBy", "count", "exists");

    /** Types that read the database without going through a repository. */
    static final Set<String> RAW_ACCESS = Set.of("jakarta.persistence.EntityManager",
            "jakarta.persistence.EntityManagerFactory", "org.hibernate.Session",
            "org.springframework.jdbc.core.JdbcTemplate", "org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations",
            "org.springframework.jdbc.core.simple.JdbcClient", "javax.sql.DataSource", "java.sql.Connection");

    static final String SPRING_DATA_REPOSITORY = "org.springframework.data.repository.Repository";

    static final String REPOSITORY_ANNOTATION = "org.springframework.stereotype.Repository";

    static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages(ROOT);

    private static boolean inPackage(JavaClass c, String pkg) {
        return c.getPackageName().equals(pkg) || c.getPackageName().startsWith(pkg + ".");
    }

    private static boolean inCustomerData(JavaClass c) {
        return CUSTOMER_DATA.stream().anyMatch(p -> inPackage(c, p)) && !inPackage(c, STORAGE)
                && !inPackage(c, SYSTEM);
    }

    private static List<JavaClass> classesIn(String pkg) {
        return PRODUCTION.stream().filter(c -> inPackage(c, pkg)).toList();
    }

    private static boolean isRepository(JavaClass c) {
        return c.isAssignableTo(SPRING_DATA_REPOSITORY) || c.isAnnotatedWith(REPOSITORY_ANNOTATION)
                || c.getSimpleName().endsWith("Repository") || c.getSimpleName().endsWith("RepositoryImpl");
    }

    private static final DescribedPredicate<JavaClass> REPOSITORY =
            DescribedPredicate.describe("a repository", ScopedReadRulesTest::isRepository);

    private static final DescribedPredicate<JavaCall<?>> UNSCOPED_CUSTOMER_DATA_READ =
            DescribedPredicate.describe("an unscoped Spring Data read " + UNSCOPED_READS + " of a customer-data "
                    + "repository", call -> UNSCOPED_READS.contains(call.getName())
                    && call.getTargetOwner().isAssignableTo(SPRING_DATA_REPOSITORY)
                    && inCustomerData(call.getTargetOwner()));

    private static final DescribedPredicate<JavaClass> RAW_ACCESS_TYPE =
            DescribedPredicate.describe("JPA or JDBC types " + RAW_ACCESS, c -> RAW_ACCESS.contains(c.getFullName()));

    private static final DescribedPredicate<JavaCall<?>> STORAGE_READ =
            DescribedPredicate.describe("a method of " + STORAGE + " that is not a write or removal", call ->
                    inPackage(call.getTargetOwner(), STORAGE)
                            && STORAGE_WRITE_PREFIXES.stream().noneMatch(p -> call.getName().startsWith(p))
                            && !call.getName().equals("<init>"));

    @Test
    @DisplayName("S0-AC-84 only the system-job package reads customer-data repositories by ID or in full, without a "
            + "scope")
    void unscopedReadsOnlyInSystemPackage() {
        assertThat(classesIn(SYSTEM)).as("classes in %s", SYSTEM).isNotEmpty();

        noClasses()
                .that().resideInAPackage(ROOT + "..")
                .and().resideOutsideOfPackage(SYSTEM + "..")
                .should().callCodeUnitWhere(UNSCOPED_CUSTOMER_DATA_READ)
                .because("customer data is read with the viewer's scope; reads by ID alone belong to system jobs")
                .check(PRODUCTION);
    }

    @Test
    @DisplayName("S0-AC-84 every public method of a customer-data repository that is not a write takes the "
            + "viewer's scope")
    void customerDataReadsTakeViewer() {
        assertThat(PRODUCTION.stream().anyMatch(c -> c.getFullName().equals(VIEWER))).as("class %s", VIEWER).isTrue();
        List<String> violations = new ArrayList<>();
        for (String pkg : CUSTOMER_DATA) {
            List<JavaClass> repositories = classesIn(pkg).stream()
                    .filter(ScopedReadRulesTest::inCustomerData)
                    .filter(ScopedReadRulesTest::isRepository)
                    .toList();
            assertThat(repositories).as("repositories in %s", pkg).isNotEmpty();
            for (JavaClass repository : repositories) {
                for (JavaMethod method : repository.getMethods()) {
                    Set<JavaModifier> modifiers = method.getModifiers();
                    boolean write = WRITE_PREFIXES.stream().anyMatch(p -> method.getName().startsWith(p));
                    boolean objectMethod = Set.of("equals", "hashCode", "toString").contains(method.getName());
                    boolean scoped = method.getRawParameterTypes().stream()
                            .anyMatch(t -> t.getFullName().equals(VIEWER));
                    if (modifiers.contains(JavaModifier.PUBLIC) && !modifiers.contains(JavaModifier.STATIC)
                            && !modifiers.contains(JavaModifier.SYNTHETIC) && !modifiers.contains(JavaModifier.BRIDGE)
                            && !write && !objectMethod && !scoped) {
                        violations.add(method.getFullName());
                    }
                }
            }
        }
        assertThat(violations).as("read methods without a %s parameter", VIEWER).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-84 in the helpdesk module only repositories, system jobs, and the storage use JPA or JDBC "
            + "directly")
    void rawDatabaseAccessOnlyInRepositories() {
        assertThat(classesIn(HELPDESK).stream().anyMatch(ScopedReadRulesTest::isRepository))
                .as("repositories in %s", HELPDESK).isTrue();

        noClasses()
                .that().resideInAPackage(HELPDESK + "..")
                .and().resideOutsideOfPackages(SYSTEM + "..", STORAGE + "..")
                .and(DescribedPredicate.not(REPOSITORY))
                .should().dependOnClassesThat(RAW_ACCESS_TYPE)
                .because("a service that reads with an EntityManager or JDBC steps around the viewer's scope")
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

    @Test
    @DisplayName("S0-AC-84 outside the attachment storage only the attachment service and system jobs use it")
    void storageUsedOnlyByAttachmentServiceAndSystem() {
        assertThat(classesIn(STORAGE)).as("classes in %s", STORAGE).isNotEmpty();

        noClasses()
                .that().resideOutsideOfPackages(STORAGE + "..", SYSTEM + "..")
                .and().doNotHaveFullyQualifiedName(ATTACHMENT_SERVICE)
                .should().dependOnClassesThat().resideInAPackage(STORAGE + "..")
                .because("file contents are customer data; other code reaches them through the attachment service")
                .check(PRODUCTION);
    }
}
