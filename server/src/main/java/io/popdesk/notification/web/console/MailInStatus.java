package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

/**
 * Receiving email over IMAP (S12-AC-36). The round times and failures are counted in memory since the app started;
 * a restart empties them. The two mail counts come from the received mail records and survive a restart. Never
 * the IMAP host, user, password, or folder names.
 */
public record MailInStatus(
        @Schema(description = "`IMAP_HOST`, `IMAP_USER`, `IMAP_PASSWORD`, and `MAIL_INBOUND_ADDRESS` are all set, "
                + "outside a sandbox.") boolean enabled,
        @Schema(description = "Why it is off: `NOT_SET` without the settings, `SANDBOX` in a sandbox even with them "
                + "(S12-AC-03). Null when on.", nullable = true) MailInOffReason offReason,
        @Schema(description = "The end of the last round that read the inbox folder through; null before the first "
                + "since the app started.", nullable = true) OffsetDateTime lastSuccessAt,
        @Schema(description = "Rounds that failed since the app started (S12-AC-10).") int roundFailures,
        @Schema(description = "The last of them; null without one.", nullable = true) MailInFailure lastFailure,
        @Schema(description = "Emails left in the inbox folder to try again: a record with no outcome and at least "
                + "one attempt.") int retrying,
        @Schema(description = "Emails quarantined in the last 90 days, over the attempt limit or the rate limit "
                + "(`QUARANTINED` and `RATE_LIMITED`).") int quarantined90d) {}
