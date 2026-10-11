package io.popdesk.helpdesk.account;

import io.popdesk.PublicAddress.App;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.stereotype.Component;

/**
 * Server sessions (architecture.md section 5): one per sign-in, held in an {@code HttpOnly} cookie,
 * ending 8 hours after sign-in by the app's clock (AUTH-4, S0-AC-25).
 */
@Component
public class Sessions {

    /** How long a session lasts after sign-in (AUTH-4). */
    static final Duration LIFETIME = Duration.ofHours(8);

    private final CsrfTokenRepository csrfTokens;
    private final SessionTableRepository table;
    private final Clock clock;

    Sessions(CsrfTokenRepository csrfTokens, SessionTableRepository table, Clock clock) {
        this.csrfTokens = csrfTokens;
        this.table = table;
        this.clock = clock;
    }

    /**
     * Starts a session for the account in a new session ID (S0-AC-17): a session the request came with,
     * or an ID planted in its cookie, is never reused. The CSRF token changes too.
     *
     * @param signedInAt when the check that lets the account in began: a password changed after it ends
     *     the session ({@link SessionFilter})
     */
    void start(HttpServletRequest request, HttpServletResponse response, long accountId, App app,
            Instant signedInAt) {
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setAttribute(SignedIn.ATTRIBUTE, new SignedIn(accountId, app.name(), signedInAt));
        // Lets the sessions of an account be found by its ID (to end them all); never the email.
        session.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, Long.toString(accountId));
        csrfTokens.saveToken(csrfTokens.generateToken(request), request, response);
    }

    /** Ends the request's session, if it has one (S0-AC-27). */
    void end(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    /**
     * Ends every session of the account, on every device (AUTH-4, S1A-AC-01 to S1A-AC-04). A suspension,
     * a role change, or a new password calls this inside the transaction that makes the change, so the
     * change and the end of the sessions are kept or lost together. A request of the account that is
     * running meanwhile does not bring its session back. Returns how many sessions ended.
     */
    public int endAll(long accountId) {
        return table.deleteByPrincipalName(Long.toString(accountId));
    }

    /** Whether a sign-in at {@code signedInAt} still holds now. */
    boolean current(SignedIn signedIn) {
        return clock.instant().isBefore(signedIn.signedInAt().plus(LIFETIME));
    }
}
