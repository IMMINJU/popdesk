package io.popdesk.boundary;

import static io.popdesk.boundary.BoundaryChecks.allPages;
import static io.popdesk.boundary.BoundaryChecks.awaitNotifications;
import static io.popdesk.boundary.BoundaryChecks.everything;
import static io.popdesk.boundary.BoundaryChecks.letters;
import static io.popdesk.boundary.BoundaryChecks.placesOfAccountId;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.boundary.BoundaryChecks.Pages;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Staff stay "Support team" to customers (S0-AC-98, ANON-1, ADR D-49): an assigned agent and an
 * admin comment in public and attach files on a customer's issue, and the admin deletes one file.
 * Nothing the customer gets on the portal (issue list and detail, comment list, attachment list,
 * company list) or by mail holds their name, email, or account ID.
 *
 * <p>Names and emails are searched as text, ignoring case; staff names are made of words no other
 * value uses. Account IDs are short numbers that other fields can hold by chance, so they are
 * searched in the parsed JSON, value by value ({@link BoundaryChecks#placesOfAccountId(JsonNode,
 * long)}), and in mail as numbers standing alone ({@link BoundaryChecks#placesOfAccountId(ReceivedMail,
 * long)}). A colleague's name must show where the portal names customers, so the searches are not
 * looking at empty answers.
 */
class StaffIdentityTest extends GatedMailTest {

    /** The people and data of this class, made once. */
    private record Scene(Company company, Session customer, Session colleague, Session agent, Session admin,
            Issue issue, Issue colleagueIssue, List<String> staffFileIds, Attachment deletedFile) {

        List<Session> staff() {
            return List.of(agent, admin);
        }
    }

    private static Scene scene;

    private Scene s;

    @BeforeEach
    void setUp() {
        if (scene == null) {
            scene = build();
        }
        s = scene;
    }

    private Scene build() {
        Session first = fixtures.signInAdmin();
        Company company = fixtures.createCompany(first);
        Session customer = person(first, Role.CUSTOMER, "Customer", company.code());
        Session colleague = person(first, Role.CUSTOMER, "Brindlemoss Okafor", company.code());
        Session agent = person(first, Role.AGENT, "Zyllith Quarnby", null);
        fixtures.assignAgent(first, company.code(), agent.accountId());
        Session admin = person(first, Role.ADMIN, "Vexmorrow Tallisk", null);

        Issue issue = fixtures.fileIssue(customer, NewIssue.in(company.code()).title(title())
                .file(File.text("filed-with-issue.txt", "Filed with the issue")));
        Attachment toDelete = fixtures.attach(customer, issue.key(), File.text("to-delete.txt", "Deleted by admin"));
        List<String> staffFiles = new ArrayList<>();
        staffFiles.add(fixtures.attach(agent, issue.key(), File.text("agent-on-issue.txt", "From the agent")).id());
        for (Session staff : List.of(agent, admin)) {
            Comment answer = fixtures.comment(staff, issue.key(), "Answer from support, " + letters(Unique.number()));
            staffFiles.add(fixtures.attachToComment(staff, issue.key(), answer.id(),
                    File.text("answer-file.txt", "Attached to the answer")).id());
        }
        admin.client().delete(admin.api("/issues/" + issue.key() + "/attachments/" + toDelete.id())).expect(204);

        Issue colleagueIssue = fixtures.fileIssue(colleague, NewIssue.in(company.code()).title(title()));
        fixtures.comment(colleague, colleagueIssue.key(), "Colleague follow-up, " + letters(Unique.number()));
        fixtures.attach(colleague, colleagueIssue.key(), File.text("colleague.txt", "From the colleague"));
        fixtures.comment(agent, colleagueIssue.key(), "Answer to the colleague, " + letters(Unique.number()));
        return new Scene(company, customer, colleague, agent, admin, issue, colleagueIssue, List.copyOf(staffFiles),
                toDelete);
    }

    /** A signed-in person named {@code "<name> <letters>"}, with an email made of the same words. */
    private Session person(Session admin, Role role, String name, String companyCode) {
        String unique = name + " " + letters(Unique.number());
        String email = unique.toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.test";
        return fixtures.accept(fixtures.invite(admin, role, email, unique,
                companyCode == null ? List.of() : List.of(companyCode)));
    }

    /** A title without digits, so no number in a mail subject can be an account ID by chance. */
    private static String title() {
        return "Printer jams on page feed " + letters(Unique.number());
    }

    /** Checks one portal answer: no staff name or email as text, no staff account ID as a JSON value. */
    private void assertNoStaff(SoftAssertions softly, String what, String body, List<JsonNode> json) {
        for (Session staff : s.staff()) {
            softly.assertThat(body).as(what + ": name of " + staff.role()).doesNotContainIgnoringCase(staff.name());
            softly.assertThat(body).as(what + ": email of " + staff.role()).doesNotContainIgnoringCase(staff.email());
            for (JsonNode node : json) {
                softly.assertThat(placesOfAccountId(node, staff.accountId()))
                        .as(what + ": account ID " + staff.accountId() + " of " + staff.role()).isEmpty();
            }
        }
    }

    private void assertNoStaff(SoftAssertions softly, String what, Pages pages) {
        assertNoStaff(softly, what, pages.text(), pages.responses().stream().map(ApiResponse::json).toList());
    }

    private void assertNoStaff(SoftAssertions softly, String what, ApiResponse response) {
        assertNoStaff(softly, what, response.text(), List.of(response.json()));
    }

    private String issuePath(String key) {
        return "/api/portal/issues/" + key;
    }

    @Test
    @DisplayName("S0-AC-98 the customer's issue list and issue details hold no name, email, or account ID of the agent or admin")
    void issuesHoldNoStaff() {
        Pages list = allPages(s.customer(), "/api/portal/issues");
        ApiResponse own = s.customer().client().get(issuePath(s.issue().key())).expect(200);
        ApiResponse colleagues = s.customer().client().get(issuePath(s.colleagueIssue().key())).expect(200);

        assertThat(list.texts("key")).contains(s.issue().key(), s.colleagueIssue().key());
        assertThat(colleagues.json().path("reporter").path("name").asString(null)).as("reporter of the colleague's issue")
                .isEqualTo(s.colleague().name());
        assertThat(list.text()).as("the colleague's name in the list").contains(s.colleague().name());
        SoftAssertions softly = new SoftAssertions();
        assertNoStaff(softly, "issue list", list);
        assertNoStaff(softly, "own issue", own);
        assertNoStaff(softly, "colleague's issue", colleagues);
        softly.assertAll();
    }

    @Test
    @DisplayName("S0-AC-98 the customer's comment lists show staff answers as the support team, without their name, email, or account ID")
    void commentsHoldNoStaff() {
        Pages own = allPages(s.customer(), issuePath(s.issue().key()) + "/comments");
        Pages colleagues = allPages(s.customer(), issuePath(s.colleagueIssue().key()) + "/comments");

        assertThat(own.items()).as("staff answers the customer sees")
                .filteredOn(n -> "SUPPORT_TEAM".equals(n.path("author").path("kind").asString(null))).hasSize(2);
        assertThat(colleagues.items()).as("authors of comments on the colleague's issue")
                .anySatisfy(n -> assertThat(n.path("author").path("name").asString(null))
                        .isEqualTo(s.colleague().name()));
        SoftAssertions softly = new SoftAssertions();
        assertNoStaff(softly, "comments on own issue", own);
        assertNoStaff(softly, "comments on the colleague's issue", colleagues);
        softly.assertAll();
    }

    @Test
    @DisplayName("S0-AC-98 the customer's attachment lists hold no name, email, or account ID of staff who uploaded or deleted")
    void attachmentsHoldNoStaff() {
        Pages own = allPages(s.customer(), issuePath(s.issue().key()) + "/attachments");
        Pages colleagues = allPages(s.customer(), issuePath(s.colleagueIssue().key()) + "/attachments");

        assertThat(own.texts("id")).as("files the customer sees")
                .containsAll(s.staffFileIds()).contains(s.deletedFile().id());
        assertThat(own.items()).as("the file the admin deleted")
                .filteredOn(n -> s.deletedFile().id().equals(n.path("id").asString(null))).singleElement()
                .satisfies(n -> assertThat(n.path("deletedBy").path("kind").asString(null)).isEqualTo("SUPPORT_TEAM"));
        assertThat(colleagues.text()).as("the colleague's name as uploader").contains(s.colleague().name());
        SoftAssertions softly = new SoftAssertions();
        assertNoStaff(softly, "attachments of own issue", own);
        assertNoStaff(softly, "attachments of the colleague's issue", colleagues);
        softly.assertAll();
    }

    @Test
    @DisplayName("S0-AC-98 the customer's company list holds no name, email, or account ID of staff")
    void companiesHoldNoStaff() {
        Pages companies = allPages(s.customer(), "/api/portal/companies");

        assertThat(companies.texts("code")).contains(s.company().code());
        SoftAssertions softly = new SoftAssertions();
        assertNoStaff(softly, "company list", companies);
        softly.assertAll();
    }

    @Test
    @DisplayName("S0-AC-98 the mails the customer gets for the agent's and the admin's public answers hold no name, email, or account ID of them")
    void mailHoldsNoStaff() {
        for (Session staff : s.staff()) {
            fixtures.comment(staff, s.issue().key(), "Another answer, " + letters(Unique.number()));
        }

        List<ReceivedMail> mails = awaitNotifications(mail(), s.customer().email(), 2);

        SoftAssertions softly = new SoftAssertions();
        for (ReceivedMail m : mails) {
            softly.assertThat(m.text()).as("mail about the issue").contains(s.issue().key());
            String all = everything(m);
            for (Session staff : s.staff()) {
                softly.assertThat(all).as("mail: name of " + staff.role()).doesNotContainIgnoringCase(staff.name());
                softly.assertThat(all).as("mail: email of " + staff.role()).doesNotContainIgnoringCase(staff.email());
                softly.assertThat(placesOfAccountId(m, staff.accountId()))
                        .as("mail: account ID " + staff.accountId() + " of " + staff.role()).isEmpty();
            }
        }
        softly.assertAll();
    }
}
