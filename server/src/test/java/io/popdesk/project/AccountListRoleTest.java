package io.popdesk.project;

import static io.popdesk.project.ProjectChecks.assertFieldError;
import static org.assertj.core.api.Assertions.assertThat;

import io.popdesk.support.ApiResponse;
import io.popdesk.support.BareAppTest;
import io.popdesk.support.Fixtures.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code role} filter of the account list (docs/integrate/openapi-console.json,
 * {@code listAccounts}): a value that is no role is 400 {@code INVALID_INPUT} with field {@code role}
 * {@code UNKNOWN_VALUE} (conventions.md, codes in {@code fields[].code}).
 */
class AccountListRoleTest extends BareAppTest {

    @Test
    @DisplayName("MUT ApiExceptionHandler an unknown role in the account list is 400 with field role UNKNOWN_VALUE, and a known one lists")
    void unknownRole() {
        Session admin = fixtures.signInAdmin();

        ApiResponse rejected = admin.client().get("/api/console/accounts").query("role", "NOSUCHROLE").send();
        ApiResponse admins = admin.client().get("/api/console/accounts").query("role", "ADMIN").expect(200);

        assertFieldError(rejected, 400, "role", "UNKNOWN_VALUE");
        assertThat(admins.json().path("items").findValuesAsString("role")).as("roles in the list of admins")
                .isNotEmpty().containsOnly("ADMIN");
    }
}
