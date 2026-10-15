package io.popdesk.trash;

import static io.popdesk.issue.IssueFlow.time;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.issue.TalkFlow;
import io.popdesk.link.LinkSteps;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver.Received;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the automatic closing sends (S7A-AC-43, STATUS-5, LINK-6, acceptance 5절 11): the same as when staff close an
 * issue. A twin issue of the same company and reporter, resolved at the same time, is closed by staff first; its
 * inbox item, mail, and webhook are what the closed issue's must be, with the key and the title of the other issue.
 * With SMTP and the webhook receiver (setup "connected").
 */
class AutoCloseNotifyTest extends ConnectedAppTest {

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    private static boolean names(ReceivedMail mail, String key) {
        return mail.subject().contains("[" + key + "]");
    }

    /** The reporter's mails about the issue, in the order they came. */
    private List<ReceivedMail> mailsAbout(Session reporter, String key) {
        return mail().messagesTo(reporter.email()).stream().filter(m -> names(m, key)).toList();
    }

    /** Webhook texts name an issue key followed by a space; so {@code CODE-1} does not match {@code CODE-12}. */
    private List<Received> webhooksAbout(String key) {
        return webhook().containing(key + " ");
    }

    /** An inbox item without what differs between two items of two issues: its ID, issue, and times. */
    private static JsonNode shape(JsonNode item) {
        ObjectNode copy = (ObjectNode) item.deepCopy();
        for (String field : List.of("id", "issue", "createdAt", "readAt")) {
            copy.remove(field);
        }
        return copy;
    }

    @Test
    @DisplayName("S7A-AC-43 the automatic closing gives the reporter the ISSUE_CLOSED inbox item and mail and posts the ISSUE_CLOSED webhook staff's closing gives, and the assignee of a linked issue a LINKED_ISSUE_CLOSED item whose actor is null")
    void autoClosingNotifiesLikeStaffClosing() {
        World w = flow.world();
        Session watcher = fixtures.agent(w.admin(), w.code());
        String titleX = LinkSteps.title("Closed by popdesk");
        String titleZ = LinkSteps.title("Closed by staff");
        Issue x = flow.fileIn(w.reporter(), NewIssue.in(w.code()).title(titleX), w.agent(), "RESOLVED");
        Issue z = flow.fileIn(w.reporter(), NewIssue.in(w.code()).title(titleZ), w.agent(), "RESOLVED");
        Issue y = fixtures.fileIssue(w.reporter(), w.code());
        flow.assign(w.admin(), y.key(), watcher.accountId());
        LinkSteps.link(w.agent(), y.key(), x.key(), LinkSteps.RELATED);
        Instant resolvedAt = time(flow.detail(w.agent(), x.key()), "resolvedAt");
        int mailsOfZ = mailsAbout(w.reporter(), z.key()).size();
        int hooksOfZ = webhooksAbout(z.key()).size();

        flow.changeStatus(w.agent(), z.key(), "CLOSED");

        ReceivedMail staffMail = Wait.until("the mail of the staff closing of " + z.key(),
                () -> mailsAbout(w.reporter(), z.key()).size() > mailsOfZ
                        ? mailsAbout(w.reporter(), z.key()).getLast() : null);
        Received staffHook = Wait.until("the webhook of the staff closing of " + z.key(),
                () -> webhooksAbout(z.key()).size() > hooksOfZ ? webhooksAbout(z.key()).getLast() : null);
        JsonNode staffItem = TalkFlow.inboxOf(w.reporter(), z.key()).getFirst();
        assertThat(TalkFlow.kind(staffItem)).as("kind of the reporter's newest item about " + z.key())
                .isEqualTo("ISSUE_CLOSED");
        UnaryOperator<String> asX = text -> text.replace(titleZ, titleX).replace(z.key(), x.key());

        Instant due = clock.advance(Duration.between(clock.instant(),
                resolvedAt.plus(Duration.ofDays(7).plusMinutes(11))));
        Session admin = fixtures.signInAgain(w.admin());
        TrashJobs.awaitRun(admin, TrashJobs.AUTO_CLOSE, due, 1);
        assertThat(flow.detail(admin, x.key()).path("status").asString(null)).as("status of " + x.key())
                .isEqualTo("CLOSED");

        ReceivedMail autoMail = mail().awaitMessage("the mail about " + x.key() + " like the staff closing's",
                m -> m.isTo(w.reporter().email()) && m.subject().equals(asX.apply(staffMail.subject())));
        assertThat(autoMail.text()).as("text of the mail of the automatic closing")
                .isEqualTo(asX.apply(staffMail.text()));
        Received autoHook = webhook().await("the webhook about " + x.key() + " like the staff closing's",
                r -> r.text().equals(asX.apply(staffHook.text())));
        assertThat(autoHook.json().path("text").asString(null)).as("webhook text of the automatic closing")
                .contains(x.key()).contains(titleX);

        Session reporter = fixtures.signInAgain(w.reporter());
        JsonNode autoItem = TalkFlow.inboxOf(reporter, x.key()).getFirst();
        assertThat(TalkFlow.kind(autoItem)).as("kind of the reporter's newest item about " + x.key())
                .isEqualTo("ISSUE_CLOSED");
        assertThat(shape(autoItem)).as("the reporter's item, against the one of the staff closing")
                .isEqualTo(shape(staffItem));
        assertThat(autoItem.path("issue").path("key").asString(null)).as("issue of the item").isEqualTo(x.key());

        List<JsonNode> linked = TalkFlow.inboxOf(fixtures.signInAgain(watcher), y.key()).stream()
                .filter(n -> LinkSteps.LINKED_CLOSED.equals(TalkFlow.kind(n))).toList();
        assertThat(linked).as("LINKED_ISSUE_CLOSED items of the assignee of " + y.key()).singleElement()
                .satisfies(n -> assertThat(n.path("linkedIssue").path("key").asString(null)).as("linkedIssue")
                        .isEqualTo(x.key()))
                .satisfies(n -> assertThat(n.has("actor") && n.path("actor").isNull()).as("actor is null in " + n)
                        .isTrue());
    }
}
