package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.Unique;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * A round that fails (MAILIN, OPS-3): the server is down ({@code CONNECT}), the sign-in is refused ({@code AUTH}), or
 * the incoming folder is missing ({@code FOLDER}). The round moves no mail and counts the failure; once the cause is
 * gone, the next round handles the mail. Every test puts the mailbox back at its end.
 */
class RoundFailureTest extends MailInAppTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"CONNECT", "AUTH"})
    @DisplayName("S12-AC-10 with the IMAP server down or the sign-in refused a round fails with that kind, counts in roundFailures, and moves no mail; once it works again the next round handles the mail")
    void serverDownOrSignInRefused(String kind) {
        Session sender = customer(company().code());
        Inbound mail = mailFrom(sender).text("Waits " + Unique.number());
        send(mail);
        int failuresBefore = rounds.roundFailures();
        try {
            breakMailbox(kind);

            String failed = rounds.nextFailing();

            assertThat(failed).as("mailIn.lastFailure.kind").isEqualTo(kind);
            assertThat(rounds.roundFailures()).as("mailIn.roundFailures").isGreaterThan(failuresBefore);
            assertThat(foldersOf(mail)).as("folders of the mail after the failed round")
                    .containsExactly(Folder.INBOUND);
            assertThat(issuesOf(sender)).as("issues of the sender after the failed round").isEmpty();
        } finally {
            mailIn().restore();
            mailIn().restorePassword();
        }
        rounds.next();
        assertThat(foldersOf(mail)).as("folders of the mail after a good round").containsExactly(Folder.PROCESSED);
        assertThat(issuesOf(sender)).as("issues of the sender after a good round").hasSize(1);
    }

    @Test
    @DisplayName("S12-AC-10 with the incoming folder missing a round fails with FOLDER and counts in roundFailures; once the folder is back the next round handles a mail in it")
    void incomingFolderMissing() {
        Session sender = customer(company().code());
        int failuresBefore = rounds.roundFailures();
        try {
            mailIn().deleteFolder(Folder.INBOUND);

            String failed = rounds.nextFailing();

            assertThat(failed).as("mailIn.lastFailure.kind").isEqualTo("FOLDER");
            assertThat(rounds.roundFailures()).as("mailIn.roundFailures").isGreaterThan(failuresBefore);
            JsonNode status = rounds.mailIn();
            assertThat(status.path("lastFailure").path("at").isString()).as("lastFailure.at in %s", status).isTrue();
        } finally {
            mailIn().createFolder(Folder.INBOUND);
        }
        Inbound mail = mailFrom(sender).text("After the folder " + Unique.number());
        send(mail);
        rounds.next();
        assertThat(foldersOf(mail)).as("folders of the mail").containsExactly(Folder.PROCESSED);
        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(1);
    }

    private void breakMailbox(String kind) {
        if (kind.equals("CONNECT")) {
            mailIn().cut();
        } else {
            mailIn().changePassword("rst-wrong-imap-password");
        }
    }
}
