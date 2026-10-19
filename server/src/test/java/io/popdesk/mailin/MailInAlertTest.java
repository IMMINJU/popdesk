package io.popdesk.mailin;

import static io.popdesk.notify.DeliverySteps.awaitItems;
import static io.popdesk.notify.DeliverySteps.items;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailInServer.Folder;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The two mail-in alerts in every admin's inbox (OPS-1): {@code MAIL_IN_STALLED} after an hour of failed rounds, until a
 * round succeeds, and {@code MAIL_IN_QUARANTINED} when a mail is quarantined, once a day until 24 hours pass without
 * one. Each test watches with an admin it made, so alerts raised before it are not counted.
 */
class MailInAlertTest extends MailInAppTest {

    static final String STALLED = "MAIL_IN_STALLED";
    static final String QUARANTINED = "MAIL_IN_QUARANTINED";

    @Test
    @DisplayName("S12-AC-37 rounds failing for over an hour give every admin a MAIL_IN_STALLED item without an issue, none before the hour; a good round resolves it, so failing for another hour gives a second item")
    void stalledAfterAnHourUntilAGoodRound() {
        rounds.next();
        Session watcher = fixtures.accept(invite(Role.ADMIN));
        mailIn().cut();
        try {
            rounds.nextFailing();
            clock.advance(Duration.ofMinutes(58));
            rounds = new Rounds(admin = fixtures.signInAgain(admin), clock);
            rounds.nextFailing();
            Session at59 = fixtures.signInAgain(watcher);
            Wait.holds("no " + STALLED + " item 59 minutes after the first failure", Duration.ofSeconds(3),
                    () -> items(at59, STALLED).isEmpty());

            rounds.nextFailing();
            rounds.nextFailing();

            Session at61 = fixtures.signInAgain(watcher);
            JsonNode item = awaitItems(at61, STALLED, 1, "an hour after the first failed round").getFirst();
            assertThat(TalkFlow.isNull(item, "issue")).as("issue is null in %s", item).isTrue();
        } finally {
            mailIn().restore();
        }
        rounds.next();
        mailIn().cut();
        try {
            rounds.nextFailing();
            clock.advance(Duration.ofMinutes(61));
            rounds = new Rounds(admin = fixtures.signInAgain(admin), clock);
            rounds.nextFailing();

            awaitItems(fixtures.signInAgain(watcher), STALLED, 2, "an hour after the rounds failed again");
        } finally {
            mailIn().restore();
        }
        rounds.next();
    }

    @Test
    @DisplayName("S12-AC-37 a quarantined mail gives every admin a MAIL_IN_QUARANTINED item without an issue; a second one the same day gives no new item")
    void quarantinedOnceADay() {
        // A day without quarantine resolves an alert an earlier test raised.
        passTime(Duration.ofHours(25));
        rounds.next();
        Session watcher = fixtures.accept(invite(Role.ADMIN));
        Session sender = customer(company().code());
        Inbound first = tooComplex(sender);
        send(first);

        rounds.next();

        assertThat(foldersOf(first)).as("folders of the first mail").containsExactly(Folder.QUARANTINE);
        JsonNode item = awaitItems(watcher, QUARANTINED, 1, "after the first quarantined mail").getFirst();
        assertThat(TalkFlow.isNull(item, "issue")).as("issue is null in %s", item).isTrue();
        Inbound second = tooComplex(sender);
        send(second);
        rounds.next();
        assertThat(foldersOf(second)).as("folders of the second mail").containsExactly(Folder.QUARANTINE);
        awaitALaterMail();
        Wait.holds("still one " + QUARANTINED + " item after the second quarantined mail", Duration.ofSeconds(3),
                () -> items(watcher, QUARANTINED).size() == 1);
    }

    /** A mail with 201 MIME parts, which is quarantined (S12-AC-09). */
    private static Inbound tooComplex(Session sender) {
        Inbound mail = mailFrom(sender).text("Parts " + Unique.number());
        for (int i = 0; i < 200; i++) {
            mail.unnamed("text/plain", ("part " + i).getBytes(StandardCharsets.US_ASCII));
        }
        return mail;
    }
}
