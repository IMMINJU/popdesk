package io.popdesk.notify;

import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.notify.DeliverySteps.NO_ROW;
import static io.popdesk.notify.DeliverySteps.RESENT;
import static io.popdesk.notify.DeliverySteps.auditEntries;
import static io.popdesk.notify.DeliverySteps.awaitItems;
import static io.popdesk.notify.DeliverySteps.id;
import static io.popdesk.notify.DeliverySteps.items;
import static io.popdesk.notify.NotifySteps.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.notify.DeliverySteps.FailedWebhook;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver.Reply;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * Sending a failed row again (OPS-3): {@code POST /api/console/system/failed-deliveries/{id}/resend}.
 * A row fails for good when its receiving side refuses it for 24 hours ({@link Delivery.App#failMail},
 * {@code failWebhook}, {@code failAuditCopies}); the receiving side works again afterwards.
 *
 * <p>Sending again only puts the row back in the queue; the send loop sends it, with the checks it
 * makes before every mail (premortem 2). So "not sent" is read after a later mail arrived.
 *
 * <p>Each test starts and ends with no failed row: the clock moves eight days on, past the 7 days a
 * failed row is kept.
 */
class ResendTest extends Delivery.PlainApp {

    private IssueFlow flow;

    @BeforeEach
    void cleanStart() {
        flow = new IssueFlow(fixtures, clock);
        startClean();
    }

    @AfterEach
    void leaveNoFailedRow() {
        smtp().pass();
        SmtpGate.shared().pass();
        webhook().respondWith(204);
        startClean();
    }

    /** The agent's answer to the customer's issue, refused for 24 hours: the failed mail row of the customer. */
    private JsonNode failedAnswerMail(Session agent, Session customer, String key) {
        return failMail(customer.accountId(), customer.email(),
                () -> fixtures.comment(agent, key, Unique.text("Answer")));
    }

    private void awaitGoneFromTheFailedList(Session admin, long id) {
        Wait.untilAsserted("row " + id + " gone from the failed list",
                () -> assertThat(Delivery.isFailed(admin, id)).as("row %d is in the failed list", id).isFalse());
    }

    // S4B-AC-14

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"MAIL", "WEBHOOK", "AUDIT_FORWARD"})
    @DisplayName("S4B-AC-14 an admin sends a failed row of each channel again: 204, it goes out at once without "
            + "the clock moving, and it leaves the failed list")
    void failedRowGoesOutWhenSentAgain(String channel) {
        Cast c = cast();
        JsonNode row;
        IntSupplier arrived;
        switch (channel) {
            case "MAIL" -> {
                String key = fixtures.fileIssue(c.customer(), c.code()).key();
                row = failedAnswerMail(c.agent(), c.customer(), key);
                arrived = () -> notifications(mail(), c.customer().email()).size();
            }
            case "WEBHOOK" -> {
                String[] key = new String[1];
                row = failWebhook(() -> {
                    key[0] = fixtures.fileIssue(c.customer(), c.code()).key();
                    return key[0];
                });
                arrived = () -> Delivery.webhooks(webhook(), key[0]).size();
            }
            default -> {
                Company[] made = new Company[1];
                List<JsonNode> rows = failAuditCopies(() -> made[0] = fixtures.createCompany(c.admin()));
                assertThat(rows).as("failed rows of the outside copy").isNotEmpty();
                row = rows.getFirst();
                arrived = () -> Delivery.auditCopies(webhook(), made[0].code()).size();
            }
        }
        Session admin = admin();
        assertThat(row.path("channel").asString(null)).as("channel of the failed row %s", row).isEqualTo(channel);
        int before = arrived.getAsInt();

        Delivery.resend(admin, id(row));

        Wait.until("the " + channel + " row sent again, without the clock moving",
                () -> arrived.getAsInt() > before ? row : null);
        awaitGoneFromTheFailedList(admin, id(row));
    }

    // S4B-AC-15

    /** What happens between the failure and sending again. */
    enum Since {
        RECIPIENT_SUSPENDED,
        NEW_ASSIGNEE_OFF_THE_COMPANY,
        COMMENT_DELETED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Since.class)
    @DisplayName("S4B-AC-15 a failed mail sent again is checked as before every send: when its recipient was "
            + "suspended or taken off the company, or its comment deleted, the answer is 204, no mail goes out, and "
            + "the row is deleted")
    void resentMailIsCheckedAgain(Since since) {
        Cast c = cast();
        String key = fixtures.fileIssue(c.customer(), c.code()).key();
        Session recipient = since == Since.NEW_ASSIGNEE_OFF_THE_COMPANY ? c.agent() : c.customer();
        long[] comment = new long[1];
        JsonNode row = failMail(recipient.accountId(), recipient.email(), () -> {
            if (since == Since.NEW_ASSIGNEE_OFF_THE_COMPANY) {
                flow.assign(c.admin(), key, c.agent().accountId());
            } else {
                comment[0] = fixtures.comment(c.agent(), key, Unique.text("Answer")).id();
            }
        });
        Session admin = admin();
        switch (since) {
            case RECIPIENT_SUSPENDED -> flow.suspend(admin, recipient.accountId());
            case NEW_ASSIGNEE_OFF_THE_COMPANY -> flow.unassignAgent(admin, c.code(), recipient.accountId());
            case COMMENT_DELETED -> TalkFlow.delete(fixtures.signInAgain(c.agent()), key, comment[0]);
        }

        Delivery.resend(admin, id(row));

        awaitALaterMail(admin);
        assertThat(notifications(mail(), recipient.email())).as("mails to %s after a later mail arrived",
                recipient.email()).isEmpty();
        Wait.untilAsserted("the row deleted: sending it again finds nothing",
                () -> assertRefused(Delivery.resendRequest(admin, id(row)).send(), 404, "NOT_FOUND"));
        assertThat(Delivery.isFailed(admin, id(row))).as("the row is in the failed list").isFalse();
    }

    // S4B-AC-16

    @Test
    @DisplayName("S4B-AC-16 a row sent again while its receiver still refuses is tried at once, waits in the queue "
            + "and is tried again an hour later, and is failed again 24 hours after it was sent again")
    void resentRowThatFailsAgainIsRetriedAndThenFailed() {
        Cast c = cast();
        FailedWebhook failed = DeliverySteps.failedWebhook(this, fixtures, c.customer(), c.code());
        Session admin = admin();
        webhook().respond(r -> Reply.status(Delivery.isAuditCopy(r) ? 204 : 500));
        int before = Delivery.webhooks(webhook(), failed.issueKey()).size();

        Delivery.resend(admin, failed.id());

        Wait.until("an attempt at once", () -> Delivery.webhooks(webhook(), failed.issueKey()).size() == before + 1
                ? failed : null);
        assertThat(Delivery.isFailed(admin, failed.id())).as("the row is failed right after it was sent again")
                .isFalse();
        assertThat(Delivery.status(admin).path("webhook").path("queued").asLong(-1)).as("webhook.queued")
                .isGreaterThanOrEqualTo(1);
        clock.advance(Duration.ofHours(1));
        Wait.until("another attempt an hour later",
                () -> Delivery.webhooks(webhook(), failed.issueKey()).size() == before + 2 ? failed : null);
        assertThat(Delivery.isFailed(admin(), failed.id())).as("the row is failed an hour after it was sent again")
                .isFalse();

        clock.advance(Delivery.PAST_GIVING_UP);

        Session later = admin();
        Wait.until("the row failed again", () -> Delivery.isFailed(later, failed.id()) ? failed : null);
    }

    // S4B-AC-18

    @Test
    @DisplayName("S4B-AC-18 sending again a row that does not exist, or an ID that is no number, is 404 with the "
            + "same body")
    void missingRowIsNotFound() {
        Session admin = admin();

        ApiResponse missing = Delivery.resendRequest(admin, NO_ROW).send();
        ApiResponse notANumber = Delivery.resendRequest(admin, "not-a-number").send();

        assertRefused(missing, 404, "NOT_FOUND");
        assertRefused(notANumber, 404, "NOT_FOUND");
        assertThat(notANumber.text()).as("body for an ID that is no number").isEqualTo(missing.text());
    }

    @Test
    @DisplayName("S4B-AC-18 a row that went out after it was sent again is gone: sending it once more is 404, as "
            + "for a row that never was")
    void rowThatWentOutIsNotFound() {
        Cast c = cast();
        FailedWebhook failed = DeliverySteps.failedWebhook(this, fixtures, c.customer(), c.code());
        Session admin = admin();
        int before = Delivery.webhooks(webhook(), failed.issueKey()).size();
        Delivery.resend(admin, failed.id());
        Wait.until("the webhook sent again", () -> Delivery.webhooks(webhook(), failed.issueKey()).size() > before
                ? failed : null);
        awaitGoneFromTheFailedList(admin, failed.id());

        Wait.untilAsserted("the row deleted after it went out",
                () -> assertRefused(Delivery.resendRequest(admin, failed.id()).send(), 404, "NOT_FOUND"));
        assertThat(Delivery.resendRequest(admin, failed.id()).send().text()).as("body for a row that went out")
                .isEqualTo(Delivery.resendRequest(admin, NO_ROW).send().text());
    }

    @Test
    @DisplayName("S4B-AC-18 S4B-AC-20 a row that is back in the queue already answers 204 again and nothing "
            + "changes: no new attempt, no second audit entry")
    void rowAlreadyQueuedIsLeftAlone() {
        Cast c = cast();
        Session resender = person(c.admin(), Role.ADMIN, "Resender");
        FailedWebhook failed = DeliverySteps.failedWebhook(this, fixtures, c.customer(), c.code());
        Session admin = fixtures.signInAgain(resender);
        webhook().respond(r -> Reply.status(Delivery.isAuditCopy(r) ? 204 : 500));
        int before = Delivery.webhooks(webhook(), failed.issueKey()).size();
        Delivery.resend(admin, failed.id());
        Wait.until("the attempt of the first time", () -> Delivery.webhooks(webhook(), failed.issueKey()).size()
                == before + 1 ? failed : null);
        assertThat(auditEntries(admin, RESENT, admin.accountId())).as("audit entries after the first time")
                .hasSize(1);

        Delivery.resend(admin, failed.id());

        Wait.holds("no attempt for the second time: the row waits for its retry", Duration.ofSeconds(2),
                () -> Delivery.webhooks(webhook(), failed.issueKey()).size() == before + 1);
        assertThat(auditEntries(admin, RESENT, admin.accountId())).as("audit entries after the second time")
                .hasSize(1);
        assertThat(Delivery.isFailed(admin, failed.id())).as("the row is in the failed list").isFalse();
    }

    // S4B-AC-20

    @Test
    @DisplayName("S4B-AC-20 sending a mail row and a webhook row again leaves one DELIVERY_RESENT entry each: the "
            + "admin who did it, channel and kind in the changes, and nowhere the recipient's address, the "
            + "issue's title, or the webhook URL; a 404 leaves none, and the integrity check still passes")
    void resendingIsAuditedWithoutAddressesOrTitles() {
        Cast c = cast();
        Session resender = person(c.admin(), Role.ADMIN, "Resender");
        String word = IssueFlow.word();
        String[] key = new String[1];
        JsonNode hook = failWebhook(() -> {
            key[0] = fixtures.fileIssue(c.customer(), NewIssue.in(c.code()).title("Invoice " + word)).key();
            return key[0];
        });
        JsonNode mailRow = failedAnswerMail(fixtures.signInAgain(c.agent()), c.customer(), key[0]);
        Session admin = fixtures.signInAgain(resender);

        Delivery.resend(admin, id(hook));
        Delivery.resend(admin, id(mailRow));

        List<JsonNode> entries = auditEntries(admin, RESENT, admin.accountId());
        assertThat(entries).as("DELIVERY_RESENT entries of the admin").hasSize(2);
        for (JsonNode entry : entries) {
            DeliverySteps.assertActor(entry, admin);
            JsonNode changes = entry.path("changes");
            assertThat(changes.path("channel").asString("")).as("changes.channel of %s", entry).isNotBlank();
            assertThat(changes.path("kind").asString("")).as("changes.kind of %s", entry).isNotBlank();
            String text = entry.toString();
            assertThat(Delivery.foundIn(text, c.customer().email())).as("the recipient's address in %s", text)
                    .isEmpty();
            assertThat(text).as("the audit entry").doesNotContainIgnoringCase(word);
            for (String form : NotifySteps.urlForms(webhook().url())) {
                assertThat(text).as("the audit entry holds the webhook URL as " + form)
                        .doesNotContainIgnoringCase(form);
            }
        }
        assertThat(entries.stream().map(e -> e.path("changes").path("channel").asString("")
                .toUpperCase(Locale.ROOT)).toList()).as("channels of the two entries")
                .containsExactlyInAnyOrder("MAIL", "WEBHOOK");

        assertRefused(Delivery.resendRequest(admin, NO_ROW).send(), 404, "NOT_FOUND");

        assertThat(auditEntries(admin, RESENT, admin.accountId())).as("entries after a 404").hasSize(2);
        AuditChainMath.assertValid(admin.client());
    }

    // S4B-AC-21

    @Test
    @DisplayName("S4B-AC-21 once the only failed row went out after it was sent again, the DELIVERY_FAILED alert "
            + "is resolved: 25 hours later there is no new item")
    void resendingTheOnlyFailedRowResolvesTheAlert() {
        Cast c = cast();
        Session watcher = person(c.admin(), Role.ADMIN, "Watcher");
        FailedWebhook failed = DeliverySteps.failedWebhook(this, fixtures, c.customer(), c.code());
        Session admin = fixtures.signInAgain(watcher);
        awaitItems(admin, "DELIVERY_FAILED", 1, "when the row failed");

        Delivery.resend(admin, failed.id());

        DeliverySteps.awaitNoFailedRow(admin);
        clock.advance(Duration.ofHours(25));
        Session later = fixtures.signInAgain(watcher);
        Wait.holds("no new DELIVERY_FAILED item 25 hours after the row went out", Duration.ofSeconds(3),
                () -> items(later, "DELIVERY_FAILED").size() == 1);
    }
}
