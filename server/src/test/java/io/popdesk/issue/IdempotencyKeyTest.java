package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.fieldNames;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.numberOf;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.issue.IssueFlow.World;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.CleanupRecords;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

/**
 * The {@code Idempotency-Key} header of the two requests that file an issue: the portal's, as JSON
 * and as multipart, and the console's filing on behalf. A filing sent again with the key and content
 * of an earlier one gives that issue back instead of a second one.
 *
 * <p>Whether an issue was filed is read from the newest issues an admin sees and from the next number
 * of the company.
 */
class IdempotencyKeyTest extends BareAppTest {

    private static final String HEADER = "Idempotency-Key";

    /**
     * The cleanup jobs that are not the one of idempotency keys (the image cleanup of slice 5 among
     * them); their rows tell which table holds the records.
     */
    private static final Set<String> EARLIER_JOBS = Set.of("outbox-failed-cleanup", "attachment-cleanup",
            "session-cleanup", "password-reset-cleanup", "audit-retention", "temporary-image-cleanup",
            "trash-purge", "issue-auto-close", "sla-recalculate", "issue-retention", "company-export-expiry");

    /** The three ways to file an issue. */
    enum Way {
        PORTAL_JSON,
        PORTAL_MULTIPART,
        ON_BEHALF
    }

    /** One filing that can be sent any number of times: who sends it, its fields, and its file. */
    private record Filing(Way way, Session sender, Map<String, Object> body, File file) {

        ApiRequest request(String key) {
            ApiRequest request = sender.client().post(sender.api("/issues"));
            if (key != null) {
                request.header(HEADER, key);
            }
            if (way != Way.PORTAL_MULTIPART) {
                return request.json(body);
            }
            return request.jsonPart("issue", body).file("files", file.name(), file.contentType(), file.content());
        }

        Filing with(String field, Object value) {
            Map<String, Object> changed = new LinkedHashMap<>(body);
            changed.put(field, value);
            return new Filing(way, sender, changed, file);
        }

        Filing by(Session other) {
            return new Filing(way, other, body, file);
        }

        String title() {
            return (String) body.get("title");
        }
    }

    private IssueFlow flow;
    private CleanupRecords cleanup;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
        cleanup = new CleanupRecords(db);
    }

    private static String newKey() {
        return "key-" + UUID.randomUUID();
    }

    /** A filing for the world's company: by its reporter on the portal, or by its agent for the reporter. */
    private static Filing filing(Way way, World w) {
        if (way == Way.ON_BEHALF) {
            return new Filing(way, w.agent(), OnBehalf.of(w.code(), w.reporter().accountId()).body(), null);
        }
        return portalFiling(way, w.reporter(), w.code());
    }

    private static Filing portalFiling(Way way, Session customer, String code) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", code);
        body.put("title", Unique.text("Filed once"));
        body.put("content", "Sent twice.");
        File file = way == Way.PORTAL_MULTIPART ? File.text("first.txt", "The first file") : null;
        return new Filing(way, customer, body, file);
    }

    /** The newest issues an admin sees that have one of these titles. */
    private static List<JsonNode> titled(Session admin, String... titles) {
        return items(IssueFlow.list(admin)).stream().filter(n -> List.of(titles).contains(text(n, "title"))).toList();
    }

    /** Files one more issue in the company and gives its number. */
    private long nextNumber(Session customer, String code) {
        return numberOf(fixtures.fileIssue(customer, code).key());
    }

    private static List<String> fileNames(Session customer, String key) {
        return IssueSteps.texts(items(customer.client().get("/api/portal/issues/" + key + "/attachments")
                .expect(200)), "fileName");
    }

    // S3-AC-41

    static Stream<Arguments> waysAndKeys() {
        return Stream.of(
                Arguments.of("portal, JSON", Way.PORTAL_JSON, newKey()),
                Arguments.of("portal, multipart", Way.PORTAL_MULTIPART, newKey()),
                Arguments.of("on behalf", Way.ON_BEHALF, newKey()),
                // The longest key, with the first and the last visible ASCII character.
                Arguments.of("portal, JSON, a key of 100 characters", Way.PORTAL_JSON,
                        "!~" + UUID.randomUUID() + "a".repeat(62)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("waysAndKeys")
    @DisplayName("S3-AC-41, S3-AC-45 a filing sent again with the same key, of up to 100 visible ASCII characters, and the same content answers 201 with the first issue as it is now: one issue, one number")
    void sameKeyAndContentGiveTheFirstIssue(String what, Way way, String key) {
        World w = flow.world();
        Filing filing = filing(way, w);
        assertThat(key.length()).as("length of the key").isBetween(1, 100);
        String filed = filing.request(key).expect(201).jsonText("key");
        flow.changeStatus(w.agent(), filed, "CONFIRMED");

        ApiResponse again = filing.request(key).send();

        assertThat(again.status()).as(again.request()).isEqualTo(201);
        assertThat(again.jsonText("key")).as("key of the second answer").isEqualTo(filed);
        assertThat(text(again.json(), "status")).as("status in the second answer, as the issue is now")
                .isEqualTo("CONFIRMED");
        assertThat(titled(w.admin(), filing.title())).as("issues titled %s", filing.title()).hasSize(1);
        if (way == Way.PORTAL_MULTIPART) {
            assertThat(fileNames(w.reporter(), filed)).as("files of %s", filed).containsExactly("first.txt");
        }
        assertThat(nextNumber(w.reporter(), w.code())).as("number after %s", filed).isEqualTo(numberOf(filed) + 1);
    }

    // S3-AC-42

    /** The field that differs in the second request. */
    enum Change {
        PORTAL_TITLE,
        PORTAL_CONTENT,
        PORTAL_PRIORITY,
        PORTAL_PRIVATE,
        PORTAL_COMPANY,
        ON_BEHALF_TITLE,
        ON_BEHALF_CONTENT,
        ON_BEHALF_CHANNEL,
        ON_BEHALF_REPORTER
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Change.class)
    @DisplayName("S3-AC-42 the same key with another title, content, priority, private flag, company, customer, or channel is 409 IDEMPOTENCY_KEY_REUSED, and nothing is filed")
    void sameKeyWithOtherContentIsRefused(Change change) {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Session ofBoth = fixtures.customer(w.admin(), w.code(), other.code());
        Filing first = change.name().startsWith("ON_BEHALF") ? filing(Way.ON_BEHALF, w)
                : portalFiling(Way.PORTAL_JSON, ofBoth, w.code());
        Filing second = switch (change) {
            case PORTAL_TITLE, ON_BEHALF_TITLE -> first.with("title", Unique.text("Another title"));
            case PORTAL_CONTENT, ON_BEHALF_CONTENT -> first.with("content", "Other content.");
            case PORTAL_PRIORITY -> first.with("priority", "URGENT");
            case PORTAL_PRIVATE -> first.with("private", true);
            case PORTAL_COMPANY -> first.with("companyCode", other.code());
            case ON_BEHALF_CHANNEL -> first.with("channel", "OTHER");
            case ON_BEHALF_REPORTER -> first.with("reporterId", w.colleague().accountId());
        };
        String key = newKey();
        String filed = first.request(key).expect(201).jsonText("key");
        JsonNode before = flow.detail(w.admin(), filed);

        ApiResponse reused = second.request(key).send();

        assertThat(reused.status()).as(reused.request()).isEqualTo(409);
        assertThat(reused.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(titled(w.admin(), first.title(), second.title())).as("issues with either title").hasSize(1);
        assertThat(flow.detail(w.admin(), filed)).as("the first issue after the refused request").isEqualTo(before);
        assertThat(nextNumber(ofBoth, w.code())).as("number after %s", filed).isEqualTo(numberOf(filed) + 1);
        assertThat(fixtures.fileIssue(ofBoth, other.code()).key()).as("the first key of the other company")
                .isEqualTo(other.code() + "-1");
    }

    @Test
    @DisplayName("S3-AC-42 files are not compared: the same key and fields with another file answer the first issue, and the second file is not attached")
    void filesAreNotCompared() {
        World w = flow.world();
        Filing first = filing(Way.PORTAL_MULTIPART, w);
        Filing second = new Filing(first.way(), first.sender(), first.body(), File.text("second.txt", "Another file"));
        String key = newKey();
        String filed = first.request(key).expect(201).jsonText("key");

        ApiResponse again = second.request(key).send();

        assertThat(again.status()).as(again.request()).isEqualTo(201);
        assertThat(again.jsonText("key")).isEqualTo(filed);
        assertThat(fileNames(w.reporter(), filed)).as("files of %s", filed).containsExactly("first.txt");
        assertThat(titled(w.admin(), first.title())).hasSize(1);
    }

    // S3-AC-43

    @Test
    @DisplayName("S3-AC-43 a key belongs to its account: a colleague and an agent who send the key and content of a customer's private issue file issues of their own")
    void keyBelongsToTheAccount() {
        World w = flow.world();
        String key = newKey();
        Filing ofReporter = portalFiling(Way.PORTAL_JSON, w.reporter(), w.code()).with("private", true);
        Filing ofColleague = ofReporter.by(w.colleague());
        Filing ofAgent = filing(Way.ON_BEHALF, w).with("title", ofReporter.title()).with("private", true);
        String first = ofReporter.request(key).expect(201).jsonText("key");

        ApiResponse byColleague = ofColleague.request(key).send();
        ApiResponse byAgent = ofAgent.request(key).send();

        assertThat(byColleague.status()).as(byColleague.request()).isEqualTo(201);
        assertThat(byColleague.jsonText("key")).as("the colleague's issue").isNotEqualTo(first);
        assertThat(byColleague.json().path("reporter").path("accountId").asLong(-1)).as("reporter of the colleague's issue")
                .isEqualTo(w.colleague().accountId());
        assertThat(byAgent.status()).as(byAgent.request()).isEqualTo(201);
        assertThat(byAgent.jsonText("key")).as("the agent's issue").isNotIn(first, byColleague.jsonText("key"));
        assertThat(byAgent.json().path("createdBy").path("accountId").asLong(-1)).isEqualTo(w.agent().accountId());
        assertThat(titled(w.admin(), ofReporter.title())).as("issues with the title").hasSize(3);
        ApiResponse hidden = w.colleague().client().get("/api/portal/issues/" + first).send();
        assertThat(hidden.status()).as("the customer's private issue for the colleague").isEqualTo(404);
        assertThat(ofReporter.request(key).expect(201).jsonText("key")).as("the customer's key again").isEqualTo(first);
        assertThat(ofColleague.request(key).expect(201).jsonText("key")).as("the colleague's key again")
                .isEqualTo(byColleague.jsonText("key"));
        assertThat(ofAgent.request(key).expect(201).jsonText("key")).as("the agent's key again")
                .isEqualTo(byAgent.jsonText("key"));
    }

    // S3-AC-44

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Way.class, names = {"PORTAL_JSON", "ON_BEHALF"})
    @DisplayName("S3-AC-44 eight filings with one key at the same time file one issue and use one number, and every answer is that issue")
    void filingsWithOneKeyAtTheSameTimeFileOnce(Way way) {
        World w = flow.world();
        Filing filing = filing(way, w);
        String key = newKey();
        List<Supplier<ApiResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            calls.add(() -> filing.request(key).send());
        }

        List<ApiResponse> answers = Concurrently.send(8, calls);

        assertThat(answers).extracting(ApiResponse::status).as("statuses").containsOnly(201);
        assertThat(answers.stream().map(a -> a.jsonText("key")).distinct()).as("keys in the answers").hasSize(1);
        String filed = answers.getFirst().jsonText("key");
        assertThat(titled(w.admin(), filing.title())).as("issues titled %s", filing.title()).hasSize(1);
        assertThat(nextNumber(w.reporter(), w.code())).as("number after %s", filed).isEqualTo(numberOf(filed) + 1);
    }

    // S3-AC-45

    static Stream<Arguments> malformedKeys() {
        return Stream.of(
                Arguments.of("empty", ""),
                Arguments.of("101 characters", "k".repeat(101)),
                Arguments.of("a space inside", "two words"),
                // A tab is allowed in a header value but is not a visible character. (Characters above
                // ASCII cannot be tested here: the JDK's HTTP client sends them as "?", which is a valid key.)
                Arguments.of("a tab inside", "two	words"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedKeys")
    @DisplayName("S3-AC-45 a key that is not 1 to 100 visible ASCII characters is 400 with field Idempotency-Key, and nothing is filed")
    void malformedKeyIsRefused(String what, String key) {
        World w = flow.world();

        for (Filing filing : List.of(filing(Way.PORTAL_JSON, w), filing(Way.ON_BEHALF, w))) {
            ApiResponse refused = filing.request(key).send();

            assertThat(refused.status()).as(refused.request()).isEqualTo(400);
            assertThat(refused.errorCode()).isEqualTo("INVALID_INPUT");
            assertThat(fieldNames(refused)).as("fields of %s", refused.request()).contains(HEADER);
            assertThat(titled(w.admin(), filing.title())).as("issues titled %s", filing.title()).isEmpty();
        }
    }

    @Test
    @DisplayName("S3-AC-45 without the header every request files a new issue, on the portal and on behalf")
    void withoutTheHeaderEveryRequestFiles() {
        World w = flow.world();

        for (Filing filing : List.of(filing(Way.PORTAL_JSON, w), filing(Way.ON_BEHALF, w))) {
            String first = filing.request(null).expect(201).jsonText("key");
            String second = filing.request(null).expect(201).jsonText("key");

            assertThat(second).as("the second issue of %s", filing.way()).isNotEqualTo(first);
            assertThat(titled(w.admin(), filing.title())).as("issues titled %s", filing.title()).hasSize(2);
        }
    }

    // S3-AC-46

    @Test
    @DisplayName("S3-AC-46 just under 24 hours later the key still gives the first issue; after 24 hours the cleanup deletes it, records its run and count, and the key files a new issue")
    void keyIsForgottenAfterADay() {
        World w = flow.world();
        Filing filing = filing(Way.PORTAL_JSON, w);
        String key = newKey();
        String filed = filing.request(key).expect(201).jsonText("key");

        clock.advance(Duration.ofHours(24).minusMinutes(1));
        Session sameDay = fixtures.signInAgain(w.reporter());
        assertThat(filing.by(sameDay).request(key).expect(201).jsonText("key")).as("the key after 23 hours 59 minutes")
                .isEqualTo(filed);

        Instant due = clock.advance(Duration.ofMinutes(62)).truncatedTo(ChronoUnit.MICROS);
        JsonNode record = Wait.until("a record of a cleanup job other than " + EARLIER_JOBS + " that ran at or after "
                + due + " and deleted at least the one key", Duration.ofSeconds(90),
                () -> cleanup.ofNewJob(EARLIER_JOBS, due, 1));
        assertThat(CleanupRecords.deletedCount(record)).as("deleted count in %s", record).isGreaterThanOrEqualTo(1);

        Session nextDay = fixtures.signInAgain(w.reporter());
        ApiResponse afterCleanup = filing.by(nextDay).request(key).send();
        assertThat(afterCleanup.status()).as(afterCleanup.request()).isEqualTo(201);
        assertThat(afterCleanup.jsonText("key")).as("the key after the cleanup").isNotEqualTo(filed);
        assertThat(titled(flow.admin(), filing.title())).as("issues titled %s", filing.title()).hasSize(2);
    }

    // S3-AC-47

    /** Why the first request with the key fails. */
    enum Failure {
        WRONG_TITLE,
        COMPANY_OF_OTHERS,
        RATE_LIMITED
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("S3-AC-47 a filing that failed with 400, 404, or 429 keeps no key: the same key with the input put right files the issue, once")
    void failedFilingKeepsNoKey(Failure failure) {
        World w = flow.world();
        Company other = fixtures.createCompany(w.admin());
        Filing good = filing(Way.PORTAL_JSON, w);
        String key = newKey();

        ApiResponse failed = switch (failure) {
            case WRONG_TITLE -> good.with("title", "").request(key).send();
            case COMPANY_OF_OTHERS -> good.with("companyCode", other.code()).request(key).send();
            case RATE_LIMITED -> {
                for (int i = 0; i < 10; i++) {
                    fixtures.fileIssue(w.reporter(), w.code());
                }
                ApiResponse limited = good.request(key).send();
                clock.advance(Duration.ofSeconds(61));
                yield limited;
            }
        };
        ApiResponse filed = good.request(key).send();
        ApiResponse again = good.request(key).send();

        assertThat(failed.status()).as("the failed filing: %s", failed.request()).isEqualTo(switch (failure) {
            case WRONG_TITLE -> 400;
            case COMPANY_OF_OTHERS -> 404;
            case RATE_LIMITED -> 429;
        });
        assertThat(filed.status()).as("the same key with the input put right").isEqualTo(201);
        assertThat(again.status()).as("the same request once more").isEqualTo(201);
        assertThat(again.jsonText("key")).as("key of the answer to the request sent once more")
                .isEqualTo(filed.jsonText("key"));
        assertThat(titled(w.admin(), good.title())).as("issues titled %s", good.title()).hasSize(1);
    }
}
