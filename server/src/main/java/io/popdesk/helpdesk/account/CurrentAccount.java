package io.popdesk.helpdesk.account;

import io.popdesk.ApiErrors;
import io.popdesk.ApiException;
import io.popdesk.helpdesk.Role;
import io.popdesk.helpdesk.Viewer;
import io.popdesk.helpdesk.audit.AuditLog;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The signed-in account of this request, as read from the database by {@link SessionFilter}.
 * Controllers call {@link #get()}; the security filter has already answered 401 for endpoints that
 * need a session.
 *
 * <p>As a {@link Principal} its name is the account ID, never the email: that is how modules without
 * this type (notification) learn who is calling.
 */
public record CurrentAccount(long id, String email, String name, Role role) implements Principal {

    /** The account ID as text; the person's name is {@link #name()}. */
    @Override
    public String getName() {
        return Long.toString(id);
    }

    /** The signed-in account; 401 when the request has none. */
    public static CurrentAccount get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentAccount account) {
            return account;
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, ApiErrors.UNAUTHENTICATED, ApiErrors.UNAUTHENTICATED_MESSAGE);
    }

    /** The scope for reading customer data. */
    public Viewer viewer() {
        return new Viewer(id, role);
    }

    /** The person of an audit row. */
    public AuditLog.Actor actor() {
        return new AuditLog.Actor(id, name, email);
    }
}
