package io.popdesk.notify;

import io.popdesk.support.AppTest;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.MailServer;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver;
import io.popdesk.support.WebhookReceiver.Received;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Steps and checks shared by the send queue, mail, and webhook tests. Only HTTP, through {@link Fixtures}. */
final class NotifySteps {

    private static final Pattern HANGUL = Pattern.compile("[\\uAC00-\\uD7A3]");

    private final Fixtures fixtures;

    NotifySteps(Fixtures fixtures) {
        this.fixtures = fixtures;
    }

    /** An admin, a company, a customer of it, and an agent assigned to it, each with a name and email of its own. */
    record Scene(Session admin, Company company, Session customer, Session agent) {

        String code() {
            return company.code();
        }
    }

    Scene scene() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = person(admin, Role.CUSTOMER, "Customer", company.code());
        Session agent = person(admin, Role.AGENT, "Staffer", null);
        fixtures.assignAgent(admin, company.code(), agent.accountId());
        return new Scene(admin, company, customer, agent);
    }

    /**
     * A signed-in person whose name and email appear nowhere else: {@code "<prefix> 12"} and
     * {@code <prefix>-13@example.test} in lower case. Customers join {@code companyCode}.
     */
    Session person(Session admin, Role role, String prefix, String companyCode) {
        String email = Unique.email(prefix.toLowerCase(Locale.ROOT));
        String name = Unique.text(prefix);
        List<String> codes = companyCode == null ? List.of() : List.of(companyCode);
        Invitation invitation = fixtures.invite(admin, role, email, name, codes);
        return fixtures.accept(invitation);
    }

    // Links (conventions "Invitation links", S0-AC-20; app routes of web/portal and web/console)

    static String portalIssueLink(String key) {
        return "http://" + AppTest.PORTAL_HOST + "/issues/" + key;
    }

    static String consoleIssueLink(String key) {
        return "http://" + AppTest.CONSOLE_HOST + "/issues/" + key;
    }

    static boolean hasHangul(String text) {
        return text != null && HANGUL.matcher(text).find();
    }

    // Mail

    /** Mail to the address other than invitations. */
    static List<ReceivedMail> notifications(MailServer mail, String address) {
        return mail.messagesTo(address).stream().filter(m -> !m.text().contains("/invite#token=")).toList();
    }

    static ReceivedMail awaitNotification(MailServer mail, String address) {
        return Wait.until("a notification mail to " + address, () -> notifications(mail, address).stream()
                .findFirst().orElse(null));
    }

    /** Everything a person could read in a mail: headers, subject, decoded text, raw source. */
    static String everything(ReceivedMail m) {
        return m.headers() + "\n" + m.subject() + "\n" + m.text() + "\n" + m.raw();
    }

    // Webhook

    /** Waits until the receiver has at least {@code n} requests whose body contains {@code text}. */
    static List<Received> awaitWebhooks(WebhookReceiver webhook, String text, int n) {
        return Wait.until(n + " webhook request(s) containing " + text, () -> {
            List<Received> found = webhook.containing(text);
            return found.size() >= n ? found : null;
        });
    }

    /** The webhook URL as it could show up in text: as is, its host and port, encoded, JSON-escaped. */
    static List<String> urlForms(String url) {
        String hostPort = url.replaceFirst("^https?://", "").replaceFirst("/.*$", "");
        return List.of(url, hostPort, URLEncoder.encode(url, StandardCharsets.UTF_8),
                URLEncoder.encode(hostPort, StandardCharsets.UTF_8), url.replace("/", "\\/"));
    }
}
