package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.trash.TrashSteps;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Replies that point at an issue the sender cannot see, or can see but not comment on (MAILIN-2, TM-03, TM-36): no
 * comment there, and notice A or B instead.
 */
class ReplyNotVisibleTest extends MailInAppTest {

    /** An issue of another company: its customer got a notification about it. */
    private record Foreign(Company company, Issue issue, String notificationId) {}

    private Foreign foreign() {
        Company company = company();
        Session customer = customer(company.code());
        Session agent = agent(company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        return new Foreign(company, issue, messageIdOf(staffAnswer(agent, customer, issue.key())));
    }

    private boolean hasComment(String key, String content) {
        return comments(key).stream().anyMatch(c -> content.equals(c.path("content").asString("")));
    }

    @Test
    @DisplayName("S12-AC-17 a reply to another company's notification without a key in the subject becomes a new issue in the sender's own company")
    void foreignMessageIdWithoutKey() {
        Foreign other = foreign();
        Company own = company();
        Session sender = customer(own.code());
        String subject = "Re: thanks " + Unique.number();
        String content = "Body " + Unique.number();

        send(mailFrom(sender).subject(subject).inReplyTo(other.notificationId()).text(content));
        rounds.next();

        JsonNode issue = onlyIssueOf(sender);
        assertThat(issue.path("companyCode").asString("")).as("company of the new issue").isEqualTo(own.code());
        assertThat(issue.path("title").asString("")).as("title of the new issue").isEqualTo(subject);
        assertThat(hasComment(other.issue().key(), content)).as("a comment on the other company's issue").isFalse();
    }

    /** One case of a key the sender cannot see: who sends, and the key in the subject. */
    private record Case(String what, Session sender, String key) {}

    @Test
    @DisplayName("S12-AC-17 a subject key of another company, of a colleague's private issue, of an issue in the trash, or of no issue gives no issue and no comment, and the same notice A")
    void keysTheSenderCannotSee() {
        Foreign other = foreign();
        Company own = company();
        Session colleague = customer(own.code());
        Issue privateOne = fixtures.fileIssue(colleague, NewIssue.in(own.code()).privateIssue());
        Session trashing = customer(own.code());
        Issue trashed = fixtures.fileIssue(trashing, own.code());
        TrashSteps.delete(admin, trashed.key());
        List<Case> cases = List.of(
                new Case("another company's issue", customer(own.code()), other.issue().key()),
                new Case("a colleague's private issue", customer(own.code()), privateOne.key()),
                new Case("an issue in the trash", trashing, trashed.key()),
                new Case("no issue", customer(own.code()), own.code() + "-999999"));
        String content = "Body " + Unique.number();

        List<Inbound> sent = new ArrayList<>();
        for (Case c : cases) {
            Inbound mail = mailFrom(c.sender()).subject("Re: [" + c.key() + "] " + Unique.number()).text(content);
            send(mail);
            sent.add(mail);
        }
        rounds.next();

        Set<String> notices = new LinkedHashSet<>();
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            ReceivedMail notice = awaitMailTo(c.sender(), NOTICE_A_SUBJECT);
            notices.add(withoutRecipient(notice, c.sender()));
            assertThat(notice.text()).as("notice A of " + c.what()).doesNotContain(c.key())
                    .doesNotContain(other.company().code()).doesNotContain(own.code());
            assertThat(issuesOf(c.sender())).as("issues of the sender, " + c.what()).isEmpty();
            assertThat(foldersOf(sent.get(i))).as("folders of the mail, " + c.what()).containsExactly(Folder.PROCESSED);
        }
        assertThat(notices).as("notice A of the four cases, without the name").hasSize(1);
        for (String key : List.of(other.issue().key(), privateOne.key())) {
            assertThat(hasComment(key, content)).as("a comment on " + key).isFalse();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"NOT_ALLOWED", "CLOSED"})
    @DisplayName("S12-AC-18 a reply to an issue the sender sees but cannot comment on (a colleague's public issue, a closed issue) gives no comment and notice B with that issue's key and link")
    void visibleButNoComment(String reason) {
        Company own = company();
        Session sender = korean(customer(own.code()));
        Issue issue;
        if (reason.equals("CLOSED")) {
            issue = fixtures.fileIssue(sender, own.code());
            IssueFlow.statusRequest(admin, issue.key(), "CLOSED").expect(200);
        } else {
            issue = fixtures.fileIssue(customer(own.code()), own.code());
        }
        String content = "Body " + Unique.number();

        send(mailFrom(sender).subject("Re: [" + issue.key() + "] " + Unique.number()).text(content));
        rounds.next();

        ReceivedMail notice = awaitMailTo(sender, "문의 " + issue.key() + "에 댓글을 달지 못했습니다");
        assertThat(notice.text()).as("notice B, " + reason)
                .contains("문의 " + issue.key())
                .contains(reason.equals("CLOSED") ? "종료되어 메일 답장을 받지 않습니다" : "문의를 올린 분만 답장할 수 있습니다")
                .contains("http://" + PORTAL_HOST + "/issues/" + issue.key());
        assertThat(hasComment(issue.key(), content)).as("a comment on " + issue.key()).isFalse();
        assertThat(mailTo(sender, NOTICE_A_SUBJECT_KO)).as("notice A to the sender").isEmpty();
    }
}
