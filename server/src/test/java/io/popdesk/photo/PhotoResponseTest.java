package io.popdesk.photo;

import static io.popdesk.photo.PhotoSteps.cacheControl;
import static io.popdesk.photo.PhotoSteps.photo;
import static io.popdesk.photo.PhotoSteps.uploadPng;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.App;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The headers of a photo answer and its revalidation (first-slice rule "no-store on every GET" has
 * this one exception): the browser may keep the photo but asks again every time, and the app checks
 * the caller's right again before it says "not changed".
 */
class PhotoResponseTest extends BareAppTest {

    private static void assertPhotoHeaders(ApiResponse response) {
        assertThat(cacheControl(response)).as("Cache-Control of %s", response).containsExactly("no-cache", "private");
        assertThat(response.header("ETag")).as("ETag of %s", response).isNotBlank();
    }

    @ParameterizedTest
    @EnumSource(App.class)
    @DisplayName("S1A-AC-87 a photo is answered as image/png with nosniff, Cache-Control private no-cache, and an "
            + "ETag; If-None-Match with that ETag is 304 without a body, with another value 200")
    void headersAndNotModified(App app) {
        Session admin = fixtures.signInAdmin();
        Session owner = app == App.PORTAL
                ? fixtures.customer(admin, fixtures.createCompany(admin).code())
                : fixtures.agent(admin);
        uploadPng(owner, PhotoSteps.png(200, 200, 400));

        ApiResponse first = photo(owner, owner.accountId());

        assertThat(first.status()).as("%s: %s", first, first.snippet()).isEqualTo(200);
        assertThat(first.header("Content-Type")).isEqualTo("image/png");
        assertThat(first.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertPhotoHeaders(first);
        String etag = first.header("ETag");

        ApiResponse same = owner.client().get(owner.api("/photos/" + owner.accountId()))
                .header("If-None-Match", etag).send();
        ApiResponse other = owner.client().get(owner.api("/photos/" + owner.accountId()))
                .header("If-None-Match", "\"not-the-etag\"").send();

        assertThat(same.status()).as("%s: %s", same, same.snippet()).isEqualTo(304);
        assertThat(same.body()).as("body of the 304").isEmpty();
        assertPhotoHeaders(same);
        assertThat(same.header("ETag")).as("ETag of the 304").isEqualTo(etag);
        assertThat(other.status()).as("%s: %s", other, other.snippet()).isEqualTo(200);
        assertThat(other.body()).isEqualTo(first.body());
    }

    @Test
    @DisplayName("S1A-AC-87 after the photo is replaced the old ETag gets 200 with the new photo and a new ETag")
    void replacedPhotoHasANewETag() {
        Session admin = fixtures.signInAdmin();
        Session owner = fixtures.customer(admin, fixtures.createCompany(admin).code());
        uploadPng(owner, PhotoSteps.png(200, 200, 401));
        ApiResponse first = photo(owner, owner.accountId()).expectStatus(200);
        clock.advance(Duration.ofSeconds(2));
        uploadPng(owner, PhotoSteps.png(200, 200, 402));

        ApiResponse again = owner.client().get(owner.api("/photos/" + owner.accountId()))
                .header("If-None-Match", first.header("ETag")).send();

        assertThat(again.status()).as("%s: %s", again, again.snippet()).isEqualTo(200);
        assertThat(again.header("ETag")).as("ETag of the new photo").isNotBlank().isNotEqualTo(first.header("ETag"));
        assertThat(again.body()).as("the new photo").isNotEqualTo(first.body());
    }

    @ParameterizedTest
    @EnumSource(App.class)
    @DisplayName("S1A-AC-87 the right to see the photo is checked before 304: once the viewer is taken out of the "
            + "company (a customer) or off it (an agent), the matching If-None-Match gets 404 with no-store")
    void rightIsCheckedBeforeNotModified(App app) {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session owner = fixtures.customer(admin, company.code());
        Session viewer = app == App.PORTAL
                ? fixtures.customer(admin, company.code())
                : fixtures.agent(admin, company.code());
        uploadPng(owner, PhotoSteps.png(200, 200, 403));
        String path = viewer.api("/photos/" + owner.accountId());
        String etag = viewer.client().get(path).expect(200).header("ETag");
        assertThat(etag).as("ETag").isNotBlank();
        viewer.client().get(path).header("If-None-Match", etag).expect(304);

        String member = app == App.PORTAL ? "customers" : "agents";
        admin.client().delete("/api/console/companies/" + company.code() + "/" + member + "/" + viewer.accountId())
                .expect(200);
        ApiResponse after = viewer.client().get(path).header("If-None-Match", etag).send();

        assertThat(after.status()).as("%s: %s", after, after.snippet()).isEqualTo(404);
        assertThat(after.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(cacheControl(after)).as("Cache-Control of the 404").contains("no-store").doesNotContain("private");
        assertThat(after.header("ETag")).as("ETag of the 404").isNull();
    }
}
