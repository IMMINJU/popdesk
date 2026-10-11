package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.canonical;
import static io.popdesk.audit.AuditSteps.export;
import static io.popdesk.audit.AuditSteps.json;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.audit.AuditSteps.pages;
import static io.popdesk.audit.AuditSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.project.PeopleSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The changed values of an audit entry as the log gives them: {@code {from, to}} per field for the
 * kinds that change a value, in the right direction, and no password, token, or link anywhere.
 */
class AuditChangesTest extends BareAppTest {

    private static final String NEW_PASSWORD = "audited-Password-3";

    /** A change of a value. */
    enum Change {
        ACCOUNT_NAME,
        ACCOUNT_ROLE,
        ACCOUNT_SUSPENDED,
        ACCOUNT_REACTIVATED,
        COMPANY_NAME,
        COMPANY_NAME_AND_LEGAL_NAME,
        COMPANY_STATUS
    }

    /** What was done: the kind, the query that finds its entry, and the changed values it must hold. */
    private record Done(String action, List<Object> target, Map<String, Object> changes) {}

    private static Map<String, Object> change(Object from, Object to) {
        return Map.of("from", from, "to", to);
    }

    private static String accountPath(long accountId) {
        return "/api/console/accounts/" + accountId;
    }

    private static String companyPath(String code) {
        return "/api/console/companies/" + code;
    }

    private Done perform(Session admin, Change change) {
        return switch (change) {
            case ACCOUNT_NAME -> {
                Session agent = fixtures.agent(admin);
                String newName = Unique.text("Renamed");
                admin.client().patch(accountPath(agent.accountId())).json(Map.of("name", newName)).expect(200);
                yield new Done("ACCOUNT_NAME_CHANGED", List.of("targetAccountId", agent.accountId()),
                        Map.of("name", change(agent.name(), newName)));
            }
            case ACCOUNT_ROLE -> {
                Session agent = fixtures.agent(admin);
                admin.client().put(accountPath(agent.accountId()) + "/role").json(Map.of("role", "ADMIN"))
                        .expect(200);
                yield new Done("ACCOUNT_ROLE_CHANGED", List.of("targetAccountId", agent.accountId()),
                        Map.of("role", change("AGENT", "ADMIN")));
            }
            case ACCOUNT_SUSPENDED -> {
                Session agent = fixtures.agent(admin);
                admin.client().post(accountPath(agent.accountId()) + "/suspend").expect(200);
                yield new Done("ACCOUNT_SUSPENDED", List.of("targetAccountId", agent.accountId()),
                        Map.of("status", change("ACTIVE", "SUSPENDED")));
            }
            case ACCOUNT_REACTIVATED -> {
                Session agent = fixtures.agent(admin);
                admin.client().post(accountPath(agent.accountId()) + "/suspend").expect(200);
                admin.client().post(accountPath(agent.accountId()) + "/reactivate").expect(200);
                yield new Done("ACCOUNT_REACTIVATED", List.of("targetAccountId", agent.accountId()),
                        Map.of("status", change("SUSPENDED", "ACTIVE")));
            }
            case COMPANY_NAME -> {
                Company company = fixtures.createCompany(admin);
                String newName = Unique.text("Renamed company");
                admin.client().patch(companyPath(company.code())).json(Map.of("name", newName)).expect(200);
                yield new Done("COMPANY_UPDATED", List.of("targetCode", company.code()),
                        Map.of("name", change(company.name(), newName)));
            }
            case COMPANY_NAME_AND_LEGAL_NAME -> {
                Company company = fixtures.createCompany(admin);
                String newName = Unique.text("Renamed company");
                String newLegalName = Unique.text("Renamed company Ltd.");
                admin.client().patch(companyPath(company.code()))
                        .json(Map.of("name", newName, "legalName", newLegalName)).expect(200);
                yield new Done("COMPANY_UPDATED", List.of("targetCode", company.code()),
                        Map.of("name", change(company.name(), newName),
                                "legalName", change(company.legalName(), newLegalName)));
            }
            case COMPANY_STATUS -> {
                Company company = fixtures.createCompany(admin);
                admin.client().put(companyPath(company.code()) + "/status").json(Map.of("status", "SUSPENDED"))
                        .expect(200);
                yield new Done("COMPANY_STATUS_CHANGED", List.of("targetCode", company.code()),
                        Map.of("status", change("ACTIVE", "SUSPENDED")));
            }
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Change.class)
    @DisplayName("S1B-AC-07 a kind that changes a value gives each changed field as {from, to}: the value "
            + "before in from, the value after in to, and no other field")
    void changedValuesHaveFromAndTo(Change change) {
        Session admin = fixtures.admin(fixtures.signInAdmin());

        Done done = perform(admin, change);

        List<Object> query = new ArrayList<>(done.target());
        query.addAll(List.of("action", done.action()));
        JsonNode entry = onlyEntry(admin, query.toArray());
        assertThat(canonical(entry.path("changes"))).as("changes of %s", entry)
                .isEqualTo(canonical(json(done.changes())));
    }

    @Test
    @DisplayName("S1B-AC-07 after an invitation, a copied invitation link, a password reset by an admin, a new "
            + "password set with the reset link, and a request for a reset link, the account's entries hold no "
            + "password, token, or link, in the log and in both export formats")
    void noEntryHoldsAPasswordTokenOrLink() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Invitation invited = fixtures.invite(admin, Role.AGENT);
        String copied = admin.client().post("/api/console/invitations/" + invited.accountId() + "/link")
                .expect(200).jsonText("inviteLink");
        fixtures.accept(new Invitation(invited.accountId(), invited.email(), invited.name(), invited.role(),
                invited.companyCodes(), copied, Fixtures.tokenOf(copied)));
        String resetLink = PeopleSteps.resetPassword(admin, invited.accountId()).jsonText("resetLink");
        console().post("/api/public/password-reset/confirm")
                .json(Map.of("token", Fixtures.tokenOf(resetLink), "password", NEW_PASSWORD)).expect(204);
        console().post("/api/public/password-reset/request").json(Map.of("email", invited.email())).expect(202);
        List<String> secrets = List.of(invited.link(), invited.token(), copied, Fixtures.tokenOf(copied), resetLink,
                Fixtures.tokenOf(resetLink), NEW_PASSWORD, Fixtures.PASSWORD);

        List<ApiResponse> log = pages(admin, "targetAccountId", invited.accountId());
        ApiResponse jsonLines = export(admin, "JSONL", "targetAccountId", invited.accountId()).expectStatus(200);
        ApiResponse csv = export(admin, "CSV", "targetAccountId", invited.accountId()).expectStatus(200);

        List<String> actions = new ArrayList<>();
        log.forEach(page -> page.json().path("items").forEach(e -> actions.add(text(e, "action"))));
        assertThat(actions).as("kinds of the account's entries").contains("INVITATION_CREATED",
                "INVITATION_LINK_CREATED", "ACCOUNT_PASSWORD_RESET", "PASSWORD_RESET_COMPLETED");
        assertThat(log).as("pages of the log").allSatisfy(page -> assertThat(page.text()).as(page.request())
                .doesNotContain(secrets));
        assertThat(jsonLines.text()).as("the JSON Lines export").contains("INVITATION_LINK_CREATED")
                .doesNotContain(secrets);
        assertThat(csv.text()).as("the CSV export").contains("INVITATION_LINK_CREATED").doesNotContain(secrets);
    }
}
