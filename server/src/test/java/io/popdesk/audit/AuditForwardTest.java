package io.popdesk.audit;

import static io.popdesk.audit.AuditSteps.canonical;
import static io.popdesk.audit.AuditSteps.number;
import static io.popdesk.audit.AuditSteps.onlyEntry;
import static io.popdesk.audit.AuditSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import io.popdesk.support.WebhookReceiver;
import io.popdesk.support.WebhookReceiver.Received;
import io.popdesk.support.WebhookReceiver.Reply;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The outside copy of the audit log (AUDIT-9): with {@code AUDIT_FORWARD_URL} every audit entry is
 * posted to that address, retried by the rules of the send queue (D-39), and shown in the system
 * status as a third channel.
 *
 * <p>A context of its own, the only one with the copy on: setup "bare" plus {@code AUDIT_FORWARD_URL},
 * pointing at {@link WebhookReceiver} on a path of its own, and {@code AUDIT_FORWARD_TOKEN}. Every
 * audit entry of this context is posted there (each sign-in too), so a test finds its own copies by
 * the company it created: the {@code COMPANY_CREATED} entry whose target holds that code. A test
 * that makes sending fail fails only those copies, so the other rows of the queue go out.
 *
 * <p>The tests move the clock by days and leave {@code failed} rows behind. A test that counts failed
 * rows or alerts first moves eight days on and waits until none is left (a failed row is deleted 7
 * days after it failed).
 *
 * <p>The body of a copy is read with the field names of an entry of the log and of an export line:
 * {@code id}, {@code seq}, {@code prevHash}, {@code hash}, {@code action}, {@code actor},
 * {@code target}, {@code changes}, {@code occurredAt}.
 */
@TestPropertySource(properties = "AUDIT_FORWARD_TOKEN=" + AuditForwardTest.TOKEN)
class AuditForwardTest extends BareAppTest {

    static final String TOKEN = "copy-Token-4c9e17a2b8d35f60";

    private static final String COPY_PATH = "/audit-copy";

    private static final String MOVED_PATH = "/audit-copy-moved";

    private static final String STATUS = "/api/console/system/status";

    private static final String FAILED = "/api/console/system/failed-deliveries";

    private static final String INBOX = "/api/console/notifications";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    Environment environment;

    @DynamicPropertySource
    static void outsideCopy(DynamicPropertyRegistry registry) {
        registry.add("AUDIT_FORWARD_URL", AuditForwardTest::copyUrl);
    }

    private static String copyUrl() {
        return "http://127.0.0.1:" + WebhookReceiver.shared().port() + COPY_PATH;
    }

    @BeforeEach
    @AfterEach
    void receiverAnswersAgain() {
        webhook().reset();
    }

    // The receiver

    /** Whether the request is the copy of the {@code COMPANY_CREATED} entry of the company {@code code}. */
    private static boolean isCopyOf(Received request, String code) {
        if (!request.path().equals(COPY_PATH)) {
            return false;
        }
        JsonNode body;
        try {
            body = JSON.readTree(request.body());
        } catch (JacksonException notJson) {
            return false;
        }
        return "COMPANY_CREATED".equals(body.path("action").asString(null))
                && code.equals(body.path("target").path("code").asString(null));
    }

    private List<Received> copiesOf(String code) {
        return webhook().requests(r -> isCopyOf(r, code));
    }

    /** Waits until the receiver got at least {@code n} copies of the company's entry. */
    private List<Received> awaitCopies(String code, int n) {
        return Wait.until(n + " copy request(s) of the COMPANY_CREATED entry of " + code, () -> {
            List<Received> found = copiesOf(code);
            return found.size() >= n ? found : null;
        });
    }

    /** From now on the receiver gives {@code reply} to the copies of this company's entry and 204 to the rest. */
    private void answerCopiesOf(String code, Reply reply) {
        webhook().respond(r -> isCopyOf(r, code) ? reply : Reply.status(204));
    }

    /** Creates a company with a code chosen before, so that the receiver can be told about it first. */
    private Company createCompany(Session admin, String code) {
        return fixtures.createCompany(admin, code, "Company " + code, "Company " + code + " Ltd.");
    }

    /** Creates a company while the receiver answers 500 to its copy; returns the entry ID of the first attempt. */
    private long entryWhoseCopyFails(Session admin, String code) {
        answerCopiesOf(code, Reply.status(500));
        createCompany(admin, code);
        return number(awaitCopies(code, 1).getFirst().json(), "id");
    }

    // The system status

    private static JsonNode copyStatus(Session admin) {
        JsonNode channel = admin.client().get(STATUS).expect(200).json().path("auditForward");
        if (!channel.isObject()) {
            throw new AssertionError("No auditForward object in the system status");
        }
        return channel;
    }

    private static ApiResponse failedDeliveries(Session admin) {
        return admin.client().get(FAILED).query("size", 100).expect(200);
    }

    /** The failed row of the copy of this entry in a failed-deliveries page, or null. */
    private static JsonNode failedCopy(ApiResponse failedDeliveries, long entryId) {
        for (JsonNode row : failedDeliveries.json().path("items")) {
            if ("AUDIT_FORWARD".equals(row.path("channel").asString(null))
                    && row.path("auditEntryId").isIntegralNumber() && row.path("auditEntryId").asLong() == entryId) {
                return row;
            }
        }
        return null;
    }

    private static ApiResponse awaitFailedCopy(Session admin, long entryId) {
        return Wait.until("the copy of audit entry " + entryId + " listed as failed", () -> {
            ApiResponse page = failedDeliveries(admin);
            return failedCopy(page, entryId) != null ? page : null;
        });
    }

    /** Moves past the deletion of every failed row; all sessions end. Returns the first admin signed in again. */
    private Session startWithNoFailedCopy() {
        clock.advance(Duration.ofDays(8));
        Session admin = fixtures.signInAdmin();
        Wait.untilAsserted("no failed copy left from earlier tests", Duration.ofSeconds(60),
                () -> assertThat(copyStatus(admin).path("failed").asLong(-1)).as("auditForward.failed").isZero());
        return admin;
    }

    private static List<String> inboxKinds(Session admin) {
        List<String> kinds = new ArrayList<>();
        for (JsonNode item : admin.client().get(INBOX).query("size", 100).expect(200).json().path("items")) {
            kinds.add(item.path("kind").asString(""));
        }
        return kinds;
    }

    // S1B-AC-29

    @Test
    @DisplayName("S1B-AC-29 an audit entry is posted once as JSON, with its ID, seq, hash, prevHash, and the "
            + "fields the hash is made of, each as the log gives it")
    void entryIsPostedAsJson() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        Received copy = awaitCopies(company.code(), 1).getFirst();
        Wait.holds("one copy of the entry", Duration.ofSeconds(2), () -> copiesOf(company.code()).size() == 1);

        assertThat(copy.method()).as("method").isEqualTo("POST");
        assertThat(copy.header("Content-Type")).as("Content-Type").isNotNull();
        assertThat(copy.header("Content-Type").toLowerCase(Locale.ROOT).replace(" ", "")).as("Content-Type")
                .matches("application/json(;charset=utf-8)?");
        JsonNode body = copy.json();
        JsonNode entry = onlyEntry(admin, "targetCode", company.code(), "action", "COMPANY_CREATED");
        for (String field : List.of("id", "seq")) {
            assertThat(number(body, field)).as(field).isEqualTo(number(entry, field));
        }
        for (String field : List.of("hash", "prevHash", "action")) {
            assertThat(text(body, field)).as(field).isEqualTo(text(entry, field));
        }
        for (String field : List.of("actor", "target", "changes")) {
            assertThat(canonical(body.path(field))).as(field).isEqualTo(canonical(entry.path(field)));
        }
        assertThat(AuditSteps.time(body)).as("occurredAt").isEqualTo(AuditSteps.time(entry));
        assertThat(text(body, "hash")).as("hash computed again from the body, %s", AuditSteps.chainJson(body))
                .matches(AuditSteps.HASH_PATTERN)
                .isEqualTo(AuditSteps.hash(text(body, "prevHash"), body));
    }

    // S1B-AC-30, S1B-AC-34

    @Test
    @DisplayName("S1B-AC-30, S1B-AC-34 a copy the receiver answered with 500 is sent again when its wait is over, "
            + "with the same entry ID, and not again after it worked")
    void failedCopyIsSentAgain() {
        Session admin = fixtures.signInAdmin();
        String code = Unique.companyCode();
        long entryId = entryWhoseCopyFails(admin, code);
        Wait.holds("no second attempt while the clock stands still", Duration.ofSeconds(2),
                () -> copiesOf(code).size() == 1);

        webhook().respondWith(204);
        clock.advance(Duration.ofSeconds(1));
        List<Received> attempts = awaitCopies(code, 2);

        assertThat(number(attempts.get(1).json(), "id")).as("entry ID of the second attempt").isEqualTo(entryId);
        clock.advance(Duration.ofMinutes(10));
        Wait.holds("no attempt after the one that worked", Duration.ofSeconds(3), () -> copiesOf(code).size() == 2);
    }

    @Test
    @DisplayName("S1B-AC-30 a copy that still fails 24 hours after its first failure is left failed, raises "
            + "DELIVERY_FAILED for an admin, and is not sent again")
    void copyFailingForADayIsGivenUpWithAnAlert() {
        Session admin = startWithNoFailedCopy();
        Session second = fixtures.admin(admin);
        String code = Unique.companyCode();
        long entryId = entryWhoseCopyFails(admin, code);

        clock.advance(Duration.ofHours(25));
        Session again = fixtures.signInAgain(second);

        awaitFailedCopy(again, entryId);
        Wait.untilAsserted("DELIVERY_FAILED in the inbox of " + again.email(),
                () -> assertThat(inboxKinds(again)).contains("DELIVERY_FAILED"));
        int sends = copiesOf(code).size();
        webhook().respondWith(204);
        clock.advance(Duration.ofHours(1));
        Wait.holds("no attempt after the copy was given up", Duration.ofSeconds(3),
                () -> copiesOf(code).size() == sends);
    }

    @Test
    @DisplayName("S1B-AC-30 a 302 from the receiver is a failure: the copy is tried again at the same address, "
            + "and nothing is sent to the address the redirect names")
    void redirectIsNotFollowed() {
        Session admin = fixtures.signInAdmin();
        String code = Unique.companyCode();
        String moved = "http://127.0.0.1:" + webhook().port() + MOVED_PATH;
        answerCopiesOf(code, new Reply(302, Map.of("Location", moved), ""));
        createCompany(admin, code);
        awaitCopies(code, 1);

        clock.advance(Duration.ofSeconds(1));

        awaitCopies(code, 2);
        assertThat(webhook().requests(r -> r.path().startsWith(MOVED_PATH)))
                .as("requests to the address of the redirect").isEmpty();
    }

    // S1B-AC-32

    @Test
    @DisplayName("S1B-AC-32 with AUDIT_FORWARD_TOKEN the copy carries Authorization: Bearer and the token")
    void tokenIsSentAsBearer() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);

        Received copy = awaitCopies(company.code(), 1).getFirst();

        assertThat(copy.header("Authorization")).as("Authorization").isEqualTo("Bearer " + TOKEN);
    }

    @Test
    @DisplayName("S1B-AC-32 after a copy failed for good, the log, the system status, the failed list, and the "
            + "metrics hold neither the address nor the token")
    void addressAndTokenAreNotShown() {
        Session admin = fixtures.signInAdmin();
        long entryId = entryWhoseCopyFails(admin, Unique.companyCode());
        clock.advance(Duration.ofHours(25));
        Session again = fixtures.signInAdmin();

        Map<String, String> places = new LinkedHashMap<>();
        places.put("the failed list", awaitFailedCopy(again, entryId).text());
        places.put("the system status", again.client().get(STATUS).expect(200).text());
        places.put("the metrics", metrics());
        places.put("the log", log.all());

        String hostAndPort = "127.0.0.1:" + webhook().port();
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("the token", TOKEN);
        secrets.put("the address", copyUrl());
        secrets.put("the address, percent-encoded", URLEncoder.encode(copyUrl(), StandardCharsets.UTF_8));
        secrets.put("the address, JSON-escaped", copyUrl().replace("/", "\\/"));
        secrets.put("the host and port of the address", hostAndPort);
        secrets.put("the host and port, percent-encoded", URLEncoder.encode(hostAndPort, StandardCharsets.UTF_8));
        secrets.put("the path of the address", COPY_PATH);
        places.forEach((place, content) -> secrets.forEach((what, secret) ->
                assertThat(content.contains(secret)).as("%s holds %s", place, what).isFalse()));
    }

    /** {@code GET /actuator/prometheus} on the management port, as text. */
    private String metrics() {
        String port = environment.getProperty("local.management.port");
        if (port == null) {
            throw new AssertionError("The app has no management port (local.management.port is not set)");
        }
        return new ApiClient("localhost", Integer.parseInt(port)).get("/actuator/prometheus")
                .header("Accept", "text/plain").send().expectStatus(200).text();
    }

    // S1B-AC-33

    @Test
    @DisplayName("S1B-AC-33 with AUDIT_FORWARD_URL the status shows the copy on, and lastSuccessAt is the time a "
            + "copy was delivered")
    void statusShowsTheCopyOnWithItsLastSuccess() {
        Session admin = fixtures.signInAdmin();
        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        assertThat(copyStatus(admin).path("enabled").asBoolean(false)).as("auditForward.enabled").isTrue();

        Company company = fixtures.createCompany(admin);
        awaitCopies(company.code(), 1);

        Wait.untilAsserted("auditForward.lastSuccessAt", () -> assertThat(AuditSteps.instant(
                text(copyStatus(admin), "lastSuccessAt"))).as("auditForward.lastSuccessAt").isEqualTo(at));
    }

    @Test
    @DisplayName("S1B-AC-33 a failing copy is counted as queued; given up, it is counted as failed and listed "
            + "with channel AUDIT_FORWARD and the ID of its audit entry")
    void failingCopyIsQueuedThenListedAsFailed() {
        Session admin = startWithNoFailedCopy();
        long entryId = entryWhoseCopyFails(admin, Unique.companyCode());
        Wait.untilAsserted("auditForward.queued", () -> assertThat(copyStatus(admin).path("queued").asLong(-1))
                .as("auditForward.queued").isGreaterThanOrEqualTo(1));

        clock.advance(Duration.ofHours(25));
        Session again = fixtures.signInAdmin();

        JsonNode row = failedCopy(awaitFailedCopy(again, entryId), entryId);
        assertThat(row.path("lastErrorKind").asString("")).as("lastErrorKind of %s", row).isNotBlank();
        assertThat(row.has("recipientAccountId") && row.path("recipientAccountId").isNull())
                .as("recipientAccountId of a copy row is null: %s", row).isTrue();
        assertThat(copyStatus(again).path("failed").asLong(-1)).as("auditForward.failed").isEqualTo(1);
    }
}
