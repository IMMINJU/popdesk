package io.popdesk.privacy;

import static io.popdesk.privacy.PrivacySteps.AUDIT_LOG;
import static io.popdesk.privacy.PrivacySteps.anonymizeRequest;
import static io.popdesk.privacy.PrivacySteps.assertChainValid;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * Anonymizing in an installation with {@value #ENTRIES} audit entries of the person while 20 sign-ins come
 * (S10A-AC-20, acceptance.md 5절 2): the chain head is held only while the entries appended after the start are
 * computed, so every sign-in succeeds.
 *
 * <p>A context of its own ({@link HundredThousand}). The entries are not made through the API: a Flyway callback puts
 * them into {@code audit_log} right after V6, before the app first starts, so V7 computes their hashes the way it
 * does for an installation upgraded from 0.1 (design.md 7절). They are all done by account 1, the first admin the app
 * then creates, with the first admin's email. A second admin anonymizes the first, so the recalculation runs over all
 * of them.
 */
@Import(AnonymizeUnderLoadTest.HundredThousand.class)
class AnonymizeUnderLoadTest extends BareAppTest {

    static final int ENTRIES = 100_000;

    /** Puts {@value #ENTRIES} entries by account 1 into the audit table once V6 is applied. */
    @TestConfiguration(proxyBeanMethods = false)
    static class HundredThousand {

        @Bean
        Callback hundredThousandAuditEntries() {
            return new Callback() {

                @Override
                public boolean supports(Event event, Context context) {
                    return event == Event.AFTER_EACH_MIGRATE && context.getMigrationInfo() != null
                            && "6".equals(context.getMigrationInfo().getVersion().getVersion());
                }

                @Override
                public boolean canHandleInTransaction(Event event, Context context) {
                    return true;
                }

                @Override
                public void handle(Event event, Context context) {
                    try (Statement s = context.getConnection().createStatement()) {
                        s.execute("insert into audit_log (tenant_id, action, actor, target, changes, occurred_at) "
                                + "select 1, 'COMPANY_UPDATED', jsonb_build_object('accountId', 1, 'name', 'Load admin', "
                                + "'email', '" + AppTest.ADMIN_EMAIL + "'), jsonb_build_object('type', 'COMPANY', "
                                + "'code', 'LOAD' || g), jsonb_build_object('name', jsonb_build_object('from', "
                                + "'Before ' || g, 'to', 'After ' || g)), timestamptz '2026-10-01T00:00:00Z' "
                                + "+ g * interval '1 millisecond' from generate_series(1, " + ENTRIES + ") g");
                    } catch (SQLException e) {
                        throw new IllegalStateException("Could not put the audit entries in", e);
                    }
                }

                @Override
                public String getCallbackName() {
                    return "test-hundred-thousand-audit-entries";
                }
            };
        }
    }

    private static long total(Session admin, Object... query) {
        var request = admin.client().get(AUDIT_LOG).query("size", 1);
        for (int i = 0; i < query.length; i += 2) {
            request.query((String) query[i], query[i + 1]);
        }
        return request.expect(200).json().path("totalElements").asLong(-1);
    }

    @Test
    @DisplayName("S10A-AC-20 with 100,000 entries of the person, 20 sign-ins sent while the anonymization runs all "
            + "succeed; the anonymization succeeds, the oldest entries hold the anonymous values, and the integrity "
            + "check says all entries match")
    void signInsWhileAnonymizing() {
        Session first = fixtures.signInAdmin();
        assertThat(first.accountId()).as("ID of the first admin, whom the entries of the callback name").isEqualTo(1);
        Session second = fixtures.admin(first);
        List<Session> agents = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            agents.add(fixtures.agent(first));
        }
        assertThat(total(first, "actorAccountId", 1)).as("entries by the first admin").isGreaterThan(ENTRIES);
        List<Supplier<ApiResponse>> signIns = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            Session agent = agents.get(i % agents.size());
            signIns.add(() -> console().post("/api/public/sign-in")
                    .json(Map.of("email", agent.email(), "password", Fixtures.PASSWORD)).send());
        }

        CompletableFuture<ApiResponse> anonymizing = CompletableFuture.supplyAsync(
                () -> anonymizeRequest(second, 1).send());
        Wait.until("the anonymization changing audit rows, or its answer", Duration.ofSeconds(30),
                () -> anonymizing.isDone() || db.count("select count(*) from pg_stat_activity where state = 'active' "
                        + "and pid <> pg_backend_pid() and datname = current_database() "
                        + "and position('audit_log' in lower(query)) > 0 "
                        + "and lower(query) like '%update%'") > 0 ? Boolean.TRUE : null);
        boolean runningAtStart = !anonymizing.isDone();
        List<ApiResponse> answers = Concurrently.send(20, signIns);
        ApiResponse anonymized = Concurrently.finish(anonymizing, "the anonymization");

        assertThat(runningAtStart).as("the anonymization still ran when the sign-ins were sent (it answered %s)",
                anonymized.snippet()).isTrue();
        assertThat(answers).as("statuses of the 20 sign-ins").extracting(ApiResponse::status)
                .containsOnly(204);
        anonymized.expectStatus(200);
        JsonNode oldest = second.client().get(AUDIT_LOG).query("actorAccountId", 1).query("size", 100)
                .query("page", (total(second, "actorAccountId", 1) + 99) / 100).expect(200).json().path("items");
        assertThat(oldest.isArray() && !oldest.isEmpty()).as("the oldest page of the first admin's entries").isTrue();
        oldest.forEach(e -> assertThat(e.path("actor").path("email").asString(null)).as("actor of %s", e)
                .isEqualTo(PrivacySteps.anonymousEmail(1)));
        assertChainValid(second);
    }
}
