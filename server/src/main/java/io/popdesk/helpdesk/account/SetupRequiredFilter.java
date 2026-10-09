package io.popdesk.helpdesk.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * While there is no first admin, every API request gets 503 {@code SETUP_REQUIRED} (conventions,
 * S0-AC-02). The log says what to set.
 */
@Component
class SetupRequiredFilter extends OncePerRequestFilter {

    static final String BODY = "{\"code\":\"SETUP_REQUIRED\",\"message\":\"popdesk is not set up yet: the first admin "
            + "could not be created. The server log says what to set.\"}";

    // Looked up on the first request: the filter is made while the web server is built, and taking the
    // setup then would run the migrations inside that step.
    private final ObjectProvider<FirstAdminSetup> setup;

    SetupRequiredFilter(ObjectProvider<FirstAdminSetup> setup) {
        this.setup = setup;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!setup.getObject().setupRequired()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(BODY);
    }
}
