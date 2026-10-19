package io.popdesk.auth;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.privacy.PrivacySteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Only an admin removes an OIDC link (S11-AC-28, ADR D-43): an agent gets 403, no session 401, the portal host 404,
 * a write without the CSRF token 403, an account nobody has 404, and an anonymized account, which has no link, 404.
 * Each refusal is of a linked account and leaves it linked; then the admin's same call answers 204, so that the
 * refusal is the check and not a path that answers nobody.
 */
class OidcUnlinkAccessTest extends OidcAppTest {

    private static void assertRefused(ApiResponse answer, int status, String code) {
        assertThat(answer.status()).as("%s: %s", answer, answer.snippet()).isEqualTo(status);
        assertThat(answer.errorCode()).as("code of %s", answer.snippet()).isEqualTo(code);
    }

    /** The account is still linked, and the admin's unlinking then answers 204. */
    private void assertStillLinkedThenUnlinked(Session admin, long accountId) {
        assertThat(linked(admin, accountId)).as("oidcLinked after the refusal").isTrue();
        ApiResponse answer = unlink(admin.client(), accountId).send();
        assertThat(answer.status()).as("the admin's %s: %s", answer, answer.snippet()).isEqualTo(204);
    }

    @Test
    @DisplayName("S11-AC-28 an agent, assigned to the customer's company, gets 403 FORBIDDEN for unlinking the "
            + "customer and themselves, and both stay linked")
    void agentIsForbidden() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Session customer = fixtures.customer(admin, company.code());
        linkByOidc(customer);
        linkByOidc(agent);

        assertRefused(unlink(agent.client(), customer.accountId()).send(), 403, "FORBIDDEN");
        assertRefused(unlink(agent.client(), agent.accountId()).send(), 403, "FORBIDDEN");

        assertStillLinkedThenUnlinked(admin, customer.accountId());
        assertStillLinkedThenUnlinked(admin, agent.accountId());
    }

    @Test
    @DisplayName("S11-AC-28 without a session unlinking is 401 UNAUTHENTICATED on the console host, and the account "
            + "stays linked")
    void signedOutGets401() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        linkByOidc(agent);

        assertRefused(unlink(console(), agent.accountId()).send(), 401, "UNAUTHENTICATED");

        assertStillLinkedThenUnlinked(admin, agent.accountId());
    }

    @Test
    @DisplayName("S11-AC-28 a customer on the portal host gets 404 NOT_FOUND for the console path, for their own "
            + "account and another's, and both stay linked")
    void portalHostGets404() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session other = fixtures.customer(admin, company.code());
        linkByOidc(customer);
        linkByOidc(other);

        assertRefused(unlink(customer.client(), customer.accountId()).send(), 404, "NOT_FOUND");
        assertRefused(unlink(customer.client(), other.accountId()).send(), 404, "NOT_FOUND");

        assertStillLinkedThenUnlinked(admin, customer.accountId());
        assertStillLinkedThenUnlinked(admin, other.accountId());
    }

    @Test
    @DisplayName("S11-AC-28 an admin's unlinking without the CSRF token, or with a wrong one, is 403 "
            + "CSRF_TOKEN_INVALID and the account stays linked")
    void unlinkingNeedsTheCsrfToken() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        linkByOidc(agent);

        assertRefused(unlink(admin.client(), agent.accountId()).withoutCsrf().send(), 403, "CSRF_TOKEN_INVALID");
        assertRefused(unlink(admin.client(), agent.accountId()).csrfToken("wrong").send(), 403,
                "CSRF_TOKEN_INVALID");

        assertStillLinkedThenUnlinked(admin, agent.accountId());
    }

    @ParameterizedTest
    @ValueSource(longs = {999999999L, 0L})
    @DisplayName("S11-AC-28 an account ID nobody has and account 0 (popdesk itself) are 404 NOT_FOUND, while a "
            + "linked account answers 204")
    void unknownAccountIsNotFound(long accountId) {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        linkByOidc(agent);

        assertRefused(unlink(admin.client(), accountId).send(), 404, "NOT_FOUND");

        assertStillLinkedThenUnlinked(admin, agent.accountId());
    }

    @Test
    @DisplayName("S11-AC-28, S11-AC-29 an account linked and then anonymized has no link: unlinking it is 404 "
            + "NOT_FOUND")
    void anonymizedAccountIsNotFound() {
        Session admin = fixtures.signInAdmin();
        Session agent = person(admin, Role.AGENT);
        linkByOidc(agent);
        PrivacySteps.anonymize(admin, agent.accountId());

        assertRefused(unlink(admin.client(), agent.accountId()).send(), 404, "NOT_FOUND");
    }
}
