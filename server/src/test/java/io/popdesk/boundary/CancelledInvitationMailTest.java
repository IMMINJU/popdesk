package io.popdesk.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An invitation mail still in the send queue when the invitation is cancelled. The mail is kept in
 * the queue as in {@link QueuedMailBoundaryTest}: the {@link SmtpGate} is closed, the first attempt
 * fails, and the invitation is cancelled before the retry 1 second later (on the app's clock).
 *
 * <p>In this package because the gate is here; the rest of cancelling is in the project package.
 */
class CancelledInvitationMailTest extends GatedMailTest {

    /** Invites without waiting for the mail, which the closed gate does not let through. */
    private long invite(Session admin, String email) {
        return admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", Unique.text("Invited"), "role", "AGENT"))
                .expect(201).jsonLong("accountId");
    }

    @Test
    @DisplayName("S1A-AC-39 an invitation mail still queued when the invitation is cancelled is not sent, while "
            + "another queued invitation mail is")
    void queuedMailOfCancelledInvitationIsNotSent() {
        Session admin = fixtures.signInAdmin();
        String cancelledEmail = Unique.email("cancelled");
        String keptEmail = Unique.email("kept");
        gate().hold();
        long cancelled = invite(admin, cancelledEmail);
        invite(admin, keptEmail);
        gate().awaitHeldTo(cancelledEmail);
        gate().awaitHeldTo(keptEmail);

        admin.client().post("/api/console/invitations/" + cancelled + "/cancel").expect(204);
        gate().pass();
        clock.advance(Duration.ofSeconds(1));

        mail().awaitMessageTo(keptEmail);
        Wait.holds("no mail to the cancelled invitation", Duration.ofSeconds(3),
                () -> mail().messagesTo(cancelledEmail).isEmpty());
        assertThat(gate().held()).as("attempts held while the gate was closed")
                .filteredOn(h -> h.isTo(cancelledEmail)).hasSize(1);
    }
}
