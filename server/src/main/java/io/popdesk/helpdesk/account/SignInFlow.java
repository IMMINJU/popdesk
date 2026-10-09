package io.popdesk.helpdesk.account;

import io.popdesk.ApiException;
import io.popdesk.Forwarded;
import io.popdesk.HostRoutingFilter;
import io.popdesk.PublicAddress.App;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/**
 * The web side of sign-in, accepting an invitation, and sign-out: the app comes from the host, the
 * client IP from {@link Forwarded}, and a successful check starts a new session (S0-AC-17).
 */
@Component
public class SignInFlow {

    private final SignInService signIn;
    private final InvitationService invitations;
    private final Sessions sessions;

    SignInFlow(SignInService signIn, InvitationService invitations, Sessions sessions) {
        this.signIn = signIn;
        this.invitations = invitations;
        this.sessions = sessions;
    }

    public void signIn(String email, String password, HttpServletRequest request, HttpServletResponse response) {
        App app = app(request);
        long accountId = signIn.signIn(email, password, app, Forwarded.of(request).clientIp());
        sessions.start(request, response, accountId, app);
    }

    public void accept(String token, String password, HttpServletRequest request, HttpServletResponse response) {
        App app = app(request);
        long accountId = invitations.accept(token, password, app);
        sessions.start(request, response, accountId, app);
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
