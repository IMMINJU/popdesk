/**
 * The outbox, outgoing email (SMTP), webhooks, resending, and incoming email (IMAP)
 * (design/spec/architecture.md section 3).
 */
@ApplicationModule(displayName = "Notification")
package io.popdesk.notification;

import org.springframework.modulith.ApplicationModule;
