package io.popdesk.project;

import static io.popdesk.project.PeopleSteps.account;
import static io.popdesk.project.PeopleSteps.accounts;
import static io.popdesk.project.ProjectChecks.assertError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.HeldWrite;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;

/**
 * Two admins who are the only {@code ACTIVE} admins take each other down at the same time (S1A
 * premortem 1).
 *
 * <p>This class has a context of its own ({@link OwnContext}): in a shared one, the admins other
 * test classes made would stay, and both requests could rightly succeed. Each test first makes every
 * admin but the first one an agent, and brings the account that was taken down back at its end, so it
 * does not depend on what the other invocation left.
 *
 * <p>The two requests are made to overlap in the database ({@link HeldWrite}): the first request is
 * kept waiting at its UPDATE of the other admin's account row, the second is sent, and the first is
 * let go once the second has finished or waits for a lock. Both are then past the check that the
 * caller is an admin, so the one that loses is 400 {@code LAST_ADMIN}, never 401. This rests on the
 * role and the status being columns of the account row (the one table with a text column equal to
 * the account's email) that a take-down changes with an UPDATE.
 */
@Import(LastAdminTest.OwnContext.class)
class LastAdminTest extends BareAppTest {

    /** How long the first request is held when the second neither finishes nor shows in the database. */
    private static final Duration HELD_AT_MOST = Duration.ofSeconds(2);

    /** Nothing in it; importing it gives this class a test context no other class shares. */
    @TestConfiguration(proxyBeanMethods = false)
    static class OwnContext {}

    @Autowired
    PostgreSQLContainer postgres;

    /** The table, sessions left out, with a text column equal to the email. */
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

    private static ApiResponse finish(CompletableFuture<ApiResponse> running, String what) {
        try {
            return running.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while " + what + " finished", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError failed) {
                throw failed;
            }
            throw new AssertionError(what + " failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError(what + " did not finish within 30 seconds of the release", e);
        }
    }

    private List<JsonNode> activeAdmins(Session admin) {
        return accounts(admin, "role", "ADMIN").stream()
                .filter(n -> "ACTIVE".equals(n.path("status").asString(null))).toList();
    }

    private ApiResponse takeDown(String action, Session by, Session target) {
        return action.equals("role")
                ? PeopleSteps.roleChange(by, target.accountId(), Role.AGENT).send()
                : by.client().post(account(target.accountId()) + "/suspend").send();
    }

    private void bringBack(String action, Session by, Session target) {
        if (action.equals("role")) {
            PeopleSteps.roleChange(by, target.accountId(), Role.ADMIN).expect(200);
        } else {
            PeopleSteps.reactivate(by, target.accountId());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"role", "suspend"})
    @DisplayName("S1A-AC-81 when the only two active admins demote or suspend each other in overlapping requests, "
            + "one succeeds, the other is 400 LAST_ADMIN and changes nothing, and an active admin is left")
    void twoAdminsTakeEachOtherDown(String action) {
        Session first = fixtures.signInAdmin();
        for (JsonNode other : activeAdmins(first)) {
            if (other.path("id").asLong(-1) != first.accountId()) {
                PeopleSteps.roleChange(first, other.path("id").asLong(-1), Role.AGENT).expect(200);
            }
        }
        Session second = fixtures.admin(first);
        assertThat(activeAdmins(first)).as("active admins before the two requests")
                .extracting(n -> n.path("id").asLong(-1))
                .containsExactlyInAnyOrder(first.accountId(), second.accountId());
        ApiResponse ofFirst;
        ApiResponse ofSecond;

        try (HeldWrite hold = new HeldWrite(db, postgres, accountTable(second.email()), "update", second.email())) {
            CompletableFuture<ApiResponse> firstTakesDown = CompletableFuture.supplyAsync(
                    () -> takeDown(action, first, second));
            hold.awaitHeld("the first request's UPDATE of the second admin's account");
            CompletableFuture<ApiResponse> secondTakesDown = CompletableFuture.supplyAsync(
                    () -> takeDown(action, second, first));
            long heldUntil = System.nanoTime() + HELD_AT_MOST.toNanos();
            Wait.until("the second request finished, waits for a lock, or had " + HELD_AT_MOST,
                    () -> secondTakesDown.isDone() || hold.waitingForALock() > 0 || System.nanoTime() > heldUntil
                            ? Boolean.TRUE : null);
            hold.release();
            ofFirst = finish(firstTakesDown, "the first request");
            ofSecond = finish(secondTakesDown, "the second request");
        }

        assertThat(List.of(ofFirst.status(), ofSecond.status())).as("statuses of %s and %s", ofFirst.snippet(),
                ofSecond.snippet()).containsExactlyInAnyOrder(200, 400);
        boolean firstWon = ofFirst.status() == 200;
        assertError(firstWon ? ofSecond : ofFirst, 400, "LAST_ADMIN");
        Session winner = firstWon ? first : second;
        Session loser = firstWon ? second : first;
        Session after = fixtures.signIn(App.CONSOLE, winner.email(), winner.password());
        assertThat(activeAdmins(after)).as("active admins left").extracting(n -> n.path("id").asLong(-1))
                .containsExactly(winner.accountId());
        JsonNode taken = ProjectChecks.account(after, loser.accountId());
        assertThat(taken.path("role").asString(null) + " " + taken.path("status").asString(null))
                .as("the account that was taken down")
                .isEqualTo(action.equals("role") ? "AGENT ACTIVE" : "ADMIN SUSPENDED");

        // The first admin is the one every test signs in as; the next invocation needs it as it was.
        bringBack(action, after, loser);
    }
}
