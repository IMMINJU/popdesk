package io.popdesk.notification;

import io.popdesk.PublicAddress.App;
import java.util.List;
import java.util.Optional;

/**
 * The accounts notification sends to, read when it sends (MAIL-5): helpdesk owns the accounts and
 * implements this. Notification never stores an address.
 */
public interface Recipients {

    /** An account's status as far as sending is concerned. */
    enum Status {
        INVITED,
        ACTIVE,
        /** Any other status, for example {@code SUSPENDED}: nothing is sent. */
        INACTIVE
    }

    /**
     * An account as it is now.
     *
     * @param email where mail goes
     * @param name what the account is called, for the greeting of an invitation
     * @param locale the language of its mail, {@code en} or {@code ko}
     * @param app the app it signs in to
     */
    record Recipient(String email, String name, String locale, App app, Status status) {}

    /** The account, or empty when there is none. */
    Optional<Recipient> find(long accountId);

    /** The {@code ACTIVE} admins now: who gets an operations alert (S0-AC-80). */
    List<Long> activeAdmins();
}
