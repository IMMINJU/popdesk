package io.popdesk.helpdesk.account;

import io.popdesk.ApiException;
import io.popdesk.Forwarded;
import io.popdesk.HostRoutingFilter;
import io.popdesk.PublicAddress.App;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * The web side of sign-in, accepting an invitation, sign-out, and passwords: the app comes from the
 * host, the client IP from {@link Forwarded}, and a successful check starts a new session (S0-AC-17).
 * A session's sign-in time is taken before the check, so a password that changes while the check runs
 * ends the session ({@link SessionFilter}).
 */
@Component
public class SignInFlow {

    private final SignInService signIn;
    private final InvitationService invitations;
    private final PasswordResetService resets;
    private final OwnAccountService own;
    private final Sessions sessions;
    private final Clock clock;

    SignInFlow(SignInService signIn, InvitationService invitations, PasswordResetService resets, OwnAccountService own,
            Sessions sessions, Clock clock) {
        this.signIn = signIn;
        this.invitations = invitations;
        this.resets = resets;
        this.own = own;
        this.sessions = sessions;
        this.clock = clock;
    }

    public void signIn(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        App app = app(request);
        Instant began = clock.instant();
        long accountId = signIn.signIn(email, password, app, Forwarded.of(request).clientIp());
        sessions.start(request, response, accountId, app, began);
    }

    public void accept(String token, String password, HttpServletRequest request, HttpServletResponse response) {
        App app = app(request);
        Instant began = clock.instant();
        long accountId = invitations.accept(token, password, app);
        sessions.start(request, response, accountId, app, began);
    }

    /** Asks for a reset email for an account of the host's app (S1A-AC-05); the answer tells nothing. */
    public void requestPasswordReset(String email, HttpServletRequest request) {
        resets.request(email, app(request), Forwarded.of(request).clientIp());
    }

    /** Sets a password with a reset link's token (S1A-AC-09). Signs nobody in. */
    public void resetPassword(String token, String password, HttpServletRequest request) {
        resets.confirm(token, password, app(request));
    }

    /**
     * Changes the caller's password (S1A-AC-17). The change ends every session of the account, this one
     * included; the caller goes on in a new session with a new ID (S1A-AC-02).
     */
    public void changePassword(String currentPassword, String newPassword, HttpServletRequest request,
            HttpServletResponse response) {
        App app = app(request);
        CurrentAccount account = CurrentAccount.get();
        own.changePassword(account, currentPassword, newPassword);
        sessions.start(request, response, account.id(), app, clock.instant());
    }

    public void signOut(HttpServletRequest request) {
        sessions.end(request);
    }

    private static App app(HttpServletRequest request) {
        App app = HostRoutingFilter.app(request);
        if (app == null) {
            throw ApiException.notFound();
        }
        return app;
    }
}
