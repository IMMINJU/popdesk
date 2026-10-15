package io.popdesk.task;

import static io.popdesk.issue.IssueFlow.NO_ACCOUNT;
import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.personId;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Who may be a task's assignee (S6B-AC-04, TASK-6, TM-57): an {@code ACTIVE} admin, or an {@code ACTIVE} agent
 * assigned to the issue's company. The check runs only when the assignee sent differs from the one the task has.
 */
class TaskAssigneeTest extends BareAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    /** An account that may not be the assignee of a task of the world's company. */
    enum Refused {
        NOBODY,
        AGENT_OF_ANOTHER_COMPANY_ONLY,
        SUSPENDED_AGENT_OF_THE_COMPANY,
        CUSTOMER_OF_THE_COMPANY;

        long accountId(World w, Session suspended) {
            return switch (this) {
                case NOBODY -> NO_ACCOUNT;
                case AGENT_OF_ANOTHER_COMPANY_ONLY -> w.agentElsewhere().accountId();
                case SUSPENDED_AGENT_OF_THE_COMPANY -> suspended.accountId();
                case CUSTOMER_OF_THE_COMPANY -> w.reporter().accountId();
            };
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Refused.class)
    @DisplayName("S6B-AC-04 an ID of nobody, an ACTIVE agent assigned to another company only, a suspended agent of the company, and a customer are 400 INVALID_INPUT assigneeId UNKNOWN_VALUE with the body of an ID of nobody, for creating and for editing, and nothing changes")
    void accountsThatCannotBeAssignedAreRefused(Refused refused) {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Session suspended = fixtures.agent(w.admin(), w.code());
        flow.suspend(w.admin(), suspended.accountId());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode task = TaskSteps.create(w.agent(), issue.key(),
                body("title", TaskSteps.title("Assigned"), "assigneeId", w.agent().accountId()));
        long accountId = refused.accountId(w, suspended);

        ApiResponse creating = TaskSteps.createRequest(admin, issue.key(),
                body("title", "Refused", "assigneeId", accountId)).send();
        ApiResponse editing = TaskSteps.updateRequest(admin, issue.key(), id(task),
                body("assigneeId", accountId)).send();
        ApiResponse nobody = TaskSteps.updateRequest(admin, issue.key(), id(task),
                body("assigneeId", NO_ACCOUNT)).send();

        for (ApiResponse answer : List.of(creating, editing)) {
            TaskSteps.assertInvalid(answer, "assigneeId", "UNKNOWN_VALUE");
            assertThat(answer.text()).as("body of %s, like for an ID of nobody", answer.request())
                    .isEqualTo(nobody.text());
        }
        assertThat(TaskSteps.tasks(w.admin(), issue.key())).as("tasks after the refused requests")
                .containsExactly(task);
    }

    @Test
    @DisplayName("S6B-AC-04 an admin and an ACTIVE agent assigned to the company are taken as assignee, also by an agent for themself")
    void adminsAndAssignedAgentsAreTaken() {
        World w = flow.world();
        Session admin = fixtures.admin(w.admin());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());

        JsonNode toAdmin = TaskSteps.create(w.agent(), issue.key(),
                body("title", TaskSteps.title("For an admin"), "assigneeId", admin.accountId()));
        JsonNode toSelf = TaskSteps.update(w.agent(), issue.key(), id(toAdmin),
                body("assigneeId", w.agent().accountId()));

        assertThat(personId(toAdmin, "assignee")).isEqualTo(admin.accountId());
        assertThat(personId(toSelf, "assignee")).isEqualTo(w.agent().accountId());
    }

    /** How the assignee lost the right to be one. */
    enum Loss {
        TAKEN_OFF_THE_COMPANY,
        SUSPENDED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Loss.class)
    @DisplayName("S6B-AC-04 a task whose assignee was taken off the company or suspended still takes edits of its other fields and the same assignee sent again, 200, and keeps the assignee")
    void assigneeWhoLostTheRightIsNotCheckedAgain(Loss loss) {
        World w = flow.world();
        Session assignee = fixtures.agent(w.admin(), w.code());
        Issue issue = fixtures.fileIssue(w.reporter(), w.code());
        JsonNode task = TaskSteps.create(w.agent(), issue.key(),
                body("title", TaskSteps.title("Kept"), "assigneeId", assignee.accountId()));
        if (loss == Loss.SUSPENDED) {
            flow.suspend(w.admin(), assignee.accountId());
        } else {
            flow.unassignAgent(w.admin(), w.code(), assignee.accountId());
        }

        ApiResponse renamed = TaskSteps.updateRequest(w.agent(), issue.key(), id(task), body("title", "Renamed"))
                .send();
        ApiResponse same = TaskSteps.updateRequest(w.agent(), issue.key(), id(task),
                body("assigneeId", assignee.accountId(), "status", "DONE")).send();

        assertThat(renamed.status()).as(renamed.request() + ": " + renamed.snippet()).isEqualTo(200);
        assertThat(same.status()).as(same.request() + ": " + same.snippet()).isEqualTo(200);
        JsonNode after = TaskSteps.task(w.agent(), issue.key(), id(task));
        assertThat(TaskSteps.text(after, "title")).isEqualTo("Renamed");
        assertThat(TaskSteps.text(after, "status")).isEqualTo("DONE");
        assertThat(personId(after, "assignee")).as("assignee").isEqualTo(assignee.accountId());
    }
}
