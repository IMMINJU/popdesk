package io.popdesk.boundary;

import static io.popdesk.boundary.BoundaryChecks.awaitNotifications;
import static io.popdesk.boundary.BoundaryChecks.notifications;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A comment mail already in the send queue is not sent to a customer who can no longer see the
 * issue (S0-AC-92, last sentence). The slice has no API that makes an issue private or takes a
 * customer out of a company, so the customer is taken out of the company with SQL ({@link
 * Membership}, the one business-row change tasks.md 6절 allows).
 */
class QueuedMailBoundaryTest extends GatedMailTest {

    @Test
    @DisplayName("S0-AC-92 a queued comment mail is not sent to a customer taken out of the company, while a colleague's queued mail is")
    void queuedMailSkipsCustomerWhoLeft() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session leaving = fixtures.customer(admin, company.code());
        Session staying = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        Issue ofLeaving = fixtures.fileIssue(leaving, company.code());
        Issue ofStaying = fixtures.fileIssue(staying, company.code());

        gate().hold();
        fixtures.comment(agent, ofLeaving.key(), "Answer to the customer who leaves");
        fixtures.comment(agent, ofStaying.key(), "Answer to the customer who stays");
        gate().awaitHeldTo(leaving.email());
        gate().awaitHeldTo(staying.email());
        new Membership(db).remove(leaving.accountId(), company.code());
        assertThat(leaving.client().get("/api/portal/issues/" + ofLeaving.key()).send().status())
                .as("the customer sees the issue after leaving the company").isEqualTo(404);
        staying.client().get("/api/portal/issues/" + ofStaying.key()).expect(200);

        gate().pass();
        clock.advance(Duration.ofSeconds(1));

        List<ReceivedMail> sent = awaitNotifications(mail(), staying.email(), 1);
        assertThat(sent.getFirst().text()).as("mail to the customer who stays").contains(ofStaying.key());
        Wait.holds("no mail to the customer who left", Duration.ofSeconds(3),
                () -> notifications(mail(), leaving.email()).isEmpty());
        assertThat(gate().held()).as("attempts held while the gate was closed")
                .filteredOn(h -> h.isTo(leaving.email())).hasSize(1);
    }
}
