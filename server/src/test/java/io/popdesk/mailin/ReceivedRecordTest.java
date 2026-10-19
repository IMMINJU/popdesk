package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.privacy.PrivacySteps;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.trash.TrashSteps;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The received-mail record (MAILIN-6) and the sent-mail record (MAIL-7) in the database, which no API shows: one row per
 * key with its outcome and its issue or comment, the sender cleared when the account is anonymized, and the links
 * cleared when the issue is purged.
 */
class ReceivedRecordTest extends MailInAppTest {

    @Test
    @DisplayName("S12-AC-30 a new-issue mail leaves one record ISSUE linked to the issue with its sender, and a reply leaves one record COMMENT linked to the comment")
    void recordLinksIssueAndComment() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("New " + Unique.number());
        send(mail);
        rounds.next();
        String key = onlyIssueOf(sender).path("key").asString();
        String answer = "Reply " + Unique.number();
        Inbound reply = mailFrom(sender).subject("Re: [" + key + "] question").text(answer);
        send(reply);

        rounds.next();

        Map<String, Object> issueRecord = received(sender.email(), mail.messageId());
        assertThat(issueRecord).as("record of the new-issue mail").isNotNull();
        assertThat(issueRecord.get("outcome")).as("outcome").isEqualTo("ISSUE");
        assertThat(issueRecord.get("issue_id")).as("issue_id").isEqualTo(issueId(key));
        assertThat(issueRecord.get("sender_address")).as("sender_address").isEqualTo(sender.email());
        assertThat(((Number) issueRecord.get("sender_account_id")).longValue()).as("sender_account_id")
                .isEqualTo(sender.accountId());
        assertThat(issueRecord.get("decided_at")).as("decided_at").isNotNull();
        Map<String, Object> commentRecord = received(sender.email(), reply.messageId());
        assertThat(commentRecord).as("record of the reply").isNotNull();
        assertThat(commentRecord.get("outcome")).as("outcome").isEqualTo("COMMENT");
        assertThat(((Number) commentRecord.get("comment_id")).longValue()).as("comment_id")
                .isEqualTo(commentWith(key, answer).path("id").asLong());
    }

    @Test
    @DisplayName("S12-AC-30 a mail that comes twice has one record, kept one by a unique constraint on the key, and a notice has its record NOTICE without an issue")
    void oneRecordPerKey() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Twice " + Unique.number());
        send(mail);
        send(mail);
        Session twoCompanies = customer(company().code(), company().code());
        Inbound noCompany = mailFrom(twoCompanies).text("Which company " + Unique.number());
        send(noCompany);

        rounds.next();

        assertThat(receivedRows(sender.email(), mail.messageId())).as("records of the mail sent twice").hasSize(1);
        assertThat(db.constraints("mail_received").values()).as("constraints of mail_received")
                .anyMatch(c -> c.replace(" ", "").contains("UNIQUE(message_key)"));
        Map<String, Object> notice = received(twoCompanies.email(), noCompany.messageId());
        assertThat(notice).as("record of the mail without a company").isNotNull();
        assertThat(notice.get("outcome")).as("outcome").isEqualTo("NOTICE");
        assertThat(notice.get("reason")).as("reason").isEqualTo("NO_COMPANY");
        assertThat(notice.get("issue_id")).as("issue_id").isNull();
    }

    @Test
    @DisplayName("S12-AC-31 anonymizing a customer clears the sender address of the records of the mail they sent")
    void anonymizingClearsTheSender() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Anonymous later " + Unique.number());
        send(mail);
        rounds.next();
        assertThat(received(sender.email(), mail.messageId())).as("record of the mail").isNotNull()
                .containsEntry("sender_address", sender.email());

        PrivacySteps.anonymize(admin, sender.accountId());

        Map<String, Object> record = received(sender.email(), mail.messageId());
        assertThat(record).as("record after anonymizing").isNotNull();
        assertThat(record.get("sender_address")).as("sender_address after anonymizing").isNull();
        assertThat(record.get("outcome")).as("outcome after anonymizing").isEqualTo("ISSUE");
    }

    @Test
    @DisplayName("S12-AC-32 purging an issue made by mail deletes its sent-mail records and clears the issue and comment of its received-mail records, which keep their outcome")
    void purgingClearsTheLinks() {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Purged later " + Unique.number());
        send(mail);
        rounds.next();
        String key = onlyIssueOf(sender).path("key").asString();
        Object id = issueId(key);
        ReceivedMail notification = staffAnswer(admin, sender, key);
        Inbound reply = mailFrom(sender).inReplyTo(messageIdOf(notification)).subject("Re: thanks")
                .text("Thanks " + Unique.number());
        send(reply);
        rounds.next();
        assertThat(db.count("select count(*) from mail_sent where issue_id = ?", id)).as("sent-mail records of %s",
                key).isPositive();
        assertThat(received(sender.email(), reply.messageId())).as("record of the reply").isNotNull()
                .containsEntry("outcome", "COMMENT");

        TrashSteps.delete(admin, key);
        TrashSteps.purge(admin, key);

        assertThat(db.count("select count(*) from mail_sent where issue_id = ?", id))
                .as("sent-mail records of %s after the purge", key).isZero();
        Map<String, Object> issueRecord = received(sender.email(), mail.messageId());
        assertThat(issueRecord.get("issue_id")).as("issue_id of the new-issue record").isNull();
        assertThat(issueRecord.get("outcome")).as("outcome of the new-issue record").isEqualTo("ISSUE");
        Map<String, Object> commentRecord = received(sender.email(), reply.messageId());
        assertThat(commentRecord.get("comment_id")).as("comment_id of the reply record").isNull();
        assertThat(commentRecord.get("issue_id")).as("issue_id of the reply record").isNull();
        assertThat(commentRecord.get("outcome")).as("outcome of the reply record").isEqualTo("COMMENT");
    }

    private JsonNode commentWith(String key, String content) {
        return comments(key).stream().filter(c -> c.path("content").asString("").contains(content)).findFirst()
                .orElseThrow(() -> new AssertionError("No comment on " + key + " with " + content));
    }
}
