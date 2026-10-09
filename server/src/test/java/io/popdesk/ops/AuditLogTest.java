package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.AuditTrail.Mark;
import io.popdesk.ops.AuditTrail.Row;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AppTest;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Audit rows for the actions this slice can cause over HTTP: the first admin, a new company, an
 * agent assignment, an invitation, an invitation link, and sign-in success, failure, and lock.
 * The admin-reset, backup, and restore commands are tested by the deploy tab.
 *
 * <p>The audit table and its columns are not fixed by the slice; {@link AuditTrail} finds an
 * action's rows as the new rows that hold the acting person's email. Each test acts as an admin of
 * its own, so its email is in no other row.
 */
class AuditLogTest extends BareAppTest {

    private AuditTrail audit;

    @BeforeEach
    void setUp() {
        audit = new AuditTrail(db);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    /** The one new row since the mark. */
    private Row onlyNewRow(Mark mark, String action) {
        List<Row> rows = audit.newRows(mark);
        assertThat(rows).as("new rows holding %s after %s", mark.needles(), action).hasSize(1);
        return rows.getFirst();
    }

    private static void assertActor(Row row, Session who) {
        assertThat(row.person(who.accountId(), who.name(), who.email()))
                .as("{accountId, name, email} of %s in %s", who.email(), row)
                .isPresent();
    }

    @Test
    @DisplayName("S0-AC-83 the first admin has one audit row whose person is the system, account 0")
    void firstAdminRow() {
        Session admin = fixtures.signInAdmin();

        List<Row> rows = audit.rowsContaining(AppTest.ADMIN_EMAIL).stream()
                .filter(r -> r.people().stream().anyMatch(p -> p.path("accountId").isIntegralNumber()
                        && p.path("accountId").asLong() == 0))
                .toList();

        assertThat(rows).as("rows holding %s with person accountId 0", AppTest.ADMIN_EMAIL).hasSize(1);
        Row row = rows.getFirst();
        assertThat(row.mentions(admin.accountId()) || row.contains(admin.email()))
                .as("the new admin is the target of %s", row).isTrue();
        assertThat(row.times()).as("times in %s", row).isNotEmpty();
        assertThat(row.contains(AppTest.ADMIN_PASSWORD)).as("password in %s", row).isFalse();
    }

    @Test
    @DisplayName("S0-AC-83 creating a company leaves one row: the admin, the company, its values, the time")
    void companyCreated() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Mark mark = audit.mark(admin.email());
        String code = Unique.companyCode();
        String name = Unique.text("Audited");
        String legalName = Unique.text("Audited Ltd.");
        Instant at = now();

        fixtures.createCompany(admin, code, name, legalName);

        Row row = onlyNewRow(mark, "creating company " + code);
        assertActor(row, admin);
        assertThat(row.contains(code)).as("company code in %s", row).isTrue();
        assertThat(row.contains(name)).as("name in %s", row).isTrue();
        assertThat(row.contains(legalName)).as("legal name in %s", row).isTrue();
        assertThat(row.times()).as("times in %s", row).contains(at);
    }

    @Test
    @DisplayName("S0-AC-83 assigning an agent leaves one row: the admin, the company and the agent, the time")
    void agentAssigned() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Company company = fixtures.createCompany(admin);
        Invitation agent = fixtures.invite(admin, Role.AGENT);
        Mark mark = audit.mark(admin.email());
        Instant at = now();

        fixtures.assignAgent(admin, company.code(), agent.accountId());

        Row row = onlyNewRow(mark, "assigning agent " + agent.accountId());
        assertActor(row, admin);
        assertThat(row.contains(company.code())).as("company code in %s", row).isTrue();
        assertThat(row.mentions(agent.accountId()) || row.contains(agent.email()))
                .as("agent %s in %s", agent.accountId(), row).isTrue();
        assertThat(row.times()).as("times in %s", row).contains(at);
    }

    @Test
    @DisplayName("S0-AC-83 an invitation leaves one row with the invited person, without the link or token")
    void invitationCreated() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Company company = fixtures.createCompany(admin);
        String email = Unique.email("audited");
        String name = Unique.text("Audited person");
        Mark mark = audit.mark(admin.email());
        Instant at = now();

        Invitation invitation = fixtures.invite(admin, Role.CUSTOMER, email, name, List.of(company.code()));

        Row row = onlyNewRow(mark, "inviting " + email);
        assertActor(row, admin);
        assertThat(row.contains(email)).as("invited email in %s", row).isTrue();
        assertThat(row.contains(company.code())).as("company in %s", row).isTrue();
        assertThat(row.times()).as("times in %s", row).contains(at);
        assertThat(row.contains(invitation.token())).as("token in %s", row).isFalse();
        assertThat(row.contains(invitation.link())).as("link in %s", row).isFalse();
    }

    @Test
    @DisplayName("S0-AC-83 copying an invitation link leaves one row without the new or the old link and token")
    void invitationLinkCreated() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Invitation invitation = fixtures.invite(admin, Role.AGENT);
        Mark mark = audit.mark(admin.email());
        Instant at = now();

        ApiResponse copied = admin.client().post("/api/console/invitations/" + invitation.accountId() + "/link")
                .expect(200);
        String link = copied.jsonText("inviteLink");
        String token = Fixtures.tokenOf(link);

        Row row = onlyNewRow(mark, "copying the link of " + invitation.accountId());
        assertActor(row, admin);
        assertThat(row.mentions(invitation.accountId()) || row.contains(invitation.email()))
                .as("invited account in %s", row).isTrue();
        assertThat(row.times()).as("times in %s", row).contains(at);
        for (String secret : List.of(link, token, invitation.link(), invitation.token())) {
            assertThat(row.contains(secret)).as("%s in %s", secret, row).isFalse();
        }
    }

    @Test
    @DisplayName("S0-AC-83 a sign-in leaves one row whose person is the one who signed in, without the password")
    void signInSucceeded() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        Mark mark = audit.mark(agent.email());
        Instant at = now();

        fixtures.signInAgain(agent);

        Row row = onlyNewRow(mark, "signing in " + agent.email());
        assertActor(row, agent);
        assertThat(row.times()).as("times in %s", row).contains(at);
        assertThat(row.contains(Fixtures.PASSWORD)).as("password in %s", row).isFalse();
    }

    @Test
    @DisplayName("S0-AC-83 a failed sign-in leaves its own row, without the password that was tried")
    void signInFailed() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String wrong = "wrong-" + Unique.number() + "-Password";
        Mark mark = audit.mark(agent.email());
        Instant at = now();

        console().post("/api/public/sign-in").json(Map.of("email", agent.email(), "password", wrong)).expect(401);

        Row row = onlyNewRow(mark, "a failed sign-in of " + agent.email());
        assertThat(row.times()).as("times in %s", row).contains(at);
        assertThat(row.contains(wrong)).as("tried password in %s", row).isFalse();
        assertThat(row.contains(Fixtures.PASSWORD)).as("password in %s", row).isFalse();
    }

    @Test
    @DisplayName("S0-AC-83 five failed sign-ins leave five failure rows and one lock row")
    void lockLeavesItsOwnRow() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String wrong = "wrong-" + Unique.number() + "-Password";
        Mark mark = audit.mark(agent.email());
        ApiClient browser = console();

        for (int i = 0; i < 5; i++) {
            browser.post("/api/public/sign-in").json(Map.of("email", agent.email(), "password", wrong)).expect(401);
        }

        List<Row> rows = audit.newRows(mark);
        assertThat(rows).as("new rows holding %s after five failed sign-ins", agent.email()).hasSize(6);
        assertThat(rows).as("rows holding the tried password").noneMatch(r -> r.contains(wrong));
    }

    @Test
    @DisplayName("S0-AC-83 a company that was not created (code taken, 409) leaves no audit row")
    void noRowForRejectedCompany() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Company taken = fixtures.createCompany(admin);
        String legalName = Unique.text("Never created Ltd.");

        ApiResponse response = admin.client().post("/api/console/companies")
                .json(Map.of("code", taken.code(), "name", Unique.text("Never"), "legalName", legalName))
                .send();

        OpsChecks.assertError(response, 409, "COMPANY_CODE_TAKEN");
        assertThat(audit.rowsContaining(legalName)).as("rows holding %s", legalName).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-83 an invitation that was not made (email taken, 409) leaves no audit row")
    void noRowForRejectedInvitation() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Invitation first = fixtures.invite(admin, Role.AGENT);
        String name = Unique.text("Never invited");

        ApiResponse response = admin.client().post("/api/console/invitations")
                .json(Map.of("email", first.email(), "name", name, "role", "AGENT"))
                .send();

        OpsChecks.assertError(response, 409, "EMAIL_TAKEN");
        assertThat(audit.rowsContaining(name)).as("rows holding %s", name).isEmpty();
    }
}
