package io.popdesk;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The error page for failures outside a controller (a filter, the servlet container). Replaces
 * Spring Boot's page, whose body names the path and the time: every error has the body of COM-3.
 */
@RestController
class ApiErrorController implements ErrorController {

    @RequestMapping("${server.error.path:/error}")
    ResponseEntity<ApiError> error(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        int status = code instanceof Integer i ? i : 500;
        ApiException e = switch (status) {
            case 400 -> new ApiException(HttpStatus.BAD_REQUEST, ApiErrors.INVALID_INPUT, ApiErrors.INVALID_INPUT_MESSAGE);
            case 401 -> new ApiException(HttpStatus.UNAUTHORIZED, ApiErrors.UNAUTHENTICATED,
                    ApiErrors.UNAUTHENTICATED_MESSAGE);
            case 403 -> ApiException.forbidden();
            case 404, 405 -> ApiException.notFound();
            case 413 -> new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, ApiErrors.PAYLOAD_TOO_LARGE,
                    ApiErrors.PAYLOAD_TOO_LARGE_MESSAGE);
            default -> new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrors.INTERNAL_ERROR,
                    ApiErrors.INTERNAL_ERROR_MESSAGE);
        };
        return ResponseEntity.status(e.status()).body(e.body());
    }
}
