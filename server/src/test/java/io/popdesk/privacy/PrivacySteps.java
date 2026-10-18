package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.AuditChainMath;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.WebhookReceiver;
import io.popdesk.support.WebhookReceiver.Received;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * Steps of the slice 10a server tests: anonymizing, reading an account back, the audit entries of one person, the
 * whole chain, and the outside copies of {@link PrivacyAppTest}. The audit steps of slice 1b are private to their
 * package ({@code audit.AuditSteps}, {@code audit.ChainSteps}); these read the same API.
 *
 * <p>Every failure is an {@link AssertionError}.
 */
public final class PrivacySteps {

    public static final String ACCOUNTS = "/api/console/accounts";
    public static final String AUDIT_LOG = "/api/console/audit-log";

    private static final int MAX_PAGES = 1000;

    private PrivacySteps() {}

    /** The anonymous email of an account: {@code deleted-{id}@invalid}. */
    public static String anonymousEmail(long accountId) {
        return "deleted-" + accountId + "@invalid";
    }

    // Anonymizing

    /** {@code POST /api/console/accounts/{id}/anonymize}, not sent yet. */
    public static ApiRequest anonymizeRequest(Session admin, long accountId) {
        return admin.client().post(ACCOUNTS + "/" + accountId + "/anonymize");
    }

    /** Anonymizes without clearing assignments and expects 200; returns the answer. */
    public static JsonNode anonymize(Session admin, long accountId) {
        return anonymizeRequest(admin, accountId).expect(200).json();
    }

    /** Anonymizes with {@code clearAssignments} and expects 200; returns the answer. */
    public static JsonNode anonymize(Session admin, long accountId, boolean clearAssignments) {
        return anonymizeRequest(admin, accountId).query("clearAssignments", clearAssignments).expect(200).json();
    }

    // Accounts

    /** Every account of the list narrowed by {@code query} (name, value, name, value, ...). */
    public static List<JsonNode> accounts(Session admin, Object... query) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode page : pages(admin, ACCOUNTS, query)) {
            page.path("items").forEach(out::add);
        }
        return out;
    }

    /** How many active admins there are now. */
    public static long activeAdmins(Session admin) {
        JsonNode page = admin.client().get(ACCOUNTS).query("role", "ADMIN").query("status", "ACTIVE")
                .query("size", 1).expect(200).json();
        JsonNode total = page.path("totalElements");
        if (!total.isIntegralNumber()) {
            throw new AssertionError("No totalElements in the account list " + page);
        }
        return total.asLong();
    }

    /** The account's row of the account list, found by a text its name or email holds (the list has no GET by ID). */
    public static JsonNode account(Session admin, long accountId, String searchText) {
        List<JsonNode> found = accounts(admin, "q", searchText).stream()
                .filter(a -> a.path("id").asLong(-1) == accountId).toList();
        assertThat(found).as("accounts with ID %d found by '%s'", accountId, searchText).hasSize(1);
        return found.getFirst();
    }

    /** The anonymized account's row, found by its anonymous email. */
    public static JsonNode anonymizedAccount(Session admin, long accountId) {
        return account(admin, accountId, anonymousEmail(accountId));
    }

    // Audit log

    /** Every entry the query finds, newest first. */
    public static List<JsonNode> entries(Session admin, Object... query) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode page : pages(admin, AUDIT_LOG, query)) {
            page.path("items").forEach(out::add);
        }
        return out;
    }

    /**
     * The entries of a person (acceptance.md "그 사람의 감사 항목"): {@code actor.accountId} or {@code target.accountId}
     * is the account, each once, by {@code seq}.
     */
    public static List<JsonNode> entriesOf(Session admin, long accountId) {
        Map<Long, JsonNode> bySeq = new TreeMap<>();
        for (JsonNode entry : entries(admin, "actorAccountId", accountId)) {
            bySeq.put(AuditChainMath.number(entry, "seq"), entry);
        }
        for (JsonNode entry : entries(admin, "targetAccountId", accountId)) {
            if (entry.path("target").path("accountId").asLong(-1) == accountId) {
                bySeq.put(AuditChainMath.number(entry, "seq"), entry);
            }
        }
        return List.copyOf(bySeq.values());
    }

    /** The whole chain as it is now: the lines of a JSON Lines export without a filter, in chain order. */
    public static List<JsonNode> chain(Session admin) {
        ApiResponse export = admin.client().get(AUDIT_LOG + "/export").query("format", "JSONL").expect(200);
        List<JsonNode> out = new ArrayList<>();
        for (String line : export.text().split("\n")) {
            if (!line.isBlank()) {
                out.add(AuditChainMath.parse(line));
            }
        }
        return out;
    }

    /** The integrity check says that all entries match. */
    public static void assertChainValid(Session admin) {
        AuditChainMath.assertValid(admin.client());
    }

    // Outside copies

    /** The bodies of the outside copies the receiver got, in the order it got them. */
    public static List<JsonNode> copies(WebhookReceiver receiver) {
        List<JsonNode> out = new ArrayList<>();
        for (Received request : receiver.requests(r -> r.path().equals(PrivacyAppTest.COPY_PATH))) {
            out.add(request.json());
        }
        return out;
    }

    // Pages

    private static List<JsonNode> pages(Session admin, String path, Object... query) {
        if (query.length % 2 != 0) {
            throw new IllegalArgumentException("Query values come in pairs: name, value");
        }
        Map<String, Object> pairs = new LinkedHashMap<>();
        for (int i = 0; i < query.length; i += 2) {
            pairs.put((String) query[i], query[i + 1]);
        }
        List<JsonNode> out = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            ApiRequest request = admin.client().get(path).query("page", page).query("size", 100);
            pairs.forEach(request::query);
            ApiResponse response = request.expect(200);
            if (!response.json().path("items").isArray()) {
                throw new AssertionError(response.request() + ": no items array in " + response.snippet());
            }
            out.add(response.json());
            if (page >= response.json().path("totalPages").asInt(0)) {
                return out;
            }
        }
        throw new AssertionError(path + " narrowed by this query has more than " + MAX_PAGES + " pages");
    }
}
