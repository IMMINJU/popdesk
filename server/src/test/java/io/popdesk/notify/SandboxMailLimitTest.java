package io.popdesk.notify;

import static io.popdesk.notify.Delivery.MAIL_LIMIT;
import static io.popdesk.notify.Delivery.SANDBOX_TO;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The mail limit in a sandbox counts the address the mail really goes to: all of it goes to one
 * address, so mail for different people adds up to 50 an hour. The clock moved 61 minutes on before
 * the test ({@link Delivery.SandboxWithAddress}), so the mails counted are those at the sandbox address
 * since the test began, invitations included.
 */
class SandboxMailLimitTest extends Delivery.SandboxWithAddress {

    @AfterEach
    void leaveNoFailedRow() {
        startClean();
    }

    private int atSandboxAddress() {
        return mail().messagesTo(SANDBOX_TO).size();
    }

    @Test
    @DisplayName("S4B-AC-13, S4B-AC-38 in a sandbox mail for two people adds up: after 50 at the sandbox address "
            + "the next mail for either is failed with RATE_LIMITED, and the log never holds the address")
    void mailForTwoPeopleAddsUp() {
        Cast c = cast();
        Session second = person(c.admin(), Role.CUSTOMER, "Second", c.code());
        Session secondAgent = person(c.admin(), Role.AGENT, "Staffer", c.code());
        String first = fixtures.fileIssue(c.customer(), c.code()).key();
        String other = fixtures.fileIssue(second, c.code()).key();
        answer(c.agent(), first, 1);
        Delivery.awaitRedirected(mail(), c.customer().accountId(), m -> m.text().contains(first));
        answer(c.agent(), other, 1);
        Delivery.awaitRedirected(mail(), second.accountId(), m -> m.text().contains(other));
        int left = MAIL_LIMIT - atSandboxAddress();
        assertThat(left).as("mails left of the %s this hour", MAIL_LIMIT).isPositive();

        answer(c.agent(), first, left / 2);
        answer(secondAgent, other, left - left / 2);
        Wait.untilAsserted(MAIL_LIMIT + " mails at the sandbox address", Duration.ofSeconds(60),
                () -> assertThat(atSandboxAddress()).isEqualTo(MAIL_LIMIT));
        answer(c.agent(), first, 1);
        answer(secondAgent, other, 1);

        for (Session customer : List.of(c.customer(), second)) {
            JsonNode row = Delivery.awaitFailedRow(c.admin(), "a failed mail row of " + customer.email(),
                    n -> "MAIL".equals(n.path("channel").asString(null))
                            && n.path("recipientAccountId").asLong(-1) == customer.accountId());
            assertThat(row.path("lastErrorKind").asString(null)).as("lastErrorKind of %s", row)
                    .isEqualTo("RATE_LIMITED");
        }
        assertThat(atSandboxAddress()).as("mails at the sandbox address").isEqualTo(MAIL_LIMIT);
        assertThat(Delivery.foundIn(log.all(), SANDBOX_TO)).as("the sandbox address in the whole log").isEmpty();
    }
}
