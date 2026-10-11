package io.popdesk.notification.outbox;

import io.popdesk.notification.AuditCopyRows;
import io.popdesk.notification.AuditEntries;
import io.popdesk.notification.outbox.OutboxRepository.OutboxRow;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The outside copy of the audit log (AUDIT-9, S1B-AC-29): one {@code POST} per audit entry to
 * {@code AUDIT_FORWARD_URL}, the entry as JSON, read when it is sent. With {@code AUDIT_FORWARD_TOKEN}
 * the request carries {@code Authorization: Bearer} and the token; without it, no such header (D-54).
 * Any 2xx answer is a success, and a redirect is not followed, so the token never goes to another
 * host. A row whose entry is gone, or that waits while the copy is off, is dropped.
 *
 * <p>The address and the token are secrets (S1B-AC-32): neither is logged, and an error is kept as a
 * kind only.
 */
@Component
class AuditCopySender implements Sender {

    private final AuditEntries entries;
    private final Environment environment;
    private volatile HttpClient client;

    AuditCopySender(AuditEntries entries, Environment environment) {
        this.entries = entries;
        this.environment = environment;
    }

    @Override
    public String channel() {
        return OutboxRepository.AUDIT_FORWARD;
    }

    @Override
    public Attempt attempt(OutboxRow row) {
        if (row.auditId() == null || !AuditCopyRows.enabled(environment::getProperty)) {
            return Attempt.dropped();
        }
        Optional<String> body = entries.body(row.auditId());
        if (body.isEmpty()) {
            return Attempt.dropped();
        }
        HttpRequest.Builder request;
        try {
            request = HttpRequest.newBuilder(URI.create(environment.getProperty(AuditCopyRows.URL_KEY).strip()))
                    .timeout(JsonPost.TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.get(), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Attempt.failed("AUDIT_FORWARD_URL_INVALID");
        }
        String token = environment.getProperty(AuditCopyRows.TOKEN_KEY);
        if (token != null && !token.isBlank()) {
            try {
                request.header("Authorization", "Bearer " + token);
            } catch (IllegalArgumentException e) {
                return Attempt.failed("AUDIT_FORWARD_TOKEN_INVALID");
            }
        }
        return JsonPost.send(client(), request.build());
    }

    private HttpClient client() {
        HttpClient c = client;
        if (c == null) {
            c = JsonPost.newClient();
            client = c;
        }
        return c;
    }
}
