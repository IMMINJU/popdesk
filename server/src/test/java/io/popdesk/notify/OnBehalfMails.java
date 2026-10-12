package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.everything;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;

/**
 * The mail a customer gets when staff file an issue for them (S3-AC-37), for the English and the
 * Korean test: the filing, the snapshot form of the mail, and what the mail must not hold.
 *
 * <p>The snapshot is {@code Subject: <subject>}, an empty line, and the text, with the issue key as
 * {@code {key}} and the portal link as {@code {link}}. It is compared with
 * {@link WebhookSnapshots#assertMatches}, against {@code notify/__snapshots__/}.
 */
final class OnBehalfMails {

    private OnBehalfMails() {}

    /** The scene's agent files a public issue for {@code customer}, titled {@link WebhookSnapshots#TITLE}. */
    static Issue file(IssueFlow flow, Scene s, Session customer, String content) {
        return flow.fileOnBehalf(s.agent(), OnBehalf.of(s.code(), customer.accountId()).title(WebhookSnapshots.TITLE)
                .content(content));
    }

    static String snapshotForm(ReceivedMail m, String key) {
        String text = m.text().replace("\r\n", "\n").replace(NotifySteps.portalIssueLink(key), "{link}")
                .replace(key, "{key}").stripTrailing();
        return "Subject: " + m.subject().replace(key, "{key}") + "\n\n" + text;
    }

    /** The mail links to the issue on the portal and holds nothing of the agent and no word of the content. */
    static void assertLinksAndHidesStaff(ReceivedMail m, Scene s, String key, String wordOfContent) {
        assertThat(m.text()).as("text of the mail").contains(NotifySteps.portalIssueLink(key))
                .doesNotContain(NotifySteps.consoleIssueLink(key));
        assertThat(everything(m)).as("the whole mail").doesNotContainIgnoringCase(s.agent().name())
                .doesNotContainIgnoringCase(s.agent().email()).doesNotContainIgnoringCase(wordOfContent);
    }
}
