package io.popdesk.issue;

import static io.popdesk.issue.IssueSteps.fieldCode;
import static io.popdesk.issue.IssueSteps.items;
import static io.popdesk.issue.IssueSteps.keys;
import static io.popdesk.issue.IssueSteps.text;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.issue.IssueSteps.Scene;
import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Who sees which issues in lists and details, in what order, and in pages. */
class IssueListTest extends BareAppTest {

    private IssueSteps steps;

    @BeforeEach
    void setUp() {
        steps = new IssueSteps(fixtures, clock);
    }

    /**
     * Company A with customers alice and bob, company B with carol. Each of alice and bob filed a
     * public and a private issue, carol a public one, a second apart, in this order.
     */
    private record World(Scene a, Company b, Session alice, Session bob, Session carol, Issue alicePublic,
            Issue alicePrivate, Issue bobPublic, Issue bobPrivate, Issue carolPublic) {}

    private World world() {
        Scene a = steps.scene();
        Company b = fixtures.createCompany(a.admin());
        Session bob = fixtures.customer(a.admin(), a.code());
        Session carol = fixtures.customer(a.admin(), b.code());
        Issue alicePublic = fixtures.fileIssue(a.customer(), a.code());
        steps.tick();
        Issue alicePrivate = fixtures.fileIssue(a.customer(), NewIssue.in(a.code()).privateIssue());
        steps.tick();
        Issue bobPublic = fixtures.fileIssue(bob, a.code());
        steps.tick();
        Issue bobPrivate = fixtures.fileIssue(bob, NewIssue.in(a.code()).privateIssue());
        steps.tick();
        Issue carolPublic = fixtures.fileIssue(carol, b.code());
        return new World(a, b, a.customer(), bob, carol, alicePublic, alicePrivate, bobPublic, bobPrivate,
                carolPublic);
    }

    private static ApiResponse list(Session session) {
        return session.client().get(session.api("/issues")).query("size", 100).expect(200);
    }

    private static ApiResponse detail(Session session, Issue issue) {
        return session.client().get(session.api("/issues/" + issue.key())).send();
    }

    // S0-AC-47

    @Test
    @DisplayName("S0-AC-47 a customer lists their company's public issues and their own private ones only")
    void customerList() {
        World w = world();

        assertThat(keys(list(w.alice())))
                .containsExactlyInAnyOrder(w.alicePublic().key(), w.alicePrivate().key(), w.bobPublic().key());
        assertThat(keys(list(w.carol()))).containsExactly(w.carolPublic().key());
    }

    @Test
    @DisplayName("S0-AC-47 a customer opens their own private issue, but a colleague's private issue and other companies' issues are 404")
    void customerDetail() {
        World w = world();

        assertThat(detail(w.alice(), w.alicePrivate()).status()).isEqualTo(200);
        assertThat(detail(w.alice(), w.bobPublic()).status()).isEqualTo(200);
        ApiResponse colleaguePrivate = detail(w.alice(), w.bobPrivate());
        ApiResponse otherCompany = detail(w.alice(), w.carolPublic());
        assertThat(colleaguePrivate.status()).isEqualTo(404);
        assertThat(colleaguePrivate.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(otherCompany.status()).isEqualTo(404);
        assertThat(otherCompany.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-47 a customer taken out of their company still lists and opens the issues they filed there, and no longer a colleague's public issue")
    void customerWhoLeftKeepsOwnIssues() {
        World w = world();

        new Membership(db).remove(w.alice().accountId(), w.a().code());

        assertThat(keys(list(w.alice()))).containsExactlyInAnyOrder(w.alicePublic().key(), w.alicePrivate().key());
        assertThat(detail(w.alice(), w.alicePublic()).status()).isEqualTo(200);
        assertThat(detail(w.alice(), w.alicePrivate()).status()).isEqualTo(200);
        ApiResponse colleaguePublic = detail(w.alice(), w.bobPublic());
        assertThat(colleaguePublic.status()).isEqualTo(404);
        assertThat(colleaguePublic.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(keys(list(w.bob()))).contains(w.alicePublic().key(), w.bobPublic().key());
    }

    @Test
    @DisplayName("S0-AC-47 an agent sees every issue of assigned companies, private ones too, and nothing of others")
    void agentSeesAssignedCompanies() {
        World w = world();
        Session agent = fixtures.agent(w.a().admin(), w.a().code());

        assertThat(keys(list(agent))).containsExactlyInAnyOrder(w.alicePublic().key(), w.alicePrivate().key(),
                w.bobPublic().key(), w.bobPrivate().key());
        assertThat(detail(agent, w.bobPrivate()).status()).isEqualTo(200);
        ApiResponse other = detail(agent, w.carolPublic());
        assertThat(other.status()).isEqualTo(404);
        assertThat(other.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("S0-AC-47 an admin sees every issue of every company, private ones too")
    void adminSeesEverything() {
        World w = world();
        Session admin = w.a().admin();

        assertThat(keys(list(admin))).contains(w.alicePublic().key(), w.alicePrivate().key(), w.bobPublic().key(),
                w.bobPrivate().key(), w.carolPublic().key());
        assertThat(detail(admin, w.bobPrivate()).status()).isEqualTo(200);
        assertThat(detail(admin, w.carolPublic()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("S0-AC-47 lists are newest first for customers, agents, and admins")
    void newestFirst() {
        World w = world();
        Session agent = fixtures.agent(w.a().admin(), w.a().code());

        assertThat(keys(list(w.alice())))
                .containsExactly(w.bobPublic().key(), w.alicePrivate().key(), w.alicePublic().key());
        assertThat(keys(list(agent))).containsExactly(w.bobPrivate().key(), w.bobPublic().key(),
                w.alicePrivate().key(), w.alicePublic().key());
        assertThat(keys(list(w.a().admin())).subList(0, 5)).containsExactly(w.carolPublic().key(),
                w.bobPrivate().key(), w.bobPublic().key(), w.alicePrivate().key(), w.alicePublic().key());
    }

    @Test
    @DisplayName("S0-AC-47 the list is split into pages with page, size, and totals")
    void pages() {
        World w = world();
        ApiClient alice = w.alice().client();

        ApiResponse first = alice.get("/api/portal/issues").query("page", 1).query("size", 2).expect(200);
        ApiResponse second = alice.get("/api/portal/issues").query("page", 2).query("size", 2).expect(200);
        ApiResponse past = alice.get("/api/portal/issues").query("page", 3).query("size", 2).expect(200);
        ApiResponse defaults = alice.get("/api/portal/issues").expect(200);

        assertThat(keys(first)).containsExactly(w.bobPublic().key(), w.alicePrivate().key());
        assertThat(keys(second)).containsExactly(w.alicePublic().key());
        assertThat(items(past)).isEmpty();
        JsonNode page = first.json();
        assertThat(page.path("page").asInt()).isEqualTo(1);
        assertThat(page.path("size").asInt()).isEqualTo(2);
        assertThat(page.path("totalElements").asLong()).isEqualTo(3);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        assertThat(second.json().path("page").asInt()).isEqualTo(2);
        assertThat(defaults.json().path("page").asInt()).isEqualTo(1);
        assertThat(defaults.json().path("size").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("S0-AC-47 a page below 1 or a size outside 1 to 100 is 400 OUT_OF_RANGE on both apps")
    void pageOutOfRange() {
        Scene s = steps.scene();

        for (Session session : List.of(s.customer(), s.admin())) {
            ApiResponse page0 = session.client().get(session.api("/issues")).query("page", 0).send();
            ApiResponse size0 = session.client().get(session.api("/issues")).query("size", 0).send();
            ApiResponse size101 = session.client().get(session.api("/issues")).query("size", 101).send();
            assertThat(page0.status()).as(page0.request()).isEqualTo(400);
            assertThat(fieldCode(page0, "page")).isEqualTo("OUT_OF_RANGE");
            assertThat(size0.status()).as(size0.request()).isEqualTo(400);
            assertThat(fieldCode(size0, "size")).isEqualTo("OUT_OF_RANGE");
            assertThat(size101.status()).as(size101.request()).isEqualTo(400);
            assertThat(size101.errorCode()).isEqualTo("INVALID_INPUT");
            assertThat(fieldCode(size101, "size")).isEqualTo("OUT_OF_RANGE");
        }
        assertThat(s.customer().client().get("/api/portal/issues").query("size", 100).send().status())
                .isEqualTo(200);
    }

    // S0-AC-48

    @Test
    @DisplayName("S0-AC-48 staff-only parameters on the customer list are 400 UNKNOWN_PARAMETER")
    void staffOnlyParametersAreRejected() {
        Scene s = steps.scene();
        fixtures.fileIssue(s.customer(), s.code());

        for (String[] parameter : new String[][] {
                {"assignee", Long.toString(s.admin().accountId())},
                {"label", "bug"},
                {"sla", "breached"},
                {"sort", "createdAt,asc"}}) {
            ApiResponse rejected = s.customer().client().get("/api/portal/issues")
                    .query(parameter[0], parameter[1])
                    .send();
            assertThat(rejected.status()).as(rejected.request()).isEqualTo(400);
            assertThat(rejected.errorCode()).as(rejected.request()).isEqualTo("INVALID_INPUT");
            assertThat(fieldCode(rejected, parameter[0])).as(rejected.request()).isEqualTo("UNKNOWN_PARAMETER");
        }
        assertThat(keys(s.customer().client().get("/api/portal/issues").query("page", 1).query("size", 20)
                .expect(200))).hasSize(1);
    }

    @Test
    @DisplayName("S0-AC-48 a staff-only parameter next to page and size is still 400")
    void staffOnlyParameterWithPaging() {
        Scene s = steps.scene();

        ApiResponse rejected = s.customer().client().get("/api/portal/issues")
                .query("page", 1).query("size", 20).query("assignee", s.admin().accountId())
                .send();

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(fieldCode(rejected, "assignee")).isEqualTo("UNKNOWN_PARAMETER");
        assertThat(text(rejected.json(), "code")).isEqualTo("INVALID_INPUT");
    }
}
