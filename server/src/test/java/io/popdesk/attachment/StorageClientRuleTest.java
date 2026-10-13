package io.popdesk.attachment;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Only the attachment storage package uses the S3 client's types (ADR D-57, D-60). The classes are
 * read by package name from the compiled production code, as {@link AttachmentArchitectureTest} does;
 * this test imports no production class.
 */
class StorageClientRuleTest {

    private static final String STORAGE_PACKAGE = "io.popdesk.helpdesk.attachment.storage..";

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .importPackages("io.popdesk");

    @Test
    @DisplayName("S5B-AC-61 no production class outside the attachment storage package depends on a type of the "
            + "AWS SDK")
    void onlyTheStoragePackageUsesTheS3Client() {
        assertThat(PRODUCTION.stream().filter(c -> c.getPackageName().startsWith(
                "io.popdesk.helpdesk.attachment.storage"))).as("classes in the storage package").isNotEmpty();

        noClasses().that().resideOutsideOfPackage(STORAGE_PACKAGE)
                .should().dependOnClassesThat().resideInAPackage("software.amazon.awssdk..")
                .because("the S3 client stays behind the attachment storage (D-57, D-60)")
                .check(PRODUCTION);
    }
}
