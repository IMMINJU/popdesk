package io.popdesk.manager;

import static io.popdesk.manager.ManagerSteps.ROW_ID;
import static io.popdesk.manager.ManagerSteps.invited;
import static io.popdesk.manager.ManagerSteps.mark;
import static io.popdesk.manager.ManagerSteps.members;
import static io.popdesk.manager.ManagerSteps.row;
import static io.popdesk.manager.ManagerSteps.rows;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps.Existing;
import io.popdesk.manager.ManagerSteps.ExistingKind;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Invitation;
import io.popdesk.support.Fixtures.Role;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/** The member list a manager reads on the portal: its rows, their order and pages, and what a row leaves out. */
class MemberListTest extends BareAppTest {

    private static final Set<String> ROW_FIELDS = Set.of("id", "name", "email", "status", "manager");

    /**
     * A company whose member list has five rows, with emails in the order of the record's fields: the
     * manager, a pending membership request, an active customer, a new account the manager invited,
     * and a suspended customer.
     */
    private record Scene(Session admin, Company company, Session manager, List<String> emails, String requestName,
            String invitedName, Session active, Session suspended) {}

    private Scene scene() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        List<String> emails = ManagerSteps.orderedEmails(5);
        Session manager = fixtures.accept(fixtures.invite(admin, Role.CUSTOMER, emails.get(0), Unique.text("Manager"),
                List.of(company.code())));
        mark(admin, company.code(), manager.accountId());
        fixtures.invite(admin, Role.CUSTOMER, emails.get(1), Unique.text("Elsewhere"), List.of(other.code()));
        Session active = fixtures.accept(fixtures.invite(admin, Role.CUSTOMER, emails.get(2), Unique.text("Active"),
                List.of(company.code())));
        Session suspended = fixtures.accept(fixtures.invite(admin, Role.CUSTOMER, emails.get(4),
                Unique.text("Suspended"), List.of(company.code())));
        ManagerSteps.suspend(admin, suspended.accountId());
        String requestName = Unique.text("Requested");
        String invitedName = Unique.text("Invited");
        invited(manager, company.code(), emails.get(1), requestName);
        invited(manager, company.code(), emails.get(3), invitedName);
        return new Scene(admin, company, manager, emails, requestName, invitedName, active, suspended);
    }

    private static String text(JsonNode row, String field) {
        return row.path(field).asString(null);
    }

    // S2-AC-08

    @Test
    @DisplayName("S2-AC-08 the member list gives each row's ID, name, email, status, and manager mark, in "
            + "ascending order of email")
    void rowsInEmailOrder() {
        Scene s = scene();

        List<JsonNode> rows = rows(s.manager(), s.company().code());

        assertThat(rows).extracting(r -> text(r, "email")).containsExactlyElementsOf(s.emails());
        assertThat(rows).extracting(r -> text(r, "name")).containsExactly(s.manager().name(), s.requestName(),
                s.active().name(), s.invitedName(), s.suspended().name());
        assertThat(rows).extracting(r -> text(r, "status")).containsExactly("ACTIVE", "INVITED", "ACTIVE", "INVITED",
                "SUSPENDED");
        assertThat(rows).allSatisfy(r -> assertThat(r.path("manager").isBoolean()).as("manager of " + r).isTrue());
        assertThat(rows).extracting(r -> r.path("manager").asBoolean(false)).containsExactly(true, false, false,
                false, false);
        assertThat(rows).allSatisfy(r -> assertThat(text(r, "id")).as("id of " + r).matches(ROW_ID));
        assertThat(rows).extracting(r -> text(r, "id")).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("S2-AC-08 the member list is paged (COM-4), and members and membership requests come as one "
            + "sequence across the pages")
    void pagesMixMembersAndRequests() {
        Scene s = scene();
        List<String> seen = new ArrayList<>();

        for (int page = 1; page <= 3; page++) {
            ApiResponse response = s.manager().client().get(members(s.company().code())).query("page", page)
                    .query("size", 2).expect(200);
            JsonNode body = response.json();
            assertThat(body.path("page").asInt(-1)).isEqualTo(page);
            assertThat(body.path("size").asInt(-1)).isEqualTo(2);
            assertThat(body.path("totalElements").asLong(-1)).isEqualTo(5);
            assertThat(body.path("totalPages").asInt(-1)).isEqualTo(3);
            assertThat(body.path("items")).as("items of page " + page).hasSize(page < 3 ? 2 : 1);
            body.path("items").forEach(r -> seen.add(text(r, "email")));
        }

        assertThat(seen).containsExactlyElementsOf(s.emails());
    }

    // S2-AC-10

    @Test
    @DisplayName("S2-AC-10 a row has only id, name, email, status, and manager: no account ID, photo, or "
            + "creation time, and nothing of the person's other company")
    void rowLeavesOutAccountData() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        String otherCode = Unique.companyCode();
        Company other = fixtures.createCompany(admin, otherCode, "Elsewhere-" + otherCode, "Elsewhere-" + otherCode
                + " Holdings");
        Session manager = ManagerSteps.manager(fixtures, admin, company.code());
        Session inBoth = fixtures.customer(admin, company.code(), other.code());
        Invitation pending = fixtures.invite(admin, Role.CUSTOMER, company.code(), other.code());
        Existing elsewhere = ExistingKind.CUSTOMER_OF_ANOTHER_COMPANY.make(fixtures, admin, other.code());
        invited(manager, company.code(), elsewhere.email(), Unique.text("Typed"));
        List<Long> accountIds = List.of(manager.accountId(), inBoth.accountId(), pending.accountId(),
                elsewhere.accountId());

        ApiResponse response = manager.client().get(members(company.code())).query("size", 100).expect(200);

        JsonNode items = response.json().path("items");
        assertThat(items).hasSize(4);
        for (JsonNode row : items) {
            assertThat(new TreeSet<>(row.propertyNames())).as("fields of " + row).isEqualTo(new TreeSet<>(ROW_FIELDS));
            for (JsonNode value : row) {
                assertThat(value.isNull()).as("a null field in " + row).isFalse();
                assertThat(value.isNumber()).as("a number in " + row).isFalse();
                assertThat(accountIds).as("account IDs, against the values of " + row)
                        .noneMatch(id -> Long.toString(id).equals(value.asString(null)));
            }
        }
        assertThat(response.text()).doesNotContain(other.code(), other.name(), other.legalName());
    }

    // S2-AC-12

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SUSPENDED", "ARCHIVED"})
    @DisplayName("S2-AC-12 the member list of a SUSPENDED or ARCHIVED company is still given to its manager")
    void listOfACompanyThatIsNotActive(String status) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session manager = ManagerSteps.manager(fixtures, admin, company.code());
        Session colleague = fixtures.customer(admin, company.code());
        ManagerSteps.setCompanyStatus(admin, company.code(), status);

        List<JsonNode> rows = rows(manager, company.code());

        assertThat(rows).extracting(r -> text(r, "email")).containsExactlyInAnyOrder(manager.email(),
                colleague.email());
    }

    // S2-AC-16

    @ParameterizedTest(name = "{0}")
    @EnumSource(ExistingKind.class)
    @DisplayName("S2-AC-16 a pending membership request is a row with the name and email the manager typed and "
            + "status INVITED, with the same fields and shape of values as the row of a new account")
    void requestRowLooksLikeANewAccountRow(ExistingKind kind) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Company other = fixtures.createCompany(admin);
        Session manager = ManagerSteps.manager(fixtures, admin, company.code());
        Existing existing = kind.make(fixtures, admin, other.code());
        String newEmail = Unique.email("new");
        String typedName = Unique.text("Typed");
        invited(manager, company.code(), newEmail, Unique.text("New"));
        invited(manager, company.code(), existing.email(), typedName);

        ApiResponse list = manager.client().get(members(company.code())).query("size", 100).expect(200);
        JsonNode ofNew = row(manager, company.code(), newEmail);
        JsonNode ofRequest = row(manager, company.code(), existing.email());

        assertThat(text(ofRequest, "name")).isEqualTo(typedName);
        assertThat(text(ofRequest, "email")).isEqualTo(existing.email());
        assertThat(list.text()).as("the member list").doesNotContain(existing.name());
        for (JsonNode row : List.of(ofNew, ofRequest)) {
            assertThat(new TreeSet<>(row.propertyNames())).as("fields of " + row).isEqualTo(new TreeSet<>(ROW_FIELDS));
            assertThat(row).as("values of " + row).noneMatch(JsonNode::isNull);
            assertThat(text(row, "id")).matches(ROW_ID);
            assertThat(text(row, "status")).isEqualTo("INVITED");
            assertThat(row.path("manager").isBoolean()).isTrue();
            assertThat(row.path("manager").asBoolean(true)).isFalse();
            assertThat(row.path("name").isString()).isTrue();
            assertThat(row.path("email").isString()).isTrue();
        }
        assertThat(text(ofRequest, "id").charAt(14)).as("UUID version of the request row's ID")
                .isEqualTo(text(ofNew, "id").charAt(14));
    }
}
