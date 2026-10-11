package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.remove;
import static io.popdesk.manager.ManagerSteps.requests;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Steps of the membership request tests of slice 2, on top of {@link ManagerSteps}: making a request
 * (a manager invites the email of an existing account), finding it in the admin's list, and the
 * admin's approve and reject.
 */
final class RequestSteps {

    static final String REQUESTS = "/api/console/membership-requests";

    private RequestSteps() {}

    static String approve(long requestId) {
        return REQUESTS + "/" + requestId + "/approve";
    }

    static String reject(long requestId) {
        return REQUESTS + "/" + requestId + "/reject";
    }

    /** Approves, whatever the answer. */
    static ApiResponse approve(Session admin, long requestId) {
        return admin.client().post(approve(requestId)).send();
    }

    /** Rejects, whatever the answer. */
    static ApiResponse reject(Session admin, long requestId) {
        return admin.client().post(reject(requestId)).send();
    }

    /**
     * Has the manager invite the email of an existing account and returns the ID of the pending
     * request the admin then lists for that company and email.
     */
    static long request(Session admin, Session manager, String code, String email, String typedName) {
        invited(manager, code, email, typedName);
        return pendingId(admin, code, email);
    }

    /** The ID of the one pending request of the company with this email. */
    static long pendingId(Session admin, String code, String email) {
        List<JsonNode> found = requests(admin, code).stream()
                .filter(r -> email.equalsIgnoreCase(r.path("email").asString(null)))
                .toList();
        assertThat(found).as("pending membership requests of %s for %s", code, email).hasSize(1);
        JsonNode id = found.getFirst().path("id");
        assertThat(id.isIntegralNumber()).as("id of the request %s is a number", found.getFirst()).isTrue();
        return id.asLong();
    }

    /** The emails of the company's pending requests, in the order the admin's list gives them. */
    static List<String> pendingEmails(Session admin, String code) {
        return requests(admin, code).stream().map(r -> r.path("email").asString(null)).toList();
    }

    /** The manager takes the row with this email out of the member list; a request row is withdrawn. */
    static void withdraw(Session manager, String code, String email) {
        remove(manager, code, email).expectStatus(204);
    }
}
