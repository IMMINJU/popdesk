package io.popdesk.project;

import static io.popdesk.project.InvitationTest.invite;
import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertFieldError;
import static io.popdesk.project.ProjectChecks.pending;
import static io.popdesk.project.ProjectChecks.status;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Invitations that are refused: customers without a valid company, and an email that is taken. */
class InvitationRejectTest extends BareAppTest {

    @Test
    @DisplayName("S0-AC-34 a customer invitation without companies is 400 on companyCodes and makes no account")
    void customerNeedsCompany() {
        Session admin = fixtures.signInAdmin();
        String left = Unique.email("nocompany");
        String empty = Unique.email("emptycompany");

        assertFieldError(invite(admin, left, "No company", "CUSTOMER", List.of()), 400, "companyCodes", "REQUIRED");
        ApiResponse emptyList = admin.client().post("/api/console/invitations")
                .json(Map.of("email", empty, "name", "Empty", "role", "CUSTOMER", "companyCodes", List.of()))
                .send();
        assertFieldError(emptyList, 400, "companyCodes", "REQUIRED");

        assertThat(pending(admin, left)).isEmpty();
        assertThat(pending(admin, empty)).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-34 a customer invitation naming a company that does not exist is 400 and makes no account")
    void unknownCompany() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String email = Unique.email("unknowncompany");

        assertFieldError(invite(admin, email, "Unknown", "CUSTOMER", List.of("NOSUCH9")), 400, "companyCodes",
                "UNKNOWN_VALUE");
        assertFieldError(invite(admin, email, "Unknown", "CUSTOMER", List.of(company.code(), "NOSUCH9")), 400,
                "companyCodes", "UNKNOWN_VALUE");

        assertThat(pending(admin, email)).isEmpty();
        invite(admin, email, "Now valid", "CUSTOMER", List.of(company.code())).expectStatus(201);
    }

    @Test
    @DisplayName("S0-AC-34 a staff invitation with companies is 400 NOT_ALLOWED on companyCodes")
    void staffTakeNoCompanies() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String email = Unique.email("staffcompany");

        assertFieldError(invite(admin, email, "Staff", "AGENT", List.of(company.code())), 400, "companyCodes",
                "NOT_ALLOWED");

        assertThat(pending(admin, email)).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-34 inviting the email of an invited account is 409 EMAIL_TAKEN and leaves that account as it was")
    void emailOfInvitedAccount() {
        Session admin = fixtures.signInAdmin();
        Invitation first = fixtures.invite(admin, Role.AGENT);

        assertError(invite(admin, first.email(), "Second name", "ADMIN", List.of()), 409, "EMAIL_TAKEN");

        assertThat(pending(admin, first.email())).singleElement().satisfies(p -> {
            assertThat(p.path("name").asString(null)).isEqualTo(first.name());
            assertThat(p.path("role").asString(null)).isEqualTo("AGENT");
        });
    }

    @Test
    @DisplayName("S0-AC-34 inviting the email of an active account, the first admin included, is 409 EMAIL_TAKEN")
    void emailOfActiveAccount() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());

        assertError(invite(admin, customer.email(), "Again", "CUSTOMER", List.of(company.code())), 409,
                "EMAIL_TAKEN");
        assertError(invite(admin, ADMIN_EMAIL, "Again", "AGENT", List.of()), 409, "EMAIL_TAKEN");

        assertThat(status(admin, customer.accountId())).isEqualTo("ACTIVE");
        assertThat(pending(admin, customer.email())).isEmpty();
    }
}
