package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The headers of the mail the app sends in the shared "connected" context, where mail-in is off (no IMAP keys). */
class SentMailHeadersOffTest extends ConnectedAppTest {

    @Test
    @DisplayName("S12-AC-04, S12-AC-05 with mail-in off the customer's issue mail has a Message-ID at the sender's domain and Auto-Submitted, but no Reply-To")
    void noReplyToWhenMailInIsOff() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue issue = fixtures.fileIssue(customer, company.code());

        fixtures.comment(agent, issue.key(), "Answer " + Unique.number());

        ReceivedMail m = mail().awaitMessage("mail about " + issue.key() + " to the customer",
                r -> r.isTo(customer.email()) && r.subject() != null && r.subject().contains("[" + issue.key() + "]"));
        assertThat(m.header("Message-ID")).as("Message-ID headers").hasSize(1);
        assertThat(m.header("Message-ID").get(0).trim()).as("Message-ID (MAIL_FROM is at popdesk.test)")
                .matches(SentMailHeadersTest.MESSAGE_ID);
        assertThat(m.header("Auto-Submitted")).as("Auto-Submitted").containsExactly("auto-generated");
        assertThat(m.header("Reply-To")).as("Reply-To with mail-in off").isEmpty();
    }
}
