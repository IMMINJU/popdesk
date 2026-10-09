package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.ConnectedAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver.Received;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Team webhook messages (HOOK-1): {@code {"text": "..."}} posted to {@code NOTIFY_WEBHOOK_URL} when a
 * customer files an issue or comments. The tests fix what the text holds and leaves out; the exact
 * wording is fixed by the snapshot in C-10.
 */
class WebhookMessageTest extends ConnectedAppTest {

    private NotifySteps steps;

    @BeforeEach
    void setUp() {
        steps = new NotifySteps(fixtures);
    }

    /** The {@code text} of a webhook body that is a JSON object with that one field. */
    private static String text(Received r) {
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.header("Content-Type")).as("Content-Type").startsWith("application/json");
        JsonNode body = r.json();
        List<String> fields = new ArrayList<>(body.propertyNames());
        assertThat(fields).as("fields of " + r.text()).containsExactly("text");
        assertThat(body.path("text").isString()).as("text is a string in " + r.text()).isTrue();
        return body.path("text").stringValue();
    }

    /**
     * A scene whose company name and legal name do not hold the company code. Issue keys and console
     * links hold the code ({@code CODE-n}), and the default fixture names do too ({@code Company CODE}),
     * so the code is checked in what is left of the text without them.
     */
    private Scene sceneWithPlainNames() {
        Session admin = fixtures.signInAdmin();
        long n = Unique.number();
        Company company = fixtures.createCompany(admin, Unique.companyCode(), "Hooked company " + n,
                "Hooked company " + n + " Ltd.");
        Session customer = steps.person(admin, Role.CUSTOMER, "Customer", company.code());
        return new Scene(admin, company, customer, null);
    }

    /** The text without the issue's console link and key. */
    private static String withoutKeyAndLink(String text, String key) {
        return text.replace(NotifySteps.consoleIssueLink(key), "").replace(key, "");
    }

    @Test
    @DisplayName("S0-AC-75 a new issue posts {\"text\"} with the issue key, title, company code, and console link, and no customer name, email, or content")
    void newIssueMessage() {
        Scene s = sceneWithPlainNames();
        String title = Unique.text("Hooked title");
        String content = "Hooked content " + Unique.number();

        Issue issue = fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).title(title).content(content));

        String text = text(awaitWebhooks(webhook(), issue.key(), 1).getFirst());
        assertThat(text).contains(issue.key()).contains(title)
                .contains(NotifySteps.consoleIssueLink(issue.key()))
                .doesNotContain(PORTAL_HOST)
                .doesNotContain(content)
                .doesNotContain(s.customer().name())
                .doesNotContainIgnoringCase(s.customer().email());
        assertThat(withoutKeyAndLink(text, issue.key())).as("the text without the key and the link")
                .contains(s.code());
        Wait.holds("one webhook for one new issue", Duration.ofSeconds(3),
                () -> webhook().containing(issue.key()).size() == 1);
    }

    @Test
    @DisplayName("S0-AC-75 a customer's comment posts a message of another kind with the key, title, code, and link, and no comment text or customer")
    void customerCommentMessage() {
        Scene s = sceneWithPlainNames();
        String title = Unique.text("Commented title");
        Issue issue = fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).title(title));
        String created = text(awaitWebhooks(webhook(), issue.key(), 1).getFirst());
        String comment = "Customer says " + Unique.number();

        fixtures.comment(s.customer(), issue.key(), comment);

        String commented = text(awaitWebhooks(webhook(), issue.key(), 2).get(1));
        assertThat(commented).contains(issue.key()).contains(title)
                .contains(NotifySteps.consoleIssueLink(issue.key()))
                .doesNotContain(comment)
                .doesNotContain(s.customer().name())
                .doesNotContainIgnoringCase(s.customer().email());
        assertThat(withoutKeyAndLink(commented, issue.key())).as("the text without the key and the link")
                .contains(s.code());
        assertThat(commented).as("the comment message tells another kind of event than the new issue message")
                .isNotEqualTo(created);
    }

    @Test
    @DisplayName("S0-AC-75 staff comments, public or internal, post no webhook")
    void staffCommentsPostNothing() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        awaitWebhooks(webhook(), issue.key(), 1);

        fixtures.comment(s.agent(), issue.key(), "Public answer " + Unique.number());
        fixtures.internalComment(s.agent(), issue.key(), "Internal note " + Unique.number());
        fixtures.comment(s.admin(), issue.key(), "Admin answer " + Unique.number());

        Wait.holds("only the new issue's webhook for " + issue.key(), Duration.ofSeconds(3),
                () -> webhook().containing(issue.key()).size() == 1);
    }
}
