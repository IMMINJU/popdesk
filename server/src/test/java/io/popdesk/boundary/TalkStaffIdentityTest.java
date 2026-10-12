package io.popdesk.boundary;

import static io.popdesk.boundary.BoundaryChecks.allPages;
import static io.popdesk.boundary.BoundaryChecks.awaitNotifications;
import static io.popdesk.boundary.BoundaryChecks.everything;
import static io.popdesk.boundary.BoundaryChecks.letters;
import static io.popdesk.boundary.BoundaryChecks.notifications;
import static io.popdesk.boundary.BoundaryChecks.placesOfAccountId;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.boundary.BoundaryChecks.Pages;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.TalkFlow;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.NewIssue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.ReceivedMail;
import io.popdesk.support.Unique;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Staff stay "Support team" to customers in what slice 4 adds (S4-AC-71, ANON-1, ADR D-49), over one
 * issue from end to end: an agent answers in public and mentions the customer, edits the answer,
 * resolves the issue, reopens it, and closes it, an admin answers too and then deletes the agent's
 * answer. Nothing the customer gets about it holds the name, email, or account ID of the agent or the
 * admin: the portal's comment list, inbox, and unread count, and every mail.
 *
 * <p>Searched the way {@link StaffIdentityTest} does. In the inbox an item's own {@code id} and its
 * {@code commentId} are numbers that can equal an account ID by chance, so those two fields are left
 * out there; the unread count is one number that counts and is compared with the list.
 */
class TalkStaffIdentityTest extends GatedMailTest {

    /** Fields of an inbox page whose numbers name an item or a comment or count something, never a person. */
    private static final Set<String> NOT_ACCOUNTS = Set.of("id", "commentId", "page", "size", "totalElements",
            "totalPages");

    /** A signed-in person named {@code "<name> <letters>"}, with an email made of the same words. */
    private Session person(Session admin, Role role, String name, String companyCode) {
        String unique = name + " " + letters(Unique.number());
        String email = unique.toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.test";
        return fixtures.accept(fixtures.invite(admin, role, email, unique,
                companyCode == null ? List.of() : List.of(companyCode)));
    }

    private static List<String> kinds(List<JsonNode> items) {
        return items.stream().map(n -> n.path("kind").asString(null)).toList();
    }

    @Test
    @DisplayName("S4-AC-71 after an agent answered with a mention of the customer, edited the answer, resolved, "
            + "reopened, and closed the issue, and an admin answered and deleted the agent's answer, the "
            + "customer's comment list, inbox, unread count, and every mail hold no name, email, or account ID "
            + "of the agent or the admin")
    void answersAndMailsOfOneIssueHoldNoStaff() {
        IssueFlow flow = new IssueFlow(fixtures, clock);
        Session first = flow.admin();
        Company company = fixtures.createCompany(first);
        Session customer = person(first, Role.CUSTOMER, "Brindlemoss Okafor", company.code());
        Session agent = person(first, Role.AGENT, "Quorrin Vasheld", null);
        fixtures.assignAgent(first, company.code(), agent.accountId());
        Session admin = person(first, Role.ADMIN, "Thessaly Brumwick", null);
        List<Session> staff = List.of(agent, admin);
        // A title without digits, so no number in a mail subject can be an account ID by chance.
        String key = fixtures.fileIssue(customer, NewIssue.in(company.code())
                .title("Scanner stops after one page " + letters(Unique.number()))).key();
        fixtures.comment(customer, key, "Question, " + letters(Unique.number()));

        Comment answer = TalkFlow.mention(agent, key, false, List.of(customer.accountId()));
        TalkFlow.editContent(agent, key, answer.id(), "Answer, edited, " + letters(Unique.number()));
        Comment kept = fixtures.comment(admin, key, "Answer from the admin, " + letters(Unique.number()));
        flow.changeStatus(agent, key, "IN_PROGRESS");
        flow.changeStatus(agent, key, "RESOLVED");
        flow.reopen(agent, key);
        flow.changeStatus(admin, key, "CLOSED");
        // The mention, the admin's answer, resolved, reopened, closed: five mails, all out before the deletion.
        awaitNotifications(mail(), customer.email(), 5);
        Pages inboxBefore = allPages(customer, "/api/portal/notifications");
        TalkFlow.delete(admin, key, answer.id());
        // A mail queued after everything above; once it is here, no mail about the issue is still to come.
        fixtures.invite(first, Role.AGENT);

        Pages comments = allPages(customer, "/api/portal/issues/" + key + "/comments");
        Pages inbox = allPages(customer, "/api/portal/notifications");
        ApiResponse unread = customer.client().get("/api/portal/notifications/unread-count").expect(200);
        List<ReceivedMail> mails = notifications(mail(), customer.email());

        // The answers are not empty: the customer is named, staff answers are there, and so are the items.
        assertThat(comments.text()).as("the customer's own name in the comment list").contains(customer.name());
        assertThat(comments.items()).as("the deleted answer of the agent in the comment list")
                .filteredOn(n -> n.path("id").asLong(-1) == answer.id()).singleElement()
                .satisfies(n -> {
                    assertThat(n.path("deleted").asBoolean(false)).as("deleted in %s", n).isTrue();
                    assertThat(n.path("author").path("kind").asString(null)).isEqualTo("SUPPORT_TEAM");
                    assertThat(n.has("deletedBy")).as("the field deletedBy in %s", n).isFalse();
                });
        assertThat(comments.items()).as("the admin's answer in the comment list")
                .filteredOn(n -> n.path("id").asLong(-1) == kept.id()).singleElement()
                .satisfies(n -> assertThat(n.path("author").path("kind").asString(null)).isEqualTo("SUPPORT_TEAM"));
        assertThat(kinds(inboxBefore.items())).as("kinds of the customer's items before the deletion")
                .containsExactlyInAnyOrder("MENTIONED", "STAFF_COMMENT", "ISSUE_RESOLVED", "ISSUE_REOPENED",
                        "ISSUE_CLOSED");
        assertThat(kinds(inbox.items())).as("kinds of the customer's items after the deletion")
                .containsExactlyInAnyOrder("STAFF_COMMENT", "ISSUE_RESOLVED", "ISSUE_REOPENED", "ISSUE_CLOSED");
        assertThat(inbox.items()).as("who did it, in the customer's items")
                .allSatisfy(n -> assertThat(n.path("actor").path("kind").asString(null)).as("actor of %s", n)
                        .isEqualTo("SUPPORT_TEAM"));
        assertThat(Set.copyOf(unread.json().propertyNames())).as("fields of %s", unread.snippet())
                .isEqualTo(Set.of("count"));
        assertThat(unread.jsonLong("count")).as("unread count").isEqualTo(inbox.items().size());
        assertThat(mails).as("mails to the customer about the issue").hasSizeGreaterThanOrEqualTo(5)
                .allSatisfy(m -> assertThat(m.subject() + "\n" + m.text()).contains(key));

        SoftAssertions softly = new SoftAssertions();
        for (Session s : staff) {
            String who = s.role().name();
            for (ApiResponse page : comments.responses()) {
                softly.assertThat(placesOfAccountId(page.json(), s.accountId()))
                        .as("comment list: account ID %s of %s", s.accountId(), who).isEmpty();
            }
            for (Pages pages : List.of(inboxBefore, inbox)) {
                for (ApiResponse page : pages.responses()) {
                    softly.assertThat(TalkFlow.placesOfNumber(page.json(), s.accountId(), NOT_ACCOUNTS))
                            .as("inbox: account ID %s of %s", s.accountId(), who).isEmpty();
                }
            }
            for (String body : List.of(comments.text(), inboxBefore.text(), inbox.text(), unread.text())) {
                softly.assertThat(body).as("name of %s", who).doesNotContainIgnoringCase(s.name());
                softly.assertThat(body).as("email of %s", who).doesNotContainIgnoringCase(s.email());
            }
            for (ReceivedMail m : mails) {
                String all = everything(m);
                softly.assertThat(all).as("mail '%s': name of %s", m.subject(), who)
                        .doesNotContainIgnoringCase(s.name());
                softly.assertThat(all).as("mail '%s': email of %s", m.subject(), who)
                        .doesNotContainIgnoringCase(s.email());
                softly.assertThat(placesOfAccountId(m, s.accountId()))
                        .as("mail '%s': account ID %s of %s", m.subject(), s.accountId(), who).isEmpty();
            }
        }
        softly.assertAll();
    }
}
