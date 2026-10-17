package io.popdesk.branding;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.Delivery;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The display name in mail (S9-AC-26, 27, BRAND-4): the sender's name is the display name ({@code MAIL_FROM} of this
 * setup is an address without a name), and the invitation, the password reset, and the test mail say the display
 * name where they said "popdesk". The mail of an issue keeps "지원팀", and no mail carries an image. The
 * installation language is en; each test saves a display name of its own first, with Hangul in it, so the name is
 * sent as UTF-8.
 */
class BrandingMailTest extends BrandingAppTest {

    private static final String RESET = "/api/public/password-reset/request";

    /** A display name of this test: a space, Hangul, and a number no other test sends. */
    private static String hangulName() {
        return "Acme 데스크 " + Unique.number();
    }

    /** The name of the {@code From} header, decoded. */
    private static String senderName(ReceivedMail m) {
        List<String> from = m.header("From");
        assertThat(from).as("From headers of %s", m.subject()).hasSize(1);
        try {
            InternetAddress address = new InternetAddress(from.getFirst());
            assertThat(address.getAddress()).as("From address").isEqualTo(MailServer.FROM);
            return address.getPersonal();
        } catch (AddressException e) {
            throw new AssertionError("From is not an address: " + from.getFirst(), e);
        }
    }

    /** The mail's text without the two app hosts, which hold the word popdesk on their own. */
    private static String textWithoutHosts(ReceivedMail m) {
        return m.text().replace(CONSOLE_HOST, "{console}").replace(PORTAL_HOST, "{portal}");
    }

    /** No part of the mail is an image, inline or attached. */
    private static void assertNoImage(ReceivedMail m) {
        assertThat(m.raw().toLowerCase(Locale.ROOT)).as("raw source of %s", m.subject()).doesNotContain("image/")
                .doesNotContain("content-id:");
    }

    /** The sender's name is {@code name}, the subject and the text say {@code name} and not popdesk. */
    private static void assertNamed(ReceivedMail m, String name, String subject, String sentence) {
        assertThat(senderName(m)).as("sender name of %s", m.subject()).isEqualTo(name);
        assertThat(m.subject()).as("subject").isEqualTo(subject);
        assertThat(textWithoutHosts(m)).as("text of %s", m.subject()).contains(sentence)
                .doesNotContainIgnoringCase("popdesk");
        assertNoImage(m);
    }

    @Test
    @DisplayName("S9-AC-26 S9-AC-27 an invitation sent after the display name changed comes from the display name and "
            + "says it in the subject and the text where it said popdesk, without an image")
    void invitationSaysTheDisplayName() {
        Session admin = fixtures.signInAdmin();
        String name = hangulName();
        save(admin, name, null);

        String email = fixtures.invite(admin, Role.AGENT).email();

        assertNamed(mail().awaitMessageTo(email), name, "You are invited to " + name, "You are invited to " + name
                + ".");
    }

    @Test
    @DisplayName("S9-AC-26 S9-AC-27 a password reset mail sent after the display name changed comes from the display "
            + "name and says it in the subject and the text where it said popdesk, without an image")
    void passwordResetSaysTheDisplayName() {
        Session admin = fixtures.signInAdmin();
        Session agent = fixtures.agent(admin);
        String name = hangulName();
        save(admin, name, null);
        mail().reset();

        console().post(RESET).json(Map.of("email", agent.email())).expect(202);

        ReceivedMail m = mail().awaitMessage("the reset mail to " + agent.email(),
                r -> r.isTo(agent.email()) && r.text().contains("/reset-password#token="));
        assertNamed(m, name, "Reset your " + name + " password", "A password reset was asked for your " + name
                + " account.");
    }

    @Test
    @DisplayName("S9-AC-26 S9-AC-27 a test mail sent after the display name changed comes from the display name and "
            + "says it in the subject and the text where it said popdesk, without an image")
    void testMailSaysTheDisplayName() {
        Session tester = fixtures.admin(fixtures.signInAdmin());
        String name = hangulName();
        save(tester, name, null);

        JsonNode result = Delivery.testMail(tester);
        assertThat(result.path("ok").isBoolean() && result.path("ok").booleanValue()).as("ok in %s", result).isTrue();

        ReceivedMail m = mail().awaitMessageTo(tester.email());
        assertNamed(m, name, name + " test email", "This is a test email to check the email settings of " + name
                + ".");
    }

    @Test
    @DisplayName("S9-AC-26 S9-AC-27 the mail of an issue comes from the display name and still says 지원팀, not the "
            + "display name, without an image")
    void issueMailKeepsTheSupportTeam() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session agent = fixtures.agent(admin, company.code());
        Session customer = fixtures.customer(admin, company.code());
        customer.client().patch("/api/portal/me").json(Map.of("locale", "ko")).expect(200);
        String name = hangulName();
        save(admin, name, null);
        Issue issue = fixtures.fileIssue(customer, company.code());
        mail().reset();

        fixtures.comment(agent, issue.key(), "Answer " + Unique.number());

        ReceivedMail m = mail().awaitMessage("the answer mail to " + customer.email(),
                r -> r.isTo(customer.email()) && r.subject().contains(issue.key()));
        assertThat(senderName(m)).as("sender name of the issue mail").isEqualTo(name);
        assertThat(m.subject() + "\n" + m.text()).as("subject and text of the issue mail").contains("지원팀")
                .doesNotContain(name);
        assertNoImage(m);
    }
}
