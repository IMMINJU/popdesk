package io.popdesk;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown by controller shells: the endpoint is in the contract but not built yet (design/process.md
 * C-4). Tests written against the contract fail on it until the slice implements the endpoint.
 */
@ResponseStatus(HttpStatus.NOT_IMPLEMENTED)
public class NotImplementedException extends RuntimeException {

    public NotImplementedException() {
        super("Not implemented yet.");
    }
}
