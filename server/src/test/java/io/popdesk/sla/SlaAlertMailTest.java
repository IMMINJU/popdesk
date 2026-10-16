package io.popdesk.sla;

import static io.popdesk.sla.SlaAlertSteps.RESPONSE_BREACHED;
import static io.popdesk.sla.SlaAlertSteps.RESPONSE_WARNING;
import static io.popdesk.sla.SlaAlertSteps.SNAPSHOT_TITLE;
import static io.popdesk.sla.SlaAlertSteps.alertKinds;
import static io.popdesk.sla.SlaAlertSteps.assertSnapshot;
import static io.popdesk.sla.SlaAlertSteps.consoleLink;
import static io.popdesk.sla.SlaAlertSteps.mailAbout;
import static io.popdesk.sla.SlaAlertSteps.portalItems;
import static io.popdesk.sla.SlaAlertSteps.slaRowsOf;
import static io.popdesk.sla.SlaSteps.FIRST_RESPONSE;
import static io.popdesk.sla.SlaSteps.goal;
import static io.popdesk.sla.SlaSteps.state;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.sla.SlaSteps.Desk;
import io.popdesk.sla.SlaSteps.Policy;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver.Received;
import io.popdesk.trash.TrashSteps;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The mail and webhook messages of the SLA alerts (S8B-AC-01, S8B-AC-02, S8B-AC-16 to S8B-AC-18, S8B-AC-20): only
 * an overdue goal sends them, one mail per recipient and goal and one webhook message per goal, in the recipient's
 * language for mail and the installation's ({@code en}) for the webhook, with nothing of the customer; and a mail
 * that waits in the queue is checked again before it goes. Every day counts 24 hours here.
 *
 * <p>"No mail" is read after a later mail came ({@link TalkFlow#awaitALaterMail}), "no webhook message" after a
 * later message came, never after a fixed wait.
 */
class SlaAlertMailTest extends SlaMailAppTest {

    /** A word of the issue's content that no mail or message may hold. */
    private static String content(String word) {
        return "The invoice " + word + " is wrong.";
    }

    private Issue file(Desk d, String word) {
        return fixtures.fileIssue(d.customer(), NewIssue.in(d.code()).title(SNAPSHOT_TITLE).priority("NORMAL")
                .content(content(word)));
    }

    /** Webhook messages about the issue that are not the ones of filing and assigning it. */
    private List<Received> slaWebhooks(String key) {
        return webhook().containing(key).stream().filter(r -> r.text().contains("SLA")).toList();
    }

    /** Files another issue and waits for its webhook message: every message queued before has been sent. */
    private void awaitALaterWebhook(Desk d) {
        String later = fixtures.fileIssue(d.customer(), d.code()).key();
        webhook().await("the message of a later issue " + later, r -> r.text().contains(later));
    }

    private static String bodyForm(Received r, String key, String code) {
        return r.text().replace(consoleLink(key), "{link}").replace(key, "{key}").replace(code, "{code}");
    }

    // S8B-AC-01, S8B-AC-02, S8B-AC-18

    @Test
    @DisplayName("S8B-AC-01, S8B-AC-02, S8B-AC-18 a warning sends no mail and no webhook message; both goals overdue "
            + "send the assignee and an admin one mail per goal, and one webhook message per goal in the "
            + "installation language: the en snapshots, with no name, email, or word of the customer")
    void onlyOverdueGoalsSendMailAndOneMessagePerGoal() {
        Desk d = sla.desk();
        Session admin = alerts.newAdmin(d.admin());
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 10, 20));
        String word = Unique.text("Word");
        Issue issue = file(d, word);
        String key = issue.key();
        flow.assign(d.admin(), key, d.agent().accountId());
        sla.recalculateNow(d.admin());
        alerts.runAfterFiling(d.admin(), issue, 9);
        assertThat(alertKinds(d.agent(), key)).as("SLA items of the assignee at 9 minutes")
                .containsExactly(RESPONSE_WARNING);

        alerts.runAfterFiling(d.admin(), issue, 21);

        for (Session person : List.of(d.agent(), admin)) {
            Wait.untilAsserted("two overdue mails to " + person.email(),
                    () -> assertThat(mailAbout(mail().messagesTo(person.email()), key)).hasSize(2));
        }
        Wait.untilAsserted("two overdue webhook messages about " + key,
                () -> assertThat(slaWebhooks(key)).hasSize(2));
        TalkFlow.awaitALaterMail(fixtures, d.admin());
        awaitALaterWebhook(d);
        for (Session person : List.of(d.agent(), admin)) {
            assertThat(mailAbout(mail().messagesTo(person.email()), key)).as("mail about %s to %s", key,
                    person.role()).hasSize(2);
        }
        assertThat(mailAbout(mail().messagesTo(d.customer().email()), key)).as("mail about %s to the customer", key)
                .isEmpty();
        List<Received> messages = slaWebhooks(key);
        assertThat(messages).as("SLA webhook messages about %s", key).hasSize(2);
        for (Received message : messages) {
            String form = bodyForm(message, key, d.code());
            assertSnapshot(form.contains("first response") ? "en-sla-response-breached.json"
                    : "en-sla-resolution-breached.json", form);
            assertThat(message.text()).as("webhook message about %s", key).doesNotContain(word)
                    .doesNotContainIgnoringCase(d.customer().email()).doesNotContain(d.customer().name());
        }
        assertThat(messages.stream().map(m -> bodyForm(m, key, d.code())).distinct()).as("the two messages differ")
                .hasSize(2);
    }

    // S8B-AC-16

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"en", "ko"})
    @DisplayName("S8B-AC-16 the overdue mails are in the recipient's language: the subject holds the key and the "
            + "goal, the text the title, the company code, and the console link, and the snapshot; no name, email, "
            + "or word of the customer")
    void overdueMailInTheRecipientsLanguage(String language) {
        Desk d = sla.desk();
        SlaAlertSteps.language(d.agent(), language);
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 10, 20));
        String word = Unique.text("Word");
        Issue issue = file(d, word);
        String key = issue.key();
        flow.assign(d.admin(), key, d.agent().accountId());
        sla.recalculateNow(d.admin());

        alerts.runAfterFiling(d.admin(), issue, 21);

        List<ReceivedMail> mails = Wait.until("two overdue mails to the assignee", () -> {
            List<ReceivedMail> found = mailAbout(mail().messagesTo(d.agent().email()), key);
            return found.size() >= 2 ? found : null;
        });
        assertThat(mails).as("mail about %s to the assignee", key).hasSize(2);
        String responseWord = language.equals("ko") ? "첫 응답" : "first response";
        for (ReceivedMail m : mails) {
            assertThat(m.to()).as("To").containsExactly(d.agent().email());
            assertThat(m.subject()).as("subject").contains("[" + key + "]");
            assertThat(m.text()).as("text of the mail").contains(consoleLink(key)).contains(d.code())
                    .contains(SNAPSHOT_TITLE.replace('\n', ' '));
            String all = m.subject() + "\n" + m.text();
            assertThat(all).as("the whole mail").doesNotContain(word)
                    .doesNotContainIgnoringCase(d.customer().email()).doesNotContain(d.customer().name());
            String goal = m.subject().contains(responseWord) ? "response" : "resolution";
            assertSnapshot(language + "-mail-sla-" + goal + "-breached.txt",
                    SlaAlertSteps.snapshotForm(m, key, d.code(), consoleLink(key)));
        }
        assertThat(mails.stream().map(ReceivedMail::subject).distinct()).as("the subjects of the two mails")
                .hasSize(2);
    }

    // S8B-AC-17

    /** What happens while the overdue mail waits in the queue. */
    enum Meanwhile {
        TRASHED,
        RECIPIENT_SUSPENDED,
        RECIPIENT_OFF_THE_COMPANY,
        RESOLVED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Meanwhile.class)
    @DisplayName("S8B-AC-17 an overdue mail waiting in the queue is not sent to an agent once the issue is in the "
            + "trash, or the agent is suspended or taken off the company, while the admin's goes unless the issue is "
            + "in the trash; a goal that stopped meanwhile is mailed")
    void queuedOverdueMailIsCheckedAgain(Meanwhile meanwhile) {
        Desk d = sla.desk();
        Session admin = alerts.newAdmin(d.admin());
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 10, null));
        Issue issue = file(d, Unique.text("Word"));
        String key = issue.key();
        flow.assign(d.admin(), key, d.agent().accountId());
        sla.recalculateNow(d.admin());
        holdMail();
        alerts.runAfterFiling(d.admin(), issue, 11);
        heldTo(d.agent().email());
        heldTo(admin.email());

        switch (meanwhile) {
            case TRASHED -> TrashSteps.delete(d.admin(), key);
            case RECIPIENT_SUSPENDED -> flow.suspend(d.admin(), d.agent().accountId());
            case RECIPIENT_OFF_THE_COMPANY -> flow.unassignAgent(d.admin(), d.code(), d.agent().accountId());
            case RESOLVED -> flow.moveTo(d.agent(), key, "RESOLVED");
        }
        letMailGo();

        if (meanwhile == Meanwhile.RESOLVED) {
            Wait.untilAsserted("the overdue mail to the agent",
                    () -> assertThat(mailAbout(mail().messagesTo(d.agent().email()), key)).hasSize(1));
        } else {
            Wait.untilAsserted("the mail row of the agent dropped", () -> assertThat(db.count(
                    "select count(*) from outbox where channel = 'MAIL' and recipient_account_id = ?",
                    d.agent().accountId())).isZero());
        }
        if (meanwhile != Meanwhile.TRASHED) {
            Wait.untilAsserted("the overdue mail to the admin",
                    () -> assertThat(mailAbout(mail().messagesTo(admin.email()), key)).hasSize(1));
        }
        TalkFlow.awaitALaterMail(fixtures, sla.admin());
        assertThat(mailAbout(mail().messagesTo(d.agent().email()), key)).as("overdue mail to the agent")
                .hasSize(meanwhile == Meanwhile.RESOLVED ? 1 : 0);
        assertThat(mailAbout(mail().messagesTo(admin.email()), key)).as("overdue mail to the admin")
                .hasSize(meanwhile == Meanwhile.TRASHED ? 0 : 1);
    }

    // S8B-AC-04, S8B-AC-05

    @Test
    @DisplayName("S8B-AC-04, S8B-AC-05 an overdue first response goal keeps its mark while the issue is in the "
            + "trash: put in the trash, restored still overdue, and two more runs on, the assignee and an admin still "
            + "have one SLA_RESPONSE_BREACHED item and one mail each, and there is one webhook message")
    void theTrashKeepsTheMark() {
        Desk d = sla.desk();
        Session admin = alerts.newAdmin(d.admin());
        SlaSteps.change(d.admin(), Policy.off().target("NORMAL", 10, null));
        Issue issue = file(d, Unique.text("Word"));
        String key = issue.key();
        flow.assign(d.admin(), key, d.agent().accountId());
        sla.recalculateNow(d.admin());
        alerts.runAfterFiling(d.admin(), issue, 11);
        for (Session person : List.of(d.agent(), admin)) {
            assertThat(alertKinds(person, key)).as("SLA items of %s past the due time", person.role())
                    .containsExactly(RESPONSE_BREACHED);
            Wait.untilAsserted("the overdue mail to " + person.email(),
                    () -> assertThat(mailAbout(mail().messagesTo(person.email()), key)).hasSize(1));
        }

        TrashSteps.delete(d.admin(), key);
        alerts.runs(d.admin(), 2);
        TrashSteps.restore(d.admin(), key);
        assertThat(state(goal(SlaSteps.sla(d.agent(), key), FIRST_RESPONSE))).as("first response after the restore")
                .isEqualTo("BREACHED");
        alerts.runs(d.admin(), 2);

        TalkFlow.awaitALaterMail(fixtures, d.admin());
        awaitALaterWebhook(d);
        for (Session person : List.of(d.agent(), admin)) {
            assertThat(alertKinds(person, key)).as("SLA items of %s after the trash and two runs", person.role())
                    .containsExactly(RESPONSE_BREACHED);
            assertThat(mailAbout(mail().messagesTo(person.email()), key)).as("mail about %s to %s", key,
                    person.role()).hasSize(1);
        }
        assertThat(slaWebhooks(key)).as("SLA webhook messages about %s", key).hasSize(1);
    }

    // S8B-AC-20

    @Test
    @DisplayName("S8B-AC-20 an issue of a company with no assigned agent and no assignee is alerted to the admins "
            + "only: its reporter and another customer of the company get no item, no change of the unread count, "
            + "and no mail")
    void noAgentMeansAdminsOnly() {
        Session first = sla.admin();
        String code = fixtures.createCompany(first).code();
        Session reporter = fixtures.customer(first, code);
        Session colleague = fixtures.customer(first, code);
        Session admin = alerts.newAdmin(first);
        SlaSteps.change(first, Policy.off().target("NORMAL", 10, null));
        Issue issue = fixtures.fileIssue(reporter, NewIssue.in(code).priority("NORMAL"));
        String key = issue.key();
        Map<Long, Long> unread = Map.of(reporter.accountId(), TalkFlow.unreadCount(reporter),
                colleague.accountId(), TalkFlow.unreadCount(colleague));
        sla.recalculateNow(first);

        alerts.runAfterFiling(first, issue, Duration.ofMinutes(9));
        alerts.runAfterFiling(first, issue, Duration.ofMinutes(10).plusSeconds(30));

        Session again = fixtures.signInAgain(admin);
        assertThat(alertKinds(again, key)).as("SLA items of the admin")
                .containsExactly(RESPONSE_BREACHED, RESPONSE_WARNING);
        Wait.untilAsserted("the overdue mail to the admin",
                () -> assertThat(mailAbout(mail().messagesTo(admin.email()), key)).hasSize(1));
        TalkFlow.awaitALaterMail(fixtures, sla.admin());
        for (Session customer : List.of(reporter, colleague)) {
            Session signedIn = fixtures.signInAgain(customer);
            assertThat(portalItems(signedIn, key)).as("portal items of %s", customer.email()).isEmpty();
            assertThat(TalkFlow.unreadCount(signedIn)).as("unread count of %s", customer.email())
                    .isEqualTo(unread.get(customer.accountId()));
            assertThat(mailAbout(mail().messagesTo(customer.email()), key)).as("mail about %s to %s", key,
                    customer.email()).isEmpty();
            // The portal hides kinds not meant for customers, so only the table shows an SLA row given to one.
            assertThat(slaRowsOf(db, customer)).as("SLA rows of %s in the notification table", customer.email())
                    .isZero();
        }
    }
}
