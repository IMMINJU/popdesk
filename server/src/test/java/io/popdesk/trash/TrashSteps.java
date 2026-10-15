package io.popdesk.trash;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * Steps of slice 7a for the trash, through the console API only: delete an issue, read the trash, restore an issue,
 * and purge one. All four are for admins only. Every step expects its one status and fails with an
 * {@link AssertionError} otherwise, so before the slice is built a test fails at the step. A method named
 * {@code ...Request} gives the same request without sending it, for a test that reads another status.
 *
 * <p>Public, with public methods, so that other packages (the hidden E2E checks among them) can use it.
 */
public final class TrashSteps {

    /** The trash list. */
    public static final String TRASH = "/api/console/trash";

    /** Days an issue stays in the trash before popdesk purges it (S7A-AC-31). */
    public static final int PURGE_AFTER_DAYS = 30;

    /** The history field of the trash steps (S7A-AC-06) and its value. */
    public static final String FIELD = "TRASH";
    public static final String TRASHED = "TRASHED";
    public static final String PURGED = "PURGED";

    /** Pages the trash list is read over at most, 100 entries each. */
    private static final int MAX_PAGES = 50;

    private TrashSteps() {}

    // Paths

    public static String issuePath(String key) {
        return "/api/console/issues/" + key;
    }

    public static String trashPath(String key) {
        return TRASH + "/" + key;
    }

    public static String restorePath(String key) {
        return trashPath(key) + "/restore";
    }

    // Deleting

    /** {@code DELETE /api/console/issues/{key}}, not sent yet. */
    public static ApiRequest deleteRequest(Session session, String key) {
        return session.client().delete(issuePath(key));
    }

    /** The admin moves the issue to the trash; expects 204. */
    public static void delete(Session admin, String key) {
        deleteRequest(admin, key).expect(204);
    }

    // The trash

    /** {@code GET /api/console/trash} with {@code query} as name and value pairs, not sent yet. */
    public static ApiRequest listRequest(Session session, Object... query) {
        ApiRequest request = session.client().get(TRASH);
        for (int i = 0; i < query.length; i += 2) {
            request.query((String) query[i], query[i + 1]);
        }
        return request;
    }

    /** One page of the trash; expects 200. */
    public static ApiResponse list(Session admin, int page, int size) {
        return listRequest(admin, "page", page, "size", size).expect(200);
    }

    /** Every entry of the trash, newest delete first, over all pages of 100. */
    public static List<JsonNode> entries(Session admin) {
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            ApiResponse response = list(admin, page, 100);
            JsonNode items = response.json().path("items");
            if (!items.isArray()) {
                throw new AssertionError("No items array in " + response.snippet());
            }
            out.addAll(items.values());
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError("The trash has more than " + MAX_PAGES + " pages of 100");
    }

    /** The trash entry of the issue, or null when the trash does not list it. */
    public static JsonNode entry(Session admin, String key) {
        return entries(admin).stream().filter(e -> key.equals(e.path("key").asString(null))).findFirst()
                .orElse(null);
    }

    /** The keys of the trash, in the order of the list. */
    public static List<String> keys(Session admin) {
        return entries(admin).stream().map(e -> e.path("key").asString(null)).toList();
    }

    // Restoring

    /** {@code POST /api/console/trash/{key}/restore}, not sent yet. */
    public static ApiRequest restoreRequest(Session session, String key) {
        return session.client().post(restorePath(key));
    }

    /** The admin restores the issue; expects 200 and gives the console issue. */
    public static JsonNode restore(Session admin, String key) {
        return restoreRequest(admin, key).expect(200).json();
    }

    // Purging

    /** {@code DELETE /api/console/trash/{key}}, not sent yet. */
    public static ApiRequest purgeRequest(Session session, String key) {
        return session.client().delete(trashPath(key));
    }

    /** The admin purges the issue from the trash at once; expects 204. */
    public static void purge(Session admin, String key) {
        purgeRequest(admin, key).expect(204);
    }

    // Answers

    /**
     * Fails unless {@code answer} is the 404 {@code NOT_FOUND} that {@code missing}, the same request for a key nobody
     * has, is: the same status and the same body. A {@code HEAD} answer has no body; only its status is compared.
     */
    public static void assertLikeMissing(ApiResponse answer, ApiResponse missing) {
        if (missing.status() != 404) {
            throw new AssertionError("The request for a key nobody has is not 404: " + missing.request() + " -> "
                    + missing.status() + " " + missing.snippet());
        }
        if (answer.status() != 404) {
            throw new AssertionError("Expected 404 like a key nobody has: " + answer.request() + " -> "
                    + answer.status() + " " + answer.snippet());
        }
        if (answer.request().startsWith("HEAD ")) {
            return;
        }
        if (!"NOT_FOUND".equals(answer.errorCode())) {
            throw new AssertionError("Expected code NOT_FOUND: " + answer.request() + " -> " + answer.snippet());
        }
        if (!answer.text().equals(missing.text())) {
            throw new AssertionError("The body is not the one of a key nobody has: " + answer.request() + " -> "
                    + answer.snippet() + " / " + missing.snippet());
        }
    }
}
