package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.text;
import static io.popdesk.issue.IssueSteps.texts;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Who may comment, the comment's length, internal comments, and the comment list. */
class CommentTest extends BareAppTest {

    /** An emoji outside the BMP: one code point, two UTF-16 units. */
    private static final String EMOJI = "😀";

    private IssueSteps steps;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
    }

    private static ApiResponse comment(Session session, String key, Map<String, Object> body) {
        return session.client().post(session.api("/issues/" + key + "/comments")).json(body).send();
    }

    private static ApiResponse comments(Session session, String key) {
        return session.client().get(session.api("/issues/" + key + "/comments")).query("size", 100).expect(200);
    }

    // S0-AC-51

    @Test
    @DisplayName("S0-AC-51 the reporter, an assigned agent, and an admin can add public comments")
    void reporterAndStaffComment() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        ApiResponse byReporter = comment(s.customer(), issue.key(), Map.of("content", "From the reporter"));
        ApiResponse byAgent = comment(agent, issue.key(), Map.of("content", "From the agent"));
        ApiResponse byAdmin = comment(s.admin(), issue.key(), Map.of("content", "From the admin"));

        assertThat(List.of(byReporter.status(), byAgent.status(), byAdmin.status())).containsOnly(201);
        assertThat(texts(items(comments(agent, issue.key())), "content"))
                .containsExactlyInAnyOrder("From the reporter", "From the agent", "From the admin");
        assertThat(items(comments(agent, issue.key()))).allMatch(c -> !c.path("internal").booleanValue());
    }

    @Test
    @DisplayName("S0-AC-51 an agent not assigned to the issue's company gets 404 and adds nothing")
    void unassignedAgentGets404() {
        Scene s = steps.scene();
        Company other = fixtures.createCompany(s.admin());
        Session outsider = fixtures.agent(s.admin(), other.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        ApiResponse rejected = comment(outsider, issue.key(), Map.of("content", "Not my company"));

        assertThat(rejected.status()).isEqualTo(404);
        assertThat(rejected.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(items(comments(s.admin(), issue.key()))).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-51 a comment of 1 code point and one of 4,000 code points are accepted and kept whole")
    void commentLengthLimitsInside() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String longest = EMOJI.repeat(4_000);

        ApiResponse shortest = comment(s.customer(), issue.key(), Map.of("content", "a"));
        ApiResponse longestResponse = comment(s.customer(), issue.key(), Map.of("content", longest));

        assertThat(shortest.status()).isEqualTo(201);
        assertThat(longestResponse.status()).isEqualTo(201);
        assertThat(texts(items(comments(s.customer(), issue.key())), "content")).containsExactlyInAnyOrder("a", longest);
    }

    @Test
    @DisplayName("S0-AC-51 an empty comment or one of 4,001 code points is 400 with field content")
    void commentLengthLimitsOutside() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());

        ApiResponse empty = comment(s.customer(), issue.key(), Map.of("content", ""));
        ApiResponse tooLong = comment(s.customer(), issue.key(), Map.of("content", "a".repeat(4_001)));
        ApiResponse tooLongByAgent = comment(agent, issue.key(), Map.of("content", "a".repeat(4_001)));

        assertThat(empty.status()).isEqualTo(400);
        assertThat(empty.errorCode()).isEqualTo("INVALID_INPUT");
        assertThat(fieldCode(empty, "content")).isIn("REQUIRED", "TOO_SHORT", "BLANK");
        for (ApiResponse r : List.of(tooLong, tooLongByAgent)) {
            assertThat(r.status()).as(r.request()).isEqualTo(400);
            assertThat(r.errorCode()).isEqualTo("INVALID_INPUT");
            assertThat(fieldCode(r, "content")).isEqualTo("TOO_LONG");
        }
        assertThat(items(comments(agent, issue.key()))).isEmpty();
    }

    // S0-AC-52

    @Test
    @DisplayName("S0-AC-52 a colleague of the reporter who sees the public issue gets 403 on commenting")
    void colleagueGets403() {
        Scene s = steps.scene();
        Session colleague = fixtures.customer(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        assertThat(colleague.client().get("/api/portal/issues/" + issue.key()).send().status())
                .as("the colleague sees the public issue").isEqualTo(200);

        ApiResponse rejected = comment(colleague, issue.key(), Map.of("content", "Me too"));

        assertThat(rejected.status()).isEqualTo(403);
        assertThat(rejected.errorCode()).isEqualTo("FORBIDDEN");
        assertThat(items(comments(s.customer(), issue.key()))).isEmpty();
    }

    @Test
    @DisplayName("S0-AC-52 a colleague commenting on the reporter's private issue gets 404, not 403")
    void colleagueOnPrivateIssueGets404() {
        Scene s = steps.scene();
        Session colleague = fixtures.customer(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), NewIssue.in(s.code()).privateIssue());

        ApiResponse rejected = comment(colleague, issue.key(), Map.of("content", "Me too"));

        assertThat(rejected.status()).isEqualTo(404);
        assertThat(rejected.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(items(comments(s.customer(), issue.key()))).isEmpty();
    }

    // S0-AC-53

    @Test
    @DisplayName("S0-AC-53 an internal comment is kept for staff and left out of the customer's list and totals")
    void internalCommentHiddenFromCustomer() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        String secret = "Internal " + Unique.number();

        Comment answer = fixtures.comment(agent, issue.key(), "Public answer");
        Comment internal = fixtures.internalComment(agent, issue.key(), secret);

        ApiResponse staffList = comments(agent, issue.key());
        assertThat(items(staffList)).extracting(c -> c.path("id").asLong())
                .containsExactlyInAnyOrder(answer.id(), internal.id());
        assertThat(items(staffList)).filteredOn(c -> c.path("id").asLong() == internal.id())
                .extracting(c -> c.path("internal").booleanValue()).containsExactly(true);
        assertThat(items(staffList)).filteredOn(c -> c.path("id").asLong() == answer.id())
                .extracting(c -> c.path("internal").booleanValue()).containsExactly(false);
        ApiResponse customerList = comments(s.customer(), issue.key());
        assertThat(items(customerList)).extracting(c -> c.path("id").asLong()).containsExactly(answer.id());
        assertThat(customerList.json().path("totalElements").asLong()).isEqualTo(1);
        assertThat(customerList.text()).doesNotContain(secret);
    }

    // S0-AC-54

    @Test
    @DisplayName("S0-AC-54 comments are listed oldest first, for staff with internal ones, for the customer without")
    void oldestFirst() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Comment c1 = fixtures.comment(s.customer(), issue.key(), "One");
        steps.tick();
        Comment c2 = fixtures.comment(agent, issue.key(), "Two");
        steps.tick();
        Comment c3 = fixtures.internalComment(agent, issue.key(), "Three");
        steps.tick();
        Comment c4 = fixtures.comment(s.admin(), issue.key(), "Four");

        assertThat(items(comments(agent, issue.key()))).extracting(c -> c.path("id").asLong())
                .containsExactly(c1.id(), c2.id(), c3.id(), c4.id());
        assertThat(items(comments(s.customer(), issue.key()))).extracting(c -> c.path("id").asLong())
                .containsExactly(c1.id(), c2.id(), c4.id());
    }

    @Test
    @DisplayName("S0-AC-54 staff see the author's name on public and internal comments")
    void staffSeeAuthorNames() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        fixtures.comment(s.customer(), issue.key(), "By the customer");
        steps.tick();
        fixtures.comment(agent, issue.key(), "By the agent");
        steps.tick();
        fixtures.internalComment(s.admin(), issue.key(), "By the admin");

        List<JsonNode> list = items(comments(agent, issue.key()));

        assertThat(list).hasSize(3);
        assertAuthor(list.get(0).path("author"), s.customer(), "CUSTOMER");
        assertAuthor(list.get(1).path("author"), agent, "AGENT");
        assertAuthor(list.get(2).path("author"), s.admin(), "ADMIN");
    }

    private static void assertAuthor(JsonNode author, Session who, String role) {
        assertThat(text(author, "name")).isEqualTo(who.name());
        assertThat(author.path("accountId").asLong()).isEqualTo(who.accountId());
        assertThat(text(author, "role")).isEqualTo(role);
    }

    @Test
    @DisplayName("S0-AC-54 the customer sees customers' names, and staff comments only as the support team")
    void customerSeesSupportTeam() {
        Scene s = steps.scene();
        Session agent = fixtures.agent(s.admin(), s.code());
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        fixtures.comment(s.customer(), issue.key(), "By the customer");
        steps.tick();
        fixtures.comment(agent, issue.key(), "By the agent");
        steps.tick();
        fixtures.comment(s.admin(), issue.key(), "By the admin");

        ApiResponse response = comments(s.customer(), issue.key());
        List<JsonNode> list = items(response);

        assertThat(list).hasSize(3);
        JsonNode customer = list.get(0).path("author");
        assertThat(text(customer, "kind")).isEqualTo("CUSTOMER");
        assertThat(text(customer, "name")).isEqualTo(s.customer().name());
        assertThat(customer.path("accountId").asLong()).isEqualTo(s.customer().accountId());
        for (JsonNode staff : List.of(list.get(1).path("author"), list.get(2).path("author"))) {
            assertThat(text(staff, "kind")).isEqualTo("SUPPORT_TEAM");
            assertThat(staff.has("name")).as("name present as null (D-44)").isTrue();
            assertThat(staff.path("name").isNull()).isTrue();
            assertThat(staff.has("accountId")).as("accountId present as null (D-44)").isTrue();
            assertThat(staff.path("accountId").isNull()).isTrue();
        }
        assertThat(response.text()).doesNotContain(agent.name(), agent.email(), s.admin().email());
    }

    @Test
    @DisplayName("S0-AC-54 the comment list is split into pages with page, size, and totals")
    void commentPages() {
        Scene s = steps.scene();
        Issue issue = fixtures.fileIssue(s.customer(), s.code());
        Comment c1 = fixtures.comment(s.customer(), issue.key(), "One");
        steps.tick();
        Comment c2 = fixtures.comment(s.customer(), issue.key(), "Two");
        steps.tick();
        Comment c3 = fixtures.comment(s.customer(), issue.key(), "Three");
        String path = "/api/portal/issues/" + issue.key() + "/comments";

        ApiResponse first = s.customer().client().get(path).query("page", 1).query("size", 2).expect(200);
        ApiResponse second = s.customer().client().get(path).query("page", 2).query("size", 2).expect(200);
        ApiResponse past = s.customer().client().get(path).query("page", 3).query("size", 2).expect(200);
        ApiResponse tooBig = s.customer().client().get(path).query("size", 101).send();

        assertThat(items(first)).extracting(c -> c.path("id").asLong()).containsExactly(c1.id(), c2.id());
        assertThat(items(second)).extracting(c -> c.path("id").asLong()).containsExactly(c3.id());
        assertThat(items(past)).isEmpty();
        assertThat(first.json().path("totalElements").asLong()).isEqualTo(3);
        assertThat(first.json().path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.json().path("size").asInt()).isEqualTo(2);
        assertThat(tooBig.status()).isEqualTo(400);
        assertThat(fieldCode(tooBig, "size")).isEqualTo("OUT_OF_RANGE");
    }
}
