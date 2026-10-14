package io.popdesk;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ends the connection of a response that failed after part of it went out (S5B-AC-32): a download
 * whose storage failed half way. No error body can follow the part, and a response that just ends
 * leaves the client waiting for the rest or taking the part for the whole file.
 *
 * <p>{@link ApiExceptionHandler} logs the failure by its class and marks the request. After the
 * request this filter throws {@link BrokenResponse}, and the container closes the connection of a
 * response that is committed. The original exception never reaches the container, which would log
 * its message and stack trace; what it logs is the one fixed exception, which has neither.
 */
@Component
class BrokenResponseFilter extends OncePerRequestFilter {

    private static final String BROKEN = BrokenResponseFilter.class.getName() + ".broken";

    /** Thrown to end a connection. It says nothing: no message, no cause, no stack trace. */
    static final class BrokenResponse extends RuntimeException {

        BrokenResponse() {
            super(null, null, false, false);
        }
    }

    /** Marks a request whose response broke off after it was committed. */
    static void mark(HttpServletRequest request) {
        request.setAttribute(BROKEN, Boolean.TRUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        if (request.getAttribute(BROKEN) != null && response.isCommitted()) {
            throw new BrokenResponse();
        }
    }
}
