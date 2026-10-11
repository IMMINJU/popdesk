package io.popdesk.project;

import static io.popdesk.project.PeopleSteps.account;
import static io.popdesk.project.PeopleSteps.accounts;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * Two admins who are the only {@code ACTIVE} admins take each other down at the same time (S1A
 * premortem 1).
 *
 * <p>This class has a context of its own ({@link OwnContext}): in a shared one, the admins other
 * test classes made would stay, and both requests could rightly succeed. Each test first makes every
 * admin but the first one an agent, so it does not depend on what the other invocation left.
 *
 * <p>The request that loses is 400 {@code LAST_ADMIN} when both were let in before either finished.
 * When the winner finished first, the loser's session is already gone and the answer is 401; that is
 * a right answer too, so each round takes either, and the test asks that {@code LAST_ADMIN} was seen
 * in at least one of the rounds.
 */
@Import(LastAdminTest.OwnContext.class)
class LastAdminTest extends BareAppTest {

    private static final int ROUNDS = 12;

    /** Nothing in it; importing it gives this class a test context no other class shares. */
    @TestConfiguration(proxyBeanMethods = false)
    static class OwnContext {}

    private static List<ApiResponse> together(Supplier<ApiResponse> first, Supplier<ApiResponse> second) {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (Supplier<ApiResponse> call : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.get();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new AssertionError("The two requests did not get ready in 30 seconds");
            }
            go.countDown();
            List<ApiResponse> out = new ArrayList<>();
            for (Future<ApiResponse> future : futures) {
                out.add(future.get(60, TimeUnit.SECONDS));
            }
            return out;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError failed) {
                throw failed;
            }
            throw new AssertionError("A concurrent request failed", e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("The two requests did not finish in 60 seconds", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for the two requests", e);
        } finally {
            pool.shutdownNow();
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
    @DisplayName("S1A-AC-81 when the only two active admins demote or suspend each other at the same time, one "
            + "request succeeds, the other changes nothing, and an active admin is left every time")
    void twoAdminsTakeEachOtherDown(String action) {
        Session first = fixtures.signInAdmin();
        for (JsonNode other : activeAdmins(first)) {
            if (other.path("id").asLong(-1) != first.accountId()) {
                PeopleSteps.roleChange(first, other.path("id").asLong(-1), Role.AGENT).expect(200);
            }
        }
        Session second = fixtures.admin(first);
        assertThat(activeAdmins(first)).as("active admins before the rounds")
                .extracting(n -> n.path("id").asLong(-1))
                .containsExactlyInAnyOrder(first.accountId(), second.accountId());
        int lastAdminAnswers = 0;

        for (int round = 1; round <= ROUNDS; round++) {
            Session a = fixtures.signIn(App.CONSOLE, first.email(), first.password());
            Session b = fixtures.signIn(App.CONSOLE, second.email(), second.password());

            List<ApiResponse> responses = together(() -> takeDown(action, a, b), () -> takeDown(action, b, a));

            assertThat(responses).as("round %s: requests that succeeded, of %s and %s", round, responses.get(0),
                    responses.get(1)).filteredOn(r -> r.status() == 200).hasSize(1);
            boolean firstWon = responses.get(0).status() == 200;
            ApiResponse lost = responses.get(firstWon ? 1 : 0);
            Session winner = firstWon ? a : b;
            Session loser = firstWon ? b : a;
            assertThat(lost.status()).as("round %s: status of the request that lost: %s", round, lost.snippet())
                    .isIn(400, 401);
            if (lost.status() == 400) {
                assertThat(lost.errorCode()).as("round %s: code of the request that lost", round)
                        .isEqualTo("LAST_ADMIN");
                lastAdminAnswers++;
            }
            List<JsonNode> left = activeAdmins(winner);
            assertThat(left).as("round %s: active admins left", round).extracting(n -> n.path("id").asLong(-1))
                    .containsExactly(winner.accountId());
            JsonNode taken = ProjectChecks.account(winner, loser.accountId());
            assertThat(taken.path("role").asString(null) + " " + taken.path("status").asString(null))
                    .as("round %s: the account that was taken down", round)
                    .isEqualTo(action.equals("role") ? "AGENT ACTIVE" : "ADMIN SUSPENDED");

            bringBack(action, winner, loser);
        }

        assertThat(lastAdminAnswers).as("rounds of %s that ended in 400 LAST_ADMIN", ROUNDS).isPositive();
    }
}
