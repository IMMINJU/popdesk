package io.popdesk.notification.web.console;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What came of a test email (S4B-AC-22, S4B-AC-23). Never carries the SMTP password, the SMTP user
 * name, or the address the email went to (S4B-AC-24, S4B-AC-31).
 */
public record TestMailResult(
        @Schema(description = "The SMTP server took the email.") boolean ok,
        @Schema(description = "Kind of the error, for example `SMTP_AUTHENTICATION_FAILED` or "
                + "`CONNECTION_FAILED`, as in `FailedDelivery.lastErrorKind`, or `RATE_LIMITED` when the address "
                + "already got its 50 emails this hour; null when `ok`.", nullable = true)
                String errorKind,
        @Schema(description = "What the SMTP server answered, as plain text of up to 500 characters, with line "
                + "breaks but no other control characters; show it as text, never as markup. The address the "
                + "email went to is masked after its first character, and the SMTP user name and password "
                + "are replaced by `***`. null when the server gave no answer: when the email was taken, "
                + "or when the server could not be reached.", nullable = true)
                String serverReply) {}
