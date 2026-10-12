package io.popdesk.deploy;

import static io.popdesk.deploy.IssueInstallSteps.SAMPLE_CUSTOMER;
import static io.popdesk.deploy.IssueInstallSteps.SAMPLE_PASSWORD;
import static io.popdesk.deploy.IssueInstallSteps.items;
import static io.popdesk.deploy.IssueInstallSteps.keptSample;
import static io.popdesk.deploy.IssueInstallSteps.keys;
import static io.popdesk.deploy.IssueInstallSteps.sampleAdmin;
import static io.popdesk.deploy.IssueInstallSteps.startedOn;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.deploy.AppSteps.Person;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Unique;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slice 4 on installations that were there before it (ADR D-24, S4-AC-72): the samples kept from
 * 0.1.0, 0.2.0, and 0.3.0 in {@code deploy/upgrade/samples/} are restored and the image under test is
 * started on them. The comments of a sample were never edited or deleted and mention nobody; the
 * items in its admin's inbox are about no issue; and what the slice adds works on the old data: an
 * agent's answer on a sample issue is an item in the sample customer's inbox.
 *
 * <p>The issues are the keys in the sample's {@code sample.expected.json}; the accounts and passwords
 * are the ones {@code deploy/upgrade/make-sample.py} and {@code popdesk_stack.py} give every sample.
 * Everything is read through the API; the database is only asked whether a migration failed and how
 * many inbox rows the admin has. Each sample is restored once and its stack is shared by the tests of
 * this class (three installations at the end).
 */
@Tag("deploy")
class TalkUpgradeTest {

    /** The agent of every sample, assigned to the company ACME ({@code make-sample.py}). */
    private static final String SAMPLE_AGENT = "agent@sample.popdesk.test";

    /** The inbox kinds there were before this slice: two operations alerts and the membership request. */
    private static final Set<String> KINDS_BEFORE = Set.of("DELIVERY_FAILED", "ATTACHMENT_QUOTA_WARNING",
            "MEMBERSHIP_REQUESTED");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Per sample version, the stack with the dump restored and the image under test started on it. */
    private static final Map<String, Once<Stack>> UPGRADED = Map.of(
            "0.1.0", new Once<Stack>("restoring the 0.1.0 sample and starting the image", () -> restored("0.1.0")),
            "0.2.0", new Once<Stack>("restoring the 0.2.0 sample and starting the image", () -> restored("0.2.0")),
            "0.3.0", new Once<Stack>("restoring the 0.3.0 sample and starting the image", () -> restored("0.3.0")));

    @AfterAll
    static void cleanUp() {
        for (Once<Stack> upgraded : UPGRADED.values()) {
            Stack stack = upgraded.peek();
            if (stack != null) {
                stack.close();
            }
        }
    }

    private static Stack restored(String version) {
        return startedOn("talk" + version.replace(".", ""), keptSample(version, "sample.dump"));
    }

    /** The keys of the issues that were in the sample (its expected file). */
    private static List<String> sampleKeys(String version) {
        Path file = keptSample(version, "sample.expected.json");
        List<String> keys = new ArrayList<>();
        try {
            JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)).path("issues")
                    .forEach(key -> keys.add(key.asString("")));
        } catch (IOException e) {
            throw new AssertionError("Could not read " + file + ": " + e, e);
        }
        assertThat(keys).as("issue keys in %s", file).isNotEmpty().doesNotContain("");
        return keys;
    }

    private static ApiClient signedIn(ApiClient client, String email) {
        client.post("/api/public/sign-in").json(Map.of("email", email, "password", SAMPLE_PASSWORD)).expect(204);
        return client;
    }

    /** Whether the field is there and null (D-44: a missing value is null, not left out). */
    private static boolean isNull(JsonNode node, String field) {
        return node.has(field) && node.path(field).isNull();
    }

    private static void assertNeverEditedOrDeleted(JsonNode comment, String where) {
        assertThat(isNull(comment, "editedAt")).as("editedAt is there and null in %s: %s", where, comment).isTrue();
        assertThat(comment.path("deleted").isBoolean() && !comment.path("deleted").asBoolean())
                .as("deleted is false in %s: %s", where, comment).isTrue();
        assertThat(isNull(comment, "deletedAt")).as("deletedAt is there and null in %s: %s", where, comment).isTrue();
    }

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"0.1.0", "0.2.0", "0.3.0"})
    @DisplayName("S4-AC-72 on a sample of an older version this image finishes migrating and starts, and every "
            + "comment that was there has editedAt null, deleted false, and deletedAt null on both apps, and on "
            + "the console no mentions and deletedBy null")
    void sampleCommentsWereNeverEditedDeletedOrMentioning(String version) {
        Stack stack = UPGRADED.get(version).get();
        Db db = stack.db();
        assertThat(db.count("select count(*) from " + Db.quote(db.schema())
                + ".flyway_schema_history where not success")).as("failed migrations").isZero();
        Person admin = sampleAdmin(stack);
        int onConsole = 0;

        for (String key : sampleKeys(version)) {
            for (JsonNode comment : items(admin.client().get("/api/console/issues/" + key + "/comments")
                    .query("size", 100).expect(200))) {
                String where = "the console's comments of " + key;
                assertNeverEditedOrDeleted(comment, where);
                assertThat(isNull(comment, "deletedBy")).as("deletedBy is there and null in %s: %s", where, comment)
                        .isTrue();
                assertThat(comment.path("mentions").isArray() && comment.path("mentions").isEmpty())
                        .as("mentions is an empty list in %s: %s", where, comment).isTrue();
                onConsole++;
            }
        }
        assertThat(onConsole).as("comments of the sample's issues on the console").isPositive();

        ApiClient portal = signedIn(stack.portal(), SAMPLE_CUSTOMER);
        int onPortal = 0;
        for (String key : keys(portal.get("/api/portal/issues").query("size", 100).expect(200))) {
            for (JsonNode comment : items(portal.get("/api/portal/issues/" + key + "/comments").query("size", 100)
                    .expect(200))) {
                assertNeverEditedOrDeleted(comment, "the portal's comments of " + key);
                onPortal++;
            }
        }
        assertThat(onPortal).as("comments %s sees on the portal", SAMPLE_CUSTOMER).isPositive();
    }

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"0.1.0", "0.2.0", "0.3.0"})
    @DisplayName("S4-AC-72 after the upgrade the sample admin's inbox is 200 with every item that was there, each "
            + "of a kind from before this slice with issue, commentId, and actor null, and the unread count "
            + "counts the unread ones")
    void sampleAdminInboxIsReadAsItWas(String version) {
        Stack stack = UPGRADED.get(version).get();
        Db db = stack.db();
        Person admin = sampleAdmin(stack);
        String rowsOfAdmin = "select count(*) from " + Db.quote(db.schema()) + ".notification where account_id = ?";

        // The app may add an operations alert of its own while this runs, so the rows are counted before and after.
        long rowsBefore = db.count(rowsOfAdmin, admin.accountId());
        long unreadBefore = db.count(rowsOfAdmin + " and read_at is null", admin.accountId());
        ApiResponse inbox = admin.client().get("/api/console/notifications").query("size", 100).expect(200);
        ApiResponse unread = admin.client().get("/api/console/notifications/unread-count").expect(200);
        long rowsAfter = db.count(rowsOfAdmin, admin.accountId());
        long unreadAfter = db.count(rowsOfAdmin + " and read_at is null", admin.accountId());

        assertThat(rowsBefore).as("inbox rows of the sample's admin in the database").isPositive();
        assertThat(rowsAfter).as("inbox rows of the sample's admin, which fit one page").isLessThanOrEqualTo(100);
        assertThat((long) items(inbox).size()).as("items of the admin's inbox: %s", inbox.snippet())
                .isBetween(rowsBefore, rowsAfter);
        assertThat(inbox.jsonLong("totalElements")).as("totalElements").isBetween(rowsBefore, rowsAfter);
        assertThat(unread.jsonLong("count")).as("unread count").isBetween(unreadBefore, unreadAfter);
        for (JsonNode item : items(inbox)) {
            assertThat(item.path("kind").asString(null)).as("kind of %s", item).isIn(KINDS_BEFORE);
            for (String field : List.of("issue", "commentId", "actor")) {
                assertThat(isNull(item, field)).as("%s is there and null in %s", field, item).isTrue();
            }
        }
    }

    @Test
    @DisplayName("S4-AC-72 the MEMBERSHIP_REQUESTED item of the 0.3.0 sample keeps its companyCode after the "
            + "upgrade and has issue, commentId, and actor null")
    void membershipRequestItemKeepsItsCompany() {
        Stack stack = UPGRADED.get("0.3.0").get();
        Person admin = sampleAdmin(stack);

        ApiResponse inbox = admin.client().get("/api/console/notifications").query("size", 100).expect(200);

        List<JsonNode> requests = items(inbox).stream()
                .filter(n -> "MEMBERSHIP_REQUESTED".equals(n.path("kind").asString(null))).toList();
        assertThat(requests).as("MEMBERSHIP_REQUESTED items in %s", inbox.snippet()).isNotEmpty();
        for (JsonNode item : requests) {
            // The sample's one request is the manager of ACME asking for other@sample.popdesk.test.
            assertThat(item.path("companyCode").asString(null)).as("companyCode of %s", item).isEqualTo("ACME");
            for (String field : List.of("issue", "commentId", "actor")) {
                assertThat(isNull(item, field)).as("%s is there and null in %s", field, item).isTrue();
            }
        }
    }

    @ParameterizedTest(name = "from {0}")
    @ValueSource(strings = {"0.1.0", "0.2.0", "0.3.0"})
    @DisplayName("S4-AC-72 after the upgrade the sample agent's answer on an issue of the sample customer is one "
            + "new unread STAFF_COMMENT item in that customer's inbox, from the support team")
    void answerOnASampleIssueReachesTheSampleCustomersInbox(String version) {
        Stack stack = UPGRADED.get(version).get();
        ApiClient portal = signedIn(stack.portal(), SAMPLE_CUSTOMER);
        ApiClient agent = signedIn(stack.console(), SAMPLE_AGENT);
        long customerId = portal.get("/api/portal/me").expect(200).jsonLong("id");
        // An issue the customer reported that takes comments: any of theirs that is not CLOSED.
        String key = keys(portal.get("/api/portal/issues").query("size", 100).expect(200)).stream().sorted()
                .filter(k -> {
                    JsonNode detail = portal.get("/api/portal/issues/" + k).expect(200).json();
                    return detail.path("reporter").path("accountId").asLong(-1) == customerId
                            && !"CLOSED".equals(detail.path("status").asString(null));
                }).findFirst()
                .orElseThrow(() -> new AssertionError("No open issue of " + SAMPLE_CUSTOMER + " in the sample"));
        long unreadBefore = portal.get("/api/portal/notifications/unread-count").expect(200).jsonLong("count");
        List<Long> before = items(portal.get("/api/portal/notifications").query("size", 100).expect(200)).stream()
                .map(n -> n.path("id").asLong(-1)).toList();

        long commentId = agent.post("/api/console/issues/" + key + "/comments")
                .json(Map.of("content", Unique.text("Answer after the upgrade"))).expect(201).jsonLong("id");

        List<JsonNode> added = items(portal.get("/api/portal/notifications").query("size", 100).expect(200)).stream()
                .filter(n -> !before.contains(n.path("id").asLong(-1))).toList();
        assertThat(added).as("items the answer on %s added to the inbox of %s", key, SAMPLE_CUSTOMER).hasSize(1);
        JsonNode item = added.getFirst();
        assertThat(item.path("kind").asString(null)).as("kind of %s", item).isEqualTo("STAFF_COMMENT");
        assertThat(item.path("issue").path("key").asString(null)).as("issue.key of %s", item).isEqualTo(key);
        assertThat(item.path("companyCode").asString(null)).as("companyCode of %s", item)
                .isEqualTo(key.substring(0, key.lastIndexOf('-')));
        assertThat(item.path("commentId").asLong(-1)).as("commentId of %s", item).isEqualTo(commentId);
        assertThat(item.path("actor").path("kind").asString(null)).as("actor.kind of %s", item)
                .isEqualTo("SUPPORT_TEAM");
        assertThat(isNull(item, "readAt")).as("readAt is there and null in %s", item).isTrue();
        assertThat(portal.get("/api/portal/notifications/unread-count").expect(200).jsonLong("count"))
                .as("unread count after the answer").isEqualTo(unreadBefore + 1);
    }
}
