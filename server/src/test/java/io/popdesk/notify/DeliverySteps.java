package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Steps the tests of resending and of the test mail share, next to {@link Delivery}: the audit entries
 * of one person, the items of one kind in an inbox, mail that is no invitation, and a failed webhook
 * row with the issue it is about. Only HTTP. Public, because tests of {@code ops} use it.
 */
public final class DeliverySteps {

    public static final String RESENT = "DELIVERY_RESENT";
    public static final String TEST_MAIL_SENT = "TEST_MAIL_SENT";

    /** A row ID far above any the app has given out. */
    public static final long NO_ROW = 9_000_000_000_000_000L;

    private static final String AUDIT_LOG = "/api/console/audit-log";

    private DeliverySteps() {}

    // Audit log

    /** The audit entries of one kind that one account did, newest first, all pages. */
    public static List<JsonNode> auditEntries(Session admin, String action, long actorAccountId) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            ApiResponse response = admin.client().get(AUDIT_LOG).query("action", action)
                    .query("actorAccountId", actorAccountId).query("page", page).query("size", 100).expect(200);
            JsonNode items = response.json().path("items");
            if (!items.isArray()) {
                throw new AssertionError(response.request() + ": no items array in " + response.snippet());
            }
            items.forEach(out::add);
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError("The audit log of account " + actorAccountId + " has more than 1000 pages");
    }

    /** Fails unless the entry's actor is this person: account ID, name, and email. */
    public static void assertActor(JsonNode entry, Session who) {
        JsonNode actor = entry.path("actor");
        assertThat(actor.path("accountId").asLong(-1)).as("actor.accountId of %s", entry).isEqualTo(who.accountId());
        assertThat(actor.path("email").asString(null)).as("actor.email of %s", entry).isEqualTo(who.email());
        assertThat(actor.path("name").asString(null)).as("actor.name of %s", entry).isEqualTo(who.name());
    }

    // Inbox

    /** The items of one kind in the caller's inbox, newest first. */
    public static List<JsonNode> items(Session session, String kind) {
        return TalkFlow.inbox(session).stream().filter(n -> kind.equals(TalkFlow.kind(n))).toList();
    }

    /** Waits until the caller's inbox has exactly {@code count} items of the kind. */
    public static List<JsonNode> awaitItems(Session session, String kind, int count, String when) {
        Wait.untilAsserted(count + " " + kind + " item(s) " + when,
                () -> assertThat(items(session, kind)).as("%s items of %s %s", kind, session.email(), when)
                        .hasSize(count));
        return items(session, kind);
    }

    // Mail

    /** Mail to the address other than invitations. */
    public static List<ReceivedMail> mailsTo(MailServer mail, String address) {
        return NotifySteps.notifications(mail, address);
    }

    // Failed rows

    /** A failed webhook row: its ID, and the key of the issue whose filing it tells of. */
    public record FailedWebhook(long id, String issueKey) {}

    /** The customer files an issue while the receiver refuses webhooks, for 24 hours ({@link Delivery.App#failWebhook}). */
    public static FailedWebhook failedWebhook(Delivery.App app, Fixtures fixtures, Session customer, String code) {
        String[] key = new String[1];
        JsonNode row = app.failWebhook(() -> {
            key[0] = fixtures.fileIssue(customer, code).key();
            return key[0];
        });
        return new FailedWebhook(id(row), key[0]);
    }

    /** {@code id} of a row of the failed list. */
    public static long id(JsonNode row) {
        JsonNode id = row.path("id");
        if (!id.isIntegralNumber()) {
            throw new AssertionError("No id in the failed row " + row);
        }
        return id.asLong();
    }

    /** Waits until no channel has a failed row. */
    public static void awaitNoFailedRow(Session admin) {
        Wait.untilAsserted("no failed row left", () -> {
            JsonNode status = Delivery.status(admin);
            for (String channel : Delivery.CHANNELS.values()) {
                assertThat(status.path(channel).path("failed").asLong(-1)).as("%s.failed", channel).isZero();
            }
        });
    }
}
