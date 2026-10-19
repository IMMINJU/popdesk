package io.popdesk.helpdesk.account;

import io.popdesk.ApiException;
import io.popdesk.Forwarded;
import io.popdesk.HostRoutingFilter;
import io.popdesk.PublicAddress;
import io.popdesk.PublicAddress.App;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The web side of sign-in, accepting an invitation, sign-out, and passwords: the app comes from the
 * host, the client IP from {@link Forwarded}, and a successful check starts a new session (S0-AC-17).
 * A session's sign-in time is taken before the check, so a password that changes while the check runs
 * ends the session ({@link SessionFilter}).
 */
@Component
public class SignInFlow {

    /** The cookie that ties an OIDC sign-in to the browser on http (S11-AC-04). */
    static final String OIDC_COOKIE = "POPDESK_OIDC";

    /** The same on https: {@code __Host-} keeps it to this host, with {@code Secure} and {@code Path=/}. */
    static final String OIDC_COOKIE_HTTPS = "__Host-POPDESK_OIDC";

    private final SignInService signIn;
    private final InvitationService invitations;
    private final PasswordResetService resets;
    private final OwnAccountService own;
    private final OidcSignInService oidc;
    private final Sessions sessions;
    private final PublicAddress address;
    private final Clock clock;

    SignInFlow(SignInService signIn, InvitationService invitations, PasswordResetService resets, OwnAccountService own,
            OidcSignInService oidc, Sessions sessions, PublicAddress address, Clock clock) {
        this.signIn = signIn;
        this.invitations = invitations;
        this.resets = resets;
        this.own = own;
        this.oidc = oidc;
        this.sessions = sessions;
        this.address = address;
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

    /** Whether the sign-in page of the host's app offers OIDC sign-in (S11-AC-03). */
    public boolean oidcOffered(HttpServletRequest request) {
        return oidc.on(app(request));
    }

    /**
     * Starts an OIDC sign-in (S11-AC-04): 302 to the provider with the sign-in cookie, or back to the sign-in page
     * (S11-AC-07, S11-AC-08). 404 when OIDC is off for the host's app (S11-AC-06).
     */
    public void oidcStart(String next, HttpServletRequest request, HttpServletResponse response) {
        App app = app(request);
        OidcSignInService.Step step = oidc.start(app, Forwarded.of(request).clientIp(), next);
        if (step.browserValue() != null) {
            response.addHeader(HttpHeaders.SET_COOKIE,
                    oidcCookie(step.browserValue(), OidcSignInService.REQUEST_LIFETIME));
        }
        redirect(response, step.location());
    }

    /**
     * The provider sends the browser back (S11-AC-09 to S11-AC-13): the sign-in cookie goes, and a matched account
     * signs in in a new session, its sign-in time taken before the checks. 404 when OIDC is off for the host's app.
     */
    public void oidcCallback(String code, String state, String error, HttpServletRequest request,
            HttpServletResponse response) {
        App app = app(request);
        Instant began = clock.instant();
        OidcSignInService.Step step = oidc.callback(app, oidcCookieValue(request), code, state, error);
        response.addHeader(HttpHeaders.SET_COOKIE, oidcCookie("", Duration.ZERO));
        if (step.accountId() > 0) {
            sessions.start(request, response, step.accountId(), app, began);
        }
        redirect(response, step.location());
    }

    private String oidcCookieName() {
        return address.secure() ? OIDC_COOKIE_HTTPS : OIDC_COOKIE;
    }

    private String oidcCookie(String value, Duration maxAge) {
        return ResponseCookie.from(oidcCookieName(), value).httpOnly(true).secure(address.secure()).sameSite("Lax")
                .path("/").maxAge(maxAge).build().toString();
    }

    private String oidcCookieValue(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String name = oidcCookieName();
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /**
     * A 302 to {@code location} as it is: a path of the app stays relative, so no host from the request goes into
     * it. Characters outside printable ASCII are percent-encoded as UTF-8.
     */
    private static void redirect(HttpServletResponse response, String location) {
        StringBuilder out = new StringBuilder();
        for (byte b : location.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c > 0x20 && c < 0x7f) {
                out.append((char) c);
            } else {
                out.append('%').append(String.format("%02X", c));
            }
        }
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, out.toString());
    }

    private static App app(HttpServletRequest request) {
        App app = HostRoutingFilter.app(request);
        if (app == null) {
            throw ApiException.notFound();
        }
        return app;
    }
}
