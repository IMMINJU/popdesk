package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.everything;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.manager.ManagerSteps;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.support.AppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.TestClock;
import io.popdesk.support.Unique;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The six mails of slice 4 (S4-AC-48 to S4-AC-53), for the English and the Korean test: what makes each
 * of them, the snapshot form of a mail, and what a mail must and must not hold.
 *
 * <p>The snapshot is {@code Subject: <subject>}, an empty line, and the text, with the link as
 * {@code {link}}, the issue key as {@code {key}}, and for the membership request the company code as
 * {@code {code}}. It is compared with {@link WebhookSnapshots#assertMatches}, against
 * {@code notify/__snapshots__/<language>-mail-<name>.txt}.
 */
final class TalkMails {

    /** The new mails, by the name of their snapshot file. */
    enum Mail {
        ISSUE_RESOLVED,
        ISSUE_CLOSED,
        ISSUE_REOPENED,
        MENTIONED,
        ASSIGNED,
        MEMBERSHIP_REQUESTED;

        String snapshot(String language) {
            return language + "-mail-" + name().toLowerCase(Locale.ROOT).replace('_', '-') + ".txt";
        }
    }

    /**
     * A mail that was caused: who gets it, the issue key (for the membership request the company code),
     * the link it must hold, whether that is the portal's, the staff whose name and email it must not
     * hold, and words of the issue, the comment, or the request it must not hold either.
     */
    record Caused(Mail mail, Session recipient, String key, String link, boolean toCustomer, List<Session> staff,
            List<String> words) {}

    private TalkMails() {}

    static String consoleCompanyLink(String code) {
        return "http://" + AppTest.CONSOLE_HOST + "/companies/" + code;
    }

    /** A public issue of the scene's customer, titled {@link WebhookSnapshots#TITLE}, whose content holds {@code word}. */
    static Issue issue(Fixtures fixtures, Scene s, String word) {
        return fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).title(WebhookSnapshots.TITLE)
                .content("The invoice " + word + " is wrong."));
    }

    /**
     * Does what queues the mail, in a scene of its own. The recipient gets no other notification mail
     * on the way.
     */
    static Caused cause(Mail mail, Fixtures fixtures, IssueFlow flow, NotifySteps steps) {
        Scene s = steps.scene();
        String word = IssueFlow.word();
        if (mail == Mail.MEMBERSHIP_REQUESTED) {
            Session approver = steps.person(s.admin(), Role.ADMIN, "Approver", null);
            Session manager = ManagerSteps.manager(fixtures, s.admin(), s.code());
            Company elsewhere = fixtures.createCompany(s.admin());
            Session target = steps.person(s.admin(), Role.CUSTOMER, "Wanted", elsewhere.code());
            String typed = "Typed " + word;
            ManagerSteps.invited(manager, s.code(), target.email(), typed);
            return new Caused(mail, approver, s.code(), consoleCompanyLink(s.code()), false, List.of(),
                    List.of(typed, word, target.email(), target.name(), manager.email()));
        }
        String key = issue(fixtures, s, word).key();
        String portal = NotifySteps.portalIssueLink(key);
        String console = NotifySteps.consoleIssueLink(key);
        switch (mail) {
            case ISSUE_RESOLVED -> {
                flow.changeStatus(s.agent(), key, "IN_PROGRESS");
                flow.changeStatus(s.agent(), key, "RESOLVED");
                return new Caused(mail, s.customer(), key, portal, true, List.of(s.agent()), List.of(word));
            }
            case ISSUE_CLOSED -> {
                flow.changeStatus(s.agent(), key, "CLOSED");
                return new Caused(mail, s.customer(), key, portal, true, List.of(s.agent()), List.of(word));
            }
            case ISSUE_REOPENED -> {
                flow.close(s.customer(), key);
                flow.reopen(s.agent(), key);
                return new Caused(mail, s.customer(), key, portal, true, List.of(s.agent()), List.of(word));
            }
            case MENTIONED -> {
                Session mentioned = steps.person(s.admin(), Role.AGENT, "Mentioned", null);
                fixtures.assignAgent(s.admin(), s.code(), mentioned.accountId());
                String said = IssueFlow.word();
                TalkFlow.add(s.agent(), key, TalkFlow.body("content", "Please look at " + said + ".", "internal", true,
                        "mentions", List.of(mentioned.accountId())));
                return new Caused(mail, mentioned, key, console, false, List.of(s.agent()), List.of(word, said));
            }
            case ASSIGNED -> {
                Session assigner = steps.person(s.admin(), Role.ADMIN, "Assigner", null);
                flow.assign(assigner, key, s.agent().accountId());
                return new Caused(mail, s.agent(), key, console, false, List.of(assigner), List.of(word));
            }
            default -> throw new AssertionError("No scenario for " + mail);
        }
    }

    static String snapshotForm(ReceivedMail m, Caused c) {
        return snapshotForm(m, c.key(), c.link(), c.mail() == Mail.MEMBERSHIP_REQUESTED ? "{code}" : "{key}");
    }

    /** The mail with its link as {@code {link}} and {@code key} as {@code placeholder}. */
    static String snapshotForm(ReceivedMail m, String key, String link, String placeholder) {
        String text = m.text().replace("\r\n", "\n").replace(link, "{link}").replace(key, placeholder).stripTrailing();
        return "Subject: " + m.subject().replace(key, placeholder) + "\n\n" + text;
    }

    /**
     * One recipient; the link of the recipient's app and not the other app's host; {@code [key]} in the
     * subject of a mail about an issue; nothing of the staff who did it, and no word of the content.
     */
    static void assertLinksAndHides(ReceivedMail m, Caused c) {
        assertThat(m.to()).as("To").containsExactly(c.recipient().email());
        assertThat(m.cc()).as("Cc").isEmpty();
        assertThat(m.text()).as("text of the mail").contains(c.link())
                .doesNotContain(c.toCustomer() ? AppTest.CONSOLE_HOST : AppTest.PORTAL_HOST);
        if (c.mail() != Mail.MEMBERSHIP_REQUESTED) {
            assertThat(m.subject()).as("subject").contains("[" + c.key() + "]");
        }
        assertThat(m.subject()).as("subject").doesNotContain("\n").doesNotContain("\r");
        String all = everything(m);
        for (Session staff : c.staff()) {
            assertThat(all).as("the whole mail, name and email of %s", staff.role())
                    .doesNotContainIgnoringCase(staff.name()).doesNotContainIgnoringCase(staff.email());
        }
        for (String word : c.words()) {
            assertThat(all).as("the whole mail").doesNotContainIgnoringCase(word);
        }
    }

    /**
     * The table of mail queue rows, as {@code MailQueueRowTest} finds it: where an invitation's row is
     * while the gate refuses its mail, and from where it goes once the mail is out. Leaves the gate open.
     */
    static String mailQueueTable(Rows rows, SmtpGate gate, TestClock clock, MailServer mail, Session admin) {
        String email = Unique.email("probe");
        gate.hold();
        Snapshot before = rows.snapshot();
        admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", "Probe " + Unique.number(), "role", "AGENT"))
                .expect(201);
        gate.awaitHeldTo(email);
        List<Row> added = rows.added(before);
        gate.pass();
        clock.advance(Duration.ofSeconds(1));
        mail.awaitMessageTo(email);
        List<Row> gone = rows.awaitGone("the probe's mail row deleted after the mail went out", added);
        assertThat(gone).as("rows that went away once the probe's mail was sent").hasSize(1);
        return gone.getFirst().table();
    }

    /** The rows of the mail queue table among {@code added} that name the recipient's account. */
    static List<Row> mailRowsTo(List<Row> added, String queueTable, Session recipient) {
        return added.stream().filter(r -> r.table().equals(queueTable))
                .filter(r -> !r.columnsEqualTo(recipient.accountId()).isEmpty()).toList();
    }
}
