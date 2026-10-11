package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitNotification;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.notify.Rows.Row;
import io.popdesk.notify.Rows.Snapshot;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.DbInspector.Column;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A queue row whose sending breaks inside the app, not at the receiving side (verify.md V-5). Sending
 * an invitation mail writes a new token to the invitation just before the mail goes out; a trigger
 * whose name starts with {@code test_} (tasks.md 6절) makes that write fail, so the sender ends with an
 * exception instead of a failed attempt. Such a row must count as a failed attempt and wait its turn
 * (S0-AC-68), so that the rows behind it in the mail channel still go out.
 *
 * <p>The trigger fails every update of the invitation table while a test holds it; the mail behind
 * the broken row is a notification about a comment, which writes nothing there. The table is found
 * by what an invitation does: the one table with a column whose name holds {@code token} that gets a
 * row when a person is invited. Other tables may hold tokens too (a password reset).
 */
class SendLoopBrokenRowTest extends ConnectedAppTest {

    private static final String FUNCTION = "test_popdesk_fail_invitation_update";

    private NotifySteps steps;
    private Rows rows;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
        rows = new Rows(db);
    }

    @Test
    @DisplayName("V-5 S0-AC-67 a mail row whose sending throws does not hold up the mail queued after it")
    void laterMailIsSent() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String invited = Unique.email("broken");

        try (BrokenInvitationMail broken = new BrokenInvitationMail(s)) {
            Row row = inviteWithoutWaitingForMail(s, invited);
            fixtures.comment(s.agent(), issue.key(), Unique.text("An answer"));

            ReceivedMail later = awaitNotification(mail(), s.customer().email());

            assertThat(later.text()).as("the mail queued after the broken row").contains(issue.key());
            assertThat(mail().messagesTo(invited)).as("mail to %s while its sending breaks", invited).isEmpty();
            assertThat(rows.exists(row)).as("the broken row is kept for its next attempt: %s", row).isTrue();
        }

        clock.advance(Duration.ofMinutes(10));
        ReceivedMail invitation = mail().awaitMessageTo(invited);
        assertThat(Fixtures.linkIn(invitation.text())).as("the invitation once sending works again")
                .contains("/invite#token=");
    }

    @Test
    @DisplayName("V-5 S0-AC-68 a mail row whose sending throws counts the attempt and waits 1, then 2 seconds for the next")
    void throwingAttemptIsCountedAndBacksOff() {
        Scene s = steps.scene();
        String invited = Unique.email("broken");

        try (BrokenInvitationMail broken = new BrokenInvitationMail(s)) {
            Instant firstFailure = clock.instant();
            Row queued = inviteWithoutWaitingForMail(s, invited);
            Row afterFirst = Wait.until("the first attempt of " + queued.table() + "[" + queued.key()
                    + "] recorded with its next attempt 1 second later", () -> {
                        Row r = rows.reread(queued);
                        return r != null && !r.columnsAt(firstFailure.plusSeconds(1)).isEmpty() ? r : null;
                    });
            Set<String> attemptColumns = new HashSet<>(afterFirst.columnsEqualTo(1));
            Set<String> nextColumns = new HashSet<>(afterFirst.columnsAt(firstFailure.plusSeconds(1)));

            Instant secondFailure = clock.advance(Duration.ofSeconds(1));
            Row afterSecond = Wait.until("the second attempt recorded with its next attempt 2 seconds later", () -> {
                Row r = rows.reread(queued);
                return r != null && !r.columnsAt(secondFailure.plusSeconds(2)).isEmpty() ? r : null;
            });
            attemptColumns.retainAll(afterSecond.columnsEqualTo(2));
            nextColumns.retainAll(afterSecond.columnsAt(secondFailure.plusSeconds(2)));

            assertThat(attemptColumns).as("a column counting the attempts (1, then 2): %s then %s", afterFirst,
                    afterSecond).isNotEmpty();
            assertThat(nextColumns).as("a column holding the next attempt: %s then %s", afterFirst, afterSecond)
                    .isNotEmpty();
            assertThat(mail().messagesTo(invited)).as("mail to %s while its sending breaks", invited).isEmpty();
        }

        clock.advance(Duration.ofMinutes(10));
        mail().awaitMessageTo(invited);
    }

    /**
     * Invites a customer and returns the queue row of the invitation mail. {@code Fixtures.invite}
     * waits for the mail, which does not come here.
     */
    private Row inviteWithoutWaitingForMail(Scene s, String email) {
        Snapshot before = rows.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("name", Unique.text("Broken"));
        body.put("role", "CUSTOMER");
        body.put("companyCodes", List.of(s.code()));
        s.admin().client().post("/api/console/invitations").json(body).expect(201);
        List<Row> queued = rows.added(before).stream().filter(r -> r.table().contains("outbox")).toList();
        assertThat(queued).as("queue rows added by the invitation of %s", email).hasSize(1);
        return queued.getFirst();
    }

    /** Fails every update of the invitation table until closed. */
    private final class BrokenInvitationMail implements AutoCloseable {

        private final String drop;

        BrokenInvitationMail(Scene s) {
            String table = invitationTable(s);
            String name = "test_" + UUID.randomUUID().toString().replace("-", "");
            db.schemaFixture("create or replace function " + FUNCTION + "() returns trigger language plpgsql as $f$\n"
                    + "begin\n"
                    + "  raise exception 'test_ trigger: injected failure of an invitation update';\n"
                    + "end $f$");
            db.schemaFixture("create trigger " + name + " before update on " + Rows.quote(table)
                    + " for each row execute function " + FUNCTION + "()");
            drop = "drop trigger if exists " + name + " on " + Rows.quote(table);
        }

        @Override
        public void close() {
            db.schemaFixture(drop);
        }
    }

    /**
     * The table an invitation adds a row to that has a token column. Invites one more customer to see
     * it, and waits for that mail so that no other invitation mail is in the queue when the trigger
     * comes.
     */
    private String invitationTable(Scene s) {
        Snapshot before = rows.snapshot();
        fixtures.invite(s.admin(), Role.CUSTOMER, s.code());
        List<String> found = rows.added(before).stream().map(Row::table).distinct()
                .filter(t -> db.columns(t).stream().map(Column::name).anyMatch(c -> c.contains("token")))
                .toList();
        assertThat(found).as("tables with a token column that an invitation adds a row to (the invitation table)")
                .hasSize(1);
        return found.getFirst();
    }
}
