package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.fieldNames;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.keys;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import tools.jackson.databind.JsonNode;

/** Filing an issue on the portal: the new issue, its field limits, and who may file where. */
class IssueCreateTest extends BareAppTest {

    /** 한 in decomposed form: three code points that NFC joins into one. */
    private static final String HAN_NFD = Normalizer.normalize("한", Normalizer.Form.NFD);

    /** An emoji outside the BMP: one code point, two UTF-16 units. */
    private static final String EMOJI = "😀";

    private IssueSteps steps;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
    }

    private static Map<String, Object> issue(String code, String title) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyCode", code);
        body.put("title", title);
        body.put("content", "Steps to reproduce.");
        return body;
    }

    private static ApiResponse file(Session customer, Map<String, Object> body) {
        return customer.client().post("/api/portal/issues").json(body).send();
    }

    // S0-AC-41

    @Test
    @DisplayName("S0-AC-41 a new issue gets key CODE-1, status RECEIVED, the customer as reporter, channel PORTAL")
    void newIssueHasKeyStatusReporterAndChannel() {
        Scene s = steps.scene();
        String title = Unique.text("First issue");

        ApiResponse created = file(s.customer(), issue(s.code(), title)).expectStatus(201);

        JsonNode issue = created.json();
        assertThat(text(issue, "key")).isEqualTo(s.code() + "-1");
        assertThat(text(issue, "status")).isEqualTo("RECEIVED");
        assertThat(text(issue, "title")).isEqualTo(title);
        assertThat(text(issue, "companyCode")).isEqualTo(s.code());
        assertThat(text(issue.path("reporter"), "kind")).isEqualTo("CUSTOMER");
        assertThat(issue.path("reporter").path("accountId").asLong()).isEqualTo(s.customer().accountId());

        JsonNode staffView = s.admin().client().get("/api/console/issues/" + s.code() + "-1").expect(200).json();
        assertThat(text(staffView, "channel")).isEqualTo("PORTAL");
        assertThat(text(staffView, "status")).isEqualTo("RECEIVED");
        assertThat(staffView.path("reporter").path("accountId").asLong()).isEqualTo(s.customer().accountId());
        assertThat(text(staffView.path("reporter"), "role")).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("S0-AC-41 an issue without priority and private flag is NORMAL and public")
    void leftOutPriorityAndPrivateMeanNormalAndPublic() {
        Scene s = steps.scene();

        String key = text(file(s.customer(), issue(s.code(), Unique.text("Defaults"))).expectStatus(201).json(), "key");

        JsonNode reread = s.customer().client().get("/api/portal/issues/" + key).expect(200).json();
        assertThat(text(reread, "priority")).isEqualTo("NORMAL");
        assertThat(reread.path("private").isBoolean()).isTrue();
        assertThat(reread.path("private").booleanValue()).isFalse();
    }

    @Test
    @DisplayName("S0-AC-41 the priority and private flag the customer sends are kept")
    void sentPriorityAndPrivateAreKept() {
        Scene s = steps.scene();
        Map<String, Object> body = issue(s.code(), Unique.text("Urgent and private"));
        body.put("priority", "URGENT");
        body.put("private", true);

        String key = text(file(s.customer(), body).expectStatus(201).json(), "key");

        JsonNode reread = s.customer().client().get("/api/portal/issues/" + key).expect(200).json();
        assertThat(text(reread, "priority")).isEqualTo("URGENT");
        assertThat(reread.path("private").booleanValue()).isTrue();
        JsonNode staffView = s.admin().client().get("/api/console/issues/" + key).expect(200).json();
        assertThat(text(staffView, "priority")).isEqualTo("URGENT");
        assertThat(staffView.path("private").booleanValue()).isTrue();
    }

    // S0-AC-42

    @Test
    @DisplayName("S0-AC-42 a title of 1 code point after trimming is accepted and stored trimmed")
    void titleOfOneCodePointAfterTrimming() {
        Scene s = steps.scene();

        ApiResponse created = file(s.customer(), issue(s.code(), "   가   ")).expectStatus(201);

        String key = text(created.json(), "key");
        assertThat(text(s.customer().client().get("/api/portal/issues/" + key).expect(200).json(), "title"))
                .isEqualTo("가");
    }

    @Test
    @DisplayName("S0-AC-42 a title of 200 code points after trimming is accepted, counted in code points")
    void titleOf200CodePoints() {
        Scene s = steps.scene();
        String title = EMOJI.repeat(200);

        ApiResponse created = file(s.customer(), issue(s.code(), "  " + title + "  ")).expectStatus(201);

        String key = text(created.json(), "key");
        assertThat(text(s.customer().client().get("/api/portal/issues/" + key).expect(200).json(), "title"))
                .isEqualTo(title);
    }

    @Test
    @DisplayName("S0-AC-42 a title of 201 code points is 400 with field title TOO_LONG")
    void titleOf201CodePoints() {
        Scene s = steps.scene();

        ApiResponse rejected = file(s.customer(), issue(s.code(), "  " + "a".repeat(201) + "  "));

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(rejected, "title")).isEqualTo("TOO_LONG");
        assertNothingFiled(s);
    }

    @Test
    @DisplayName("S0-AC-42 an empty title is 400 REQUIRED, a title of only spaces is 400 BLANK")
    void titleEmptyAfterTrimming() {
        Scene s = steps.scene();

        ApiResponse blank = file(s.customer(), issue(s.code(), "    "));
        ApiResponse empty = file(s.customer(), issue(s.code(), ""));

        for (ApiResponse rejected : new ApiResponse[] {blank, empty}) {
            assertThat(rejected.status()).as(rejected.request()).isEqualTo(400);
            assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        }
        assertThat(fieldCode(empty, "title")).isEqualTo("REQUIRED");
        assertThat(fieldCode(blank, "title")).isEqualTo("BLANK");
        assertNothingFiled(s);
    }

    @Test
    @DisplayName("S0-AC-42 a decomposed Hangul title of 200 syllables is accepted after NFC and stored in NFC")
    void decomposedHangulTitleIsCountedAfterNfc() {
        Scene s = steps.scene();
        String decomposed = HAN_NFD.repeat(200);
        assertThat(decomposed.codePointCount(0, decomposed.length())).isEqualTo(600);

        ApiResponse created = file(s.customer(), issue(s.code(), decomposed)).expectStatus(201);

        String key = text(created.json(), "key");
        String stored = text(s.customer().client().get("/api/portal/issues/" + key).expect(200).json(), "title");
        assertThat(stored).isEqualTo("한".repeat(200));
        assertThat(Normalizer.isNormalized(stored, Normalizer.Form.NFC)).isTrue();
    }

    @Test
    @DisplayName("S0-AC-42 a decomposed Hangul title of 201 syllables is 400 with field title TOO_LONG")
    void decomposedHangulTitleOf201Syllables() {
        Scene s = steps.scene();

        ApiResponse rejected = file(s.customer(), issue(s.code(), HAN_NFD.repeat(201)));

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(fieldCode(rejected, "title")).isEqualTo("TOO_LONG");
    }

    @Test
    @DisplayName("S0-AC-42 content of 10,000 code points is accepted and kept whole")
    void contentOf10000CodePoints() {
        Scene s = steps.scene();
        Map<String, Object> body = issue(s.code(), Unique.text("Long content"));
        String content = EMOJI.repeat(10_000);
        body.put("content", content);

        String key = text(file(s.customer(), body).expectStatus(201).json(), "key");

        assertThat(text(s.customer().client().get("/api/portal/issues/" + key).expect(200).json(), "content"))
                .isEqualTo(content);
    }

    @Test
    @DisplayName("S0-AC-42 decomposed Hangul content of 10,000 syllables is counted after NFC and accepted")
    void decomposedContentIsCountedAfterNfc() {
        Scene s = steps.scene();
        Map<String, Object> body = issue(s.code(), Unique.text("Decomposed content"));
        body.put("content", HAN_NFD.repeat(10_000));

        String key = text(file(s.customer(), body).expectStatus(201).json(), "key");

        assertThat(text(s.customer().client().get("/api/portal/issues/" + key).expect(200).json(), "content"))
                .isEqualTo("한".repeat(10_000));
    }

    @Test
    @DisplayName("S0-AC-42 content of 10,001 code points is 400 with field content TOO_LONG")
    void contentOf10001CodePoints() {
        Scene s = steps.scene();
        Map<String, Object> body = issue(s.code(), Unique.text("Too long content"));
        body.put("content", "a".repeat(10_001));

        ApiResponse rejected = file(s.customer(), body);

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(rejected, "content")).isEqualTo("TOO_LONG");
        assertNothingFiled(s);
    }

    @Test
    @DisplayName("S0-AC-42 an unknown priority is 400 with field priority UNKNOWN_VALUE")
    void unknownPriority() {
        Scene s = steps.scene();
        Map<String, Object> body = issue(s.code(), Unique.text("Odd priority"));
        body.put("priority", "CRITICAL");

        ApiResponse rejected = file(s.customer(), body);

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(rejected, "priority")).isEqualTo("UNKNOWN_VALUE");
        assertNothingFiled(s);
    }

    @Test
    @DisplayName("S0-AC-42 every wrong field is listed in fields at once")
    void everyWrongFieldIsListed() {
        Scene s = steps.scene();
        Map<String, Object> body = issue(s.code(), "a".repeat(201));
        body.put("content", "a".repeat(10_001));
        body.put("priority", "CRITICAL");

        ApiResponse rejected = file(s.customer(), body);

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldNames(rejected)).contains("title", "content", "priority");
        assertThat(fieldCode(rejected, "title")).isEqualTo("TOO_LONG");
        assertThat(fieldCode(rejected, "content")).isEqualTo("TOO_LONG");
        assertThat(fieldCode(rejected, "priority")).isEqualTo("UNKNOWN_VALUE");
        assertNothingFiled(s);
    }

    // S0-AC-43

    @Test
    @DisplayName("S0-AC-43 a customer filing in a company they do not belong to gets 404 and nothing is filed")
    void customerOfOtherCompanyGets404() {
        Scene a = steps.scene();
        Company b = fixtures.createCompany(a.admin());
        Session ofB = fixtures.customer(a.admin(), b.code());
        String title = Unique.text("Wrong company");

        ApiResponse rejected = file(a.customer(), issue(b.code(), title));

        assertThat(rejected.status()).isEqualTo(404);
        assertThat(rejected.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(items(ofB.client().get("/api/portal/issues").expect(200))).isEmpty();
        assertThat(items(a.admin().client().get("/api/console/issues").query("size", 100).expect(200)))
                .extracting(n -> text(n, "title"))
                .doesNotContain(title);
    }

    @Test
    @DisplayName("S0-AC-43 a company code that does not exist gets the same 404 as a company of others")
    void unknownCompanyGetsSame404() {
        Scene a = steps.scene();
        Company b = fixtures.createCompany(a.admin());

        ApiResponse unknown = file(a.customer(), issue(Unique.companyCode(), Unique.text("Nowhere")));
        ApiResponse others = file(a.customer(), issue(b.code(), Unique.text("Elsewhere")));

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(others.status()).isEqualTo(404);
        assertThat(others.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-43 admins and agents calling the portal filing path on the console host get 404")
    void staffOnConsoleHostGet404() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        String title = Unique.text("Filed by staff");

        ApiResponse byAdmin = s.admin().client().post("/api/portal/issues").json(issue(s.code(), title)).send();
        ApiResponse byAgent = agent.client().post("/api/portal/issues").json(issue(s.code(), title)).send();

        assertThat(byAdmin.status()).isEqualTo(404);
        assertThat(byAdmin.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(byAgent.status()).isEqualTo(404);
        assertThat(byAgent.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(keys(s.customer().client().get("/api/portal/issues").expect(200))).isEmpty();
        assertThat(items(agent.client().get("/api/console/issues").query("size", 100).expect(200)))
                .extracting(n -> text(n, "title"))
                .doesNotContain(title);
    }

    /** The scene's customer sees no issue: a rejected request filed nothing. */
    private static void assertNothingFiled(Scene s) {
        ApiResponse list = s.customer().client().get("/api/portal/issues").expect(200);
        assertThat(items(list)).as("issues after a rejected filing").isEmpty();
        assertThat(list.json().path("totalElements").asLong()).isZero();
    }
}
