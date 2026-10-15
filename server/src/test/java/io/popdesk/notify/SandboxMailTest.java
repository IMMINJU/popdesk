package io.popdesk.notify;

import static io.popdesk.notify.Delivery.SANDBOX_TO;
import static io.popdesk.notify.NotifySteps.everything;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.issue.TalkFlow;
import io.popdesk.manager.ManagerSteps;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.SmtpGate.Held;
import io.popdesk.notify.TalkMails.Mail;
import io.popdesk.support.AppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.task.TaskSteps;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * Mail in a sandbox with an address: every mail goes to that one address and none to the person it
 * was for, it says so in its subject and first line, and a mail that is no longer to be sent is not
 * sent there either.
 *
 * <p>The kinds of mail are the eleven names in {@link #KINDS}. A test here reads the list of kinds from
 * the app's source as text ({@link #KIND_SOURCE}) and fails when the two differ, so a new kind of mail
 * cannot be added without a scenario here. It also reads the kind of each queued row in the
 * {@code outbox} table while the mail server refuses mail, to see that each scenario queues the kind
 * it is named for.
 */
class SandboxMailTest extends Delivery.SandboxWithAddress {

    /** Every kind of mail the send queue holds, by the name in {@code outbox.kind}. */
    static final Set<String> KINDS = Set.of("INVITATION", "PASSWORD_RESET", "STAFF_COMMENT", "ISSUE_FILED_ON_BEHALF",
            "ISSUE_RESOLVED", "ISSUE_CLOSED", "ISSUE_REOPENED", "MENTIONED", "ASSIGNED", "MEMBERSHIP_REQUESTED",
            "TASK_ASSIGNED");

    /** Where the app lists its kinds of mail, from the module's folder. */
    static final Path KIND_SOURCE = Path.of("src", "main", "java", "io", "popdesk", "notification", "MailKind.java");

    private static final String PREFIX = "[sandbox]";

    private IssueFlow flow;
    private SmtpGate gate;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        gate = SmtpGate.shared();
    }

    /** The constants of the enum in {@link #KIND_SOURCE}, read as text. */
    private static Set<String> kindsInSource() {
        String source;
        try {
            source = Files.readString(KIND_SOURCE, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String code = source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
        Matcher body = Pattern.compile("(?s)enum\\s+MailKind\\s*\\{(.*?)[;}]").matcher(code);
        if (!body.find()) {
            throw new AssertionError("No enum MailKind in " + KIND_SOURCE);
        }
        Set<String> out = new TreeSet<>();
        for (String name : body.group(1).split(",")) {
            if (!name.isBlank()) {
                out.add(name.strip());
            }
        }
        return out;
    }

    /** A mail row of the queue: its kind and the account it is for. */
    private record Queued(String kind, long recipient) {}

    /** The mail rows queued at {@code at} for one of {@code accounts}. */
    private List<Queued> queuedAt(Instant at, Set<Long> accounts) {
        List<Queued> out = new ArrayList<>();
        for (Map<String, Object> row : db.rows("select kind, recipient_account_id, created_at from outbox "
                + "where channel = 'MAIL'")) {
            Object recipient = row.get("recipient_account_id");
            if (recipient instanceof Number n && accounts.contains(n.longValue())
                    && at.equals(Rows.instant(row.get("created_at")))) {
                out.add(new Queued(String.valueOf(row.get("kind")), n.longValue()));
            }
        }
        return out;
    }

    private List<Held> heldAtSandboxAddress() {
        return gate.held().stream().filter(h -> h.isTo(SANDBOX_TO)).toList();
    }

    @Test
    @DisplayName("S4B-AC-01 in a sandbox one mail of every kind goes to the sandbox address, each naming the "
            + "account it was for, and the addresses of those accounts get none")
    void everyKindGoesToTheSandboxAddress() {
        Cast c = cast();
        Session mentioned = person(c.admin(), Role.AGENT, "Mentioned", c.code());
        Session manager = person(c.admin(), Role.CUSTOMER, "Manager", c.code());
        ManagerSteps.mark(c.admin(), c.code(), manager.accountId());
        Company elsewhere = fixtures.createCompany(c.admin());
        Session wanted = person(c.admin(), Role.CUSTOMER, "Wanted", elsewhere.code());
        String answered = fixtures.fileIssue(c.customer(), c.code()).key();
        String resolved = fixtures.fileIssue(c.customer(), c.code()).key();
        String closed = fixtures.fileIssue(c.customer(), c.code()).key();
        String reopened = fixtures.fileIssue(c.customer(), c.code()).key();
        String talkedAbout = fixtures.fileIssue(c.customer(), c.code()).key();
        String handedOver = fixtures.fileIssue(c.customer(), c.code()).key();
        String tasked = fixtures.fileIssue(c.customer(), c.code()).key();
        flow.close(c.customer(), reopened);
        String invitedEmail = Unique.email("invited");
        List<Session> people = List.of(c.customer(), c.agent(), mentioned, manager, wanted);
        Map<Long, Integer> redirectedBefore = new LinkedHashMap<>();
        for (Session person : people) {
            redirectedBefore.put(person.accountId(), Delivery.redirected(mail(), person.accountId()).size());
        }
        int before = mail().messagesTo(SANDBOX_TO).size();
        Instant at = tick();
        gate.hold();

        long invited = c.admin().client().post("/api/console/invitations")
                .json(Map.of("email", invitedEmail, "name", Unique.text("Invited"), "role", "AGENT"))
                .expect(201).jsonLong("accountId");
        portal().post("/api/public/password-reset/request").json(Map.of("email", wanted.email())).expect(202);
        fixtures.comment(c.agent(), answered, "Answer " + Unique.number());
        flow.fileOnBehalf(c.agent(), OnBehalf.of(c.code(), c.customer().accountId()));
        flow.changeStatus(c.agent(), resolved, "IN_PROGRESS");
        flow.changeStatus(c.agent(), resolved, "RESOLVED");
        flow.changeStatus(c.agent(), closed, "CLOSED");
        flow.reopen(c.agent(), reopened);
        TalkFlow.mention(c.agent(), talkedAbout, true, List.of(mentioned.accountId()));
        flow.assign(c.admin(), handedOver, c.agent().accountId());
        ManagerSteps.invited(manager, c.code(), wanted.email(), Unique.text("Typed"));
        TaskSteps.create(c.admin(), tasked, TaskSteps.body("title", TaskSteps.title("Sandbox"), "assigneeId",
                c.agent().accountId()));

        List<Queued> expected = List.of(new Queued("INVITATION", invited),
                new Queued("PASSWORD_RESET", wanted.accountId()),
                new Queued("STAFF_COMMENT", c.customer().accountId()),
                new Queued("ISSUE_FILED_ON_BEHALF", c.customer().accountId()),
                new Queued("ISSUE_RESOLVED", c.customer().accountId()),
                new Queued("ISSUE_CLOSED", c.customer().accountId()),
                new Queued("ISSUE_REOPENED", c.customer().accountId()),
                new Queued("MENTIONED", mentioned.accountId()),
                new Queued("ASSIGNED", c.agent().accountId()),
                new Queued("MEMBERSHIP_REQUESTED", c.admin().accountId()),
                new Queued("TASK_ASSIGNED", c.agent().accountId()));
        Set<Long> accounts = new TreeSet<>(expected.stream().map(Queued::recipient).toList());
        accounts.add(manager.accountId());
        Wait.untilAsserted("one queued mail row per kind", () -> assertThat(queuedAt(at, accounts))
                .containsExactlyInAnyOrderElementsOf(expected));
        assertThat(expected.stream().map(Queued::kind).toList()).as("the kinds this test queues")
                .containsExactlyInAnyOrderElementsOf(KINDS);
        assertThat(kindsInSource()).as("the kinds of mail in %s: a new kind needs a scenario in this test",
                KIND_SOURCE).isEqualTo(new TreeSet<>(KINDS));

        Wait.untilAsserted("every one of the " + expected.size() + " mails tried at the sandbox address",
                () -> assertThat(heldAtSandboxAddress()).hasSizeGreaterThanOrEqualTo(expected.size()));
        List<String> addresses = new ArrayList<>(people.stream().map(Session::email).toList());
        addresses.add(invitedEmail);
        addresses.add(AppTest.ADMIN_EMAIL);
        for (String address : addresses) {
            assertThat(gate.held()).as("mail tried at %s", address).noneMatch(h -> h.isTo(address));
        }

        gate.pass();
        clock.advance(Duration.ofSeconds(1));
        Wait.untilAsserted("the " + expected.size() + " mails at the sandbox address",
                () -> assertThat(mail().messagesTo(SANDBOX_TO)).hasSize(before + expected.size()));
        awaitALaterMail(c.admin());
        assertThat(mail().messagesTo(SANDBOX_TO)).as("mail at the sandbox address, with the one that came later")
                .hasSize(before + expected.size() + 1);
        for (Session person : people) {
            long wantedCount = expected.stream().filter(q -> q.recipient() == person.accountId()).count();
            assertThat(Delivery.redirected(mail(), person.accountId())).as("mail whose first line names %s",
                    person.email()).hasSize(redirectedBefore.get(person.accountId()) + (int) wantedCount);
        }
        assertThat(Delivery.redirected(mail(), invited)).as("mail whose first line names the invited account")
                .hasSize(1);
        assertThat(Delivery.redirected(mail(), c.admin().accountId())).as("mail whose first line names the admin")
                .hasSize(1);
        for (String address : addresses) {
            assertThat(mail().messagesTo(address)).as("mail to %s", address).isEmpty();
        }
        Map<Long, String> addressOf = new LinkedHashMap<>();
        people.forEach(person -> addressOf.put(person.accountId(), person.email()));
        addressOf.put(invited, invitedEmail);
        addressOf.put(c.admin().accountId(), AppTest.ADMIN_EMAIL);
        addressOf.forEach((account, address) -> {
            for (ReceivedMail m : Delivery.redirected(mail(), account)) {
                assertThat(Delivery.foundIn(everything(m), address)).as("the address of account %s in the mail "
                        + "redirected from it: %s", account, m.subject()).isEmpty();
            }
        });
        // Mail was refused and then let through above; neither way may log the address.
        assertThat(log.lines()).as("log lines read").isNotEmpty();
        assertThat(Delivery.foundIn(log.all(), SANDBOX_TO)).as("the sandbox address in the whole log").isEmpty();
    }

    @Test
    @DisplayName("S4B-AC-01 in a sandbox the test mail goes to the sandbox address and not to the admin's")
    void testMailGoesToTheSandboxAddress() {
        Session admin = admin();
        int before = mail().messagesTo(SANDBOX_TO).size();

        JsonNode result = Delivery.testMail(admin);

        assertThat(result.path("ok").asBoolean(false)).as("ok of %s", result).isTrue();
        Wait.untilAsserted("the test mail at the sandbox address",
                () -> assertThat(mail().messagesTo(SANDBOX_TO)).hasSize(before + 1));
        assertThat(mail().messagesTo(admin.email())).as("mail to the admin's own address").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Mail.class, names = {"ISSUE_CLOSED", "ASSIGNED"})
    @DisplayName("S4B-AC-02 a redirected mail has [sandbox] before its subject and the account ID of the person "
            + "it was for in its first line, never their address, and is otherwise the mail they would get")
    void redirectedMailSaysSo(Mail kind) {
        Cast c = cast();
        String key = TalkMails.issue(fixtures, new Scene(c.admin(), c.company(), c.customer(), c.agent()),
                IssueFlow.word()).key();
        Session recipient;
        String link;
        if (kind == Mail.ISSUE_CLOSED) {
            flow.changeStatus(c.agent(), key, "CLOSED");
            recipient = c.customer();
            link = NotifySteps.portalIssueLink(key);
        } else {
            flow.assign(c.admin(), key, c.agent().accountId());
            recipient = c.agent();
            link = NotifySteps.consoleIssueLink(key);
        }

        ReceivedMail m = Delivery.awaitRedirected(mail(), recipient.accountId(), x -> x.text().contains(key));

        assertThat(m.to()).as("To").containsExactly(SANDBOX_TO);
        assertThat(m.cc()).as("Cc").isEmpty();
        assertThat(m.subject()).as("subject").startsWith(PREFIX);
        assertThat(Delivery.foundIn(everything(m), recipient.email())).as("the address of %s in the whole mail",
                recipient.role()).isEmpty();
        String text = m.text().replace("\r\n", "\n");
        String first = Delivery.firstLine(m);
        String rest = text.substring(text.indexOf(first) + first.length()).replaceFirst("^\\n+", "");
        ReceivedMail original = new ReceivedMail(m.from(), m.to(), m.cc(),
                m.subject().substring(PREFIX.length()).stripLeading(), rest, m.headers(), m.raw());
        WebhookSnapshots.assertMatches(kind.snapshot("en"), TalkMails.snapshotForm(original, key, link, "{key}"));
    }

    /** What happens to a mail's recipient or comment while the mail waits. */
    enum Gone {
        RECIPIENT_SUSPENDED,
        NEW_ASSIGNEE_OFF_THE_COMPANY,
        COMMENT_DELETED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Gone.class)
    @DisplayName("S4B-AC-05 a mail that waits in a sandbox is checked against the person it was for: once they "
            + "are suspended or cannot see the issue, or the comment is deleted, the sandbox address gets none")
    void checksBeforeSendingUseTheOriginalRecipient(Gone gone) {
        Cast c = cast();
        String key = fixtures.fileIssue(c.customer(), c.code()).key();
        // A customer taken out of the company still sees the issues they reported (PROJ-4), so the
        // recipient who stops seeing the issue is an agent taken off the company.
        var recipient = gone == Gone.NEW_ASSIGNEE_OFF_THE_COMPANY ? c.agent() : c.customer();
        gate.hold();
        Comment answer = null;
        if (gone == Gone.NEW_ASSIGNEE_OFF_THE_COMPANY) {
            flow.assign(c.admin(), key, c.agent().accountId());
        } else {
            answer = fixtures.comment(c.agent(), key, "Answer " + Unique.number());
        }
        Wait.until("the mail tried at the sandbox address",
                () -> heldAtSandboxAddress().stream().filter(h -> h.text().contains(key)).findFirst().orElse(null));

        switch (gone) {
            case RECIPIENT_SUSPENDED -> flow.suspend(c.admin(), c.customer().accountId());
            case NEW_ASSIGNEE_OFF_THE_COMPANY -> flow.unassignAgent(c.admin(), c.code(), c.agent().accountId());
            case COMMENT_DELETED -> TalkFlow.delete(c.agent(), key, answer.id());
        }
        gate.pass();
        clock.advance(Duration.ofSeconds(1));

        awaitALaterMail(c.admin());
        assertThat(mail().messages()).as("mail about %s at any address", key)
                .noneMatch(m -> (m.subject() + "\n" + m.text()).contains(key));
        Wait.untilAsserted("the mail row of the recipient dropped", () -> assertThat(db.count(
                "select count(*) from outbox where channel = 'MAIL' and recipient_account_id = ?",
                recipient.accountId())).isZero());
        assertThat(mail().messages()).as("mail about %s at any address, after its row was dropped", key)
                .noneMatch(m -> (m.subject() + "\n" + m.text()).contains(key));
    }
}
