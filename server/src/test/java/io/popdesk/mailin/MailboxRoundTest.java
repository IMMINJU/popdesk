package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.MailInServer.Stored;
import io.popdesk.support.Unique;
import jakarta.mail.Flags;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What a round does with the mailbox (MAILIN-8, MAILIN-6): which mail it reads, how many, and where it puts them. */
class MailboxRoundTest extends MailInAppTest {

    @Test
    @DisplayName("S12-AC-06 a mail already marked read is handled too, and the handled mail goes to the processed folder without the read mark")
    void readMailIsHandledAndStaysUnread() {
        Session sender = customer(company().code());
        Inbound read = mailFrom(sender).text("Read " + Unique.number());
        Inbound unread = mailFrom(sender).text("Unread " + Unique.number());
        mailIn().deliver(Folder.INBOUND, read.bytes(), new Flags(Flags.Flag.SEEN), clock.instant());
        send(unread);

        rounds.next();

        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(2);
        for (Inbound mail : List.of(read, unread)) {
            List<Stored> processed = mailIn().withMessageId(Folder.PROCESSED, mail.messageId());
            assertThat(processed).as("%s in the processed folder", mail.messageId()).hasSize(1);
            assertThat(mailIn().withMessageId(Folder.INBOUND, mail.messageId())).as("%s in the incoming folder",
                    mail.messageId()).isEmpty();
        }
        assertThat(mailIn().withMessageId(Folder.PROCESSED, unread.messageId()).get(0).seen())
                .as("read mark of the mail that came unread").isFalse();
    }

    @Test
    @DisplayName("S12-AC-06 a round makes the processed and the quarantine folder when they are missing")
    void missingFoldersAreMade() {
        Session sender = customer(company().code());
        mailIn().deleteFolder(Folder.PROCESSED);
        mailIn().deleteFolder(Folder.QUARANTINE);
        Inbound mail = mailFrom(sender).text("Folders " + Unique.number());
        try {
            send(mail);

            rounds.next();

            assertThat(mailIn().hasFolder(Folder.PROCESSED)).as("processed folder after the round").isTrue();
            assertThat(mailIn().hasFolder(Folder.QUARANTINE)).as("quarantine folder after the round").isTrue();
            assertThat(foldersOf(mail)).as("folders of the mail").containsExactly(Folder.PROCESSED);
        } finally {
            mailIn().createFolder(Folder.PROCESSED);
            mailIn().createFolder(Folder.QUARANTINE);
        }
    }

    @Test
    @DisplayName("S12-AC-06 one round reads at most 100 mails in UID order: of 101 new mails the last one waits in the incoming folder for the next round")
    void hundredMailsARound() {
        int waiting = mailIn().messages(Folder.INBOUND).size();
        List<Inbound> mails = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            // Unknown senders: each mail is discarded, so no rate limit applies.
            mails.add(Inbound.mail().from(Unique.email("nobody")).text("Mail " + i));
        }
        mails.forEach(this::send);

        rounds.next();

        long processed = mails.stream().filter(m -> foldersOf(m).equals(List.of(Folder.PROCESSED))).count();
        assertThat(processed).as("of the 101 mails, processed in the first round (%d other mails were waiting)",
                waiting).isBetween((long) Math.max(0, 100 - waiting), 100L);
        assertThat(foldersOf(mails.get(100))).as("folders of the 101st mail after the first round")
                .containsExactly(Folder.INBOUND);

        rounds.next();

        assertThat(mails).as("folders of the 101 mails after the second round")
                .allSatisfy(m -> assertThat(foldersOf(m)).as(m.messageId()).containsExactly(Folder.PROCESSED));
    }

    @Test
    @DisplayName("S12-AC-07 a handled mail is gone from the incoming folder and is in the processed folder once; another mail that a person only marked deleted stays in the incoming folder")
    void handledMailMovesAndAMarkedMailStays() {
        Session sender = customer(company().code());
        Inbound marked = mailFrom(sender).text("Marked " + Unique.number());
        Inbound mail = mailFrom(sender).text("Moved " + Unique.number());
        mailIn().deliver(Folder.INBOUND, marked.bytes(), new Flags(Flags.Flag.DELETED), clock.instant());
        send(mail);

        rounds.next();

        assertThat(mailIn().withMessageId(Folder.INBOUND, mail.messageId())).as("the mail in the incoming folder")
                .isEmpty();
        assertThat(mailIn().withMessageId(Folder.PROCESSED, mail.messageId())).as("the mail in the processed folder")
                .hasSize(1);
        List<Stored> stays = mailIn().withMessageId(Folder.INBOUND, marked.messageId());
        assertThat(stays).as("the mail marked deleted, in the incoming folder").hasSize(1);
        assertThat(stays.get(0).deleted()).as("its deleted mark").isTrue();
        assertThat(Set.copyOf(foldersOf(marked))).as("folders of the mail marked deleted")
                .isEqualTo(Set.of(Folder.INBOUND));
    }
}
