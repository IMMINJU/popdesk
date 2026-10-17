package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Who may use the five admin paths of the branding (S9-AC-35): admins only. An agent gets 403 before the request's
 * values are looked at, a customer gets what any console path gives them (404 on the portal host, 401 on the console
 * host), a caller signed out 401, and a write without the CSRF token 403; none of them changes the branding or a
 * logo.
 */
class BrandingAccessTest extends BrandingAppTest {

    /** The five admin calls, as {@code client -> request}; {@code version} is the version to send. */
    private static List<Function<ApiClient, ApiRequest>> adminCalls(long version) {
        return List.of(
                c -> c.get(BRANDING),
                c -> c.put(BRANDING).json(body(version, uniqueName(), "#6D28D9")),
                c -> c.get(ACCENT),
                c -> c.put(LOGOS + "dark").file("file", "logo.png", "image/png", PhotoSteps.png(64, 32, 9)),
                c -> c.delete(LOGOS + "light"));
    }

    /**
     * The branding with a light logo and no dark one, as the starting point of the test. The admin is one the test
     * made, so the upload counts toward no other test's logo limit (per admin and minute, and the app clock stands still).
     */
    private static JsonNode startingPoint(Session admin) {
        deleteLogos(admin);
        save(admin, uniqueName(), EMERALD);
        admin.client().put(LOGOS + "light").file("file", "logo.png", "image/png", PhotoSteps.png(64, 32, 3))
                .expect(200);
        return branding(admin);
    }

    @Test
    @DisplayName("S9-AC-35 an agent gets 403 FORBIDDEN on the five admin paths, the preview without color too, and "
            + "the branding and its logos stay as they were")
    void agentsAreForbidden() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Session agent = fixtures.agent(admin, fixtures.createCompany(admin).code());
        JsonNode before = startingPoint(admin);

        List<String> wrong = new ArrayList<>();
        for (Function<ApiClient, ApiRequest> call : adminCalls(version(before))) {
            ApiResponse answer = call.apply(agent.client()).send();
            if (answer.status() != 403 || !"FORBIDDEN".equals(answer.errorCode())) {
                wrong.add(answer.request() + " -> " + answer.status() + " " + answer.snippet());
            }
        }

        assertThat(wrong).as("agent calls not refused with 403 FORBIDDEN").isEmpty();
        assertThat(canonical(branding(admin))).as("the branding after the agent's calls").isEqualTo(canonical(before));
    }

    @Test
    @DisplayName("S9-AC-35 a customer gets what any console path gives them on the five admin paths: 404 on the "
            + "portal host, 401 on the console host, and the branding stays as it was")
    void customerGetsTheConsoleAnswer() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Session customer = fixtures.customer(admin, fixtures.createCompany(admin).code());
        JsonNode before = startingPoint(admin);
        ApiClient carried = console();
        customer.client().cookies().forEach(carried::setCookie);

        for (ApiClient client : List.of(customer.client(), carried)) {
            ApiResponse existing = client.get("/api/console/companies").send();
            for (Function<ApiClient, ApiRequest> call : adminCalls(version(before))) {
                ApiResponse answer = call.apply(client).send();
                assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet())
                        .isEqualTo(existing.status());
                assertThat(answer.errorCode()).as("code of " + answer.snippet()).isEqualTo(existing.errorCode());
            }
        }

        assertThat(customer.client().get(BRANDING).send().status()).as("on the portal host").isEqualTo(404);
        assertThat(carried.get(BRANDING).send().status()).as("on the console host").isEqualTo(401);
        assertThat(canonical(branding(admin))).as("the branding after the customer's calls")
                .isEqualTo(canonical(before));
    }

    @Test
    @DisplayName("S9-AC-35 signed out, the five admin paths answer 401 on the console host")
    void signedOutGets401() {
        ApiClient browser = console();
        String expected = browser.get("/api/console/companies").send().errorCode();

        for (Function<ApiClient, ApiRequest> call : adminCalls(1)) {
            ApiResponse answer = call.apply(browser).send();
            assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet()).isEqualTo(401);
            assertThat(answer.errorCode()).as("code of " + answer.snippet()).isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("S9-AC-35 an admin's write to the branding or a logo without the CSRF token is 403 "
            + "CSRF_TOKEN_INVALID and changes nothing")
    void writesNeedTheCsrfToken() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        JsonNode before = startingPoint(admin);

        List<ApiResponse> answers = List.of(
                admin.client().put(BRANDING).withoutCsrf().json(body(version(before), uniqueName(), null)).send(),
                admin.client().put(LOGOS + "dark").withoutCsrf()
                        .file("file", "logo.png", "image/png", PhotoSteps.png(64, 32, 5)).send(),
                admin.client().delete(LOGOS + "light").withoutCsrf().send());

        for (ApiResponse answer : answers) {
            assertRefused(answer, 403, "CSRF_TOKEN_INVALID");
        }
        assertThat(canonical(branding(admin))).as("the branding after the writes").isEqualTo(canonical(before));
    }
}
