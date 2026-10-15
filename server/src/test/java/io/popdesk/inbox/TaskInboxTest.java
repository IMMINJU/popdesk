package io.popdesk.inbox;

import static io.popdesk.inbox.InboxSteps.CONSOLE_ITEM_FIELDS;
import static io.popdesk.inbox.InboxSteps.added;
import static io.popdesk.inbox.InboxSteps.assertItem;
import static io.popdesk.inbox.InboxSteps.idsAbout;
import static io.popdesk.inbox.InboxSteps.item;
import static io.popdesk.inbox.InboxSteps.itemsOf;
import static io.popdesk.inbox.InboxSteps.mailAbout;
import static io.popdesk.inbox.InboxSteps.same;
import static io.popdesk.issue.TalkFlow.ids;
import static io.popdesk.task.TaskSteps.body;
import static io.popdesk.task.TaskSteps.id;
import static io.popdesk.task.TaskSteps.title;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.due.DueSteps;
import io.popdesk.inbox.InboxSteps.Cast;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver.Received;
import io.popdesk.task.TaskSteps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The inbox item of a task assignee (slice 6b, S6B-AC-33, 35, 36): who gets {@code TASK_ASSIGNED} when the
 * assignee of a task is set, that nobody else does, that the rest of the task work tells nobody, and that a
 * deleted task takes its items with it.
 *
 * <p>With SMTP (setup "connected"), so that a mail to the wrong person would arrive. Items and mails are
 * counted as what the step added, about the one issue of the test. "No item" and "no mail" are read after
 * a later mail arrived ({@link TalkFlow#awaitALaterMail}). Task writes are done by the second admin of the
 * cast ({@code bystander}), who is new, so the shared first admin's writes do not count against the limit.
 */
class TaskInboxTest extends ConnectedAppTest {

    private static final String KIND = "TASK_ASSIGNED";

    private IssueFlow flow;
    private InboxSteps steps;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        steps = new InboxSteps(fixtures, flow);
    }

    /** {@code task.id} of an inbox item, or -1. */
    private static long taskId(JsonNode item) {
        return item.path("task").path("id").asLong(-1);
    }

    /** The {@code TASK_ASSIGNED} items of the person about the issue. */
    private static List<JsonNode> taskItems(Session session, String key) {
        return itemsOf(session, key, KIND);
    }

    /**
     * Files another issue and waits for its webhook. Webhooks go out one at a time in the order they were
     * queued, so every webhook queued before has been sent by then.
     */
    private void awaitALaterWebhook(Cast c) {
        String later = fixtures.fileIssue(c.reporter(), c.code()).key();
        Wait.until("the webhook of the later issue " + later,
                () -> webhook().containing(later).isEmpty() ? null : Boolean.TRUE);
    }

    // S6B-AC-33

    /** How the assignee of a task is set, or not set. */
    enum Event {
        SET_ON_CREATE,
        SET_BY_EDIT,
        CHANGED_TO_ANOTHER,
        TO_ONESELF_ON_CREATE,
        TO_ONESELF_BY_EDIT,
        CLEARED,
        SAME_PERSON_AGAIN
    }

    /** The task of the case, who must get the one item (null for nobody), and who set the assignee. */
    private record Outcome(long taskId, Session recipient, Session actor) {}

    /** The task the case starts from: none, one without assignee, or one {@code former} is the assignee of. */
    private Long prepare(Event event, Cast c, String key) {
        return switch (event) {
            case SET_ON_CREATE, TO_ONESELF_ON_CREATE -> null;
            case SET_BY_EDIT -> id(TaskSteps.create(c.bystander(), key, title("Task")));
            case CHANGED_TO_ANOTHER, TO_ONESELF_BY_EDIT, CLEARED, SAME_PERSON_AGAIN -> id(TaskSteps.create(
                    c.bystander(), key, body("title", title("Task"), "assigneeId", c.former().accountId())));
        };
    }

    private Outcome act(Event event, Cast c, String key, Long task) {
        Session admin = c.bystander();
        Session assignee = c.assignee();
        return switch (event) {
            case SET_ON_CREATE -> new Outcome(id(TaskSteps.create(admin, key,
                    body("title", title("Task"), "assigneeId", assignee.accountId()))), assignee, admin);
            case SET_BY_EDIT, CHANGED_TO_ANOTHER -> {
                TaskSteps.update(admin, key, task, body("assigneeId", assignee.accountId()));
                yield new Outcome(task, assignee, admin);
            }
            case TO_ONESELF_ON_CREATE -> new Outcome(id(TaskSteps.create(assignee, key,
                    body("title", title("Task"), "assigneeId", assignee.accountId()))), null, assignee);
            case TO_ONESELF_BY_EDIT -> {
                TaskSteps.update(assignee, key, task, body("assigneeId", assignee.accountId()));
                yield new Outcome(task, null, assignee);
            }
            case CLEARED -> {
                TaskSteps.update(admin, key, task, body("assigneeId", null));
                yield new Outcome(task, null, admin);
            }
            case SAME_PERSON_AGAIN -> {
                TaskSteps.update(admin, key, task, body("assigneeId", c.former().accountId(), "title",
                        title("Renamed")));
                yield new Outcome(task, null, admin);
            }
        };
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Event.class)
    @DisplayName("S6B-AC-33 a task assignee set to another person, on create or by an edit, gets one TASK_ASSIGNED "
            + "item with the issue, the task, and who set it, and one mail; setting oneself, clearing, and the same "
            + "person again add no item and no mail; nobody else gets one, and the assignee before keeps theirs")
    void assigneeOfATaskGetsTheOneItem(Event event) {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();
        Long prepared = prepare(event, c, key);
        TalkFlow.awaitALaterMail(fixtures, c.admin());
        Map<String, Set<Long>> itemsBefore = new LinkedHashMap<>();
        Map<String, Integer> mailBefore = new LinkedHashMap<>();
        for (Session person : c.everyone()) {
            itemsBefore.put(person.email(), idsAbout(person, key));
            mailBefore.put(person.email(), mailAbout(mail(), person, key).size());
        }
        List<Long> formerItems = ids(taskItems(c.former(), key));
        if (prepared != null && event != Event.SET_BY_EDIT) {
            assertThat(formerItems).as("TASK_ASSIGNED items of the assignee before the step").hasSize(1);
        }

        Outcome o = act(event, c, key, prepared);

        TalkFlow.awaitALaterMail(fixtures, c.admin());
        SoftAssertions softly = new SoftAssertions();
        for (Session person : c.everyone()) {
            boolean recipient = o.recipient() != null && same(person, o.recipient());
            List<JsonNode> got = added(person, key, itemsBefore.get(person.email()));
            int mailed = mailAbout(mail(), person, key).size() - mailBefore.get(person.email());
            softly.assertThat(got).as("items the step added for %s (%s)", person.email(), person.role())
                    .hasSize(recipient ? 1 : 0);
            softly.assertThat(mailed).as("mails about %s the step sent to %s (%s)", key, person.email(),
                    person.role()).isEqualTo(recipient ? 1 : 0);
            if (recipient && got.size() == 1) {
                JsonNode item = got.getFirst();
                assertItem(item, person, KIND, key, null, o.actor());
                softly.assertThat(taskId(item)).as("task.id of %s", item).isEqualTo(o.taskId());
            }
        }
        softly.assertAll();
        assertThat(ids(taskItems(c.former(), key))).as("TASK_ASSIGNED items of the assignee before, afterwards")
                .isEqualTo(formerItems);
    }

    @Test
    @DisplayName("S6B-AC-33 a TASK_ASSIGNED item on the console carries the fields of an item and the task as ID "
            + "and title, no comment, and after the task is renamed the title of now")
    void itemCarriesTheTask() {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();
        String first = title("Call back");

        long task = id(TaskSteps.create(c.bystander(), key,
                body("title", first, "assigneeId", c.assignee().accountId())));

        List<JsonNode> items = taskItems(c.assignee(), key);
        assertThat(items).as("TASK_ASSIGNED items of the assignee").hasSize(1);
        JsonNode item = items.getFirst();
        assertThat(new TreeSet<>(item.propertyNames())).as("fields of %s", item)
                .isEqualTo(new TreeSet<>(CONSOLE_ITEM_FIELDS));
        assertThat(new TreeSet<>(item.path("task").propertyNames())).as("fields of task in %s", item)
                .isEqualTo(new TreeSet<>(Set.of("id", "title")));
        assertThat(taskId(item)).as("task.id").isEqualTo(task);
        assertThat(item.path("task").path("title").asString(null)).as("task.title").isEqualTo(first);
        assertThat(item.path("companyCode").asString(null)).as("companyCode").isEqualTo(c.code());
        assertItem(item, c.assignee(), KIND, key, null, c.bystander());

        String renamed = title("Renamed");
        TaskSteps.update(c.bystander(), key, task, body("title", renamed));
        assertThat(item(c.assignee(), item.path("id").asLong()).path("task").path("title").asString(null))
                .as("task.title after the task was renamed").isEqualTo(renamed);
    }

    // S6B-AC-35

    @Test
    @DisplayName("S6B-AC-35 task work posts no webhook and gives the reporter and their colleague no item and no "
            + "mail; notes, status, due date, and title changes give the assignee nothing past the one item and mail "
            + "of the assignment")
    void taskWorkTellsOnlyTheAssignee() {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();
        awaitALaterWebhook(c);
        TalkFlow.awaitALaterMail(fixtures, c.admin());
        int webhooksBefore = webhook().containing(key).size();
        Map<String, Set<Long>> itemsBefore = new LinkedHashMap<>();
        Map<String, Integer> mailBefore = new LinkedHashMap<>();
        for (Session person : List.of(c.reporter(), c.colleague(), c.assignee())) {
            itemsBefore.put(person.email(), idsAbout(person, key));
            mailBefore.put(person.email(), mailAbout(mail(), person, key).size());
        }
        String taskTitle = title("Webhook");
        String noteWord = IssueFlow.word();

        long task = id(TaskSteps.create(c.bystander(), key,
                body("title", taskTitle, "assigneeId", c.assignee().accountId())));
        TaskSteps.note(c.bystander(), key, task, "Note " + noteWord);
        TaskSteps.note(c.assignee(), key, task, "Reply " + noteWord);
        TaskSteps.update(c.bystander(), key, task,
                body("dueDate", new DueSteps(fixtures, clock).today(DueSteps.UTC).plusDays(3).toString()));
        TaskSteps.update(c.bystander(), key, task, body("status", "DONE"));
        TaskSteps.update(c.assignee(), key, task, body("status", "OPEN", "title", title("Webhook again")));

        // The webhook text holds no comment text, only the issue; webhooks go out in the order they were queued.
        fixtures.comment(c.reporter(), key, Unique.text("Later question"));
        Wait.until("the webhook of the comment after the task work",
                () -> webhook().containing(key).size() > webhooksBefore ? Boolean.TRUE : null);
        TalkFlow.awaitALaterMail(fixtures, c.admin());
        List<Received> webhooks = webhook().containing(key);
        assertThat(webhooks).as("webhook messages about %s: those before and the later comment's", key)
                .hasSize(webhooksBefore + 1);
        assertThat(webhooks).as("webhook messages about %s", key)
                .noneMatch(r -> r.text().contains(taskTitle) || r.text().contains(noteWord)
                        || r.text().contains(KIND));
        for (Session customer : List.of(c.reporter(), c.colleague())) {
            assertThat(added(customer, key, itemsBefore.get(customer.email()))).as("items added for %s",
                    customer.email()).isEmpty();
            assertThat(mailAbout(mail(), customer, key)).as("mails about %s to %s", key, customer.email())
                    .hasSize(mailBefore.get(customer.email()));
        }
        // The assignee of the task is also the assignee of the issue, so the later comment gives them an item too.
        List<JsonNode> assigneeGot = added(c.assignee(), key, itemsBefore.get(c.assignee().email()));
        assertThat(assigneeGot.stream().filter(n -> KIND.equals(n.path("kind").asString(null))).toList())
                .as("TASK_ASSIGNED items added for the assignee").hasSize(1);
        assertThat(mailAbout(mail(), c.assignee(), key)).as("mails about %s to the assignee", key)
                .hasSize(mailBefore.get(c.assignee().email()) + 1);
    }

    // S6B-AC-36

    @Test
    @DisplayName("S6B-AC-36 deleting a task deletes its TASK_ASSIGNED items; the item of another task of the "
            + "issue and the issue's own items stay")
    void deletedTaskTakesItsItems() {
        Cast c = steps.cast();
        String key = steps.assignedIssue(c).key();
        long gone = id(TaskSteps.create(c.bystander(), key,
                body("title", title("Gone"), "assigneeId", c.assignee().accountId())));
        long kept = id(TaskSteps.create(c.bystander(), key,
                body("title", title("Kept"), "assigneeId", c.assignee().accountId())));
        TaskSteps.update(c.bystander(), key, gone, body("assigneeId", c.former().accountId()));
        List<JsonNode> beforeOfAssignee = TalkFlow.inboxOf(c.assignee(), key);
        assertThat(beforeOfAssignee.stream().map(TaskInboxTest::taskId).filter(t -> t > 0).toList())
                .as("tasks of the assignee's items before").containsExactlyInAnyOrder(gone, kept);
        assertThat(taskItems(c.former(), key).stream().map(TaskInboxTest::taskId).toList())
                .as("tasks of the items of the person the task went to next").containsExactly(gone);

        TaskSteps.delete(c.bystander(), key, gone);

        List<Long> left = ids(beforeOfAssignee.stream().filter(n -> taskId(n) != gone).toList());
        assertThat(ids(TalkFlow.inboxOf(c.assignee(), key))).as("items of the assignee after the delete")
                .isEqualTo(left);
        assertThat(taskItems(c.former(), key)).as("TASK_ASSIGNED items of the next assignee after the delete")
                .isEmpty();
        assertThat(itemsOf(c.former(), key, "ASSIGNED")).as("ASSIGNED items of the issue's assignee before")
                .hasSize(1);
        for (Session person : c.everyone()) {
            assertThat(TalkFlow.inbox(person)).as("items of %s", person.email()).noneMatch(n -> taskId(n) == gone);
        }
    }
}
