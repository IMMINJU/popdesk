package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Outgoing channels, the sandbox, the cleanup jobs, OIDC sign-in, and receiving email (OPS-3 in part). {@code auditForward} is the
 * outside copy of the audit log (S1B-AC-33). Never carries the sandbox address itself (S4B-AC-08).
 */
public record SystemStatus(
        ChannelStatus mail,
        ChannelStatus webhook,
        ChannelStatus auditForward,
        @Schema(description = "`POPDESK_SANDBOX=true`: the webhook and the outside copy of the audit log are off, "
                + "and email goes to one address only or is off (S4B-AC-08).") boolean sandbox,
        @Schema(description = "The sandbox has an address to send email to (`POPDESK_SANDBOX_MAIL_TO`). `false` "
                + "outside a sandbox. The address itself is never given.") boolean sandboxMailToSet,
        @Schema(description = "Every cleanup job, in name order (S4B-AC-30).") List<CleanupJobStatus> cleanupJobs,
        @Schema(description = "OIDC sign-in, counted since the app started (S11-AC-30).") OidcStatus oidc,
        @Schema(description = "Receiving email over IMAP (S12-AC-36).") MailInStatus mailIn) {}
