package io.popdesk.notification.web.console;

import java.time.OffsetDateTime;

/** The last failed round of receiving email. */
public record MailInFailure(MailInFailureKind kind, OffsetDateTime at) {}
