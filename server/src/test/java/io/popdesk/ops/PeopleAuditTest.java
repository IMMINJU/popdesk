package io.popdesk.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.ops.AuditTrail.Mark;
import io.popdesk.ops.AuditTrail.Row;
import io.popdesk.project.PeopleSteps;
import io.popdesk.support.ApiResponse;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;

/**
 * Audit rows for what slice 1a lets people do to accounts, invitations, and companies. As in
 * {@link AuditLogTest}, a row is found as the one new row that holds the acting person's email, and
 * each case acts as a person of its own.
 *
 * <p>The names of the kinds are not in the contract; this test fixes them, in the form the first
 * slice used ({@code COMPANY_CREATED}, {@code AGENT_ASSIGNED}).
 */
class PeopleAuditTest extends BareAppTest {

    private static final String NEW_PASSWORD = "audited-Password-2";

    /** What is done; the name is the kind of the audit row. */
    enum Action {
        INVITATION_RESENT,
        INVITATION_CANCELLED,
        ACCOUNT_SUSPENDED,
        ACCOUNT_REACTIVATED,
        ACCOUNT_ROLE_CHANGED,
        ACCOUNT_NAME_CHANGED,
        ACCOUNT_PASSWORD_RESET,
        PASSWORD_RESET_COMPLETED,
        PASSWORD_CHANGED,
        COMPANY_UPDATED,
        COMPANY_STATUS_CHANGED,
        CUSTOMER_ADDED,
        CUSTOMER_REMOVED,
        AGENT_UNASSIGNED
    }

    /**
     * An action that was done after {@code mark}: who did it, the account and the company it was done
     * to (0 or null when there is none), the values before and after, and what must not be in the row.
     */
    private record Done(Mark mark, Instant at, Session actor, long targetAccountId, String targetEmail,
            String targetCompany, List<String> beforeAndAfter, List<String> secrets) {}

    private AuditTrail audit;

    @BeforeEach
    void setUp() {
        audit = new AuditTrail(db);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String accountPath(long accountId) {
        return "/api/console/accounts/" + accountId;
    }

    private static String companyPath(String code) {
        return "/api/console/companies/" + code;
    }

    private String resetLink(Session admin, long accountId) {
        return PeopleSteps.resetPassword(admin, accountId).jsonText("resetLink");
    }

    /** Sets the scene, marks, and does the action as a person of this call's own. */
    private Done perform(Action action) {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        return switch (action) {
            case INVITATION_RESENT -> {
                Invitation invited = fixtures.invite(admin, Role.AGENT);
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                String link = admin.client().post("/api/console/invitations/" + invited.accountId() + "/resend")
                        .expect(200).jsonText("inviteLink");
                yield new Done(mark, at, admin, invited.accountId(), invited.email(), null, List.of(),
                        List.of(link, Fixtures.tokenOf(link), invited.link(), invited.token()));
            }
            case INVITATION_CANCELLED -> {
                Invitation invited = fixtures.invite(admin, Role.AGENT);
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().post("/api/console/invitations/" + invited.accountId() + "/cancel").expect(204);
                yield new Done(mark, at, admin, invited.accountId(), invited.email(), null, List.of(),
                        List.of(invited.link(), invited.token()));
            }
            case ACCOUNT_SUSPENDED -> {
                Session agent = fixtures.agent(admin);
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().post(accountPath(agent.accountId()) + "/suspend").expect(200);
                yield new Done(mark, at, admin, agent.accountId(), agent.email(), null,
                        List.of("ACTIVE", "SUSPENDED"), List.of(Fixtures.PASSWORD));
            }
            case ACCOUNT_REACTIVATED -> {
                Session agent = fixtures.agent(admin);
                admin.client().post(accountPath(agent.accountId()) + "/suspend").expect(200);
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().post(accountPath(agent.accountId()) + "/reactivate").expect(200);
                yield new Done(mark, at, admin, agent.accountId(), agent.email(), null,
                        List.of("SUSPENDED", "ACTIVE"), List.of(Fixtures.PASSWORD));
            }
            case ACCOUNT_ROLE_CHANGED -> {
                Session agent = fixtures.agent(admin);
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().put(accountPath(agent.accountId()) + "/role").json(Map.of("role", "ADMIN"))
                        .expect(200);
                yield new Done(mark, at, admin, agent.accountId(), agent.email(), null, List.of("AGENT", "ADMIN"),
                        List.of(Fixtures.PASSWORD));
            }
            case ACCOUNT_NAME_CHANGED -> {
                Session agent = fixtures.agent(admin);
                String newName = Unique.text("Renamed");
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().patch(accountPath(agent.accountId())).json(Map.of("name", newName)).expect(200);
                yield new Done(mark, at, admin, agent.accountId(), agent.email(), null,
                        List.of(agent.name(), newName), List.of(Fixtures.PASSWORD));
            }
            case ACCOUNT_PASSWORD_RESET -> {
                Session agent = fixtures.agent(admin);
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                String link = resetLink(admin, agent.accountId());
                yield new Done(mark, at, admin, agent.accountId(), agent.email(), null, List.of(),
                        List.of(link, Fixtures.tokenOf(link), Fixtures.PASSWORD));
            }
            case PASSWORD_RESET_COMPLETED -> {
                Session agent = fixtures.agent(admin);
                String link = resetLink(admin, agent.accountId());
                Mark mark = audit.mark(agent.email());
                Instant at = now();
                console().post("/api/public/password-reset/confirm")
                        .json(Map.of("token", Fixtures.tokenOf(link), "password", NEW_PASSWORD)).expect(204);
                yield new Done(mark, at, agent, agent.accountId(), agent.email(), null, List.of(),
                        List.of(link, Fixtures.tokenOf(link), NEW_PASSWORD, Fixtures.PASSWORD));
            }
            case PASSWORD_CHANGED -> {
                Session agent = fixtures.agent(admin);
                Mark mark = audit.mark(agent.email());
                Instant at = now();
                agent.client().put("/api/console/me/password")
                        .json(Map.of("currentPassword", Fixtures.PASSWORD, "newPassword", NEW_PASSWORD)).expect(204);
                yield new Done(mark, at, agent, agent.accountId(), agent.email(), null, List.of(),
                        List.of(NEW_PASSWORD, Fixtures.PASSWORD));
            }
            case COMPANY_UPDATED -> {
                Company company = fixtures.createCompany(admin);
                String newName = Unique.text("Renamed company");
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().patch(companyPath(company.code())).json(Map.of("name", newName)).expect(200);
                yield new Done(mark, at, admin, 0, null, company.code(), List.of(company.name(), newName),
                        List.of());
            }
            case COMPANY_STATUS_CHANGED -> {
                Company company = fixtures.createCompany(admin);
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().put(companyPath(company.code()) + "/status").json(Map.of("status", "SUSPENDED"))
                        .expect(200);
                yield new Done(mark, at, admin, 0, null, company.code(), List.of("ACTIVE", "SUSPENDED"), List.of());
            }
            case CUSTOMER_ADDED -> {
                Company company = fixtures.createCompany(admin);
                Invitation customer = fixtures.invite(admin, Role.CUSTOMER, fixtures.createCompany(admin).code());
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().put(companyPath(company.code()) + "/customers/" + customer.accountId()).expect(200);
                yield new Done(mark, at, admin, customer.accountId(), customer.email(), company.code(), List.of(),
                        List.of());
            }
            case CUSTOMER_REMOVED -> {
                Company company = fixtures.createCompany(admin);
                Invitation customer = fixtures.invite(admin, Role.CUSTOMER, company.code());
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().delete(companyPath(company.code()) + "/customers/" + customer.accountId())
                        .expect(200);
                yield new Done(mark, at, admin, customer.accountId(), customer.email(), company.code(), List.of(),
                        List.of());
            }
            case AGENT_UNASSIGNED -> {
                Company company = fixtures.createCompany(admin);
                Invitation agent = fixtures.invite(admin, Role.AGENT);
                fixtures.assignAgent(admin, company.code(), agent.accountId());
                Mark mark = audit.mark(admin.email());
                Instant at = now();
                admin.client().delete(companyPath(company.code()) + "/agents/" + agent.accountId()).expect(200);
                yield new Done(mark, at, admin, agent.accountId(), agent.email(), company.code(), List.of(),
                        List.of());
            }
        };
    }

    /** The one new row since the mark. */
    private Row onlyNewRow(Done done, Action action) {
        List<Row> rows = audit.newRows(done.mark());
        assertThat(rows).as("new rows holding %s after %s", done.mark().needles(), action).hasSize(1);
        return rows.getFirst();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Action.class)
    @DisplayName("S1A-AC-62 each action leaves one audit row: its kind, the person who did it, what it was done "
            + "to, and the time")
    void oneRowPerAction(Action action) {
        Done done = perform(action);

        Row row = onlyNewRow(done, action);

        assertThat(row.hasText(action.name())).as("kind %s in %s", action, row).isTrue();
        assertThat(row.person(done.actor().accountId(), done.actor().name(), done.actor().email()))
                .as("{accountId, name, email} of %s in %s", done.actor().email(), row).isPresent();
        if (done.targetEmail() != null) {
            assertThat(row.mentions(done.targetAccountId()) || row.contains(done.targetEmail()))
                    .as("account %s in %s", done.targetAccountId(), row).isTrue();
        }
        if (done.targetCompany() != null) {
            assertThat(row.contains(done.targetCompany())).as("company %s in %s", done.targetCompany(), row)
                    .isTrue();
        }
        assertThat(row.times()).as("times in %s", row).contains(done.at());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Action.class, mode = Mode.EXCLUDE,
            names = {"CUSTOMER_ADDED", "CUSTOMER_REMOVED", "AGENT_UNASSIGNED"})
    @DisplayName("S1A-AC-63 the audit row has the values before and after, and no password, token, or link")
    void valuesWithoutSecrets(Action action) {
        Done done = perform(action);

        Row row = onlyNewRow(done, action);

        assertThat(done.beforeAndAfter()).as("values before and after in %s", row).allMatch(row::hasText);
        assertThat(done.secrets()).as("secrets in %s", row).noneMatch(row::contains);
    }

    /** The audit table, quoted: the table that gains the one row of a new company. */
    private String auditTable(Session creator) {
        Mark tableMark = audit.mark(creator.email());
        fixtures.createCompany(creator);
        List<Row> created = audit.newRows(tableMark);
        assertThat(created).as("the audit row of a new company").hasSize(1);
        return "\"" + created.getFirst().table().replace("\"", "\"\"") + "\"";
    }

    @Test
    @DisplayName("S1A-AC-62 asking for a password reset link leaves no audit row")
    void resetRequestLeavesNoRow() {
        Session creator = fixtures.admin(fixtures.signInAdmin());
        String auditTable = auditTable(creator);
        Session agent = fixtures.agent(creator);
        long rowsBefore = db.count("select count(*) from " + auditTable);

        ApiResponse response = console().post("/api/public/password-reset/request")
                .json(Map.of("email", agent.email())).expect(202);

        assertThat(response.body()).as("body of the 202").isEmpty();
        assertThat(db.count("select count(*) from " + auditTable))
                .as("rows of %s after the request", auditTable).isEqualTo(rowsBefore);
    }

    @Test
    @DisplayName("S1A-AC-64 a failed sign-in with an email no account has leaves no audit row at all, with or "
            + "without the email")
    void unknownEmailSignInLeavesNoRow() {
        String auditTable = auditTable(fixtures.admin(fixtures.signInAdmin()));
        long rowsBefore = db.count("select count(*) from " + auditTable);

        console().post("/api/public/sign-in")
                .json(Map.of("email", Unique.email("nobody"), "password", "wrong-Password-1")).expect(401);

        assertThat(db.count("select count(*) from " + auditTable))
                .as("rows of %s after the failed sign-in", auditTable).isEqualTo(rowsBefore);
    }
}
