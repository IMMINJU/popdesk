package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.notify.SmtpGate.Held;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mail queue rows, caught while the mail server refuses mail ({@link SmtpGate} closed). A mail row
 * is the row the request added that goes away once the gate opens and the mail is handled.
 */
class MailQueueRowTest extends GatedMailTest {

    private NotifySteps steps;
    private Rows rows;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        rows = new Rows(db);
    }

    /** Which columns of each added row contain each value, read while the rows are there. */
    private Map<Row, Map<String, List<String>>> contents(List<Row> added, List<String> values) {
        Map<Row, Map<String, List<String>>> out = new LinkedHashMap<>();
        for (Row row : added) {
            Map<String, List<String>> byValue = new LinkedHashMap<>();
            for (String value : values) {
                byValue.put(value, rows.columnsContaining(row, value));
            }
            out.put(row, byValue);
        }
        return out;
    }

    @Test
    @DisplayName("S0-AC-66 the mail row of a comment is added with the comment and holds the customer's account ID, not their address or the issue title")
    void commentMailRowHoldsAccountIdOnly() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String title = issue.json().path("title").asString(null);
        gate().hold();
        Snapshot before = rows.snapshot();

        fixtures.comment(s.agent(), issue.key(), "Answer " + Unique.number());
        List<Row> addedAtResponse = rows.added(before);
        gate().awaitHeldTo(s.customer().email());
        List<Row> added = rows.added(before);
        Map<Row, Map<String, List<String>>> contents = contents(added, List.of(s.customer().email(), title));

        gate().pass();
        clock.advance(Duration.ofSeconds(1));
        NotifySteps.awaitNotification(mail(), s.customer().email());
        List<Row> gone = rows.awaitGone("the mail row deleted after the mail went out", added);

        assertThat(gone).as("rows that went away once the mail was sent").hasSize(1);
        Row mailRow = gone.getFirst();
        assertThat(addedAtResponse).as("rows there when the comment request answered")
                .anySatisfy(r -> assertThat(r.key()).isEqualTo(mailRow.key()));
        assertThat(mailRow.columnsEqualTo(s.customer().accountId())).as("a column with the customer's account ID in "
                + mailRow).isNotEmpty();
        assertThat(contents.get(mailRow).get(s.customer().email())).as("columns with the address").isEmpty();
        assertThat(contents.get(mailRow).get(title)).as("columns with the issue title").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-66 the mail row of an invitation holds the invited account's ID, not the address")
    void invitationMailRowHoldsAccountIdOnly() {
        Session admin = fixtures.signInAdmin();
        String email = Unique.email("queued");
        gate().hold();
        Snapshot before = rows.snapshot();

        ApiResponse invited = admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", "Queued " + Unique.number(), "role", "AGENT")).expect(201);
        long accountId = invited.jsonLong("accountId");
        gate().awaitHeldTo(email);
        List<Row> added = rows.added(before);
        Map<Row, Map<String, List<String>>> contents = contents(added, List.of(email));

        gate().pass();
        clock.advance(Duration.ofSeconds(1));
        mail().awaitMessageTo(email);
        List<Row> gone = rows.awaitGone("the invitation mail row deleted after the mail went out", added);

        assertThat(gone).as("rows that went away once the mail was sent").hasSize(1);
        Row mailRow = gone.getFirst();
        assertThat(mailRow.columnsEqualTo(accountId)).as("a column with the account ID in " + mailRow).isNotEmpty();
        assertThat(contents.get(mailRow).get(email)).as("columns with the address").isEmpty();
    }

    @Test
    @DisplayName("S0-AC-68 a refused mail is sent again once the mail server takes mail")
    void refusedMailIsRetried() {
        Session admin = fixtures.signInAdmin();
        String email = Unique.email("retried");
        gate().hold();

        admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", "Retried " + Unique.number(), "role", "AGENT")).expect(201);
        Held held = gate().awaitHeldTo(email);
        String link = Fixtures.linkIn(held.text());
        gate().pass();
        Wait.holds("no new attempt before the first wait of 1 second", Duration.ofSeconds(2),
                () -> mail().messagesTo(email).isEmpty() && gate().held().size() == 1);
        clock.advance(Duration.ofSeconds(1));

        String sent = Fixtures.linkIn(mail().awaitMessageTo(email).text());
        assertThat(sent).as("link in the mail sent on retry").isEqualTo(link);
        Wait.holds("one mail to " + email, Duration.ofSeconds(3), () -> mail().messagesTo(email).size() == 1);
    }

    @Test
    @DisplayName("S0-AC-69 an invitation mail queued for an account that has accepted meanwhile is not sent, and its row is deleted")
    void invitationMailForAcceptedAccountIsDropped() {
        Session admin = fixtures.signInAdmin();
        String email = Unique.email("accepted");
        gate().hold();
        Snapshot before = rows.snapshot();

        admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", "Accepted " + Unique.number(), "role", "AGENT")).expect(201);
        String token = Fixtures.tokenOf(Fixtures.linkIn(gate().awaitHeldTo(email).text()));
        List<Row> added = new ArrayList<>(rows.added(before));
        console().post("/api/public/invitations/accept")
                .json(Map.of("token", token, "password", Fixtures.PASSWORD)).expect(204);

        gate().pass();
        clock.advance(Duration.ofSeconds(1));
        List<Row> gone = rows.awaitGone("the invitation mail row handled after the account became ACTIVE", added);

        assertThat(gone).as("rows that went away").hasSize(1);
        Wait.holds("no invitation mail to the account that accepted", Duration.ofSeconds(3),
                () -> mail().messagesTo(email).isEmpty());
        assertThat(gate().held()).as("attempts that reached the mail server").hasSize(1);
    }

    @Test
    @DisplayName("S0-AC-69 a comment mail queued for a customer who then leaves the company is not sent, and its row is deleted")
    void mailForCustomerWhoCannotSeeIssueIsDropped() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        gate().hold();
        Snapshot before = rows.snapshot();
        fixtures.comment(s.agent(), issue.key(), "Answer " + Unique.number());
        gate().awaitHeldTo(s.customer().email());
        List<Row> added = rows.added(before);

        new Membership(db).remove(s.customer().accountId(), s.code());
        assertThat(s.customer().client().get("/api/portal/issues/" + issue.key()).send().status())
                .as("the customer sees the issue after leaving the company").isEqualTo(404);
        gate().pass();
        clock.advance(Duration.ofSeconds(1));
        List<Row> gone = rows.awaitGone("the mail row handled after the customer lost sight of the issue", added);

        assertThat(gone).as("rows that went away").hasSize(1);
        Wait.holds("no mail to the customer who cannot see the issue", Duration.ofSeconds(3),
                () -> NotifySteps.notifications(mail(), s.customer().email()).isEmpty());
        assertThat(gate().held()).as("attempts that reached the mail server").hasSize(1);
    }

    /** The table of mail queue rows: the row an invitation adds that goes away once its mail is sent. */
    private String mailQueueTable(Session admin) {
        String email = Unique.email("probe");
        gate().hold();
        Snapshot before = rows.snapshot();
        admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", "Probe " + Unique.number(), "role", "AGENT")).expect(201);
        gate().awaitHeldTo(email);
        List<Row> added = rows.added(before);
        gate().pass();
        clock.advance(Duration.ofSeconds(1));
        mail().awaitMessageTo(email);
        List<Row> gone = rows.awaitGone("the probe's mail row deleted after the mail went out", added);
        assertThat(gone).as("rows that went away once the probe's mail was sent").hasSize(1);
        return gone.getFirst().table();
    }

    @Test
    @DisplayName("S0-AC-66 when the transaction fails at commit after the queue row was written, neither the change nor the row stays and nothing is sent")
    void failureAfterQueueRowRollsBackBoth() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String table = Rows.quote(mailQueueTable(s.admin()));
        String email = Unique.email("rolledback");
        String answer = "Rolled back " + Unique.number();
        Snapshot before = rows.snapshot();
        ApiResponse invited;
        ApiResponse answered;
        db.schemaFixture("create or replace function test_fail_at_commit() returns trigger language plpgsql as "
                + "$$ begin raise exception 'test_ failure after the queue row'; end $$");
        db.schemaFixture("create constraint trigger test_fail_after_queue after insert on " + table
                + " deferrable initially deferred for each row execute function test_fail_at_commit()");
        try {
            invited = s.admin().client().post("/api/console/invitations")
                    .json(Map.of("email", email, "name", "Rolled " + Unique.number(), "role", "AGENT")).send();
            answered = s.agent().client().post("/api/console/issues/" + issue.key() + "/comments")
                    .json(Map.of("content", answer)).send();
        } finally {
            db.schemaFixture("drop trigger if exists test_fail_after_queue on " + table);
            db.schemaFixture("drop function if exists test_fail_at_commit()");
        }

        for (ApiResponse r : List.of(invited, answered)) {
            assertThat(r.status()).as(r.toString()).isEqualTo(500);
            assertThat(r.errorCode()).isEqualTo("INTERNAL_ERROR");
        }
        assertThat(rows.added(before)).as("rows left by the failed requests").isEmpty();
        assertThat(s.agent().client().get("/api/console/issues/" + issue.key() + "/comments").expect(200).text())
                .doesNotContain(answer);
        Wait.holds("no mail for the failed requests", Duration.ofSeconds(3),
                () -> mail().messagesTo(email).isEmpty()
                        && NotifySteps.notifications(mail(), s.customer().email()).isEmpty());
    }
}
