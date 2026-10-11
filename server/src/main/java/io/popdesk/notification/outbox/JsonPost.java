package io.popdesk.notification.outbox;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import javax.net.ssl.SSLException;

/**
 * One HTTP POST of a send loop, for the channels that post JSON to an address from the settings (the
 * webhook and the audit log's outside copy). Any 2xx answer is a success; a redirect is not followed,
 * so it is a failure and nothing goes to the address it names. What went wrong is given as a short
 * kind, never as the exception's message: that can hold the address.
 */
final class JsonPost {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** How long one request may take; set on each request. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private JsonPost() {}

    /** A client that never follows a redirect. Not one Spring Boot measures, so no address is a metric label. */
    static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    static Attempt send(HttpClient client, HttpRequest request) {
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 300 ? Attempt.sent() : Attempt.failed("HTTP_" + status);
        } catch (HttpConnectTimeoutException | ConnectException e) {
            return Attempt.failed("CONNECTION_FAILED");
        } catch (HttpTimeoutException e) {
            return Attempt.failed("TIMEOUT");
        } catch (SSLException e) {
            return Attempt.failed("TLS_FAILED");
        } catch (IOException e) {
            return Attempt.failed(e.getCause() instanceof SSLException ? "TLS_FAILED" : "IO_ERROR");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Attempt.failed("INTERRUPTED");
        }
    }
}
