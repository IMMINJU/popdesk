package io.popdesk.project;

import static io.popdesk.project.PeopleSteps.agentOf;
import static io.popdesk.project.PeopleSteps.customerOf;
import static io.popdesk.project.PeopleSteps.members;
import static io.popdesk.project.ProjectChecks.allItems;
import static io.popdesk.project.ProjectChecks.allValues;
import static io.popdesk.project.ProjectChecks.assertError;
import static io.popdesk.project.ProjectChecks.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Adding customers to a company and taking customers and agents out of it. What the person sees is
 * read with the session they already had, in the request right after the change.
 */
class MembershipTest extends BareAppTest {

    /**
     * Company {@code a} with customer {@code leaver} and a colleague, an agent, and three issues with a
     * comment and a file each: the leaver's own, the colleague's public one, the colleague's private one.
     * Company {@code b} is another company the leaver belongs to.
     */
    private record Scene(Session admin, Company a, Company b, Session leaver, Session colleague, Session agent,
            Issue own, Comment ownComment, Attachment ownFile, Issue ofColleague, Attachment colleagueFile,
            Issue privateOfColleague) {}

    private Scene scene() {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session leaver = fixtures.customer(admin, a.code(), b.code());
        Session colleague = fixtures.customer(admin, a.code());
        Session agent = fixtures.agent(admin, a.code(), b.code());
        Issue own = fixtures.fileIssue(leaver, a.code());
        Comment ownComment = fixtures.comment(leaver, own.key(), "A comment of the customer who leaves");
        Attachment ownFile = fixtures.attach(leaver, own.key(), File.text("own.txt", "the leaver's file"));
        Issue ofColleague = fixtures.fileIssue(colleague, a.code());
        Attachment colleagueFile = fixtures.attach(colleague, ofColleague.key(),
                File.text("colleague.txt", "the colleague's file"));
        Issue privateOfColleague = fixtures.fileIssue(colleague, NewIssue.in(a.code()).privateIssue());
        return new Scene(admin, a, b, leaver, colleague, agent, own, ownComment, ownFile, ofColleague, colleagueFile,
                privateOfColleague);
    }

    private static String portalIssue(Issue issue) {
        return "/api/portal/issues/" + issue.key();
    }

    private static String consoleIssue(Issue issue) {
        return "/api/console/issues/" + issue.key();
    }

    // S1A-AC-50

    @Test
    @DisplayName("S1A-AC-50 a customer added to a company sees it and its public issues from the next request, not "
            + "its private ones; adding again is 200 and lists the customer once")
    void addedCustomerSeesTheCompany() {
        Scene s = scene();
        Session newcomer = fixtures.customer(s.admin(), s.b().code());
        assertError(newcomer.client().get(portalIssue(s.ofColleague())).send(), 404, "NOT_FOUND");

        ApiResponse added = s.admin().client().put(customerOf(s.a().code(), newcomer.accountId())).expect(200);

        assertThat(added.jsonLong("accountId")).isEqualTo(newcomer.accountId());
        assertThat(added.jsonText("email")).isEqualTo(newcomer.email());
        assertThat(added.jsonText("status")).isEqualTo("ACTIVE");
        assertThat(allValues(newcomer.client(), "/api/portal/companies", "code")).contains(s.a().code(),
                s.b().code());
        assertThat(newcomer.client().get(portalIssue(s.ofColleague())).expect(200).jsonText("key"))
                .isEqualTo(s.ofColleague().key());
        assertThat(allValues(newcomer.client(), "/api/portal/issues", "key")).contains(s.ofColleague().key(),
                s.own().key()).doesNotContain(s.privateOfColleague().key());
        assertError(newcomer.client().get(portalIssue(s.privateOfColleague())).send(), 404, "NOT_FOUND");
        fixtures.fileIssue(newcomer, s.a().code());

        ApiResponse again = s.admin().client().put(customerOf(s.a().code(), newcomer.accountId())).expect(200);

        assertThat(again.jsonLong("accountId")).isEqualTo(newcomer.accountId());
        assertThat(members(s.admin(), s.a().code(), "customers")).filteredOn(id -> id == newcomer.accountId())
                .hasSize(1);
    }

    @Test
    @DisplayName("S1A-AC-50 a customer added before accepting the invitation sees the company once signed in")
    void invitedCustomerCanBeAdded() {
        Scene s = scene();
        Invitation invitation = fixtures.invite(s.admin(), Role.CUSTOMER, s.b().code());

        ApiResponse added = s.admin().client().put(customerOf(s.a().code(), invitation.accountId())).expect(200);

        assertThat(added.jsonLong("accountId")).isEqualTo(invitation.accountId());
        assertThat(added.jsonText("status")).isEqualTo("INVITED");
        Session newcomer = fixtures.accept(invitation);
        assertThat(allValues(newcomer.client(), "/api/portal/companies", "code")).contains(s.a().code(),
                s.b().code());
        newcomer.client().get(portalIssue(s.ofColleague())).expect(200);
    }

    // S1A-AC-51

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"AGENT", "ADMIN"})
    @DisplayName("S1A-AC-51 adding an agent or an admin as a company's customer is 400 with field accountId "
            + "UNKNOWN_VALUE and adds nobody")
    void onlyCustomersBelong(Role role) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session staff = role == Role.AGENT ? fixtures.agent(admin) : fixtures.admin(admin);

        ApiResponse response = admin.client().put(customerOf(company.code(), staff.accountId())).send();

        assertFieldError(response, 400, "accountId", "UNKNOWN_VALUE");
        assertThat(members(admin, company.code(), "customers")).doesNotContain(staff.accountId());
    }

    @Test
    @DisplayName("S1A-AC-50 adding to a company or an account that does not exist is 404 NOT_FOUND")
    void unknownCompanyOrAccount() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session customer = fixtures.customer(admin, company.code());

        assertError(admin.client().put(customerOf(company.code(), 999_999_999L)).send(), 404, "NOT_FOUND");
        assertError(admin.client().put(customerOf("NOSUCH9", customer.accountId())).send(), 404, "NOT_FOUND");
    }

    // S1A-AC-52

    @Test
    @DisplayName("S1A-AC-52 a customer taken out of a company no longer sees it or colleagues' issues from the next "
            + "request (404, not listed); taking out again is 200")
    void removedCustomerLosesTheCompany() {
        Scene s = scene();
        s.leaver().client().get(portalIssue(s.ofColleague())).expect(200);

        ApiResponse removed = s.admin().client().delete(customerOf(s.a().code(), s.leaver().accountId())).expect(200);

        assertThat(removed.body()).as("body of the 200").isEmpty();
        assertThat(allValues(s.leaver().client(), "/api/portal/companies", "code")).contains(s.b().code())
                .doesNotContain(s.a().code());
        assertError(s.leaver().client().get(portalIssue(s.ofColleague())).send(), 404, "NOT_FOUND");
        assertError(s.leaver().client().get(portalIssue(s.ofColleague()) + "/comments").send(), 404, "NOT_FOUND");
        assertError(s.leaver().client().get(portalIssue(s.ofColleague()) + "/attachments/" + s.colleagueFile().id())
                .send(), 404, "NOT_FOUND");
        assertThat(allValues(s.leaver().client(), "/api/portal/issues", "key")).doesNotContain(s.ofColleague().key());
        assertThat(members(s.admin(), s.a().code(), "customers")).contains(s.colleague().accountId())
                .doesNotContain(s.leaver().accountId());

        s.admin().client().delete(customerOf(s.a().code(), s.leaver().accountId())).expect(200);

        s.colleague().client().get(portalIssue(s.ofColleague())).expect(200);
    }

    // S1A-AC-53

    @Test
    @DisplayName("S1A-AC-53 a customer taken out of a company still reads the issue they filed there: detail, "
            + "comments, and file download")
    void removedCustomerStillReadsOwnIssue() {
        Scene s = scene();
        Comment answer = fixtures.comment(s.agent(), s.own().key(), "An answer from the agent");

        PeopleSteps.removeCustomer(s.admin(), s.a().code(), s.leaver().accountId());

        assertThat(s.leaver().client().get(portalIssue(s.own())).expect(200).jsonText("key"))
                .isEqualTo(s.own().key());
        assertThat(allValues(s.leaver().client(), "/api/portal/issues", "key")).contains(s.own().key());
        assertThat(allItems(s.leaver().client(), portalIssue(s.own()) + "/comments").stream()
                .map(n -> n.path("id").asLong(-1))).contains(s.ownComment().id(), answer.id());
        assertThat(allValues(s.leaver().client(), portalIssue(s.own()) + "/attachments", "id"))
                .contains(s.ownFile().id());
        assertThat(s.leaver().client().get(portalIssue(s.own()) + "/attachments/" + s.ownFile().id()).expect(200)
                .body()).isEqualTo(s.ownFile().content());
    }

    // S1A-AC-54

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"comment", "attach", "delete"})
    @DisplayName("S1A-AC-54 a customer taken out of a company gets 403 FORBIDDEN when commenting on, attaching to, "
            + "or deleting a file of the issue they filed there")
    void removedCustomerCannotWriteToOwnIssue(String action) {
        Scene s = scene();
        PeopleSteps.removeCustomer(s.admin(), s.a().code(), s.leaver().accountId());
        String issue = portalIssue(s.own());

        ApiResponse response = switch (action) {
            case "comment" -> s.leaver().client().post(issue + "/comments")
                    .json(Map.of("content", "A comment after leaving")).send();
            case "attach" -> s.leaver().client().post(issue + "/attachments")
                    .file("file", "after.txt", "text/plain", "after leaving".getBytes()).send();
            default -> s.leaver().client().delete(issue + "/attachments/" + s.ownFile().id()).send();
        };

        assertError(response, 403, "FORBIDDEN");
        assertThat(allItems(s.agent().client(), consoleIssue(s.own()) + "/comments")).as("comments of the issue")
                .hasSize(1);
        List<String> files = allValues(s.agent().client(), consoleIssue(s.own()) + "/attachments", "id");
        assertThat(files).as("files of the issue").containsExactly(s.ownFile().id());
        assertThat(s.leaver().client().get(issue + "/attachments/" + s.ownFile().id()).expect(200).body())
                .isEqualTo(s.ownFile().content());
    }

    // S1A-AC-55

    @Test
    @DisplayName("S1A-AC-55 an agent taken off a company gets 404 for its issues, comments, and files from the next "
            + "request, and they leave the lists; taking off again is 200")
    void unassignedAgentLosesTheCompany() {
        Scene s = scene();
        Issue ofB = fixtures.fileIssue(s.leaver(), s.b().code());
        s.agent().client().get(consoleIssue(s.own())).expect(200);

        ApiResponse removed = s.admin().client().delete(agentOf(s.a().code(), s.agent().accountId())).expect(200);

        assertThat(removed.body()).as("body of the 200").isEmpty();
        String issue = consoleIssue(s.own());
        assertError(s.agent().client().get(issue).send(), 404, "NOT_FOUND");
        assertError(s.agent().client().get(issue + "/comments").send(), 404, "NOT_FOUND");
        assertError(s.agent().client().get(issue + "/attachments").send(), 404, "NOT_FOUND");
        assertError(s.agent().client().get(issue + "/attachments/" + s.ownFile().id()).send(), 404, "NOT_FOUND");
        assertError(s.agent().client().post(issue + "/comments").json(Map.of("content", "After leaving")).send(),
                404, "NOT_FOUND");
        assertError(s.agent().client().post(issue + "/attachments")
                .file("file", "after.txt", "text/plain", "after leaving".getBytes()).send(), 404, "NOT_FOUND");
        assertThat(allValues(s.agent().client(), "/api/console/issues", "key")).contains(ofB.key())
                .doesNotContain(s.own().key(), s.ofColleague().key(), s.privateOfColleague().key());
        assertThat(allValues(s.agent().client(), "/api/console/companies", "code")).contains(s.b().code())
                .doesNotContain(s.a().code());
        assertThat(members(s.admin(), s.a().code(), "agents")).doesNotContain(s.agent().accountId());
        s.agent().client().get(consoleIssue(ofB)).expect(200);

        s.admin().client().delete(agentOf(s.a().code(), s.agent().accountId())).expect(200);

        assertThat(allItems(s.admin().client(), issue + "/comments")).as("comments of the issue").hasSize(1);
    }

    // S1A-AC-56

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"add customer", "remove customer", "remove agent"})
    @DisplayName("S1A-AC-56 an agent adding or taking out a customer, or taking off an agent, gets 403 FORBIDDEN and "
            + "the members stay as they were")
    void agentsCannotChangeMembers(String action) {
        Scene s = scene();
        Session outsider = fixtures.customer(s.admin(), s.b().code());

        ApiResponse response = switch (action) {
            case "add customer" -> s.agent().client().put(customerOf(s.a().code(), outsider.accountId())).send();
            case "remove customer" -> s.agent().client().delete(customerOf(s.a().code(), s.leaver().accountId()))
                    .send();
            default -> s.agent().client().delete(agentOf(s.a().code(), s.agent().accountId())).send();
        };

        assertError(response, 403, "FORBIDDEN");
        assertThat(members(s.admin(), s.a().code(), "customers")).contains(s.leaver().accountId(),
                s.colleague().accountId()).doesNotContain(outsider.accountId());
        assertThat(members(s.admin(), s.a().code(), "agents")).contains(s.agent().accountId());
        s.leaver().client().get(portalIssue(s.ofColleague())).expect(200);
    }
}
