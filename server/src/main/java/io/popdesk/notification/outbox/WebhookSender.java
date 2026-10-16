package io.popdesk.notification.outbox;

import io.popdesk.InstallationLocale;
import io.popdesk.PublicAddress;
import io.popdesk.PublicAddress.App;
import io.popdesk.notification.IssueFacts;
import io.popdesk.notification.IssueFacts.Issue;
import io.popdesk.notification.Sandbox;
import io.popdesk.notification.WebhookKind;
import io.popdesk.notification.outbox.OutboxRepository.OutboxRow;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The webhook channel (HOOK-1, S0-AC-75): {@code POST {"text": "..."}} to {@code NOTIFY_WEBHOOK_URL}.
 * The text is made when it is sent, from the issue as it is then, in the installation's language. It
 * holds the kind of event, the issue key, the title, the company code, and the console link; never a
 * customer's name or email, or what an issue or comment says. Any 2xx answer is a success. A row that
 * waits while the webhook is off is dropped: without an address, and in a sandbox, where it came with
 * the copied database and must not reach the real receiver (S4B-AC-04).
 *
 * <p>The URL is a secret (S0-AC-76): it is never logged, and the HTTP client is not one Spring Boot
 * measures, so it is no metric label either.
 */
@Component
class WebhookSender implements Sender {

    private final IssueFacts issues;
    private final PublicAddress address;
    private final Environment environment;
    private final InstallationLocale locale;
    private final Sandbox sandbox;
    private volatile HttpClient client;

    WebhookSender(IssueFacts issues, PublicAddress address, Environment environment, InstallationLocale locale,
            Sandbox sandbox) {
        this.issues = issues;
        this.address = address;
        this.environment = environment;
        this.locale = locale;
        this.sandbox = sandbox;
    }

    @Override
    public String channel() {
        return OutboxRepository.WEBHOOK;
    }

    @Override
    public Attempt attempt(OutboxRow row) {
        if (!Channels.webhookOn(environment, sandbox)) {
            return Attempt.dropped();
        }
        String url = environment.getProperty(Channels.WEBHOOK_URL_KEY);
        Issue issue = row.issueId() == null ? null : issues.find(row.issueId()).orElse(null);
        if (issue == null) {
            return Attempt.dropped();
        }
        String body = "{\"text\":" + json(text(WebhookKind.valueOf(row.kind()), issue)) + "}";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url.strip()))
                    .timeout(JsonPost.TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            return Attempt.failed("WEBHOOK_URL_INVALID");
        }
        return JsonPost.send(client(), request);
    }

    /** The message text in the installation's language ({@code POPDESK_LOCALE}). */
    String text(WebhookKind kind, Issue issue) {
        return text(kind, issue, MailText.korean(locale.language()), address.origin(App.CONSOLE));
    }

    /**
     * The wording (S0-AC-75, S3-AC-74): what happened, the issue key, the company code, and the title on
     * one line; the console link on the next.
     */
    static String text(WebhookKind kind, Issue issue, boolean korean, String consoleOrigin) {
        String title = MailText.oneLine(issue.title());
        String link = consoleOrigin + "/issues/" + issue.key();
        String which = issue.key() + (korean ? " (고객사 " + issue.companyCode() + ")" : " from " + issue.companyCode());
        String what = switch (kind) {
            case ISSUE_CREATED -> korean ? "새 문의 " + which : "New issue " + which;
            case CUSTOMER_COMMENT -> korean ? "고객 댓글 " + which : "Customer comment on " + which;
            case ISSUE_RESOLVED -> korean ? "문의 해결 " + which : "Issue " + which + " resolved";
            case ISSUE_CLOSED -> korean ? "문의 종료 " + which : "Issue " + which + " closed";
            case ISSUE_REOPENED -> korean ? "문의 다시 열림 " + which : "Issue " + which + " reopened";
            // One wording for an assignee set, changed, and cleared; it names nobody (S3-AC-74).
            case ASSIGNEE_CHANGED -> korean ? "담당자 변경 " + which : "Assignee of " + which + " changed";
            // One message per goal that ran out (S8B-AC-18).
            case SLA_RESPONSE_BREACHED -> (korean ? "SLA 첫 응답 기한 넘김 " : "SLA first response overdue ") + which;
            case SLA_RESOLUTION_BREACHED -> (korean ? "SLA 해결 기한 넘김 " : "SLA resolution overdue ") + which;
        };
        return what + ": " + title + "\n" + link;
    }

    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            c = JsonPost.newClient();
            client = c;
        }
        return c;
    }

    /** A JSON string literal. */
    static String json(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}
