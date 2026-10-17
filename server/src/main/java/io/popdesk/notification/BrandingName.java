package io.popdesk.notification;

/**
 * The display name of the installation, read when an email is sent (BRAND-4, S9-AC-26, S9-AC-27): helpdesk owns
 * the branding and implements this. Notification never stores it, so an email waiting in the outbox goes out with
 * the name of the moment it is sent.
 */
public interface BrandingName {

    /** 1 to 50 characters, with no line break, control character, or direction mark; {@code popdesk} by default. */
    String displayName();
}
