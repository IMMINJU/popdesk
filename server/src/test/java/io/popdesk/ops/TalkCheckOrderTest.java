package io.popdesk.ops;

import static io.popdesk.issue.TalkFlow.body;

import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The order of checks on the operations slice 4 adds (conventions "Order of checks", COM-1, COM-2):
 * 401, then 404 for a target out of scope, then 403 for a target the caller sees but may not act on,
 * then 400 for the input, then 400 for the current state. Each request below is wrong in several ways
 * at once, or wrong in an earlier and right in a later one, and must get the first check in that order.
 * {@link CheckOrderTest} is a list written by hand
 * and ends with slice 3; this class goes on from there.
 */
class TalkCheckOrderTest extends BareAppTest {

    private static final String NO_KEY = "NOPE-999999";
    private static final long NO_ID = 999_999_999L;

    private static void expect(SoftAssertions soft, String what, ApiRequest request, int status, String code) {
        ApiResponse response = request.send();
        soft.assertThat(response.status()).as("%s: %s -> %s", what, response.request(), response.snippet())
                .isEqualTo(status);
        if (response.status() == status) {
            soft.assertThat(response.errorCode()).as("code of %s", what).isEqualTo(code);
        }
    }

    private static Map<String, Object> emptyWithAMentionOfNobody() {
        return body("content", "", "mentions", List.of(NO_ID));
    }

    @Test
    @DisplayName("S4-AC-70, S4-AC-39 without a session every operation this slice adds, and the console inbox it "
            + "fills, answers 401 first, whatever else is wrong")
    void unauthenticatedFirst() {
        ApiClient c = console();
        ApiClient p = portal();
        String ci = "/api/console/issues/" + NO_KEY;
        String pi = "/api/portal/issues/" + NO_KEY;
        SoftAssertions soft = new SoftAssertions();

        expect(soft, "portal edit comment", p.patch(pi + "/comments/nope").json(body("content", "")), 401,
                "UNAUTHENTICATED");
        expect(soft, "portal delete comment", p.delete(pi + "/comments/nope"), 401, "UNAUTHENTICATED");
        expect(soft, "portal notifications", p.get("/api/portal/notifications").query("size", 0), 401,
                "UNAUTHENTICATED");
        expect(soft, "portal unread count", p.get("/api/portal/notifications/unread-count"), 401, "UNAUTHENTICATED");
        expect(soft, "portal mark read", p.post("/api/portal/notifications/" + NO_ID + "/read"), 401,
                "UNAUTHENTICATED");
        expect(soft, "portal read all", p.post("/api/portal/notifications/read-all"), 401, "UNAUTHENTICATED");
        expect(soft, "console edit comment", c.patch(ci + "/comments/nope").json(emptyWithAMentionOfNobody()), 401,
                "UNAUTHENTICATED");
        expect(soft, "console delete comment", c.delete(ci + "/comments/nope"), 401, "UNAUTHENTICATED");
        expect(soft, "mention candidates", c.get(ci + "/mention-candidates").query("internal", "maybe"), 401,
                "UNAUTHENTICATED");
        expect(soft, "console notifications", c.get("/api/console/notifications").query("size", 0), 401,
                "UNAUTHENTICATED");
        expect(soft, "console unread count", c.get("/api/console/notifications/unread-count"), 401,
                "UNAUTHENTICATED");
        expect(soft, "console read all", c.post("/api/console/notifications/read-all"), 401, "UNAUTHENTICATED");
        soft.assertAll();
    }

    @Test
    @DisplayName("S4-AC-03, S4-AC-23 a comment or the mention candidates of an issue out of scope are 404 before "
            + "a 403 or bad input")
    void scopeBeforePermissionAndInput() {
        OpsWorld w = OpsWorld.build(fixtures);
        String key = w.issueA().key();
        Comment byCustomer = fixtures.comment(w.customerA(), key, Unique.text("By the customer"));
        Comment byAgent = fixtures.comment(w.agentA(), key, Unique.text("By the agent"));
        ApiClient outsider = w.agentNowhere().client();
        ApiClient customerB = w.customerB().client();
        String agentPath = TalkFlow.commentPath(w.agentNowhere(), key, byCustomer.id());
        String portalPath = TalkFlow.commentPath(w.customerB(), key, byAgent.id());
        SoftAssertions soft = new SoftAssertions();

        expect(soft, "agent elsewhere: edit a customer's comment with an empty content and a mention of nobody",
                outsider.patch(agentPath).json(emptyWithAMentionOfNobody()), 404, "NOT_FOUND");
        expect(soft, "agent elsewhere: delete a customer's comment", outsider.delete(agentPath), 404, "NOT_FOUND");
        expect(soft, "agent elsewhere: mention candidates with internal=maybe", outsider
                .get("/api/console/issues/" + key + "/mention-candidates").query("internal", "maybe"), 404,
                "NOT_FOUND");
        expect(soft, "customer of B: edit a staff comment with an empty content",
                customerB.patch(portalPath).json(body("content", "")), 404, "NOT_FOUND");
        expect(soft, "customer of B: delete a staff comment", customerB.delete(portalPath), 404, "NOT_FOUND");
        expect(soft, "customer of B: mark a missing item read", customerB.post("/api/portal/notifications/" + NO_ID
                + "/read"), 404, "NOT_FOUND");
        soft.assertAll();
    }

    @Test
    @DisplayName("S4-AC-02 editing a comment the caller sees but did not write is 403 before bad input")
    void permissionBeforeInput() {
        OpsWorld w = OpsWorld.build(fixtures);
        String key = w.issueA().key();
        Comment byCustomer = fixtures.comment(w.customerA(), key, Unique.text("By the customer"));
        Comment byAgent = fixtures.comment(w.agentA(), key, Unique.text("By the agent"));
        SoftAssertions soft = new SoftAssertions();

        expect(soft, "colleague: the reporter's comment with an empty content", TalkFlow.editRequest(w.colleagueA(),
                key, byCustomer.id(), body("content", "")), 403, "FORBIDDEN");
        expect(soft, "reporter: a staff comment with an empty content", TalkFlow.editRequest(w.customerA(), key,
                byAgent.id(), body("content", "")), 403, "FORBIDDEN");
        expect(soft, "assigned agent: the reporter's comment with an empty content and a mention of nobody",
                TalkFlow.editRequest(w.agentA(), key, byCustomer.id(), emptyWithAMentionOfNobody()), 403,
                "FORBIDDEN");
        expect(soft, "admin: an agent's comment with an empty content and a mention of nobody",
                TalkFlow.editRequest(w.admin(), key, byAgent.id(), emptyWithAMentionOfNobody()), 403, "FORBIDDEN");
        soft.assertAll();
    }

    @Test
    @DisplayName("S4-AC-04, S4-AC-02 an edit of someone else's comment on a CLOSED issue, with valid input, is "
            + "403 FORBIDDEN, not ISSUE_CLOSED, and the comment stays")
    void permissionBeforeState() {
        OpsWorld w = OpsWorld.build(fixtures);
        String key = w.issueA().key();
        Comment byCustomer = fixtures.comment(w.customerA(), key, Unique.text("By the customer"));
        Comment byAgent = fixtures.comment(w.agentA(), key, Unique.text("By the agent"));
        IssueFlow.closeRequest(w.customerA(), key).expect(200);
        List<JsonNode> before = TalkFlow.comments(w.admin(), key);
        SoftAssertions soft = new SoftAssertions();

        expect(soft, "assigned agent: the reporter's comment on a closed issue", TalkFlow.editRequest(w.agentA(),
                key, byCustomer.id(), body("content", Unique.text("Not mine"))), 403, "FORBIDDEN");
        expect(soft, "reporter: a staff comment on a closed issue", TalkFlow.editRequest(w.customerA(), key,
                byAgent.id(), body("content", Unique.text("Not mine"))), 403, "FORBIDDEN");
        expect(soft, "admin: an agent's comment on a closed issue", TalkFlow.editRequest(w.admin(), key,
                byAgent.id(), body("content", Unique.text("Not mine"))), 403, "FORBIDDEN");
        soft.assertThat(TalkFlow.comments(w.admin(), key)).as("the comments after the refused edits")
                .isEqualTo(before);
        soft.assertAll();
    }

    @Test
    @DisplayName("S4-AC-04, S4-AC-08 the author's edit with bad input is 400 INVALID_INPUT before ISSUE_CLOSED "
            + "and COMMENT_DELETED, which the same edit with valid input gets")
    void inputBeforeState() {
        OpsWorld w = OpsWorld.build(fixtures);
        String open = w.issueA().key();
        Comment deleted = fixtures.comment(w.customerA(), open, Unique.text("Soon deleted"));
        TalkFlow.delete(w.customerA(), open, deleted.id());
        Issue closed = fixtures.fileIssue(w.customerA(), w.a().code());
        Comment byCustomer = fixtures.comment(w.customerA(), closed.key(), Unique.text("By the customer"));
        Comment byAgent = fixtures.comment(w.agentA(), closed.key(), Unique.text("By the agent"));
        IssueFlow.closeRequest(w.customerA(), closed.key()).expect(200);
        SoftAssertions soft = new SoftAssertions();

        expect(soft, "author: a deleted comment with an empty content", TalkFlow.editRequest(w.customerA(), open,
                deleted.id(), body("content", "")), 400, "INVALID_INPUT");
        expect(soft, "reporter: own comment on a closed issue with an empty content", TalkFlow.editRequest(
                w.customerA(), closed.key(), byCustomer.id(), body("content", "")), 400, "INVALID_INPUT");
        expect(soft, "agent: own comment on a closed issue with an empty content and a mention of nobody",
                TalkFlow.editRequest(w.agentA(), closed.key(), byAgent.id(), emptyWithAMentionOfNobody()), 400,
                "INVALID_INPUT");
        // The same requests with valid input reach the state check.
        expect(soft, "author: a deleted comment", TalkFlow.editRequest(w.customerA(), open, deleted.id(),
                body("content", Unique.text("Back again"))), 400, "COMMENT_DELETED");
        expect(soft, "reporter: own comment on a closed issue", TalkFlow.editRequest(w.customerA(), closed.key(),
                byCustomer.id(), body("content", Unique.text("After closing"))), 400, "ISSUE_CLOSED");
        expect(soft, "agent: own comment on a closed issue", TalkFlow.editRequest(w.agentA(), closed.key(),
                byAgent.id(), body("content", Unique.text("After closing"))), 400, "ISSUE_CLOSED");
        soft.assertAll();
    }
}
