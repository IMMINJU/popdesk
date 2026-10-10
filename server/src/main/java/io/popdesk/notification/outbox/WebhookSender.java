package io.popdesk.notification.outbox;

import io.popdesk.InstallationLocale;
import io.popdesk.PublicAddress;
import io.popdesk.PublicAddress.App;
import io.popdesk.notification.IssueFacts;
import io.popdesk.notification.IssueFacts.Issue;
import io.popdesk.notification.WebhookKind;
import io.popdesk.notification.outbox.OutboxRepository.OutboxRow;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLException;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The webhook channel (HOOK-1, S0-AC-75): {@code POST {"text": "..."}} to {@code NOTIFY_WEBHOOK_URL}.
 * The text is made when it is sent, from the issue as it is then, in the installation's language. It
 * holds the kind of event, the issue key, the title, the company code, and the console link; never a
 * customer's name or email, or what an issue or comment says. Any 2xx answer is a success.
 *
 * <p>The URL is a secret (S0-AC-76): it is never logged, and the HTTP client is not one Spring Boot
 * measures, so it is no metric label either.
 */
@Component
class WebhookSender implements Sender {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final IssueFacts issues;
    private final PublicAddress address;
    private final Environment environment;
    private final InstallationLocale locale;
    private volatile HttpClient client;

    WebhookSender(IssueFacts issues, PublicAddress address, Environment environment, InstallationLocale locale) {
        this.issues = issues;
        this.address = address;
        this.environment = environment;
        this.locale = locale;
    }

    @Override
    public String channel() {
        return OutboxRepository.WEBHOOK;
    }

    @Override
    public Attempt attempt(OutboxRow row) {
        String url = environment.getProperty("NOTIFY_WEBHOOK_URL");
        Issue issue = row.issueId() == null ? null : issues.find(row.issueId()).orElse(null);
        if (url == null || url.isBlank() || issue == null) {
            return Attempt.dropped();
        }
        String body = "{\"text\":" + json(text(WebhookKind.valueOf(row.kind()), issue)) + "}";
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url.strip()))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            return Attempt.failed("WEBHOOK_URL_INVALID");
        }
        try {
            HttpResponse<Void> response = client().send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 300 ? Attempt.sent() : Attempt.failed("HTTP_" + status);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            return Attempt.failed("CONNECTION_FAILED");
        } catch (HttpTimeoutException e) {
            return Attempt.failed("TIMEOUT");
        } catch (SSLException e) {
            return Attempt.failed("TLS_FAILED");
        } catch (IOException e) {
            return Attempt.failed(e.getCause() instanceof SSLException ? "TLS_FAILED" : "IO_ERROR");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Attempt.failed("INTERRUPTED");
        }
    }

    /** The message text in the installation's language ({@code POPDESK_LOCALE}). */
    String text(WebhookKind kind, Issue issue) {
        String title = MailText.oneLine(issue.title());
        String link = address.origin(App.CONSOLE) + "/issues/" + issue.key();
        boolean korean = MailText.korean(locale.language());
        String what = switch (kind) {
            case ISSUE_CREATED -> korean ? "새 문의" : "New issue";
            case CUSTOMER_COMMENT -> korean ? "고객 댓글" : "Customer comment on";
        };
        String company = korean ? " (고객사 " + issue.companyCode() + ")" : " from " + issue.companyCode();
        return what + " " + issue.key() + company + ": " + title + "\n" + link;
    }

    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            c = HttpClient.newBuilder()
                    .connectTimeout(CONNECT_TIMEOUT)
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
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
