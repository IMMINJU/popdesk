package io.popdesk.privacy;

import static io.popdesk.issue.TalkFlow.assertRefused;
import static io.popdesk.privacy.PrivacySteps.anonymize;
import static io.popdesk.privacy.PrivacySteps.anonymousEmail;
import static io.popdesk.privacy.PrivacySteps.entriesOf;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.manager.ManagerSteps;
import io.popdesk.support.ApiResponse;
import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

/**
 * An anonymized customer in the console's customer list of a company and as its manager (b2 라-1): the list and the
 * answers of marking and unmarking carry {@code anonymized}, marking an anonymized customer is 400
 * {@code ACCOUNT_ANONYMIZED} and changes nothing, unmarking one who was a manager before is taken.
 *
 * <p>The anonymized accounts are made with the anonymize endpoint, so these tests run in {@link PrivacyAppTest}.
 */
class AnonymizedManagerTest extends PrivacyAppTest {

    /** The console's entry of the account in the company's customer list. */
    private static JsonNode entry(Session admin, String code, long accountId) {
        return ManagerSteps.customer(admin, code, accountId);
    }

    /** {@code anonymized} of an entry, which must be a boolean. */
    private static boolean anonymizedFlag(JsonNode entry) {
        assertThat(entry.path("anonymized").isBoolean()).as("anonymized is a boolean in %s", entry).isTrue();
        return entry.path("anonymized").booleanValue();
    }

    @Test
    @DisplayName("B2-AC-01 the customer list of a company gives anonymized true only for the anonymized customer, "
            + "with its anonymous name and email and SUSPENDED; a suspended and an active customer give false, as "
            + "does the answer of marking a customer")
    void listCarriesAnonymized() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session gone = fixtures.customer(admin, company.code());
        Session suspended = fixtures.customer(admin, company.code());
        Session active = fixtures.customer(admin, company.code());
        anonymize(admin, gone.accountId());
        ManagerSteps.suspend(admin, suspended.accountId());

        JsonNode marked = ManagerSteps.mark(admin, company.code(), active.accountId()).json();

        assertThat(anonymizedFlag(marked)).as("anonymized in the answer of marking %s", active.email()).isFalse();
        JsonNode anonymous = entry(admin, company.code(), gone.accountId());
        assertThat(anonymizedFlag(anonymous)).as("anonymized of the anonymized customer").isTrue();
        assertThat(anonymous.path("name").asString(null)).as("name of %s", anonymous).isEqualTo(ANONYMOUS_NAME);
        assertThat(anonymous.path("email").asString(null)).as("email of %s", anonymous)
                .isEqualTo(anonymousEmail(gone.accountId()));
        assertThat(anonymous.path("status").asString(null)).as("status of %s", anonymous).isEqualTo("SUSPENDED");
        JsonNode stopped = entry(admin, company.code(), suspended.accountId());
        assertThat(anonymizedFlag(stopped)).as("anonymized of the suspended customer").isFalse();
        assertThat(stopped.path("status").asString(null)).as("status of %s", stopped).isEqualTo("SUSPENDED");
        assertThat(anonymizedFlag(entry(admin, company.code(), active.accountId())))
                .as("anonymized of the active customer").isFalse();
    }

    @Test
    @DisplayName("B2-AC-02 marking an anonymized customer as manager is 400 ACCOUNT_ANONYMIZED: the customer stays "
            + "no manager and no audit entry about them is added")
    void markingAnonymizedIsRefused() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session gone = fixtures.customer(admin, company.code());
        anonymize(admin, gone.accountId());
        List<JsonNode> entriesBefore = entriesOf(admin, gone.accountId());

        ApiResponse refused = admin.client().put(ManagerSteps.managerOf(company.code(), gone.accountId())).send();

        assertRefused(refused, 400, "ACCOUNT_ANONYMIZED");
        JsonNode after = entry(admin, company.code(), gone.accountId());
        assertThat(ManagerSteps.managerFlag(after)).as("manager of %s after the refusal", after).isFalse();
        assertThat(entriesOf(admin, gone.accountId())).as("audit entries about account %d", gone.accountId())
                .isEqualTo(entriesBefore);
    }

    @Test
    @DisplayName("B2-AC-02 in an ARCHIVED company marking an anonymized customer is 400 ACCOUNT_ANONYMIZED, not "
            + "COMPANY_NOT_ACTIVE: the anonymized check comes before the archived one")
    void anonymizedComesBeforeArchived() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session gone = fixtures.customer(admin, company.code());
        anonymize(admin, gone.accountId());
        ManagerSteps.setCompanyStatus(admin, company.code(), "ARCHIVED");

        ApiResponse refused = admin.client().put(ManagerSteps.managerOf(company.code(), gone.accountId())).send();

        assertRefused(refused, 400, "ACCOUNT_ANONYMIZED");
        assertThat(ManagerSteps.managerFlag(entry(admin, company.code(), gone.accountId())))
                .as("manager of the anonymized customer of the archived company").isFalse();
    }

    @ParameterizedTest(name = "anonymized: {0}")
    @ValueSource(booleans = {true, false})
    @DisplayName("B2-AC-02 marking a customer of company B on company A's path is 404 NOT_FOUND, anonymized or not "
            + "(404 before 400), and B's entry stays no manager")
    void otherCompanyIsNotFound(boolean anonymized) {
        Session admin = fixtures.signInAdmin();
        Company a = fixtures.createCompany(admin);
        Company b = fixtures.createCompany(admin);
        Session ofB = fixtures.customer(admin, b.code());
        if (anonymized) {
            anonymize(admin, ofB.accountId());
        }

        ApiResponse refused = admin.client().put(ManagerSteps.managerOf(a.code(), ofB.accountId())).send();

        assertRefused(refused, 404, "NOT_FOUND");
        assertThat(ManagerSteps.customerIds(admin, a.code())).as("customers of %s", a.code())
                .doesNotContain(ofB.accountId());
        assertThat(ManagerSteps.managerFlag(entry(admin, b.code(), ofB.accountId())))
                .as("manager of the customer in %s", b.code()).isFalse();
    }

    @Test
    @DisplayName("B2-AC-03 a manager anonymized afterwards is listed as manager and anonymized; unmarking them is 200 "
            + "with anonymized true and manager false, and the list then shows no manager")
    void anonymizedManagerIsUnmarked() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session manager = ManagerSteps.manager(fixtures, admin, company.code());
        anonymize(admin, manager.accountId());
        JsonNode before = entry(admin, company.code(), manager.accountId());
        assertThat(ManagerSteps.managerFlag(before)).as("manager of %s before unmarking", before).isTrue();
        assertThat(anonymizedFlag(before)).as("anonymized of %s before unmarking", before).isTrue();

        JsonNode answer = ManagerSteps.unmark(admin, company.code(), manager.accountId()).json();

        assertThat(ManagerSteps.managerFlag(answer)).as("manager in the answer %s", answer).isFalse();
        assertThat(anonymizedFlag(answer)).as("anonymized in the answer %s", answer).isTrue();
        assertThat(ManagerSteps.managerFlag(entry(admin, company.code(), manager.accountId())))
                .as("manager of the anonymized customer after unmarking").isFalse();
    }
}
