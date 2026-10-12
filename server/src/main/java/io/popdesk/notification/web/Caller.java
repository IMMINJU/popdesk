package io.popdesk.notification.web;

import io.popdesk.ApiErrors;
import io.popdesk.ApiException;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The signed-in account of this request. Helpdesk signs people in and puts a principal whose name is
 * the account ID; the security filter has already answered 401 where a session is needed.
 */
public final class Caller {

    private Caller() {}

    public static long accountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Principal principal
                && principal.getName() != null && principal.getName().matches("[0-9]{1,18}")) {
            return Long.parseLong(principal.getName());
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, ApiErrors.UNAUTHENTICATED, ApiErrors.UNAUTHENTICATED_MESSAGE);
    }
}
