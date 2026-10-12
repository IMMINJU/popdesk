package io.popdesk.boundary;

import static io.popdesk.boundary.BoundaryChecks.allPages;
import static io.popdesk.boundary.BoundaryChecks.letters;
import static io.popdesk.boundary.BoundaryChecks.placesOfAccountId;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.boundary.BoundaryChecks.Pages;
import io.popdesk.issue.IssueFlow;
import io.popdesk.issue.IssueFlow.OnBehalf;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The agent who filed an issue for a customer and the admin assigned to it stay unknown to customers
 * (ISSUE-8, ISSUE-9, ANON-1): nothing the portal answers about that issue holds their name, email, or
 * account ID. Searched the way {@link StaffIdentityTest} does: names and emails as text, account IDs
 * value by value in the parsed JSON.
 */
class OnBehalfIdentityTest extends BareAppTest {

    /** A signed-in person named {@code "<name> <letters>"}, with an email made of the same words. */
    private Session person(Session admin, Role role, String name, String companyCode) {
        String unique = name + " " + letters(Unique.number());
        String email = unique.toLowerCase(Locale.ROOT).replace(' ', '.') + "@example.test";
        return fixtures.accept(fixtures.invite(admin, role, email, unique,
                companyCode == null ? List.of() : List.of(companyCode)));
    }

    @Test
    @DisplayName("S3-AC-29, S3-AC-36 the portal's list, detail, and edit answer for an issue an agent filed for the customer and an admin is assigned to hold no name, email, or account ID of either")
    void portalHoldsNoStaffOfAnIssueFiledOnBehalf() {
        IssueFlow flow = new IssueFlow(fixtures, clock);
        Session first = flow.admin();
        Company company = fixtures.createCompany(first);
        Session customer = person(first, Role.CUSTOMER, "Brindlemoss Okafor", company.code());
        Session colleague = person(first, Role.CUSTOMER, "Customer", company.code());
        Session agent = person(first, Role.AGENT, "Zyllith Quarnby", null);
        fixtures.assignAgent(first, company.code(), agent.accountId());
        Session admin = person(first, Role.ADMIN, "Vexmorrow Tallisk", null);
        Issue issue = flow.fileOnBehalf(agent, OnBehalf.of(company.code(), customer.accountId())
                .title("Printer jams on page feed " + letters(Unique.number())));
        flow.assign(agent, issue.key(), admin.accountId());
        JsonNode staffView = flow.detail(first, issue.key());
        assertThat(staffView.path("createdBy").path("accountId").asLong(-1)).as("createdBy on the console")
                .isEqualTo(agent.accountId());
        assertThat(staffView.path("assignee").path("accountId").asLong(-1)).as("assignee on the console")
                .isEqualTo(admin.accountId());

        Map<String, List<JsonNode>> answers = new LinkedHashMap<>();
        Map<String, String> texts = new LinkedHashMap<>();
        for (Session viewer : List.of(customer, colleague)) {
            String who = viewer == customer ? "the customer's" : "the colleague's";
            Pages list = allPages(viewer, "/api/portal/issues");
            assertThat(list.texts("key")).as("%s issue list", who).contains(issue.key());
            assertThat(list.text()).as("the customer's name in %s list", who).contains(customer.name());
            JsonNode detail = flow.detail(viewer, issue.key());
            assertThat(detail.path("filedBySupport").asBoolean(false)).as("filedBySupport in %s detail", who).isTrue();
            answers.put(who + " list", list.responses().stream().map(r -> r.json()).toList());
            texts.put(who + " list", list.text());
            answers.put(who + " detail", List.of(detail));
            texts.put(who + " detail", detail.toString());
        }
        JsonNode edited = flow.edit(customer, issue.key(), Map.of("priority", "HIGH"));
        answers.put("the edit answer", List.of(edited));
        texts.put("the edit answer", edited.toString());

        SoftAssertions softly = new SoftAssertions();
        for (Session staff : List.of(agent, admin)) {
            texts.forEach((what, body) -> {
                softly.assertThat(body).as(what + ": name of " + staff.role()).doesNotContainIgnoringCase(staff.name());
                softly.assertThat(body).as(what + ": email of " + staff.role())
                        .doesNotContainIgnoringCase(staff.email());
            });
            answers.forEach((what, nodes) -> nodes.forEach(node -> softly
                    .assertThat(placesOfAccountId(node, staff.accountId()))
                    .as(what + ": account ID " + staff.accountId() + " of " + staff.role()).isEmpty()));
        }
        softly.assertAll();
    }
}
