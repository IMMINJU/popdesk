package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The headers of the mail the app sends while mail-in is on (MAIL-1, MAIL-7). */
class SentMailHeadersTest extends MailInAppTest {

    /** 32 random bytes in lower-case hex, at the domain of {@code MAIL_INBOUND_ADDRESS}. */
    static final String MESSAGE_ID = "<[0-9a-f]{64}@popdesk\\.test>";

    @Test
    @DisplayName("S12-AC-04 every mail (an invitation, an issue notification) has its own Message-ID of 64 lower-case hex digits and Auto-Submitted: auto-generated")
    void messageIdAndAutoSubmitted() {
        Company company = company();
        Session customer = customer(company.code());
        Session agent = agent(company.code());
        ReceivedMail invitation = invitationTo(customer.email());
        Issue issue = fixtures.fileIssue(customer, company.code());

        ReceivedMail answer = staffAnswer(agent, customer, issue.key());

        for (ReceivedMail m : new ReceivedMail[] {invitation, answer}) {
            assertThat(messageIdOf(m)).as("Message-ID of " + m.subject()).matches(MESSAGE_ID);
            assertThat(m.header("Auto-Submitted")).as("Auto-Submitted of " + m.subject())
                    .containsExactly("auto-generated");
        }
        assertThat(messageIdOf(answer)).as("Message-ID of the notification").isNotEqualTo(messageIdOf(invitation));
    }

    @Test
    @DisplayName("S12-AC-05 the customer's issue mail has Reply-To: MAIL_INBOUND_ADDRESS and a sent-mail record of its Message-ID and issue; the agent's mail and the invitation have neither")
    void replyToOnlyOnCustomerMail() {
        Company company = company();
        Session customer = customer(company.code());
        Session agent = agent(company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());
        IssueFlow.assignRequest(admin, issue.key(), agent.accountId()).expect(200);
        ReceivedMail toAgent = awaitIssueMail(agent.email(), issue.key());

        ReceivedMail toCustomer = staffAnswer(agent, customer, issue.key());

        assertThat(toCustomer.header("Reply-To")).as("Reply-To of the mail to the customer")
                .containsExactly(MailInServer.ADDRESS);
        assertThat(toAgent.header("Reply-To")).as("Reply-To of the mail to the agent").isEmpty();
        assertThat(invitationTo(customer.email()).header("Reply-To")).as("Reply-To of the invitation").isEmpty();
        assertThat(db.tables()).as("tables").contains("mail_sent");
        Wait.untilAsserted("a sent-mail record of the mail to the customer", () ->
                assertThat(sentRecords(messageIdOf(toCustomer), issue)).as("records of " + messageIdOf(toCustomer))
                        .isEqualTo(1));
        assertThat(sentRecords(messageIdOf(toAgent), issue)).as("records of the mail to the agent").isZero();
        assertThat(sentRecords(messageIdOf(invitationTo(customer.email())), issue)).as("records of the invitation")
                .isZero();
    }

    /** Rows of {@code mail_sent} with this Message-ID (with or without its angle brackets) and the issue. */
    private long sentRecords(String messageId, Issue issue) {
        String bare = messageId.replaceAll("^<|>$", "");
        return db.count("select count(*) from mail_sent s join issue i on i.id = s.issue_id"
                + " join company c on c.id = i.company_id"
                + " where s.message_id in (?, ?) and c.code = ? and i.number = ?",
                messageId, bare, issue.companyCode(), number(issue.key()));
    }

    static int number(String key) {
        return Integer.parseInt(key.substring(key.lastIndexOf('-') + 1));
    }
}
