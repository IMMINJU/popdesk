package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** How notices go out (MAILIN-2, MAILIN-3, TM-36): to the account's own address, marked as an auto reply, rarely. */
class NoticeMailTest extends MailInAppTest {

    @Test
    @DisplayName("S12-AC-29 a notice goes to the account's registered address (not Reply-To, Return-Path, Sender), with Auto-Submitted: auto-replied, no Reply-To, no bracketed key")
    void noticeToRegisteredAddress() {
        Session sender = customer(company().code(), company().code());
        String elsewhere = Unique.email("elsewhere");
        Inbound mail = mailFrom(sender).text("Body " + Unique.number())
                .header("Reply-To", elsewhere)
                .header("Return-Path", "<" + Unique.email("bounce") + ">")
                .header("Sender", Unique.email("sender"));

        send(mail);
        rounds.next();

        ReceivedMail notice = awaitMailTo(sender, NOTICE_A_SUBJECT);
        assertThat(notice.to()).as("To of the notice").containsExactly(sender.email());
        assertThat(notice.header("Auto-Submitted")).as("Auto-Submitted").containsExactly("auto-replied");
        assertThat(notice.header("Reply-To")).as("Reply-To").isEmpty();
        assertThat(notice.subject()).as("subject").doesNotContain("[");
        awaitALaterMail();
        assertThat(mailIn().sentTo(elsewhere)).as("mail to the Reply-To address").isEmpty();
    }

    @Test
    @DisplayName("S12-AC-29 a second notice to the same account within 15 minutes is not sent (the mail is still handled alike); after 15 minutes one is sent again")
    void oneNoticeIn15Minutes() {
        Session sender = customer(company().code(), company().code());
        send(mailFrom(sender).text("First " + Unique.number()));
        rounds.next();
        awaitMailTo(sender, NOTICE_A_SUBJECT);
        Inbound second = mailFrom(sender).text("Second " + Unique.number());

        send(second);
        rounds.next();

        awaitALaterMail();
        assertThat(mailTo(sender, NOTICE_A_SUBJECT)).as("notices after the second mail").hasSize(1);
        assertThat(foldersOf(second)).as("folders of the second mail").containsExactly(Folder.PROCESSED);
        assertThat(issuesOf(sender)).as("issues of the sender").isEmpty();

        clock.advance(Duration.ofMinutes(15));
        send(mailFrom(sender).text("Third " + Unique.number()));
        rounds.next();

        Wait.untilAsserted("a second notice after 15 minutes", () ->
                assertThat(mailTo(sender, NOTICE_A_SUBJECT)).as("notices after the third mail").hasSize(2));
    }
}
