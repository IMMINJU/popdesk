package io.popdesk.helpdesk.account;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * What the server session holds about a sign-in (architecture.md section 5): the account, the app it
 * signed in to, and when. Nothing else: role, status, memberships, and assignments are read from the
 * database on every request (S0-AC-26), and no email or IP address is kept in the session.
 */
record SignedIn(long accountId, String app, Instant signedInAt) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The session attribute that holds it. */
    static final String ATTRIBUTE = "popdesk.signedIn";
}
