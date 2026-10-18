package io.popdesk.privacy;

import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.privacy.PrivacySteps.accounts;
import static io.popdesk.privacy.PrivacySteps.anonymizeRequest;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Concurrently;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * The only two active admins: one anonymizes the other while the other suspends the first (S10A-AC-12, the last-admin
 * part). Only one of the two may succeed, and an active admin is always left.
 *
 * <p>A context of its own ({@link OwnContext}), as in {@code project.LastAdminTest}: in {@link PrivacyAppTest} there are
 * always more admins. Each invocation first makes every other active admin an agent. The request that comes first is
 * held at its {@code UPDATE} of the other admin's account row ({@link HeldWrite}, the one table besides the sessions
 * with a text column equal to the email, found as {@code LastAdminTest} finds it); the second is sent and the first is
 * let go once the second finished, waits for a lock, or had {@link #HELD_AT_MOST}. The loser is 400
 * {@code LAST_ADMIN}.
 */
@Import(LastAdminAnonymizeTest.OwnContext.class)
class LastAdminAnonymizeTest extends BareAppTest {

    private static final Duration HELD_AT_MOST = Duration.ofSeconds(2);

    /** Nothing in it; importing it gives this class a test context no other class shares. */
    @TestConfiguration(proxyBeanMethods = false)
    static class OwnContext {}

    /** Which request comes first and is held. */
    enum First {
        ANONYMIZE,
        SUSPEND
    }

    @Autowired
    PostgreSQLContainer postgres;

    private String accountTable(String email) {
        List<String> found = new ArrayList<>();
        for (String table : db.tables()) {
            if (table.startsWith("spring_session")) {
                continue;
            }
            for (Column column : db.columns(table)) {
                if (db.count("select count(*) from \"" + table + "\" where \"" + column.name() + "\"::text = ?",
                        email) > 0) {
                    found.add(table);
                    break;
                }
            }
        }
        assertThat(found).as("tables with a column equal to %s", email).hasSize(1);
        return found.getFirst();
    }

    private static List<JsonNode> activeAdmins(Session admin) {
        return accounts(admin, "role", "ADMIN", "status", "ACTIVE");
    }

    private static ApiResponse suspend(Session by, Session target) {
        return by.client().post(PrivacySteps.ACCOUNTS + "/" + target.accountId() + "/suspend").send();
    }

    @ParameterizedTest(name = "{0} first")
    @EnumSource(First.class)
    @DisplayName("S10A-AC-12 when the only two active admins anonymize and suspend each other in overlapping "
            + "requests, one succeeds, the other is 400 LAST_ADMIN and changes nothing, and one active admin is left")
    void anonymizeAndSuspendEachOther(First order) {
        Session first = fixtures.signInAdmin();
        for (JsonNode other : activeAdmins(first)) {
            long id = other.path("id").asLong(-1);
            if (id != first.accountId()) {
                first.client().put(PrivacySteps.ACCOUNTS + "/" + id + "/role").json(Map.of("role", "AGENT"))
                        .expect(200);
            }
        }
        Session second = fixtures.admin(first);
        assertThat(activeAdmins(first)).as("active admins before the two requests")
                .extracting(n -> n.path("id").asLong(-1))
                .containsExactlyInAnyOrder(first.accountId(), second.accountId());
        boolean anonymizeFirst = order == First.ANONYMIZE;
        Session heldTarget = anonymizeFirst ? second : first;
        ApiResponse anonymized;
        ApiResponse suspended;

        try (HeldWrite hold = new HeldWrite(db, postgres, accountTable(heldTarget.email()), "update",
                heldTarget.email())) {
            CompletableFuture<ApiResponse> held = CompletableFuture.supplyAsync(() -> anonymizeFirst
                    ? anonymizeRequest(first, second.accountId()).send() : suspend(second, first));
            hold.awaitHeld("the first request's UPDATE of " + heldTarget.email());
            CompletableFuture<ApiResponse> next = CompletableFuture.supplyAsync(() -> anonymizeFirst
                    ? suspend(second, first) : anonymizeRequest(first, second.accountId()).send());
            long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
            Wait.until("the second request finished, waits for a lock, or had " + HELD_AT_MOST,
                    () -> next.isDone() || hold.waitingForALock() > 0 || System.nanoTime() > heldUntil
                            ? Boolean.TRUE : null);
            hold.release();
            ApiResponse ofHeld = Concurrently.finish(held, "the first request");
            ApiResponse ofNext = Concurrently.finish(next, "the second request");
            anonymized = anonymizeFirst ? ofHeld : ofNext;
            suspended = anonymizeFirst ? ofNext : ofHeld;
        }
        if (suspended.status() == 200) {
            // The first admin is the one every invocation signs in as; the next one needs it active again, also
            // when an assertion below fails.
            fixtures.signIn(App.CONSOLE, second.email(), second.password()).client()
                    .post(PrivacySteps.ACCOUNTS + "/" + first.accountId() + "/reactivate").expect(200);
        }

        assertThat(List.of(anonymized.status(), suspended.status()))
                .as("statuses of anonymizing %s and suspending %s", anonymized.snippet(), suspended.snippet())
                .containsExactlyInAnyOrder(200, 400);
        boolean anonymizeWon = anonymized.status() == 200;
        assertRefused(anonymizeWon ? suspended : anonymized, 400, "LAST_ADMIN");
        Session winner = anonymizeWon ? first : second;
        Session after = fixtures.signIn(App.CONSOLE, winner.email(), winner.password());
        if (anonymizeWon) {
            assertThat(activeAdmins(after)).as("active admins left").extracting(n -> n.path("id").asLong(-1))
                    .containsExactly(first.accountId());
            JsonNode gone = PrivacySteps.anonymizedAccount(after, second.accountId());
            assertThat(gone.path("anonymized").asBoolean(false)).as("the second admin is anonymized").isTrue();
        } else {
            JsonNode kept = PrivacySteps.account(after, second.accountId(), second.email());
            assertThat(kept.path("anonymized").asBoolean(true)).as("the second admin is not anonymized").isFalse();
            assertThat(kept.path("status").asString(null)).as("status of the second admin").isEqualTo("ACTIVE");
            assertThat(suspended.json().path("status").asString(null)).as("status in the suspension's answer")
                    .isEqualTo("SUSPENDED");
        }
    }
}
