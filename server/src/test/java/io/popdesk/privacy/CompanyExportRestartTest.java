package io.popdesk.privacy;

import static io.popdesk.privacy.CompanyExportSteps.filePath;
import static io.popdesk.privacy.CompanyExportSteps.list;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;
import tools.jackson.databind.JsonNode;

/**
 * What the app does with the exports of its last run when it starts (S10B-AC-06): an export still {@code RUNNING}
 * becomes {@code FAILED} with {@code INTERRUPTED}, a {@code DONE} export whose file is gone becomes {@code EXPIRED},
 * and of the files in the export folder only those of {@code DONE} exports stay.
 *
 * <p>A context of its own ({@link LastRun}), as {@code LastAdminAnonymizeTest} has one. The last run is put in before
 * the app first starts: the folder with its files is made when the context reads its keys, and a Flyway callback puts
 * the rows into the export table right after the last migration, before the app goes on to start. This is the one
 * place a test writes rows of the export table; there is no other way to have an export the app did not start. The
 * rows are those of design.md 1절, with a company and a suspended agent for their keys. The files are named as
 * design.md 0절 5 writes them: {@code {export ID}.zip}, and {@code {export ID}.zip.part} while one is written.
 */
@Import(CompanyExportRestartTest.LastRun.class)
class CompanyExportRestartTest extends BareAppTest {

    static final String CODE = "RSTRT1";
    static final UUID RUNNING = UUID.fromString("0f6c1a52-6d1e-4c51-9f0e-6a5a3c0e0a01");
    static final UUID DONE_WITH_FILE = UUID.fromString("0f6c1a52-6d1e-4c51-9f0e-6a5a3c0e0a02");
    static final UUID DONE_WITHOUT_FILE = UUID.fromString("0f6c1a52-6d1e-4c51-9f0e-6a5a3c0e0a03");
    /** A ZIP of no export: one whose row was never written. */
    static final UUID NO_EXPORT = UUID.fromString("0f6c1a52-6d1e-4c51-9f0e-6a5a3c0e0a04");

    static final byte[] KEPT_ZIP = zip("manifest.json", "{\"code\": \"" + CODE + "\"}");
    static final Path EXPORT_DIR = lastRunFolder();

    /** What the callback could not do, or null once it put the rows in. */
    static volatile String seedProblem = "the callback did not run";

    @TestConfiguration(proxyBeanMethods = false)
    static class LastRun {

        @Bean
        static DynamicPropertyRegistrar lastRunFolder() {
            return registry -> registry.add("POPDESK_EXPORT_DIR", () -> EXPORT_DIR.toString());
        }

        @Bean
        Callback lastRunExports() {
            return new Callback() {

                @Override
                public boolean supports(Event event, Context context) {
                    return event == Event.AFTER_MIGRATE;
                }

                @Override
                public boolean canHandleInTransaction(Event event, Context context) {
                    return false;
                }

                @Override
                public void handle(Event event, Context context) {
                    seedProblem = seed(context.getConfiguration().getDataSource());
                }

                @Override
                public String getCallbackName() {
                    return "company export last run";
                }
            };
        }
    }

    private static Path lastRunFolder() {
        try {
            Path dir = Files.createTempDirectory("popdesk-exports-restart-").toAbsolutePath();
            Files.write(dir.resolve(DONE_WITH_FILE + ".zip"), KEPT_ZIP);
            Files.write(dir.resolve(NO_EXPORT + ".zip"), zip("manifest.json", "{}"));
            Files.write(dir.resolve(RUNNING + ".zip.part"), new byte[] {'P', 'K'});
            Files.write(dir.resolve(UUID.randomUUID() + ".zip.part"), new byte[] {'P', 'K'});
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make the export folder of the last run", e);
        }
    }

    private static byte[] zip(String name, String content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Puts the company, its requester, and the three exports in; gives what went wrong, or null. */
    private static String seed(DataSource dataSource) {
        Instant start = TestClock.START;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(true);
            if (!c.getMetaData().getTables(null, c.getSchema(), CompanyExportSteps.TABLE, null).next()) {
                return "no table " + CompanyExportSteps.TABLE + " after the last migration";
            }
            update(c, "insert into company (tenant_id, code, name, legal_name, status, created_at) "
                    + "values (1, ?, 'Restart company', 'Restart company Ltd.', 'ACTIVE', ?)", CODE, at(start, 48));
            update(c, "insert into account (tenant_id, email, name, role, status, locale, created_at, updated_at) "
                    + "values (1, 'restart-requester@example.test', 'Restart requester', 'AGENT', 'SUSPENDED', 'en', "
                    + "?, ?)", at(start, 48), at(start, 48));
            String insert = "insert into " + CompanyExportSteps.TABLE + " (id, tenant_id, company_id, requested_by, "
                    + "include_internal, include_private, state, failure, size_bytes, requested_at, finished_at) "
                    + "values (?, 1, (select id from company where code = ?), (select id from account where email = "
                    + "'restart-requester@example.test'), false, false, ?, null, ?, ?, ?)";
            update(c, insert, RUNNING, CODE, "RUNNING", null, at(start, 0), null);
            update(c, insert, DONE_WITH_FILE, CODE, "DONE", (long) KEPT_ZIP.length, at(start, 1), at(start, 1));
            update(c, insert, DONE_WITHOUT_FILE, CODE, "DONE", 1024L, at(start, 2), at(start, 2));
            return null;
        } catch (SQLException e) {
            return "the rows of the last run could not be put in: " + e.getMessage();
        }
    }

    private static Timestamp at(Instant start, int hoursBefore) {
        return Timestamp.from(start.minus(Duration.ofHours(hoursBefore)).minusSeconds(60));
    }

    private static void update(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                s.setObject(i + 1, values[i]);
            }
            s.executeUpdate();
        }
    }

    private static List<String> files() {
        try (Stream<Path> files = Files.list(EXPORT_DIR)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("S10B-AC-06 when the app starts, an export still RUNNING becomes FAILED with INTERRUPTED, a DONE "
            + "export whose file is gone becomes EXPIRED, the files of no DONE export (a .part, a ZIP of no export) "
            + "are deleted, and the DONE export with its file stays and is downloaded")
    void startEndsTheLastRun() {
        assertThat(seedProblem).as("what went wrong putting the last run in").isNull();
        Session admin = fixtures.signInAdmin();

        Map<String, JsonNode> byId = list(admin, CODE).stream()
                .collect(Collectors.toMap(e -> e.path("id").asString(""), Function.identity()));

        assertThat(byId).as("exports of %s", CODE).containsOnlyKeys(RUNNING.toString(), DONE_WITH_FILE.toString(),
                DONE_WITHOUT_FILE.toString());
        JsonNode interrupted = byId.get(RUNNING.toString());
        assertThat(interrupted.path("state").asString(null)).as("state of %s", interrupted).isEqualTo("FAILED");
        assertThat(interrupted.path("failure").asString(null)).as("failure of %s", interrupted)
                .isEqualTo("INTERRUPTED");
        assertThat(byId.get(DONE_WITH_FILE.toString()).path("state").asString(null)).as("state of the DONE export "
                + "with its file").isEqualTo("DONE");
        assertThat(byId.get(DONE_WITHOUT_FILE.toString()).path("state").asString(null)).as("state of the DONE "
                + "export without its file").isEqualTo("EXPIRED");
        assertThat(files()).as("files in the export folder").containsExactly(DONE_WITH_FILE + ".zip");
        ApiResponse kept = admin.client().get(filePath(DONE_WITH_FILE.toString())).expect(200);
        assertThat(kept.body()).as("the kept ZIP").isEqualTo(KEPT_ZIP);
    }
}
