package io.popdesk.privacy;

import static io.popdesk.privacy.CompanyExportSteps.awaitEnded;
import static io.popdesk.privacy.CompanyExportSteps.body;
import static io.popdesk.privacy.CompanyExportSteps.done;
import static io.popdesk.privacy.CompanyExportSteps.exportsPath;
import static io.popdesk.privacy.CompanyExportSteps.filePath;
import static io.popdesk.privacy.CompanyExportSteps.id;
import static io.popdesk.privacy.CompanyExportSteps.list;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiClient;
import io.popdesk.support.ApiRequest;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import io.popdesk.support.Unique;
import io.popdesk.support.Wait;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The company export is for admins only (S10B-AC-10, ADR D-43): asking for one, the list, and the file ({@code GET}
 * and {@code HEAD}). Each refusal is checked to make no export and record no download, and then the admin's same call
 * succeeds, so that the refusal is the check and not a path that answers nobody.
 */
class CompanyExportAccessTest extends CompanyExportAppTest {

    /** A call of the slice. */
    enum Call {
        CREATE, LIST, FILE, HEAD;

        ApiRequest on(ApiClient client, String code, String exportId) {
            return switch (this) {
                case CREATE -> client.post(exportsPath(code)).json(body(false, false));
                case LIST -> client.get(exportsPath(code));
                case FILE -> client.get(filePath(exportId));
                case HEAD -> client.head(filePath(exportId));
            };
        }

        int success() {
            return this == CREATE ? 202 : 200;
        }
    }

    /** An admin, a company with an issue, and a DONE export of it. */
    private record Scene(Session admin, Company company, String exportId) {}

    private Scene scene() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        fixtures.fileIssue(fixtures.customer(admin, company.code()), company.code());
        return new Scene(admin, company, id(done(admin, company.code(), false, false)));
    }

    private static void assertRefused(ApiResponse answer, Call call, int status, String code) {
        assertThat(answer.status()).as("%s: %s", answer.request(), answer.snippet()).isEqualTo(status);
        if (call != Call.HEAD) {
            assertThat(answer.errorCode()).as("code of %s", answer.snippet()).isEqualTo(code);
        }
    }

    /** No export was made beside the scene's one, and no download was recorded. */
    private static void assertNothingChanged(Scene s) {
        assertThat(list(s.admin(), s.company().code())).extracting(CompanyExportSteps::id)
                .as("exports of %s", s.company().code()).containsExactly(s.exportId());
        assertThat(PrivacySteps.entries(s.admin(), "action", "COMPANY_EXPORT_DOWNLOADED", "targetCode",
                s.company().code())).as("download entries").isEmpty();
    }

    /** The admin's same call succeeds; an export it starts is waited for. */
    private static void assertAdminSucceeds(Scene s, Call call) {
        ApiResponse answer = Wait.until("the admin's " + call + " not refused as EXPORT_RUNNING", () -> {
            ApiResponse r = call.on(s.admin().client(), s.company().code(), s.exportId()).send();
            return r.status() == 409 ? null : r;
        });
        assertThat(answer.status()).as("the admin's %s: %s", answer.request(), answer.snippet())
                .isEqualTo(call.success());
        if (call == Call.CREATE) {
            awaitEnded(s.admin(), s.company().code(), id(answer.json()));
        }
    }

    @ParameterizedTest
    @EnumSource(Call.class)
    @DisplayName("S10B-AC-10 an agent assigned to the company gets 403 FORBIDDEN on asking, the list, and the file "
            + "(GET and HEAD), and nothing changes; the admin's same call succeeds")
    void agentsAreForbidden(Call call) {
        Scene s = scene();
        Session agent = fixtures.agent(s.admin(), s.company().code());

        ApiResponse answer = call.on(agent.client(), s.company().code(), s.exportId()).send();

        assertRefused(answer, call, 403, "FORBIDDEN");
        assertNothingChanged(s);
        assertAdminSucceeds(s, call);
    }

    @ParameterizedTest
    @EnumSource(Call.class)
    @DisplayName("S10B-AC-10 signed out, every export call answers 401 UNAUTHENTICATED on the console host and "
            + "nothing changes; the admin's same call succeeds")
    void signedOutGets401(Call call) {
        Scene s = scene();

        ApiResponse answer = call.on(console(), s.company().code(), s.exportId()).send();

        assertRefused(answer, call, 401, "UNAUTHENTICATED");
        assertNothingChanged(s);
        assertAdminSucceeds(s, call);
    }

    @ParameterizedTest
    @EnumSource(Call.class)
    @DisplayName("S10B-AC-10 a customer of the company on the portal host gets 404 NOT_FOUND for the console paths "
            + "(D-43) and nothing changes; the admin's same call succeeds")
    void customerHostGets404(Call call) {
        Scene s = scene();
        Session customer = fixtures.customer(s.admin(), s.company().code());

        ApiResponse answer = call.on(customer.client(), s.company().code(), s.exportId()).send();

        assertRefused(answer, call, 404, "NOT_FOUND");
        assertNothingChanged(s);
        assertAdminSucceeds(s, call);
    }

    @Test
    @DisplayName("S10B-AC-10 an admin's request for an export without the CSRF token, or with a wrong one, is 403 "
            + "CSRF_TOKEN_INVALID and makes no export; with the token it answers 202")
    void askingNeedsTheCsrfToken() {
        Scene s = scene();

        ApiResponse without = Call.CREATE.on(s.admin().client(), s.company().code(), null).withoutCsrf().send();
        ApiResponse wrong = Call.CREATE.on(s.admin().client(), s.company().code(), null).csrfToken("wrong").send();

        assertRefused(without, Call.CREATE, 403, "CSRF_TOKEN_INVALID");
        assertRefused(wrong, Call.CREATE, 403, "CSRF_TOKEN_INVALID");
        assertNothingChanged(s);
        assertAdminSucceeds(s, Call.CREATE);
    }

    @ParameterizedTest
    @EnumSource(value = Call.class, names = {"CREATE", "LIST"})
    @DisplayName("S10B-AC-10 asking for an export of, or listing, a company nobody has is 404 NOT_FOUND; the "
            + "company that exists answers")
    void unknownCompanyIsNotFound(Call call) {
        Scene s = scene();

        ApiResponse answer = call.on(s.admin().client(), Unique.companyCode(), null).send();

        assertRefused(answer, call, 404, "NOT_FOUND");
        assertNothingChanged(s);
        assertAdminSucceeds(s, call);
    }

    @ParameterizedTest
    @ValueSource(strings = {"random", "999999999", "not-an-id"})
    @DisplayName("S10B-AC-10 the file of an export nobody has, also by an ID that is not a UUID, is 404 NOT_FOUND "
            + "for GET and HEAD; the export that exists answers 200")
    void unknownExportIsNotFound(String which) {
        Scene s = scene();
        String id = which.equals("random") ? UUID.randomUUID().toString() : which;

        for (Call call : List.of(Call.FILE, Call.HEAD)) {
            assertRefused(call.on(s.admin().client(), s.company().code(), id).send(), call, 404, "NOT_FOUND");
        }
        assertNothingChanged(s);
        assertAdminSucceeds(s, Call.FILE);
    }
}
