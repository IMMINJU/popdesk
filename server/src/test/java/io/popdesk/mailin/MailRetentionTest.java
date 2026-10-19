package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.Unique;
import io.popdesk.trash.TrashJobs;
import jakarta.mail.Flags;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Received mail is kept 90 days (PII-1, PII-6, MAIL-7): the records by the cleanup job {@code mail-record-cleanup}, and
 * the mail in the processed and quarantine folders by the round once a day. Both by the app's clock; a mail's age in
 * the mailbox is its {@code INTERNALDATE}, which these tests set.
 */
class MailRetentionTest extends MailInAppTest {

    static final String JOB = "mail-record-cleanup";

    @Test
    @DisplayName("S12-AC-33 mail-record-cleanup is among the cleanup jobs of the system status; 91 days on it has deleted the old received-mail and sent-mail records and left how many it deleted")
    void oldRecordsAreDeleted() {
        assertThat(rounds.mailIn().isObject()).as("mailIn in the system status").isTrue();
        JsonNode jobs = admin.client().get(Rounds.STATUS).expect(200).json().path("cleanupJobs");
        assertThat(jobs.findValuesAsString("name")).as("names of the cleanup jobs").contains(JOB);
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Old " + Unique.number());
        send(mail);
        rounds.next();
        String key = onlyIssueOf(sender).path("key").asString();
        Object issueId = issueId(key);
        staffAnswer(admin, sender, key);
        assertThat(received(sender.email(), mail.messageId())).as("record of the mail before").isNotNull();
        assertThat(db.count("select count(*) from mail_sent where issue_id = ?", issueId))
                .as("sent-mail records of %s before", key).isPositive();
        Instant before = clock.instant();

        passTime(Duration.ofDays(91));

        JsonNode run = TrashJobs.awaitRun(admin, JOB, before.plus(Duration.ofDays(90)), 2);
        assertThat(TrashJobs.count(run)).as("deleted of %s", run).isGreaterThanOrEqualTo(2);
        assertThat(receivedRows(sender.email(), mail.messageId())).as("records of the mail 91 days on").isEmpty();
        assertThat(db.count("select count(*) from mail_sent where issue_id = ?", issueId))
                .as("sent-mail records of %s 91 days on", key).isZero();
    }

    @Test
    @DisplayName("S12-AC-43 once a day at the end of a round the mail of the processed and quarantine folders received over 90 days ago is deleted; newer mail there and old mail of the incoming folder stay")
    void oldMailboxMailIsDeleted() {
        Instant now = clock.instant();
        Instant old = now.minus(Duration.ofDays(91));
        Inbound oldProcessed = Inbound.mail().text("Old processed " + Unique.number());
        Inbound newerProcessed = Inbound.mail().text("Newer processed " + Unique.number());
        Inbound oldQuarantined = Inbound.mail().text("Old quarantined " + Unique.number());
        Inbound oldIncoming = Inbound.mail().text("Old incoming " + Unique.number());
        mailIn().deliver(Folder.PROCESSED, oldProcessed.bytes(), new Flags(), old);
        mailIn().deliver(Folder.PROCESSED, newerProcessed.bytes(), new Flags(), now.minus(Duration.ofDays(80)));
        mailIn().deliver(Folder.QUARANTINE, oldQuarantined.bytes(), new Flags(), old);
        // Marked deleted by a person, so no round handles it (S12-AC-07); it stays in the incoming folder.
        mailIn().deliver(Folder.INBOUND, oldIncoming.bytes(), new Flags(Flags.Flag.DELETED), old);

        passTime(Duration.ofHours(25));
        rounds.next();

        assertThat(foldersOf(oldProcessed)).as("folders of the old processed mail").isEmpty();
        assertThat(foldersOf(oldQuarantined)).as("folders of the old quarantined mail").isEmpty();
        assertThat(foldersOf(newerProcessed)).as("folders of the newer processed mail")
                .containsExactly(Folder.PROCESSED);
        assertThat(foldersOf(oldIncoming)).as("folders of the old incoming mail").containsExactly(Folder.INBOUND);
    }
}
