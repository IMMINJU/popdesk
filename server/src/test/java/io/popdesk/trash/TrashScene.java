package io.popdesk.trash;

import io.popdesk.issue.IssueFlow;
import io.popdesk.label.LabelSteps;
import io.popdesk.link.LinkSteps;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.TestClock;
import io.popdesk.support.Unique;
import io.popdesk.task.TaskSteps;
import java.time.LocalDate;
import java.time.ZoneOffset;
import tools.jackson.databind.JsonNode;

/**
 * One company and an issue with something of every kind under it, made through the API: a file of the customer,
 * a public comment of the customer, a public comment of the agent with a file, an internal comment, a task of the
 * agent with a note, a label, a due date, the agent as assignee, and a link to another issue of the company. Then
 * the issue is taken to {@code status}. The admin is a new admin of the test, so audit entries and trash entries
 * are found by who made them too.
 *
 * <p>Titles, contents, and file names are unique, so a test can look for them anywhere.
 */
public record TrashScene(Session admin, Company company, Session reporter, Session agent, Issue issue,
        Issue other, Attachment issueFile, Comment customerComment, Comment staffComment, Attachment commentFile,
        Comment internalComment, long task, long note, long label) {

    public String code() {
        return company.code();
    }

    public String key() {
        return issue.key();
    }

    public String title() {
        return issue.json().path("title").asString(null);
    }

    public static TrashScene build(Fixtures fixtures, IssueFlow flow, TestClock clock, String status) {
        Session admin = fixtures.admin(flow.admin());
        Company company = fixtures.createCompany(admin);
        String code = company.code();
        Session reporter = fixtures.customer(admin, code);
        Session agent = fixtures.agent(admin, code);
        Issue issue = fixtures.fileIssue(reporter, NewIssue.in(code).title(LinkSteps.title("Trashed issue of " + code))
                .content("Trashed content #" + Unique.number() + "#"));
        Issue other = fixtures.fileIssue(reporter, NewIssue.in(code).title(LinkSteps.title("Other issue of " + code)));
        String key = issue.key();
        Attachment issueFile = fixtures.attach(reporter, key, file("issue"));
        Comment customerComment = fixtures.comment(reporter, key, "Customer comment #" + Unique.number() + "#");
        Comment staffComment = fixtures.comment(agent, key, "Staff answer #" + Unique.number() + "#");
        Attachment commentFile = fixtures.attachToComment(agent, key, staffComment.id(), file("comment"));
        Comment internalComment = fixtures.internalComment(agent, key, "Internal note #" + Unique.number() + "#");
        flow.assign(agent, key, agent.accountId());
        long task = TaskSteps.id(TaskSteps.create(agent, key,
                TaskSteps.body("title", TaskSteps.title("Task"), "assigneeId", agent.accountId())));
        long note = TaskSteps.id(TaskSteps.note(agent, key, task, "Task note #" + Unique.number() + "#"));
        long label = LabelSteps.id(LabelSteps.create(admin, code));
        LabelSteps.add(agent, key, label);
        LabelSteps.setDueDate(agent, key, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(10));
        LinkSteps.link(agent, key, other.key(), LinkSteps.RELATED);
        if (!"RECEIVED".equals(status)) {
            flow.moveTo(agent, key, status);
        }
        return new TrashScene(admin, company, reporter, agent, issue, other, issueFile, customerComment,
                staffComment, commentFile, internalComment, task, note, label);
    }

    /** The issue as the admin sees it now; expects 200. */
    public JsonNode detail(IssueFlow flow) {
        return flow.detail(admin, key());
    }

    private static File file(String kind) {
        long n = Unique.number();
        return File.text("trash-" + kind + "-" + n + ".txt", "Trash " + kind + " file " + n);
    }
}
