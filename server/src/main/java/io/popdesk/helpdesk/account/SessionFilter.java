package io.popdesk.helpdesk.account;

import io.popdesk.HostRoutingFilter;
import io.popdesk.PublicAddress.App;
import io.popdesk.helpdesk.AccountStatus;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.account.AccountRepository.AccountRow;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads the session of each request and, when it holds a sign-in that still counts, makes the account
 * the request's principal with its role read from the database now (S0-AC-26). A sign-in does not
 * count when it is past its 8 hours (S0-AC-25), was made on the other app's host, its account is no
 * longer {@code ACTIVE}, or the account's password changed after it (S1A-AC-91): ending the account's
 * sessions cannot catch a sign-in with the old password that was still running, because its session is
 * stored only when it ends. The request then goes on without a principal and gets 401 where one is
 * needed.
 *
 * <p>Not a bean: the security filter chain runs it before the anonymous filter.
 */
class SessionFilter extends OncePerRequestFilter {

    private final Sessions sessions;
    private final AccountRepository accounts;

    SessionFilter(Sessions sessions, AccountRepository accounts) {
        this.sessions = sessions;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        App app = HostRoutingFilter.app(request);
        HttpSession session = app == null ? null : request.getSession(false);
        if (session != null && session.getAttribute(SignedIn.ATTRIBUTE) instanceof SignedIn signedIn) {
            if (!sessions.current(signedIn)) {
                session.invalidate();
            } else if (signedIn.app().equals(app.name())) {
                account(signedIn, app).ifPresent(account -> {
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + account.role().name()))));
                    SecurityContextHolder.setContext(context);
                });
            }
        }
        chain.doFilter(request, response);
    }

    private Optional<CurrentAccount> account(SignedIn signedIn, App app) {
        return accounts.findById(signedIn.accountId())
                .filter(a -> a.status() == AccountStatus.ACTIVE)
                .filter(a -> appOf(a.role()) == app)
                .filter(a -> a.passwordChangedAt() == null
                        || !signedIn.signedInAt().isBefore(a.passwordChangedAt()))
                .map(SessionFilter::current);
    }

    static App appOf(Role role) {
        return role == Role.CUSTOMER ? App.PORTAL : App.CONSOLE;
    }

    private static CurrentAccount current(AccountRow a) {
        return new CurrentAccount(a.id(), a.email(), a.name(), a.role());
    }
}
