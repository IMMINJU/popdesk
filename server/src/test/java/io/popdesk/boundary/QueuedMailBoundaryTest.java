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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A comment mail already in the send queue, after its recipient changed (S0-AC-92, last two
 * sentences). The slice has no API for either change, so they are made with the two business-row
 * changes tasks.md 6절 allows: taking a customer out of a company ({@link Membership}) and moving an
 * account out of {@code ACTIVE} ({@link AccountStatus}).
 *
 * <p>The mail is kept in the queue by closing the {@link SmtpGate}: the first attempt fails, the row
 * stays, and the change is made before the retry 1 second later (on the app's clock).
 */
class QueuedMailBoundaryTest extends GatedMailTest {

    /** A company with two customers, each with an issue, and an assigned agent. */
    private record Scene(Company company, Session first, Session second, Session agent, Issue ofFirst,
            Issue ofSecond) {}

    private Scene scene() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session first = fixtures.customer(admin, company.code());
        Session second = fixtures.customer(admin, company.code());
        Session agent = fixtures.agent(admin, company.code());
        return new Scene(company, first, second, agent, fixtures.fileIssue(first, company.code()),
                fixtures.fileIssue(second, company.code()));
    }

    /** Closes the gate and answers both issues; returns once both mails were tried and refused. */
    private void queueAnswers(Scene s) {
        gate().hold();
        fixtures.comment(s.agent(), s.ofFirst().key(), "Answer to the first customer");
        fixtures.comment(s.agent(), s.ofSecond().key(), "Answer to the second customer");
        gate().awaitHeldTo(s.first().email());
        gate().awaitHeldTo(s.second().email());
    }

    private void retry() {
        gate().pass();
        clock.advance(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("S0-AC-92 a queued comment mail still goes to a customer taken out of the company, who still sees the issue they filed")
    void queuedMailReachesCustomerWhoLeft() {
        Scene s = scene();
        queueAnswers(s);

        new Membership(db).remove(s.first().accountId(), s.company().code());
        s.first().client().get("/api/portal/issues/" + s.ofFirst().key()).expect(200);
        assertThat(BoundaryChecks.allPages(s.first(), "/api/portal/companies").texts("code"))
                .as("companies of the customer who left").doesNotContain(s.company().code());
        retry();

        ReceivedMail toLeaver = awaitNotifications(mail(), s.first().email(), 1).getFirst();
        ReceivedMail toStayer = awaitNotifications(mail(), s.second().email(), 1).getFirst();
        assertThat(toLeaver.text()).as("mail to the customer who left").contains(s.ofFirst().key());
        assertThat(toStayer.text()).as("mail to the customer who stays").contains(s.ofSecond().key());
    }

    @Test
    @DisplayName("S0-AC-92 a queued comment mail is not sent to a customer whose account is no longer ACTIVE, while a colleague's queued mail is")
    void queuedMailSkipsSuspendedCustomer() {
        Scene s = scene();
        queueAnswers(s);

        new AccountStatus(db).suspend(s.first().accountId(), s.first().email());
        assertThat(portal().post("/api/public/sign-in")
                .json(Map.of("email", s.first().email(), "password", s.first().password())).send().status())
                .as("sign-in of the suspended customer").isEqualTo(401);
        retry();

        List<ReceivedMail> sent = awaitNotifications(mail(), s.second().email(), 1);
        assertThat(sent.getFirst().text()).as("mail to the customer who stays").contains(s.ofSecond().key());
        Wait.holds("no mail to the suspended customer", Duration.ofSeconds(3),
                () -> notifications(mail(), s.first().email()).isEmpty());
        assertThat(gate().held()).as("attempts held while the gate was closed")
                .filteredOn(h -> h.isTo(s.first().email())).hasSize(1);
    }
}
