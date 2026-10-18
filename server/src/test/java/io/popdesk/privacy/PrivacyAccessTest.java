package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The two paths of slice 10a are for admins only (S10A-AC-25, ADR D-43): the export ({@code GET} and {@code HEAD})
 * and the anonymization. Each refusal is checked to change nothing, and then the same call by an admin succeeds, so
 * that the refusal is the role check and not a path that answers nobody. It runs in {@link PrivacyAppTest} because a
 * wrong answer would anonymize an account.
 */
class PrivacyAccessTest extends PrivacyAppTest {

    /** A call of the slice, on an account ID. */
    enum Call {
        EXPORT, HEAD, ANONYMIZE;

        ApiRequest on(ApiClient client, long accountId) {
            return switch (this) {
                case EXPORT -> client.get(ExportSteps.path(accountId));
                case HEAD -> client.head(ExportSteps.path(accountId));
                case ANONYMIZE -> client.post(PrivacySteps.ACCOUNTS + "/" + accountId + "/anonymize");
            };
        }

        boolean hasBody() {
            return this != HEAD;
        }
    }

    private static void assertRefused(ApiResponse answer, Call call, int status, String code) {
        assertThat(answer.status()).as("%s: %s", answer, answer.snippet()).isEqualTo(status);
        if (call.hasBody()) {
            assertThat(answer.errorCode()).as("code of %s", answer.snippet()).isEqualTo(code);
        }
    }

    /** The account is as it was: not anonymized, its email, and no export recorded. */
    private static void assertUntouched(Session admin, Session target) {
        JsonNode account = PrivacySteps.account(admin, target.accountId(), target.email());
        assertThat(account.path("anonymized").asBoolean(true)).as("anonymized of %s", account).isFalse();
        assertThat(account.path("name").asString(null)).as("name of %s", account).isEqualTo(target.name());
        assertThat(PrivacySteps.entries(admin, "action", "ACCOUNT_EXPORTED", "targetAccountId", target.accountId()))
                .as("ACCOUNT_EXPORTED entries of the target").isEmpty();
    }

    /** The admin's same call answers 200. */
    private static void assertAdminSucceeds(Session admin, Call call, long accountId) {
        ApiResponse answer = call.on(admin.client(), accountId).send();
        assertThat(answer.status()).as("the admin's %s: %s", answer, answer.snippet()).isEqualTo(200);
    }

    @ParameterizedTest
    @EnumSource(Call.class)
    @DisplayName("S10A-AC-25 an agent, assigned to the target's company, gets 403 FORBIDDEN on the export, its HEAD, "
            + "and the anonymization, and nothing changes; the admin's same call answers 200")
    void agentsAreForbidden(Call call) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Session target = fixtures.customer(admin, company.code());

        ApiResponse answer = call.on(agent.client(), target.accountId()).send();

        assertRefused(answer, call, 403, "FORBIDDEN");
        assertUntouched(admin, target);
        assertAdminSucceeds(admin, call, target.accountId());
    }

    @ParameterizedTest
    @EnumSource(Call.class)
    @DisplayName("S10A-AC-25 signed out, the export, its HEAD, and the anonymization answer 401 UNAUTHENTICATED on "
            + "the console host and nothing changes; the admin's same call answers 200")
    void signedOutGets401(Call call) {
        Session admin = fixtures.signInAdmin();
        Session target = fixtures.customer(admin, fixtures.createCompany(admin).code());

        ApiResponse answer = call.on(console(), target.accountId()).send();

        assertRefused(answer, call, 401, "UNAUTHENTICATED");
        assertUntouched(admin, target);
        assertAdminSucceeds(admin, call, target.accountId());
    }

    @ParameterizedTest
    @EnumSource(Call.class)
    @DisplayName("S10A-AC-25 a customer on the portal host gets 404 NOT_FOUND for the console paths (D-43) and "
            + "nothing changes; the admin's same call answers 200")
    void customerHostGets404(Call call) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session target = fixtures.customer(admin, company.code());

        ApiResponse own = call.on(customer.client(), customer.accountId()).send();
        ApiResponse other = call.on(customer.client(), target.accountId()).send();

        assertRefused(own, call, 404, "NOT_FOUND");
        assertRefused(other, call, 404, "NOT_FOUND");
        assertUntouched(admin, target);
        assertAdminSucceeds(admin, call, target.accountId());
    }

    @Test
    @DisplayName("S10A-AC-25 an admin's anonymization without the CSRF token, or with a wrong one, is 403 "
            + "CSRF_TOKEN_INVALID and changes nothing; with the token it answers 200")
    void anonymizationNeedsTheCsrfToken() {
        Session admin = fixtures.signInAdmin();
        Session target = fixtures.customer(admin, fixtures.createCompany(admin).code());

        ApiResponse without = Call.ANONYMIZE.on(admin.client(), target.accountId()).withoutCsrf().send();
        ApiResponse wrong = Call.ANONYMIZE.on(admin.client(), target.accountId()).csrfToken("wrong").send();

        assertRefused(without, Call.ANONYMIZE, 403, "CSRF_TOKEN_INVALID");
        assertRefused(wrong, Call.ANONYMIZE, 403, "CSRF_TOKEN_INVALID");
        assertUntouched(admin, target);
        assertAdminSucceeds(admin, Call.ANONYMIZE, target.accountId());
    }

    @ParameterizedTest
    @CsvSource({"EXPORT, 999999999", "HEAD, 999999999", "ANONYMIZE, 999999999", "EXPORT, 0", "HEAD, 0",
        "ANONYMIZE, 0"})
    @DisplayName("S10A-AC-25 an account ID nobody has and account 0 (popdesk itself) are 404 NOT_FOUND for the "
            + "export, its HEAD, and the anonymization; an account that exists answers 200")
    void unknownAccountsAreNotFound(Call call, long accountId) {
        Session admin = fixtures.signInAdmin();
        Session target = fixtures.customer(admin, fixtures.createCompany(admin).code());

        ApiResponse answer = call.on(admin.client(), accountId).send();

        assertRefused(answer, call, 404, "NOT_FOUND");
        assertAdminSucceeds(admin, call, target.accountId());
    }
}
