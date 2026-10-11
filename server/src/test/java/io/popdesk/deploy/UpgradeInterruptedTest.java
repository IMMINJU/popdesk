package io.popdesk.deploy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.Wait;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * An upgrade from 0.1.0 whose app dies while it computes the hashes of the audit rows that were
 * there (slice 1b premortem section 2): until that is done neither port answers, and the next start
 * ends with the same chain a start without the interruption gives.
 *
 * <p>The computation is held where the design puts it (design section 1: the migration updates the
 * rows of {@code audit_log}): a {@code test_} trigger before update on that table sleeps. The app
 * container is then killed, the trigger removed, and the app started again.
 */
@Tag("deploy")
class UpgradeInterruptedTest {

    private static final String SAMPLE = "deploy/upgrade/samples/0.1.0/";

    /** The first admin of the 0.1.0 sample (as in {@code UpgradeFrom010Test}). */
    private static final String SAMPLE_ADMIN = "admin@sample.popdesk.test";
    private static final String SAMPLE_ADMIN_PASSWORD = "sample-Admin-Password-1";

    private static final String HELD = "select count(*) from pg_stat_activity where wait_event = 'PgSleep' "
            + "and pid <> pg_backend_pid() and datname = current_database()";

    /** Whether an HTTP request to a published port of the app gets any answer. */
    private static boolean answers(int hostPort, String path) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + hostPort + path))
                .timeout(Duration.ofSeconds(3)).GET().build();
        try {
            client.send(request, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (IOException noAnswer) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while asking port " + hostPort, e);
        }
    }

    private static String health(Stack stack) {
        return stack.inspect("app").path("State").path("Health").path("Status").asString("");
    }

    @Test
    @DisplayName("S1B-AC-13 while the hashes of the old audit rows are computed neither port answers; after the "
            + "app was killed there, the next start finishes: every old row is chained by time and then ID from "
            + "seq 1, no migration is left failed, and the integrity check says valid")
    void appKilledWhileComputingHashesFinishesOnTheNextStart() {
        Path dump = Repo.require(SAMPLE + "sample.dump", "the sample kept from 0.1.0, deploy/upgrade/README.md");
        try (Stack stack = Stack.release("upgradekill")) {
            stack.appEnv("POPDESK_ADMIN_EMAIL", SAMPLE_ADMIN).appEnv("POPDESK_ADMIN_PASSWORD", SAMPLE_ADMIN_PASSWORD);
            stack.up("db");
            Db db = stack.db();
            db.awaitReady();
            stack.command(Duration.ofMinutes(5), "run", "--rm", "-T", "-v",
                            Repo.forCompose(dump) + ":/tmp/restore.dump:ro", "app", "popdesk", "restore",
                            "/tmp/restore.dump")
                    .expectOk("popdesk restore of the 0.1.0 sample");
            String schema = Db.quote(db.schema());
            String audit = schema + ".audit_log";
            String function = schema + ".test_hold_audit_update";
            List<Long> rowsBefore = db.query("select id from " + audit + " order by occurred_at, id").stream()
                    .map(row -> ((Number) row.get("id")).longValue()).toList();
            assertThat(rowsBefore).as("audit rows of the restored 0.1.0 sample").isNotEmpty();
            db.execute("create function " + function + "() returns trigger language plpgsql as "
                    + "$$ begin perform pg_sleep(300); return NEW; end $$");
            db.execute("create trigger test_hold_audit_update before update on " + audit
                    + " for each row execute function " + function + "()");

            stack.up("app");
            Wait.until("the app updating a row of audit_log while it starts", Stack.HEALTHY, () -> {
                if (db.count(HELD) > 0) {
                    return Boolean.TRUE;
                }
                if ("healthy".equals(health(stack))) {
                    throw new AssertionError("The app became healthy on the 0.1.0 sample without updating a row of "
                            + "audit_log: no hashes were computed for the rows that were there");
                }
                return null;
            });
            boolean httpAnswered = answers(stack.port("app", 8080), "/api/public/csrf");
            boolean managementAnswered = answers(stack.port("app", 8081), "/actuator/health/readiness");
            String healthWhileHeld = health(stack);

            Cli.run(Duration.ofMinutes(1), Cli.docker("kill", stack.containerId("app"))).expectOk("docker kill app");
            stack.command("stop", "app").expectOk("docker compose stop app");
            db.execute("select pg_terminate_backend(pid) from pg_stat_activity where pid <> pg_backend_pid() "
                    + "and datname = current_database()");
            db.execute("drop trigger if exists test_hold_audit_update on " + audit);
            db.execute("drop function if exists " + function + "()");
            stack.up("app");
            stack.awaitHealthy("app");

            assertThat(httpAnswered).as("the HTTP port answered while the hashes were computed").isFalse();
            assertThat(managementAnswered).as("the management port answered while the hashes were computed").isFalse();
            assertThat(healthWhileHeld).as("health of the app while the hashes were computed").isNotEqualTo("healthy");
            assertThat(db.count("select count(*) from " + schema + ".flyway_schema_history where not success"))
                    .as("failed migrations after the second start").isZero();
            Person admin = new AppSteps(stack).signIn(SAMPLE_ADMIN, SAMPLE_ADMIN_PASSWORD);
            AuditFile.Chain chain = AuditFile.export(admin, line -> false);
            assertThat(chain.firstSeq()).as("seq of the first line of the export").isEqualTo(1);
            assertThat(chain.firstPrevHash()).as("prevHash of the first line").isEqualTo(AuditFile.ZERO_HASH);
            assertThat(chain.ids().size()).as("lines of the export").isGreaterThanOrEqualTo(rowsBefore.size());
            assertThat(chain.ids().subList(0, rowsBefore.size()))
                    .as("the first lines of the export: the rows from before the upgrade, by time and then ID")
                    .containsExactlyElementsOf(rowsBefore);
            JsonNode answer = AuditFile.assertValid(admin);
            assertThat(AuditFile.number(answer, "checkedCount")).as("checkedCount in %s", answer)
                    .isGreaterThanOrEqualTo(rowsBefore.size());
        }
    }
}
