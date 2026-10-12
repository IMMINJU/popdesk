package io.popdesk.notify;

import static io.popdesk.notify.NotifySteps.awaitWebhooks;

import io.popdesk.issue.IssueFlow;
import io.popdesk.notify.NotifySteps.Scene;
import io.popdesk.support.Fixtures;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.WebhookReceiver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The webhook message snapshots of slice 3 (S3-AC-74): one issue is resolved, reopened, closed, and
 * given an assignee, by staff, and the four request bodies, as sent, must equal the files in
 * {@code notify/__snapshots__/}. Placeholders, the title, and the comparing are those of
 * {@link WebhookSnapshots}.
 */
final class IssueWebhookSnapshots {

    private IssueWebhookSnapshots() {}

    /**
     * Files an issue in {@code s} and takes it through the four changes. Returns the bodies with
     * placeholders by the name of their snapshot, without the language prefix.
     */
    static Map<String, String> bodies(Fixtures fixtures, IssueFlow flow, WebhookReceiver webhook, Scene s) {
        Issue issue = fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).title(WebhookSnapshots.TITLE));
        String key = issue.key();
        awaitWebhooks(webhook, key, 1);
        flow.moveTo(s.agent(), key, "RESOLVED");
        awaitWebhooks(webhook, key, 2);
        flow.reopen(s.agent(), key);
        awaitWebhooks(webhook, key, 3);
        flow.changeStatus(s.agent(), key, "CLOSED");
        awaitWebhooks(webhook, key, 4);
        flow.assign(s.admin(), key, s.agent().accountId());
        List<String> sent = awaitWebhooks(webhook, key, 5).stream()
                .map(r -> r.text()
                        .replace(NotifySteps.consoleIssueLink(key), "{link}")
                        .replace(key, "{key}")
                        .replace(s.code(), "{code}"))
                .toList();
        Map<String, String> out = new LinkedHashMap<>();
        out.put("issue-resolved.json", sent.get(1));
        out.put("issue-reopened.json", sent.get(2));
        out.put("issue-closed.json", sent.get(3));
        out.put("assignee-changed.json", sent.get(4));
        return out;
    }
}
