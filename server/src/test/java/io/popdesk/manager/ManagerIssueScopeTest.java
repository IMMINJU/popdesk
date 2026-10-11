package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.assertError;
import static io.popdesk.manager.ManagerSteps.mark;
import static io.popdesk.manager.ManagerSteps.members;
import static io.popdesk.manager.ManagerSteps.pages;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Attachment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Being a manager widens the member endpoints only: the issues a manager sees stay the same. */
class ManagerIssueScopeTest extends BareAppTest {

    private static List<String> issueKeys(Session customer) {
        return pages(customer.client(), "/api/portal/issues").stream().map(i -> i.path("key").asString(null))
                .toList();
    }

    @Test
    @DisplayName("S2-AC-44 a manager sees the same issues as before being marked: a colleague's private issue "
            + "is not listed, its detail, comments, and files are 404 NOT_FOUND, and so is commenting on it")
    void managerSeesNoMoreIssues() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        Session person = fixtures.customer(admin, company.code());
        Session colleague = fixtures.customer(admin, company.code());
        Session outsider = fixtures.customer(admin, other.code());
        Issue own = fixtures.fileIssue(person, company.code());
        Issue ofColleague = fixtures.fileIssue(colleague, company.code());
        Issue secret = fixtures.fileIssue(colleague, NewIssue.in(company.code()).privateIssue());
        fixtures.comment(colleague, secret.key(), "A comment on the private issue");
        Attachment secretFile = fixtures.attach(colleague, secret.key(), File.text("secret.txt", "private file"));
        Issue ofOutsider = fixtures.fileIssue(outsider, other.code());
        List<String> before = issueKeys(person);

        mark(admin, company.code(), person.accountId());

        person.client().get(members(company.code())).expect(200);
        assertThat(issueKeys(person)).isEqualTo(before).contains(own.key(), ofColleague.key())
                .doesNotContain(secret.key(), ofOutsider.key());
        String path = "/api/portal/issues/" + secret.key();
        assertError(person.client().get(path).send(), 404, "NOT_FOUND");
        assertError(person.client().get(path + "/comments").send(), 404, "NOT_FOUND");
        assertError(person.client().get(path + "/attachments").send(), 404, "NOT_FOUND");
        assertError(person.client().get(path + "/attachments/" + secretFile.id()).send(), 404, "NOT_FOUND");
        assertError(person.client().post(path + "/comments").json(Map.of("content", "A manager's comment")).send(),
                404, "NOT_FOUND");
        assertThat(colleague.client().get(path + "/comments").expect(200).json().path("totalElements").asLong(-1))
                .as("comments of the private issue").isEqualTo(1);
        assertError(person.client().get("/api/portal/issues/" + ofOutsider.key()).send(), 404, "NOT_FOUND");
    }
}
