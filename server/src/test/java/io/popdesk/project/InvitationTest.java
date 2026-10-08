package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.account;
import static io.popdesk.project.ProjectChecks.allItems;
import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertNoStore;
import static io.popdesk.project.ProjectChecks.grown;
import static io.popdesk.project.ProjectChecks.pending;
import static io.popdesk.project.ProjectChecks.rowCounts;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Inviting the three roles without SMTP: the INVITED account, companies, and what a failed invitation leaves. */
class InvitationTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-33 inviting a customer makes an INVITED account in the chosen companies")
    void invitesCustomer() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        String email = Unique.email("customer");

        ApiResponse response = invite(admin, email, "Customer One", "CUSTOMER", List.of(a.code(), b.code()));

        response.expectStatus(201);
        long id = response.jsonLong("accountId");
        JsonNode account = account(admin, id);
        assertThat(account.path("status").asString(null)).isEqualTo("INVITED");
        assertThat(account.path("role").asString(null)).isEqualTo("CUSTOMER");
        assertThat(account.path("email").asString(null)).isEqualTo(email);
        assertThat(account.path("name").asString(null)).isEqualTo("Customer One");
        for (Company company : List.of(a, b)) {
            assertThat(allItems(admin.client(), "/api/console/companies/" + company.code() + "/customers"))
                    .anySatisfy(m -> {
                        assertThat(m.path("accountId").asLong(-1)).isEqualTo(id);
                        assertThat(m.path("status").asString(null)).isEqualTo("INVITED");
                    });
        }
    }

    @Test
    @DisplayName("S0-AC-33 inviting an agent and an admin makes INVITED accounts with those roles")
    void invitesStaff() {
        Session admin = fixtures.signInAdmin();
        String agentEmail = Unique.email("agent");
        String adminEmail = Unique.email("admin");

        long agentId = invite(admin, agentEmail, "Agent One", "AGENT", List.of()).expectStatus(201).jsonLong("accountId");
        long adminId = invite(admin, adminEmail, "Admin One", "ADMIN", List.of()).expectStatus(201).jsonLong("accountId");

        assertThat(account(admin, agentId).path("status").asString(null)).isEqualTo("INVITED");
        assertThat(account(admin, agentId).path("role").asString(null)).isEqualTo("AGENT");
        assertThat(account(admin, adminId).path("status").asString(null)).isEqualTo("INVITED");
        assertThat(account(admin, adminId).path("role").asString(null)).isEqualTo("ADMIN");
        assertThat(pending(admin, agentEmail)).hasSize(1);
        assertThat(pending(admin, adminEmail)).hasSize(1);
    }

    @Test
    @DisplayName("S0-AC-33 without SMTP the invitation response carries the link, with Cache-Control no-store")
    void responseCarriesLinkWithoutSmtp() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        ApiResponse customer = invite(admin, Unique.email("customer"), "Customer", "CUSTOMER", List.of(company.code()));
        ApiResponse agent = invite(admin, Unique.email("agent"), "Agent", "AGENT", List.of());

        customer.expectStatus(201);
        agent.expectStatus(201);
        assertThat(customer.jsonText("inviteLink")).startsWith("http://" + PORTAL_HOST + "/invite#token=");
        assertThat(agent.jsonText("inviteLink")).startsWith("http://" + CONSOLE_HOST + "/invite#token=");
        assertNoStore(customer);
        assertNoStore(agent);
    }

    @Test
    @DisplayName("S0-AC-33 an agent inviting gets 403 FORBIDDEN and no account is made")
    void agentCannotInvite() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String email = Unique.email("by-agent");

        assertError(invite(agent, email, "By agent", "AGENT", List.of()), 403, "FORBIDDEN");

        assertThat(pending(admin, email)).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-33 a failed invitation leaves no row in any table: no account, no mail queue row, no audit row")
    void failedInvitationLeavesNothing() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session existing = fixtures.customer(admin, company.code());
        Map<String, Long> before = rowCounts(db);

        assertError(invite(admin, existing.email(), "Again", "CUSTOMER", List.of(company.code())), 409,
                "EMAIL_TAKEN");
        assertError(invite(admin, Unique.email("nocompany"), "No company", "CUSTOMER", List.of()), 400,
                "INVALID_INPUT");
        assertError(invite(admin, Unique.email("unknown"), "Unknown company", "CUSTOMER",
                List.of(company.code(), "NOSUCH9")), 400, "INVALID_INPUT");

        assertThat(grown(before, rowCounts(db))).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-33 eight invitations of one new email at once make one account; the rest are 409 EMAIL_TAKEN")
    void concurrentInvitationsOfOneEmail() throws InterruptedException {
        Session admin = fixtures.signInAdmin();
        String email = Unique.email("race");
        int n = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<ApiResponse> responses = new ArrayList<>();
        try {
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String name = "Race " + i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return invite(admin, email, name, "AGENT", List.of());
                }));
            }
            start.countDown();
            for (Future<ApiResponse> f : futures) {
                responses.add(result(f));
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        }

        assertThat(responses).filteredOn(r -> r.status() == 201).hasSize(1);
        assertThat(responses).filteredOn(r -> r.status() != 201)
                .hasSize(n - 1)
                .allSatisfy(r -> assertError(r, 409, "EMAIL_TAKEN"));
        assertThat(pending(admin, email)).hasSize(1);
    }

    static ApiResponse invite(Session admin, String email, String name, String role, List<String> companyCodes) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("name", name);
        body.put("role", role);
        if (!companyCodes.isEmpty()) {
            body.put("companyCodes", companyCodes);
        }
        return admin.client().post("/api/console/invitations").json(body).send();
    }

    private static ApiResponse result(Future<ApiResponse> future) throws InterruptedException {
        try {
            return future.get(90, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AssertionError a) {
                throw a;
            }
            throw new AssertionError("An invitation request failed: " + e.getCause(), e.getCause());
        } catch (TimeoutException e) {
            throw new AssertionError("An invitation request did not finish in 90 seconds", e);
        }
    }
}
