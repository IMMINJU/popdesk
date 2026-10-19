/**
 * The outbox, outgoing email (SMTP) and the record of its Message-IDs, webhooks, and resending
 * (design/spec/architecture.md section 3).
 */
@ApplicationModule(displayName = "Notification")
package io.popdesk.notification;

import org.springframework.modulith.ApplicationModule;
