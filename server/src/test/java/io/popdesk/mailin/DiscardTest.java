package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.MailServer;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * Mail that is discarded before it is read as a customer's (MAILIN-7, TM-22, TM-23): automated mail, a DMARC fail. A
 * discarded mail makes no issue, comment, or mail, goes to the processed folder, and keeps no sender in its record.
 *
 * <p>The senders are active customers of one company, so a mail that is not discarded would become an issue.
 */
class DiscardTest extends MailInAppTest {

    @Autowired
    private Environment environment;

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource(delimiter = '|', value = {
        "Auto-Submitted|auto-replied",
        "Auto-Submitted|Auto-Generated",
        "Precedence|bulk",
        "Precedence|list",
        "Precedence|JUNK",
        "Precedence|auto_reply",
        "X-Autoreply|yes",
        "Return-Path|<>"})
    @DisplayName("S12-AC-11 a customer's mail with an automated-mail header is discarded: no issue, no mail, in the processed folder")
    void automatedHeader(String name, String value) {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Automated " + Unique.number()).header(name, value);

        assertDiscarded(sender, mail);
    }

    @Test
    @DisplayName("S12-AC-11 a customer's mail whose top Content-Type is multipart/report is discarded")
    void deliveryReport() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Report " + Unique.number())
                .topType("multipart/report; report-type=delivery-status")
                .unnamed("message/delivery-status", "Final-Recipient: rfc822; someone@example.test\r\n"
                        .getBytes(StandardCharsets.US_ASCII));

        assertDiscarded(sender, mail);
    }

    @Test
    @DisplayName("S12-AC-11 an auto reply to an issue mail adds no comment and sends no mail")
    void automatedReplyAddsNoComment() {
        Company company = company();
        Session sender = customer(company.code());
        Issue issue = fixtures.fileIssue(sender, company.code());
        int before = comments(issue.key()).size();
        Inbound reply = mailFrom(sender).subject("Re: [" + issue.key() + "] out of office")
                .text("I am away " + Unique.number()).header("Auto-Submitted", "auto-replied");
        send(reply);

        rounds.next();

        assertThat(foldersOf(reply)).as("folders of the auto reply").containsExactly(Folder.PROCESSED);
        assertThat(comments(issue.key())).as("comments on %s", issue.key()).hasSize(before);
        awaitALaterMail();
        assertThat(mailTo(sender)).as("mail to the sender").isEmpty();
    }

    @Test
    @DisplayName("S12-AC-11 Auto-Submitted: no is not automated: the mail becomes an issue")
    void autoSubmittedNo() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Person " + Unique.number()).header("Auto-Submitted", "no");
        send(mail);

        rounds.next();

        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(1);
    }

    @ParameterizedTest(name = "From {0}")
    @ValueSource(strings = {MailServer.FROM, "support+ACME@popdesk.test", "SUPPORT@Popdesk.test"})
    @DisplayName("S12-AC-11 a mail from the sending address or from MAIL_INBOUND_ADDRESS (its +code form too, any case) is discarded as AUTOMATED and no mail goes to it")
    void fromOurOwnAddress(String from) {
        Inbound mail = Inbound.mail().from(from).text("Loop " + Unique.number());
        send(mail);

        rounds.next();

        assertThat(foldersOf(mail)).as("folders of the mail").containsExactly(Folder.PROCESSED);
        Map<String, Object> record = received(from, mail.messageId());
        assertThat(record).as("received-mail record").isNotNull();
        assertThat(record.get("outcome")).as("outcome").isEqualTo("DISCARDED");
        assertThat(record.get("reason")).as("reason").isEqualTo("AUTOMATED");
        awaitALaterMail();
        assertThat(mailIn().sentTo(from)).as("mail to %s", from).isEmpty();
        assertThat(mailIn().sentTo(MailInServer.ADDRESS)).as("mail to the incoming address").isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "mx.example; dmarc=fail (p=reject dis=none) header.from=example.test",
        "mx.example; DMARC=Fail header.from=example.test",
        "mx.example; spf=pass smtp.mailfrom=example.test; dmarc = fail header.from=example.test",
        "mx.example;\r\n dmarc=fail\r\n header.from=example.test"})
    @DisplayName("S12-AC-12 a customer's mail whose Authentication-Results says dmarc=fail (any case, spaces around =, folded) is discarded")
    void dmarcFail(String results) {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Fail " + Unique.number()).header("Authentication-Results", results);

        assertDiscarded(sender, mail);
    }

    @Test
    @DisplayName("S12-AC-12 a fail in the second of two Authentication-Results headers is enough to discard")
    void dmarcFailInTheSecondHeader() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Second " + Unique.number())
                .header("Authentication-Results", "mx.example; dmarc=pass header.from=example.test")
                .header("Authentication-Results", "relay.example; dmarc=fail header.from=example.test");

        assertDiscarded(sender, mail);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "Authentication-Results: mx.example; dmarc=pass (was dmarc=fail before forwarding) header.from=example.test",
        "Authentication-Results: mx.example; dmarc=none header.from=example.test",
        "ARC-Authentication-Results: i=1; mx.example; dmarc=fail header.from=example.test",
        "no header"})
    @DisplayName("S12-AC-12 dmarc=pass with fail only in a comment, dmarc=none, a fail in another header, or no result: the mail becomes an issue")
    void noDmarcFail(String header) {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Pass " + Unique.number());
        if (header.contains(": ")) {
            mail.header(header.substring(0, header.indexOf(": ")), header.substring(header.indexOf(": ") + 2));
        }
        send(mail);

        rounds.next();

        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dmarc=none", "no header", "dmarc=pass"})
    @DisplayName("S12-AC-12 a mail with dmarc=none or with no DMARC result is taken and adds one to popdesk.mailin.unauthenticated; a mail with dmarc=pass is taken and adds none")
    void unauthenticatedIsCounted(String result) {
        MailInMetrics metrics = new MailInMetrics(environment);
        double before = metrics.sum("popdesk_mailin_unauthenticated", "");
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Unauthenticated " + Unique.number());
        if (!result.equals("no header")) {
            mail.header("Authentication-Results", "mx.example; " + result + " header.from=example.test");
        }
        send(mail);

        rounds.next();

        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(1);
        double added = result.equals("dmarc=pass") ? 0 : 1;
        Wait.untilAsserted("popdesk_mailin_unauthenticated up by " + added, () ->
                assertThat(metrics.sum("popdesk_mailin_unauthenticated", "")).as("popdesk_mailin_unauthenticated")
                        .isEqualTo(before + added));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"AUTOMATED", "DMARC_FAIL", "UNKNOWN_SENDER"})
    @DisplayName("S12-AC-14 the record of a discarded mail keeps its key, outcome, reason, and time, but no sender address and no account")
    void discardedRecordKeepsNoSender(String reason) {
        Session customer = customer(company().code());
        Session sender = reason.equals("UNKNOWN_SENDER") ? agent() : customer;
        Inbound mail = mailFrom(sender).text("Record " + Unique.number());
        if (reason.equals("AUTOMATED")) {
            mail.header("Auto-Submitted", "auto-replied");
        } else if (reason.equals("DMARC_FAIL")) {
            mail.header("Authentication-Results", "mx.example; dmarc=fail header.from=example.test");
        }
        send(mail);

        rounds.next();

        Map<String, Object> record = received(sender.email(), mail.messageId());
        assertThat(record).as("received-mail record of the mail").isNotNull();
        assertThat(record.get("outcome")).as("outcome").isEqualTo("DISCARDED");
        assertThat(record.get("reason")).as("reason").isEqualTo(reason);
        assertThat(record.get("decided_at")).as("decided_at").isNotNull();
        assertThat(record.get("sender_address")).as("sender_address").isNull();
        assertThat(record.get("sender_account_id")).as("sender_account_id").isNull();
    }

    private void assertDiscarded(Session sender, Inbound mail) {
        send(mail);

        rounds.next();

        assertThat(foldersOf(mail)).as("folders of the mail").containsExactly(Folder.PROCESSED);
        assertThat(issuesOf(sender)).as("issues of the sender").isEmpty();
        awaitALaterMail();
        assertThat(mailTo(sender)).as("mail to the sender").isEmpty();
    }
}
