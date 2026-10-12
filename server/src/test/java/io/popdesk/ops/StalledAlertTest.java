package io.popdesk.ops;

import static io.popdesk.notify.DeliverySteps.awaitItems;
import static io.popdesk.notify.DeliverySteps.items;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.notify.Delivery;
import io.popdesk.notify.DeliverySteps;
import io.popdesk.notify.DeliverySteps.FailedWebhook;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver.Reply;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * The alert for a channel whose sends only fail for over an hour (OPS-1): {@code MAIL_STALLED},
 * {@code WEBHOOK_STALLED}, {@code AUDIT_FORWARD_STALLED} in every admin's inbox, one a day until the
 * channel sends once.
 *
 * <p>The app looks for it in its housekeeping round, as for {@code DELIVERY_FAILED}
 * ({@link OpsAlertTest}): the test moves the clock and waits for the item. A row waits at most 10
 * minutes between attempts, so each move of the clock here brings one more attempt, and no row is
 * given up: the test stays under 24 hours.
 *
 * <p>Each test starts with nothing failed and nothing queued, and ends with every receiving side
 * working and the clock eight days on.
 */
class StalledAlertTest extends Delivery.PlainApp {

    @BeforeEach
    void cleanStart() {
        Session admin = startClean();
        Wait.untilAsserted("nothing queued from earlier tests", () -> {
            JsonNode status = Delivery.status(admin);
            for (String channel : Delivery.CHANNELS.values()) {
                assertThat(status.path(channel).path("queued").asLong(-1)).as("%s.queued", channel).isZero();
            }
        });
    }

    @AfterEach
    void leaveEverythingWorking() {
        revive();
        startClean();
    }

    /** Makes every send of the channel fail in a way the app tries again. */
    private void stop(String channel) {
        switch (channel) {
            case "MAIL" -> smtp().refuseMessage("451 4.3.0 Try again later");
            case "WEBHOOK" -> webhook().respond(r -> Reply.status(Delivery.isAuditCopy(r) ? 204 : 500));
            default -> webhook().respond(r -> Reply.status(Delivery.isAuditCopy(r) ? 500 : 204));
        }
    }

    private void revive() {
        smtp().pass();
        webhook().respondWith(204);
    }

    /** Queues one row of the channel and gives a text its attempts are found by. */
    private String queueOne(String channel, Cast c) {
        switch (channel) {
            case "MAIL" -> {
                String email = Unique.email("stalled");
                admin().client().post("/api/console/invitations")
                        .json(Map.of("email", email, "name", Unique.text("Stalled"), "role", "AGENT")).expect(201);
                return email;
            }
            case "WEBHOOK" -> {
                return fixtures.fileIssue(fixtures.signInAgain(c.customer()), c.code()).key();
            }
            default -> {
                return fixtures.createCompany(admin()).code();
            }
        }
    }

    private int attempts(String channel, String text) {
        return switch (channel) {
            case "MAIL" -> (int) smtp().taken().stream().filter(t -> t.isTo(text)).count();
            case "WEBHOOK" -> Delivery.webhooks(webhook(), text).size();
            default -> Delivery.auditCopies(webhook(), text).size();
        };
    }

    private void awaitAttempts(String channel, String text, int n) {
        Wait.until(n + " attempt(s) of the " + channel + " row of " + text,
                () -> attempts(channel, text) >= n ? text : null);
    }

    private void awaitNothingQueued(String channel) {
        Session admin = admin();
        Wait.untilAsserted("every " + channel + " row sent", () -> assertThat(Delivery.status(admin)
                .path(Delivery.CHANNELS.get(channel)).path("queued").asLong(-1)).as("queued").isZero());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"MAIL", "WEBHOOK", "AUDIT_FORWARD"})
    @DisplayName("S4B-AC-29 a channel whose sends only fail gives every admin one stalled item after an hour: "
            + "none at 59 minutes, one at 61, no more two hours later, none for an agent; once the channel sent "
            + "again the alert is resolved, and failing for another hour gives a second item the same day")
    void channelFailingForOverAnHourAlertsAdminsUntilItSendsAgain(String channel) {
        String kind = channel + "_STALLED";
        Cast c = cast();
        Session watcher = person(c.admin(), Role.ADMIN, "Watcher");
        stop(channel);
        String first = queueOne(channel, c);
        awaitAttempts(channel, first, 1);

        clock.advance(Duration.ofMinutes(59));
        awaitAttempts(channel, first, 2);
        Session at59 = fixtures.signInAgain(watcher);
        Wait.holds("no " + kind + " item 59 minutes after the first failure", Duration.ofSeconds(3),
                () -> items(at59, kind).isEmpty());

        clock.advance(Duration.ofMinutes(2));
        Session at61 = fixtures.signInAgain(watcher);
        JsonNode item = awaitItems(at61, kind, 1, "61 minutes after the first failure").getFirst();
        assertThat(TalkFlow.isNull(item, "issue")).as("issue is null in %s", item).isTrue();
        assertThat(items(admin(), kind)).as("%s items of the first admin", kind).isNotEmpty();
        assertThat(items(fixtures.signInAgain(c.agent()), kind)).as("%s items of an agent", kind).isEmpty();

        clock.advance(Duration.ofHours(2));
        Session twoHoursOn = fixtures.signInAgain(watcher);
        Wait.holds("still one " + kind + " item two hours later", Duration.ofSeconds(3),
                () -> items(twoHoursOn, kind).size() == 1);

        revive();
        clock.advance(Duration.ofMinutes(11));
        awaitNothingQueued(channel);
        stop(channel);
        String second = queueOne(channel, c);
        awaitAttempts(channel, second, 1);
        clock.advance(Duration.ofMinutes(61));

        awaitItems(fixtures.signInAgain(watcher), kind, 2, "an hour after the channel stopped again");
    }

    @Test
    @DisplayName("S4B-AC-29 a channel with nothing but a row that was given up is not stalled: 61 minutes after "
            + "the webhook last sent, with one failed row and nothing queued, there is no new WEBHOOK_STALLED "
            + "item; a new webhook that then fails for over an hour gives one")
    void failedRowAloneDoesNotStallTheChannel() {
        String kind = "WEBHOOK_STALLED";
        Cast c = cast();
        Session watcher = person(c.admin(), Role.ADMIN, "Watcher");
        FailedWebhook failed = DeliverySteps.failedWebhook(this, fixtures, c.customer(), c.code());
        // The receiver works again. One webhook goes out, which resolves an alert raised while the row failed.
        String sent = queueOne("WEBHOOK", c);
        awaitAttempts("WEBHOOK", sent, 1);
        awaitNothingQueued("WEBHOOK");
        assertThat(Delivery.isFailed(admin(), failed.id())).as("the row is in the failed list").isTrue();
        int before = items(fixtures.signInAgain(watcher), kind).size();

        clock.advance(Duration.ofMinutes(61));
        Session at61 = fixtures.signInAgain(watcher);
        Wait.holds("no new " + kind + " item 61 minutes on, with only a failed row", Duration.ofSeconds(3),
                () -> items(at61, kind).size() == before);
        assertThat(Delivery.isFailed(admin(), failed.id())).as("the row is still in the failed list").isTrue();

        stop("WEBHOOK");
        String refused = queueOne("WEBHOOK", c);
        awaitAttempts("WEBHOOK", refused, 1);
        clock.advance(Duration.ofMinutes(61));
        awaitItems(fixtures.signInAgain(watcher), kind, before + 1, "an hour after a new webhook began to fail");
    }
}
