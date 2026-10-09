package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * What the app does when the database says no (conventions "Retrying on lock conflicts", S0
 * premortem 2 and 4): unique violations become the code of their constraint, connections have a
 * 5-second {@code lock_timeout}, and a lock timeout is retried once.
 */
class DatabaseConflictTest extends BareAppTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    PostgreSQLContainer postgres;

    /** Sends the calls at the same moment and returns the responses in call order. */
    private static List<ApiResponse> atOnce(List<Supplier<ApiResponse>> calls) {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (Supplier<ApiResponse> call : calls) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return call.get();
                }));
            }
            go.countDown();
            List<ApiResponse> out = new ArrayList<>();
            for (Future<ApiResponse> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError a) {
                throw a;
            }
            throw new AssertionError("A concurrent request failed", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("Concurrent requests did not finish in 60 seconds", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("S0-AC-85 the same company code created ten times at once: one 201, nine 409 COMPANY_CODE_TAKEN")
    void concurrentCompanyCode() {
        Session admin = fixtures.signInAdmin();
        String code = Unique.companyCode();
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Session browser = fixtures.signInAdmin();
            String n = Integer.toString(i);
            calls.add(() -> browser.client().post("/api/console/companies")
                    .json(Map.of("code", code, "name", "Same " + n, "legalName", "Same " + n + " Ltd.")).send());
        }

        List<ApiResponse> responses = atOnce(calls);

        assertThat(responses).extracting(ApiResponse::status).as("statuses").containsOnly(201, 409)
                .filteredOn(s -> s == 201).hasSize(1);
        responses.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertThat(r.errorCode()).as(r.snippet()).isEqualTo("COMPANY_CODE_TAKEN"));
        admin.client().get("/api/console/companies/" + code).expect(200);
    }

    @Test
    @DisplayName("S0-AC-85 the same email invited ten times at once: one 201, nine 409 EMAIL_TAKEN")
    void concurrentInvitationEmail() {
        String email = Unique.email("twice");
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Session browser = fixtures.signInAdmin();
            String n = Integer.toString(i);
            calls.add(() -> browser.client().post("/api/console/invitations")
                    .json(Map.of("email", email, "name", "Same " + n, "role", "AGENT")).send());
        }

        List<ApiResponse> responses = atOnce(calls);

        assertThat(responses).extracting(ApiResponse::status).as("statuses").containsOnly(201, 409)
                .filteredOn(s -> s == 201).hasSize(1);
        responses.stream().filter(r -> r.status() == 409)
                .forEach(r -> assertThat(r.errorCode()).as(r.snippet()).isEqualTo("EMAIL_TAKEN"));
    }

    @Test
    @DisplayName("S0-AC-85 the app's database connections have lock_timeout 5s")
    void lockTimeoutIsFiveSeconds() throws SQLException {
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("show lock_timeout")) {
            assertThat(rs.next()).as("a row from SHOW lock_timeout").isTrue();
            assertThat(rs.getString(1)).as("lock_timeout").isEqualTo("5s");
        }
    }

    /** Backends waiting on a lock held by {@code blockerPid}: pid and transaction start. */
    private Set<String> waitingOn(int blockerPid) {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> r : db.rows("select pid, xact_start::text as xs from pg_stat_activity "
                + "where ? = any(pg_blocking_pids(pid))", blockerPid)) {
            out.add(r.get("pid") + "@" + r.get("xs"));
        }
        return out;
    }

    @Test
    @DisplayName("S0-AC-85 a request that hits the lock timeout (55P03) is retried once and then succeeds")
    void lockTimeoutRetriedOnce() throws Exception {
        Session admin = fixtures.signInAdmin();
        String legalName = Unique.text("Locked Ltd.");
        Company company = fixtures.createCompany(admin, Unique.companyCode(), Unique.text("Locked"), legalName);
        Session customer = fixtures.customer(admin, company.code());
        String table = companyTable(legalName);

        try (Connection lock = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword())) {
            lock.setSchema(db.schema());
            lock.setAutoCommit(false);
            int pid;
            try (Statement s = lock.createStatement(); ResultSet rs = s.executeQuery("select pg_backend_pid()")) {
                rs.next();
                pid = rs.getInt(1);
            }
            try (PreparedStatement s = lock.prepareStatement("select 1 from " + table.split("\\|")[0] + " where "
                    + table.split("\\|")[1] + " = ? for update")) {
                s.setString(1, legalName);
                s.executeQuery().close();
            }

            CompletableFuture<ApiResponse> filing = CompletableFuture.supplyAsync(() -> customer.client()
                    .post("/api/portal/issues")
                    .json(Map.of("companyCode", company.code(), "title", Unique.text("Waits"), "content", "x"))
                    .send());

            Set<String> first = Wait.until("the filing waiting on the company row", () -> {
                Set<String> w = waitingOn(pid);
                return w.isEmpty() ? null : w;
            });
            String seen = Wait.until("a second attempt after the first timed out, or the end of the filing",
                    Duration.ofSeconds(20), () -> {
                        if (filing.isDone()) {
                            return "ended";
                        }
                        Set<String> now = waitingOn(pid);
                        now.removeAll(first);
                        return now.isEmpty() ? null : "retry";
                    });
            if (seen.equals("ended")) {
                ApiResponse ended = filing.join();
                throw new AssertionError("The filing ended while the row was still locked, without a retry: "
                        + ended.status() + " " + ended.snippet());
            }
            lock.rollback();

            ApiResponse response = filing.get(30, TimeUnit.SECONDS);
            assertThat(response.status()).as(response.snippet()).isEqualTo(201);
        }
    }

    /** {@code "schema"."table"|"column"} of the text column that holds the company's legal name. */
    private String companyTable(String legalName) {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> col : db.rows("select table_name, column_name from information_schema.columns "
                + "where table_schema = ? and data_type in ('text', 'character varying')", db.schema())) {
            String t = quote(db.schema()) + "." + quote((String) col.get("table_name"));
            String c = quote((String) col.get("column_name"));
            if (db.count("select count(*) from " + t + " where " + c + " = ?", legalName) == 1) {
                found.add(t + "|" + c);
            }
        }
        assertThat(found).as("columns holding the legal name %s", legalName).hasSize(1);
        return found.getFirst();
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }
}
