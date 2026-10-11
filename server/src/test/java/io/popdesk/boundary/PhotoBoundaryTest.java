package io.popdesk.boundary;

import static io.popdesk.boundary.BoundaryChecks.placesOfAccountId;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.photo.PhotoSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
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
 * The customer-company boundary on the portal endpoints slice 1a adds, on the two-company fixture
 * ({@link CompanyWorld}): a customer of A gets no name, email, or photo of a customer of B. The only
 * new portal endpoint that takes another account as its target is the photo; the others act on the
 * caller's own account, and their answers are searched for anything of B.
 */
class PhotoBoundaryTest extends BareAppTest {

    private CompanyWorld w;

    @BeforeEach
    void bothCompaniesHavePhotos() {
        w = CompanyWorld.build(fixtures);
        int seed = 500;
        for (Session customer : List.of(w.a().reporter(), w.a().colleague(), w.b().reporter(), w.b().colleague())) {
            PhotoSteps.uploadPng(customer, PhotoSteps.png(96, 96, seed++));
        }
    }

    @Test
    @DisplayName("S1A-AC-76 a customer of A asking for the photo of a customer of B gets the 404 of an account "
            + "that does not exist, while B's customers get those photos")
    void customerOfAGetsNoPhotoOfB() {
        for (Session caller : List.of(w.a().reporter(), w.a().colleague())) {
            for (Session target : List.of(w.b().reporter(), w.b().colleague())) {
                PhotoSteps.assertLikeNoAccount(PhotoSteps.photo(caller, target.accountId()), caller);
            }
        }

        PhotoSteps.decodePng(PhotoSteps.photo(w.b().colleague(), w.b().reporter().accountId()));
        PhotoSteps.decodePng(PhotoSteps.photo(w.a().colleague(), w.a().reporter().accountId()));
    }

    @Test
    @DisplayName("S1A-AC-76 the answers of the new portal endpoints about one's own account (Me after a change of "
            + "name, after a photo upload, and read again) hold no name, email, account ID, or company of B")
    void ownAccountAnswersHoldNothingOfB() {
        Session caller = w.a().reporter();
        Map<String, ApiResponse> answers = new LinkedHashMap<>();
        answers.put("PATCH me", caller.client().patch("/api/portal/me").json(Map.of("name", caller.name()))
                .expect(200));
        answers.put("POST me/photo", PhotoSteps.upload(caller, "photo.png", "image/png", PhotoSteps.png(96, 96, 510))
                .expectStatus(201));
        answers.put("GET me", caller.client().get("/api/portal/me").expect(200));

        SoftAssertions softly = new SoftAssertions();
        answers.forEach((what, answer) -> {
            JsonNode json = answer.json();
            softly.assertThat(json.path("companies").findValuesAsString("code")).as("%s: companies", what)
                    .containsExactly(w.a().code());
            List<String> texts = new ArrayList<>();
            collectTexts(json, texts);
            softly.assertThat(texts).as("%s: text values", what).contains(caller.email())
                    .doesNotContain(w.b().code(), w.b().company().name());
            for (Session other : List.of(w.b().reporter(), w.b().colleague())) {
                softly.assertThat(texts).as("%s: name and email of B's %s", what, other.email())
                        .doesNotContain(other.name(), other.email());
                softly.assertThat(placesOfAccountId(json, other.accountId()))
                        .as("%s: account ID %d of B", what, other.accountId()).isEmpty();
            }
        });
        softly.assertAll();
    }

    private static void collectTexts(JsonNode node, List<String> out) {
        if (node.isString()) {
            out.add(node.stringValue());
        }
        node.values().forEach(child -> collectTexts(child, out));
    }
}
