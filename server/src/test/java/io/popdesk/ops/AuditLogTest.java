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
import java.util.ArrayList;
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

    private static void assertKind(Row row, String kind) {
        assertThat(row.hasText(kind)).as("kind %s in %s", kind, row).isTrue();
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
        assertKind(row, "FIRST_ADMIN_CREATED");
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
        assertKind(row, "COMPANY_CREATED");
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
        assertKind(row, "AGENT_ASSIGNED");
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
        assertKind(row, "INVITATION_CREATED");
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
        assertKind(row, "INVITATION_LINK_CREATED");
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
        assertKind(row, "SIGN_IN_SUCCEEDED");
        assertActor(row, agent);
        assertThat(row.times()).as("times in %s", row).contains(at);
        assertThat(row.contains(Fixtures.PASSWORD)).as("password in %s", row).isFalse();
    }

    @Test
    @DisplayName("S0-AC-83 a failed sign-in leaves its own row with that account as the person, without the "
            + "password that was tried")
    void signInFailed() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String wrong = "wrong-" + Unique.number() + "-Password";
        Mark mark = audit.mark(agent.email());
        Instant at = now();

        console().post("/api/public/sign-in").json(Map.of("email", agent.email(), "password", wrong)).expect(401);

        Row row = onlyNewRow(mark, "a failed sign-in of " + agent.email());
        assertKind(row, "SIGN_IN_FAILED");
        assertActor(row, agent);
        assertThat(row.times()).as("times in %s", row).contains(at);
        assertThat(row.contains(wrong)).as("tried password in %s", row).isFalse();
        assertThat(row.contains(Fixtures.PASSWORD)).as("password in %s", row).isFalse();
    }

    @Test
    @DisplayName("S0-AC-83 five failed sign-ins leave five SIGN_IN_FAILED rows and one ACCOUNT_LOCKED row")
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
        assertThat(rows).as("SIGN_IN_FAILED rows").filteredOn(r -> r.hasText("SIGN_IN_FAILED")).hasSize(5);
        assertThat(rows).as("ACCOUNT_LOCKED rows").filteredOn(r -> r.hasText("ACCOUNT_LOCKED")).hasSize(1);
    }

    @Test
    @DisplayName("S0-AC-83 a failed sign-in with an email no account has leaves no audit row")
    void unknownEmailLeavesNoRow() {
        fixtures.signInAdmin();
        String email = Unique.email("nobody");
        Mark mark = audit.mark(email);

        console().post("/api/public/sign-in").json(Map.of("email", email, "password", "wrong-Password-1"))
                .expect(401);

        assertThat(audit.newRows(mark)).as("new rows holding %s", email).isEmpty();
        assertThat(audit.rowsContaining(email)).as("rows holding %s", email).isEmpty();
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

    /**
     * The audit table: the table of the one new row a company creation leaves. Creates a company with
     * a new admin to find it.
     */
    private String auditTable() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Mark mark = audit.mark(admin.email());
        fixtures.createCompany(admin);
        return onlyNewRow(mark, "creating a company to find the audit table").table();
    }

    /**
     * Makes every INSERT into {@code tables} fail whose row, as JSON text, holds {@code marker}: a
     * {@code test_} trigger (tasks.md section 6). Returns the statements that drop it again.
     */
    private List<String> failInserts(List<String> tables, String marker) {
        String function = quote(db.schema()) + ".test_audit_fail_" + Unique.number();
        db.schemaFixture("create function " + function + "() returns trigger language plpgsql as $$ begin "
                + "if position(" + literal(marker) + " in row_to_json(NEW)::text) > 0 then "
                + "raise exception 'test_ trigger: injected failure'; end if; return NEW; end $$");
        List<String> drops = new ArrayList<>();
        for (String table : tables) {
            String trigger = "test_audit_fail_" + Unique.number();
            db.schemaFixture("create trigger " + trigger + " before insert on " + quote(table) + " for each row "
                    + "execute function " + function + "()");
            drops.add("drop trigger if exists " + trigger + " on " + quote(table));
        }
        drops.add("drop function if exists " + function + "()");
        return drops;
    }

    private void dropAll(List<String> drops) {
        drops.forEach(db::schemaFixture);
    }

    @Test
    @DisplayName("S0-AC-83 when writing the audit row fails, the company is not created either: 500, and the code "
            + "is free")
    void auditFailureUndoesTheChange() {
        String table = auditTable();
        Session admin = fixtures.admin(fixtures.signInAdmin());
        String code = Unique.companyCode();
        String legalName = Unique.text("Unaudited Ltd.");
        List<String> drops = failInserts(List.of(table), legalName);
        try {
            ApiResponse response = admin.client().post("/api/console/companies")
                    .json(Map.of("code", code, "name", Unique.text("Unaudited"), "legalName", legalName)).send();

            OpsChecks.assertError(response, 500, "INTERNAL_ERROR");
        } finally {
            dropAll(drops);
        }
        OpsChecks.assertError(admin.client().get("/api/console/companies/" + code).send(), 404, "NOT_FOUND");
        assertThat(audit.rowsContaining(legalName)).as("rows holding %s", legalName).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-83 when writing the company fails after the checks, no audit row is left: 500, and no row "
            + "holds the company")
    void changeFailureLeavesNoAuditRow() {
        String auditTable = auditTable();
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Company probe = fixtures.createCompany(admin);
        List<String> companyTables = audit.rowsContaining(probe.code()).stream().map(Row::table)
                .filter(t -> !t.equals(auditTable)).distinct().toList();
        assertThat(companyTables).as("tables holding company %s besides the audit table", probe.code())
                .isNotEmpty();
        String code = Unique.companyCode();
        String legalName = Unique.text("Unwritten Ltd.");
        List<String> drops = failInserts(companyTables, legalName);
        try {
            ApiResponse response = admin.client().post("/api/console/companies")
                    .json(Map.of("code", code, "name", Unique.text("Unwritten"), "legalName", legalName)).send();

            OpsChecks.assertError(response, 500, "INTERNAL_ERROR");
        } finally {
            dropAll(drops);
        }
        assertThat(audit.rowsContaining(legalName)).as("rows holding %s", legalName).isEmpty();
        assertThat(audit.rowsContaining(code)).as("rows holding %s", code).isEmpty();
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
