package io.popdesk.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.Fixtures.Company;
import io.popdesk.support.Fixtures.Session;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The account list narrowed by {@code anonymized} (S10A-AC-36), which the customer search of C-9 sends as
 * {@code false} so that an anonymized account is never offered for a company. Adding one anyway is the 400 of
 * S10A-AC-13, checked by the anonymization tests.
 */
class CustomerSearchTest extends PrivacyAppTest {

    private static List<Long> ids(List<JsonNode> accounts) {
        return accounts.stream().map(a -> a.path("id").asLong(-1)).toList();
    }

    @Test
    @DisplayName("S10A-AC-36 the customer search with anonymized=false leaves out an anonymized customer and keeps "
            + "one that is not; anonymized=true gives only the anonymized one, and without the value both are there")
    void searchLeavesOutAnonymizedAccounts() {
        Session admin = fixtures.signInAdmin();
        Company company = fixtures.createCompany(admin);
        Session gone = fixtures.customer(admin, company.code());
        Session kept = fixtures.customer(admin, company.code());
        PrivacySteps.anonymize(admin, gone.accountId());
        String anonymous = PrivacySteps.anonymousEmail(gone.accountId());

        List<Long> notAnonymized = ids(PrivacySteps.accounts(admin, "role", "CUSTOMER", "anonymized", false,
                "q", anonymous));
        List<Long> keptNotAnonymized = ids(PrivacySteps.accounts(admin, "role", "CUSTOMER", "anonymized", false,
                "q", kept.email()));
        List<Long> onlyAnonymized = ids(PrivacySteps.accounts(admin, "role", "CUSTOMER", "anonymized", true)).stream()
                .filter(id -> id == gone.accountId() || id == kept.accountId()).toList();
        List<Long> both = ids(PrivacySteps.accounts(admin, "role", "CUSTOMER", "q", anonymous));

        assertThat(both).as("customers found by the anonymous email without the value").contains(gone.accountId());
        assertThat(notAnonymized).as("customers found by the anonymous email with anonymized=false")
                .doesNotContain(gone.accountId());
        assertThat(keptNotAnonymized).as("customers found by the other email with anonymized=false")
                .containsExactly(kept.accountId());
        assertThat(onlyAnonymized).as("the two customers among anonymized=true").containsExactly(gone.accountId());
    }
}
