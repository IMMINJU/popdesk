package io.popdesk.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.Transactions;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Sending again a failed row of a channel that is off. No request can make such a row: an app without
 * SMTP drops its mail rows, and a failed row needs a channel that was on for a day. So this test puts
 * a failed mail row into the queue of an app without SMTP through the app's own repository, and then
 * asks over HTTP.
 */
class ResendChannelOffTest extends BareAppTest {

    @Autowired
    OutboxRepository outbox;

    @Autowired
    Transactions transactions;

    @Autowired
    JdbcClient jdbc;

    private long resentEntries() {
        return db.count("select count(*) from audit_log where action = 'DELIVERY_RESENT'");
    }

    @Test
    @DisplayName("S4B-AC-17 S4B-AC-20 sending again a failed row whose channel is off is 400 CHANNEL_DISABLED: the "
            + "row stays failed with its attempts and error, and no audit entry is written")
    void failedRowOfAChannelThatIsOffStaysAsItIs() {
        Session admin = fixtures.admin(fixtures.signInAdmin());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        long id = transactions.run(() -> {
            outbox.insert(OutboxRepository.MAIL, "STAFF_COMMENT", admin.accountId(), null, null, now);
            long row = jdbc.sql("select max(id) from outbox where recipient_account_id = ?")
                    .param(admin.accountId()).query(Long.class).single();
            outbox.updateFailed(row, 3, now, now, "CONNECTION_FAILED");
            return row;
        });
        try {
            long entries = resentEntries();

            ApiResponse response = admin.client().post("/api/console/system/failed-deliveries/" + id + "/resend")
                    .send();

            assertThat(response.status()).as(response + " " + response.snippet()).isEqualTo(400);
            assertThat(response.json().path("code").asString(null)).as("code").isEqualTo("CHANNEL_DISABLED");
            List<Map<String, Object>> rows = db.rows("select state, attempts, last_error_kind, "
                    + "first_failed_at is not null as first_failure, failed_at is not null as failure "
                    + "from outbox where id = ?", id);
            assertThat(rows).as("the row after the refused request").hasSize(1);
            assertThat(rows.getFirst()).containsEntry("state", "failed").containsEntry("attempts", 3)
                    .containsEntry("last_error_kind", "CONNECTION_FAILED").containsEntry("first_failure", true)
                    .containsEntry("failure", true);
            assertThat(resentEntries()).as("DELIVERY_RESENT entries").isEqualTo(entries);
        } finally {
            transactions.run(() -> outbox.delete(id));
        }
    }
}
