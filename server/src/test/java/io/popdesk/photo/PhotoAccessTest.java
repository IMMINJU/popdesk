package io.popdesk.photo;

import static io.popdesk.photo.PhotoSteps.assertLikeNoAccount;
import static io.popdesk.photo.PhotoSteps.decodePng;
import static io.popdesk.photo.PhotoSteps.photo;
import static io.popdesk.photo.PhotoSteps.uploadPng;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Comment;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.File;
import io.popdesk.support.Fixtures.Issue;
import io.popdesk.support.Fixtures.Session;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Who gets whose profile photo (ACCT-4, ANON-1, ADR D-49). Company A has two customers and an
 * assigned agent, company B one customer; one customer belongs to both; there is a second admin and
 * an agent assigned nowhere. Everyone has a photo, so a 404 is never "no photo".
 */
class PhotoAccessTest extends BareAppTest {

    private Session admin;
    private Company a;
    private Company b;
    private Session alice;
    private Session colleague;
    private Session ofB;
    private Session ofBoth;
    private Session agentOfA;
    private Session agentNowhere;
    private Session secondAdmin;

    @BeforeEach
    void everyoneHasAPhoto() {
        admin = fixtures.signInAdmin();
        a = fixtures.createCompany(admin);
        b = fixtures.createCompany(admin);
        alice = fixtures.customer(admin, a.code());
        colleague = fixtures.customer(admin, a.code());
        ofB = fixtures.customer(admin, b.code());
        ofBoth = fixtures.customer(admin, a.code(), b.code());
        agentOfA = fixtures.agent(admin, a.code());
        agentNowhere = fixtures.agent(admin);
        secondAdmin = fixtures.admin(admin);
        int seed = 300;
        for (Session person : List.of(alice, colleague, ofB, ofBoth, agentOfA, agentNowhere, secondAdmin)) {
            uploadPng(person, PhotoSteps.png(96, 96, seed++));
        }
    }

    @Test
    @DisplayName("S1A-AC-25 a customer gets their own photo and the photo of a customer of the same company")
    void customerGetsOwnAndColleaguesPhoto() {
        Map<String, Session> allowed = new LinkedHashMap<>();
        allowed.put("own", alice);
        allowed.put("colleague", colleague);
        allowed.put("customer of A and B", ofBoth);

        SoftAssertions softly = new SoftAssertions();
        allowed.forEach((who, target) -> {
            ApiResponse response = photo(alice, target.accountId());
            softly.assertThat(response.status()).as("photo of %s: %s", who, response.snippet()).isEqualTo(200);
            softly.assertThat(response.body()).as("photo of %s is the one that account gets itself", who)
                    .isEqualTo(photo(target, target.accountId()).body());
        });
        softly.assertAll();
        decodePng(photo(alice, colleague.accountId()));
    }

    @Test
    @DisplayName("S1A-AC-25, S1A-AC-26 a customer asking for the photo of a customer of another company, of an "
            + "agent, or of an admin gets the same 404 as for an account that does not exist, body and headers")
    void otherPhotosAreLikeNoAccount() {
        Map<String, Session> hidden = new LinkedHashMap<>();
        hidden.put("customer of another company", ofB);
        hidden.put("agent assigned to the customer's company", agentOfA);
        hidden.put("agent assigned nowhere", agentNowhere);
        hidden.put("admin", secondAdmin);

        hidden.forEach((who, target) -> {
            assertThat(photo(target, target.accountId()).status()).as("%s has a photo", who).isEqualTo(200);
            assertLikeNoAccount(photo(alice, target.accountId()), alice);
        });
    }

    @Test
    @DisplayName("S1A-AC-26 no portal answer gives a staff photo: photoUpdatedAt is null on every SUPPORT_TEAM "
            + "person, while a colleague's is set, and no answer holds a photo address of staff")
    void portalAnswersHoldNoStaffPhoto() {
        Issue own = fixtures.fileIssue(alice, a.code());
        Issue colleagues = fixtures.fileIssue(colleague, a.code());
        for (Session staff : List.of(agentOfA, secondAdmin)) {
            Comment answer = fixtures.comment(staff, own.key(), "Answer from support.");
            fixtures.attachToComment(staff, own.key(), answer.id(), File.text("answer.txt", "Attached to the answer"));
            fixtures.attach(staff, own.key(), File.text("staff.txt", "From staff"));
        }

        List<ApiResponse> answers = new ArrayList<>();
        answers.add(alice.client().get("/api/portal/issues").query("size", 100).expect(200));
        for (String key : List.of(own.key(), colleagues.key())) {
            answers.add(alice.client().get("/api/portal/issues/" + key).expect(200));
            answers.add(alice.client().get("/api/portal/issues/" + key + "/comments").query("size", 100).expect(200));
            answers.add(alice.client().get("/api/portal/issues/" + key + "/attachments").query("size", 100)
                    .expect(200));
        }
        answers.add(alice.client().get("/api/portal/me").expect(200));
        answers.add(alice.client().get("/api/portal/companies").expect(200));

        List<JsonNode> people = new ArrayList<>();
        answers.forEach(r -> collectPeople(r.json(), people));
        List<JsonNode> support = people.stream().filter(p -> "SUPPORT_TEAM".equals(p.path("kind").asString(null)))
                .toList();
        assertThat(support).as("SUPPORT_TEAM people in the customer's answers").hasSizeGreaterThanOrEqualTo(4);
        assertThat(support).as("photoUpdatedAt of SUPPORT_TEAM people")
                .allSatisfy(p -> assertThat(p.has("photoUpdatedAt") && p.path("photoUpdatedAt").isNull())
                        .as("photoUpdatedAt is null in %s", p).isTrue());
        assertThat(people).as("the colleague, who has a photo, among the people")
                .anySatisfy(p -> {
                    assertThat(p.path("accountId").asLong(-1)).isEqualTo(colleague.accountId());
                    assertThat(p.path("photoUpdatedAt").isString()).as("photoUpdatedAt in %s", p).isTrue();
                });
        SoftAssertions softly = new SoftAssertions();
        for (ApiResponse answer : answers) {
            for (Session staff : List.of(agentOfA, secondAdmin)) {
                softly.assertThat(answer.text()).as("photo address of %s in %s", staff.role(), answer)
                        .doesNotContain("photos/" + staff.accountId());
            }
        }
        softly.assertAll();
    }

    /** Every JSON object that has a {@code kind} field: the portal's person type. */
    private static void collectPeople(JsonNode node, List<JsonNode> out) {
        if (node.isObject() && node.has("kind") && node.has("accountId")) {
            out.add(node);
        }
        node.values().forEach(child -> collectPeople(child, out));
    }

    @Test
    @DisplayName("S1A-AC-27 an agent gets the photos of staff and of customers of assigned companies, an admin "
            + "every photo; a customer only of companies the agent is not assigned to is the same 404 as no account")
    void staffGetPhotosOfAccountsTheySee() {
        Map<String, Session> forAgent = new LinkedHashMap<>();
        forAgent.put("own", agentOfA);
        forAgent.put("customer of the assigned company", alice);
        forAgent.put("customer of the assigned company and another", ofBoth);
        forAgent.put("another agent", agentNowhere);
        forAgent.put("admin", secondAdmin);

        SoftAssertions softly = new SoftAssertions();
        forAgent.forEach((who, target) -> {
            ApiResponse response = photo(agentOfA, target.accountId());
            softly.assertThat(response.status()).as("agent, photo of %s: %s", who, response.snippet()).isEqualTo(200);
            softly.assertThat(response.body()).as("agent, photo of %s", who)
                    .isEqualTo(photo(target, target.accountId()).body());
        });
        for (Session target : List.of(alice, ofB, agentOfA, agentNowhere)) {
            ApiResponse response = photo(secondAdmin, target.accountId());
            softly.assertThat(response.status()).as("admin, photo of %s: %s", target.email(), response.snippet())
                    .isEqualTo(200);
        }
        softly.assertAll();
        decodePng(photo(agentOfA, alice.accountId()));
        assertLikeNoAccount(photo(agentOfA, ofB.accountId()), agentOfA);
        assertLikeNoAccount(photo(agentNowhere, alice.accountId()), agentNowhere);
    }
}
