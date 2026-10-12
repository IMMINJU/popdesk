package io.popdesk.boundary;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueFlow;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.JsonNode;

/**
 * The customer-company boundary on the endpoints of slice 3 that name an issue (S3-AC-88): a
 * customer of company A on the portal, and an agent assigned to A only on the console, send a
 * request that would be valid for their own issue to an issue of company B. The answer is the 404 of
 * an issue key nobody has, and B's issue and its history are as they were.
 *
 * <p>B's issue is {@code RESOLVED} with an assignee, so that every request here would change
 * something if it went through.
 */
class IssueBoundaryTest extends BareAppTest {

    private static final String NO_KEY = "ZZ9NOPE-999999";

    /** What a request needs besides the caller and the issue key. */
    private record Input(long version, long ownAccountId) {}

    /** One request to an issue; {@code path} comes after the app's {@code /issues/{key}}. */
    private interface Call {
        ApiRequest request(Session caller, String issuePath, Input input);
    }

    /** The endpoints, by app. */
    enum Operation {
        PORTAL_EDIT(true, (c, p, in) -> c.client().patch(p).json(edit(in))),
        PORTAL_CLOSE(true, (c, p, in) -> c.client().post(p + "/close")),
        PORTAL_REOPEN(true, (c, p, in) -> c.client().post(p + "/reopen")),
        CONSOLE_EDIT(false, (c, p, in) -> c.client().patch(p).json(edit(in))),
        CONSOLE_STATUS(false, (c, p, in) -> c.client().put(p + "/status").json(Map.of("status", "CLOSED"))),
        CONSOLE_REOPEN(false, (c, p, in) -> c.client().post(p + "/reopen")),
        CONSOLE_SET_ASSIGNEE(false, (c, p, in) -> c.client().put(p + "/assignee")
                .json(Map.of("accountId", in.ownAccountId()))),
        CONSOLE_CLEAR_ASSIGNEE(false, (c, p, in) -> c.client().delete(p + "/assignee")),
        CONSOLE_HISTORY(false, (c, p, in) -> c.client().get(p + "/history"));

        private final boolean portal;
        private final Call call;

        Operation(boolean portal, Call call) {
            this.portal = portal;
            this.call = call;
        }

        private static Map<String, Object> edit(Input in) {
            return Map.of("title", Unique.text("Across the boundary"), "version", in.version());
        }
    }

    private IssueFlow flow;

    @BeforeEach
    void setUp() {
        flow = new IssueFlow(fixtures, clock);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Operation.class)
    @DisplayName("S3-AC-88 a customer of A and an agent assigned to A only get the 404 of a missing key for an issue of B, and the issue stays as it was")
    void issueOfAnotherCompanyIsNotFound(Operation operation) {
        Session admin = flow.admin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session customerOfA = fixtures.customer(admin, a.code());
        Session agentOfA = fixtures.agent(admin, a.code());
        Session customerOfB = fixtures.customer(admin, b.code());
        Issue ofB = fixtures.fileIssue(customerOfB, b.code());
        flow.assign(admin, ofB.key(), admin.accountId());
        flow.moveTo(admin, ofB.key(), "RESOLVED");
        JsonNode before = flow.detail(admin, ofB.key());
        List<JsonNode> historyBefore = flow.history(admin, ofB.key());
        Session caller = operation.portal ? customerOfA : agentOfA;
        Input input = new Input(before.path("version").asLong(-1), caller.accountId());
        flow.tick();

        ApiResponse answer = operation.call.request(caller, caller.api("/issues/" + ofB.key()), input).send();
        ApiResponse missing = operation.call.request(caller, caller.api("/issues/" + NO_KEY), input).send();

        assertThat(missing.status()).as(missing.request() + " -> " + missing.snippet()).isEqualTo(404);
        assertThat(answer.status()).as(answer.request() + " -> " + answer.snippet()).isEqualTo(404);
        assertThat(answer.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(answer.text()).as("body, like an issue key nobody has").isEqualTo(missing.text());
        assertThat(flow.detail(admin, ofB.key())).as("the issue of B after the request").isEqualTo(before);
        assertThat(flow.history(admin, ofB.key())).as("the history of B's issue after the request")
                .isEqualTo(historyBefore);
    }
}
