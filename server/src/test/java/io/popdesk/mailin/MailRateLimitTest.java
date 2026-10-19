package io.popdesk.mailin;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mail counts against the customer's portal limits (MAILIN-9, COM-8): ten issues a minute, the same count for mail and
 * the portal. All mail is put in before one round, as moving the clock a minute empties the window.
 */
class MailRateLimitTest extends MailInAppTest {

    @Test
    @DisplayName("S12-AC-28 eleven new-issue mails of one customer in one round give ten issues, the eleventh is quarantined with no notice, and the same customer's next portal issue gets 429")
    void elevenMailsInAMinute() {
        Company company = company();
        Session sender = customer(company.code());
        List<Inbound> mails = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            mails.add(mailFrom(sender).text("Mail " + i + " " + Unique.number()));
        }
        mails.forEach(this::send);

        rounds.next();

        assertThat(issuesOf(sender)).as("issues of the sender").hasSize(10);
        assertThat(mails.stream().filter(m -> foldersOf(m).equals(List.of(Folder.PROCESSED))).count())
                .as("mails in the processed folder").isEqualTo(10);
        assertThat(mails.stream().filter(m -> foldersOf(m).equals(List.of(Folder.QUARANTINE))).count())
                .as("mails in the quarantine folder").isEqualTo(1);
        awaitALaterMail();
        assertThat(mailTo(sender)).as("mail to the sender").isEmpty();
        ApiResponse portal = fixtures.signInAgain(sender).client().post("/api/portal/issues")
                .json(Map.of("companyCode", company.code(), "title", Unique.text("Portal"), "content", "Body"))
                .send();
        assertThat(portal.status()).as("portal issue after ten by mail: %s", portal.snippet()).isEqualTo(429);
        assertThat(portal.json().path("code").asString(null)).as("code of %s", portal.snippet())
                .isEqualTo("RATE_LIMITED");
    }
}
