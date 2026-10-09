package io.popdesk.helpdesk.attachment;

import io.popdesk.ApiError;
import io.popdesk.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * At most 16 uploads and, counted apart, 16 downloads at once (S0-AC-99, S0 premortem 1); the next
 * one is 503 {@code SERVER_BUSY} with {@code Retry-After}.
 *
 * <p>A servlet filter after Spring Security (order 0, the security chain is -100): a request that is
 * not signed in or not allowed is refused before it takes a place, and the 503 carries the security
 * headers. It runs before Spring MVC reads the multipart body, so an upload holds its place while its
 * body arrives, and a download holds it until the response is written. Nothing here holds a database
 * connection. The per-account rate limit is counted apart, in the service (S0-AC-65).
 */
@Component
class TransferLimitFilter extends OncePerRequestFilter implements Ordered {

    static final int RUNNING = 16;

    /** Seconds a refused client waits before it tries again. */
    static final String RETRY_AFTER = "1";

    private static final Pattern UPLOAD = Pattern.compile("^/api/(portal|console)/issues/[^/]+/attachments/?$");
    private static final Pattern DOWNLOAD = Pattern.compile("^/api/(portal|console)/issues/[^/]+/attachments/[^/]+$");
    private static final String FILING = "/api/portal/issues";

    private final Semaphore uploads = new Semaphore(RUNNING);
    private final Semaphore downloads = new Semaphore(RUNNING);

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Semaphore places = places(request);
        if (places == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!places.tryAcquire()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER);
            ApiErrors.write(response, 503, new ApiError(ApiErrors.SERVER_BUSY, ApiErrors.SERVER_BUSY_MESSAGE, null));
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            places.release();
        }
    }

    /** The places this request takes, or null when it is neither an upload nor a download. */
    private Semaphore places(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String method = request.getMethod();
        if ("POST".equals(method) && (UPLOAD.matcher(path).matches() || FILING.equals(path) && multipart(request))) {
            return uploads;
        }
        if ("GET".equals(method) && DOWNLOAD.matcher(path).matches()) {
            return downloads;
        }
        return null;
    }

    private static boolean multipart(HttpServletRequest request) {
        String type = request.getContentType();
        return type != null && type.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }
}
