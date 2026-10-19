package io.popdesk.notification.web.console;

import io.popdesk.Paged;
import io.popdesk.notification.OidcSignInState;
import io.popdesk.notification.ops.CleanupJobs;
import io.popdesk.notification.outbox.DeliveryStatus;
import io.popdesk.notification.outbox.OutboxRepository.FailedRow;
import io.popdesk.notification.outbox.Resend;
import io.popdesk.notification.outbox.TestMail;
import io.popdesk.notification.web.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "System")
@RestController
@RequestMapping("/api/console/system")
class ConsoleSystemController {

    private final DeliveryStatus status;
    private final CleanupJobs cleanupJobs;
    private final Resend resend;
    private final TestMail testMail;
    private final OidcSignInState oidc;

    ConsoleSystemController(DeliveryStatus status, CleanupJobs cleanupJobs, Resend resend, TestMail testMail,
            OidcSignInState oidc) {
        this.status = status;
        this.cleanupJobs = cleanupJobs;
        this.resend = resend;
        this.testMail = testMail;
        this.oidc = oidc;
    }

    @Operation(operationId = "getSystemStatus", summary = "Outgoing channels, the sandbox, and cleanup jobs",
            description = "For mail, webhook, and the outside copy of the audit log (`auditForward`): configured or "
                    + "not, last success, queued and failed rows (S0-AC-81, S1B-AC-33). "
                    + "The console also reads `mail.enabled` to decide whether to offer invitation links (C-10). "
                    + "In a sandbox the webhook and the outside copy are not `enabled`, and neither is mail "
                    + "without a sandbox address (S4B-AC-08). Each cleanup job comes with its last run "
                    + "(S4B-AC-30). `oidc` is OIDC sign-in, counted in memory since the app started (S11-AC-30). No "
                    + "SMTP password, webhook URL, or address or token of the outside copy, and no OIDC issuer, "
                    + "client ID, or secret (S4B-AC-31).")
    @ApiResponse(responseCode = "200", description = "The status.")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @GetMapping("/status")
    SystemStatus getSystemStatus() {
        return new SystemStatus(channel(status.mail()), channel(status.webhook()), channel(status.auditForward()),
                status.sandbox(), status.sandboxMailToSet(), cleanupJobs.list().stream()
                        .map(job -> new CleanupJobStatus(job.name(), utc(job.lastRunAt()), job.deleted())).toList(),
                oidc(oidc.snapshot()));
    }

    private static OidcStatus oidc(OidcSignInState.Snapshot s) {
        return new OidcStatus(s.enabled(), s.apps().stream().map(OidcApp::valueOf).toList(), utc(s.lastSuccessAt()),
                s.providerFailures(), s.lastFailureKind() == null ? null
                        : new OidcFailure(OidcFailureKind.valueOf(s.lastFailureKind().name()), utc(s.lastFailureAt())));
    }

    @Operation(operationId = "listFailedDeliveries", summary = "Outbox rows that stopped retrying",
            description = "Newest first (S0-AC-81). `resendFailedDelivery` sends one of them again.")
    @ApiResponse(responseCode = "200", description = "One page.")
    @ApiResponse(responseCode = "400", description = "`INVALID_INPUT`: `page` or `size` out of range (`OUT_OF_RANGE`).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only.")
    @GetMapping("/failed-deliveries")
    Paged<FailedDelivery> listFailedDeliveries(
            @Parameter(description = "Page number, from 1.") @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "1 to 100.") @RequestParam(defaultValue = "20") int size) {
        return status.failed(page, size).map(ConsoleSystemController::failedDelivery);
    }

    @Operation(operationId = "resendFailedDelivery", summary = "Send a failed outbox row again",
            description = "Puts a `failed` row of any channel back in the queue with its attempts and failure "
                    + "times cleared; it is sent at once and, when it fails again, retried like a new row "
                    + "(S4B-AC-14, S4B-AC-16). The checks before sending run again, so an email whose "
                    + "recipient may no longer get it is dropped, not sent (S4B-AC-15). A row that is back in "
                    + "the queue already is 204 too and changes nothing (S4B-AC-18). Leaves an audit entry "
                    + "`DELIVERY_RESENT` when it changed the row (S4B-AC-20).")
    @ApiResponse(responseCode = "204", description = "Queued again.")
    @ApiResponse(responseCode = "400", description = "`CHANNEL_DISABLED`: the row's channel is not configured or "
            + "the sandbox turned it off. The row stays as it is (S4B-AC-17).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only. `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "404", description = "`NOT_FOUND`: no such row. It was sent, dropped, or cleaned "
            + "up 7 days after it failed (S4B-AC-18).")
    @PostMapping("/failed-deliveries/{id}/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void resendFailedDelivery(
            @Parameter(schema = @Schema(type = "integer", format = "int64")) @PathVariable String id) {
        resend.resend(Caller.accountId(), id);
    }

    @Operation(operationId = "sendTestMail", summary = "Send a test email to the caller",
            description = "Sends one email to the caller's own address, in a sandbox to the sandbox address, "
                    + "within the request and without the outbox, and says what the SMTP server answered "
                    + "(S4B-AC-22). A server that refuses or cannot be reached is 200 with `ok` false, never a "
                    + "5xx (S4B-AC-23). The email counts toward the 50 an hour one address gets (S4B-AC-13). "
                    + "Leaves an audit entry `TEST_MAIL_SENT` whether it worked or not (S4B-AC-27). The request "
                    + "can take as long as the SMTP timeouts.")
    @ApiResponse(responseCode = "200", description = "The email was tried; `ok` says whether it went.")
    @ApiResponse(responseCode = "400", description = "`CHANNEL_DISABLED`: email is not configured, or this is a "
            + "sandbox without an address (S4B-AC-25).")
    @ApiResponse(responseCode = "401", description = "`UNAUTHENTICATED`.")
    @ApiResponse(responseCode = "403", description = "`FORBIDDEN`: admins only. `CSRF_TOKEN_INVALID`.")
    @ApiResponse(responseCode = "429", description = "`RATE_LIMITED`: more than 5 test emails in 60 seconds "
            + "(S4B-AC-26). Counted before the channel is checked.")
    @PostMapping("/test-mail")
    TestMailResult sendTestMail() {
        TestMail.Result result = testMail.send(Caller.accountId());
        return new TestMailResult(result.ok(), result.errorKind(), result.serverReply());
    }

    private static ChannelStatus channel(DeliveryStatus.Channel c) {
        return new ChannelStatus(c.enabled(), utc(c.lastSuccessAt()), c.queued(), c.failed());
    }

    private static FailedDelivery failedDelivery(FailedRow row) {
        return new FailedDelivery(row.id(), DeliveryChannel.valueOf(row.channel()), utc(row.createdAt()),
                row.lastErrorKind(), row.recipientAccountId(), row.auditId());
    }

    private static OffsetDateTime utc(Instant at) {
        return at == null ? null : at.atOffset(ZoneOffset.UTC);
    }
}
