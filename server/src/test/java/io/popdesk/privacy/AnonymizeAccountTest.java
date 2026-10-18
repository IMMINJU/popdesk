package io.popdesk.privacy;

import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.privacy.PrivacySteps.anonymize;
import static io.popdesk.privacy.PrivacySteps.anonymizeRequest;
import static io.popdesk.privacy.PrivacySteps.anonymizedAccount;
import static io.popdesk.privacy.PrivacySteps.anonymousEmail;
import static io.popdesk.privacy.PrivacySteps.entries;
import static io.popdesk.support.AuditChainMath.canonical;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.task.TaskSteps;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Anonymizing one account (PII-4): the answer and the account afterwards, refusing one's own account, clearing
 * assignments, inviting the old email again, and the {@code ACCOUNT_ANONYMIZED} entry.
 */
class AnonymizeAccountTest extends PrivacyAppTest {

    /** Who is anonymized. */
    enum Who {
        ACTIVE_CUSTOMER,
        ACTIVE_AGENT,
        INVITED_CUSTOMER
    }

    /** The anonymous values, {@code SUSPENDED}, {@code anonymized: true}, the role kept, no photo. */
    private static void assertAnonymous(JsonNode account, long accountId, Role role) {
        assertThat(account.path("id").asLong(-1)).as("id of %s", account).isEqualTo(accountId);
        assertThat(account.path("name").asString(null)).as("name of %s", account).isEqualTo(ANONYMOUS_NAME);
        assertThat(account.path("email").asString(null)).as("email of %s", account)
                .isEqualTo(anonymousEmail(accountId));
        assertThat(account.path("status").asString(null)).as("status of %s", account).isEqualTo("SUSPENDED");
        assertThat(account.path("anonymized").isBoolean() && account.path("anonymized").booleanValue())
                .as("anonymized is true in %s", account).isTrue();
        assertThat(account.path("role").asString(null)).as("role of %s", account).isEqualTo(role.name());
        assertThat(account.path("photoUpdatedAt").isNull()).as("photoUpdatedAt is null in %s", account).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Who.class)
    @DisplayName("S10A-AC-08 anonymizing answers 200 like a suspension, with the anonymous name of the installation "
            + "language, deleted-{id}@invalid, SUSPENDED, anonymized true and no assignment cleared; the account list "
            + "then shows the same, also for an account that never accepted its invitation")
    void anonymizes(Who who) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        long id;
        Role role;
        String oldEmail;
        switch (who) {
            case ACTIVE_CUSTOMER -> {
                Session customer = fixtures.customer(admin, company.code());
                id = customer.accountId();
                role = Role.CUSTOMER;
                oldEmail = customer.email();
            }
            case ACTIVE_AGENT -> {
                Session agent = fixtures.agent(admin, company.code());
                id = agent.accountId();
                role = Role.AGENT;
                oldEmail = agent.email();
            }
            default -> {
                Invitation invited = fixtures.invite(admin, Role.CUSTOMER, company.code());
                id = invited.accountId();
                role = Role.CUSTOMER;
                oldEmail = invited.email();
            }
        }

        JsonNode answer = anonymize(admin, id);

        assertAnonymous(answer, id, role);
        assertThat(answer.path("cleared").path("issues").asLong(-1)).as("cleared.issues in %s", answer).isZero();
        assertAnonymous(anonymizedAccount(admin, id), id, role);
        assertThat(PrivacySteps.accounts(admin, "q", oldEmail)).as("accounts found by the old email %s", oldEmail)
                .extracting(a -> a.path("id").asLong(-1)).doesNotContain(id);
    }

    @Test
    @DisplayName("S10A-AC-12 an admin who anonymizes their own account gets 400 OWN_ACCOUNT, and the account, its "
            + "session and the audit log stay as they were")
    void ownAccount() {
        Session admin = fixtures.admin(fixtures.signInAdmin());

        assertRefused(anonymizeRequest(admin, admin.accountId()).send(), 400, "OWN_ACCOUNT");

        JsonNode account = PrivacySteps.account(admin, admin.accountId(), admin.email());
        assertThat(account.path("name").asString(null)).as("name").isEqualTo(admin.name());
        assertThat(account.path("status").asString(null)).as("status").isEqualTo("ACTIVE");
        assertThat(account.path("anonymized").isBoolean() && !account.path("anonymized").booleanValue())
                .as("anonymized is false in %s", account).isTrue();
        assertThat(entries(admin, "targetAccountId", admin.accountId(), "action", "ACCOUNT_ANONYMIZED"))
                .as("ACCOUNT_ANONYMIZED entries of the admin").isEmpty();
    }

    @ParameterizedTest(name = "clearAssignments={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("S10A-AC-14 with clearAssignments=true anonymizing clears the assignee of the agent's open issue "
            + "and OPEN task and counts the issue; without it both keep the assignee, who is then unavailable")
    void clearsAssignments(boolean clear) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        flow.assign(admin, issue.key(), agent.accountId());
        long task = TaskSteps.id(TaskSteps.create(admin, issue.key(),
                TaskSteps.body("title", TaskSteps.title("Anon"), "assigneeId", agent.accountId())));

        JsonNode answer = anonymize(admin, agent.accountId(), clear);

        assertThat(answer.path("cleared").path("issues").asLong(-1)).as("cleared.issues in %s", answer)
                .isEqualTo(clear ? 1 : 0);
        JsonNode detail = flow.detail(admin, issue.key());
        JsonNode taskNow = TaskSteps.task(admin, issue.key(), task);
        if (clear) {
            assertThat(detail.path("assignee").isNull()).as("assignee of the issue %s", detail).isTrue();
            assertThat(taskNow.path("assignee").isNull()).as("assignee of the task %s", taskNow).isTrue();
        } else {
            assertThat(detail.path("assignee").path("accountId").asLong(-1)).as("assignee of the issue %s", detail)
                    .isEqualTo(agent.accountId());
            assertThat(detail.path("assigneeUnavailable").asBoolean(false)).as("assigneeUnavailable in %s", detail)
                    .isTrue();
            assertThat(taskNow.path("assignee").path("accountId").asLong(-1)).as("assignee of the task %s", taskNow)
                    .isEqualTo(agent.accountId());
        }
    }

    @Test
    @DisplayName("S10A-AC-15 inviting the old email of an anonymized customer makes a new account, apart from the old "
            + "one: it joins no company of the old one and sees none of its issues, and the old account stays "
            + "anonymized and stays the reporter")
    void oldEmailInvitedAgain() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session old = fixtures.customer(admin, company.code());
        Issue issue = fixtures.fileIssue(old, company.code());
        anonymize(admin, old.accountId());

        Invitation again = fixtures.invite(admin, Role.CUSTOMER, old.email(), old.name(), List.of());
        Session fresh = fixtures.accept(again);

        assertThat(again.accountId()).as("account ID of the new invitation").isNotEqualTo(old.accountId());
        assertThat(fresh.client().get("/api/portal/me").expect(200).json().path("companies").findValuesAsString("code"))
                .as("companies of the new account").doesNotContain(company.code());
        assertThat(IssueFlow.list(fresh).json().path("items").findValuesAsString("key")).as("issues the new account lists")
                .doesNotContain(issue.key());
        assertThat(anonymizedAccount(admin, old.accountId()).path("anonymized").asBoolean(false))
                .as("the old account is still anonymized").isTrue();
        assertThat(flow.detail(admin, issue.key()).path("reporter").path("accountId").asLong(-1))
                .as("reporter of the old issue").isEqualTo(old.accountId());
        assertThat(PrivacySteps.account(admin, again.accountId(), old.email().toLowerCase(Locale.ROOT))
                .path("anonymized").asBoolean(true)).as("the new account is not anonymized").isFalse();
    }

    @ParameterizedTest(name = "clearAssignments={0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("S10A-AC-16 anonymizing leaves one ACCOUNT_ANONYMIZED entry by the admin with the target "
            + "{type: ACCOUNT, accountId}; clearedIssues only when assignments were cleared; neither the old name "
            + "nor the old email anywhere in it")
    void anonymizedEntry(boolean clear) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        flow.assign(admin, fixtures.fileIssue(customer, company.code()).key(), agent.accountId());

        anonymize(admin, agent.accountId(), clear);

        List<JsonNode> found = entries(admin, "targetAccountId", agent.accountId(), "action", "ACCOUNT_ANONYMIZED");
        assertThat(found).as("ACCOUNT_ANONYMIZED entries of the agent").hasSize(1);
        JsonNode entry = found.getFirst();
        assertThat(entry.path("actor").path("accountId").asLong(-1)).as("actor of %s", entry)
                .isEqualTo(admin.accountId());
        assertThat(canonical(entry.path("target"))).as("target of %s", entry)
                .isEqualTo("{\"accountId\":" + agent.accountId() + ",\"type\":\"ACCOUNT\"}");
        if (clear) {
            assertThat(entry.path("changes").path("clearedIssues").asLong(-1)).as("clearedIssues in %s", entry)
                    .isEqualTo(1);
        } else {
            assertThat(entry.path("changes").has("clearedIssues")).as("clearedIssues in %s", entry).isFalse();
        }
        String text = canonical(entry).toLowerCase(Locale.ROOT);
        assertThat(text).as("the entry").doesNotContain(agent.email().toLowerCase(Locale.ROOT))
                .doesNotContain(agent.name().toLowerCase(Locale.ROOT));
    }
}
