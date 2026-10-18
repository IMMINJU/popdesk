package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.HeldWrite;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The app of every server test that asks for a company export (slice 10b): one export runs at a time in the whole
 * installation (acceptance.md 5절 3), so these tests run in a context and a database of their own and no other class
 * takes that one place.
 *
 * <ul>
 *   <li>"bare" ({@link BareAppTest}): the export notifies only through the inbox.
 *   <li>{@code POPDESK_EXPORT_DIR} is {@link #EXPORT_DIR}, a folder this class made, so a test can look at the files
 *       there and make the folder unwritable for a while ({@link CompanyExportSteps#withExportDirAFile}).
 *   <li>Attachments are stored on {@code disk} in {@link #STORAGE_DIR}, so a test can delete a stored file and make it
 *       unreadable (S10B-AC-14), at {@code {folder}/{first two characters of the ID}/{ID}} (configuration.md).
 * </ul>
 *
 * <p>Tests wait for their own exports to end by reading the list again ({@link CompanyExportSteps#awaitEnded}), never
 * with a fixed wait, and start one again while another class's export still runs
 * ({@link CompanyExportSteps#start}).
 */
@TestPropertySource(properties = {CompanyExportAppTest.OWN_CONTEXT, "POPDESK_STORAGE=disk"})
@Import(CompanyExportAppTest.Folders.class)
public abstract class CompanyExportAppTest extends BareAppTest {

    /** A property the app does not read; it keeps this context apart from the other bare ones. */
    public static final String OWN_CONTEXT = "privacy.test.context=company-export";

    /** Where the app writes the ZIP files. */
    public static final Path EXPORT_DIR = folder("popdesk-exports-");

    /** Where the app stores attachment files. */
    public static final Path STORAGE_DIR = folder("popdesk-attachments-");

    /** Sets the two folders; a registrar bean is applied after the setups' own dynamic keys. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Folders {

        @Bean
        static DynamicPropertyRegistrar companyExportFolders() {
            return registry -> {
                registry.add("POPDESK_EXPORT_DIR", () -> EXPORT_DIR.toString());
                registry.add("POPDESK_STORAGE_DIR", () -> STORAGE_DIR.toString());
            };
        }
    }

    private static Path folder(String prefix) {
        try {
            return Files.createTempDirectory(prefix).toAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make a folder for the company export tests", e);
        }
    }

    /** This context's database container, for tests that hold the end of an export ({@link #holdEnd()}). */
    @Autowired
    protected PostgreSQLContainer postgres;

    /** Issue steps of the earlier slices (status, filing on behalf, suspending, company status). */
    protected IssueFlow flow;

    @BeforeEach
    void setUpCompanyExport() {
        flow = new IssueFlow(fixtures, clock);
    }

    /**
     * Holds every change of an export's row ({@link HeldWrite}), so an export stays {@code RUNNING} until the test
     * lets it go: the export ends with an {@code UPDATE} of its row to {@code DONE} or {@code FAILED}. The table is the
     * one design.md 1절 names; the test fails on an assertion while it is not there.
     */
    protected HeldWrite holdEnd() {
        assertThat(db.tables()).as("tables of the app (design.md 1절)").contains(CompanyExportSteps.TABLE);
        return new HeldWrite(db, postgres, CompanyExportSteps.TABLE, "update", "");
    }
}
