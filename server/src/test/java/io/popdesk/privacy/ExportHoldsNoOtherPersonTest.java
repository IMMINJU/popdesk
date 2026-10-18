package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.task.TaskSteps;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The export holds one person (S10A-AC-05, TM-05): on issues with an assignee, comments and files of others, a
 * mention, a task, and an issue filed on the customer's behalf, every text shaped like an email in the file is the
 * account's own, no name of the other people is there, and every field that names an account names this one.
 */
class ExportHoldsNoOtherPersonTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** A person whose name and email are words no other test has. */
    private Session named(Session admin, Role role, String companyCode) {
        String word = IssueFlow.word();
        List<String> codes = role == Role.CUSTOMER ? List.of(companyCode) : List.of();
        Session person = fixtures.accept(fixtures.invite(admin, role, word + "@example.test",
                role.name().toLowerCase(Locale.ROOT) + " " + word, codes));
        if (role == Role.AGENT) {
            fixtures.assignAgent(admin, companyCode, person.accountId());
        }
        return person;
    }

    @Test
    @DisplayName("S10A-AC-05 the export of a customer and of an agent who worked on the same issues holds no email, "
            + "name, or account ID of anyone else: not the assignee, other people's comments and files, a mention, a "
            + "task, or who filed on the customer's behalf")
    void holdsOnlyItsOwnPerson() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = named(admin, Role.CUSTOMER, company.code());
        Session colleague = named(admin, Role.CUSTOMER, company.code());
        Session agent = named(admin, Role.AGENT, company.code());
        Session other = named(admin, Role.AGENT, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        flow.assign(admin, issue.key(), agent.accountId());
        fixtures.comment(customer, issue.key(), "Question " + Unique.number());
        fixtures.attach(customer, issue.key(), File.text("own-" + Unique.number() + ".txt", "Own"));
        Issue ofColleague = fixtures.fileIssue(colleague, company.code());
        fixtures.comment(colleague, ofColleague.key(), "Colleague " + Unique.number());
        TalkFlow.add(agent, issue.key(), TalkFlow.body("content", "Answer " + Unique.number(), "mentions",
                List.of(other.accountId())));
        fixtures.internalComment(other, issue.key(), "Note " + Unique.number());
        fixtures.attach(other, issue.key(), File.text("other-" + Unique.number() + ".txt", "Staff"));
        TaskSteps.create(other, issue.key(), TaskSteps.body("title", TaskSteps.title("Task"), "assigneeId",
                agent.accountId()));
        Issue onBehalf = flow.fileOnBehalf(other, OnBehalf.of(company.code(), customer.accountId()));
        flow.assign(other, onBehalf.key(), other.accountId());
        fixtures.comment(customer, onBehalf.key(), "On the issue filed for me " + Unique.number());
        List<Session> people = List.of(customer, colleague, agent, other);

        for (Session person : List.of(customer, agent)) {
            ApiResponse answer = ExportSteps.export(admin, person.accountId());
            String text = answer.text();
            JsonNode json = answer.json();

            assertThat(ExportSteps.emailsIn(text)).as("emails in the export of %s", person.email())
                    .isNotEmpty()
                    .allSatisfy(e -> assertThat(e.toLowerCase(Locale.ROOT)).isEqualTo(person.email()));
            for (Session someone : people) {
                if (someone != person) {
                    assertThat(text).as("the export of %s", person.email())
                            .doesNotContain(someone.name()).doesNotContain(someone.email());
                }
            }
            assertThat(accountFields(json, "")).as("fields that name an account in the export of %s", person.email())
                    .isNotEmpty()
                    .allSatisfy(f -> assertThat(f).endsWith("=" + person.accountId()));
        }
        assertThat(ExportSteps.keys(ExportSteps.file(admin, customer.accountId())))
                .as("issues of the customer, the one filed on their behalf too").contains(issue.key(), onBehalf.key());
    }

    /**
     * Every field of the file whose name says it holds an account ({@code id}, a name ending in {@code Id} or
     * {@code By}, or a person's field such as {@code assignee}), as {@code path=value}. {@code commentId} names a
     * comment and is left out.
     */
    private static List<String> accountFields(JsonNode node, String path) {
        List<String> out = new ArrayList<>();
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                out.addAll(accountFields(node.get(i), path + "[" + i + "]"));
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                String name = e.getKey();
                String here = path + "." + name;
                boolean account = !name.equals("commentId") && (name.equals("id") || name.endsWith("Id")
                        || name.endsWith("By") || List.of("assignee", "reporter", "actor", "author", "mentions")
                        .contains(name));
                if (account) {
                    out.add(here + "=" + (e.getValue().isValueNode() ? e.getValue().asString("") : e.getValue()));
                } else {
                    out.addAll(accountFields(e.getValue(), here));
                }
            }
        }
        return out;
    }
}
