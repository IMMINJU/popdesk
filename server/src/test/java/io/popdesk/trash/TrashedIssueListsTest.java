package io.popdesk.trash;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.label.LabelSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.task.TaskSteps;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * An issue in the trash is left out of every list and count (S7A-AC-04, TRASH-1): the issue lists of both apps
 * with a search word and filters and their total, the agent's own tasks, the inboxes of both apps and their unread
 * counts, the counts of open assignments, and the count of issues of a label. Clearing an agent's assignments leaves
 * the issue in the trash as it is (acceptance 5절 6), so that it is restored with its assignee.
 *
 * <p>Two issues of one company are alike in everything the lists look at: a word of their own in the title, the same
 * status, assignee, label, a task of the same agent, and an inbox item for the agent and for the customer. One is
 * deleted ({@code gone}), the other stays ({@code kept}), so each list shows that it still finds what it should. The
 * people are new accounts of the test, so their inboxes and counts hold only what it made.
 */
class TrashedIssueListsTest extends BareAppTest {

    private IssueFlow flow;
    private TrashRows rows;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        rows = new TrashRows(db);
    }

    private record Scene(Session admin, Company company, Session reporter, Session agent, String word, long label,
            Issue gone, Issue kept, long goneTask, long keptTask) {

        String code() {
            return company.code();
        }
    }

    private Scene scene() {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        String code = company.code();
        Session reporter = fixtures.customer(admin, code);
        Session agent = fixtures.agent(admin, code);
        String word = IssueFlow.word();
        long label = LabelSteps.id(LabelSteps.create(admin, code));
        List<Issue> issues = new ArrayList<>();
        List<Long> tasks = new ArrayList<>();
        for (String which : List.of("Gone", "Kept")) {
            Issue issue = fixtures.fileIssue(reporter, NewIssue.in(code).title(which + " " + word + " #"
                    + Unique.number() + "#"));
            flow.assign(agent, issue.key(), agent.accountId());
            flow.changeStatus(agent, issue.key(), "CONFIRMED");
            LabelSteps.add(agent, issue.key(), label);
            tasks.add(TaskSteps.id(TaskSteps.create(agent, issue.key(),
                    TaskSteps.body("title", TaskSteps.title(which), "assigneeId", agent.accountId()))));
            fixtures.comment(reporter, issue.key(), "Customer comment on " + which);
            fixtures.comment(agent, issue.key(), "Staff answer on " + which);
            issues.add(issue);
        }
        return new Scene(admin, company, reporter, agent, word, label, issues.get(0), issues.get(1), tasks.get(0),
                tasks.get(1));
    }

    private static List<String> keys(ApiResponse page) {
        return page.json().path("items").values().stream().map(n -> n.path("key").asString(null)).toList();
    }

    /** The lists of issues, each narrowed to the scene's company or word. */
    enum IssueList {
        CONSOLE_COMPANY(false, "companyCode"),
        CONSOLE_SEARCH(false, "q"),
        CONSOLE_STATUS(false, "status"),
        CONSOLE_LABEL(false, "label"),
        CONSOLE_ASSIGNEE(false, "assignee"),
        PORTAL_COMPANY(true, "companyCode"),
        PORTAL_SEARCH(true, "q");

        private final boolean portal;
        private final String filter;

        IssueList(boolean portal, String filter) {
            this.portal = portal;
            this.filter = filter;
        }

        ApiResponse read(Scene s) {
            Session reader = portal ? s.reporter() : s.agent();
            Object[] query = switch (filter) {
                case "companyCode" -> new Object[] {"companyCode", s.code()};
                case "q" -> new Object[] {"q", s.word()};
                case "status" -> new Object[] {"status", "CONFIRMED", "companyCode", s.code()};
                case "label" -> new Object[] {"label", s.label()};
                case "assignee" -> new Object[] {"assignee", "me", "companyCode", s.code()};
                default -> throw new IllegalStateException(filter);
            };
            return IssueFlow.list(reader, query);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(IssueList.class)
    @DisplayName("S7A-AC-04 the issue lists of both apps, with the company, a search word, a status, a label, or the assignee, leave out the issue in the trash and count one issue less; the other issue is still there")
    void issueListsLeaveItOut(IssueList list) {
        Scene s = scene();
        ApiResponse before = list.read(s);

        TrashSteps.delete(s.admin(), s.gone().key());
        ApiResponse after = list.read(s);

        assertThat(keys(before)).as("keys before the delete").containsExactlyInAnyOrder(s.gone().key(),
                s.kept().key());
        assertThat(IssueFlow.total(before)).as("total before the delete").isEqualTo(2);
        assertThat(keys(after)).as("keys after the delete").containsExactly(s.kept().key());
        assertThat(IssueFlow.total(after)).as("total after the delete").isEqualTo(1);
    }

    @Test
    @DisplayName("S7A-AC-04 the agent's own tasks leave out the task of the issue in the trash; the task of the other issue is still there")
    void myTasksLeaveItOut() {
        Scene s = scene();
        List<Long> before = myTaskIds(s.agent());

        TrashSteps.delete(s.admin(), s.gone().key());

        assertThat(before).as("the agent's tasks before the delete").containsExactlyInAnyOrder(s.goneTask(),
                s.keptTask());
        assertThat(myTaskIds(s.agent())).as("the agent's tasks after the delete").containsExactly(s.keptTask());
    }

    private static List<Long> myTaskIds(Session staff) {
        ApiResponse page = TaskSteps.myTasks(staff, "page", 1, "size", 100);
        return page.json().path("items").values().stream().map(TaskSteps::id).toList();
    }

    @Test
    @DisplayName("S7A-AC-04 the inboxes of the agent (console) and of the customer (portal) leave out the items of the issue in the trash, in the list and in the unread count; the items of the other issue are still there and unread")
    void inboxesLeaveItOut() {
        Scene s = scene();
        for (Session reader : List.of(s.agent(), s.reporter())) {
            Wait.until("items of both issues in the inbox of account " + reader.accountId(),
                    () -> !TalkFlow.inboxOf(reader, s.gone().key()).isEmpty()
                            && !TalkFlow.inboxOf(reader, s.kept().key()).isEmpty() ? Boolean.TRUE : null);
        }
        long agentKept = TalkFlow.inboxOf(s.agent(), s.kept().key()).size();
        long reporterKept = TalkFlow.inboxOf(s.reporter(), s.kept().key()).size();
        long agentUnread = TalkFlow.unreadCount(s.agent());
        long reporterUnread = TalkFlow.unreadCount(s.reporter());

        TrashSteps.delete(s.admin(), s.gone().key());

        for (Session reader : List.of(s.agent(), s.reporter())) {
            List<JsonNode> inbox = TalkFlow.inbox(reader);
            assertThat(inbox.stream().map(n -> n.path("issue").path("key").asString(null)).toList())
                    .as("issue keys in the inbox of account " + reader.accountId())
                    .contains(s.kept().key())
                    .doesNotContain(s.gone().key());
        }
        assertThat(TalkFlow.unreadCount(s.agent())).as("unread count of the agent after the delete")
                .isEqualTo(agentKept).isLessThan(agentUnread);
        assertThat(TalkFlow.unreadCount(s.reporter())).as("unread count of the customer after the delete")
                .isEqualTo(reporterKept).isLessThan(reporterUnread);
    }

    @Test
    @DisplayName("S7A-AC-04 the open assignments of the agent, for the company and for the account, leave out the issue in the trash")
    void openAssignmentsLeaveItOut() {
        Scene s = scene();
        long id = s.agent().accountId();
        long companyBefore = companyCount(s, id);
        long accountBefore = accountCount(s, id);

        TrashSteps.delete(s.admin(), s.gone().key());

        assertThat(companyBefore).as("open assignments in the company before the delete").isEqualTo(2);
        assertThat(accountBefore).as("open assignments of the account before the delete").isEqualTo(2);
        assertThat(companyCount(s, id)).as("open assignments in the company after the delete").isEqualTo(1);
        assertThat(accountCount(s, id)).as("open assignments of the account after the delete").isEqualTo(1);
    }

    private static long companyCount(Scene s, long id) {
        return s.admin().client().get("/api/console/companies/" + s.code() + "/agents/" + id + "/open-assignments")
                .expect(200).jsonLong("issues");
    }

    private static long accountCount(Scene s, long id) {
        return s.admin().client().get("/api/console/accounts/" + id + "/open-assignments").expect(200)
                .jsonLong("issues");
    }

    @Test
    @DisplayName("S7A-AC-04 the count of issues of a label leaves out the issue in the trash")
    void labelCountLeavesItOut() {
        Scene s = scene();
        long before = LabelSteps.find(s.admin(), s.code(), s.label()).path("issueCount").asLong(-1);

        TrashSteps.delete(s.admin(), s.gone().key());

        assertThat(before).as("issueCount of the label before the delete").isEqualTo(2);
        assertThat(LabelSteps.find(s.admin(), s.code(), s.label()).path("issueCount").asLong(-1))
                .as("issueCount of the label after the delete").isEqualTo(1);
    }

    @Test
    @DisplayName("S7A-AC-04 taking the agent off the company with clearAssignments=true clears the other issue and answers 1; the issue in the trash keeps the agent as assignee, and its task keeps the agent too")
    void clearingAssignmentsLeavesTheTrashAlone() {
        Scene s = scene();
        TrashSteps.delete(s.admin(), s.gone().key());
        Map<String, Object> goneBefore = rows.fields(s.gone().key());

        ApiResponse answer = IssueFlow.unassignAgentRequest(s.admin(), s.code(), s.agent().accountId())
                .query("clearAssignments", true).expect(200);

        assertThat(answer.jsonLong("issues")).as("issues cleared in the answer").isEqualTo(1);
        assertThat(flow.detail(s.admin(), s.kept().key()).path("assignee").isNull())
                .as("the other issue has no assignee").isTrue();
        assertThat(rows.fields(s.gone().key())).as("the issue in the trash after clearing").isEqualTo(goneBefore);
        assertThat(goneBefore.get("assignee_id")).as("assignee of the issue in the trash")
                .isEqualTo(s.agent().accountId());
        assertThat(rows.under(s.gone().key()).get("tasks")).as("the task of the issue in the trash")
                .singleElement()
                .satisfies(t -> assertThat(((Number) t.get("assignee_id")).longValue()).as("task assignee")
                        .isEqualTo(s.agent().accountId()));
    }
}
