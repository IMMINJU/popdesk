package io.popdesk.notify;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.AppLog;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A sandbox without an address, with SMTP set up: no mail goes anywhere. Each mail leaves one log
 * line with the address masked, as without SMTP ({@code NoSmtpMailLogTest}), and its row is deleted.
 *
 * <p>The people whose log lines are read get addresses that start with {@code q}, a letter no other
 * address in this context starts with.
 */
class SandboxNoAddressTest extends Delivery.SandboxNoAddress {

    private List<String> maskedLines(AppLog.Mark mark) {
        return Delivery.maskedLines(log.lines(mark), 'q');
    }

    private long mailRows(Session recipient) {
        return db.count("select count(*) from outbox where channel = 'MAIL' and recipient_account_id = ?",
                recipient.accountId());
    }

    @Test
    @DisplayName("S4B-AC-03 in a sandbox without an address a staff answer sends no mail although SMTP is set "
            + "up: one log line with the customer's address masked, the mail row deleted, no SMTP connection")
    void answerIsLoggedNotSent() {
        Session admin = admin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.accept(invite(admin, Role.CUSTOMER, Unique.email("quinn"), Unique.text("Quinn"),
                List.of(company.code())));
        Session agent = person(admin, Role.AGENT, "Staffer", company.code());
        String key = fixtures.fileIssue(customer, company.code()).key();
        Wait.untilAsserted("the invitation's mail row gone, sent or logged", () -> assertThat(mailRows(customer))
                .isZero());
        smtp().reset();
        AppLog.Mark mark = log.mark();

        fixtures.comment(agent, key, "Answer " + Unique.number());

        Wait.until("a log line with the masked address q***", () -> maskedLines(mark).isEmpty() ? null : mark);
        Wait.holds("one log line for one mail", Duration.ofSeconds(2), () -> maskedLines(mark).size() == 1);
        assertThat(Delivery.foundIn(log.since(mark), customer.email())).as("the customer's address in the log")
                .isEmpty();
        Wait.untilAsserted("the mail row of the customer deleted", () -> assertThat(mailRows(customer)).isZero());
        assertThat(mail().messages()).as("mail about %s at any address", key)
                .noneMatch(m -> (m.subject() + "\n" + m.text()).contains(key));
        assertThat(smtp().connections()).as("connections to the SMTP server").isZero();
    }

    @Test
    @DisplayName("S4B-AC-03 in a sandbox without an address an invitation is not mailed: the response carries "
            + "the link, and the log has one line with the address masked and no link or token")
    void invitationIsLoggedNotSent() {
        Session admin = admin();
        String email = Unique.email("quentin");
        AppLog.Mark mark = log.mark();

        JsonNode response = admin.client().post("/api/console/invitations")
                .json(Map.of("email", email, "name", Unique.text("Quentin"), "role", "AGENT")).expect(201).json();

        Wait.until("a log line with the masked address q***", () -> maskedLines(mark).isEmpty() ? null : mark);
        assertThat(response.path("inviteLink").isString()).as("inviteLink in %s", response).isTrue();
        String link = response.path("inviteLink").stringValue();
        assertThat(log.since(mark)).as("the log since the invitation").doesNotContain(Fixtures.tokenOf(link))
                .doesNotContain(link);
        assertThat(Delivery.foundIn(log.since(mark), email)).as("the invited address in the log").isEmpty();
        assertThat(mail().messagesTo(email)).as("mail to the invited address").isEmpty();
    }

    @Test
    @DisplayName("S4B-AC-07 a sandbox without an address warns at startup that the webhook and mail are off")
    void startupWarning() {
        List<String> warnings = log.lines().stream()
                .filter(l -> l.contains("WARN") && l.toLowerCase(Locale.ROOT).contains("sandbox")).toList();

        assertThat(warnings).as("WARN lines about the sandbox").anyMatch(l -> {
            String lower = l.toLowerCase(Locale.ROOT);
            // The log is shared with the sandbox that has an address; its line holds a masked address.
            return lower.contains("webhook") && lower.contains("mail")
                    && Delivery.maskedLines(List.of(l), Delivery.SANDBOX_TO.charAt(0)).isEmpty();
        });
    }

    @Test
    @DisplayName("S4B-AC-08 the status of a sandbox without an address: sandbox, no address set, and mail, "
            + "webhook, and the outside copy all off although all three are set up")
    void status() {
        Session admin = admin();

        JsonNode status = Delivery.status(admin);

        assertThat(status.path("sandbox").isBoolean() && status.path("sandbox").asBoolean()).as("sandbox in %s",
                status).isTrue();
        assertThat(status.path("sandboxMailToSet").isBoolean() && !status.path("sandboxMailToSet").asBoolean())
                .as("sandboxMailToSet is false in %s", status).isTrue();
        for (String channel : Delivery.CHANNELS.values()) {
            assertThat(status.path(channel).path("enabled").asBoolean(true)).as("%s.enabled", channel).isFalse();
        }
    }
}
