package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.WebhookReceiver;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The webhook message snapshots (S0-AC-75): a customer files an issue and comments, and the two
 * request bodies, as sent, must equal the files in {@code notify/__snapshots__/}. The parts that
 * change from run to run are replaced first: the console link by {@code {link}}, the issue key by
 * {@code {key}}, the company code by {@code {code}}.
 *
 * <p>The title holds a line break and quotes, so the snapshot also fixes how the text keeps to one
 * line and how the body is escaped.
 */
final class WebhookSnapshots {

    static final String TITLE = "Printer \"B2\" jams\nafter toner change";

    private WebhookSnapshots() {}

    /** Files an issue and a customer comment in {@code s}, and returns the two bodies with placeholders. */
    static List<String> bodies(Fixtures fixtures, WebhookReceiver webhook, Scene s) {
        Issue issue = fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).title(TITLE));
        awaitWebhooks(webhook, issue.key(), 1);
        fixtures.comment(s.customer(), issue.key(), "Still jams");
        List<WebhookReceiver.Received> sent = awaitWebhooks(webhook, issue.key(), 2);
        return sent.stream()
                .map(r -> r.text()
                        .replace(NotifySteps.consoleIssueLink(issue.key()), "{link}")
                        .replace(issue.key(), "{key}")
                        .replace(s.code(), "{code}"))
                .toList();
    }

    /**
     * Fails unless {@code actual} equals the snapshot {@code name}. When they differ, the actual body
     * is written to {@code target/snapshots/} to compare or to copy over the snapshot after review.
     */
    static void assertMatches(String name, String actual) {
        String expected = read(name);
        if (!actual.equals(expected)) {
            Path out = Path.of("target", "snapshots", name);
            try {
                Files.createDirectories(out.getParent());
                Files.writeString(out, actual + "\n", StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        assertThat(actual).as("webhook body %s (actual written to target/snapshots/%s)", name, name)
                .isEqualTo(expected);
    }

    private static String read(String name) {
        String resource = "notify/__snapshots__/" + name;
        try (InputStream in = WebhookSnapshots.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return "(no snapshot " + resource + ")";
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
